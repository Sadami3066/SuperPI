import { afterEach, describe, expect, it } from 'vitest'
import { execFileSync, spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { X509Certificate } from 'node:crypto'
import { PROTOCOL_VERSION, type DeviceListResponse, type EnrollResponse, type HealthResponse, type PairCodeResponse } from '@superpi/protocol'
import { createRelayServer, type EnrollRateLimitOptions, type RelayServer } from '../src/server/http.ts'
import { RelayStore } from '../src/server/store.ts'
import { hashSecret, newAccountId, readFingerprint } from '../src/server/auth.ts'
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

async function startRelay(options?: { now?: () => number; enrollRateLimit?: EnrollRateLimitOptions }): Promise<Harness> {
  const storeDir = mkdtempSync(path.join(tmpdir(), 'superpi-relay-'))
  const store = new RelayStore(storeDir)
  store.createAccount(newAccountId(), hashSecret(LOGIN_SECRET), options?.now?.() ?? Date.now())
  const config: RelayConfig = {
    host: '127.0.0.1',
    port: 0,
    dataDir: storeDir,
    certPath: null,
    keyPath: null,
    caPath: null,
    version: '0.1.0',
    entries: [{ kind: 'public', origin: 'https://relay.test:8443' }],
  }
  const relay = createRelayServer({
    config,
    store,
    fingerprint: 'fp-test',
    entries: config.entries,
    now: options?.now,
    enrollRateLimit: options?.enrollRateLimit,
  })
  const address = await relay.listen()
  const harness: Harness = { relay, store, storeDir, baseUrl: `http://127.0.0.1:${address.port}` }
  harnesses.push(harness)
  return harness
}

function generateTestCerts(dir: string): { caPath: string; certPath: string; caFingerprint: string; leafFingerprint: string } {
  const caKey = path.join(dir, 'ca.key')
  const caPath = path.join(dir, 'ca.crt')
  const serverKey = path.join(dir, 'server.key')
  const serverCsr = path.join(dir, 'server.csr')
  const certPath = path.join(dir, 'server.crt')
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'ec',
      '-pkeyopt',
      'ec_paramgen_curve:prime256v1',
      '-sha256',
      '-days',
      '1',
      '-nodes',
      '-keyout',
      caKey,
      '-out',
      caPath,
      '-subj',
      '/CN=Test Relay CA',
    ],
    { stdio: 'pipe' },
  )
  execFileSync(
    'openssl',
    [
      'req',
      '-newkey',
      'ec',
      '-pkeyopt',
      'ec_paramgen_curve:prime256v1',
      '-sha256',
      '-days',
      '1',
      '-nodes',
      '-keyout',
      serverKey,
      '-out',
      serverCsr,
      '-subj',
      '/CN=test-relay',
    ],
    { stdio: 'pipe' },
  )
  execFileSync(
    'openssl',
    [
      'x509',
      '-req',
      '-in',
      serverCsr,
      '-CA',
      caPath,
      '-CAkey',
      caKey,
      '-CAcreateserial',
      '-out',
      certPath,
      '-days',
      '1',
      '-sha256',
    ],
    { stdio: 'pipe' },
  )
  const caFingerprint = readFingerprint(caPath)
  const leafFingerprint = readFingerprint(certPath)
  if (!caFingerprint || !leafFingerprint) throw new Error('failed to read generated fingerprints')
  return { caPath, certPath, caFingerprint, leafFingerprint }
}

