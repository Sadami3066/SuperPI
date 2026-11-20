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

const api = {
  listBots: (): Promise<BotInfo[]> => ipcRenderer.invoke('bots:list'),
  createBot: (bot: BotInfo): Promise<BotInfo[]> => ipcRenderer.invoke('bots:add', bot),
  removeBot: (id: string): Promise<BotInfo[]> => ipcRenderer.invoke('bots:remove', id),
  getState: (botId: string): Promise<unknown> => ipcRenderer.invoke('bot:state', botId),
  prompt: (botId: string, text: string): Promise<unknown> => ipcRenderer.invoke('bot:prompt', { botId, text }),
  abort: (botId: string): Promise<unknown> => ipcRenderer.invoke('bot:abort', botId),
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
}

contextBridge.exposeInMainWorld('superpi', api)

export type SuperPiApi = typeof api
