import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { RelayCredentialStore } from '../src/main/relay/credentials'

const dirs: string[] = []

function tempHome(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-credentials-'))
  dirs.push(dir)
  return dir
}

afterEach(() => {
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

describe('relay credentials', () => {
  it('round-trips saved credentials with restrictive permissions', () => {
    const store = new RelayCredentialStore(tempHome())
    expect(store.read()).toBeNull()

    store.save({
      origin: 'https://relay.test:8443',
      caPem: '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n',
      deviceId: 'dev-1',
      token: 'token-1',
      deviceName: 'Desktop',
      accountId: 'acct-1',
      enrolledAt: 1795000000000,
    })

    const read = store.read()
    expect(read).toMatchObject({ deviceId: 'dev-1', deviceName: 'Desktop', token: 'token-1' })
    const file = path.join(store['homeDir'], 'relay.json')
    expect(statSync(file).mode & 0o777).toBe(0o600)
  })

  it('ignores corrupt files and clears cleanly', () => {
    const home = tempHome()
    const store = new RelayCredentialStore(home)
    expect(store.read()).toBeNull()
    store.clear()
    expect(store.read()).toBeNull()
  })
})
