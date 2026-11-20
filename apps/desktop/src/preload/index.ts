import { contextBridge, ipcRenderer } from 'electron'

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

export interface RelayStatusPayload {
  state: 'disconnected' | 'connecting' | 'online' | 'offline' | 'error'
  deviceId?: string
  origin?: string
  deviceName?: string
  accountId?: string
  devices: RelayDeviceInfo[]
  error?: string
}

const api = {
  listBots: (): Promise<BotInfo[]> => ipcRenderer.invoke('bots:list'),
  createBot: (bot: BotInfo): Promise<BotInfo[]> => ipcRenderer.invoke('bots:add', bot),
  removeBot: (id: string): Promise<BotInfo[]> => ipcRenderer.invoke('bots:remove', id),
  getState: (botId: string): Promise<unknown> => ipcRenderer.invoke('bot:state', botId),
  prompt: (botId: string, text: string): Promise<unknown> => ipcRenderer.invoke('bot:prompt', { botId, text }),
  abort: (botId: string): Promise<unknown> => ipcRenderer.invoke('bot:abort', botId),
  relayStatus: (): Promise<RelayStatusPayload> => ipcRenderer.invoke('relay:status'),
  relayPair: (input: { link: string; loginSecret?: string; deviceName?: string }): Promise<RelayStatusPayload> =>
    ipcRenderer.invoke('relay:pair', input),
  relayLogout: (): Promise<RelayStatusPayload> => ipcRenderer.invoke('relay:logout'),
  remoteStart: (input: { deviceId: string; botId: string }): Promise<RemoteSessionPayload> =>
    ipcRenderer.invoke('remote:start', input),
  remotePrompt: (input: { sessionId: string; text: string }): Promise<void> => ipcRenderer.invoke('remote:prompt', input),
  remoteAbort: (sessionId: string): Promise<void> => ipcRenderer.invoke('remote:abort', sessionId),
  remoteClose: (sessionId: string): Promise<void> => ipcRenderer.invoke('remote:close', sessionId),
  onRemoteEvent: (listener: (payload: RemoteEventPayload) => void): (() => void) => {
    const handler = (_event: unknown, payload: RemoteEventPayload): void => listener(payload)
    ipcRenderer.on('remote:event', handler)
    return () => ipcRenderer.removeListener('remote:event', handler)
  },
  onEvent: (listener: (payload: PiEventPayload) => void): (() => void) => {
    const handler = (_event: unknown, payload: PiEventPayload): void => listener(payload)
    ipcRenderer.on('pi:event', handler)
    return () => ipcRenderer.removeListener('pi:event', handler)
  },
  onExit: (listener: (payload: PiExitPayload) => void): (() => void) => {
    const handler = (_event: unknown, payload: PiExitPayload): void => listener(payload)
    ipcRenderer.on('pi:exit', handler)
    return () => ipcRenderer.removeListener('pi:exit', handler)
  },
  onRelayState: (listener: (payload: RelayStatusPayload) => void): (() => void) => {
    const handler = (_event: unknown, payload: RelayStatusPayload): void => listener(payload)
    ipcRenderer.on('relay:state', handler)
    return () => ipcRenderer.removeListener('relay:state', handler)
  },
}

contextBridge.exposeInMainWorld('superpi', api)

export type SuperPiApi = typeof api
