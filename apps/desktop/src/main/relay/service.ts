import os from 'node:os'
import type { BotEvent, BotInfo, DeviceInfo, EnrollResponse, RouteMethod, ServerFrame } from '@superpi/protocol'
import { RelayCredentialStore, type RelayCredentials } from './credentials.ts'
import { RelayClient, type RelayConnectionState } from './relay-client.ts'
import { fetchCaPem, fingerprintOfPem, relayRequest } from './http.ts'
import { parsePairingLink } from './pairing.ts'
import { MemoryClient, type MemorySummary } from './memory-client.ts'

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

export interface RelayServiceOptions {
  homeDir: string
  bots: () => BotInfo[]
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
  private remoteCounter = 0
  private readonly memoryClient: MemoryClient
  private memorySummary: MemorySummary | null = null
  private memorySync: Promise<void> | null = null

  constructor(private readonly options: RelayServiceOptions) {
    this.store = new RelayCredentialStore(options.homeDir)
    this.credentials = this.store.read()
    this.memoryClient = new MemoryClient(options.homeDir)
    this.memorySummary = this.memoryClient.cached()
    if (this.credentials) this.startClient()
  }

  memoryStatus(): MemorySummary | null {
    return this.memorySummary
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
    this.remoteCounter += 1
    const session: RemoteSessionInfo = {
      sessionId: `rs-${this.remoteCounter}`,
      deviceId: input.deviceId,
      botId: input.botId,
      peerSessionId: null,
    }
    this.remoteSessions.set(session.sessionId, session)
    return session
  }

  promptRemote(sessionId: string, text: string): void {
    const session = this.remoteSessions.get(sessionId)
    if (!session) throw new Error(`unknown remote session: ${sessionId}`)
    const params: Record<string, unknown> = { botId: session.botId, text }
    if (session.peerSessionId) params.sessionId = session.peerSessionId
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

  dispose(): void {
    this.client?.disconnect()
    this.client = null
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
    this.client.send({ type: 'route', id, to: session.deviceId, method, params })
  }

  private handleFrame(frame: ServerFrame): void {
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
      this.options.onRemoteEvent?.({ sessionId, deviceId: session.deviceId, botId: session.botId, event: frame.event })
      return
    }
    if (frame.type === 'route_ok') {
      const sessionId = this.remoteRoutes.get(frame.id)
      if (!sessionId) return
      const session = this.remoteSessions.get(sessionId)
      if (session && hasSessionId(frame.result)) session.peerSessionId = frame.result.sessionId
      return
    }
    if (frame.type === 'route_error') {
      const sessionId = this.remoteRoutes.get(frame.id)
      if (!sessionId) return
      const session = this.remoteSessions.get(sessionId)
      if (!session) return
      this.options.onRemoteEvent?.({
        sessionId,
        deviceId: session.deviceId,
        botId: session.botId,
        event: { type: 'error', message: frame.message },
      })
    }
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
