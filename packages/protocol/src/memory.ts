export const MEMORY_KINDS = ['IDENTITY', 'ACTIVE', 'HABIT', 'EPISODIC'] as const
export type MemoryKindWire = (typeof MEMORY_KINDS)[number]

export const MEMORY_CONFIDENCES = ['CONFIRMED', 'OBSERVED', 'INFERRED'] as const
export type MemoryConfidenceWire = (typeof MEMORY_CONFIDENCES)[number]

export const MEMORY_ITEM_STATUSES = ['ACTIVE', 'SUPERSEDED', 'EXPIRED', 'ARCHIVED'] as const
export type MemoryItemStatusWire = (typeof MEMORY_ITEM_STATUSES)[number]

export const MEMORY_SOURCE_KINDS = ['CHAT_EXPLICIT', 'CHAT_AUTO', 'DREAM_SYNTH', 'USER_MANUAL'] as const
export type MemorySourceKindWire = (typeof MEMORY_SOURCE_KINDS)[number]

export const ENTITY_KINDS = ['PERSON', 'PROJECT', 'PLACE', 'TOPIC', 'OTHER'] as const
export type EntityKindWire = (typeof ENTITY_KINDS)[number]

export type MemoryOpKind = 'memory' | 'entity'

export interface MemorySyncRecord {
  id: string
  kind: MemoryKindWire
  content: string
  summary?: string | null
  tags: string[]
  entities: string[]
  confidence: MemoryConfidenceWire
  importance: number
  pinned: boolean
  validFrom: number
  validUntil?: number | null
  status: MemoryItemStatusWire
  supersededBy?: string | null
  sourceKind: MemorySourceKindWire
  sourceSessionId?: string | null
  sourceEntryId?: string | null
  sourceQuote?: string | null
  createdAt: number
  updatedAt: number
  deletedAt?: number | null
  revision: number
  updatedByDeviceId?: string | null
}

export interface EntitySyncRecord {
  canonicalKey?: string
  id: string
  canonicalName: string
  kind: EntityKindWire
  aliases: string[]
  summary?: string | null
  mergedInto?: string | null
  updatedAt: number
  deletedAt?: number | null
  revision: number
  updatedByDeviceId?: string | null
}

export interface MemoryPushOp {
  opId: string
  kind: MemoryOpKind
  memory?: MemorySyncRecord
  entity?: EntitySyncRecord
}

export interface MemoryPushRequest {
  ops: MemoryPushOp[]
  dreamCompletedAt?: number
}

export interface MemoryPushRejectedOp {
  opId: string
  reason: string
}

export interface MemoryPushResponse {
  serverSeq: number
  appliedCount: number
  skippedCount: number
  lastDreamAt: number | null
  rejectedCount?: number
  rejected?: MemoryPushRejectedOp[]
}

export interface MemoryPullEntry {
  seq: number
  kind: MemoryOpKind
  deviceId: string
  memory?: MemorySyncRecord
  entity?: EntitySyncRecord
}

export interface MemoryPullResponse {
  serverSeq: number
  hasMore: boolean
  nextSince: number
  lastDreamAt: number | null
  entries: MemoryPullEntry[]
}

export interface MemoryDreamLeaseRequest {
  ttlMs?: number
  minIntervalMs?: number
}

export interface MemoryDreamLeaseResponse {
  acquired: boolean
  leaseId?: string
  holderDeviceId?: string
  expiresAt: number
  lastDreamAt: number | null
  serverSeq: number
}

export interface MemoryDreamLeaseReleaseResponse {
  released: boolean
}
