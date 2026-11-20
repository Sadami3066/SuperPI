import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import WebSocket from 'ws'
import {
  PROTOCOL_VERSION,
  type ClientFrame,
  type DeviceListResponse,
  type EnrollResponse,
  type ServerFrame,
} from '@superpi/protocol'
import { createRelayServer, type RelayServer } from '../src/server/http.ts'
import { createRealtimeHub, type RealtimeHub } from '../src/server/realtime.ts'
import { RelayStore } from '../src/server/store.ts'
import { hashSecret, newAccountId } from '../src/server/auth.ts'
import type { RelayConfig } from '../src/server/config.ts'

const LOGIN_SECRET = 'test-login-secret'

interface Harness {
  relay: RelayServer
  hub: RealtimeHub
  store: RelayStore
  storeDir: string
  httpBase: string
  wsBase: string
}

const harnesses: Harness[] = []
const clients: Client[] = []

afterEach(async () => {
  while (clients.length > 0) {
    const client = clients.pop()
    if (client) await client.close()
  }
  while (harnesses.length > 0) {
    const harness = harnesses.pop()
    if (!harness) continue
    await harness.hub.close()
    await harness.relay.close()
    harness.store.close()
    rmSync(harness.storeDir, { recursive: true, force: true })
  }
})

async function startRelay(): Promise<Harness> {
  const storeDir = mkdtempSync(path.join(tmpdir(), 'superpi-realtime-'))
  const store = new RelayStore(storeDir)
  store.createAccount(newAccountId(), hashSecret(LOGIN_SECRET), Date.now())
  const config: RelayConfig = {
    host: '127.0.0.1',
    port: 0,
    dataDir: storeDir,
    certPath: null,
    keyPath: null,
    caPath: null,
    version: '0.1.0',
    entries: [],
  }
  const hub = createRealtimeHub(store)
  const relay = createRelayServer({
    config,
    store,
    fingerprint: null,
    entries: [],
    online: (deviceId) => hub.isOnline(deviceId),
    botsOf: (deviceId) => hub.botsOf(deviceId),
  })
  hub.attach(relay.server)
  const address = await relay.listen()
  const harness: Harness = {
    relay,
    hub,
    store,
    storeDir,
    httpBase: `http://127.0.0.1:${address.port}`,
    wsBase: `ws://127.0.0.1:${address.port}/v1/ws`,
  }
  harnesses.push(harness)
  return harness
}

async function enrollDevice(harness: Harness, name: string, platform: 'android' | 'desktop'): Promise<EnrollResponse> {
  const response = await fetch(`${harness.httpBase}/v1/pair/enroll`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ loginSecret: LOGIN_SECRET, deviceName: name, platform }),
  })
  expect(response.status).toBe(200)
  return (await response.json()) as EnrollResponse
}

class Client {
  readonly socket: WebSocket
  private readonly queue: ServerFrame[] = []
  private readonly waiters: Array<(frame: ServerFrame) => void> = []

  private constructor(socket: WebSocket) {
    this.socket = socket
    socket.on('message', (raw) => {
      const frame = JSON.parse(raw.toString()) as ServerFrame
      const waiter = this.waiters.shift()
      if (waiter) waiter(frame)
      else this.queue.push(frame)
    })
  }

  static async open(url: string, token: string): Promise<Client> {
    const socket = new WebSocket(url, { headers: { authorization: `Bearer ${token}` } })
    await new Promise<void>((resolve, reject) => {
      socket.once('open', () => resolve())
      socket.once('error', (error) => reject(error))
    })
    const client = new Client(socket)
    clients.push(client)
    return client
  }

  send(frame: ClientFrame): void {
    this.socket.send(JSON.stringify(frame))
  }

  next(timeoutMs = 3_000): Promise<ServerFrame> {
    const queued = this.queue.shift()
    if (queued) return Promise.resolve(queued)
    return new Promise<ServerFrame>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timed out waiting for a frame')), timeoutMs)
      this.waiters.push((frame) => {
        clearTimeout(timer)
        resolve(frame)
      })
    })
  }

  async close(): Promise<void> {
    if (this.socket.readyState === WebSocket.CLOSED) return
    await new Promise<void>((resolve) => {
      this.socket.once('close', () => resolve())
      this.socket.close()
    })
  }
}

function hello(deviceId: string, bots: Array<{ id: string; name: string }> = []): ClientFrame {
  return {
    type: 'hello',
    protocolVersion: PROTOCOL_VERSION,
    deviceId,
    platform: 'desktop',
    appVersion: '0.1.0',
    bots,
    capabilities: ['remote-bot'],
  }
}

