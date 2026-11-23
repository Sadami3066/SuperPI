import { afterEach, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import type { ClientFrame, ServerFrame } from '@superpi/protocol'
import type { RemoteEventPayload, RemoteSessionStore } from '../src/main/relay/service'
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

type RouteHandler = (frame: Extract<ServerFrame, { type: 'route' }>, peer: Peer) => void

class Peer {
  readonly received: ServerFrame[] = []
  readonly routes: Array<Extract<ServerFrame, { type: 'route' }>> = []

  private constructor(
    readonly socket: WebSocket,
    readonly deviceId: string,
    private readonly onRoute: RouteHandler,
  ) {}

  static async connect(
    relay: RelayProcess,
    token: string,
    deviceId: string,
    onRoute: RouteHandler,
  ): Promise<Peer> {
    const socket = new WebSocket(`ws://127.0.0.1:${relay.port}/v1/ws`, {
      headers: { authorization: `Bearer ${token}` },
    })
    await new Promise<void>((resolve, reject) => {
      socket.once('open', () => resolve())
      socket.once('error', (error) => reject(error))
    })
    const peer = new Peer(socket, deviceId, onRoute)
    socket.on('message', (raw) => {
      const frame = JSON.parse(raw.toString()) as ServerFrame
      peer.received.push(frame)
      if (frame.type === 'route') {
        peer.routes.push(frame)
        peer.onRoute(frame, peer)
      }
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

  send(frame: ClientFrame): void {
    this.socket.send(JSON.stringify(frame))
  }

  respond(frame: Extract<ServerFrame, { type: 'route' }>, result: unknown, events: Array<{ seq: number; event: unknown }> = []): void {
    this.send({ type: 'route_ok', id: frame.id, result })
    for (const entry of events) {
      this.send({ type: 'route_event', id: frame.id, seq: entry.seq, event: entry.event as never })
    }
  }

  reject(frame: Extract<ServerFrame, { type: 'route' }>, code: 'session_not_found' | 'bad_request' | 'internal', message: string): void {
    this.send({ type: 'route_error', id: frame.id, code, message })
  }

  async waitForRoute(predicate: (frame: Extract<ServerFrame, { type: 'route' }>) => boolean, timeoutMs = 8_000): Promise<Extract<ServerFrame, { type: 'route' }>> {
    await waitFor(() => this.routes.some(predicate), timeoutMs)
    const frame = this.routes.find(predicate)
    if (!frame) throw new Error('route not received')
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

class MemoryRemoteSessionStore implements RemoteSessionStore {
  private readonly refs = new Map<string, { sessionId: string; afterSeq: number }>()

  find(deviceId: string, botId: string): { sessionId: string; afterSeq: number } | null {
    return this.refs.get(key(deviceId, botId)) ?? null
  }

  save(deviceId: string, botId: string, sessionId: string, afterSeq: number): void {
    this.refs.set(key(deviceId, botId), { sessionId, afterSeq })
  }

  clear(deviceId: string, botId: string): void {
    this.refs.delete(key(deviceId, botId))
  }

  clearAll(): void {
    this.refs.clear()
  }
}

function key(deviceId: string, botId: string): string {
  return `${deviceId}:${botId}`
}

function streamedText(events: RemoteEventPayload[]): string {
  return events
    .filter((payload) => payload.event.type === 'message_update')
    .map((payload) => {
      const update = (payload.event as { assistantMessageEvent?: { delta?: string } }).assistantMessageEvent
      return update?.delta ?? ''
    })
    .join('')
}

describe.skipIf(!hasRemoteRepo)('remote bot session persistence', () => {
  it('resumes a stored peer session with its event cursor', async () => {
    const relay = await startRelay()
    const enrollment = await enrollDevice(relay.port, relay.secret, 'My phone', 'android')
    const store = new MemoryRemoteSessionStore()
    const peer = await Peer.connect(relay, enrollment.token, enrollment.deviceId, (frame) => {
      if (frame.method === 'bot.prompt') {
        peer.respond(frame, { sessionId: 'peer-1' }, [
          { seq: 0, event: { type: 'agent_start' } },
          { seq: 1, event: { type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'one' } } },
          { seq: 2, event: { type: 'agent_end' } },
          { seq: 3, event: { type: 'agent_settled' } },
        ])
      }
    })
    peer.hello()
    await waitFor(() => peer.received.some((frame) => frame.type === 'hello_ok'), 10_000)

    const firstEvents: RemoteEventPayload[] = []
    const first = createService({ remoteSessions: store, onRemoteEvent: (payload) => firstEvents.push(payload) })
    try {
      await first.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop A' })
      await waitFor(
        () => first.status().state === 'online' && first.status().devices.some((entry) => entry.deviceId === enrollment.deviceId),
        8_000,
      )
      const session = first.startRemote({ deviceId: enrollment.deviceId, botId: 'pi' })
      expect(first.resumeRemote(session.sessionId)).toEqual({ resumed: false })
      first.promptRemote(session.sessionId, 'hello')
      await waitFor(() => firstEvents.some((payload) => payload.event.type === 'agent_settled'), 8_000)
      expect(streamedText(firstEvents)).toBe('one')
      expect(store.find(enrollment.deviceId, 'pi')).toEqual({ sessionId: 'peer-1', afterSeq: 3 })
    } finally {
      await first.dispose()
    }

    const secondEvents: RemoteEventPayload[] = []
    const second = createService({ remoteSessions: store, onRemoteEvent: (payload) => secondEvents.push(payload) })
    try {
      await second.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop B' })
      await waitFor(
        () => second.status().state === 'online' && second.status().devices.some((entry) => entry.deviceId === enrollment.deviceId && entry.online),
        8_000,
      )
      peer.routes.length = 0
      const session = second.startRemote({ deviceId: enrollment.deviceId, botId: 'pi' })
      expect(second.resumeRemote(session.sessionId)).toEqual({ resumed: true })
      const resume = await peer.waitForRoute((frame) => frame.method === 'bot.resume')
      expect(resume.params).toEqual({ sessionId: 'peer-1', afterSeq: 3 })
      peer.respond(resume, { sessionId: 'peer-1', busy: false }, [
        { seq: 4, event: { type: 'agent_start' } },
        { seq: 5, event: { type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'two' } } },
        { seq: 6, event: { type: 'agent_end' } },
        { seq: 7, event: { type: 'agent_settled' } },
      ])
      await waitFor(() => secondEvents.some((payload) => payload.event.type === 'agent_settled'), 8_000)
      expect(streamedText(secondEvents)).toBe('two')
      expect(store.find(enrollment.deviceId, 'pi')).toEqual({ sessionId: 'peer-1', afterSeq: 7 })
    } finally {
      await second.dispose()
    }
  }, 40_000)

  it('clears a stale session and starts fresh without an error dead-end', async () => {
    const relay = await startRelay()
    const enrollment = await enrollDevice(relay.port, relay.secret, 'My phone', 'android')
    const store = new MemoryRemoteSessionStore()
    const peer = await Peer.connect(relay, enrollment.token, enrollment.deviceId, (frame) => {
      if (frame.method === 'bot.resume') {
        peer.reject(frame, 'session_not_found', 'session gone is not active')
        return
      }
      if (frame.method === 'bot.prompt') {
        peer.respond(frame, { sessionId: 'peer-2' }, [
          { seq: 0, event: { type: 'agent_start' } },
          { seq: 1, event: { type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'fresh' } } },
          { seq: 2, event: { type: 'agent_settled' } },
        ])
      }
    })
    peer.hello()
    await waitFor(() => peer.received.some((frame) => frame.type === 'hello_ok'), 10_000)
    store.save(enrollment.deviceId, 'pi', 'gone', 5)

    const events: RemoteEventPayload[] = []
    const service = createService({ remoteSessions: store, onRemoteEvent: (payload) => events.push(payload) })
    try {
      await service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop' })
      await waitFor(
        () => service.status().state === 'online' && service.status().devices.some((entry) => entry.deviceId === enrollment.deviceId),
        8_000,
      )
      const session = service.startRemote({ deviceId: enrollment.deviceId, botId: 'pi' })
      expect(service.resumeRemote(session.sessionId)).toEqual({ resumed: true })
      await peer.waitForRoute((frame) => frame.method === 'bot.resume')
      await waitFor(() => store.find(enrollment.deviceId, 'pi') === null, 5_000)
      expect(events.some((payload) => payload.event.type === 'error')).toBe(false)

      service.promptRemote(session.sessionId, 'start over')
      const prompt = await peer.waitForRoute((frame) => frame.method === 'bot.prompt')
      expect(prompt.params.sessionId).toBeUndefined()
      await waitFor(() => events.some((payload) => payload.event.type === 'agent_settled'), 8_000)
      expect(streamedText(events)).toBe('fresh')
      expect(store.find(enrollment.deviceId, 'pi')).toEqual({ sessionId: 'peer-2', afterSeq: 2 })
    } finally {
      await service.dispose()
    }
  }, 40_000)

  it('retries a prompt without the stale session id when the remote lost it', async () => {
    const relay = await startRelay()
    const enrollment = await enrollDevice(relay.port, relay.secret, 'My phone', 'android')
    const store = new MemoryRemoteSessionStore()
    const peer = await Peer.connect(relay, enrollment.token, enrollment.deviceId, (frame) => {
      if (frame.method !== 'bot.prompt') return
      const params = frame.params as { sessionId?: string }
      if (params.sessionId === 'gone') {
        peer.reject(frame, 'session_not_found', 'session gone is not active')
        return
      }
      peer.respond(frame, { sessionId: 'peer-3' }, [
        { seq: 0, event: { type: 'agent_start' } },
        { seq: 1, event: { type: 'agent_settled' } },
      ])
    })
    peer.hello()
    await waitFor(() => peer.received.some((frame) => frame.type === 'hello_ok'), 10_000)
    store.save(enrollment.deviceId, 'pi', 'gone', 2)

    const service = createService({ remoteSessions: store })
    try {
      await service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop' })
      await waitFor(
        () => service.status().state === 'online' && service.status().devices.some((entry) => entry.deviceId === enrollment.deviceId),
        8_000,
      )
      const session = service.startRemote({ deviceId: enrollment.deviceId, botId: 'pi' })
      service.promptRemote(session.sessionId, 'hello again')
      await waitFor(() => peer.routes.filter((frame) => frame.method === 'bot.prompt').length === 2, 8_000)
      const prompts = peer.routes.filter((frame) => frame.method === 'bot.prompt')
      expect((prompts[0]?.params as { sessionId?: string }).sessionId).toBe('gone')
      expect((prompts[1]?.params as { sessionId?: string }).sessionId).toBeUndefined()
      await waitFor(() => store.find(enrollment.deviceId, 'pi')?.sessionId === 'peer-3', 8_000)
    } finally {
      await service.dispose()
    }
  }, 40_000)
})
