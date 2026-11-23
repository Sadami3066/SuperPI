import type { BotEvent, ClientFrame, InboundRouteFrame } from '@superpi/protocol'
import type { PiRpcEvent } from '../pi/rpc-client'

export interface RemoteBotClient {
  prompt(message: string, options?: { streamingBehavior?: 'steer' | 'followUp' }): Promise<unknown>
  steer(message: string): Promise<unknown>
  followUp(message: string): Promise<unknown>
  abort(): Promise<unknown>
  newSession(): Promise<unknown>
  getState<T = unknown>(): Promise<T>
  onEvent(listener: (event: PiRpcEvent) => void): () => void
  onExit(listener: (error: Error | null) => void): () => void
  stop(): Promise<void>
}

export interface RemoteBotHost {
  botIds(): string[]
  createClient(botId: string): Promise<RemoteBotClient>
}

export interface RemoteAgentServerOptions {
  host: RemoteBotHost
  send: (frame: ClientFrame) => void
  idleTimeoutMs?: number
}

interface RemoteSession {
  id: string
  botId: string
  client: RemoteBotClient
  routeId: string | null
  seq: number
  busy: boolean
  buffer: Array<{ seq: number; event: BotEvent }>
  unsubscribe: () => void
}

interface RemoteClientEntry {
  client: RemoteBotClient
  sessions: Set<RemoteSession>
  idleTimer: ReturnType<typeof setTimeout> | null
}

const MAX_BUFFERED_EVENTS = 200
export const DEFAULT_REMOTE_IDLE_TIMEOUT_MS = 10 * 60 * 1000

export class RemoteAgentServer {
  private readonly sessions = new Map<string, RemoteSession>()
  private readonly clients = new Map<string, RemoteClientEntry>()
  private readonly idleTimeoutMs: number

  constructor(private readonly options: RemoteAgentServerOptions) {
    this.idleTimeoutMs = options.idleTimeoutMs ?? DEFAULT_REMOTE_IDLE_TIMEOUT_MS
  }

  async handle(frame: InboundRouteFrame): Promise<void> {
    try {
      switch (frame.method) {
        case 'bot.prompt':
          await this.prompt(frame)
          break
        case 'bot.steer':
          await this.steer(frame)
          break
        case 'bot.follow_up':
          await this.followUp(frame)
          break
        case 'bot.abort':
          await this.abort(frame)
          break
        case 'bot.state':
          this.state(frame)
          break
        case 'bot.resume':
          this.resume(frame)
          break
        default:
          this.error(frame.id, 'bad_request', `unsupported method: ${frame.method}`)
      }
    } catch (failure) {
      this.error(frame.id, 'internal', failure instanceof Error ? failure.message : String(failure))
    }
  }

  async dispose(): Promise<void> {
    await this.closeAll()
  }

  async closeAll(): Promise<void> {
    const entries = [...this.clients.values()]
    this.clients.clear()
    for (const entry of entries) {
      if (entry.idleTimer) clearTimeout(entry.idleTimer)
      entry.idleTimer = null
    }
    for (const session of this.sessions.values()) session.unsubscribe()
    this.sessions.clear()
    await Promise.all(
      entries.map(async (entry) => {
        try {
          await entry.client.stop()
        } catch {
          return
        }
      }),
    )
  }

  private async prompt(frame: InboundRouteFrame): Promise<void> {
    const botId = stringParam(frame.params, 'botId').trim()
    const text = stringParam(frame.params, 'text')
    if (botId === '') {
      this.error(frame.id, 'bot_not_found', 'botId is required')
      return
    }
    if (!this.options.host.botIds().includes(botId)) {
      this.error(frame.id, 'bot_not_found', `bot ${botId} is not available`)
      return
    }
    if (text.trim() === '') {
      this.error(frame.id, 'bad_request', 'text is required')
      return
    }
    const requested = stringParam(frame.params, 'sessionId').trim()
    let session: RemoteSession | undefined
    if (requested !== '') {
      session = this.sessions.get(requested)
      if (!session) {
        this.error(frame.id, 'session_not_found', `session ${requested} is not active`)
        return
      }
      if (session.botId !== botId) {
        this.error(frame.id, 'bad_request', `session ${requested} belongs to another bot`)
        return
      }
      this.cancelIdle(botId)
    } else {
      if (this.findRunningSession(botId)) {
        this.error(frame.id, 'bad_request', `bot ${botId} is already running a remote session`)
        return
      }
      this.removeSessionsForBot(botId)
      const client = await this.ensureClient(botId)
      await client.newSession()
      const state = await client.getState<Record<string, unknown>>()
      const sessionId = typeof state.sessionId === 'string' && state.sessionId !== '' ? state.sessionId : `remote-${Date.now()}`
      session = this.createSession(sessionId, botId, client)
    }
    this.attachRoute(session, frame.id)
    const queued = session.busy
    session.busy = true
    this.ok(frame.id, { sessionId: session.id })
    try {
      await session.client.prompt(text, queued ? { streamingBehavior: 'followUp' } : undefined)
    } catch (failure) {
      session.busy = false
      this.emit(session, { type: 'error', message: messageOf(failure) })
      this.emit(session, { type: 'agent_settled' })
      this.scheduleIdle(botId)
    }
  }

