import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { SessionSummary } from '../../shared/ipc'

export interface SessionLocation {
  directory: string
}

const TITLE_LIMIT = 80
const MAX_SESSION_BYTES = 32 * 1024 * 1024

export function resolveAgentDirectory(env: NodeJS.ProcessEnv = process.env, homeDir: string = os.homedir()): string {
  return env.PI_CODING_AGENT_DIR?.trim()
    ? expandHome(env.PI_CODING_AGENT_DIR.trim(), homeDir)
    : path.join(homeDir, '.pi', 'agent')
}

export function resolveSessionLocation(
  cwd: string,
  env: NodeJS.ProcessEnv = process.env,
  homeDir: string = os.homedir(),
): SessionLocation {
  const agentDir = resolveAgentDirectory(env, homeDir)
  const configured =
    env.PI_CODING_AGENT_SESSION_DIR?.trim() ||
    readSettingsSessionDir(path.join(cwd, '.pi', 'settings.json')) ||
    readSettingsSessionDir(path.join(agentDir, 'settings.json'))
  if (configured) return { directory: path.resolve(cwd, expandHome(configured, homeDir)) }
  const resolvedCwd = path.resolve(cwd)
  const encoded = `--${resolvedCwd.replace(/^[/\\]/, '').replace(/[/\\:]/g, '-')}--`
  return { directory: path.join(agentDir, 'sessions', encoded) }
}

export function listSessions(
  cwd: string,
  env: NodeJS.ProcessEnv = process.env,
  homeDir: string = os.homedir(),
): SessionSummary[] {
  const { directory } = resolveSessionLocation(cwd, env, homeDir)
  let files: string[]
  try {
    files = readdirSync(directory).filter((file) => file.endsWith('.jsonl'))
  } catch {
    return []
  }
  const resolvedCwd = path.resolve(cwd)
  const sessions: SessionSummary[] = []
  for (const file of files) {
    const summary = readSession(path.join(directory, file), resolvedCwd)
    if (summary) sessions.push(summary)
  }
  return sessions.sort((left, right) => right.modified - left.modified)
}

export function resumableSession(
  sessionFile: string | null | undefined,
  exists: (file: string) => boolean = existsSync,
): string | null {
  if (typeof sessionFile !== 'string' || sessionFile === '') return null
  if (!path.isAbsolute(sessionFile) || !sessionFile.endsWith('.jsonl')) return null
  return exists(sessionFile) ? sessionFile : null
}

function readSession(filePath: string, resolvedCwd: string): SessionSummary | null {
  let content: string
  let fileModified: number
  try {
    const stats = statSync(filePath)
    if (stats.size > MAX_SESSION_BYTES) return null
    fileModified = stats.mtimeMs
    content = readFileSync(filePath, 'utf8')
  } catch {
    return null
  }
  let header: Record<string, unknown> | null = null
  let name: string | null = null
  let firstUserText: string | null = null
  let lastActivity = 0
  let messageCount = 0
  for (const line of content.split('\n')) {
    if (line.trim() === '') continue
    let entry: Record<string, unknown>
    try {
      const parsed: unknown = JSON.parse(line)
      if (!isRecord(parsed)) continue
      entry = parsed
    } catch {
      continue
    }
    if (!header) {
      if (entry.type !== 'session' || typeof entry.id !== 'string') return null
      header = entry
      continue
    }
    if (entry.type === 'session_info') {
      name = typeof entry.name === 'string' && entry.name.trim() !== '' ? entry.name.trim() : null
      continue
    }
    if (entry.type !== 'message' || !isRecord(entry.message)) continue
    messageCount += 1
    const message = entry.message
    if (message.role === 'user' || message.role === 'assistant') {
      const timestamp = typeof message.timestamp === 'number' ? message.timestamp : Date.parse(String(entry.timestamp))
      if (Number.isFinite(timestamp)) lastActivity = Math.max(lastActivity, timestamp)
    }
    if (firstUserText === null && message.role === 'user') {
      const text = textOf(message.content)
      if (text !== '') firstUserText = text
    }
  }
  if (!header) return null
  if (messageCount === 0 && name === null) return null
  const headerCwd = typeof header.cwd === 'string' ? header.cwd : ''
  if (headerCwd !== '' && path.resolve(headerCwd) !== resolvedCwd) return null
  const created = Date.parse(String(header.timestamp))
  const fallback = Number.isFinite(created) ? created : fileModified
  return {
    path: filePath,
    id: header.id as string,
    name,
    title: name ?? titleFrom(firstUserText) ?? 'Untitled',
    created: Number.isFinite(created) ? created : fileModified,
    modified: lastActivity > 0 ? lastActivity : fallback,
    messageCount,
  }
}

function titleFrom(text: string | null): string | null {
  if (text === null) return null
  const compact = text.replace(/\s+/g, ' ').trim()
  if (compact === '') return null
  return compact.length > TITLE_LIMIT ? `${compact.slice(0, TITLE_LIMIT - 1)}…` : compact
}

function textOf(content: unknown): string {
  if (typeof content === 'string') return content
  if (!Array.isArray(content)) return ''
  const parts: string[] = []
  for (const block of content) {
    if (isRecord(block) && block.type === 'text' && typeof block.text === 'string') parts.push(block.text)
  }
  return parts.join(' ')
}

function readSettingsSessionDir(file: string): string | null {
  try {
    const parsed: unknown = JSON.parse(readFileSync(file, 'utf8'))
    if (!isRecord(parsed)) return null
    const value = parsed.sessionDir
    return typeof value === 'string' && value.trim() !== '' ? value.trim() : null
  } catch {
    return null
  }
}

function expandHome(value: string, homeDir: string): string {
  if (value === '~') return homeDir
  if (value.startsWith('~/')) return path.join(homeDir, value.slice(2))
  return value
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
