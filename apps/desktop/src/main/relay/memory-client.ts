import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import type { EntitySyncRecord, MemoryKindWire, MemoryPullResponse, MemorySyncRecord } from '@superpi/protocol'
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
  accountId?: string
  memories?: Record<string, MemorySyncRecord>
  entities?: Record<string, EntitySyncRecord>
  files: Record<string, string>
}

const PULL_LIMIT = 500
const KIND_FILES: ReadonlyArray<{ kind: MemoryKindWire; file: string; heading: string }> = [
  { kind: 'IDENTITY', file: 'profile.md', heading: 'You & I' },
  { kind: 'ACTIVE', file: 'context.md', heading: 'Now & Ahead' },
  { kind: 'HABIT', file: 'cookbook.md', heading: 'Lessons & Methods' },
  { kind: 'EPISODIC', file: 'daily/notes.md', heading: 'Diary' },
]

export class MemoryClient {
  private readonly cacheDir: string
  private readonly snapshotPath: string

  constructor(homeDir: string) {
    this.cacheDir = path.join(homeDir, 'cache', 'memory')
    this.snapshotPath = path.join(this.cacheDir, 'snapshot.json')
  }

  async sync(credentials: RelayCredentials): Promise<MemorySummary> {
    const existing = this.readSnapshot()
    const sameAccount = existing !== null && existing.accountId === credentials.accountId
    let since = sameAccount ? existing.version : 0
    const memories: Record<string, MemorySyncRecord> = sameAccount && existing.memories ? { ...existing.memories } : {}
    const entities: Record<string, EntitySyncRecord> = sameAccount && existing.entities ? { ...existing.entities } : {}

    let serverSeq = since
    while (true) {
      const response = await relayRequest({
        url: `${credentials.origin}/v2/memory/pull?since=${since}&limit=${PULL_LIMIT}`,
        token: credentials.token,
        caPem: credentials.caPem,
      })
      if (response.status !== 200) throw new Error(`memory sync failed (${response.status})`)
      const page = JSON.parse(response.body) as MemoryPullResponse
      serverSeq = page.serverSeq
      for (const entry of page.entries) {
        if (entry.kind === 'memory' && entry.memory) {
          memories[entry.memory.id] = entry.memory
        } else if (entry.kind === 'entity' && entry.entity) {
          entities[entry.entity.id] = entry.entity
        }
      }
      since = page.nextSince
      if (!page.hasMore || page.entries.length === 0) break
    }

    const syncedAt = Date.now()
    const files = projectFiles(Object.values(memories))
    this.writeMirror({
      version: serverSeq,
      syncedAt,
      accountId: credentials.accountId,
      memories,
      entities,
      files,
    })
    return { version: serverSeq, syncedAt, files: Object.keys(files) }
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

  private writeMirror(payload: SnapshotFile): void {
    mkdirSync(this.cacheDir, { recursive: true })
    for (const [relative, content] of Object.entries(payload.files)) {
      const target = path.resolve(this.cacheDir, relative)
      if (!target.startsWith(path.resolve(this.cacheDir) + path.sep)) continue
      mkdirSync(path.dirname(target), { recursive: true })
      const temporary = `${target}.tmp`
      writeFileSync(temporary, content, 'utf8')
      renameSync(temporary, target)
    }
    const temporary = `${this.snapshotPath}.tmp`
    writeFileSync(temporary, JSON.stringify(payload), 'utf8')
    renameSync(temporary, this.snapshotPath)
  }
}

function projectFiles(records: MemorySyncRecord[]): Record<string, string> {
  const active = records
    .filter((item) => item.status === 'ACTIVE' && item.deletedAt == null && item.content.trim() !== '')
    .sort((left, right) => {
      if (left.pinned !== right.pinned) return left.pinned ? -1 : 1
      if (left.importance !== right.importance) return right.importance - left.importance
      return right.updatedAt - left.updatedAt
    })

  const files: Record<string, string> = {}
  for (const spec of KIND_FILES) {
    const lines = active
      .filter((item) => item.kind === spec.kind)
      .map((item) => formatBullet(item.content))
    if (lines.length === 0) continue
    files[spec.file] = `## ${spec.heading}\n\n${lines.join('\n')}\n`
  }
  return files
}

function formatBullet(content: string): string {
  const normalized = content.trim().replace(/\r\n/g, '\n')
  return normalized.startsWith('- ') ? normalized : `- ${normalized}`
}