  private async steer(frame: InboundRouteFrame): Promise<void> {
    const session = this.requireSession(frame)
    if (!session) return
    const text = stringParam(frame.params, 'text')
    if (text.trim() === '') {
      this.error(frame.id, 'bad_request', 'text is required')
      return
    }
    this.ok(frame.id, { sessionId: session.id })
    try {
      await session.client.steer(text)
    } catch (failure) {
      this.emit(session, { type: 'error', message: messageOf(failure) })
    }
  }

  private async followUp(frame: InboundRouteFrame): Promise<void> {
    const session = this.requireSession(frame)
    if (!session) return
    const text = stringParam(frame.params, 'text')
    if (text.trim() === '') {
      this.error(frame.id, 'bad_request', 'text is required')
      return
    }
    this.ok(frame.id, { sessionId: session.id })
    try {
      await session.client.followUp(text)
    } catch (failure) {
      this.emit(session, { type: 'error', message: messageOf(failure) })
    }
  }

  private async abort(frame: InboundRouteFrame): Promise<void> {
    const session = this.requireSession(frame)
    if (!session) return
    this.ok(frame.id, { sessionId: session.id })
    try {
      await session.client.abort()
    } catch (failure) {
      this.emit(session, { type: 'error', message: messageOf(failure) })
    }
    session.busy = false
    this.scheduleIdle(session.botId)
  }

  private state(frame: InboundRouteFrame): void {
    const sessionId = stringParam(frame.params, 'sessionId').trim()
    if (sessionId === '') {
      this.error(frame.id, 'bad_request', 'sessionId is required')
      return
    }
    const session = this.sessions.get(sessionId)
    this.ok(frame.id, { sessionId, busy: session?.busy ?? false })
  }

  private resume(frame: InboundRouteFrame): void {
    const session = this.requireSession(frame)
    if (!session) return
    const buffered = [...session.buffer]
    session.routeId = frame.id
    this.ok(frame.id, { sessionId: session.id, busy: session.busy })
    const afterSeq = numberParam(frame.params, 'afterSeq')
    if (afterSeq === null) return
    let lastSeq = session.seq - 1
    for (const entry of buffered) {
      if (entry.seq <= afterSeq) continue
      this.options.send({ type: 'route_event', id: frame.id, seq: entry.seq, event: entry.event })
      lastSeq = Math.max(lastSeq, entry.seq)
    }
    session.seq = lastSeq + 1
  }

  private requireSession(frame: InboundRouteFrame): RemoteSession | null {
    const sessionId = stringParam(frame.params, 'sessionId').trim()
    if (sessionId === '') {
      this.error(frame.id, 'bad_request', 'sessionId is required')
      return null
    }
    const session = this.sessions.get(sessionId)
    if (!session) {
      this.error(frame.id, 'session_not_found', `session ${sessionId} is not active`)
      return null
    }
    return session
  }

  private findRunningSession(botId: string): RemoteSession | undefined {
    for (const session of this.sessions.values()) {
      if (session.botId === botId && session.busy) return session
    }
    return undefined
  }

  private removeSessionsForBot(botId: string): void {
    const entry = this.clients.get(botId)
    for (const [id, session] of this.sessions) {
      if (session.botId !== botId) continue
      session.unsubscribe()
      this.sessions.delete(id)
      entry?.sessions.delete(session)
    }
  }

  private async ensureClient(botId: string): Promise<RemoteBotClient> {
    const existing = this.clients.get(botId)
    if (existing) {
      this.cancelIdle(botId)
      return existing.client
    }
    const client = await this.options.host.createClient(botId)
    const entry: RemoteClientEntry = { client, sessions: new Set(), idleTimer: null }
    this.clients.set(botId, entry)
    client.onExit(() => this.dropClient(botId, client))
    return client
  }

  private createSession(id: string, botId: string, client: RemoteBotClient): RemoteSession {
    const session: RemoteSession = {
      id,
      botId,
      client,
      routeId: null,
      seq: 0,
      busy: false,
      buffer: [],
      unsubscribe: () => undefined,
    }
    session.unsubscribe = client.onEvent((event) => this.onClientEvent(session, event))
    this.sessions.set(id, session)
    this.clients.get(botId)?.sessions.add(session)
    return session
  }

