import type { DatabaseSync } from 'node:sqlite'
import {
  MEMORY_BLOCK_INJECTION_MODES,
  MEMORY_BLOCK_OWNERS,
  MEMORY_BLOCK_STATUSES,
  MEMORY_EPISTEMIC_TIERS,
  MEMORY_EVENT_KINDS,
  MEMORY_EVENT_TIME_PRECISIONS,
  MEMORY_ITEM_STATUSES,
  MEMORY_PROPOSAL_STATUSES,
  MEMORY_SENSITIVITIES,
  MEMORY_SOURCE_KINDS,
  type MemoryBlockInjectionModeWire,
  type MemoryBlockOwnerWire,
  type MemoryBlockStatusWire,
  type MemoryBlockSyncRecord,
  type MemoryEntitySyncRecord,
  type MemoryEpistemicTierWire,
  type MemoryEventEntityLink,
  type MemoryEventKindWire,
  type MemoryEventSyncRecord,
  type MemoryEventTimePrecisionWire,
  type MemoryItemStatusWire,
  type MemoryProposalStatusWire,
  type MemoryProposalSyncRecord,
  type MemoryRelationSyncRecord,
  type MemorySensitivityWire,
  type MemorySourceKindWire,
  type MemoryV3OpKind,
  type MemoryV3PullEntry,
  type MemoryV3PullResponse,
  type MemoryV3PushOp,
  type MemoryV3PushRejectedOp,
  type MemoryV3PushRequest,
  type MemoryV3PushResponse,
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
const MAX_BLOCK_CONTENT_CHARS = 32_768
const MAX_EVENT_CONTENT_CHARS = 8_192
const MAX_SUMMARY_CHARS = 2_048
const MAX_JSON_OBJECT_CHARS = 8_192
const MAX_QUOTE_CHARS = 1_024
const MAX_NAME_CHARS = 128
const MAX_SHORT_TOKEN_CHARS = 64
const MAX_MAX_CHARS = 1_000_000
const MAX_LIST_ITEMS = 64
const MAX_IDEMPOTENCY_ROWS = 10_000

const TABLE_BY_KIND: Record<MemoryV3OpKind, string> = {
  block: 'memory_v3_blocks',
  entity: 'memory_v3_entities',
  event: 'memory_v3_events',
  relation: 'memory_v3_relations',
  proposal: 'memory_v3_proposals',
}

interface SyncMetadataFields {
  id: string
  updatedAt: number
  deletedAt?: number | null
  revision: number
  updatedByDeviceId?: string | null
}

export class MemoryStore {
  private readonly db: DatabaseSync

  constructor(db: DatabaseSync) {
    this.db = db
    this.db.exec(`
      DROP TABLE IF EXISTS memory_v2_meta;
      DROP TABLE IF EXISTS memory_v2_items;
      DROP TABLE IF EXISTS memory_v2_entities;
      DROP TABLE IF EXISTS memory_v2_ops;
      DROP TABLE IF EXISTS memory_v2_idempotency;

      CREATE TABLE IF NOT EXISTS memory_v3_meta (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v3_blocks (
        id TEXT PRIMARY KEY,
        payload_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        updated_by TEXT NOT NULL,
        deleted_at INTEGER,
        server_seq INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v3_entities (
        id TEXT PRIMARY KEY,
        payload_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        updated_by TEXT NOT NULL,
        deleted_at INTEGER,
        server_seq INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v3_events (
        id TEXT PRIMARY KEY,
        payload_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        updated_by TEXT NOT NULL,
        deleted_at INTEGER,
        server_seq INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v3_relations (
        id TEXT PRIMARY KEY,
        payload_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        updated_by TEXT NOT NULL,
        deleted_at INTEGER,
        server_seq INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v3_proposals (
        id TEXT PRIMARY KEY,
        payload_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        updated_by TEXT NOT NULL,
        deleted_at INTEGER,
        server_seq INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS memory_v3_ops (
        seq INTEGER PRIMARY KEY,
        kind TEXT NOT NULL,
        target_id TEXT NOT NULL,
        device_id TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_v3_ops_target
        ON memory_v3_ops(kind, target_id);
      CREATE TABLE IF NOT EXISTS memory_v3_idempotency (
        op_id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        created_at INTEGER NOT NULL
      );
    `)
    const hasLeases = this.db
      .prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'memory_leases' LIMIT 1")
      .get()
    if (hasLeases) {
      this.db.prepare("DELETE FROM memory_leases WHERE name = 'dream'").run()
    }
  }

  version(): number {
    return this.readMetaInt('seq')
  }

  lastConsolidationAt(): number | null {
    const row = this.db.prepare('SELECT value FROM memory_v3_meta WHERE key = ?').get('last_consolidation_at') as
      | { value: string }
      | undefined
    if (!row) return null
    const parsed = Number.parseInt(row.value, 10)
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
  }

  push(
    deviceId: string,
    rawRequest: MemoryV3PushRequest,
    now = Date.now(),
  ): { response: MemoryV3PushResponse; changed: boolean } {
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
      rawRequest.consolidationCompletedAt !== undefined &&
      (typeof rawRequest.consolidationCompletedAt !== 'number' ||
        !Number.isSafeInteger(rawRequest.consolidationCompletedAt) ||
        rawRequest.consolidationCompletedAt <= 0)
    ) {
      throw new MemoryRequestError('consolidationCompletedAt must be a positive integer')
    }

    const validatedOps: MemoryV3PushOp[] = []
    const rejected: MemoryV3PushRejectedOp[] = []
    for (let index = 0; index < rawRequest.ops.length; index += 1) {
      const rawOp = rawRequest.ops[index]
      try {
        validatedOps.push(validateOp(rawOp, deviceId))
      } catch (error) {
        if (!(error instanceof MemoryRequestError)) throw error
        const rawObj =
          typeof rawOp === 'object' && rawOp !== null && !Array.isArray(rawOp)
            ? (rawOp as unknown as Record<string, unknown>)
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
    let consolidationUpdated = false

    this.db.exec('BEGIN IMMEDIATE')
    try {
      let currentSeq = this.version()

      for (const op of validatedOps) {
        const seen = this.db
          .prepare('SELECT device_id FROM memory_v3_idempotency WHERE op_id = ?')
          .get(op.opId) as { device_id: string } | undefined
        if (seen) {
          if (seen.device_id !== deviceId) {
            throw new MemoryRequestError('opId was already used by another device')
          }
          skippedCount += 1
          continue
        }

        const nextSeq = currentSeq + 1
        let applied = false
        switch (op.kind) {
          case 'block':
            applied = this.applyLwwRecord('memory_v3_blocks', 'block', op.block!, deviceId, nextSeq, now)
            break
          case 'entity':
            applied = this.applyEntityOp(op.entity!, deviceId, nextSeq, now)
            break
          case 'event':
            applied = this.applyLwwRecord('memory_v3_events', 'event', op.event!, deviceId, nextSeq, now)
            break
          case 'relation':
            applied = this.applyLwwRecord('memory_v3_relations', 'relation', op.relation!, deviceId, nextSeq, now)
            break
          case 'proposal':
            applied = this.applyLwwRecord('memory_v3_proposals', 'proposal', op.proposal!, deviceId, nextSeq, now)
            break
        }

        if (applied) {
          currentSeq = nextSeq
          this.writeMetaInt('seq', currentSeq)
          appliedCount += 1
        } else {
          skippedCount += 1
        }

        this.db
          .prepare('INSERT INTO memory_v3_idempotency (op_id, device_id, seq, created_at) VALUES (?, ?, ?, ?)')
          .run(op.opId, deviceId, currentSeq, now)
      }

      this.pruneIdempotency()

      if (rawRequest.consolidationCompletedAt !== undefined) {
        const bounded = Math.min(rawRequest.consolidationCompletedAt, now)
        const existing = this.lastConsolidationAt()
        if (bounded > 0 && (existing === null || bounded > existing)) {
          this.writeMetaInt('last_consolidation_at', bounded)
          consolidationUpdated = true
        }
      }

      this.db.exec('COMMIT')
      const response: MemoryV3PushResponse = {
        serverSeq: currentSeq,
        appliedCount,
        skippedCount,
        lastConsolidationAt: this.lastConsolidationAt(),
      }
      if (rejected.length > 0) {
        response.rejectedCount = rejected.length
        response.rejected = rejected
      }
      return {
        response,
        changed: appliedCount > 0 || consolidationUpdated,
      }
    } catch (error) {
      try {
        this.db.exec('ROLLBACK')
      } catch {}
      throw error
    }
  }

  pull(sinceSeq = 0, requestedLimit = DEFAULT_PULL_LIMIT): MemoryV3PullResponse {
    const since = Number.isSafeInteger(sinceSeq) && sinceSeq >= 0 ? sinceSeq : 0
    const limit = Number.isSafeInteger(requestedLimit)
      ? Math.min(MAX_PULL_LIMIT, Math.max(1, requestedLimit))
      : DEFAULT_PULL_LIMIT

    this.db.exec('BEGIN')
    let committed = false
    try {
      const rows = this.db
        .prepare(
          'SELECT seq, kind, target_id, device_id FROM memory_v3_ops WHERE seq > ? ORDER BY seq ASC LIMIT ?',
        )
        .all(since, limit + 1) as Array<{ seq: number; kind: string; target_id: string; device_id: string }>

      const hasMore = rows.length > limit
      const page = hasMore ? rows.slice(0, limit) : rows
      const entries: MemoryV3PullEntry[] = []

      const selectBlock = this.db.prepare('SELECT payload_json FROM memory_v3_blocks WHERE id = ?')
      const selectEntity = this.db.prepare('SELECT payload_json FROM memory_v3_entities WHERE id = ?')
      const selectEvent = this.db.prepare('SELECT payload_json FROM memory_v3_events WHERE id = ?')
      const selectRelation = this.db.prepare('SELECT payload_json FROM memory_v3_relations WHERE id = ?')
      const selectProposal = this.db.prepare('SELECT payload_json FROM memory_v3_proposals WHERE id = ?')

      for (const row of page) {
        const seq = Number(row.seq)
        const kind = row.kind as MemoryV3OpKind
        switch (kind) {
          case 'block': {
            const item = selectBlock.get(row.target_id) as { payload_json: string } | undefined
            if (!item) continue
            entries.push({
              seq,
              kind,
              deviceId: row.device_id,
              block: JSON.parse(item.payload_json) as MemoryBlockSyncRecord,
            })
            break
          }
          case 'entity': {
            const item = selectEntity.get(row.target_id) as { payload_json: string } | undefined
            if (!item) continue
            entries.push({
              seq,
              kind,
              deviceId: row.device_id,
              entity: JSON.parse(item.payload_json) as MemoryEntitySyncRecord,
            })
            break
          }
          case 'event': {
            const item = selectEvent.get(row.target_id) as { payload_json: string } | undefined
            if (!item) continue
            entries.push({
              seq,
              kind,
              deviceId: row.device_id,
              event: JSON.parse(item.payload_json) as MemoryEventSyncRecord,
            })
            break
          }
          case 'relation': {
            const item = selectRelation.get(row.target_id) as { payload_json: string } | undefined
            if (!item) continue
            entries.push({
              seq,
              kind,
              deviceId: row.device_id,
              relation: JSON.parse(item.payload_json) as MemoryRelationSyncRecord,
            })
            break
          }
          case 'proposal': {
            const item = selectProposal.get(row.target_id) as { payload_json: string } | undefined
            if (!item) continue
            entries.push({
              seq,
              kind,
              deviceId: row.device_id,
              proposal: JSON.parse(item.payload_json) as MemoryProposalSyncRecord,
            })
            break
          }
        }
      }

      const nextSince = entries.length > 0 ? entries[entries.length - 1]!.seq : since
      const response: MemoryV3PullResponse = {
        serverSeq: this.version(),
        hasMore,
        nextSince,
        lastConsolidationAt: this.lastConsolidationAt(),
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

  private applyLwwRecord<T extends SyncMetadataFields>(
    tableName: string,
    kind: MemoryV3OpKind,
    incoming: T,
    deviceId: string,
    nextSeq: number,
    now: number,
  ): boolean {
    const targetTable = TABLE_BY_KIND[kind]
    if (targetTable !== tableName) throw new Error('table/kind mismatch')
    const existingRow = this.db
      .prepare(`SELECT payload_json, updated_at, updated_by FROM ${targetTable} WHERE id = ?`)
      .get(incoming.id) as { payload_json: string; updated_at: number; updated_by: string } | undefined

    if (existingRow) {
      const existing = JSON.parse(existingRow.payload_json) as SyncMetadataFields
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

    const stored: T = {
      ...incoming,
      updatedByDeviceId: deviceId,
    }
    this.db
      .prepare(
        `INSERT INTO ${targetTable} (id, payload_json, updated_at, updated_by, deleted_at, server_seq)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           payload_json = excluded.payload_json,
           updated_at = excluded.updated_at,
           updated_by = excluded.updated_by,
           deleted_at = excluded.deleted_at,
           server_seq = excluded.server_seq`,
      )
      .run(stored.id, JSON.stringify(stored), stored.updatedAt, deviceId, stored.deletedAt ?? null, nextSeq)

    this.upsertCompactedOp(nextSeq, kind, stored.id, deviceId, now)
    return true
  }

  private applyEntityOp(
    incoming: MemoryEntitySyncRecord,
    deviceId: string,
    nextSeq: number,
    now: number,
  ): boolean {
    const existingRow = this.db
      .prepare('SELECT payload_json, updated_at, updated_by FROM memory_v3_entities WHERE id = ?')
      .get(incoming.id) as { payload_json: string; updated_at: number; updated_by: string } | undefined

    if (!existingRow) {
      const stored: MemoryEntitySyncRecord = {
        ...incoming,
        aliases: dedupeAliases(incoming.aliases, incoming.canonicalName),
        updatedByDeviceId: deviceId,
      }
      this.db
        .prepare(
          `INSERT INTO memory_v3_entities (id, payload_json, updated_at, updated_by, deleted_at, server_seq)
           VALUES (?, ?, ?, ?, ?, ?)`,
        )
        .run(stored.id, JSON.stringify(stored), stored.updatedAt, deviceId, stored.deletedAt ?? null, nextSeq)
      this.upsertCompactedOp(nextSeq, 'entity', stored.id, deviceId, now)
      return true
    }

    const existing = JSON.parse(existingRow.payload_json) as MemoryEntitySyncRecord
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
    const winnerAliasesNormalized = dedupeAliases(winner.aliases, winner.canonicalName)
    const mergedAliases = dedupeAliases([...existing.aliases, ...incoming.aliases], winner.canonicalName)
    const winnerAttrs = parseJsonObjectMap(winner.attributesJson)
    const loserAttrs = parseJsonObjectMap(loser.attributesJson)
    const existingAttrs = parseJsonObjectMap(existing.attributesJson)
    const mergedAttrs: Record<string, unknown> = { ...loserAttrs, ...winnerAttrs }
    const mergedAttributesJson = JSON.stringify(mergedAttrs)

    const winnerSummary = cleanNullableText(winner.summary)
    const loserSummary = cleanNullableText(loser.summary)
    const mergedSummary = winnerSummary ?? loserSummary
    const mergedCreatedAt =
      existing.createdAt > 0 ? Math.min(existing.createdAt, incoming.createdAt) : winner.createdAt

    const aliasesGrew =
      mergedAliases.length !== existing.aliases.length ||
      mergedAliases.some((alias, index) => alias !== existing.aliases[index])
    const attrsGained = Object.keys(mergedAttrs).some((key) => !(key in existingAttrs))
    const summaryFilled = !incomingWins && cleanNullableText(existing.summary) === null && mergedSummary !== null
    const mergedFromExisting =
      incomingWins &&
      (mergedAliases.length !== winnerAliasesNormalized.length ||
        mergedAliases.some((alias, index) => alias !== winnerAliasesNormalized[index]) ||
        Object.keys(mergedAttrs).some((key) => !(key in winnerAttrs)) ||
        mergedSummary !== winnerSummary ||
        mergedCreatedAt < winner.createdAt)

    if (!incomingWins && !aliasesGrew && !attrsGained && !summaryFilled) {
      return false
    }

    const nextDevice = incomingWins ? deviceId : existingRow.updated_by
    const nextUpdatedAt = incomingWins ? incoming.updatedAt : Number(existingRow.updated_at)
    const nextRevision = mergedFromExisting
      ? Math.max(winner.revision, existing.revision) + 1
      : Math.max(winner.revision, existing.revision + (!incomingWins ? 1 : 0))
    const merged: MemoryEntitySyncRecord = {
      id: winner.id,
      canonicalName: winner.canonicalName,
      entityType: winner.entityType,
      aliases: mergedAliases,
      attributesJson: mergedAttributesJson,
      summary: mergedSummary,
      domain: winner.domain,
      sensitivity: winner.sensitivity,
      pinned: winner.pinned,
      createdAt: mergedCreatedAt,
      updatedAt: nextUpdatedAt,
      deletedAt: winner.deletedAt ?? null,
      revision: nextRevision,
      mergedInto: winner.mergedInto ?? null,
      updatedByDeviceId: nextDevice,
    }

    this.db
      .prepare(
        `UPDATE memory_v3_entities
         SET payload_json = ?, updated_at = ?, updated_by = ?, deleted_at = ?, server_seq = ?
         WHERE id = ?`,
      )
      .run(JSON.stringify(merged), merged.updatedAt, nextDevice, merged.deletedAt ?? null, nextSeq, merged.id)
    this.upsertCompactedOp(nextSeq, 'entity', merged.id, deviceId, now)
    return true
  }

  private upsertCompactedOp(seq: number, kind: MemoryV3OpKind, targetId: string, deviceId: string, now: number): void {
    this.db.prepare('DELETE FROM memory_v3_ops WHERE kind = ? AND target_id = ?').run(kind, targetId)
    this.db
      .prepare('INSERT INTO memory_v3_ops (seq, kind, target_id, device_id, created_at) VALUES (?, ?, ?, ?, ?)')
      .run(seq, kind, targetId, deviceId, now)
  }

  private pruneIdempotency(): void {
    const countRow = this.db.prepare('SELECT COUNT(*) AS cnt FROM memory_v3_idempotency').get() as
      | { cnt: number }
      | undefined
    const count = Number(countRow?.cnt ?? 0)
    if (count <= MAX_IDEMPOTENCY_ROWS) return
    const excess = count - MAX_IDEMPOTENCY_ROWS
    this.db
      .prepare(
        'DELETE FROM memory_v3_idempotency WHERE op_id IN (SELECT op_id FROM memory_v3_idempotency ORDER BY created_at ASC, rowid ASC LIMIT ?)',
      )
      .run(excess)
  }

  private readMetaInt(key: string): number {
    const row = this.db.prepare('SELECT value FROM memory_v3_meta WHERE key = ?').get(key) as { value: string } | undefined
    const parsed = row ? Number.parseInt(row.value, 10) : 0
    return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : 0
  }

  private writeMetaInt(key: string, value: number): void {
    this.db
      .prepare('INSERT INTO memory_v3_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value')
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
    if (result.length >= MAX_LIST_ITEMS) break
  }
  return result
}

function parseJsonObjectMap(raw: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(raw) as unknown
    if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
  } catch {}
  return {}
}

function validateOp(raw: unknown, deviceId: string): MemoryV3PushOp {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new MemoryRequestError('op must be an object')
  }
  const obj = raw as Record<string, unknown>
  const opId = requireId(obj.opId, 'opId')
  switch (obj.kind) {
    case 'block':
      return { opId, kind: 'block', block: validateBlockRecord(obj.block, deviceId) }
    case 'entity':
      return { opId, kind: 'entity', entity: validateEntityRecord(obj.entity, deviceId) }
    case 'event':
      return { opId, kind: 'event', event: validateEventRecord(obj.event, deviceId) }
    case 'relation':
      return { opId, kind: 'relation', relation: validateRelationRecord(obj.relation, deviceId) }
    case 'proposal':
      return { opId, kind: 'proposal', proposal: validateProposalRecord(obj.proposal, deviceId) }
    default:
      throw new MemoryRequestError(`unknown op kind: ${String(obj.kind)}`)
  }
}

function validateBlockRecord(raw: unknown, deviceId: string): MemoryBlockSyncRecord {
  const obj = requireObject(raw, 'block payload')
  const id = requireId(obj.id, 'block.id')
  const slug = requireText(obj.slug, MAX_NAME_CHARS, 'block.slug')
  const title = requireText(obj.title, MAX_NAME_CHARS, 'block.title')
  const purpose = requireText(obj.purpose, MAX_SUMMARY_CHARS, 'block.purpose')
  const schemaHint = optionalText(obj.schemaHint, MAX_SUMMARY_CHARS, 'block.schemaHint')
  const content = allowEmptyText(obj.content, MAX_BLOCK_CONTENT_CHARS, 'block.content')
  const domain = requireText(obj.domain, MAX_SHORT_TOKEN_CHARS, 'block.domain')
  const sensitivity = requireEnum(obj.sensitivity, MEMORY_SENSITIVITIES, 'block.sensitivity') as MemorySensitivityWire
  const injectionMode = requireEnum(
    obj.injectionMode,
    MEMORY_BLOCK_INJECTION_MODES,
    'block.injectionMode',
  ) as MemoryBlockInjectionModeWire
  if (
    typeof obj.maxChars !== 'number' ||
    !Number.isSafeInteger(obj.maxChars) ||
    obj.maxChars < 0 ||
    obj.maxChars > MAX_MAX_CHARS
  ) {
    throw new MemoryRequestError('block.maxChars invalid')
  }
  const owner = requireEnum(obj.owner, MEMORY_BLOCK_OWNERS, 'block.owner') as MemoryBlockOwnerWire
  const pinned = requireBoolean(obj.pinned, 'block.pinned')
  const status = requireEnum(obj.status, MEMORY_BLOCK_STATUSES, 'block.status') as MemoryBlockStatusWire
  if (typeof obj.sortOrder !== 'number' || !Number.isSafeInteger(obj.sortOrder)) {
    throw new MemoryRequestError('block.sortOrder must be an integer')
  }
  const createdAt = requireNonNegativeInt(obj.createdAt, 'block.createdAt')
  const updatedAt = requireNonNegativeInt(obj.updatedAt, 'block.updatedAt')
  const deletedAt = optionalNonNegativeInt(obj.deletedAt, 'block.deletedAt')
  const revision = requirePositiveInt(obj.revision, 'block.revision')

  return {
    id,
    slug,
    title,
    purpose,
    schemaHint,
    content,
    domain,
    sensitivity,
    injectionMode,
    maxChars: obj.maxChars,
    owner,
    pinned,
    status,
    sortOrder: obj.sortOrder,
    createdAt,
    updatedAt,
    deletedAt,
    revision,
    updatedByDeviceId: optionalId(obj.updatedByDeviceId, 'block.updatedByDeviceId') ?? deviceId,
  }
}

function validateEntityRecord(raw: unknown, deviceId: string): MemoryEntitySyncRecord {
  const obj = requireObject(raw, 'entity payload')
  const id = requireId(obj.id, 'entity.id')
  const canonicalName = requireText(obj.canonicalName, MAX_NAME_CHARS, 'entity.canonicalName')
  const entityType = requireText(obj.entityType, MAX_SHORT_TOKEN_CHARS, 'entity.entityType')
  const aliases = requireStringList(obj.aliases ?? [], MAX_LIST_ITEMS, MAX_NAME_CHARS, 'entity.aliases')
  const attributesJson = normalizeJsonObjectText(obj.attributesJson, MAX_JSON_OBJECT_CHARS, 'entity.attributesJson')
  const summary = optionalText(obj.summary, MAX_SUMMARY_CHARS, 'entity.summary')
  const domain = requireText(obj.domain, MAX_SHORT_TOKEN_CHARS, 'entity.domain')
  const sensitivity = requireEnum(obj.sensitivity, MEMORY_SENSITIVITIES, 'entity.sensitivity') as MemorySensitivityWire
  const pinned = requireBoolean(obj.pinned, 'entity.pinned')
  const createdAt = requireNonNegativeInt(obj.createdAt, 'entity.createdAt')
  const updatedAt = requireNonNegativeInt(obj.updatedAt, 'entity.updatedAt')
  const deletedAt = optionalNonNegativeInt(obj.deletedAt, 'entity.deletedAt')
  const revision = requirePositiveInt(obj.revision, 'entity.revision')
  const mergedInto = optionalId(obj.mergedInto, 'entity.mergedInto')

  return {
    id,
    canonicalName,
    entityType,
    aliases,
    attributesJson,
    summary,
    domain,
    sensitivity,
    pinned,
    createdAt,
    updatedAt,
    deletedAt,
    revision,
    mergedInto,
    updatedByDeviceId: optionalId(obj.updatedByDeviceId, 'entity.updatedByDeviceId') ?? deviceId,
  }
}

function validateEventRecord(raw: unknown, deviceId: string): MemoryEventSyncRecord {
  const obj = requireObject(raw, 'event payload')
  const id = requireId(obj.id, 'event.id')
  const eventKind = requireEnum(obj.eventKind, MEMORY_EVENT_KINDS, 'event.eventKind') as MemoryEventKindWire
  const content = requireText(obj.content, MAX_EVENT_CONTENT_CHARS, 'event.content')
  const summary = optionalText(obj.summary, MAX_SUMMARY_CHARS, 'event.summary')
  const qualifiersJson = normalizeJsonObjectText(obj.qualifiersJson, MAX_JSON_OBJECT_CHARS, 'event.qualifiersJson')
  const semanticKeywords = requireStringList(
    obj.semanticKeywords ?? [],
    MAX_LIST_ITEMS,
    MAX_NAME_CHARS,
    'event.semanticKeywords',
  )
  const tags = requireStringList(obj.tags ?? [], MAX_LIST_ITEMS, MAX_SHORT_TOKEN_CHARS, 'event.tags')
  const domain = requireText(obj.domain, MAX_SHORT_TOKEN_CHARS, 'event.domain')
  const sensitivity = requireEnum(obj.sensitivity, MEMORY_SENSITIVITIES, 'event.sensitivity') as MemorySensitivityWire
  const epistemicTier = requireEnum(
    obj.epistemicTier,
    MEMORY_EPISTEMIC_TIERS,
    'event.epistemicTier',
  ) as MemoryEpistemicTierWire
  if (typeof obj.importance !== 'number' || !Number.isSafeInteger(obj.importance) || obj.importance < 1 || obj.importance > 5) {
    throw new MemoryRequestError('event.importance must be an integer 1..5')
  }
  const pinned = requireBoolean(obj.pinned, 'event.pinned')
  const eventTime = requireNonNegativeInt(obj.eventTime, 'event.eventTime')
  const eventTimePrecision = requireEnum(
    obj.eventTimePrecision,
    MEMORY_EVENT_TIME_PRECISIONS,
    'event.eventTimePrecision',
  ) as MemoryEventTimePrecisionWire
  const validFrom = requireNonNegativeInt(obj.validFrom, 'event.validFrom')
  const validUntil = optionalNonNegativeInt(obj.validUntil, 'event.validUntil')
  const status = requireEnum(obj.status, MEMORY_ITEM_STATUSES, 'event.status') as MemoryItemStatusWire
  const supersededBy = optionalId(obj.supersededBy, 'event.supersededBy')
  const sourceKind = requireEnum(obj.sourceKind, MEMORY_SOURCE_KINDS, 'event.sourceKind') as MemorySourceKindWire
  const sourceSessionId = optionalId(obj.sourceSessionId, 'event.sourceSessionId')
  const sourceEntryId = optionalId(obj.sourceEntryId, 'event.sourceEntryId')
  const sourceQuote = optionalText(obj.sourceQuote, MAX_QUOTE_CHARS, 'event.sourceQuote')
  const entityLinks = requireEntityLinks(obj.entityLinks ?? [])
  const createdAt = requireNonNegativeInt(obj.createdAt, 'event.createdAt')
  const updatedAt = requireNonNegativeInt(obj.updatedAt, 'event.updatedAt')
  const deletedAt = optionalNonNegativeInt(obj.deletedAt, 'event.deletedAt')
  const revision = requirePositiveInt(obj.revision, 'event.revision')

  return {
    id,
    eventKind,
    content,
    summary,
    qualifiersJson,
    semanticKeywords,
    tags,
    domain,
    sensitivity,
    epistemicTier,
    importance: obj.importance,
    pinned,
    eventTime,
    eventTimePrecision,
    validFrom,
    validUntil,
    status,
    supersededBy,
    sourceKind,
    sourceSessionId,
    sourceEntryId,
    sourceQuote,
    entityLinks,
    createdAt,
    updatedAt,
    deletedAt,
    revision,
    updatedByDeviceId: optionalId(obj.updatedByDeviceId, 'event.updatedByDeviceId') ?? deviceId,
  }
}

function validateRelationRecord(raw: unknown, deviceId: string): MemoryRelationSyncRecord {
  const obj = requireObject(raw, 'relation payload')
  const id = requireId(obj.id, 'relation.id')
  const fromEntityId = requireId(obj.fromEntityId, 'relation.fromEntityId')
  const relationType = requireText(obj.relationType, MAX_SHORT_TOKEN_CHARS, 'relation.relationType')
  const toEntityId = requireId(obj.toEntityId, 'relation.toEntityId')
  const label = optionalText(obj.label, MAX_NAME_CHARS, 'relation.label')
  const attributesJson = normalizeJsonObjectText(obj.attributesJson, MAX_JSON_OBJECT_CHARS, 'relation.attributesJson')
  const validFrom = requireNonNegativeInt(obj.validFrom, 'relation.validFrom')
  const validUntil = optionalNonNegativeInt(obj.validUntil, 'relation.validUntil')
  const invalidatedAt = optionalNonNegativeInt(obj.invalidatedAt, 'relation.invalidatedAt')
  const invalidatedBy = optionalId(obj.invalidatedBy, 'relation.invalidatedBy')
  const epistemicTier = requireEnum(
    obj.epistemicTier,
    MEMORY_EPISTEMIC_TIERS,
    'relation.epistemicTier',
  ) as MemoryEpistemicTierWire
  const domain = requireText(obj.domain, MAX_SHORT_TOKEN_CHARS, 'relation.domain')
  const sensitivity = requireEnum(
    obj.sensitivity,
    MEMORY_SENSITIVITIES,
    'relation.sensitivity',
  ) as MemorySensitivityWire
  const sourceEventId = optionalId(obj.sourceEventId, 'relation.sourceEventId')
  const createdAt = requireNonNegativeInt(obj.createdAt, 'relation.createdAt')
  const updatedAt = requireNonNegativeInt(obj.updatedAt, 'relation.updatedAt')
  const deletedAt = optionalNonNegativeInt(obj.deletedAt, 'relation.deletedAt')
  const revision = requirePositiveInt(obj.revision, 'relation.revision')

  return {
    id,
    fromEntityId,
    relationType,
    toEntityId,
    label,
    attributesJson,
    validFrom,
    validUntil,
    invalidatedAt,
    invalidatedBy,
    epistemicTier,
    domain,
    sensitivity,
    sourceEventId,
    createdAt,
    updatedAt,
    deletedAt,
    revision,
    updatedByDeviceId: optionalId(obj.updatedByDeviceId, 'relation.updatedByDeviceId') ?? deviceId,
  }
}

function validateProposalRecord(raw: unknown, deviceId: string): MemoryProposalSyncRecord {
  const obj = requireObject(raw, 'proposal payload')
  const id = requireId(obj.id, 'proposal.id')
  const slug = requireText(obj.slug, MAX_NAME_CHARS, 'proposal.slug')
  const title = requireText(obj.title, MAX_NAME_CHARS, 'proposal.title')
  const purpose = requireText(obj.purpose, MAX_SUMMARY_CHARS, 'proposal.purpose')
  const domain = requireText(obj.domain, MAX_SHORT_TOKEN_CHARS, 'proposal.domain')
  const sensitivity = requireEnum(
    obj.sensitivity,
    MEMORY_SENSITIVITIES,
    'proposal.sensitivity',
  ) as MemorySensitivityWire
  const seedContent = allowEmptyText(obj.seedContent, MAX_BLOCK_CONTENT_CHARS, 'proposal.seedContent')
  const supportingEventIds = requireStringList(
    obj.supportingEventIds ?? [],
    MAX_LIST_ITEMS,
    MAX_ID_LENGTH,
    'proposal.supportingEventIds',
  )
  const reason = requireText(obj.reason, MAX_SUMMARY_CHARS, 'proposal.reason')
  const status = requireEnum(obj.status, MEMORY_PROPOSAL_STATUSES, 'proposal.status') as MemoryProposalStatusWire
  const createdAt = requireNonNegativeInt(obj.createdAt, 'proposal.createdAt')
  const resolvedAt = optionalNonNegativeInt(obj.resolvedAt, 'proposal.resolvedAt')
  const updatedAt = requireNonNegativeInt(obj.updatedAt, 'proposal.updatedAt')
  const deletedAt = optionalNonNegativeInt(obj.deletedAt, 'proposal.deletedAt')
  const revision = requirePositiveInt(obj.revision, 'proposal.revision')

  return {
    id,
    slug,
    title,
    purpose,
    domain,
    sensitivity,
    seedContent,
    supportingEventIds,
    reason,
    status,
    createdAt,
    resolvedAt,
    updatedAt,
    deletedAt,
    revision,
    updatedByDeviceId: optionalId(obj.updatedByDeviceId, 'proposal.updatedByDeviceId') ?? deviceId,
  }
}

function requireObject(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new MemoryRequestError(`${label} must be an object`)
  }
  return value as Record<string, unknown>
}

function requireBoolean(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') {
    throw new MemoryRequestError(`${field} must be a boolean`)
  }
  return value
}

function requirePositiveInt(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1) {
    throw new MemoryRequestError(`${field} must be a positive integer`)
  }
  return value
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

function allowEmptyText(value: unknown, maxChars: number, field: string): string {
  if (value === undefined || value === null) return ''
  if (typeof value !== 'string') throw new MemoryRequestError(`${field} must be a string`)
  if (value.length > maxChars) throw new MemoryRequestError(`${field} length invalid`)
  return value
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

function normalizeJsonObjectText(value: unknown, maxChars: number, field: string): string {
  if (value === undefined || value === null) return '{}'
  if (typeof value !== 'string') throw new MemoryRequestError(`${field} must be a string`)
  const trimmed = value.trim()
  if (trimmed === '') return '{}'
  if (trimmed.length > maxChars) throw new MemoryRequestError(`${field} length invalid`)
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    throw new MemoryRequestError(`${field} must be valid JSON`)
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new MemoryRequestError(`${field} must be a JSON object`)
  }
  return JSON.stringify(parsed)
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

function requireEntityLinks(value: unknown): MemoryEventEntityLink[] {
  if (!Array.isArray(value)) throw new MemoryRequestError('event.entityLinks must be an array')
  const seen = new Set<string>()
  const links: MemoryEventEntityLink[] = []
  for (const item of value) {
    const obj = requireObject(item, 'event.entityLinks[]')
    const entityId = requireId(obj.entityId, 'event.entityLinks[].entityId')
    const role = optionalText(obj.role, MAX_SHORT_TOKEN_CHARS, 'event.entityLinks[].role') ?? 'MENTIONED'
    if (seen.has(entityId)) continue
    seen.add(entityId)
    links.push({ entityId, role })
    if (links.length >= MAX_LIST_ITEMS) break
  }
  return links
}
