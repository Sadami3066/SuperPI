import os from 'node:os'
import path from 'node:path'
import type {
  BotEvent,
  BotInfo,
  ClientFrame,
  DeviceInfo,
  EnrollResponse,
  RouteMethod,
  ServerFrame,
} from '@superpi/protocol'
import { RelayCredentialStore, type RelayCredentials } from './credentials.ts'
import { RelayClient, type RelayConnectionState } from './relay-client.ts'
import { fetchCaPem, fingerprintOfPem, relayRequest } from './http.ts'
import { parsePairingLink } from './pairing.ts'
import { MemoryClient, type MemorySummary } from './memory-client.ts'
import { materializeRelayCa, removeRelayCa } from './ca-cache.ts'
import { RemoteAgentServer, type RemoteBotHost } from './remote-server.ts'

export type RelayState = 'disconnected' | 'connecting' | 'online' | 'offline' | 'error'

export interface RelayStatus {
  state: RelayState
  deviceId?: string
  origin?: string
  deviceName?: string
  accountId?: string
  devices: DeviceInfo[]
  error?: string
}

export interface PairInput {
  link: string
  loginSecret?: string
  deviceName?: string
}

export interface RemoteSessionStore {
  find(deviceId: string, botId: string): { sessionId: string; afterSeq: number } | null
  save(deviceId: string, botId: string, sessionId: string, afterSeq: number): void
  clear(deviceId: string, botId: string): void
  clearAll(): void
}

export interface RelayServiceOptions {
  homeDir: string
  bots: () => BotInfo[]
  host?: RemoteBotHost
  remoteIdleTimeoutMs?: number
  remoteSessions?: RemoteSessionStore
  broadcast: (status: RelayStatus) => void
  broadcastMemory?: (summary: MemorySummary | null) => void
  onRemoteEvent?: (payload: RemoteEventPayload) => void
}

export interface RemoteEventPayload {
  sessionId: string
  deviceId: string
  botId: string
  event: BotEvent
}

export interface RemoteSessionInfo {
  sessionId: string
  deviceId: string
  botId: string
  peerSessionId: string | null
  lastSeq: number
}

export class RelayService {
  private readonly store: RelayCredentialStore
  private credentials: RelayCredentials | null
  private client: RelayClient | null = null
  private devices: DeviceInfo[] = []
  private state: RelayState = 'disconnected'
  private error: string | undefined
  private readonly remoteSessions = new Map<string, RemoteSessionInfo>()
  private readonly remoteRoutes = new Map<string, string>()
  private readonly remoteRequests = new Map<string, { method: RouteMethod; params: Record<string, unknown> }>()
  private remoteCounter = 0
  private readonly memoryClient: MemoryClient
  private readonly remoteServer: RemoteAgentServer | null
  private memorySummary: MemorySummary | null = null
  private memorySync: Promise<void> | null = null

  constructor(private readonly options: RelayServiceOptions) {
    this.store = new RelayCredentialStore(options.homeDir)
    this.credentials = this.store.read()
    this.memoryClient = new MemoryClient(options.homeDir)
    this.memorySummary = this.memoryClient.cached()
    this.remoteServer = options.host
      ? new RemoteAgentServer({
          host: options.host,
          send: (frame: ClientFrame) => {
            if (this.client?.connected) this.client.send(frame)
          },
          idleTimeoutMs: options.remoteIdleTimeoutMs,
        })
      : null
    if (this.credentials) this.startClient()
  }

  memoryStatus(): MemorySummary | null {
    return this.memorySummary
  }

  memoryEnvironment(): Record<string, string> {
    const env: Record<string, string> = {
      SUPERPI_MEMORY_FILE: path.join(this.options.homeDir, 'cache', 'memory', 'snapshot.json'),
    }
    const credentials = this.credentials
    if (credentials) {
      env.SUPERPI_RELAY_ENDPOINT = credentials.origin
      env.SUPERPI_RELAY_TOKEN = credentials.token
      if (credentials.caPem !== null) {
        try {
          env.NODE_EXTRA_CA_CERTS = materializeRelayCa(this.options.homeDir, credentials.caPem)
        } catch (failure) {
          console.error(`relay CA is not available for pi: ${failure instanceof Error ? failure.message : String(failure)}`)
        }
      }
    }
    return env
  }

  status(): RelayStatus {
    const credentials = this.credentials
    return {
      state: this.state,
      deviceId: credentials?.deviceId,
      origin: credentials?.origin,
      deviceName: credentials?.deviceName,
      accountId: credentials?.accountId,
      devices: this.devices,
      error: this.error,
    }
  }

