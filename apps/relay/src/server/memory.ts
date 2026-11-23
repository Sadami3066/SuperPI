import type { DatabaseSync } from 'node:sqlite'
import {
  ENTITY_KINDS,
  MEMORY_CONFIDENCES,
  MEMORY_ITEM_STATUSES,
  MEMORY_KINDS,
  MEMORY_SOURCE_KINDS,
  type EntityKindWire,
  type EntitySyncRecord,
  type MemoryConfidenceWire,
  type MemoryItemStatusWire,
  type MemoryKindWire,
  type MemoryPullEntry,
  type MemoryPullResponse,
  type MemoryPushOp,
  type MemoryPushRejectedOp,
  type MemoryPushRequest,
  type MemoryPushResponse,
  type MemorySourceKindWire,
  type MemorySyncRecord,
} from '@superpi/protocol'

export class MemoryRequestError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'MemoryRequestError'
  }
}

const MAX_PUSH_OPS = 200
const MAX_PULL_LIMIT = 500
const DEFAULT_PULL_LIMIT = 200
const MAX_ID_LENGTH = 128
const MAX_CONTENT_CHARS = 8_192
const MAX_SUMMARY_CHARS = 2_048
const MAX_QUOTE_CHARS = 1_024
const MAX_NAME_CHARS = 128
const MAX_TAGS_PER_MEMORY = 20
const MAX_ENTITIES_PER_MEMORY = 20
const MAX_ALIASES_PER_ENTITY = 32
const MAX_IDEMPOTENCY_ROWS = 10_000

export class MemoryStore {
  private readonly db: DatabaseSync

