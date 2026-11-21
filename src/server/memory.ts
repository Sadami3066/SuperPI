import { randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
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
const MAX_MEMORY_FILE_BYTES = 512 * 1024
const MAX_SECTION_LENGTH = 160
const MAX_BACKUPS_PER_FILE = 20

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
    const section = normalizeSection(request.section)
    const relative = this.normalizePath(request.path)
    const key = typeof request.idempotencyKey === 'string' ? request.idempotencyKey.trim() : ''
    if (key === '' || key.length > 128) throw new MemoryRequestError('idempotencyKey is required')

    const previous = this.findWrite(key, relative, source.deviceId)
    if (previous !== null) return { version: previous, applied: false }

    const current = this.read(relative)
    const currentVersion = this.fileVersion(relative)
    const safeDeviceName = sanitizeCommentName(source.deviceName)
    let next: string
    switch (request.op) {
      case 'append':
        next = this.appendBlock(current, section, this.stamp(safeDeviceName, request.text, now))
        break
      case 'replace_section':
        if (section === null) throw new MemoryRequestError('section is required for replace_section')
        this.requireVersion(currentVersion, request.baseVersion)
        next = replaceSection(current, section, this.stamp(safeDeviceName, request.text, now))
        break
      case 'overwrite':
        this.requireVersion(currentVersion, request.baseVersion)
        next = `${request.text.trimEnd()}\n\n<!-- updated by ${safeDeviceName} ${new Date(now).toISOString()} -->\n`
        break
      default:
        throw new MemoryRequestError(`unknown memory op: ${request.op}`)
    }

    if (Buffer.byteLength(next, 'utf8') > MAX_MEMORY_FILE_BYTES) {
      throw new MemoryRequestError('memory file exceeds size limit')
    }

    let backupTarget: string | null = null
    if (request.op === 'overwrite' && current !== '') {
      backupTarget = this.backupFile(relative, current, now)
    }

    let version: number
    try {
      version = this.commitAtomicWrite(relative, current, next, key, source.deviceId, now)
    } catch (error) {
      if (backupTarget) {
        try {
          rmSync(backupTarget, { force: true })
        } catch {}
      }
      throw error
    }
    if (backupTarget) {
      this.pruneBackups(path.dirname(backupTarget), `${path.basename(relative)}.`)
    }
    return { version, applied: true }
  }

  version(): number {
    return this.readMetaInt('version')
  }

  private stamp(deviceName: string, text: string, now: number): string {
    const stamp = `<!-- src: ${deviceName} ${new Date(now).toISOString()} -->`
    return `${text.trimEnd()} ${stamp}`
  }

  private appendBlock(content: string, section: string | null, block: string): string {
    if (!section) {
      const base = content.trimEnd()
      return `${base === '' ? '' : `${base}\n\n`}${block}\n`
    }
    return appendSectionBlock(content, section, block)
  }

  private requireVersion(current: number, requested: number | undefined): void {
    if (requested === undefined) throw new MemoryRequestError('baseVersion is required for this operation')
    if (typeof requested !== 'number' || !Number.isSafeInteger(requested) || requested < 0) {
      throw new MemoryRequestError('invalid baseVersion')
    }
    if (requested !== current) throw new MemoryConflictError(current)
  }

  private normalizePath(requested: string): string {
    if (typeof requested !== 'string' || requested.trim() === '') throw new MemoryRequestError('path is required')
    if (/[\x00-\x1f\x7f]/.test(requested)) throw new MemoryRequestError('invalid memory path')
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

  private backupFile(relative: string, content: string, now: number): string {
    const backupRoot = path.resolve(this.rootDir, '.backup')
    const baseTarget = path.resolve(backupRoot, `${relative}.${now}.bak`)
    if (!baseTarget.startsWith(backupRoot + path.sep)) throw new MemoryRequestError('invalid memory path')
    mkdirSync(path.dirname(baseTarget), { recursive: true })
    let target = baseTarget
    let suffix = 1
    while (existsSync(target)) {
      target = path.resolve(backupRoot, `${relative}.${now}.${suffix}.bak`)
      suffix += 1
    }
    writeFileSync(target, content, 'utf8')
    return target
  }

  private pruneBackups(dir: string, prefix: string): void {
    const backups = readdirSync(dir, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.startsWith(prefix) && entry.name.endsWith('.bak'))
      .map((entry) => {
        const stem = entry.name.slice(prefix.length, -'.bak'.length)
        const parts = stem.split('.')
        const ts = Number(parts[0])
        const suffix = parts.length > 1 ? Number(parts[1]) : 0
        return {
          name: entry.name,
          ts: Number.isFinite(ts) ? ts : 0,
          suffix: Number.isFinite(suffix) ? suffix : 0,
        }
      })
      .sort((a, b) => a.ts - b.ts || a.suffix - b.suffix || a.name.localeCompare(b.name))
    const excess = backups.length - MAX_BACKUPS_PER_FILE
    if (excess <= 0) return
    for (const obsolete of backups.slice(0, excess)) {
      try {
        rmSync(path.join(dir, obsolete.name), { force: true })
      } catch {}
    }
  }

  private listFiles(): Record<string, string> {
    const files: Record<string, string> = {}
    for (const file of this.walk(this.rootDir)) {
      const relative = path.relative(this.rootDir, file).split(path.sep).join('/')
      if (relative === '.backup' || relative.startsWith('.backup/')) continue
      if (!relative.endsWith('.md') && relative !== 'summaries.log') continue
      files[relative] = readFileSync(file, 'utf8')
    }
    return files
  }

  private walk(dir: string): string[] {
    const result: string[] = []
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isSymbolicLink()) continue
      if (entry.isDirectory() && entry.name === '.backup') continue
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) result.push(...this.walk(full))
      else if (entry.isFile()) result.push(full)
    }
    return result
  }

  private commitAtomicWrite(
    relative: string,
    previousContent: string,
    nextContent: string,
    key: string,
    deviceId: string,
    now: number,
  ): number {
    const file = this.resolve(relative)
    const existedBefore = existsSync(file)
    mkdirSync(path.dirname(file), { recursive: true })
    const temporary = `${file}.${process.pid}.${randomBytes(6).toString('hex')}.tmp`
    writeFileSync(temporary, nextContent, 'utf8')
    let renamed = false
    this.db.exec('BEGIN IMMEDIATE')
    try {
      const version = this.bumpVersion()
      this.setFileVersion(relative, version)
      this.db
        .prepare('INSERT INTO memory_writes (idempotency_key, path, source_device, created_at, version) VALUES (?, ?, ?, ?, ?)')
        .run(key, relative, deviceId, now, version)
      renameSync(temporary, file)
      renamed = true
      this.db.exec('COMMIT')
      return version
    } catch (error) {
      if (renamed) {
        try {
          if (existedBefore) writeFileSync(file, previousContent, 'utf8')
          else rmSync(file, { force: true })
        } catch {}
      } else {
        try {
          rmSync(temporary, { force: true })
        } catch {}
      }
      try {
        this.db.exec('ROLLBACK')
      } catch {}
      throw error
    }
  }

  private findWrite(key: string, relative: string, deviceId: string): number | null {
    const row = this.db
      .prepare('SELECT path, source_device, version FROM memory_writes WHERE idempotency_key = ?')
      .get(key) as { path: string; source_device: string; version: number } | undefined
    if (!row) return null
    if (row.path !== relative || row.source_device !== deviceId) {
      throw new MemoryRequestError('idempotencyKey was already used for another write')
    }
    return Number(row.version)
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

interface FenceState {
  char: '`' | '~'
  length: number
}

interface SectionBounds {
  headingIndex: number
  endIndex: number
}

function updateFence(line: string, current: FenceState | null): FenceState | null {
  const trimmed = line.trim()
  if (!current) {
    const match = /^(`{3,}|~{3,})/.exec(trimmed)
    if (!match) return null
    const marker = match[1] ?? ''
    const char = marker[0] as '`' | '~'
    if (char === '`' && trimmed.slice(marker.length).includes('`')) return null
    return { char, length: marker.length }
  }
  const closing = new RegExp(`^${current.char}{${current.length},}$`)
  return closing.test(trimmed) ? null : current
}

function findSectionBounds(lines: string[], section: string): SectionBounds | null {
  const target = `## ${section.trim()}`
  let fence: FenceState | null = null
  let headingIndex = -1

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index] ?? ''
    const nextFence = updateFence(line, fence)
    if (fence !== null || nextFence !== null) {
      fence = nextFence
      continue
    }
    const trimmed = line.trim()
    if (headingIndex < 0) {
      if (trimmed === target) headingIndex = index
    } else if (/^#{1,2}(?:\s|$)/.test(trimmed)) {
      return { headingIndex, endIndex: index }
    }
  }

  if (headingIndex < 0) return null
  return { headingIndex, endIndex: lines.length }
}

