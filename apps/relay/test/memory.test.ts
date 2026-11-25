import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import type {
  EnrollResponse,
  MemoryBlockSyncRecord,
  MemoryEntitySyncRecord,
  MemoryEventSyncRecord,
  MemoryProposalSyncRecord,
  MemoryRelationSyncRecord,
  MemoryV3PullResponse,
  MemoryV3PushResponse,
} from '@superpi/protocol'
import { createRelayServer, type RelayServer } from '../src/server/http.ts'
import { RelayStore } from '../src/server/store.ts'
import { MemoryStore } from '../src/server/memory.ts'
import { createRealtimeHub, type RealtimeHub } from '../src/server/realtime.ts'
import { hashSecret, newAccountId } from '../src/server/auth.ts'
import type { RelayConfig } from '../src/server/config.ts'

const LOGIN_SECRET = 'memory-login-secret'

interface DeviceClient {
  deviceId: string
  token: string
}

interface Harness {
  relay: RelayServer
  hub: RealtimeHub
  store: RelayStore
  memory: MemoryStore
  dir: string
  baseUrl: string
  phoneA: DeviceClient
  phoneB: DeviceClient
  changed: Array<{ version: number; source?: string }>
}

const harnesses: Harness[] = []

afterEach(async () => {
  while (harnesses.length > 0) {
    const harness = harnesses.pop()
    if (!harness) continue
    await harness.hub.close()
    await harness.relay.close()
    harness.store.close()
    rmSync(harness.dir, { recursive: true, force: true })
  }
})

async function start(now?: () => number): Promise<Harness> {
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-memory-v3-'))
  const store = new RelayStore(dir)
  store.createAccount(newAccountId(), hashSecret(LOGIN_SECRET), now?.() ?? Date.now())
  const memory = new MemoryStore(store.database)
  const changed: Array<{ version: number; source?: string }> = []
  const config: RelayConfig = {
    host: '127.0.0.1',
    port: 0,
    dataDir: dir,
    certPath: null,
    keyPath: null,
    caPath: null,
    version: '0.1.0',
    entries: [],
  }
  const hub = createRealtimeHub(store, () => memory.version(), now ?? Date.now)
  const relay = createRelayServer({
    config,
    store,
    fingerprint: null,
    entries: [],
    memory,
    now,
    onMemoryChanged: (version, sourceDeviceId) => {
      changed.push({ version, source: sourceDeviceId })
      hub.broadcastMemoryChanged(version, sourceDeviceId)
    },
  })
  hub.attach(relay.server)
  const address = await relay.listen()
  const baseUrl = `http://127.0.0.1:${address.port}`
  const enroll = async (name: string): Promise<DeviceClient> => {
    const enrolled = (await (
      await fetch(`${baseUrl}/v1/pair/enroll`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ loginSecret: LOGIN_SECRET, deviceName: name, platform: 'android' }),
      })
    ).json()) as EnrollResponse
    return { deviceId: enrolled.deviceId, token: enrolled.token }
  }
  const harness: Harness = {
    relay,
    hub,
    store,
    memory,
    dir,
    baseUrl,
    phoneA: await enroll('Phone A'),
    phoneB: await enroll('Phone B'),
    changed,
  }
  harnesses.push(harness)
  return harness
}

function makeBlock(
  id: string,
  slug: string,
  content: string,
  updatedAt: number,
  overrides: Partial<MemoryBlockSyncRecord> = {},
): MemoryBlockSyncRecord {
  return {
    id,
    slug,
    title: slug,
    purpose: `Purpose for ${slug}`,
    schemaHint: null,
    content,
    domain: 'PERSONAL',
    sensitivity: 'NORMAL',
    injectionMode: 'ALWAYS',
    maxChars: 2000,
    owner: 'BUILTIN',
    pinned: false,
    status: 'ACTIVE',
    sortOrder: 10,
    createdAt: updatedAt,
    updatedAt,
    deletedAt: null,
    revision: 1,
    ...overrides,
  }
}