  constructor(db: DatabaseSync) {
    this.db = db
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS memory_v2_meta (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v2_items (
        id TEXT PRIMARY KEY,
        payload_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        updated_by TEXT NOT NULL,
        deleted_at INTEGER,
        server_seq INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v2_entities (
        canonical_key TEXT PRIMARY KEY,
        payload_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        updated_by TEXT NOT NULL,
        deleted_at INTEGER,
        server_seq INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v2_ops (
        seq INTEGER PRIMARY KEY,
        kind TEXT NOT NULL,
        target_key TEXT NOT NULL,
        device_id TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_v2_ops_target
        ON memory_v2_ops(kind, target_key);
      CREATE TABLE IF NOT EXISTS memory_v2_idempotency (
        op_id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        created_at INTEGER NOT NULL
      );
    `)
  }

  version(): number {
    return this.readMetaInt('seq')
  }

  lastDreamAt(): number | null {
    const row = this.db.prepare('SELECT value FROM memory_v2_meta WHERE key = ?').get('last_dream_at') as
      | { value: string }
      | undefined
    if (!row) return null
    const parsed = Number.parseInt(row.value, 10)
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
  }

  push(deviceId: string, rawRequest: MemoryPushRequest, now = Date.now()): { response: MemoryPushResponse; changed: boolean } {
    if (typeof rawRequest !== 'object' || rawRequest === null || Array.isArray(rawRequest)) {
      throw new MemoryRequestError('body must be an object')
    }
    if (!Array.isArray(rawRequest.ops)) {
      throw new MemoryRequestError('ops must be an array')
    }
    if (rawRequest.ops.length > MAX_PUSH_OPS) {
      throw new MemoryRequestError(`too many ops in batch (max ${MAX_PUSH_OPS})`)
    }
    if (
      rawRequest.dreamCompletedAt !== undefined &&
      (typeof rawRequest.dreamCompletedAt !== 'number' || !Number.isSafeInteger(rawRequest.dreamCompletedAt) || rawRequest.dreamCompletedAt <= 0)
    ) {
      throw new MemoryRequestError('dreamCompletedAt must be a positive integer')
    }

    const validatedOps: MemoryPushOp[] = []
    const rejected: MemoryPushRejectedOp[] = []
    for (let index = 0; index < rawRequest.ops.length; index += 1) {
      const rawOp = rawRequest.ops[index]
      try {
        validatedOps.push(validateOp(rawOp, deviceId))
      } catch (error) {
        if (!(error instanceof MemoryRequestError)) throw error
        const rawObj = typeof rawOp === 'object' && rawOp !== null && !Array.isArray(rawOp)
          ? (rawOp as Record<string, unknown>)
          : null
        const rawOpId = rawObj?.opId
        const opId =
          typeof rawOpId === 'string' && rawOpId.trim() !== ''
            ? rawOpId.trim().slice(0, MAX_ID_LENGTH)
            : `index:${index}`
        rejected.push({ opId, reason: error.message })
      }
    }

    let appliedCount = 0
    let skippedCount = 0
    let dreamUpdated = false

    this.db.exec('BEGIN IMMEDIATE')
    try {
      let currentSeq = this.version()

      for (const op of validatedOps) {
        const seen = this.db
          .prepare('SELECT device_id FROM memory_v2_idempotency WHERE op_id = ?')
          .get(op.opId) as { device_id: string } | undefined
        if (seen) {
          if (seen.device_id !== deviceId) {
            throw new MemoryRequestError('opId was already used by another device')
          }
          skippedCount += 1
          continue
        }

        if (op.kind === 'memory') {
          const incoming = op.memory!
          const applied = this.applyMemoryOp(incoming, deviceId, currentSeq + 1, now)
          if (applied) {
            currentSeq += 1
            this.writeMetaInt('seq', currentSeq)
            appliedCount += 1
          } else {
            skippedCount += 1
          }
        } else {
          const incoming = op.entity!
          const applied = this.applyEntityOp(incoming, deviceId, currentSeq + 1, now)
          if (applied) {
            currentSeq += 1
            this.writeMetaInt('seq', currentSeq)
            appliedCount += 1
          } else {
            skippedCount += 1
          }
        }

        this.db
          .prepare('INSERT INTO memory_v2_idempotency (op_id, device_id, seq, created_at) VALUES (?, ?, ?, ?)')
          .run(op.opId, deviceId, currentSeq, now)
      }

      this.pruneIdempotency()

      if (rawRequest.dreamCompletedAt !== undefined) {
        const bounded = Math.min(rawRequest.dreamCompletedAt, now)
        const existingDream = this.lastDreamAt()
        if (bounded > 0 && (existingDream === null || bounded > existingDream)) {
          this.writeMetaInt('last_dream_at', bounded)
          dreamUpdated = true
        }
      }

      this.db.exec('COMMIT')
      const response: MemoryPushResponse = {
        serverSeq: currentSeq,
        appliedCount,
        skippedCount,
        lastDreamAt: this.lastDreamAt(),
      }
      if (rejected.length > 0) {
        response.rejectedCount = rejected.length
        response.rejected = rejected
      }
      return {
        response,
        changed: appliedCount > 0 || dreamUpdated,
      }
    } catch (error) {
      try {
        this.db.exec('ROLLBACK')
      } catch {}
      throw error
    }
  }

  pull(sinceSeq = 0, requestedLimit = DEFAULT_PULL_LIMIT): MemoryPullResponse {
    const since = Number.isSafeInteger(sinceSeq) && sinceSeq >= 0 ? sinceSeq : 0
    const limit = Number.isSafeInteger(requestedLimit)
      ? Math.min(MAX_PULL_LIMIT, Math.max(1, requestedLimit))
      : DEFAULT_PULL_LIMIT

    this.db.exec('BEGIN')
    let committed = false
    try {
      const rows = this.db
        .prepare(
          'SELECT seq, kind, target_key, device_id FROM memory_v2_ops WHERE seq > ? ORDER BY seq ASC LIMIT ?',
        )
        .all(since, limit + 1) as Array<{ seq: number; kind: string; target_key: string; device_id: string }>

      const hasMore = rows.length > limit
      const page = hasMore ? rows.slice(0, limit) : rows
      const entries: MemoryPullEntry[] = []

      const selectMemory = this.db.prepare('SELECT payload_json FROM memory_v2_items WHERE id = ?')
      const selectEntity = this.db.prepare('SELECT payload_json FROM memory_v2_entities WHERE canonical_key = ?')

      for (const row of page) {
        const seq = Number(row.seq)
        if (row.kind === 'memory') {
          const item = selectMemory.get(row.target_key) as { payload_json: string } | undefined
          if (!item) continue
          entries.push({
            seq,
            kind: 'memory',
            deviceId: row.device_id,
            memory: JSON.parse(item.payload_json) as MemorySyncRecord,
          })
        } else if (row.kind === 'entity') {
          const entity = selectEntity.get(row.target_key) as { payload_json: string } | undefined
          if (!entity) continue
          entries.push({
            seq,
            kind: 'entity',
            deviceId: row.device_id,
            entity: JSON.parse(entity.payload_json) as EntitySyncRecord,
          })
        }
      }

      const nextSince = entries.length > 0 ? entries[entries.length - 1]!.seq : since
      const response: MemoryPullResponse = {
        serverSeq: this.version(),
        hasMore,
        nextSince,
        lastDreamAt: this.lastDreamAt(),
        entries,
      }
      this.db.exec('COMMIT')
      committed = true
      return response
    } finally {
      if (!committed) {
        try {
          this.db.exec('ROLLBACK')
        } catch {}
      }
    }
  }

  private applyMemoryOp(incoming: MemorySyncRecord, deviceId: string, nextSeq: number, now: number): boolean {
    const existingRow = this.db
      .prepare('SELECT payload_json, updated_at, updated_by FROM memory_v2_items WHERE id = ?')
      .get(incoming.id) as { payload_json: string; updated_at: number; updated_by: string } | undefined

    if (existingRow) {
      const existing = JSON.parse(existingRow.payload_json) as MemorySyncRecord
      const wins = compareVersionTuple(
        incoming.updatedAt,
        deviceId,
        incoming.revision,
        Number(existingRow.updated_at),
        existingRow.updated_by,
        existing.revision,
      )
      if (!wins) return false
    }

    const stored: MemorySyncRecord = {
      ...incoming,
      updatedByDeviceId: deviceId,
    }
    this.db
      .prepare(
        `INSERT INTO memory_v2_items (id, payload_json, updated_at, updated_by, deleted_at, server_seq)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           payload_json = excluded.payload_json,
           updated_at = excluded.updated_at,
           updated_by = excluded.updated_by,
           deleted_at = excluded.deleted_at,
           server_seq = excluded.server_seq`,
      )
      .run(stored.id, JSON.stringify(stored), stored.updatedAt, deviceId, stored.deletedAt ?? null, nextSeq)

    this.upsertCompactedOp(nextSeq, 'memory', stored.id, deviceId, now)
    return true
  }

  private applyEntityOp(incoming: EntitySyncRecord, deviceId: string, nextSeq: number, now: number): boolean {
    const canonicalKey = normalizeCanonicalKey(incoming.canonicalKey ?? incoming.canonicalName)
    const existingRow = this.db
      .prepare('SELECT payload_json, updated_at, updated_by FROM memory_v2_entities WHERE canonical_key = ?')
      .get(canonicalKey) as { payload_json: string; updated_at: number; updated_by: string } | undefined

    if (!existingRow) {
      const stored: EntitySyncRecord = {
        ...incoming,
        canonicalKey,
        aliases: dedupeAliases(incoming.aliases, incoming.canonicalName),
        updatedByDeviceId: deviceId,
      }
      this.db
        .prepare(
          `INSERT INTO memory_v2_entities (canonical_key, payload_json, updated_at, updated_by, deleted_at, server_seq)
           VALUES (?, ?, ?, ?, ?, ?)`,
        )
        .run(canonicalKey, JSON.stringify(stored), stored.updatedAt, deviceId, stored.deletedAt ?? null, nextSeq)
      this.upsertCompactedOp(nextSeq, 'entity', canonicalKey, deviceId, now)
      return true
    }

    const existing = JSON.parse(existingRow.payload_json) as EntitySyncRecord
    const incomingWins = compareVersionTuple(
      incoming.updatedAt,
      deviceId,
      incoming.revision,
      Number(existingRow.updated_at),
      existingRow.updated_by,
      existing.revision,
    )

    const winner = incomingWins ? incoming : existing
    const loser = incomingWins ? existing : incoming
    const mergedAliases = dedupeAliases([...existing.aliases, ...incoming.aliases], winner.canonicalName)
    const winnerSummary = cleanNullableText(winner.summary)
    const loserSummary = cleanNullableText(loser.summary)
    const mergedSummary = winnerSummary ?? loserSummary

    const aliasesGrew =
      mergedAliases.length !== existing.aliases.length ||
      mergedAliases.some((alias, index) => alias !== existing.aliases[index])
    const summaryFilled = !incomingWins && cleanNullableText(existing.summary) === null && mergedSummary !== null

    if (!incomingWins && !aliasesGrew && !summaryFilled) {
      return false
    }

    const nextDevice = incomingWins ? deviceId : existingRow.updated_by
    const nextUpdatedAt = incomingWins ? incoming.updatedAt : Number(existingRow.updated_at)
    const merged: EntitySyncRecord = {
      canonicalKey,
      id: winner.id,
      canonicalName: winner.canonicalName,
      kind: winner.kind,
      aliases: mergedAliases,
      summary: mergedSummary,
      mergedInto: winner.mergedInto ?? null,
      updatedAt: nextUpdatedAt,
      deletedAt: winner.deletedAt ?? null,
      revision: Math.max(winner.revision, existing.revision + (!incomingWins ? 1 : 0)),
      updatedByDeviceId: nextDevice,
    }

    this.db
      .prepare(
        `UPDATE memory_v2_entities
         SET payload_json = ?, updated_at = ?, updated_by = ?, deleted_at = ?, server_seq = ?
         WHERE canonical_key = ?`,
      )
      .run(JSON.stringify(merged), merged.updatedAt, nextDevice, merged.deletedAt ?? null, nextSeq, canonicalKey)
    this.upsertCompactedOp(nextSeq, 'entity', canonicalKey, deviceId, now)
    return true
  }

  private upsertCompactedOp(seq: number, kind: string, targetKey: string, deviceId: string, now: number): void {
    this.db.prepare('DELETE FROM memory_v2_ops WHERE kind = ? AND target_key = ?').run(kind, targetKey)
    this.db
      .prepare('INSERT INTO memory_v2_ops (seq, kind, target_key, device_id, created_at) VALUES (?, ?, ?, ?, ?)')
      .run(seq, kind, targetKey, deviceId, now)
  }

  private pruneIdempotency(): void {
    const countRow = this.db.prepare('SELECT COUNT(*) AS cnt FROM memory_v2_idempotency').get() as
      | { cnt: number }
      | undefined
    const count = Number(countRow?.cnt ?? 0)
    if (count <= MAX_IDEMPOTENCY_ROWS) return
    const excess = count - MAX_IDEMPOTENCY_ROWS
    this.db
      .prepare(
        'DELETE FROM memory_v2_idempotency WHERE op_id IN (SELECT op_id FROM memory_v2_idempotency ORDER BY created_at ASC, rowid ASC LIMIT ?)',
      )
      .run(excess)
  }

  private readMetaInt(key: string): number {
    const row = this.db.prepare('SELECT value FROM memory_v2_meta WHERE key = ?').get(key) as { value: string } | undefined
    const parsed = row ? Number.parseInt(row.value, 10) : 0
    return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : 0
  }

  private writeMetaInt(key: string, value: number): void {
    this.db
      .prepare('INSERT INTO memory_v2_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value')
      .run(key, String(value))
  }
}

function compareVersionTuple(
  incomingUpdatedAt: number,
  incomingDeviceId: string,
  incomingRevision: number,
  existingUpdatedAt: number,
  existingDeviceId: string,
  existingRevision: number,
): boolean {
  if (incomingUpdatedAt !== existingUpdatedAt) {
    return incomingUpdatedAt > existingUpdatedAt
  }
  if (incomingDeviceId !== existingDeviceId) {
    return incomingDeviceId > existingDeviceId
  }
  return incomingRevision > existingRevision
}

function normalizeCanonicalKey(raw: string): string {
  return raw.trim().toLowerCase()
}

function dedupeAliases(aliases: string[], canonicalName: string): string[] {
  const seen = new Set<string>([canonicalName.trim().toLowerCase()])
  const result: string[] = []
  for (const raw of aliases) {
    const clean = raw.trim()
    if (clean === '') continue
    const key = clean.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    result.push(clean.slice(0, MAX_NAME_CHARS))
    if (result.length >= MAX_ALIASES_PER_ENTITY) break
  }
  return result
}

function validateOp(raw: unknown, deviceId: string): MemoryPushOp {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new MemoryRequestError('op must be an object')
  }
  const obj = raw as Record<string, unknown>
  const opId = requireId(obj.opId, 'opId')
  if (obj.kind === 'memory') {
    return { opId, kind: 'memory', memory: validateMemoryRecord(obj.memory, deviceId) }
  }
  if (obj.kind === 'entity') {
    return { opId, kind: 'entity', entity: validateEntityRecord(obj.entity, deviceId) }
  }
  throw new MemoryRequestError(`unknown op kind: ${String(obj.kind)}`)
}

function validateMemoryRecord(raw: unknown, deviceId: string): MemorySyncRecord {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new MemoryRequestError('memory payload must be an object')
  }
  const obj = raw as Record<string, unknown>
  const id = requireId(obj.id, 'memory.id')
  const kind = requireEnum(obj.kind, MEMORY_KINDS, 'memory.kind') as MemoryKindWire
  const content = requireText(obj.content, MAX_CONTENT_CHARS, 'memory.content')
  const summary = optionalText(obj.summary, MAX_SUMMARY_CHARS, 'memory.summary')
  const tags = requireStringList(obj.tags ?? [], MAX_TAGS_PER_MEMORY, 64, 'memory.tags')
  const entities = requireStringList(obj.entities ?? [], MAX_ENTITIES_PER_MEMORY, MAX_NAME_CHARS, 'memory.entities')
  const confidence = requireEnum(obj.confidence, MEMORY_CONFIDENCES, 'memory.confidence') as MemoryConfidenceWire
  if (typeof obj.importance !== 'number' || !Number.isSafeInteger(obj.importance) || obj.importance < 1 || obj.importance > 5) {
    throw new MemoryRequestError('memory.importance must be an integer 1..5')
  }
  if (typeof obj.pinned !== 'boolean') {
    throw new MemoryRequestError('memory.pinned must be a boolean')
  }
  const validFrom = requireNonNegativeInt(obj.validFrom, 'memory.validFrom')
  const validUntil = optionalNonNegativeInt(obj.validUntil, 'memory.validUntil')
  const status = requireEnum(obj.status, MEMORY_ITEM_STATUSES, 'memory.status') as MemoryItemStatusWire
  const supersededBy = optionalId(obj.supersededBy, 'memory.supersededBy')
  const sourceKind = requireEnum(obj.sourceKind, MEMORY_SOURCE_KINDS, 'memory.sourceKind') as MemorySourceKindWire
  const sourceSessionId = optionalId(obj.sourceSessionId, 'memory.sourceSessionId')
  const sourceEntryId = optionalId(obj.sourceEntryId, 'memory.sourceEntryId')
  const sourceQuote = optionalText(obj.sourceQuote, MAX_QUOTE_CHARS, 'memory.sourceQuote')
  const createdAt = requireNonNegativeInt(obj.createdAt, 'memory.createdAt')
  const updatedAt = requireNonNegativeInt(obj.updatedAt, 'memory.updatedAt')
  const deletedAt = optionalNonNegativeInt(obj.deletedAt, 'memory.deletedAt')
  if (typeof obj.revision !== 'number' || !Number.isSafeInteger(obj.revision) || obj.revision < 1) {
    throw new MemoryRequestError('memory.revision must be a positive integer')
  }

  return {
    id,
    kind,
    content,
    summary,
    tags,
    entities,
    confidence,
    importance: obj.importance,
    pinned: obj.pinned,
    validFrom,
    validUntil,
    status,
    supersededBy,
    sourceKind,
    sourceSessionId,
    sourceEntryId,
    sourceQuote,
    createdAt,
    updatedAt,
    deletedAt,
    revision: obj.revision,
    updatedByDeviceId: optionalId(obj.updatedByDeviceId, 'memory.updatedByDeviceId') ?? deviceId,
  }
}

function validateEntityRecord(raw: unknown, deviceId: string): EntitySyncRecord {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new MemoryRequestError('entity payload must be an object')
  }
  const obj = raw as Record<string, unknown>
  const id = requireId(obj.id, 'entity.id')
  const canonicalName = requireText(obj.canonicalName, MAX_NAME_CHARS, 'entity.canonicalName')
  const canonicalKey = optionalText(obj.canonicalKey, MAX_NAME_CHARS, 'entity.canonicalKey')?.toLowerCase() ?? canonicalName.toLowerCase()
  const kind = requireEnum(obj.kind, ENTITY_KINDS, 'entity.kind') as EntityKindWire
  const aliases = requireStringList(obj.aliases ?? [], MAX_ALIASES_PER_ENTITY, MAX_NAME_CHARS, 'entity.aliases')
  const summary = optionalText(obj.summary, MAX_SUMMARY_CHARS, 'entity.summary')
  const mergedInto = optionalText(obj.mergedInto, MAX_NAME_CHARS, 'entity.mergedInto')
  const updatedAt = requireNonNegativeInt(obj.updatedAt, 'entity.updatedAt')
  const deletedAt = optionalNonNegativeInt(obj.deletedAt, 'entity.deletedAt')
  if (typeof obj.revision !== 'number' || !Number.isSafeInteger(obj.revision) || obj.revision < 1) {
    throw new MemoryRequestError('entity.revision must be a positive integer')
  }

  return {
    canonicalKey,
    id,
    canonicalName,
    kind,
    aliases,
    summary,
    mergedInto,
    updatedAt,
    deletedAt,
    revision: obj.revision,
    updatedByDeviceId: optionalId(obj.updatedByDeviceId, 'entity.updatedByDeviceId') ?? deviceId,
  }
}

function requireId(value: unknown, field: string): string {
  if (typeof value !== 'string') throw new MemoryRequestError(`${field} is required`)
  const clean = value.trim()
  if (clean === '' || clean.length > MAX_ID_LENGTH) throw new MemoryRequestError(`${field} length invalid`)
  return clean
}

function optionalId(value: unknown, field: string): string | null {
  if (value === undefined || value === null) return null
  if (typeof value !== 'string') throw new MemoryRequestError(`${field} must be a string`)
  const clean = value.trim()
  if (clean === '') return null
  if (clean.length > MAX_ID_LENGTH) throw new MemoryRequestError(`${field} length invalid`)
  return clean
}

function requireText(value: unknown, maxChars: number, field: string): string {
  if (typeof value !== 'string') throw new MemoryRequestError(`${field} is required`)
  const clean = value.trim()
  if (clean === '' || clean.length > maxChars) throw new MemoryRequestError(`${field} length invalid`)
  return clean
}

function optionalText(value: unknown, maxChars: number, field: string): string | null {
  if (value === undefined || value === null) return null
  if (typeof value !== 'string') throw new MemoryRequestError(`${field} must be a string`)
  const clean = value.trim()
  if (clean === '') return null
  return clean.slice(0, maxChars)
}

function cleanNullableText(value: string | null | undefined): string | null {
  if (value === undefined || value === null) return null
  const clean = value.trim()
  return clean === '' ? null : clean
}

function requireEnum(value: unknown, allowed: readonly string[], field: string): string {
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new MemoryRequestError(`invalid ${field}`)
  }
  return value
}

function requireNonNegativeInt(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new MemoryRequestError(`${field} must be a non-negative integer`)
  }
  return value
}

function optionalNonNegativeInt(value: unknown, field: string): number | null {
  if (value === undefined || value === null) return null
  return requireNonNegativeInt(value, field)
}

function requireStringList(value: unknown, maxItems: number, maxLen: number, field: string): string[] {
  if (!Array.isArray(value)) throw new MemoryRequestError(`${field} must be an array`)
  const seen = new Set<string>()
  const result: string[] = []
  for (const item of value) {
    if (typeof item !== 'string') throw new MemoryRequestError(`${field} entries must be strings`)
    const clean = item.trim().slice(0, maxLen)
    if (clean === '') continue
    if (!seen.has(clean)) {
      seen.add(clean)
      result.push(clean)
      if (result.length >= maxItems) break
    }
  }
  return result
}
