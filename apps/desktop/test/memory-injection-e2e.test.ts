import { afterEach, describe, expect, it } from 'vitest'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { createServer, type Server } from 'node:http'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

const piAvailable = spawnSync('pi', ['--version'], { encoding: 'utf8' }).status === 0
const children: ChildProcess[] = []
const servers: Server[] = []
const dirs: string[] = []

afterEach(async () => {
  while (children.length > 0) {
    const child = children.pop()
    if (child && child.exitCode === null) {
      child.kill('SIGTERM')
      await new Promise((resolve) => setTimeout(resolve, 150))
    }
  }
  while (servers.length > 0) {
    const server = servers.pop()
    if (server) await new Promise<void>((resolve) => server.close(() => resolve()))
  }
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

function tempDir(prefix: string): string {
  const dir = mkdtempSync(path.join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}

interface CapturedRequest {
  messages?: Array<{ role: string; content: unknown }>
  tools?: Array<{ type?: string; function?: { name?: string }; name?: string }>
}

async function startMockProvider(): Promise<{ port: number; requests: CapturedRequest[] }> {
  const requests: CapturedRequest[] = []
  const server = createServer((request, response) => {
    let body = ''
    request.on('data', (chunk) => (body += chunk))
    request.on('end', () => {
      let parsed: CapturedRequest & { stream?: boolean } = {}
      try {
        parsed = JSON.parse(body)
        requests.push(parsed)
      } catch {
        parsed = {}
      }
      if (parsed.stream === true) {
        response.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
        const chunk = (delta: unknown, finish: string | null): string =>
          `data: ${JSON.stringify({
            id: 'mock',
            object: 'chat.completion.chunk',
            created: 0,
            model: 'mock-model',
            choices: [{ index: 0, delta, finish_reason: finish }],
          })}\n\n`
        response.write(chunk({ role: 'assistant', content: 'ok' }, null))
        response.write(chunk({}, 'stop'))
        response.write('data: [DONE]\n\n')
        response.end()
        return
      }
      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(
        JSON.stringify({
          id: 'mock',
          object: 'chat.completion',
          created: 0,
          model: 'mock-model',
          choices: [{ index: 0, message: { role: 'assistant', content: 'ok' }, finish_reason: 'stop' }],
          usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
        }),
      )
    })
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
  servers.push(server)
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('mock provider did not bind a port')
  return { port: address.port, requests }
}

describe.skipIf(!piAvailable)('memory injection against a mock provider', () => {
  it('puts shared memory into the system prompt of a real agent turn', async () => {
    const mock = await startMockProvider()
    const agentDir = tempDir('superpi-agent-')
    mkdirSync(agentDir, { recursive: true })
    writeFileSync(
      path.join(agentDir, 'models.json'),
      JSON.stringify({
        providers: {
          mock: {
            baseUrl: `http://127.0.0.1:${mock.port}/v1`,
            apiKey: 'mock-key',
            api: 'openai-completions',
            models: [{ id: 'mock-model', name: 'Mock', contextWindow: 128000, maxTokens: 4096 }],
          },
        },
      }),
    )
    const cwd = tempDir('superpi-cwd-')
    const snapshot = path.join(cwd, 'snapshot.json')
    writeFileSync(
      snapshot,
      JSON.stringify({
        version: 1,
        files: { 'profile.md': '- Likes green tea <!-- src: My phone 2026-11-20T00:00:00.000Z -->' },
      }),
    )
    const extension = new URL('../extensions/superpi-memory.mjs', import.meta.url).pathname

    const child = spawn(
      'pi',
      ['--mode', 'rpc', '--no-session', '-e', extension, '--provider', 'mock', '--model', 'mock-model'],
      {
        cwd,
        env: { ...process.env, PI_CODING_AGENT_DIR: agentDir, SUPERPI_MEMORY_FILE: snapshot },
        stdio: ['pipe', 'pipe', 'pipe'],
      },
    )
    children.push(child)
    let output = ''
    child.stdout?.setEncoding('utf8')
    child.stdout?.on('data', (chunk: string) => {
      output += chunk
    })
    child.stderr?.setEncoding('utf8')
    child.stderr?.on('data', (chunk: string) => {
      output += `[stderr] ${chunk}`
    })

    await new Promise((resolve) => setTimeout(resolve, 2_500))
    child.stdin?.write(`${JSON.stringify({ id: 'p1', type: 'prompt', message: 'hello there' })}\n`)
    await waitFor(() => output.includes('"agent_settled"'), 30_000)

    const capture = mock.requests.find((request) => JSON.stringify(request).includes('hello there'))
    expect(capture).toBeDefined()
    const system = (capture?.messages ?? [])
      .filter((message) => message.role === 'system')
      .map((message) => (typeof message.content === 'string' ? message.content : JSON.stringify(message.content)))
      .join('\n')
    expect(system).toContain('green tea')
    expect(system).not.toContain('<!-- src:')
    const toolNames = (capture?.tools ?? []).map((tool) => tool.function?.name ?? tool.name ?? '')
    expect(toolNames).toEqual(expect.arrayContaining(['memory_read', 'memory_search', 'memory_write']))
  }, 45_000)
})

async function waitFor(predicate: () => boolean, timeoutMs: number): Promise<void> {
  const started = Date.now()
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) throw new Error('condition not reached in time')
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
}
