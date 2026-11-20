import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import type { DatabaseSync } from 'node:sqlite'
import type { MemoryWriteOp, MemoryWriteRequest } from '@superpi/protocol'
import { isMemoryPath } from '@superpi/protocol'

export interface MemorySnapshot {
  version: number
  files: Record<string, string>
  versions: Record<string, number>
}

export interface MemoryFileContent {
  version: number
  fileVersion: number
  path: string
  content: string
}

export interface MemorySource {
  deviceId: string
  deviceName: string
}

export class MemoryConflictError extends Error {
  readonly currentVersion: number

  constructor(currentVersion: number) {
    super(`memory version conflict: expected ${currentVersion}`)
    this.name = 'MemoryConflictError'
    this.currentVersion = currentVersion
  }
}

export class MemoryRequestError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'MemoryRequestError'
  }
}

const WRITE_OPS: readonly MemoryWriteOp[] = ['append', 'replace_section', 'overwrite']

export class MemoryStore {
  private readonly rootDir: string
  private readonly db: DatabaseSync

  constructor(rootDir: string, db: DatabaseSync) {
    this.rootDir = rootDir
    this.db = db
    mkdirSync(rootDir, { recursive: true })
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS memory_meta (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_writes (
        idempotency_key TEXT PRIMARY KEY,
        path TEXT NOT NULL,
        source_device TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        version INTEGER NOT NULL
      );
    `)
  }

  snapshot(): MemorySnapshot {
    const files = this.listFiles()
    const versions: Record<string, number> = {}
    for (const relative of Object.keys(files)) versions[relative] = this.fileVersion(relative)
    return { version: this.version(), files, versions }
  }

  file(requested: string): MemoryFileContent {
    const relative = this.normalizePath(requested)
    return {
      version: this.version(),
      fileVersion: this.fileVersion(relative),
      path: relative,
      content: this.read(relative),
    }
  }

  write(source: MemorySource, request: MemoryWriteRequest, now = Date.now()): { version: number; applied: boolean } {
    if (!WRITE_OPS.includes(request.op)) throw new MemoryRequestError(`unknown memory op: ${request.op}`)
    if (typeof request.text !== 'string' || request.text.trim() === '') {
      throw new MemoryRequestError('text is required')
    }
    const relative = this.normalizePath(request.path)
    const key = typeof request.idempotencyKey === 'string' ? request.idempotencyKey.trim() : ''
    if (key === '') throw new MemoryRequestError('idempotencyKey is required')

    const previous = this.findWrite(key)
    if (previous !== null) return { version: previous, applied: false }

    const current = this.read(relative)
    const currentVersion = this.fileVersion(relative)
    let next: string
    switch (request.op) {
      case 'append':
        next = this.appendBlock(current, request.section ?? null, this.stamp(source, request.text, now))
        break
      case 'replace_section':
        if (!request.section) throw new MemoryRequestError('section is required for replace_section')
        this.requireVersion(currentVersion, request.baseVersion)
        next = replaceSection(current, request.section, this.stamp(source, request.text, now))
        break
      case 'overwrite':
        this.requireVersion(currentVersion, request.baseVersion)
        next = `${request.text.trimEnd()}\n\n<!-- updated by ${source.deviceName} ${new Date(now).toISOString()} -->\n`
        break
      default:
        throw new MemoryRequestError(`unknown memory op: ${request.op}`)
    }

    this.writeFile(relative, next)
    const version = this.bumpVersion()
    this.setFileVersion(relative, version)
    this.db
      .prepare('INSERT INTO memory_writes (idempotency_key, path, source_device, created_at, version) VALUES (?, ?, ?, ?, ?)')
      .run(key, relative, source.deviceId, now, version)
    return { version, applied: true }
  }

  version(): number {
    return this.readMetaInt('version')
  }

  private stamp(source: MemorySource, text: string, now: number): string {
    const stamp = `<!-- src: ${source.deviceName} ${new Date(now).toISOString()} -->`
    return `${text.trimEnd()} ${stamp}`
  }

  private appendBlock(content: string, section: string | null, block: string): string {
    if (!section) {
      const base = content.trimEnd()
      return `${base === '' ? '' : `${base}\n\n`}${block}\n`
    }
    const heading = `## ${section}`
    const index = content.indexOf(heading)
    if (index < 0) {
      const base = content.trimEnd()
      return `${base === '' ? '' : `${base}\n\n`}${heading}\n\n${block}\n`
    }
    const after = content.indexOf('\n## ', index + heading.length)
    const head = content.slice(0, after < 0 ? content.length : after).trimEnd()
    const tail = after < 0 ? '' : content.slice(after)
    return `${head}\n${block}\n${tail}`
  }

  private requireVersion(current: number, requested: number | undefined): void {
    if (requested === undefined) throw new MemoryRequestError('baseVersion is required for this operation')
    if (requested !== current) throw new MemoryConflictError(current)
  }

  private normalizePath(requested: string): string {
    if (typeof requested !== 'string' || requested.trim() === '') throw new MemoryRequestError('path is required')
    const normalized = requested.replaceAll('\\', '/').replace(/^\/+/, '')
    if (normalized.includes('..') || normalized.startsWith('/')) throw new MemoryRequestError('invalid memory path')
    if (!isMemoryPath(normalized)) throw new MemoryRequestError(`unsupported memory path: ${normalized}`)
    return normalized
  }

  private resolve(relative: string): string {
    const target = path.resolve(this.rootDir, relative)
    if (!target.startsWith(path.resolve(this.rootDir) + path.sep)) throw new MemoryRequestError('invalid memory path')
    return target
  }

  private read(relative: string): string {
    const file = this.resolve(relative)
    return existsSync(file) ? readFileSync(file, 'utf8') : ''
  }

  private writeFile(relative: string, content: string): void {
    const file = this.resolve(relative)
    mkdirSync(path.dirname(file), { recursive: true })
    const temporary = `${file}.tmp`
    writeFileSync(temporary, content, 'utf8')
    renameSync(temporary, file)
  }

  private listFiles(): Record<string, string> {
    const files: Record<string, string> = {}
    for (const file of this.walk(this.rootDir)) {
      const relative = path.relative(this.rootDir, file).split(path.sep).join('/')
      if (!relative.endsWith('.md') && relative !== 'summaries.log') continue
      files[relative] = readFileSync(file, 'utf8')
    }
    return files
  }

  private walk(dir: string): string[] {
    const result: string[] = []
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) result.push(...this.walk(full))
      else if (statSync(full).isFile()) result.push(full)
    }
    return result
  }

  private findWrite(key: string): number | null {
    const row = this.db.prepare('SELECT version FROM memory_writes WHERE idempotency_key = ?').get(key) as
      | { version: number }
      | undefined
    return row ? Number(row.version) : null
  }

  private fileVersion(relative: string): number {
    return this.readMetaInt(`file:${relative}`)
  }

  private setFileVersion(relative: string, version: number): void {
    this.db
      .prepare('INSERT INTO memory_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value')
      .run(`file:${relative}`, String(version))
  }

  private readMetaInt(key: string): number {
    const row = this.db.prepare('SELECT value FROM memory_meta WHERE key = ?').get(key) as { value: string } | undefined
    const parsed = row ? Number.parseInt(row.value, 10) : 0
    return Number.isFinite(parsed) ? parsed : 0
  }

  private bumpVersion(): number {
    const next = this.version() + 1
    this.db
      .prepare('INSERT INTO memory_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value')
      .run('version', String(next))
    return next
  }
}

function replaceSection(content: string, section: string, block: string): string {
  const heading = `## ${section}`
  const index = content.indexOf(heading)
  if (index < 0) {
    const base = content.trimEnd()
    return `${base === '' ? '' : `${base}\n\n`}${heading}\n\n${block}\n`
  }
  const after = content.indexOf('\n## ', index + heading.length)
  const head = content.slice(0, index + heading.length).trimEnd()
  const tail = after < 0 ? '' : content.slice(after)
  return `${head}\n\n${block}\n${tail}`
}

