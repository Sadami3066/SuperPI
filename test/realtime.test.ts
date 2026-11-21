import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import WebSocket from 'ws'
import {
  PROTOCOL_VERSION,
  type BotInfo,
  type ClientFrame,
  type DeviceListResponse,
  type EnrollResponse,
  type ServerFrame,
} from '@superpi/protocol'
import { createRelayServer, type RelayServer } from '../src/server/http.ts'
import { createRealtimeHub, type RealtimeHub, type RealtimeHubOptions } from '../src/server/realtime.ts'
import { MemoryStore } from '../src/server/memory.ts'
import { RelayStore } from '../src/server/store.ts'
import { hashSecret, newAccountId } from '../src/server/auth.ts'
import type { RelayConfig } from '../src/server/config.ts'

const LOGIN_SECRET = 'test-login-secret'

interface Harness {
  relay: RelayServer
  hub: RealtimeHub
  store: RelayStore
  memory: MemoryStore
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

async function startRelay(options?: { now?: () => number; hubOptions?: RealtimeHubOptions }): Promise<Harness> {
  const storeDir = mkdtempSync(path.join(tmpdir(), 'superpi-realtime-'))
  const store = new RelayStore(storeDir)
  store.createAccount(newAccountId(), hashSecret(LOGIN_SECRET), options?.now?.() ?? Date.now())
  const memory = new MemoryStore(path.join(storeDir, 'memory'), store.database)
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
  const hub = createRealtimeHub(store, () => memory.version(), options?.now ?? Date.now, options?.hubOptions)
  const relay = createRelayServer({
    config,
    store,
    fingerprint: null,
    entries: [],
    memory,
    now: options?.now,
    onMemoryChanged: (version) => hub.broadcastMemoryChanged(version),
    onDeviceRevoked: (id) => hub.disconnectDevice(id),
    online: (deviceId) => hub.isOnline(deviceId),
    botsOf: (deviceId) => hub.botsOf(deviceId),
  })
  hub.attach(relay.server)
  const address = await relay.listen()
  const harness: Harness = {
    relay,
    hub,
    store,
    memory,
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

  static async open(url: string, token: string, wsOptions?: { autoPong?: boolean }): Promise<Client> {
    const socket = new WebSocket(url, {
      headers: { authorization: `Bearer ${token}` },
      autoPong: wsOptions?.autoPong,
    })
    await new Promise<void>((resolve, reject) => {
      socket.once('open', () => resolve())
      socket.once('error', (error) => reject(error))
    })
    const client = new Client(socket)
    clients.push(client)
    return client
  }

  drain(): ServerFrame[] {
    return this.queue.splice(0, this.queue.length)
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

async function nextOf<T extends ServerFrame['type']>(client: Client, type: T): Promise<Extract<ServerFrame, { type: T }>> {
  while (true) {
    const frame = await client.next()
    if (frame.type === type) return frame as Extract<ServerFrame, { type: T }>
  }
}

function hello(deviceId: string, bots: BotInfo[] = []): ClientFrame {
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

  it('keeps forwarding events after the route is accepted', async () => {
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
      id: 'req-stream',
      to: phone.deviceId,
      method: 'bot.prompt',
      params: { botId: 'pi', text: 'hello' },
    })
    const inbound = await phoneClient.next()
    expect(inbound).toMatchObject({ type: 'route', id: 'req-stream' })

    phoneClient.send({ type: 'route_ok', id: 'req-stream', result: { sessionId: 's1' } })
    const ok = await nextOf(desktopClient, 'route_ok')
    expect(ok).toMatchObject({ type: 'route_ok', id: 'req-stream', result: { sessionId: 's1' } })

    phoneClient.send({ type: 'route_event', id: 'req-stream', seq: 1, event: { type: 'agent_start' } })
    const first = await nextOf(desktopClient, 'route_event')
    expect(first).toMatchObject({ type: 'route_event', id: 'req-stream', seq: 1 })

    phoneClient.send({ type: 'route_event', id: 'req-stream', seq: 2, event: { type: 'agent_settled' } })
    const last = await nextOf(desktopClient, 'route_event')
    expect(last).toMatchObject({ type: 'route_event', id: 'req-stream', seq: 2 })

    phoneClient.send({ type: 'route_event', id: 'req-stream', seq: 3, event: { type: 'agent_start' } })
    const rejected = await nextOf(phoneClient, 'route_error')
    expect(rejected).toMatchObject({ type: 'route_error', id: 'req-stream', code: 'internal' })
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

  it('reports live memoryVersion and excludes revoked devices in hello_ok', async () => {
    const harness = await startRelay()
    const desktop = await enrollDevice(harness, 'Desktop', 'desktop')
    const revokedPhone = await enrollDevice(harness, 'Old Phone', 'android')

    harness.memory.write(
      { deviceId: desktop.deviceId, deviceName: 'Desktop' },
      { op: 'append', path: 'profile.md', text: 'One', idempotencyKey: 'm1' },
    )
    harness.memory.write(
      { deviceId: desktop.deviceId, deviceName: 'Desktop' },
      { op: 'append', path: 'profile.md', text: 'Two', idempotencyKey: 'm2' },
    )

    const revoke = await fetch(`${harness.httpBase}/v1/devices/${revokedPhone.deviceId}`, {
      method: 'DELETE',
      headers: { authorization: `Bearer ${desktop.token}` },
    })
    expect(revoke.status).toBe(200)

    const client = await Client.open(harness.wsBase, desktop.token)
    client.send(hello(desktop.deviceId))
    const helloOk = await nextOf(client, 'hello_ok')
    expect(helloOk.memoryVersion).toBe(2)
    expect(helloOk.devices.map((device) => device.deviceId)).toEqual([desktop.deviceId])
  })

  it('cleans up non-streaming routes immediately on route_ok while keeping bot.resume streaming', async () => {
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

    for (const [id, method] of [
      ['req-state', 'bot.state'],
      ['req-abort', 'bot.abort'],
    ] as const) {
      desktopClient.send({ type: 'route', id, to: phone.deviceId, method, params: { botId: 'pi' } })
      const inbound = await nextOf(phoneClient, 'route')
      expect(inbound).toMatchObject({ id, method })

      phoneClient.send({ type: 'route_ok', id, result: { ok: true } })
      const ok = await nextOf(desktopClient, 'route_ok')
      expect(ok).toMatchObject({ id, result: { ok: true } })

      phoneClient.send({ type: 'route_event', id, seq: 1, event: { type: 'agent_start' } })
      const rejected = await nextOf(phoneClient, 'route_error')
      expect(rejected).toMatchObject({ type: 'route_error', id, code: 'internal' })
    }

    desktopClient.send({ type: 'route', id: 'req-resume', to: phone.deviceId, method: 'bot.resume', params: { botId: 'pi' } })
    await nextOf(phoneClient, 'route')
    phoneClient.send({ type: 'route_ok', id: 'req-resume', result: { resumed: true } })
    await nextOf(desktopClient, 'route_ok')
    phoneClient.send({ type: 'route_event', id: 'req-resume', seq: 1, event: { type: 'agent_settled' } })
    const settled = await nextOf(desktopClient, 'route_event')
    expect(settled).toMatchObject({ id: 'req-resume', seq: 1 })
  })

  it('kicks active websockets with code 4001 and broadcasts offline presence on device revocation', async () => {
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
      id: 'req-revoke',
      to: phone.deviceId,
      method: 'bot.prompt',
      params: { botId: 'pi', text: 'hi' },
    })
    await nextOf(phoneClient, 'route')

    const closed = new Promise<{ code: number; reason: string }>((resolve) => {
      phoneClient.socket.once('close', (code, reason) => resolve({ code, reason: reason.toString('utf8') }))
    })

    const revoke = await fetch(`${harness.httpBase}/v1/devices/${phone.deviceId}`, {
      method: 'DELETE',
      headers: { authorization: `Bearer ${desktop.token}` },
    })
    expect(revoke.status).toBe(200)

    const closeInfo = await closed
    expect(closeInfo).toEqual({ code: 4001, reason: 'device revoked' })
    expect(harness.hub.isOnline(phone.deviceId)).toBe(false)

    const received = [await desktopClient.next(), await desktopClient.next()]
    expect(received).toContainEqual(
      expect.objectContaining({ type: 'presence', deviceId: phone.deviceId, online: false }),
    )
    expect(received).toContainEqual(
      expect.objectContaining({ type: 'route_error', id: 'req-revoke', code: 'device_offline' }),
    )
  })

  it('replaces duplicate connections cleanly without emitting false offline presence', async () => {
    const harness = await startRelay()
    const desktop = await enrollDevice(harness, 'Desktop', 'desktop')
    const phone = await enrollDevice(harness, 'Phone', 'android')

    const desktopClient = await Client.open(harness.wsBase, desktop.token)
    desktopClient.send(hello(desktop.deviceId))
    await desktopClient.next()

    const phone1 = await Client.open(harness.wsBase, phone.token)
    phone1.send(hello(phone.deviceId, [{ id: 'pi', name: 'PI' }]))
    await phone1.next()
    await nextOf(desktopClient, 'presence')

    const stray = await Client.open(harness.wsBase, phone.token)
    await stray.close()
    expect(harness.hub.isOnline(phone.deviceId)).toBe(true)

    desktopClient.send({
      type: 'route',
      id: 'req-dup',
      to: phone.deviceId,
      method: 'bot.prompt',
      params: { botId: 'pi', text: 'still alive' },
    })
    await nextOf(phone1, 'route')

    const phone1Closed = new Promise<{ code: number; reason: string }>((resolve) => {
      phone1.socket.once('close', (code, reason) => resolve({ code, reason: reason.toString('utf8') }))
    })

    const phone2 = await Client.open(harness.wsBase, phone.token)
    phone2.send(hello(phone.deviceId, [{ id: 'pi', name: 'PI v2' }]))
    await nextOf(phone2, 'hello_ok')

    const closeInfo = await phone1Closed
    expect(closeInfo).toEqual({ code: 1000, reason: 'replaced by new connection' })

    const presence = await nextOf(desktopClient, 'presence')
    expect(presence).toMatchObject({ deviceId: phone.deviceId, online: true, bots: [{ id: 'pi', name: 'PI v2' }] })
    expect(harness.hub.isOnline(phone.deviceId)).toBe(true)

    await new Promise((resolve) => setTimeout(resolve, 60))
    const extra = desktopClient.drain()
    expect(extra.some((frame) => frame.type === 'presence' && frame.deviceId === phone.deviceId && !frame.online)).toBe(false)

    phone2.send({ type: 'route_ok', id: 'req-dup', result: { survived: true } })
    const ok = await nextOf(desktopClient, 'route_ok')
    expect(ok).toMatchObject({ id: 'req-dup', result: { survived: true } })
  })

  it('terminates unresponsive stale sockets via server heartbeat while keeping active sockets alive', async () => {
    const harness = await startRelay({
      hubOptions: { heartbeatIntervalMs: 35, heartbeatTimeoutMs: 35 },
    })
    const staleDevice = await enrollDevice(harness, 'Stale', 'android')
    const healthyDevice = await enrollDevice(harness, 'Healthy', 'desktop')

    const staleClient = await Client.open(harness.wsBase, staleDevice.token, { autoPong: false })
    staleClient.send(hello(staleDevice.deviceId))
    await nextOf(staleClient, 'hello_ok')

    const healthyClient = await Client.open(harness.wsBase, healthyDevice.token, { autoPong: true })
    healthyClient.send(hello(healthyDevice.deviceId))
    await nextOf(healthyClient, 'hello_ok')

    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('stale client was not terminated')), 2_000)
      staleClient.socket.once('close', () => {
        clearTimeout(timer)
        resolve()
      })
    })