async function spawnRelayEntrypoint(envOverrides: Record<string, string>): Promise<{
  baseUrl: string
  loginSecret: string
  stop: () => Promise<void>
}> {
  const entry = fileURLToPath(new URL('../src/server/index.ts', import.meta.url))
  const child = spawn(process.execPath, [entry], {
    env: { ...process.env, SUPERPI_HOST: '127.0.0.1', SUPERPI_PORT: '0', ...envOverrides },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let output = ''
  const ready = await new Promise<{ baseUrl: string; loginSecret: string }>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`relay did not start: ${output}`)), 5_000)
    const onData = (chunk: Buffer) => {
      output += chunk.toString('utf8')
      const listenMatch = /listening on ([^:\s]+):(\d+)/.exec(output)
      const secretMatch = /login secret \(shown once\): (\S+)/.exec(output)
      if (listenMatch && secretMatch) {
        clearTimeout(timer)
        resolve({ baseUrl: `http://${listenMatch[1]}:${listenMatch[2]}`, loginSecret: secretMatch[1] ?? '' })
      }
    }
    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    child.once('exit', (code) => {
      clearTimeout(timer)
      reject(new Error(`relay exited early (${code}): ${output}`))
    })
  })
  return {
    ...ready,
    stop: async () => {
      if (child.exitCode !== null) return
      await new Promise<void>((resolve) => {
        child.once('exit', () => resolve())
        child.kill('SIGTERM')
      })
    },
  }
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

  it('prefers the CA certificate fingerprint and falls back to certPath when caPath is absent', async () => {
    const certDir = mkdtempSync(path.join(tmpdir(), 'superpi-certs-'))
    const dataWithCa = mkdtempSync(path.join(tmpdir(), 'superpi-entry-ca-'))
    const dataWithoutCa = mkdtempSync(path.join(tmpdir(), 'superpi-entry-leaf-'))
    try {
      const certs = generateTestCerts(certDir)
      expect(certs.caFingerprint).not.toBe(certs.leafFingerprint)

      const withCa = await spawnRelayEntrypoint({
        SUPERPI_DATA_DIR: dataWithCa,
        SUPERPI_CA: certs.caPath,
        SUPERPI_CERT: certs.certPath,
      })
      try {
        const health = (await (await fetch(`${withCa.baseUrl}/v1/health`)).json()) as HealthResponse
        expect(health.fingerprint).toBe(certs.caFingerprint)

        const caPem = await (await fetch(`${withCa.baseUrl}/v1/ca`)).text()
        const caFromEndpoint = new X509Certificate(caPem).fingerprint256.replaceAll(':', '').toLowerCase()
        expect(caFromEndpoint).toBe(health.fingerprint)

        const enrolled = (await (
          await fetch(`${withCa.baseUrl}/v1/pair/enroll`, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ loginSecret: withCa.loginSecret, deviceName: 'Desktop', platform: 'desktop' }),
          })
        ).json()) as EnrollResponse
        expect(enrolled.fingerprint).toBe(certs.caFingerprint)

        const pairCode = (await (
          await fetch(`${withCa.baseUrl}/v1/pair/code`, {
            method: 'POST',
            headers: auth(enrolled.token),
          })
        ).json()) as PairCodeResponse
        expect(pairCode.fingerprint).toBe(certs.caFingerprint)
      } finally {
        await withCa.stop()
      }

      const withoutCa = await spawnRelayEntrypoint({
        SUPERPI_DATA_DIR: dataWithoutCa,
        SUPERPI_CERT: certs.certPath,
      })
      try {
        const health = (await (await fetch(`${withoutCa.baseUrl}/v1/health`)).json()) as HealthResponse
        expect(health.fingerprint).toBe(certs.leafFingerprint)
      } finally {
        await withoutCa.stop()
      }
    } finally {
      rmSync(certDir, { recursive: true, force: true })
      rmSync(dataWithCa, { recursive: true, force: true })
      rmSync(dataWithoutCa, { recursive: true, force: true })
    }
  })

  it('rate limits repeated enrollment authentication failures per IP', async () => {
    let clock = 1_000_000
    const harness = await startRelay({ now: () => clock })

    for (let attempt = 0; attempt < 10; attempt += 1) {
      const response = await enroll(harness, { loginSecret: 'wrong', deviceName: 'Attacker', platform: 'cli' })
      expect(response.status).toBe(401)
    }

    const blocked = await enroll(harness, { loginSecret: LOGIN_SECRET, deviceName: 'Valid', platform: 'desktop' })
    expect(blocked.status).toBe(429)
    expect(((await blocked.json()) as Record<string, unknown>).code).toBe('rate_limited')

    clock += 15 * 60 * 1000 + 1
    const recovered = await enroll(harness, { loginSecret: LOGIN_SECRET, deviceName: 'Valid', platform: 'desktop' })
    expect(recovered.status).toBe(200)
  })

  it('tracks pair code failures and locks active pair codes after repeated wrong guesses', async () => {
    const harness = await startRelay()
    const first = (await (
      await enroll(harness, { loginSecret: LOGIN_SECRET, deviceName: 'Desktop', platform: 'desktop' })
    ).json()) as EnrollResponse

    const codeBody = (await (
      await fetch(`${harness.baseUrl}/v1/pair/code`, { method: 'POST', headers: auth(first.token) })
    ).json()) as PairCodeResponse

    for (let attempt = 0; attempt < 5; attempt += 1) {
      const wrong = await enroll(harness, { pairCode: `BAD0000${attempt}`, deviceName: 'Phone', platform: 'android' })
      expect(wrong.status).toBe(401)
    }

    const locked = await enroll(harness, { pairCode: codeBody.code, deviceName: 'Phone', platform: 'android' })
    expect(locked.status).toBe(401)
    expect(((await locked.json()) as { message: string }).message).toContain('too_many_attempts')
  })
})
