import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import WebSocket from 'ws'
import type {
  EnrollResponse,
  MemoryFileResponse,
  MemorySnapshotResponse,
  MemoryWriteResponse,
  ServerFrame,
} from '@superpi/protocol'
import { createRelayServer, type RelayServer } from '../src/server/http.ts'
import { RelayStore } from '../src/server/store.ts'
import { MemoryStore } from '../src/server/memory.ts'
import { createRealtimeHub, type RealtimeHub } from '../src/server/realtime.ts'
import { hashSecret, newAccountId } from '../src/server/auth.ts'
import type { RelayConfig } from '../src/server/config.ts'

const LOGIN_SECRET = 'memory-login-secret'

interface Harness {
  relay: RelayServer
  hub: RealtimeHub
  store: RelayStore
  dir: string
  baseUrl: string
  wsBase: string
  token: string
  deviceId: string
  changed: number[]
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

async function start(): Promise<Harness> {
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-memory-'))
  const store = new RelayStore(dir)
  store.createAccount(newAccountId(), hashSecret(LOGIN_SECRET), Date.now())
  const memory = new MemoryStore(path.join(dir, 'memory'), store.database)
  const changed: number[] = []
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
  const hub = createRealtimeHub(store)
  const relay = createRelayServer({
    config,
    store,
    fingerprint: null,
    entries: [],
    memory,
    onMemoryChanged: (version) => {
      changed.push(version)
      hub.broadcastMemoryChanged(version)
    },
  })
  hub.attach(relay.server)
  const address = await relay.listen()
  const baseUrl = `http://127.0.0.1:${address.port}`
  const enrolled = (await (
    await fetch(`${baseUrl}/v1/pair/enroll`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ loginSecret: LOGIN_SECRET, deviceName: 'My phone', platform: 'android' }),
    })
  ).json()) as EnrollResponse
  const harness: Harness = {
    relay,
    hub,
    store,
    dir,
    baseUrl,
    wsBase: `ws://127.0.0.1:${address.port}/v1/ws`,
    token: enrolled.token,
    deviceId: enrolled.deviceId,
    changed,
  }
  harnesses.push(harness)
  return harness
}

function auth(harness: Harness): Record<string, string> {
  return { authorization: `Bearer ${harness.token}`, 'content-type': 'application/json' }
}

async function write(harness: Harness, body: Record<string, unknown>): Promise<Response> {
  return fetch(`${harness.baseUrl}/v1/memory/write`, {
    method: 'POST',
    headers: auth(harness),
    body: JSON.stringify(body),
  })
}

async function snapshot(harness: Harness): Promise<MemorySnapshotResponse> {
  return (await (await fetch(`${harness.baseUrl}/v1/memory/snapshot`, { headers: auth(harness) })).json()) as MemorySnapshotResponse
}

async function waitFor(predicate: () => boolean, timeoutMs = 3_000): Promise<void> {
  const started = Date.now()
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error('condition not reached in time')
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
}

