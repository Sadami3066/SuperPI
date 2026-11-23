import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { relayRequest } from './http.ts'
import type { RelayCredentials } from './credentials.ts'

export interface MemorySnapshot {
  version: number
  files: Record<string, string>
}

export interface MemorySummary {
  version: number
  syncedAt: number
  files: string[]
}

interface SnapshotFile {
  version: number
  syncedAt: number
  files: Record<string, string>
}

export class MemoryClient {
  private readonly cacheDir: string
  private readonly snapshotPath: string

  constructor(homeDir: string) {
    this.cacheDir = path.join(homeDir, 'cache', 'memory')
    this.snapshotPath = path.join(this.cacheDir, 'snapshot.json')
  }

  async sync(credentials: RelayCredentials): Promise<MemorySummary> {
    const response = await relayRequest({
      url: `${credentials.origin}/v1/memory/snapshot`,
      token: credentials.token,
      caPem: credentials.caPem,
    })
    if (response.status !== 200) throw new Error(`memory sync failed (${response.status})`)
    const snapshot = JSON.parse(response.body) as MemorySnapshot
    const syncedAt = Date.now()
    this.writeMirror(snapshot, syncedAt)
    return { version: snapshot.version, syncedAt, files: Object.keys(snapshot.files) }
  }

  cached(): MemorySummary | null {
    const stored = this.readSnapshot()
    if (!stored) return null
    return { version: stored.version, syncedAt: stored.syncedAt, files: Object.keys(stored.files) }
  }

  readFile(relative: string): string | null {
    const stored = this.readSnapshot()
    if (!stored) return null
    return stored.files[relative] ?? null
  }

  private readSnapshot(): SnapshotFile | null {
    if (!existsSync(this.snapshotPath)) return null
    try {
      const parsed = JSON.parse(readFileSync(this.snapshotPath, 'utf8')) as SnapshotFile
      if (typeof parsed.version !== 'number' || typeof parsed.files !== 'object' || parsed.files === null) return null
      return parsed
    } catch {
      return null
    }
  }

  private writeMirror(snapshot: MemorySnapshot, syncedAt: number): void {
    mkdirSync(this.cacheDir, { recursive: true })
    for (const [relative, content] of Object.entries(snapshot.files)) {
      const target = path.resolve(this.cacheDir, relative)
      if (!target.startsWith(path.resolve(this.cacheDir) + path.sep)) continue
      mkdirSync(path.dirname(target), { recursive: true })
      const temporary = `${target}.tmp`
      writeFileSync(temporary, content, 'utf8')
      renameSync(temporary, target)
    }
    const payload: SnapshotFile = { version: snapshot.version, syncedAt, files: snapshot.files }
    const temporary = `${this.snapshotPath}.tmp`
    writeFileSync(temporary, JSON.stringify(payload), 'utf8')
    renameSync(temporary, this.snapshotPath)
  }
}
