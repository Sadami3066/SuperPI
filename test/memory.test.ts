import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import type {
  EnrollResponse,
  EntitySyncRecord,
  MemoryPullResponse,
  MemoryPushResponse,
  MemorySyncRecord,
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
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-memory-v2-'))
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

function makeMemory(id: string, content: string, updatedAt: number, overrides: Partial<MemorySyncRecord> = {}): MemorySyncRecord {
  return {
    id,
    kind: 'IDENTITY',
    content,
    tags: [],
    entities: [],
    confidence: 'CONFIRMED',
    importance: 4,
    pinned: false,
    validFrom: updatedAt,
    status: 'ACTIVE',
    sourceKind: 'USER_MANUAL',
    createdAt: updatedAt,
    updatedAt,
    revision: 1,
    ...overrides,
  }
}

function makeEntity(id: string, canonicalName: string, updatedAt: number, overrides: Partial<EntitySyncRecord> = {}): EntitySyncRecord {
  return {
    id,
    canonicalName,
    kind: 'PERSON',
    aliases: [],
    updatedAt,
    revision: 1,
    ...overrides,
  }
}

async function push(harness: Harness, client: DeviceClient, body: unknown): Promise<Response> {
  return fetch(`${harness.baseUrl}/v2/memory/push`, {
    method: 'POST',
    headers: { authorization: `Bearer ${client.token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function pull(harness: Harness, client: DeviceClient, since = 0, limit = 200): Promise<MemoryPullResponse> {
  const res = await fetch(`${harness.baseUrl}/v2/memory/pull?since=${since}&limit=${limit}`, {
    headers: { authorization: `Bearer ${client.token}` },
  })
  expect(res.status).toBe(200)
  return (await res.json()) as MemoryPullResponse
}

describe('memory v2 sync server', () => {
  it('supports push/pull roundtrip and idempotent opId replay', async () => {
    const harness = await start(() => 10_000)
    const first = await push(harness, harness.phoneA, {
      ops: [
        { opId: 'op-a1', kind: 'memory', memory: makeMemory('m1', 'Likes espresso', 5_000) },
        { opId: 'op-a2', kind: 'memory', memory: makeMemory('m2', 'Prefers dark mode', 6_000, { kind: 'HABIT' }) },
      ],
    })
    expect(first.status).toBe(200)
    const body1 = (await first.json()) as MemoryPushResponse
    expect(body1).toEqual({ serverSeq: 2, appliedCount: 2, skippedCount: 0, lastDreamAt: null })
    expect(harness.changed).toEqual([{ version: 2, source: harness.phoneA.deviceId }])

    const replay = await push(harness, harness.phoneA, {
      ops: [
        { opId: 'op-a1', kind: 'memory', memory: makeMemory('m1', 'Should ignore replay', 9_000) },
      ],
    })
    expect(replay.status).toBe(200)
    expect((await replay.json()) as MemoryPushResponse).toEqual({
      serverSeq: 2,
      appliedCount: 0,
      skippedCount: 1,
      lastDreamAt: null,
    })

    const crossDeviceReplay = await push(harness, harness.phoneB, {
      ops: [{ opId: 'op-a1', kind: 'memory', memory: makeMemory('m1', 'Hijack', 9_000) }],
    })
    expect(crossDeviceReplay.status).toBe(400)

    const pulled = await pull(harness, harness.phoneB, 0, 200)
    expect(pulled.serverSeq).toBe(2)
    expect(pulled.hasMore).toBe(false)
    expect(pulled.entries.map((entry) => entry.memory?.content)).toEqual(['Likes espresso', 'Prefers dark mode'])
  })

  it('resolves same-UUID updates with LWW and preserves soft-delete tombstones', async () => {
    const harness = await start(() => 20_000)
    await push(harness, harness.phoneA, {
      ops: [{ opId: 'm-new', kind: 'memory', memory: makeMemory('m1', 'Version B newer', 8_000, { revision: 2 }) }],
    })

    const stale = await push(harness, harness.phoneB, {
      ops: [{ opId: 'm-stale', kind: 'memory', memory: makeMemory('m1', 'Stale overwrite', 7_000, { revision: 5 }) }],
    })
    expect((await stale.json()) as MemoryPushResponse).toMatchObject({ appliedCount: 0, skippedCount: 1, serverSeq: 1 })

    const tieWinner = harness.phoneA.deviceId > harness.phoneB.deviceId ? harness.phoneA : harness.phoneB
    const tieLoser = tieWinner === harness.phoneA ? harness.phoneB : harness.phoneA
    await push(harness, tieLoser, {
      ops: [{ opId: 'tie-1', kind: 'memory', memory: makeMemory('m-tie', 'Loser side', 9_000) }],
    })
    await push(harness, tieWinner, {
      ops: [{ opId: 'tie-2', kind: 'memory', memory: makeMemory('m-tie', 'Winner side', 9_000) }],
    })

    const deleted = await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'm-del',
          kind: 'memory',
          memory: makeMemory('m1', 'Version B newer', 12_000, { deletedAt: 12_000, revision: 3 }),
        },
      ],
    })
    expect((await deleted.json()) as MemoryPushResponse).toMatchObject({ appliedCount: 1 })

    const pulled = await pull(harness, harness.phoneB, 0)
    const byId = new Map(pulled.entries.map((e) => [e.memory?.id, e.memory]))
    expect(byId.get('m1')?.deletedAt).toBe(12_000)
    expect(byId.get('m-tie')?.content).toBe('Winner side')
  })

  it('merges entity aliases across devices by canonical name and compacts pull ops', async () => {
    const harness = await start(() => 30_000)
    await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ent-a',
          kind: 'entity',
          entity: makeEntity('ent-a-id', 'Zhang Ming', 5_000, { aliases: ['Xiaoming'], summary: 'Coworker' }),
        },
      ],
    })
    await push(harness, harness.phoneB, {
      ops: [
        {
          opId: 'ent-b',
          kind: 'entity',
          entity: makeEntity('ent-b-id', 'zhang ming', 6_000, { aliases: ['Ming'], summary: null }),
        },
      ],
    })
    await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ent-a2',
          kind: 'entity',
          entity: makeEntity('ent-a-id', 'Zhang Ming', 4_000, { aliases: ['Uncle Zhang'] }),
        },
      ],
    })

    const page1 = await pull(harness, harness.phoneB, 0, 1)
    expect(page1.entries).toHaveLength(1)
    expect(page1.hasMore).toBe(false)
    expect(page1.serverSeq).toBe(3)
    const entity = page1.entries[0]?.entity
    expect(entity?.aliases).toEqual(['Xiaoming', 'Ming', 'Uncle Zhang'])
    expect(entity?.summary).toBe('Coworker')

    await push(harness, harness.phoneA, {
      ops: [
        {
          opId: 'ent-merge',
          kind: 'entity',
          entity: makeEntity('ent-old', 'Xiaowang', 10_000, {
            mergedInto: 'Laowang',
            deletedAt: 10_000,
            revision: 2,
          }),
        },
      ],
    })
    const page2 = await pull(harness, harness.phoneB, page1.nextSince, 10)
    expect(page2.entries[0]?.entity?.mergedInto).toBe('Laowang')
    expect(page2.entries[0]?.entity?.deletedAt).toBe(10_000)
  })

  it('bounds client future dreamCompletedAt to server time and rejects unauthenticated or oversized requests', async () => {
    let now = 500_000
    const harness = await start(() => now)
    expect((await fetch(`${harness.baseUrl}/v2/memory/pull`)).status).toBe(401)
    expect((await fetch(`${harness.baseUrl}/v1/memory/snapshot`)).status).toBe(404)

    const futureDream = await push(harness, harness.phoneA, {
      ops: [],
      dreamCompletedAt: now + 999_999_999,
    })
    expect(futureDream.status).toBe(200)
    expect(((await futureDream.json()) as MemoryPushResponse).lastDreamAt).toBe(500_000)

    const hugeOps = Array.from({ length: 201 }, (_, idx) => ({
      opId: `bulk-${idx}`,
      kind: 'memory',
      memory: makeMemory(`m-${idx}`, 'Item', 1_000),
    }))
    const tooMany = await push(harness, harness.phoneA, { ops: hugeOps })
    expect(tooMany.status).toBe(400)
  })

  it('rejects oversized or malformed ops per-item without stalling valid ops in the batch', async () => {
    const harness = await start(() => 600_000)
    const oversizedContent = 'a'.repeat(8_193)
    const oversizedName = 'n'.repeat(129)

    const partial = await push(harness, harness.phoneA, {
      ops: [
        { opId: 'bad-content', kind: 'memory', memory: makeMemory('m-bad', oversizedContent, 1_000) },
        { opId: 'good-mem', kind: 'memory', memory: makeMemory('m-good', 'Valid short item', 2_000) },
        { opId: 'bad-entity', kind: 'entity', entity: makeEntity('e-bad', oversizedName, 3_000) },
      ],
    })
    expect(partial.status).toBe(200)
    const partialBody = (await partial.json()) as MemoryPushResponse
    expect(partialBody).toEqual({
      serverSeq: 1,
      appliedCount: 1,
      skippedCount: 0,
      lastDreamAt: null,
      rejectedCount: 2,
      rejected: [
        { opId: 'bad-content', reason: 'memory.content length invalid' },
        { opId: 'bad-entity', reason: 'entity.canonicalName length invalid' },
      ],
    })
    expect(harness.changed).toEqual([{ version: 1, source: harness.phoneA.deviceId }])

    const pulled = await pull(harness, harness.phoneB, 0)
    expect(pulled.entries.map((entry) => entry.memory?.content)).toEqual(['Valid short item'])

    const allBad = await push(harness, harness.phoneA, {
      ops: [{ kind: 'unknown', memory: makeMemory('m-bad-2', 'x', 4_000) }],
    })
    expect(allBad.status).toBe(200)
    const allBadBody = (await allBad.json()) as MemoryPushResponse
    expect(allBadBody).toEqual({
      serverSeq: 1,
      appliedCount: 0,
      skippedCount: 0,
      lastDreamAt: null,
      rejectedCount: 1,
      rejected: [{ opId: 'index:0', reason: 'opId is required' }],
    })
    expect(harness.changed).toHaveLength(1)
  })
})