describe('shared memory', () => {
  it('appends an entry with a source tag and bumps the version', async () => {
    const harness = await start()
    const response = await write(harness, { op: 'append', path: 'profile.md', text: 'Likes dark mode', idempotencyKey: 'k1' })
    expect(response.status).toBe(200)
    const body = (await response.json()) as MemoryWriteResponse
    expect(body.version).toBe(1)

    const snap = await snapshot(harness)
    expect(snap.version).toBe(1)
    expect(snap.files['profile.md']).toContain('Likes dark mode')
    expect(snap.files['profile.md']).toContain('<!-- src: My phone ')
    expect(harness.changed).toEqual([1])
  })

  it('treats a repeated idempotency key as the same write', async () => {
    const harness = await start()
    await write(harness, { op: 'append', path: 'profile.md', text: 'One', idempotencyKey: 'same' })
    const second = await write(harness, { op: 'append', path: 'profile.md', text: 'Two', idempotencyKey: 'same' })
    expect(((await second.json()) as MemoryWriteResponse).version).toBe(1)
    const snap = await snapshot(harness)
    expect(snap.files['profile.md']?.match(/One/g)).toHaveLength(1)
    expect(snap.files['profile.md']).not.toContain('Two')
    expect(harness.changed).toEqual([1])
  })

  it('replaces a section and reports a conflict on a stale base version', async () => {
    const harness = await start()
    const first = await write(harness, {
      op: 'replace_section',
      path: 'context.md',
      section: 'Now & Ahead',
      text: 'Ship the desktop shell',
      idempotencyKey: 's1',
      baseVersion: 0,
    })
    expect(((await first.json()) as MemoryWriteResponse).version).toBe(1)

    const conflict = await write(harness, {
      op: 'replace_section',
      path: 'context.md',
      section: 'Now & Ahead',
      text: 'Ship something else',
      idempotencyKey: 's2',
      baseVersion: 0,
    })
    expect(conflict.status).toBe(409)
    const body = (await conflict.json()) as { code: string; details: { currentVersion: number } }
    expect(body.code).toBe('memory_conflict')
    expect(body.details.currentVersion).toBe(1)

    const snap = await snapshot(harness)
    expect(snap.files['context.md']).toContain('## Now & Ahead')
    expect(snap.files['context.md']).toContain('Ship the desktop shell')
    expect(snap.files['context.md']).not.toContain('Ship something else')
  })

  it('requires a base version for overwrite', async () => {
    const harness = await start()
    const missing = await write(harness, { op: 'overwrite', path: 'cookbook.md', text: '# Notes', idempotencyKey: 'o1' })
    expect(missing.status).toBe(400)

    const accepted = await write(harness, {
      op: 'overwrite',
      path: 'cookbook.md',
      text: '# Notes\n\nUse the relay.',
      idempotencyKey: 'o2',
      baseVersion: 0,
    })
    expect(accepted.status).toBe(200)
    const snap = await snapshot(harness)
    expect(snap.files['cookbook.md']).toContain('Use the relay.')
  })

  it('serves a single file by path', async () => {
    const harness = await start()
    await write(harness, { op: 'append', path: 'daily/2026/11/2026-11/2026-11-20.md', text: 'Worked on memory', idempotencyKey: 'd1' })
    const response = await fetch(`${harness.baseUrl}/v1/memory/file?path=daily/2026/11/2026-11/2026-11-20.md`, {
      headers: auth(harness),
    })
    expect(response.status).toBe(200)
    const body = (await response.json()) as MemoryFileResponse
    expect(body.content).toContain('Worked on memory')
  })

  it('rejects paths outside the memory directory', async () => {
    const harness = await start()
    const traversal = await write(harness, { op: 'append', path: '../escape.md', text: 'nope', idempotencyKey: 'x1' })
    expect(traversal.status).toBe(400)
    const absolute = await write(harness, { op: 'append', path: '/etc/passwd', text: 'nope', idempotencyKey: 'x2' })
    expect(absolute.status).toBe(400)
  })

  it('broadcasts memory changes to connected devices', async () => {
    const harness = await start()
    const second = (await (
      await fetch(`${harness.baseUrl}/v1/pair/enroll`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ loginSecret: LOGIN_SECRET, deviceName: 'Desktop', platform: 'desktop' }),
      })
    ).json()) as EnrollResponse

    const socket = new WebSocket(harness.wsBase, { headers: { authorization: `Bearer ${second.token}` } })
    await new Promise<void>((resolve, reject) => {
      socket.once('open', () => resolve())
      socket.once('error', (reject))
    })
    const frames: ServerFrame[] = []
    socket.on('message', (raw) => frames.push(JSON.parse(raw.toString()) as ServerFrame))
    socket.send(
      JSON.stringify({
        type: 'hello',
        protocolVersion: 1,
        deviceId: second.deviceId,
        platform: 'desktop',
        appVersion: '0.1.0',
        bots: [],
        capabilities: [],
      }),
    )
    await waitFor(() => frames.some((frame) => frame.type === 'hello_ok'))

    await write(harness, { op: 'append', path: 'profile.md', text: 'Broadcast me', idempotencyKey: 'b1' })
    await waitFor(() => frames.some((frame) => frame.type === 'memory_changed'))
    const changedFrame = frames.find((frame) => frame.type === 'memory_changed')
    expect(changedFrame).toMatchObject({ type: 'memory_changed', version: 1 })
    socket.close()
  })

  it('requires authentication', async () => {
    const harness = await start()
    const response = await fetch(`${harness.baseUrl}/v1/memory/snapshot`)
    expect(response.status).toBe(401)
  })
})
