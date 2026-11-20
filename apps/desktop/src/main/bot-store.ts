import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'

export interface BotRecord {
  id: string
  name: string
  cwd: string
  isHome: boolean
  avatar: string | null
  intro: string
}

interface BotFile {
  version: number
  bots: BotRecord[]
}

export class BotStore {
  readonly homeDir: string
  private readonly file: string

  constructor(homeDir: string = path.join(os.homedir(), '.superpi')) {
    this.homeDir = homeDir
    this.file = path.join(homeDir, 'bots.json')
  }

  list(): BotRecord[] {
    const loaded = this.read()
    if (loaded.length > 0) return loaded
    const created = this.ensureHome()
    this.write(created)
    return created
  }

  save(bots: BotRecord[]): BotRecord[] {
    const normalized = bots.map((bot) => ({ ...bot, cwd: path.resolve(bot.cwd) }))
    this.write(normalized)
    return normalized
  }

  add(bot: BotRecord): BotRecord[] {
    const current = this.list().filter((entry) => entry.id !== bot.id)
    return this.save([...current, { ...bot, cwd: path.resolve(bot.cwd) }])
  }

  remove(id: string): BotRecord[] {
    const current = this.list().filter((entry) => entry.id !== id || entry.isHome)
    return this.save(current)
  }

  private read(): BotRecord[] {
    if (!existsSync(this.file)) return []
    try {
      const parsed = JSON.parse(readFileSync(this.file, 'utf8')) as BotFile
      if (!Array.isArray(parsed.bots)) return []
      return parsed.bots.filter(isBotRecord)
    } catch {
      return []
    }
  }

  private write(bots: BotRecord[]): void {
    mkdirSync(this.homeDir, { recursive: true })
    const payload: BotFile = { version: 1, bots }
    writeFileSync(this.file, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
  }

  private ensureHome(): BotRecord[] {
    const workspace = path.join(this.homeDir, 'workspace')
    mkdirSync(workspace, { recursive: true })
    return [{ id: 'pi', name: 'PI', cwd: workspace, isHome: true, avatar: null, intro: '' }]
  }
}

function isBotRecord(value: unknown): value is BotRecord {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return (
    typeof record.id === 'string' &&
    typeof record.name === 'string' &&
    typeof record.cwd === 'string' &&
    typeof record.isHome === 'boolean'
  )
}
