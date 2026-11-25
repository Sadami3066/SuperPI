export const MEMORY_V3_OP_KINDS = ['block', 'entity', 'event', 'relation', 'proposal'] as const
export type MemoryV3OpKind = (typeof MEMORY_V3_OP_KINDS)[number]

export const MEMORY_SENSITIVITIES = ['PUBLIC', 'NORMAL', 'PRIVATE'] as const
export type MemorySensitivityWire = (typeof MEMORY_SENSITIVITIES)[number]

export const MEMORY_BLOCK_OWNERS = ['BUILTIN', 'USER', 'AGENT', 'PLUGIN'] as const
export type MemoryBlockOwnerWire = (typeof MEMORY_BLOCK_OWNERS)[number]

export const MEMORY_BLOCK_INJECTION_MODES = ['ALWAYS', 'ON_DEMAND', 'NEVER'] as const
export type MemoryBlockInjectionModeWire = (typeof MEMORY_BLOCK_INJECTION_MODES)[number]

export const MEMORY_BLOCK_STATUSES = ['ACTIVE', 'ARCHIVED'] as const
export type MemoryBlockStatusWire = (typeof MEMORY_BLOCK_STATUSES)[number]

export const MEMORY_PROPOSAL_STATUSES = ['PENDING', 'ACCEPTED', 'DISMISSED'] as const
export type MemoryProposalStatusWire = (typeof MEMORY_PROPOSAL_STATUSES)[number]

export const MEMORY_EVENT_KINDS = ['FACT', 'PREFERENCE', 'COMMITMENT', 'EPISODE', 'GOAL', 'BOUNDARY', 'NOTE'] as const
export type MemoryEventKindWire = (typeof MEMORY_EVENT_KINDS)[number]

export const MEMORY_EPISTEMIC_TIERS = ['USER_PINNED', 'USER_EXPLICIT', 'OBSERVED', 'INFERRED'] as const
export type MemoryEpistemicTierWire = (typeof MEMORY_EPISTEMIC_TIERS)[number]

export const MEMORY_EVENT_TIME_PRECISIONS = ['EXACT', 'DAY', 'MONTH', 'YEAR', 'APPROX', 'TIMELESS'] as const
export type MemoryEventTimePrecisionWire = (typeof MEMORY_EVENT_TIME_PRECISIONS)[number]

export const MEMORY_ITEM_STATUSES = ['ACTIVE', 'SUPERSEDED', 'RESOLVED', 'EXPIRED', 'ARCHIVED'] as const
export type MemoryItemStatusWire = (typeof MEMORY_ITEM_STATUSES)[number]

export const MEMORY_SOURCE_KINDS = [
  'USER_MANUAL',
  'AGENT_DISPATCH',
  'AGENT_AUTO',
  'SLEEP_CONSOLIDATION',
  'MIGRATION_V2',
] as const
export type MemorySourceKindWire = (typeof MEMORY_SOURCE_KINDS)[number]

export interface MemoryBlockSyncRecord {
  id: string
  slug: string
  title: string
  purpose: string
  schemaHint?: string | null
  content: string
  domain: string
  sensitivity: MemorySensitivityWire
  injectionMode: MemoryBlockInjectionModeWire
  maxChars: number
  owner: MemoryBlockOwnerWire
  pinned: boolean
  status: MemoryBlockStatusWire
  sortOrder: number
  createdAt: number
  updatedAt: number
  deletedAt?: number | null
  revision: number
  updatedByDeviceId?: string | null
}

export interface MemoryEntitySyncRecord {
  id: string
  canonicalName: string
  entityType: string
  aliases: string[]
  attributesJson: string
  summary?: string | null
  domain: string
  sensitivity: MemorySensitivityWire
  pinned: boolean
  createdAt: number
  updatedAt: number
  deletedAt?: number | null
  revision: number
  mergedInto?: string | null
  updatedByDeviceId?: string | null
}

export interface MemoryEventEntityLink {
  entityId: string
  role: string
}

export interface MemoryEventSyncRecord {
  id: string
  eventKind: MemoryEventKindWire
  content: string
  summary?: string | null
  qualifiersJson: string
  semanticKeywords: string[]
  tags: string[]
  domain: string
  sensitivity: MemorySensitivityWire
  epistemicTier: MemoryEpistemicTierWire
  importance: number
  pinned: boolean
  eventTime: number
  eventTimePrecision: MemoryEventTimePrecisionWire
  validFrom: number
  validUntil?: number | null
  status: MemoryItemStatusWire
  supersededBy?: string | null
  sourceKind: MemorySourceKindWire
  sourceSessionId?: string | null
  sourceEntryId?: string | null
  sourceQuote?: string | null
  entityLinks: MemoryEventEntityLink[]
  createdAt: number
  updatedAt: number
  deletedAt?: number | null
  revision: number
  updatedByDeviceId?: string | null
}

export interface MemoryRelationSyncRecord {
  id: string
  fromEntityId: string
  relationType: string
  toEntityId: string
  label?: string | null
  attributesJson: string
  validFrom: number
  validUntil?: number | null
  invalidatedAt?: number | null
  invalidatedBy?: string | null
  epistemicTier: MemoryEpistemicTierWire
  domain: string
  sensitivity: MemorySensitivityWire
  sourceEventId?: string | null
  createdAt: number
  updatedAt: number
  deletedAt?: number | null
  revision: number
  updatedByDeviceId?: string | null
}

export interface MemoryProposalSyncRecord {
  id: string
  slug: string
  title: string
  purpose: string
  domain: string
  sensitivity: MemorySensitivityWire
  seedContent: string
  supportingEventIds: string[]
  reason: string
  status: MemoryProposalStatusWire
  createdAt: number
  resolvedAt?: number | null
  updatedAt: number
  deletedAt?: number | null
  revision: number
  updatedByDeviceId?: string | null
}

export interface MemoryV3PushOp {
  opId: string
  kind: MemoryV3OpKind
  block?: MemoryBlockSyncRecord
  entity?: MemoryEntitySyncRecord
  event?: MemoryEventSyncRecord
  relation?: MemoryRelationSyncRecord
  proposal?: MemoryProposalSyncRecord
}

export interface MemoryV3PushRequest {
  ops: MemoryV3PushOp[]
  consolidationCompletedAt?: number
}

export interface MemoryV3PushRejectedOp {
  opId: string
  reason: string
}

export interface MemoryV3PushResponse {
  serverSeq: number
  appliedCount: number
  skippedCount: number
  lastConsolidationAt: number | null
  rejectedCount?: number
  rejected?: MemoryV3PushRejectedOp[]
}

export interface MemoryV3PullEntry {
  seq: number
  kind: MemoryV3OpKind
  deviceId: string
  block?: MemoryBlockSyncRecord
  entity?: MemoryEntitySyncRecord
  event?: MemoryEventSyncRecord
  relation?: MemoryRelationSyncRecord
  proposal?: MemoryProposalSyncRecord
}

export interface MemoryV3PullResponse {
  serverSeq: number
  hasMore: boolean
  nextSince: number
  lastConsolidationAt: number | null
  entries: MemoryV3PullEntry[]
}

export interface MemoryConsolidationLeaseRequest {
  ttlMs?: number
  minIntervalMs?: number
}

export interface MemoryConsolidationLeaseResponse {
  acquired: boolean
  leaseId?: string
  holderDeviceId?: string
  expiresAt: number
  lastConsolidationAt: number | null
  serverSeq: number
}

export interface MemoryConsolidationLeaseReleaseResponse {
  released: boolean
}
