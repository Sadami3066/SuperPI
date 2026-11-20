import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { PROTOCOL_VERSION, type DeviceListResponse, type EnrollResponse, type PairCodeResponse } from '@superpi/protocol'
import { createRelayServer, type RelayServer } from '../src/server/http.ts'
import { RelayStore } from '../src/server/store.ts'
import { hashSecret, newAccountId } from '../src/server/auth.ts'
import type { RelayConfig } from '../src/server/config.ts'

const LOGIN_SECRET = 'test-login-secret'

interface Harness {
  relay: RelayServer
  store: RelayStore
  storeDir: string
  baseUrl: string
}

const harnesses: Harness[] = []

afterEach(async () => {
  while (harnesses.length > 0) {
    const harness = harnesses.pop()
    if (!harness) continue
    await harness.relay.close()
    harness.store.close()
    rmSync(harness.storeDir, { recursive: true, force: true })
  }
})

async function startRelay(): Promise<Harness> {
  const storeDir = mkdtempSync(path.join(tmpdir(), 'superpi-relay-'))
  const store = new RelayStore(storeDir)
  store.createAccount(newAccountId(), hashSecret(LOGIN_SECRET), Date.now())
  const config: RelayConfig = {
    host: '127.0.0.1',
    port: 0,
    dataDir: storeDir,
    certPath: null,
    keyPath: null,
    version: '0.1.0',
    entries: [{ kind: 'public', origin: 'https://relay.test:8443' }],
  }
  const relay = createRelayServer({ config, store, fingerprint: 'fp-test', entries: config.entries })
  const address = await relay.listen()
  const harness: Harness = { relay, store, storeDir, baseUrl: `http://127.0.0.1:${address.port}` }
  harnesses.push(harness)
  return harness
}

async function enroll(harness: Harness, body: Record<string, unknown>): Promise<Response> {
  return fetch(`${harness.baseUrl}/v1/pair/enroll`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
}

function auth(token: string): Record<string, string> {
  return { authorization: `Bearer ${token}` }
}

describe('relay http', () => {
  it('serves a health document', async () => {
    const harness = await startRelay()
    const response = await fetch(`${harness.baseUrl}/v1/health`)
    expect(response.status).toBe(200)
    const body = (await response.json()) as Record<string, unknown>
    expect(body).toMatchObject({ ok: true, protocolVersion: PROTOCOL_VERSION, fingerprint: 'fp-test' })
  })

  it('enrolls the first device with the login secret', async () => {
    const harness = await startRelay()
    const response = await enroll(harness, { loginSecret: LOGIN_SECRET, deviceName: 'My phone', platform: 'android' })
    expect(response.status).toBe(200)
    const body = (await response.json()) as EnrollResponse
    expect(body.deviceId).toMatch(/^dev-/)
    expect(body.token.length).toBeGreaterThan(20)
    expect(body.fingerprint).toBe('fp-test')
    expect(body.entries).toEqual([{ kind: 'public', origin: 'https://relay.test:8443' }])

    const listResponse = await fetch(`${harness.baseUrl}/v1/devices`, { headers: auth(body.token) })
    expect(listResponse.status).toBe(200)
    const list = (await listResponse.json()) as DeviceListResponse
    expect(list.devices).toHaveLength(1)
    expect(list.devices[0]).toMatchObject({ name: 'My phone', platform: 'android', online: false })
  })

  it('rejects a wrong login secret', async () => {
    const harness = await startRelay()
    const response = await enroll(harness, { loginSecret: 'nope', deviceName: 'X', platform: 'desktop' })
    expect(response.status).toBe(401)
    expect(((await response.json()) as Record<string, unknown>).code).toBe('unauthorized')
  })

  it('enrolls a second device with a one-time pair code', async () => {
    const harness = await startRelay()
    const first = (await (
      await enroll(harness, { loginSecret: LOGIN_SECRET, deviceName: 'Desktop', platform: 'desktop' })
    ).json()) as EnrollResponse

    const codeResponse = await fetch(`${harness.baseUrl}/v1/pair/code`, { method: 'POST', headers: auth(first.token) })
    expect(codeResponse.status).toBe(200)
    const codeBody = (await codeResponse.json()) as PairCodeResponse
    expect(codeBody.code).toMatch(/^[A-Z2-9]{8}$/)
    expect(codeBody.expiresAt).toBeGreaterThan(Date.now())

    const second = await enroll(harness, { pairCode: codeBody.code, deviceName: 'My phone', platform: 'android' })
    expect(second.status).toBe(200)
    const secondBody = (await second.json()) as EnrollResponse
    expect(secondBody.deviceId).not.toBe(first.deviceId)

    const reuse = await enroll(harness, { pairCode: codeBody.code, deviceName: 'Another', platform: 'cli' })
    expect(reuse.status).toBe(401)
  })

  it('revokes a device and rejects its token afterwards', async () => {
    const harness = await startRelay()
    const first = (await (
      await enroll(harness, { loginSecret: LOGIN_SECRET, deviceName: 'Desktop', platform: 'desktop' })
    ).json()) as EnrollResponse
    const second = (await (
      await enroll(harness, { loginSecret: LOGIN_SECRET, deviceName: 'Phone', platform: 'android' })
    ).json()) as EnrollResponse

    const revoke = await fetch(`${harness.baseUrl}/v1/devices/${second.deviceId}`, {
      method: 'DELETE',
      headers: auth(first.token),
    })
    expect(revoke.status).toBe(200)

    const afterRevoke = await fetch(`${harness.baseUrl}/v1/devices`, { headers: auth(second.token) })
    expect(afterRevoke.status).toBe(401)

    const list = (await (await fetch(`${harness.baseUrl}/v1/devices`, { headers: auth(first.token) })).json()) as DeviceListResponse
    expect(list.devices).toHaveLength(1)
    expect(list.devices[0]?.deviceId).toBe(first.deviceId)
  })

  it('rejects requests without a bearer token', async () => {
    const harness = await startRelay()
    const response = await fetch(`${harness.baseUrl}/v1/devices`)
    expect(response.status).toBe(401)
  })

  it('rejects malformed enrollment bodies', async () => {
    const harness = await startRelay()
    const response = await enroll(harness, { loginSecret: LOGIN_SECRET })
    expect(response.status).toBe(400)
    expect(((await response.json()) as Record<string, unknown>).code).toBe('bad_request')

    const notJson = await fetch(`${harness.baseUrl}/v1/pair/enroll`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{oops',
    })
    expect(notJson.status).toBe(400)
  })

  it('answers unknown paths with a protocol error body', async () => {
    const harness = await startRelay()
    const response = await fetch(`${harness.baseUrl}/v1/nope`)
    expect(response.status).toBe(404)
    expect(((await response.json()) as Record<string, unknown>).code).toBe('not_found')
  })
})
