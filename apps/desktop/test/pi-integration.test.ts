import { afterEach, describe, expect, it } from 'vitest'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { PiRpcClient } from '../src/main/pi/rpc-client'

const piAvailable = spawnSync('pi', ['--version'], { encoding: 'utf8' }).status === 0
const tempDirs: string[] = []
const clients: PiRpcClient[] = []

afterEach(async () => {
  while (clients.length > 0) {
    const client = clients.pop()
    if (client) await client.stop()
  }
  while (tempDirs.length > 0) {
    const dir = tempDirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

describe.skipIf(!piAvailable)('pi rpc integration', () => {
  it('loads the superpi memory extension in the real harness', async () => {
    const cwd = mkdtempSync(path.join(tmpdir(), 'superpi-pi-ext-'))
    tempDirs.push(cwd)
    const snapshot = path.join(cwd, 'snapshot.json')
    writeFileSync(snapshot, JSON.stringify({ version: 1, files: { 'profile.md': '- likes tea' } }), 'utf8')
    const extension = new URL('../extensions/superpi-memory.mjs', import.meta.url).pathname
    const client = new PiRpcClient({
      command: 'pi',
      args: ['--mode', 'rpc', '--no-session', '-e', extension],
      cwd,
      env: { ...process.env, SUPERPI_MEMORY_FILE: snapshot },
    })
    clients.push(client)
    await client.start()
    const state = await client.getState<Record<string, unknown>>()
    expect(state).toBeTypeOf('object')
    expect(client.running).toBe(true)
  }, 30_000)

  it('starts the real pi harness and answers get_state', async () => {
    const cwd = mkdtempSync(path.join(tmpdir(), 'superpi-pi-'))
    tempDirs.push(cwd)
    const client = new PiRpcClient({ command: 'pi', args: ['--mode', 'rpc', '--no-session'], cwd })
    clients.push(client)
    await client.start()
    const state = await client.getState<Record<string, unknown>>()
    expect(state).toBeTypeOf('object')
    expect(JSON.stringify(state)).toContain('model')
    expect(client.pid).toBeGreaterThan(0)
  }, 30_000)
})
