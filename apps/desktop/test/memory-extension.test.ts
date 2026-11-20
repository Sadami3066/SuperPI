import { afterEach, describe, expect, it } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'

const dirs: string[] = []
const envKeys = ['SUPERPI_MEMORY_FILE', 'SUPERPI_RELAY_ENDPOINT', 'SUPERPI_RELAY_TOKEN'] as const
const savedEnv = new Map<string, string | undefined>()

function rememberEnv(): void {
  for (const key of envKeys) savedEnv.set(key, process.env[key])
}

afterEach(() => {
  for (const key of envKeys) {
    const value = savedEnv.get(key)
    if (value === undefined) delete process.env[key]
    else process.env[key] = value
  }
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

const snapshot = {
  version: 3,
  syncedAt: 1795000000000,
  files: {
    'profile.md': '## You & I\n\n- Likes dark mode <!-- src: My phone 2026-11-20T10:00:00.000Z -->',
    'context.md': '## Now & Ahead\n\n- Working on memory sync',
    'cookbook.md': '## Lessons & Methods\n\n- Use idempotency keys',
    'daily/2026/11/2026-11/2026-11-20.md': '## Diary\n\n- Shipped shared memory',
  },
}

interface FakeTool {
  name: string
  execute: (...args: unknown[]) => Promise<{ content: Array<{ text: string }>; isError?: boolean }>
}

async function loadExtension(): Promise<{
  handlers: Map<string, (event: Record<string, unknown>) => void>
  tools: Map<string, FakeTool>
}> {
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-memory-ext-'))
  dirs.push(dir)
  const file = path.join(dir, 'snapshot.json')
  writeFileSync(file, JSON.stringify(snapshot), 'utf8')
  rememberEnv()
  process.env.SUPERPI_MEMORY_FILE = file
  delete process.env.SUPERPI_RELAY_ENDPOINT
  delete process.env.SUPERPI_RELAY_TOKEN

  const url = new URL('../extensions/superpi-memory.mjs', import.meta.url).href
  const module = (await import(url)) as { default: (pi: unknown) => void }
  const handlers = new Map<string, (event: Record<string, unknown>) => void>()
  const tools = new Map<string, FakeTool>()
  module.default({
    on: (name: string, handler: (event: Record<string, unknown>) => void) => handlers.set(name, handler),
    registerTool: (tool: FakeTool) => tools.set(tool.name, tool),
  })
  return { handlers, tools }
}

function textOf(result: { content: Array<{ text: string }> }): string {
  return result.content.map((item) => item.text).join('\n')
}

describe('superpi memory extension', () => {
  it('injects memory sections without source tags', async () => {
    const { handlers } = await loadExtension()
    const event: { systemPromptOptions: { sections: Record<string, string> } } = { systemPromptOptions: { sections: {} } }
    handlers.get('before_agent_start')?.(event as unknown as Record<string, unknown>)
    const memory = event.systemPromptOptions.sections.memory
    expect(memory).toContain('Likes dark mode')
    expect(memory).toContain('Working on memory sync')
    expect(memory).toContain('Use idempotency keys')
    expect(memory).toContain('Shipped shared memory')
    expect(memory).not.toContain('<!-- src:')
  })

  it('lists, reads and searches memory through tools', async () => {
    const { tools } = await loadExtension()
    const read = tools.get('memory_read')
    const search = tools.get('memory_search')
    expect(read).toBeDefined()
    expect(search).toBeDefined()

    const listing = await read?.execute('c1', {}, undefined, undefined, undefined)
    expect(textOf(listing as { content: Array<{ text: string }> })).toContain('profile.md')

    const profile = await read?.execute('c2', { path: 'profile.md' }, undefined, undefined, undefined)
    expect(textOf(profile as { content: Array<{ text: string }> })).toContain('Likes dark mode')

    const hits = await search?.execute('c3', { query: 'dark' }, undefined, undefined, undefined)
    expect(textOf(hits as { content: Array<{ text: string }> })).toContain('profile.md:3')
  })

  it('reports memory writes as unavailable without a relay', async () => {
    const { tools } = await loadExtension()
    const write = tools.get('memory_write')
    const result = await write?.execute(
      'c4',
      { path: 'profile.md', text: 'Something new' },
      undefined,
      undefined,
      undefined,
    )
    expect((result as { isError?: boolean }).isError).toBe(true)
    expect(textOf(result as { content: Array<{ text: string }> })).toContain('not paired')
  })
})
