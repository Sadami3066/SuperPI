import { afterEach, describe, expect, it } from 'vitest'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
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
