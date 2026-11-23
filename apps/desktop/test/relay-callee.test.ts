import { afterEach, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { fileURLToPath } from 'node:url'
import type { ClientFrame, ServerFrame } from '@superpi/protocol'
import { PiRpcClient } from '../src/main/pi/rpc-client'
import type { RemoteBotHost } from '../src/main/relay/remote-server'
import {
  cleanupHarness,
  createService,
  enrollDevice,
  hasRemoteRepo,
  startRelay,
  tempDir,
  waitFor,
  type RelayProcess,
} from './helpers/relay-harness'

const fakePi = fileURLToPath(new URL('./fake-pi.mjs', import.meta.url))
const clients: PiRpcClient[] = []
const phones: Phone[] = []

afterEach(async () => {
  while (phones.length > 0) {
    const phone = phones.pop()
    if (phone) await phone.close()
  }
  while (clients.length > 0) {
    const client = clients.pop()
    if (client) await client.stop()
  }
  await cleanupHarness()
})

class Phone {
  readonly received: ServerFrame[] = []

  private constructor(readonly socket: WebSocket) {}

  static async connect(relay: RelayProcess, token: string, deviceId: string): Promise<Phone> {
    const socket = new WebSocket(`ws://127.0.0.1:${relay.port}/v1/ws`, {
      headers: { authorization: `Bearer ${token}` },
    })
    await new Promise<void>((resolve, reject) => {
      socket.once('open', () => resolve())
      socket.once('error', (error) => reject(error))
    })
    const phone = new Phone(socket)
    socket.on('message', (raw) => {
      phone.received.push(JSON.parse(raw.toString()) as ServerFrame)
    })
    phones.push(phone)
    return phone
  }

  hello(deviceId: string): void {
    this.socket.send(
      JSON.stringify({
        type: 'hello',
        protocolVersion: 1,
        deviceId,
        platform: 'android',
        appVersion: '0.1.0',
        bots: [{ id: 'phone-bot', name: 'Phone' }],
        capabilities: ['remote-bot'],
      }),
    )
  }

  call(to: string, id: string, method: string, params: Record<string, unknown>): void {
    this.socket.send(JSON.stringify({ type: 'route', id, to, method, params }))
  }

  async waitFor(predicate: (frame: ServerFrame) => boolean, timeoutMs = 8_000): Promise<ServerFrame> {
    await waitFor(() => this.received.some(predicate), timeoutMs)
    const frame = this.received.find(predicate)
    if (!frame) throw new Error('frame not found')
    return frame
  }

  close(): Promise<void> {
    if (this.socket.readyState === WebSocket.CLOSED) return Promise.resolve()
    return new Promise((resolve) => {
      this.socket.once('close', () => resolve())
      this.socket.close()
    })
  }
}

function fakePiHost(sessionId: string): RemoteBotHost {
  return {
    botIds: () => ['pi'],
    createClient: async () => {
      const client = new PiRpcClient({
        command: process.execPath,
        args: [fakePi],
        cwd: tempDir('superpi-callee-'),
        env: { ...process.env, FAKE_PI_SESSION_ID: sessionId, FAKE_PI_TOOL: '1' },
        requestTimeoutMs: 5_000,
      })
      clients.push(client)
      await client.start()
      return client
    },
  }
}

function eventsFor(phone: Phone, id: string): Array<Extract<ClientFrame, { type: 'route_event' }>> {
  return phone.received.filter(
    (frame): frame is Extract<ClientFrame, { type: 'route_event' }> => frame.type === 'route_event' && frame.id === id,
  )
}

describe.skipIf(!hasRemoteRepo)('remote agent server over the relay', () => {
  it('serves a phone prompt from a real pi subprocess and streams its events', async () => {
    const relay = await startRelay()
    const enrollment = await enrollDevice(relay.port, relay.secret, 'My phone', 'android')
    const phone = await Phone.connect(relay, enrollment.token, enrollment.deviceId)
    phone.hello(enrollment.deviceId)
    await phone.waitFor((frame) => frame.type === 'hello_ok')

    const service = createService({ host: fakePiHost('remote-session-1') })
    try {
      await service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop' })
      await waitFor(() => service.status().state === 'online', 8_000)
      const desktopId = service.status().deviceId
      if (!desktopId) throw new Error('desktop device id missing')

      phone.call(desktopId, 'call-1', 'bot.prompt', { botId: 'pi', text: 'hello from phone' })
      await phone.waitFor((frame) => frame.type === 'route_ok' && frame.id === 'call-1')
      const ok = phone.received.find((frame) => frame.type === 'route_ok' && frame.id === 'call-1')
      expect(ok).toEqual({ type: 'route_ok', id: 'call-1', result: { sessionId: 'remote-session-1' } })

      await phone.waitFor((frame) => frame.type === 'route_event' && frame.id === 'call-1' && frame.event.type === 'agent_settled')
      const streamed = eventsFor(phone, 'call-1')
      expect(streamed.map((frame) => frame.event.type)).toEqual([
        'agent_start',
        'tool_call',
        'tool_result',
        'message_update',
        'agent_end',
        'agent_settled',
      ])
      expect(streamed.map((frame) => frame.seq)).toEqual([0, 1, 2, 3, 4, 5])
      expect(streamed[3]?.event).toEqual({
        type: 'message_update',
        assistantMessageEvent: { type: 'text_delta', delta: 'hello from fake pi' },
      })
      expect(streamed[1]?.event).toMatchObject({ type: 'tool_call', callId: 'call_1', name: 'bash' })
      expect(streamed[2]?.event).toEqual({ type: 'tool_result', callId: 'call_1', result: '/home/fake', isError: false })

      phone.call(desktopId, 'call-2', 'bot.prompt', { botId: 'pi', text: 'again', sessionId: 'remote-session-1' })
      await phone.waitFor((frame) => frame.type === 'route_ok' && frame.id === 'call-2')
      await phone.waitFor((frame) => frame.type === 'route_event' && frame.id === 'call-2' && frame.event.type === 'agent_settled')
      expect(eventsFor(phone, 'call-2').map((frame) => frame.event.type)).toEqual([
        'agent_start',
        'tool_call',
        'tool_result',
        'message_update',
        'agent_end',
        'agent_settled',
      ])

      phone.call(desktopId, 'call-3', 'bot.prompt', { botId: 'missing', text: 'hi' })
      await phone.waitFor((frame) => frame.type === 'route_error' && frame.id === 'call-3')
      expect(phone.received.find((frame) => frame.type === 'route_error' && frame.id === 'call-3')).toMatchObject({
        code: 'bot_not_found',
      })

      phone.call(desktopId, 'call-4', 'bot.abort', { sessionId: 'remote-session-1' })
      await phone.waitFor((frame) => frame.type === 'route_ok' && frame.id === 'call-4')
      expect(phone.received.find((frame) => frame.type === 'route_ok' && frame.id === 'call-4')).toEqual({
        type: 'route_ok',
        id: 'call-4',
        result: { sessionId: 'remote-session-1' },
      })
    } finally {
      await service.dispose()
    }
  }, 30_000)
})