  private dropClient(botId: string, client: RemoteBotClient): void {
    const entry = this.clients.get(botId)
    if (!entry || entry.client !== client) return
    if (entry.idleTimer) clearTimeout(entry.idleTimer)
    this.clients.delete(botId)
    for (const [id, session] of this.sessions) {
      if (session.client !== client) continue
      session.unsubscribe()
      this.sessions.delete(id)
    }
  }

  private cancelIdle(botId: string): void {
    const entry = this.clients.get(botId)
    if (!entry?.idleTimer) return
    clearTimeout(entry.idleTimer)
    entry.idleTimer = null
  }

  private scheduleIdle(botId: string): void {
    const entry = this.clients.get(botId)
    if (!entry) return
    if (entry.idleTimer) clearTimeout(entry.idleTimer)
    if ([...entry.sessions].some((session) => session.busy)) return
    entry.idleTimer = setTimeout(() => {
      entry.idleTimer = null
      void this.closeClient(botId, entry)
    }, this.idleTimeoutMs)
    entry.idleTimer.unref?.()
  }

  private async closeClient(botId: string, entry: RemoteClientEntry): Promise<void> {
    if (this.clients.get(botId) !== entry) return
    this.clients.delete(botId)
    for (const session of entry.sessions) {
      session.unsubscribe()
      this.sessions.delete(session.id)
    }
    entry.sessions.clear()
    try {
      await entry.client.stop()
    } catch {
      return
    }
  }

  private attachRoute(session: RemoteSession, routeId: string): void {
    session.routeId = routeId
    session.seq = 0
    session.buffer = []
  }

  private onClientEvent(session: RemoteSession, event: PiRpcEvent): void {
    for (const mapped of mapPiEvent(event)) this.emit(session, mapped)
    if (event.type !== 'agent_settled') return
    session.busy = false
    this.scheduleIdle(session.botId)
  }

  private emit(session: RemoteSession, event: BotEvent): void {
    if (session.routeId === null) return
    const seq = session.seq
    session.seq += 1
    session.buffer.push({ seq, event })
    if (session.buffer.length > MAX_BUFFERED_EVENTS) session.buffer.shift()
    this.options.send({ type: 'route_event', id: session.routeId, seq, event })
  }

  private ok(id: string, result: unknown): void {
    this.options.send({ type: 'route_ok', id, result })
  }

  private error(id: string, code: 'bad_request' | 'bot_not_found' | 'session_not_found' | 'internal', message: string): void {
    this.options.send({ type: 'route_error', id, code, message })
  }
}

export function mapPiEvent(event: PiRpcEvent): BotEvent[] {
  switch (event.type) {
    case 'agent_start':
      return [{ type: 'agent_start' }]
    case 'message_update': {
      const update = event.assistantMessageEvent
      if (!isRecord(update) || typeof update.delta !== 'string') return []
      if (update.type === 'text_delta') {
        return [{ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: update.delta } }]
      }
      if (update.type === 'thinking_delta') {
        return [{ type: 'message_update', assistantMessageEvent: { type: 'reasoning_delta', delta: update.delta } }]
      }
      return []
    }
    case 'tool_execution_start': {
      const callId = stringField(event.toolCallId)
      const name = stringField(event.toolName)
      if (callId === '' || name === '') return []
      return [event.args === undefined ? { type: 'tool_call', callId, name } : { type: 'tool_call', callId, name, args: event.args }]
    }
    case 'tool_execution_end': {
      const callId = stringField(event.toolCallId)
      if (callId === '') return []
      return [{ type: 'tool_result', callId, result: resultText(event.result), isError: event.isError === true }]
    }
    case 'agent_end':
      return [{ type: 'agent_end' }]
    case 'agent_settled':
      return [{ type: 'agent_settled' }]
    case 'error': {
      const message = stringField(event.message)
      return [{ type: 'error', message: message === '' ? 'the bot reported an error' : message }]
    }
    default:
      return []
  }
}

export function resultText(result: unknown): string {
  if (typeof result === 'string') return result
  if (!isRecord(result)) return ''
  const content = result.content
  if (!Array.isArray(content)) return ''
  const parts: string[] = []
  for (const block of content) {
    if (isRecord(block) && block.type === 'text' && typeof block.text === 'string') parts.push(block.text)
  }
  return parts.join('\n')
}

function stringParam(params: Record<string, unknown>, key: string): string {
  const value = params[key]
  return typeof value === 'string' ? value : ''
}

function numberParam(params: Record<string, unknown>, key: string): number | null {
  const value = params[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function stringField(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function messageOf(failure: unknown): string {
  return failure instanceof Error ? failure.message : String(failure)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