  async pair(input: PairInput): Promise<RelayStatus> {
    const parsed = parsePairingLink(input.link)
    if (!parsed) throw new Error('That does not look like a pairing link')
    const loginSecret = input.loginSecret?.trim()
    if (!parsed.pairCode && !loginSecret) {
      throw new Error('A pairing link with a code or a login secret is required')
    }

    const secure = parsed.origin.startsWith('https:')
    let caPem: string | null = null
    if (secure) {
      if (!parsed.fingerprint) throw new Error('The pairing link is missing the server fingerprint')
      const pem = await fetchCaPem(parsed.origin)
      const actual = fingerprintOfPem(pem)
      if (actual !== parsed.fingerprint) {
        throw new Error('The server certificate does not match the pairing link')
      }
      caPem = pem
    }

    const deviceName = input.deviceName?.trim() || os.hostname()
    const response = await relayRequest({
      url: `${parsed.origin}/v1/pair/enroll`,
      method: 'POST',
      caPem,
      allowInsecure: false,
      body: {
        ...(parsed.pairCode ? { pairCode: parsed.pairCode } : {}),
        ...(loginSecret ? { loginSecret } : {}),
        deviceName,
        platform: 'desktop',
        appVersion: '0.1.0',
      },
    })
    if (response.status !== 200) {
      throw new Error(describeFailure(response.status, response.body))
    }
    const enrolled = JSON.parse(response.body) as EnrollResponse
    const credentials: RelayCredentials = {
      origin: parsed.origin,
      caPem,
      deviceId: enrolled.deviceId,
      token: enrolled.token,
      deviceName,
      accountId: enrolled.accountId,
      enrolledAt: Date.now(),
    }
    this.credentials = credentials
    this.store.save(credentials)
    this.devices = []
    this.error = undefined
    this.client?.disconnect()
    this.client = null
    this.startClient()
    return this.status()
  }

  startRemote(input: { deviceId: string; botId: string }): RemoteSessionInfo {
    const device = this.devices.find((entry) => entry.deviceId === input.deviceId)
    if (!device || !device.online) throw new Error('That device is offline')
    if (!device.bots.some((bot) => bot.id === input.botId)) throw new Error('That bot is not available')
    const stored = this.options.remoteSessions?.find(input.deviceId, input.botId) ?? null
    this.remoteCounter += 1
    const session: RemoteSessionInfo = {
      sessionId: `rs-${this.remoteCounter}`,
      deviceId: input.deviceId,
      botId: input.botId,
      peerSessionId: stored?.sessionId ?? null,
      lastSeq: stored?.afterSeq ?? 0,
    }
    this.remoteSessions.set(session.sessionId, session)
    return session
  }

  resumeRemote(sessionId: string): { resumed: boolean } {
    const session = this.remoteSessions.get(sessionId)
    if (!session) throw new Error(`unknown remote session: ${sessionId}`)
    const peerSessionId = session.peerSessionId
    if (peerSessionId === null) return { resumed: false }
    try {
      this.sendRemote(session, 'bot.resume', { sessionId: peerSessionId, afterSeq: session.lastSeq })
    } catch {
      return { resumed: false }
    }
    return { resumed: true }
  }

  promptRemote(sessionId: string, text: string): void {
    const session = this.remoteSessions.get(sessionId)
    if (!session) throw new Error(`unknown remote session: ${sessionId}`)
    const params: Record<string, unknown> = { botId: session.botId, text }
    if (session.peerSessionId) params.sessionId = session.peerSessionId
    session.lastSeq = 0
    this.sendRemote(session, 'bot.prompt', params)
  }

  abortRemote(sessionId: string): void {
    const session = this.remoteSessions.get(sessionId)
    if (!session || !session.peerSessionId) return
    this.sendRemote(session, 'bot.abort', { sessionId: session.peerSessionId })
  }

  closeRemote(sessionId: string): void {
    this.remoteSessions.delete(sessionId)
  }

  logout(): RelayStatus {
    this.options.remoteSessions?.clearAll()
    removeRelayCa(this.options.homeDir)
    void this.remoteServer?.closeAll()
    this.client?.disconnect()
    this.client = null
    this.credentials = null
    this.store.clear()
    this.devices = []
    this.state = 'disconnected'
    this.error = undefined
    this.publish()
    return this.status()
  }

  async dispose(): Promise<void> {
    this.client?.disconnect()
    this.client = null
    await this.remoteServer?.dispose()
  }

  private startClient(): void {
    const credentials = this.credentials
    if (!credentials) return
    this.state = 'connecting'
    this.client = new RelayClient({
      origin: credentials.origin,
      caPem: credentials.caPem,
      deviceId: credentials.deviceId,
      token: credentials.token,
      bots: this.options.bots,
      onState: (state, error) => this.handleState(state, error),
      onDevices: (devices) => {
        this.devices = devices
        this.publish()
      },
      onFrame: (frame) => this.handleFrame(frame),
    })
    this.client.connect()
    this.publish()
  }

  private handleState(state: RelayConnectionState, error?: string): void {
    this.state = state
    this.error = error
    this.publish()
    if (state === 'online') void this.syncMemory()
  }

