import { afterEach, describe, expect, it } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { MemoryClient } from '../src/main/relay/memory-client'
import { RelayCredentialStore } from '../src/main/relay/credentials'
import {
  cleanupHarness,
  createCerts,
  createService,
  hasRemoteRepo,
  startRelay,
  waitFor,
} from './helpers/relay-harness'

const extensionPath = fileURLToPath(new URL('../extensions/superpi-memory.mjs', import.meta.url))
const children: ChildProcess[] = []
const dirs: string[] = []

function tempDir(prefix: string): string {
  const dir = mkdtempSync(path.join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}

afterEach(async () => {
  while (children.length > 0) {
    const child = children.pop()
    if (child && child.exitCode === null) child.kill('SIGKILL')
  }
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
  await cleanupHarness()
})

interface ToolResult {
  content?: Array<{ text?: string }>
  isError?: boolean
}

function runMemoryWrite(script: string, writePath: string, text: string, env: NodeJS.ProcessEnv): Promise<ToolResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [script], {
      env: { ...env, WRITE_PATH: writePath, WRITE_TEXT: text },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    children.push(child)
    let stdout = ''
    let stderr = ''
    child.stdout?.setEncoding('utf8')
    child.stdout?.on('data', (chunk: string) => (stdout += chunk))
    child.stderr?.setEncoding('utf8')
    child.stderr?.on('data', (chunk: string) => (stderr += chunk))
    const timer = setTimeout(() => {
      child.kill('SIGKILL')
      reject(new Error(`memory_write child timed out; stderr: ${stderr.slice(0, 500)}`))
    }, 20_000)
    child.on('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.on('exit', () => {
      clearTimeout(timer)
      try {
        resolve(JSON.parse(stdout.trim()) as ToolResult)
      } catch {
        reject(new Error(`memory_write child produced no result: ${stdout} ${stderr}`))
      }
    })
  })
}

function invokeScript(): string {
  const script = path.join(tempDir('superpi-mw-script-'), 'invoke.mjs')
  writeFileSync(
    script,
    [
      `import memory from ${JSON.stringify(extensionPath)}`,
      'const tools = new Map()',
      'const pi = { on: () => undefined, registerTool: (definition) => tools.set(definition.name, definition) }',
      'memory(pi)',
      "const write = tools.get('memory_write')",
      "if (!write) { console.log(JSON.stringify({ error: 'memory_write missing' })); process.exit(2) }",
      "const result = await write.execute('call-1', { path: process.env.WRITE_PATH, text: process.env.WRITE_TEXT })",
      'console.log(JSON.stringify(result))',
    ].join('\n'),
    'utf8',
  )
  return script
}

describe.skipIf(!hasRemoteRepo)('memory write through the pinned relay CA', () => {
  it('fails without the CA and writes over the self-signed relay with NODE_EXTRA_CA_CERTS', async () => {
    const certs = createCerts()
    const relay = await startRelay({ SUPERPI_CERT: certs.cert, SUPERPI_KEY: certs.key, SUPERPI_CA: certs.ca })
    const homeDir = tempDir('superpi-mw-home-')
    const service = createService({ homeDir })
    try {
      await service.pair({
        link: `https://127.0.0.1:${relay.port}#fp=${certs.fingerprint}`,
        loginSecret: relay.secret,
        deviceName: 'Memory Desktop',
      })
      await waitFor(() => service.status().state === 'online', 10_000)

      const credentials = new RelayCredentialStore(homeDir).read()
      expect(credentials?.caPem).toBeTruthy()
      const env = service.memoryEnvironment()
      expect(env.NODE_EXTRA_CA_CERTS).toBe(path.join(homeDir, 'cache', 'relay-ca.pem'))
      expect(readFileSync(env.NODE_EXTRA_CA_CERTS ?? '', 'utf8')).toBe(`${credentials?.caPem?.trim()}\n`)

      const script = invokeScript()
      const withoutCa: NodeJS.ProcessEnv = { ...process.env, ...env }
      delete withoutCa.NODE_EXTRA_CA_CERTS
      const failed = await runMemoryWrite(script, 'profile.md', '- hidden test line', withoutCa)
      expect(failed.isError).toBe(true)
      expect(failed.content?.[0]?.text ?? '').toMatch(/Memory write failed/)

      const saved = await runMemoryWrite(script, 'profile.md', '- desktop write over https works', env)
      expect(saved.isError).toBeFalsy()
      expect(saved.content?.[0]?.text ?? '').toMatch(/Saved to profile\.md \(memory version \d+\)/)

      const client = new MemoryClient(homeDir)
      const summary = await client.sync(credentials!)
      expect(summary.files).toContain('profile.md')
      expect(client.readFile('profile.md')).toContain('desktop write over https works')

      const second = await runMemoryWrite(script, 'profile.md', '- second write works too', env)
      expect(second.isError).toBeFalsy()
      const next = await client.sync(credentials!)
      expect(next.version).toBeGreaterThan(summary.version)
      expect(client.readFile('profile.md')).toContain('second write works too')
    } finally {
      await service.dispose()
    }
  }, 60_000)
})