function appendSectionBlock(content: string, section: string, block: string): string {
  const normalized = content.replaceAll('\r\n', '\n')
  const lines = normalized.split('\n')
  const bounds = findSectionBounds(lines, section)
  if (!bounds) {
    const base = normalized.trimEnd()
    return `${base === '' ? '' : `${base}\n\n`}## ${section.trim()}\n\n${block}\n`
  }
  const head = lines.slice(0, bounds.endIndex).join('\n').trimEnd()
  const hasBody = lines.slice(bounds.headingIndex + 1, bounds.endIndex).some((line) => line.trim() !== '')
  const separator = hasBody ? '\n' : '\n\n'
  const tailLines = lines.slice(bounds.endIndex)
  if (tailLines.length === 0) return `${head}${separator}${block}\n`
  const tail = tailLines.join('\n')
  const normalizedTail = tail.endsWith('\n') ? tail : `${tail}\n`
  return `${head}${separator}${block}\n\n${normalizedTail}`
}

function replaceSection(content: string, section: string, block: string): string {
  const normalized = content.replaceAll('\r\n', '\n')
  const lines = normalized.split('\n')
  const bounds = findSectionBounds(lines, section)
  if (!bounds) {
    const base = normalized.trimEnd()
    return `${base === '' ? '' : `${base}\n\n`}## ${section.trim()}\n\n${block}\n`
  }
  const head = lines.slice(0, bounds.headingIndex + 1).join('\n').trimEnd()
  const tailLines = lines.slice(bounds.endIndex)
  if (tailLines.length === 0) return `${head}\n\n${block}\n`
  const tail = tailLines.join('\n')
  const normalizedTail = tail.endsWith('\n') ? tail : `${tail}\n`
  return `${head}\n\n${block}\n\n${normalizedTail}`
}

function normalizeSection(section: unknown): string | null {
  if (section === undefined || section === null) return null
  if (typeof section !== 'string') throw new MemoryRequestError('section must be a string')
  const trimmed = section.trim()
  if (trimmed === '' || trimmed.length > MAX_SECTION_LENGTH || /[\r\n]/.test(trimmed)) {
    throw new MemoryRequestError('section must be a non-empty single-line string')
  }
  return trimmed
}

function sanitizeCommentName(name: string): string {
  const cleaned = name
    .replace(/[\x00-\x1f\x7f]/g, ' ')
    .replaceAll('-->', '-- >')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 128)
  return cleaned === '' ? 'device' : cleaned
}

