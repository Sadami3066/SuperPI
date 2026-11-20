import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'

export interface WindowBounds {
  x: number
  y: number
  width: number
  height: number
}

export interface PersistedWindow {
  bounds: WindowBounds
  maximized: boolean
}

export interface RemoteSessionRef {
  sessionId: string
  afterSeq: number
}

export interface DesktopState {
  window: PersistedWindow | null
  lastBotId: string | null
  lastSession: Record<string, string>
  remoteSessions: Record<string, RemoteSessionRef>
}

export function defaultDesktopState(): DesktopState {
  return { window: null, lastBotId: null, lastSession: {}, remoteSessions: {} }
}

function defaultHomeDir(): string {
  return path.join(os.homedir(), '.superpi')
}

function stateFile(homeDir: string): string {
  return path.join(homeDir, 'desktop.json')
}

export function loadDesktopState(homeDir: string = defaultHomeDir()): DesktopState {
  const file = stateFile(homeDir)
  if (!existsSync(file)) return defaultDesktopState()
  try {
    return normalizeDesktopState(JSON.parse(readFileSync(file, 'utf8')))
  } catch {
    return defaultDesktopState()
  }
}

export function saveDesktopState(state: DesktopState, homeDir: string = defaultHomeDir()): void {
  mkdirSync(homeDir, { recursive: true })
  const file = stateFile(homeDir)
  const temporary = `${file}.tmp`
  writeFileSync(temporary, `${JSON.stringify(normalizeDesktopState(state), null, 2)}\n`, 'utf8')
  renameSync(temporary, file)
}

export function normalizeDesktopState(value: unknown): DesktopState {
  const state = defaultDesktopState()
  if (!isRecord(value)) return state
  const window = normalizeWindow(value.window)
  if (window) state.window = window
  if (typeof value.lastBotId === 'string' && value.lastBotId !== '') state.lastBotId = value.lastBotId
  if (isRecord(value.lastSession)) {
    for (const [botId, sessionFile] of Object.entries(value.lastSession)) {
      if (botId !== '' && typeof sessionFile === 'string' && sessionFile !== '') {
        state.lastSession[botId] = sessionFile
      }
    }
  }
  if (isRecord(value.remoteSessions)) {
    for (const [key, ref] of Object.entries(value.remoteSessions)) {
      if (key === '' || !isRecord(ref)) continue
      const sessionId = typeof ref.sessionId === 'string' ? ref.sessionId : ''
      if (sessionId === '') continue
      const afterSeq = typeof ref.afterSeq === 'number' && Number.isFinite(ref.afterSeq) && ref.afterSeq >= 0 ? Math.floor(ref.afterSeq) : 0
      state.remoteSessions[key] = { sessionId, afterSeq }
    }
  }
  return state
}

export function remoteSessionKey(deviceId: string, botId: string): string {
  return JSON.stringify([deviceId, botId])
}

export function findRemoteSession(state: DesktopState, deviceId: string, botId: string): RemoteSessionRef | null {
  return state.remoteSessions[remoteSessionKey(deviceId, botId)] ?? null
}

export function rememberRemoteSession(
  state: DesktopState,
  deviceId: string,
  botId: string,
  sessionId: string,
  afterSeq: number,
): DesktopState {
  return {
    ...state,
    remoteSessions: {
      ...state.remoteSessions,
      [remoteSessionKey(deviceId, botId)]: { sessionId, afterSeq: Math.max(0, Math.floor(afterSeq)) },
    },
  }
}

export function forgetRemoteSession(state: DesktopState, deviceId: string, botId: string): DesktopState {
  const key = remoteSessionKey(deviceId, botId)
  if (!(key in state.remoteSessions)) return state
  const remoteSessions: Record<string, RemoteSessionRef> = {}
  for (const [entryKey, value] of Object.entries(state.remoteSessions)) {
    if (entryKey !== key) remoteSessions[entryKey] = value
  }
  return { ...state, remoteSessions }
}

export function clearRemoteSessions(state: DesktopState): DesktopState {
  if (Object.keys(state.remoteSessions).length === 0) return state
  return { ...state, remoteSessions: {} }
}

export function rememberSession(state: DesktopState, botId: string, sessionFile: string | null): DesktopState {
  const lastSession: Record<string, string> = {}
  for (const [key, value] of Object.entries(state.lastSession)) {
    if (key !== botId) lastSession[key] = value
  }
  if (sessionFile !== null && sessionFile !== '') lastSession[botId] = sessionFile
  return { ...state, lastSession }
}

function normalizeWindow(value: unknown): PersistedWindow | null {
  if (!isRecord(value) || !isRecord(value.bounds)) return null
  const bounds = value.bounds
  const x = finiteNumber(bounds.x)
  const y = finiteNumber(bounds.y)
  const width = finiteNumber(bounds.width)
  const height = finiteNumber(bounds.height)
  if (x === null || y === null || width === null || height === null) return null
  if (width < 320 || height < 240) return null
  return {
    bounds: { x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) },
    maximized: value.maximized === true,
  }
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
