import { afterEach, describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import { PiRpcClient, type PiRpcEvent } from '../src/main/pi/rpc-client'

const fakePi = fileURLToPath(new URL('./fake-pi.mjs', import.meta.url))
const clients: PiRpcClient[] = []

function createClient(env: NodeJS.ProcessEnv = {}): PiRpcClient {
  const client = new PiRpcClient({
    command: process.execPath,
    args: [fakePi],
    cwd: fileURLToPath(new URL('.', import.meta.url)),
    env: { ...process.env, ...env },
    requestTimeoutMs: 2_000,
  })
  clients.push(client)
  return client
}

afterEach(async () => {
  while (clients.length > 0) {
    const client = clients.pop()
    if (client) await client.stop()
  }
})

describe('PiRpcClient', () => {
  it('starts a child process and answers commands by id', async () => {
    const client = createClient()
    await client.start()
    const state = await client.getState<{ model: string; messageCount: number }>()
    expect(state).toEqual({ model: 'fake/model', messageCount: 0 })
    expect(client.running).toBe(true)
    expect(client.pid).toBeGreaterThan(0)
  })

  it('streams session events in order', async () => {
    const client = createClient()
    await client.start()
    const seen: PiRpcEvent[] = []
    client.onEvent((event) => seen.push(event))
    await client.prompt('hi')
    await waitUntil(() => seen.some((event) => event.type === 'agent_settled'))
    expect(seen.map((event) => event.type)).toEqual(['agent_start', 'message_update', 'agent_end', 'agent_settled'])
    const update = seen[1]?.assistantMessageEvent as { type: string; delta: string }
    expect(update.delta).toBe('hello from fake pi')
  })

  it('reassembles records split across chunks', async () => {
    const client = createClient({ FAKE_PI_SPLIT: '1' })
    await client.start()
    const deltas: string[] = []
    client.onEvent((event) => {
      if (event.type !== 'message_update') return
      const update = event.assistantMessageEvent as { type: string; delta: string }
      deltas.push(update.delta)
    })
    await client.prompt('split')
    await waitUntil(() => deltas.length > 0)
    expect(deltas.join('')).toBe('hello from fake pi')
  })

  it('rejects when pi reports a failed command', async () => {
    const client = createClient()
    await client.start()
    await expect(client.send({ type: 'fail' })).rejects.toThrow('synthetic failure')
  })

  it('times out commands that never answer', async () => {
    const client = createClient()
    await client.start()
    await expect(client.send({ type: 'hang' }, 150)).rejects.toThrow(/timed out/)
  })

  it('notifies exit listeners and rejects pending work', async () => {
    const client = createClient()
    await client.start()
    const exits: Array<Error | null> = []
    client.onExit((error) => exits.push(error))
    const pending = client.send({ type: 'hang' }, 5_000)
    await client.send({ type: 'exit' }).catch(() => undefined)
    await expect(pending).rejects.toThrow(/exited/)
    expect(exits).toHaveLength(1)
    expect(exits[0]).toBeInstanceOf(Error)
    expect(client.running).toBe(false)
  })

  it('writes strictly one JSON record per line', async () => {
    const client = createClient()
    await client.start()
    const writes: string[] = []
    const child = (client as unknown as { child: { stdin: { write: (chunk: string) => void } } }).child
    const originalWrite = child.stdin.write.bind(child.stdin)
    child.stdin.write = (chunk: string) => {
      writes.push(chunk)
      return originalWrite(chunk)
    }
    await client.getState()
    expect(writes[0]?.endsWith('\n')).toBe(true)
    expect(writes[0]?.trim().split('\n')).toHaveLength(1)
  })
})

async function waitUntil(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const started = Date.now()
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error('condition not reached in time')
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
}