function makeEntity(
  id: string,
  canonicalName: string,
  updatedAt: number,
  overrides: Partial<MemoryEntitySyncRecord> = {},
): MemoryEntitySyncRecord {
  return {
    id,
    canonicalName,
    entityType: 'PERSON',
    aliases: [],
    attributesJson: '{}',
    summary: null,
    domain: 'PERSONAL',
    sensitivity: 'NORMAL',
    pinned: false,
    createdAt: updatedAt,
    updatedAt,
    deletedAt: null,
    revision: 1,
    mergedInto: null,
    ...overrides,
  }
}

function makeEvent(
  id: string,
  content: string,
  updatedAt: number,
  overrides: Partial<MemoryEventSyncRecord> = {},
): MemoryEventSyncRecord {
  return {
    id,
    eventKind: 'FACT',
    content,
    summary: null,
    qualifiersJson: '{}',
    semanticKeywords: [],
    tags: [],
    domain: 'PERSONAL',
    sensitivity: 'NORMAL',
    epistemicTier: 'USER_EXPLICIT',
    importance: 4,
    pinned: false,
    eventTime: updatedAt,
    eventTimePrecision: 'DAY',
    validFrom: updatedAt,
    validUntil: null,
    status: 'ACTIVE',
    supersededBy: null,
    sourceKind: 'USER_MANUAL',
    sourceSessionId: null,
    sourceEntryId: null,
    sourceQuote: null,
    entityLinks: [],
    createdAt: updatedAt,
    updatedAt,
    deletedAt: null,
    revision: 1,
    ...overrides,
  }
}

function makeRelation(
  id: string,
  fromEntityId: string,
  toEntityId: string,
  updatedAt: number,
  overrides: Partial<MemoryRelationSyncRecord> = {},
): MemoryRelationSyncRecord {
  return {
    id,
    fromEntityId,
    relationType: 'friend_of',
    toEntityId,
    label: null,
    attributesJson: '{}',
    validFrom: updatedAt,
    validUntil: null,
    invalidatedAt: null,
    invalidatedBy: null,
    epistemicTier: 'OBSERVED',
    domain: 'PERSONAL',
    sensitivity: 'NORMAL',
    sourceEventId: null,
    createdAt: updatedAt,
    updatedAt,
    deletedAt: null,
    revision: 1,
    ...overrides,
  }
}

function makeProposal(
  id: string,
  slug: string,
  updatedAt: number,
  overrides: Partial<MemoryProposalSyncRecord> = {},
): MemoryProposalSyncRecord {
  return {
    id,
    slug,
    title: 'Travel',
    purpose: 'Trips and bookings',
    domain: 'LIFE',
    sensitivity: 'NORMAL',
    seedContent: '',
    supportingEventIds: ['e1'],
    reason: 'Recurring travel notes',
    status: 'PENDING',
    createdAt: updatedAt,
    resolvedAt: null,
    updatedAt,
    deletedAt: null,
    revision: 1,
    ...overrides,
  }
}

