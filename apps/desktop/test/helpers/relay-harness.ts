import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { EnrollResponse } from '@superpi/protocol'
import { RelayService, type RemoteEventPayload } from '../../src/main/relay/service'
import type { RemoteBotHost } from '../../src/main/relay/remote-server'
import type { MemorySummary } from '../../src/main/relay/memory-client'

export const remoteRepo = fileURLToPath(new URL('../../../../../SuperPI-Remote/', import.meta.url))
export const remoteServer = path.join(remoteRepo, 'src/server/index.ts')
export const certScript = path.join(remoteRepo, 'scripts/gen-cert.sh')
export const hasRemoteRepo = existsSync(remoteServer)

const children: ChildProcess[] = []
const dirs: string[] = []

export interface RelayProcess {
  port: number
  secret: string
}

export function tempDir(prefix: string): string {
  const dir = mkdtempSync(path.join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}

export function createCerts(): { cert: string; key: string; ca: string; fingerprint: string } {
  const certs = tempDir('superpi-certs-')
  execFileSync('bash', [certScript, certs], { stdio: 'ignore' })
  return {
    cert: path.join(certs, 'server.crt'),
    key: path.join(certs, 'server.key'),
    ca: path.join(certs, 'ca.crt'),
    fingerprint: readFileSync(path.join(certs, 'fingerprint.txt'), 'utf8').trim(),
  }
}

export async function startRelay(extraEnv: Record<string, string> = {}): Promise<RelayProcess> {
  const dataDir = tempDir('superpi-relay-data-')
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

  let output = ''
  child.stdout?.setEncoding('utf8')
  child.stdout?.on('data', (chunk: string) => {
    output += chunk
  })
  child.stderr?.setEncoding('utf8')
  child.stderr?.on('data', (chunk: string) => {
    output += chunk
  })

  await waitFor(
    () => /listening on 127\.0\.0\.1:(\d+)/.test(output) && /login secret \(shown once\): (\S+)/.test(output),
    10_000,
  )
  return {
    port: Number.parseInt(/listening on 127\.0\.0\.1:(\d+)/.exec(output)?.[1] ?? '', 10),
    secret: /login secret \(shown once\): (\S+)/.exec(output)?.[1] ?? '',
  }
}

export function createService(
  options: {
    onRemoteEvent?: (payload: RemoteEventPayload) => void
    onMemory?: (summary: MemorySummary | null) => void
    homeDir?: string
    host?: RemoteBotHost
  } = {},
): RelayService {
  return new RelayService({
    homeDir: options.homeDir ?? tempDir('superpi-relay-home-'),
    bots: () => [{ id: 'pi', name: 'PI' }],
    host: options.host,
    broadcast: () => undefined,
    broadcastMemory: options.onMemory,
    onRemoteEvent: options.onRemoteEvent,
  })
}

export async function writeMemory(
  port: number,
  token: string,
  body: Record<string, unknown>,
): Promise<{ version: number }> {
  const response = await fetch(`http://127.0.0.1:${port}/v1/memory/write`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (response.status !== 200) throw new Error(`memory write failed: ${response.status}`)
  return (await response.json()) as { version: number }
}

export async function enrollDevice(
  port: number,
  secret: string,
  deviceName: string,
  platform: 'android' | 'desktop' = 'android',
): Promise<EnrollResponse> {
  const response = await fetch(`http://127.0.0.1:${port}/v1/pair/enroll`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ loginSecret: secret, deviceName, platform }),
  })
  if (response.status !== 200) throw new Error(`enrollment failed: ${response.status}`)
  return (await response.json()) as EnrollResponse
}

export async function waitFor(predicate: () => boolean, timeoutMs: number): Promise<void> {
  const started = Date.now()
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error('condition not reached in time')
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
}

export async function cleanupHarness(): Promise<void> {
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
}
