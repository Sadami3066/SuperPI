import { contextBridge, ipcRenderer } from 'electron'
import type {
  BotInfo,
  BotSessionPayload,
  BotSessionResult,
  MemorySummaryPayload,
  PiEventPayload,
  PiExitPayload,
  PiStatePayload,
  RelayStatusPayload,
  RemoteEventPayload,
  RemoteSessionPayload,
  SessionSummary,
} from '../shared/ipc'

export type {
  BotInfo,
  BotSessionPayload,
  BotSessionResult,
  MemorySummaryPayload,
  PiEventPayload,
  PiExitPayload,
  PiModelInfo,
  PiStatePayload,
  RelayDeviceInfo,
  RelayStatusPayload,
  RemoteEventPayload,
  RemoteSessionPayload,
  SessionSummary,
} from '../shared/ipc'

const api = {
  listBots: (): Promise<BotInfo[]> => ipcRenderer.invoke('bots:list'),
  createBot: (bot: BotInfo): Promise<BotInfo[]> => ipcRenderer.invoke('bots:add', bot),
  removeBot: (id: string): Promise<BotInfo[]> => ipcRenderer.invoke('bots:remove', id),
  openBot: (botId: string | null): Promise<BotSessionPayload> => ipcRenderer.invoke('bot:open', botId),
  getState: (botId: string): Promise<PiStatePayload> => ipcRenderer.invoke('bot:state', botId),
  listSessions: (botId: string): Promise<SessionSummary[]> => ipcRenderer.invoke('bot:sessions', botId),
  newSession: (botId: string): Promise<BotSessionResult> => ipcRenderer.invoke('bot:new-session', botId),
  switchSession: (botId: string, sessionPath: string): Promise<BotSessionResult> =>
    ipcRenderer.invoke('bot:switch-session', { botId, sessionPath }),
  prompt: (botId: string, text: string): Promise<unknown> => ipcRenderer.invoke('bot:prompt', { botId, text }),
  abort: (botId: string): Promise<unknown> => ipcRenderer.invoke('bot:abort', botId),
  relayStatus: (): Promise<RelayStatusPayload> => ipcRenderer.invoke('relay:status'),
  relayPair: (input: { link: string; loginSecret?: string; deviceName?: string }): Promise<RelayStatusPayload> =>
    ipcRenderer.invoke('relay:pair', input),
  relayLogout: (): Promise<RelayStatusPayload> => ipcRenderer.invoke('relay:logout'),
  memoryStatus: (): Promise<MemorySummaryPayload | null> => ipcRenderer.invoke('memory:status'),
  onMemoryState: (listener: (summary: MemorySummaryPayload | null) => void): (() => void) => {
    const handler = (_event: unknown, summary: MemorySummaryPayload | null): void => listener(summary)
    ipcRenderer.on('memory:state', handler)
    return () => ipcRenderer.removeListener('memory:state', handler)
  },
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