async function push(harness: Harness, client: DeviceClient, body: unknown): Promise<Response> {
  return fetch(`${harness.baseUrl}/v3/memory/push`, {
    method: 'POST',
    headers: { authorization: `Bearer ${client.token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function pull(harness: Harness, client: DeviceClient, since = 0, limit = 200): Promise<MemoryV3PullResponse> {
  const res = await fetch(`${harness.baseUrl}/v3/memory/pull?since=${since}&limit=${limit}`, {
    headers: { authorization: `Bearer ${client.token}` },
  })
  expect(res.status).toBe(200)
  return (await res.json()) as MemoryV3PullResponse
}

describe('memory v3 sync server', () => {
  it('supports push/pull roundtrip across all 5 kinds and idempotent opId replay', async () => {
    const harness = await start(() => 10_000)
    const first = await push(harness, harness.phoneA, {
      ops: [
        { opId: 'op-b1', kind: 'block', block: makeBlock('builtin_self_profile', 'self_profile', '', 0) },
        {
          opId: 'op-e1',
          kind: 'entity',
          entity: makeEntity('ent-1', 'Alice', 5_000, { attributesJson: '{"city":"Shanghai"}' }),
        },
        {
          opId: 'op-e2',
          kind: 'entity',
          entity: makeEntity('ent-2', 'Bob', 5_100),
        },
        {
          opId: 'op-ev1',
          kind: 'event',
          event: makeEvent('evt-1', 'Met Alice in Shanghai', 6_000, {
            semanticKeywords: ['meeting', 'shanghai'],
            tags: ['work'],
            entityLinks: [{ entityId: 'ent-1', role: 'MENTIONED' }],
          }),
        },
        {
          opId: 'op-rel1',
          kind: 'relation',
          relation: makeRelation('rel-1', 'ent-1', 'ent-2', 7_000),
        },
        {
          opId: 'op-prop1',
          kind: 'proposal',
          proposal: makeProposal('prop-1', 'travel_notes', 8_000),
        },
      ],
    })
    expect(first.status).toBe(200)
    const body1 = (await first.json()) as MemoryV3PushResponse
    expect(body1).toEqual({ serverSeq: 6, appliedCount: 6, skippedCount: 0, lastConsolidationAt: null })
    expect(harness.changed).toEqual([{ version: 6, source: harness.phoneA.deviceId }])

    const replay = await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'op-ev1',
          kind: 'event',
          event: makeEvent('evt-1', 'Should ignore replay', 9_000),
        },
      ],
    })
    expect(replay.status).toBe(200)
    expect((await replay.json()) as MemoryV3PushResponse).toEqual({
      serverSeq: 6,
      appliedCount: 0,
      skippedCount: 1,
      lastConsolidationAt: null,
    })

    const crossDeviceReplay = await push(harness, harness.phoneB, {
      ops: [{ opId: 'op-ev1', kind: 'event', event: makeEvent('evt-1', 'Hijack', 9_000) }],
    })
    expect(crossDeviceReplay.status).toBe(400)

    const pulled = await pull(harness, harness.phoneB, 0, 200)
    expect(pulled.serverSeq).toBe(6)
    expect(pulled.hasMore).toBe(false)
    expect(pulled.entries.map((entry) => entry.kind)).toEqual(['block', 'entity', 'entity', 'event', 'relation', 'proposal'])
    expect(pulled.entries[0]?.block?.content).toBe('')
    expect(pulled.entries[3]?.event?.entityLinks).toEqual([{ entityId: 'ent-1', role: 'MENTIONED' }])
    expect(pulled.entries[4]?.relation?.fromEntityId).toBe('ent-1')
    expect(pulled.entries[5]?.proposal?.seedContent).toBe('')
  })

  it('resolves updates with LWW and preserves soft-delete tombstones', async () => {
    const harness = await start(() => 20_000)
    await push(harness, harness.phoneA, {
      ops: [{ opId: 'ev-new', kind: 'event', event: makeEvent('evt-1', 'Version B newer', 8_000, { revision: 2 }) }],
    })

    const stale = await push(harness, harness.phoneB, {
      ops: [{ opId: 'ev-stale', kind: 'event', event: makeEvent('evt-1', 'Stale overwrite', 7_000, { revision: 5 }) }],
    })
    expect((await stale.json()) as MemoryV3PushResponse).toMatchObject({ appliedCount: 0, skippedCount: 1, serverSeq: 1 })

    const tieWinner = harness.phoneA.deviceId > harness.phoneB.deviceId ? harness.phoneA : harness.phoneB
    const tieLoser = tieWinner === harness.phoneA ? harness.phoneB : harness.phoneA
    await push(harness, tieLoser, {
      ops: [{ opId: 'tie-1', kind: 'event', event: makeEvent('evt-tie', 'Loser side', 9_000) }],
    })
    await push(harness, tieWinner, {
      ops: [{ opId: 'tie-2', kind: 'event', event: makeEvent('evt-tie', 'Winner side', 9_000) }],
    })

    const deleted = await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ev-del',
          kind: 'event',
          event: makeEvent('evt-1', 'Version B newer', 12_000, { deletedAt: 12_000, revision: 3 }),
        },
      ],
    })
    expect((await deleted.json()) as MemoryV3PushResponse).toMatchObject({ appliedCount: 1 })

    const pulled = await pull(harness, harness.phoneB, 0)
    const byId = new Map(pulled.entries.map((e) => [e.event?.id, e.event]))
    expect(byId.get('evt-1')?.deletedAt).toBe(12_000)
    expect(byId.get('evt-tie')?.content).toBe('Winner side')
  })

  it('merges entity aliases, attributes, and summary keyed by id and compacts pull ops', async () => {
    const harness = await start(() => 30_000)
    await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ent-a',
          kind: 'entity',
          entity: makeEntity('ent-shared', 'Zhang Ming', 5_000, {
            aliases: ['Xiaoming'],
            attributesJson: '{"role":"designer","city":"Hangzhou"}',
            summary: 'Coworker',
          }),
        },
      ],
    })
    await push(harness, harness.phoneB, {
      ops: [
        {
          opId: 'ent-b',
          kind: 'entity',
          entity: makeEntity('ent-shared', 'Zhang Ming', 6_000, {
            aliases: ['Ming'],
            attributesJson: '{"city":"Shanghai"}',
            summary: null,
            revision: 2,
          }),
        },
      ],
    })
    await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ent-a2',
          kind: 'entity',
          entity: makeEntity('ent-shared', 'Zhang Ming', 4_000, {
            aliases: ['Uncle Zhang'],
            attributesJson: '{"tea":"oolong","city":"Beijing"}',
          }),
        },
      ],
    })

    const page1 = await pull(harness, harness.phoneB, 0, 1)
    expect(page1.entries).toHaveLength(1)
    expect(page1.hasMore).toBe(false)
    expect(page1.serverSeq).toBe(3)
    const entity = page1.entries[0]?.entity
    expect(entity?.aliases).toEqual(['Xiaoming', 'Ming', 'Uncle Zhang'])
    expect(JSON.parse(entity?.attributesJson ?? '{}')).toEqual({
      role: 'designer',
      city: 'Shanghai',
      tea: 'oolong',
    })
    expect(entity?.summary).toBe('Coworker')

    await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ent-merge',
          kind: 'entity',
          entity: makeEntity('ent-old', 'Xiaowang', 10_000, {
            mergedInto: 'ent-shared',
            deletedAt: 10_000,
            revision: 2,
          }),
        },
      ],
    })
    const page2 = await pull(harness, harness.phoneB, page1.nextSince, 10)
    expect(page2.entries[0]?.entity?.mergedInto).toBe('ent-shared')
    expect(page2.entries[0]?.entity?.deletedAt).toBe(10_000)
  })

  it('bounds client future consolidationCompletedAt and rejects legacy v2 endpoints', async () => {
    const now = 500_000
    const harness = await start(() => now)
    expect((await fetch(`${harness.baseUrl}/v3/memory/pull`)).status).toBe(401)
    expect((await fetch(`${harness.baseUrl}/v2/memory/pull`, {
      headers: { authorization: `Bearer ${harness.phoneA.token}` },
    })).status).toBe(404)
    expect((await fetch(`${harness.baseUrl}/v1/memory/snapshot`)).status).toBe(404)

    const futureConsolidation = await push(harness, harness.phoneA, {
      ops: [],
      consolidationCompletedAt: now + 999_999_999,
    })
    expect(futureConsolidation.status).toBe(200)
    expect(((await futureConsolidation.json()) as MemoryV3PushResponse).lastConsolidationAt).toBe(500_000)

    const hugeOps = Array.from({ length: 201 }, (_, idx) => ({
      opId: `bulk-${idx}`,
      kind: 'event',
      event: makeEvent(`e-${idx}`, 'Item', 1_000),
    }))
    const tooMany = await push(harness, harness.phoneA, { ops: hugeOps })
    expect(tooMany.status).toBe(400)
  })

  it('rejects malformed ops per-item without stalling valid ops and drops legacy memory_v2 tables safely', async () => {
    const harness = await start(() => 600_000)
    const oversizedContent = 'a'.repeat(8_193)
    const oversizedName = 'n'.repeat(129)

    const partial = await push(harness, harness.phoneA, {
      ops: [
        { opId: 'bad-content', kind: 'event', event: makeEvent('e-bad', oversizedContent, 1_000) },
        { opId: 'good-ev', kind: 'event', event: makeEvent('e-good', 'Valid short item', 2_000) },
        { opId: 'bad-entity', kind: 'entity', entity: makeEntity('ent-bad', oversizedName, 3_000) },
        { opId: 'bad-json', kind: 'relation', relation: makeRelation('rel-bad', 'a', 'b', 4_000, { attributesJson: '[]' }) },
      ],
    })
    expect(partial.status).toBe(200)
    const partialBody = (await partial.json()) as MemoryV3PushResponse
    expect(partialBody).toEqual({
      serverSeq: 1,
      appliedCount: 1,
      skippedCount: 0,
      lastConsolidationAt: null,
      rejectedCount: 3,
      rejected: [
        { opId: 'bad-content', reason: 'event.content length invalid' },
        { opId: 'bad-entity', reason: 'entity.canonicalName length invalid' },
        { opId: 'bad-json', reason: 'relation.attributesJson must be a JSON object' },
      ],
    })
    expect(harness.changed).toEqual([{ version: 1, source: harness.phoneA.deviceId }])

    const pulled = await pull(harness, harness.phoneB, 0)
    expect(pulled.entries.map((entry) => entry.event?.content)).toEqual(['Valid short item'])

    const dir = mkdtempSync(path.join(tmpdir(), 'superpi-v2-drop-'))
    const store = new RelayStore(dir)
    try {
      store.createAccount('acct-1', 'hash', 100)
      store.database.exec(`
        CREATE TABLE memory_v2_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE memory_v2_items (id TEXT PRIMARY KEY, payload_json TEXT NOT NULL, updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL, deleted_at INTEGER, server_seq INTEGER NOT NULL);
        CREATE TABLE memory_v2_entities (canonical_key TEXT PRIMARY KEY, payload_json TEXT NOT NULL, updated_at INTEGER NOT NULL, updated_by TEXT NOT NULL, deleted_at INTEGER, server_seq INTEGER NOT NULL);
        CREATE TABLE memory_v2_ops (seq INTEGER PRIMARY KEY, kind TEXT NOT NULL, target_key TEXT NOT NULL, device_id TEXT NOT NULL, created_at INTEGER NOT NULL);
        CREATE TABLE memory_v2_idempotency (op_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, seq INTEGER NOT NULL, created_at INTEGER NOT NULL);
        INSERT INTO memory_leases(name, device_id, lease_id, expires_at) VALUES ('dream', 'dev-1', 'lease-1', 999999);
      `)
      new MemoryStore(store.database)
      const legacyTables = store.database
        .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'memory_v2_%'")
        .all()
      expect(legacyTables).toEqual([])
      expect(store.database.prepare("SELECT * FROM memory_leases WHERE name = 'dream'").all()).toEqual([])
      expect(store.account()?.id).toBe('acct-1')
    } finally {
      store.close()
      rmSync(dir, { recursive: true, force: true })
    }
  })

  it('bumps revision when an incoming entity winner absorbs loser attributes or aliases', async () => {
    const harness = await start(() => 700_000)

    const initialPush = await push(harness, harness.phoneB, {
      ops: [
        {
          opId: 'ent-b-1',
          kind: 'entity',
          entity: makeEntity('ent-merge-win', 'Airis', 1_000, {
            aliases: ['Boss'],
            attributesJson: JSON.stringify({ city: 'Shanghai', role: 'creator' }),
            summary: 'Old summary',
            revision: 1,
          }),
        },
      ],
    })
    expect(initialPush.status).toBe(200)

    const winnerPush = await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ent-a-win',
          kind: 'entity',
          entity: makeEntity('ent-merge-win', 'Airis', 2_000, {
            aliases: ['Founder'],
            attributesJson: JSON.stringify({ role: 'lead' }),
            summary: 'New summary',
            revision: 3,
          }),
        },
      ],
    })
    expect(winnerPush.status).toBe(200)

    const pulled = await pull(harness, harness.phoneA, 0)
    const entity = pulled.entries.find((entry) => entry.entity?.id === 'ent-merge-win')?.entity
    expect(entity).toBeDefined()
    expect(entity?.updatedAt).toBe(2_000)
    expect(entity?.updatedByDeviceId).toBe(harness.phoneA.deviceId)
    expect(entity?.revision).toBe(4)
    expect(entity?.aliases).toEqual(['Boss', 'Founder'])
    expect(JSON.parse(entity?.attributesJson ?? '{}')).toEqual({ city: 'Shanghai', role: 'lead' })
    expect(entity?.summary).toBe('New summary')
  })
})