    expect(harness.hub.isOnline(staleDevice.deviceId)).toBe(false)
    expect(harness.hub.isOnline(healthyDevice.deviceId)).toBe(true)
    expect(healthyClient.socket.readyState).toBe(WebSocket.OPEN)
  })

  it('propagates bot avatars via hello_ok and presence and routes bot.sessions and bot.history cleanly', async () => {
    const harness = await startRelay()
    const desktop = await enrollDevice(harness, 'Desktop', 'desktop')
    const phone = await enrollDevice(harness, 'Phone', 'android')
    const avatar = 'data:image/jpeg;base64,AQIDBA=='

    const phoneClient = await Client.open(harness.wsBase, phone.token)
    phoneClient.send(hello(phone.deviceId, [{ id: 'pi', name: 'Phone PI', avatar }]))
    await nextOf(phoneClient, 'hello_ok')

    const desktopClient = await Client.open(harness.wsBase, desktop.token)
    desktopClient.send(hello(desktop.deviceId, [{ id: 'pi', name: 'Desktop PI' }]))
    const desktopHello = await nextOf(desktopClient, 'hello_ok')
    expect(desktopHello.devices.find((d) => d.deviceId === phone.deviceId)?.bots).toEqual([
      { id: 'pi', name: 'Phone PI', avatar },
    ])

    const phonePresence = await nextOf(phoneClient, 'presence')
    expect(phonePresence.deviceId).toBe(desktop.deviceId)

    const updatedAvatar = 'data:image/png;base64,iVBORw0K'
    phoneClient.send(hello(phone.deviceId, [{ id: 'pi', name: 'Phone PI', avatar: updatedAvatar }]))
    await nextOf(phoneClient, 'hello_ok')
    const updatedPresence = await nextOf(desktopClient, 'presence')
    expect(updatedPresence.bots).toEqual([{ id: 'pi', name: 'Phone PI', avatar: updatedAvatar }])

    desktopClient.send({
      type: 'route',
      id: 'req-sessions',
      to: phone.deviceId,
      method: 'bot.sessions',
      params: { botId: 'pi' },
    })
    const inboundSessions = await nextOf(phoneClient, 'route')
    expect(inboundSessions).toMatchObject({ id: 'req-sessions', method: 'bot.sessions', params: { botId: 'pi' } })
    phoneClient.send({
      type: 'route_ok',
      id: 'req-sessions',
      result: { sessions: [{ id: 's1', title: 'Morning sync', updatedAt: 1000 }] },
    })
    const sessionsOk = await nextOf(desktopClient, 'route_ok')
    expect(sessionsOk).toMatchObject({
      id: 'req-sessions',
      result: { sessions: [{ id: 's1', title: 'Morning sync', updatedAt: 1000 }] },
    })

    desktopClient.send({
      type: 'route',
      id: 'req-history',
      to: phone.deviceId,
      method: 'bot.history',
      params: { botId: 'pi', sessionId: 's1' },
    })
    const inboundHistory = await nextOf(phoneClient, 'route')
    expect(inboundHistory).toMatchObject({
      id: 'req-history',
      method: 'bot.history',
      params: { botId: 'pi', sessionId: 's1' },
    })
    phoneClient.send({
      type: 'route_ok',
      id: 'req-history',
      result: { sessionId: 's1', busy: false, messages: [] },
    })
    const historyOk = await nextOf(desktopClient, 'route_ok')
    expect(historyOk).toMatchObject({
      id: 'req-history',
      result: { sessionId: 's1', busy: false, messages: [] },
    })
  })
})
