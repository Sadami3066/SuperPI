import { afterEach, describe, expect, it } from 'vitest'
import { PROTOCOL_VERSION } from '@superpi/protocol'
import { createRelayServer, type RelayServer } from '../src/server/http.ts'
import type { RelayConfig } from '../src/server/config.ts'

const baseConfig: RelayConfig = {
  host: '127.0.0.1',
  port: 0,
  dataDir: '/tmp/superpi-relay-test',
  certPath: null,
  keyPath: null,
  version: '0.1.0',
}

let relay: RelayServer | null = null

afterEach(async () => {
  if (relay) {
    await relay.close()
    relay = null
  }
})

describe('relay http', () => {
  it('serves a health document', async () => {
    relay = createRelayServer(baseConfig, 'abc123')
    const address = await relay.listen()
    const response = await fetch(`http://127.0.0.1:${address.port}/v1/health`)
    expect(response.status).toBe(200)
    const body = (await response.json()) as Record<string, unknown>
    expect(body).toMatchObject({ ok: true, protocolVersion: PROTOCOL_VERSION, fingerprint: 'abc123' })
  })

  it('answers unknown paths with a protocol error body', async () => {
    relay = createRelayServer(baseConfig, null)
    const address = await relay.listen()
    const response = await fetch(`http://127.0.0.1:${address.port}/v1/nope`)
    expect(response.status).toBe(404)
    const body = (await response.json()) as Record<string, unknown>
    expect(body.code).toBe('not_found')
  })
})