describe('realtime hub', () => {
  it('completes the handshake and reports online devices', async () => {
    const harness = await startRelay()
    const first = await enrollDevice(harness, 'Desktop', 'desktop')
    const second = await enrollDevice(harness, 'Phone', 'android')

    const desktop = await Client.open(harness.wsBase, first.token)
    desktop.send(hello(first.deviceId, [{ id: 'pi', name: 'PI' }]))
    const helloOk = await desktop.next()
    expect(helloOk.type).toBe('hello_ok')
    if (helloOk.type !== 'hello_ok') throw new Error('unexpected frame')
    expect(helloOk.accountId).not.toBe('')
    expect(helloOk.devices).toHaveLength(2)
    expect(helloOk.devices.find((device) => device.deviceId === first.deviceId)?.online).toBe(true)
    expect(helloOk.devices.find((device) => device.deviceId === second.deviceId)?.online).toBe(false)

    const phone = await Client.open(harness.wsBase, second.token)
    phone.send(hello(second.deviceId, [{ id: 'pi', name: 'PI' }]))
    await phone.next()

    const presence = await desktop.next()
    expect(presence.type).toBe('presence')
    if (presence.type !== 'presence') throw new Error('unexpected frame')
    expect(presence).toMatchObject({ deviceId: second.deviceId, online: true })
    expect(presence.bots).toEqual([{ id: 'pi', name: 'PI' }])

    const list = (await (
      await fetch(`${harness.httpBase}/v1/devices`, { headers: { authorization: `Bearer ${first.token}` } })
    ).json()) as DeviceListResponse
    expect(list.devices.find((device) => device.deviceId === second.deviceId)?.online).toBe(true)
    expect(list.devices.find((device) => device.deviceId === first.deviceId)?.bots).toEqual([{ id: 'pi', name: 'PI' }])
  })

  it('forwards a routed request and its result', async () => {
    const harness = await startRelay()
    const desktop = await enrollDevice(harness, 'Desktop', 'desktop')
    const phone = await enrollDevice(harness, 'Phone', 'android')

    const desktopClient = await Client.open(harness.wsBase, desktop.token)
    desktopClient.send(hello(desktop.deviceId))
    await desktopClient.next()

    const phoneClient = await Client.open(harness.wsBase, phone.token)
    phoneClient.send(hello(phone.deviceId))
    await phoneClient.next()
    await desktopClient.next()

    desktopClient.send({
      type: 'route',
      id: 'req-1',
      to: phone.deviceId,
      method: 'bot.prompt',
      params: { botId: 'pi', text: 'hello' },
    })

    const inbound = await phoneClient.next()
    expect(inbound.type).toBe('route')
    if (inbound.type !== 'route') throw new Error('unexpected frame')
    expect(inbound).toMatchObject({ from: desktop.deviceId, to: phone.deviceId, method: 'bot.prompt' })
    expect(inbound.params).toEqual({ botId: 'pi', text: 'hello' })

    phoneClient.send({ type: 'route_event', id: 'req-1', seq: 1, event: { type: 'agent_start' } })
    const event = await desktopClient.next()
    expect(event).toMatchObject({ type: 'route_event', id: 'req-1', seq: 1 })

    phoneClient.send({ type: 'route_ok', id: 'req-1', result: { sessionId: 's1' } })
    const ok = await desktopClient.next()
    expect(ok).toMatchObject({ type: 'route_ok', id: 'req-1', result: { sessionId: 's1' } })
  })

  it('answers a route to an offline device with device_offline', async () => {
    const harness = await startRelay()
    const desktop = await enrollDevice(harness, 'Desktop', 'desktop')
    const phone = await enrollDevice(harness, 'Phone', 'android')

    const client = await Client.open(harness.wsBase, desktop.token)
    client.send(hello(desktop.deviceId))
    await client.next()

    client.send({ type: 'route', id: 'req-2', to: phone.deviceId, method: 'bot.prompt', params: {} })
    const error = await client.next()
    expect(error).toMatchObject({ type: 'route_error', id: 'req-2', code: 'device_offline' })
  })

  it('rejects a wrong protocol version during hello', async () => {
    const harness = await startRelay()
    const device = await enrollDevice(harness, 'Desktop', 'desktop')
    const client = await Client.open(harness.wsBase, device.token)
    client.send({ ...hello(device.deviceId), protocolVersion: PROTOCOL_VERSION + 1 } as ClientFrame)
    await new Promise<void>((resolve) => client.socket.once('close', () => resolve()))
    expect(client.socket.readyState).toBe(WebSocket.CLOSED)
  })

  it('rejects unauthenticated upgrades', async () => {
    const harness = await startRelay()
    await expect(Client.open(harness.wsBase, 'bad-token')).rejects.toThrow()
  })
})
