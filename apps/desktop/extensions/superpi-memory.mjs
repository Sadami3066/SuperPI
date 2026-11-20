import { existsSync, readFileSync } from 'node:fs'

const TOTAL_BUDGET = 16000
const BUDGETS = {
  profile: 2500,
  context: 2500,
  cookbook: 800,
  today: 2500,
}

const ZONES = {
  profile: 'You & I',
  context: 'Now & Ahead',
  cookbook: 'Lessons & Methods',
}

export default function superpiMemory(pi) {
  pi.on('before_agent_start', (event) => {
    const text = buildMemorySection()
    if (text === '') return
    const existing = event.systemPromptOptions.sections ?? {}
    event.systemPromptOptions.sections = { ...existing, memory: text }
  })

  pi.registerTool({
    name: 'memory_read',
    label: 'Memory read',
    description:
      'Read the shared long-term memory. Pass no path to list files, or a path such as profile.md, context.md, cookbook.md or daily/2026/11/2026-11/2026-11-20.md to read one file.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Memory file path; omit to list files' },
      },
    },
    execute: async (_callId, params) => {
      const files = loadFiles()
      if (!files) return textResult('Shared memory is not available on this device yet.')
      const requested = typeof params?.path === 'string' ? params.path.trim() : ''
      if (requested === '') {
        const listing = Object.keys(files)
          .sort()
          .map((path) => `${path} (${files[path].length} chars)`)
          .join('\n')
        return textResult(listing === '' ? 'Shared memory is empty.' : listing)
      }
      const content = files[requested]
      if (content === undefined) return textResult(`No memory file named ${requested}.`)
      return textResult(stripSourceTags(content))
    },
  })

  pi.registerTool({
    name: 'memory_search',
    label: 'Memory search',
    description: 'Search the shared long-term memory for a phrase and return matching lines with their file.',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Phrase to look for' },
      },
      required: ['query'],
    },
    execute: async (_callId, params) => {
      const files = loadFiles()
      if (!files) return textResult('Shared memory is not available on this device yet.')
      const query = typeof params?.query === 'string' ? params.query.trim() : ''
      if (query === '') return textResult('A query is required.')
      const needle = query.toLowerCase()
      const hits = []
      for (const path of Object.keys(files).sort()) {
        const lines = stripSourceTags(files[path]).split('\n')
        for (let index = 0; index < lines.length; index += 1) {
          const line = lines[index].trim()
          if (line === '' || !line.toLowerCase().includes(needle)) continue
          hits.push(`${path}:${index + 1}: ${line}`)
          if (hits.length >= 20) break
        }
        if (hits.length >= 20) break
      }
      return textResult(hits.length === 0 ? `No memory matches "${query}".` : hits.join('\n'))
    },
  })

  pi.registerTool({
    name: 'memory_write',
    label: 'Memory write',
    description:
      'Append a durable fact to the shared memory. Use profile.md for the user, context.md for current work, cookbook.md for reusable methods, or a daily/<year>/<month>/<year-month>/<date>.md path.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Memory file path, for example profile.md' },
        text: { type: 'string', description: 'What to remember, written in the user language' },
        section: { type: 'string', description: 'Optional section heading to append under' },
      },
      required: ['path', 'text'],
    },
    execute: async (_callId, params) => {
      const endpoint = process.env.SUPERPI_RELAY_ENDPOINT
      const token = process.env.SUPERPI_RELAY_TOKEN
      if (!endpoint || !token) return textResult('This device is not paired with a relay, so memory writes are unavailable.', true)
      const body = {
        op: 'append',
        path: typeof params?.path === 'string' ? params.path : '',
        text: typeof params?.text === 'string' ? params.text : '',
        idempotencyKey: `desktop-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      }
      if (typeof params?.section === 'string' && params.section.trim() !== '') body.section = params.section.trim()
      try {
        const response = await fetch(`${endpoint}/v1/memory/write`, {
          method: 'POST',
          headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
          body: JSON.stringify(body),
        })
        if (response.status !== 200) {
          const detail = await response.text()
          return textResult(`Memory write failed (${response.status}): ${detail.slice(0, 300)}`, true)
        }
        const result = await response.json()
        return textResult(`Saved to ${body.path} (memory version ${result.version}).`)
      } catch (error) {
        return textResult(`Memory write failed: ${error instanceof Error ? error.message : String(error)}`, true)
      }
    },
  })
}

function textResult(text, isError = false) {
  const result = { content: [{ type: 'text', text }], details: undefined }
  if (isError) result.isError = true
  return result
}

function snapshotPath() {
  return process.env.SUPERPI_MEMORY_FILE ?? ''
}

function loadFiles() {
  const file = snapshotPath()
  if (file === '' || !existsSync(file)) return null
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8'))
    if (typeof parsed?.files !== 'object' || parsed.files === null) return null
    return parsed.files
  } catch {
    return null
  }
}

function buildMemorySection() {
  const files = loadFiles()
  if (!files) return ''
  const parts = []
  for (const [key, budget] of Object.entries(BUDGETS)) {
    const path = `${key}.md`
    const content = files[path]
    if (typeof content !== 'string') continue
    const body = truncate(stripSourceTags(content).trim(), budget)
    if (body === '') continue
    parts.push(`## ${ZONES[key]}\n\n${body}`)
  }
  const dailyPath = latestDailyPath(files)
  if (dailyPath) {
    const body = truncate(stripSourceTags(files[dailyPath]).trim(), BUDGETS.today)
    if (body !== '') parts.push(`## Diary\n\n${body}`)
  }
  if (parts.length === 0) return ''
  const text = truncate(parts.join('\n\n'), TOTAL_BUDGET)
  return `Shared long-term memory, synced across this account's devices. Remember things with memory_write.\n\n${text}`
}

function latestDailyPath(files) {
  let latest = null
  for (const path of Object.keys(files)) {
    if (!path.startsWith('daily/') || !path.endsWith('.md')) continue
    if (latest === null || path > latest) latest = path
  }
  return latest
}

const SOURCE_TAG = /<!--\s*(?:src:|updated by )[^>]*-->/g

function stripSourceTags(text) {
  return text
    .split('\n')
    .map((line) => ({ original: line, stripped: line.replace(SOURCE_TAG, '').replace(/[ \t]+$/, '') }))
    .filter((line) => line.stripped.trim() !== '' || line.original.trim() === '')
    .map((line) => line.stripped)
    .join('\n')
}

function truncate(text, budget) {
  if (text.length <= budget) return text
  return `${text.slice(0, budget)}\n...(truncated)`
}
