import { afterEach, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import type { ClientFrame, ServerFrame } from '@superpi/protocol'
import type { RemoteEventPayload } from '../src/main/relay/service'
import {
  cleanupHarness,
  createService,
  enrollDevice,
  hasRemoteRepo,
  startRelay,
  waitFor,
  type RelayProcess,
} from './helpers/relay-harness'

const peers: Peer[] = []

afterEach(async () => {
  while (peers.length > 0) {
    const peer = peers.pop()
    if (peer) await peer.close()
  }
  await cleanupHarness()
})

class Peer {
  readonly received: ServerFrame[] = []
  readonly routes: ClientFrame[] = []

  private constructor(
    readonly socket: WebSocket,
    readonly deviceId: string,
  ) {}

  static async connect(relay: RelayProcess, token: string, deviceId: string): Promise<Peer> {
    const socket = new WebSocket(`ws://127.0.0.1:${relay.port}/v1/ws`, {
      headers: { authorization: `Bearer ${token}` },
    })
    await new Promise<void>((resolve, reject) => {
      socket.once('open', () => resolve())
      socket.once('error', (error) => reject(error))
    })
    const peer = new Peer(socket, deviceId)
    socket.on('message', (raw) => {
      const frame = JSON.parse(raw.toString()) as ServerFrame
      peer.received.push(frame)
      if (frame.type === 'route') peer.handleRoute(frame)
    })
    peers.push(peer)
    return peer
  }

  hello(): void {
    this.socket.send(
      JSON.stringify({
        type: 'hello',
        protocolVersion: 1,
        deviceId: this.deviceId,
        platform: 'android',
        appVersion: '0.1.0',
        bots: [{ id: 'pi', name: 'PI' }],
        capabilities: ['remote-bot'],
      }),
    )
  }

  async waitForFrame(predicate: (frame: ServerFrame) => boolean, timeoutMs = 5_000): Promise<ServerFrame> {
    await waitFor(() => this.received.some(predicate), timeoutMs)
    const found = this.received.find(predicate)
    if (!found) throw new Error('frame not found')
    return found
  }

  close(): Promise<void> {
    if (this.socket.readyState === WebSocket.CLOSED) return Promise.resolve()
    return new Promise((resolve) => {
      this.socket.once('close', () => resolve())
      this.socket.close()
    })
  }

  private handleRoute(frame: Extract<ServerFrame, { type: 'route' }>): void {
    this.routes.push(frame)
    if (frame.method !== 'bot.prompt') return
    const reply = (event: unknown): void => {
      this.socket.send(JSON.stringify({ type: 'route_event', id: frame.id, seq: this.routes.length, event }))
    }
    reply({ type: 'agent_start' })
    reply({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'remote reply' } })
    reply({ type: 'agent_settled' })
    this.socket.send(JSON.stringify({ type: 'route_ok', id: frame.id, result: { sessionId: 'peer-session-1' } }))
  }
}

describe.skipIf(!hasRemoteRepo)('remote bot sessions', () => {
  it('streams events from another device through the relay', async () => {
    const relay = await startRelay()
    const peerEnrollment = await enrollDevice(relay.port, relay.secret, 'My phone', 'android')
    const peer = await Peer.connect(relay, peerEnrollment.token, peerEnrollment.deviceId)
    peer.hello()
    await peer.waitForFrame((frame) => frame.type === 'hello_ok')

    const events: RemoteEventPayload[] = []
    const service = createService({ onRemoteEvent: (payload) => events.push(payload) })
    try {
      await service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop' })
      await waitFor(
        () =>
          service.status().state === 'online' &&
          service.status().devices.some((device) => device.deviceId === peerEnrollment.deviceId && device.online),
        8_000,
      )

      const session = service.startRemote({ deviceId: peerEnrollment.deviceId, botId: 'pi' })
      service.promptRemote(session.sessionId, 'hello phone')

      await waitFor(() => events.some((payload) => payload.event.type === 'agent_settled'), 8_000)
      const text = events
        .filter((payload) => payload.event.type === 'message_update')
        .map((payload) => {
          const update = (payload.event as { assistantMessageEvent?: { delta?: string } }).assistantMessageEvent
          return update?.delta ?? ''
        })
        .join('')
      expect(text).toBe('remote reply')
      expect(events.every((payload) => payload.sessionId === session.sessionId)).toBe(true)

      const routed = peer.routes.find((frame) => frame.type === 'route')
      expect(routed).toMatchObject({ to: peerEnrollment.deviceId, method: 'bot.prompt' })
      if (routed?.type === 'route') expect(routed.params).toEqual({ botId: 'pi', text: 'hello phone' })
    } finally {
      service.dispose()
    }
  }, 25_000)

  it('refuses to start a session on an offline device', async () => {
    const relay = await startRelay()
    const service = createService()
    try {
      await service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop' })
      await waitFor(() => service.status().state === 'online', 8_000)
      expect(() => service.startRemote({ deviceId: 'dev-missing', botId: 'pi' })).toThrow(/offline/)
    } finally {
      service.dispose()
    }
  }, 20_000)
})
