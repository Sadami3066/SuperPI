import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { BotInfo } from '../shared/ipc'

export type BotRecord = BotInfo

interface BotFile {
  version: number
  bots: unknown[]
}

export const DEFAULT_BOT_ID = 'pi'

export class BotStore {
  readonly homeDir: string
  private readonly file: string

  constructor(homeDir: string = path.join(os.homedir(), '.superpi')) {
    this.homeDir = homeDir
    this.file = path.join(homeDir, 'bots.json')
  }

  list(): BotInfo[] {
    const loaded = this.read()
    if (loaded.length > 0) return loaded
    const created = this.defaultBots()
    this.write(created)
    return created
  }

  save(bots: BotInfo[]): BotInfo[] {
    const normalized = normalizeBots(bots)
    const next = normalized.length > 0 ? normalized : this.defaultBots()
    this.write(next)
    return next
  }

  add(bot: BotInfo): BotInfo[] {
    const normalized = normalizeBot(bot)
    if (!normalized) throw new Error('A bot needs an id, a name and a working directory')
    const current = this.list().filter((entry) => entry.id !== normalized.id)
    return this.save([...current, normalized])
  }

  remove(id: string): BotInfo[] {
    const current = this.list().filter((entry) => entry.id !== id || entry.id === DEFAULT_BOT_ID)
    return this.save(current)
  }

  private read(): BotInfo[] {
    if (!existsSync(this.file)) return []
    try {
      const parsed = JSON.parse(readFileSync(this.file, 'utf8')) as BotFile
      if (!Array.isArray(parsed.bots)) return []
      return normalizeBots(parsed.bots)
    } catch {
      return []
    }
  }

  private write(bots: BotInfo[]): void {
    mkdirSync(this.homeDir, { recursive: true })
    const payload: BotFile = { version: 1, bots }
    writeFileSync(this.file, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
  }

  private defaultBots(): BotInfo[] {
    const workspace = path.join(this.homeDir, 'workspace')
    mkdirSync(workspace, { recursive: true })
    return [{ id: DEFAULT_BOT_ID, name: 'PI', cwd: workspace }]
  }
}

function normalizeBots(value: unknown[]): BotInfo[] {
  const bots: BotInfo[] = []
  const seen = new Set<string>()
  for (const entry of value) {
    const bot = normalizeBot(entry)
    if (!bot || seen.has(bot.id)) continue
    seen.add(bot.id)
    bots.push(bot)
  }
  return bots
}

function normalizeBot(value: unknown): BotInfo | null {
  if (typeof value !== 'object' || value === null) return null
  const record = value as Record<string, unknown>
  const id = typeof record.id === 'string' ? record.id.trim() : ''
  const name = typeof record.name === 'string' ? record.name.trim() : ''
  const cwd = typeof record.cwd === 'string' ? record.cwd.trim() : ''
  if (id === '' || name === '' || cwd === '') return null
  return { id, name, cwd: path.resolve(cwd) }
}
