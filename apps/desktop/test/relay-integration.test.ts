import { afterEach, describe, expect, it } from 'vitest'
import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { RelayService, type RelayStatus } from '../src/main/relay/service'

const remoteRepo = fileURLToPath(new URL('../../../../SuperPI-Remote/', import.meta.url))
const remoteServer = path.join(remoteRepo, 'src/server/index.ts')
const certScript = path.join(remoteRepo, 'scripts/gen-cert.sh')
const available = existsSync(remoteServer)

const children: ChildProcess[] = []
const dirs: string[] = []

afterEach(async () => {
  while (children.length > 0) {
    const child = children.pop()
    if (child && child.exitCode === null) {
      child.kill('SIGTERM')
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
  }
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

interface RelayProcess {
  port: number
  secret: string
}

async function startRelay(extraEnv: Record<string, string> = {}): Promise<RelayProcess> {
  const dataDir = mkdtempSync(path.join(tmpdir(), 'superpi-relay-data-'))
  dirs.push(dataDir)
  const child = spawn(process.execPath, [remoteServer], {
    cwd: remoteRepo,
    env: {
      ...process.env,
      SUPERPI_HOST: '127.0.0.1',
      SUPERPI_PORT: '0',
      SUPERPI_DATA_DIR: dataDir,
      ...extraEnv,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  children.push(child)

  let stdout = ''
  child.stdout?.setEncoding('utf8')
  child.stdout?.on('data', (chunk: string) => {
    stdout += chunk
  })
  child.stderr?.setEncoding('utf8')
  child.stderr?.on('data', (chunk: string) => {
    stdout += chunk
  })

  await waitFor(() => /listening on 127\.0\.0\.1:(\d+)/.test(stdout) && /login secret \(shown once\): (\S+)/.test(stdout), 10_000)
  const port = Number.parseInt(/listening on 127\.0\.0\.1:(\d+)/.exec(stdout)?.[1] ?? '', 10)
  const secret = /login secret \(shown once\): (\S+)/.exec(stdout)?.[1] ?? ''
  return { port, secret }
}

function createService(): { service: RelayService; updates: RelayStatus[] } {
  const homeDir = mkdtempSync(path.join(tmpdir(), 'superpi-relay-home-'))
  dirs.push(homeDir)
  const updates: RelayStatus[] = []
  const service = new RelayService({
    homeDir,
    bots: () => [{ id: 'pi', name: 'PI' }],
    broadcast: (status) => updates.push(status),
  })
  return { service, updates }
}

describe.skipIf(!available)('desktop relay client against a live relay', () => {
  it('pairs with the login secret and reports the device online', async () => {
    const relay = await startRelay()
    const { service } = createService()
    try {
      await service.pair({
        link: `http://127.0.0.1:${relay.port}`,
        loginSecret: relay.secret,
        deviceName: 'Integration Desktop',
      })
      await waitFor(() => service.status().state === 'online', 8_000)
      const status = service.status()
      expect(status.state).toBe('online')
      expect(status.deviceName).toBe('Integration Desktop')
      expect(status.origin).toBe(`http://127.0.0.1:${relay.port}`)
      expect(status.devices.some((device) => device.deviceId === status.deviceId)).toBe(true)
    } finally {
      service.dispose()
    }
  }, 20_000)

  it('pins the certificate authority when pairing over https', async () => {
    const certs = mkdtempSync(path.join(tmpdir(), 'superpi-certs-'))
    dirs.push(certs)
    execFileSync('bash', [certScript, certs], { stdio: 'ignore' })
    const fingerprint = readFileSync(path.join(certs, 'fingerprint.txt'), 'utf8').trim()
    const relay = await startRelay({
      SUPERPI_CERT: path.join(certs, 'server.crt'),
      SUPERPI_KEY: path.join(certs, 'server.key'),
      SUPERPI_CA: path.join(certs, 'ca.crt'),
    })
    const { service } = createService()
    try {
      await service.pair({
        link: `https://127.0.0.1:${relay.port}#fp=${fingerprint}`,
        loginSecret: relay.secret,
        deviceName: 'TLS Desktop',
      })
      await waitFor(() => service.status().state === 'online', 8_000)
      expect(service.status().origin).toBe(`https://127.0.0.1:${relay.port}`)
    } finally {
      service.dispose()
    }
  }, 30_000)

  it('refuses a pairing link whose fingerprint does not match', async () => {
    const certs = mkdtempSync(path.join(tmpdir(), 'superpi-certs-'))
    dirs.push(certs)
    execFileSync('bash', [certScript, certs], { stdio: 'ignore' })
    const relay = await startRelay({
      SUPERPI_CERT: path.join(certs, 'server.crt'),
      SUPERPI_KEY: path.join(certs, 'server.key'),
      SUPERPI_CA: path.join(certs, 'ca.crt'),
    })
    const { service } = createService()
    try {
      await expect(
        service.pair({
          link: `https://127.0.0.1:${relay.port}#fp=${'0'.repeat(64)}`,
          loginSecret: relay.secret,
          deviceName: 'TLS Desktop',
        }),
      ).rejects.toThrow(/does not match the pairing link/)
    } finally {
      service.dispose()
    }
  }, 30_000)

  it('rejects a wrong login secret', async () => {
    const relay = await startRelay()
    const { service } = createService()
    try {
      await expect(
        service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: 'wrong-secret', deviceName: 'Nope' }),
      ).rejects.toThrow(/Pairing failed \(401\)/)
      expect(service.status().state).toBe('disconnected')
    } finally {
      service.dispose()
    }
  }, 20_000)
})

async function waitFor(predicate: () => boolean, timeoutMs: number): Promise<void> {
  const started = Date.now()
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error('condition not reached in time')
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
}
