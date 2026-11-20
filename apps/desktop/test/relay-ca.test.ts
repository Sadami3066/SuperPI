import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { RelayCredentialStore } from '../src/main/relay/credentials'
import { materializeRelayCa, relayCaPath, removeRelayCa } from '../src/main/relay/ca-cache'
import { RelayService } from '../src/main/relay/service'

const dirs: string[] = []

function tempHome(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-ca-'))
  dirs.push(dir)
  return dir
}

const SAMPLE_CA = [
  '-----BEGIN CERTIFICATE-----',
  'MIIBszCCAVmgAwIBAgIUExampleCaCertificateForTestsOnly000000',
  '-----END CERTIFICATE-----',
].join('\n')

function serviceFor(homeDir: string): RelayService {
  return new RelayService({
    homeDir,
    bots: () => [],
    broadcast: () => undefined,
  })
}

afterEach(() => {
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

describe('relay CA cache', () => {
  it('writes the pinned CA where pi can read it', () => {
    const home = tempHome()
    const file = materializeRelayCa(home, SAMPLE_CA)
    expect(file).toBe(relayCaPath(home))
    expect(readFileSync(file, 'utf8')).toBe(`${SAMPLE_CA}\n`)
    expect(statSync(file).mode & 0o777).toBe(0o600)
  })

  it('does not rewrite an unchanged CA and updates it when it changes', async () => {
    const home = tempHome()
    const file = materializeRelayCa(home, SAMPLE_CA)
    const firstWrite = statSync(file).mtimeMs
    await new Promise((resolve) => setTimeout(resolve, 20))
    materializeRelayCa(home, SAMPLE_CA)
    expect(statSync(file).mtimeMs).toBe(firstWrite)

    const rotated = SAMPLE_CA.replace('ExampleCa', 'RotatedCa')
    materializeRelayCa(home, `${rotated}\n`)
    expect(readFileSync(file, 'utf8')).toBe(`${rotated}\n`)

    removeRelayCa(home)
    expect(() => statSync(file)).toThrow()
  })

  it('rejects an empty CA', () => {
    const home = tempHome()
    expect(() => materializeRelayCa(home, '   \n')).toThrow(/empty/)
  })
})

describe('pi environment for relay memory', () => {
  it('trusts exactly the pinned relay CA and passes endpoint credentials', () => {
    const home = tempHome()
    new RelayCredentialStore(home).save({
      origin: 'https://127.0.0.1:1',
      caPem: SAMPLE_CA,
      deviceId: 'dev-1',
      token: 'token-1',
      deviceName: 'Desktop',
      accountId: 'acct-1',
      enrolledAt: Date.now(),
    })
    const service = serviceFor(home)
    try {
      const env = service.memoryEnvironment()
      expect(env.SUPERPI_RELAY_ENDPOINT).toBe('https://127.0.0.1:1')
      expect(env.SUPERPI_RELAY_TOKEN).toBe('token-1')
      expect(env.NODE_EXTRA_CA_CERTS).toBe(relayCaPath(home))
      expect(readFileSync(env.NODE_EXTRA_CA_CERTS ?? '', 'utf8')).toBe(`${SAMPLE_CA}\n`)
      expect(env.NODE_TLS_REJECT_UNAUTHORIZED).toBeUndefined()
    } finally {
      void service.dispose()
    }
  })

  it('adds no extra CA for a plain http relay', () => {
    const home = tempHome()
    new RelayCredentialStore(home).save({
      origin: 'http://127.0.0.1:1',
      caPem: null,
      deviceId: 'dev-1',
      token: 'token-1',
      deviceName: 'Desktop',
      accountId: 'acct-1',
      enrolledAt: Date.now(),
    })
    const service = serviceFor(home)
    try {
      expect(service.memoryEnvironment().NODE_EXTRA_CA_CERTS).toBeUndefined()
    } finally {
      void service.dispose()
    }
  })

  it('has no relay environment when the device is not paired', () => {
    const service = serviceFor(tempHome())
    try {
      const env = service.memoryEnvironment()
      expect(Object.keys(env)).toEqual(['SUPERPI_MEMORY_FILE'])
      expect(env.SUPERPI_MEMORY_FILE?.endsWith(path.join('cache', 'memory', 'snapshot.json'))).toBe(true)
    } finally {
      void service.dispose()
    }
  })

  it('removes the cached CA on logout', () => {
    const home = tempHome()
    new RelayCredentialStore(home).save({
      origin: 'http://127.0.0.1:1',
      caPem: SAMPLE_CA,
      deviceId: 'dev-1',
      token: 'token-1',
      deviceName: 'Desktop',
      accountId: 'acct-1',
      enrolledAt: Date.now(),
    })
    const service = serviceFor(home)
    try {
      materializeRelayCa(home, SAMPLE_CA)
      service.logout()
      expect(() => statSync(relayCaPath(home))).toThrow()
    } finally {
      void service.dispose()
    }
  })
})

describe('relay CA cache file content', () => {
  it('keeps the PEM text intact apart from a trailing newline', () => {
    const home = tempHome()
    const file = materializeRelayCa(home, `${SAMPLE_CA}\n\n`)
    expect(readFileSync(file, 'utf8')).toBe(`${SAMPLE_CA}\n`)
  })
})