  private syncMemory(): Promise<void> {
    const credentials = this.credentials
    if (!credentials) return Promise.resolve()
    if (this.memorySync) return this.memorySync
    this.memorySync = this.memoryClient
      .sync(credentials)
      .then((summary) => {
        this.memorySummary = summary
        this.options.broadcastMemory?.(summary)
      })
      .catch(() => undefined)
      .finally(() => {
        this.memorySync = null
      })
    return this.memorySync
  }

  private sendRemote(session: RemoteSessionInfo, method: RouteMethod, params: Record<string, unknown>): void {
    if (!this.client?.connected) throw new Error('The relay connection is not ready')
    const id = `rr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
    this.remoteRoutes.set(id, session.sessionId)
    this.remoteRequests.set(id, { method, params })
    this.client.send({ type: 'route', id, to: session.deviceId, method, params })
  }

  private handleFrame(frame: ServerFrame): void {
    if (frame.type === 'route') {
      void this.remoteServer?.handle(frame)
      return
    }
    if (frame.type === 'memory_changed') {
      void this.syncMemory()
      return
    }
    if (frame.type === 'presence') {
      this.devices = mergePresence(this.devices, frame.deviceId, frame.online, frame.lastSeen, frame.bots)
      this.publish()
      return
    }
    if (frame.type === 'route_event') {
      const sessionId = this.remoteRoutes.get(frame.id)
      if (!sessionId) return
      const session = this.remoteSessions.get(sessionId)
      if (!session) return
      session.lastSeq = Math.max(session.lastSeq, frame.seq)
      if (session.peerSessionId !== null) this.persistRemoteSession(session)
      this.options.onRemoteEvent?.({ sessionId, deviceId: session.deviceId, botId: session.botId, event: frame.event })
      if (frame.event.type === 'agent_settled' || frame.event.type === 'error') {
        this.remoteRoutes.delete(frame.id)
        this.remoteRequests.delete(frame.id)
      }
      return
    }
    if (frame.type === 'route_ok') {
      const sessionId = this.remoteRoutes.get(frame.id)
      if (!sessionId) return
      const session = this.remoteSessions.get(sessionId)
      if (!session) return
      if (hasSessionId(frame.result)) {
        session.peerSessionId = frame.result.sessionId
        this.persistRemoteSession(session)
      }
      return
    }
    if (frame.type === 'route_error') {
      const sessionId = this.remoteRoutes.get(frame.id)
      if (!sessionId) return
      const session = this.remoteSessions.get(sessionId)
      const request = this.remoteRequests.get(frame.id)
      this.remoteRoutes.delete(frame.id)
      this.remoteRequests.delete(frame.id)
      if (!session) return
      if (frame.code === 'session_not_found') {
        this.options.remoteSessions?.clear(session.deviceId, session.botId)
        session.peerSessionId = null
        session.lastSeq = 0
        if (request?.method === 'bot.prompt') {
          const retryParams: Record<string, unknown> = {}
          for (const [key, value] of Object.entries(request.params)) {
            if (key !== 'sessionId') retryParams[key] = value
          }
          try {
            this.sendRemote(session, 'bot.prompt', retryParams)
            return
          } catch {
            return
          }
        }
        if (request?.method === 'bot.resume') return
      }
      this.options.onRemoteEvent?.({
        sessionId,
        deviceId: session.deviceId,
        botId: session.botId,
        event: { type: 'error', message: frame.message },
      })
    }
  }

  private persistRemoteSession(session: RemoteSessionInfo): void {
    if (session.peerSessionId === null) return
    this.options.remoteSessions?.save(session.deviceId, session.botId, session.peerSessionId, session.lastSeq)
  }

  private publish(): void {
    this.options.broadcast(this.status())
  }
}

function hasSessionId(value: unknown): value is { sessionId: string } {
  return typeof value === 'object' && value !== null && typeof (value as { sessionId?: unknown }).sessionId === 'string'
}

function mergePresence(
  devices: DeviceInfo[],
  deviceId: string,
  online: boolean,
  lastSeen: number,
  bots?: BotInfo[],
): DeviceInfo[] {
  const existing = devices.find((device) => device.deviceId === deviceId)
  if (!existing) {
    return [...devices, { deviceId, name: deviceId, platform: 'unknown', online, lastSeen, bots: bots ?? [] }]
  }
  return devices.map((device) =>
    device.deviceId === deviceId
      ? { ...device, online, lastSeen, bots: bots ?? device.bots }
      : device,
  )
}

function describeFailure(status: number, body: string): string {
  try {
    const parsed = JSON.parse(body) as { message?: unknown }
    if (typeof parsed.message === 'string') return `Pairing failed (${status}): ${parsed.message}`
  } catch {
    return `Pairing failed (${status})`
  }
  return `Pairing failed (${status})`
}
