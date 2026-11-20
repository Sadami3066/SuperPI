export interface BotInfo {
  id: string
  name: string
  cwd: string
  isHome: boolean
  avatar: string | null
  intro: string
}

export interface PiEventPayload {
  botId: string
  event: { type: string; [key: string]: unknown }
}

export interface PiExitPayload {
  botId: string
  message: string | null
}

export interface PiModelInfo {
  provider: string
  id: string
  name: string
}

export interface PiStatePayload {
  model: PiModelInfo | null
  thinkingLevel: string
  sessionFile: string | null
  sessionName: string | null
}

export interface SessionSummary {
  path: string
  id: string
  name: string | null
  title: string
  created: number
  modified: number
  messageCount: number
}

export interface BotSessionPayload {
  botId: string
  state: PiStatePayload
  messages: unknown[]
  sessions: SessionSummary[]
}

export interface BotSessionResult extends BotSessionPayload {
  cancelled: boolean
}

export interface RelayDeviceInfo {
  deviceId: string
  name: string
  platform: string
  online: boolean
  lastSeen: number
  bots: Array<{ id: string; name: string }>
}

export interface RemoteSessionPayload {
  sessionId: string
  deviceId: string
  botId: string
  peerSessionId: string | null
}

export interface RemoteEventPayload {
  sessionId: string
  deviceId: string
  botId: string
  event: { type: string; [key: string]: unknown }
}

export interface MemorySummaryPayload {
  version: number
  syncedAt: number
  files: string[]
}

export interface RelayStatusPayload {
  state: 'disconnected' | 'connecting' | 'online' | 'offline' | 'error'
  deviceId?: string
  origin?: string
  deviceName?: string
  accountId?: string
  devices: RelayDeviceInfo[]
  error?: string
}
