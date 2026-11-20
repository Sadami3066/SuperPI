export type Entry =
  | { kind: 'user'; id: string; text: string }
  | { kind: 'assistant'; id: string; text: string }
  | { kind: 'tool'; id: string; name: string; status: 'running' | 'done' | 'error'; detail: string }
  | { kind: 'notice'; id: string; text: string; tone: 'error' | 'info' }

let nextEntryId = 0

export function entryId(): string {
  nextEntryId += 1
  return `e${nextEntryId}`
}

export function entriesFromMessages(messages: unknown[]): Entry[] {
  const entries: Entry[] = []
  for (const message of messages) {
    if (!isRecord(message)) continue
    if (message.role === 'user') {
      const text = textOf(message.content)
      if (text !== '') entries.push({ kind: 'user', id: entryId(), text })
      continue
    }
    if (message.role === 'assistant') {
      for (const block of blocksOf(message.content)) {
        if (block.type === 'text') {
          if (typeof block.text === 'string' && block.text !== '') {
            entries.push({ kind: 'assistant', id: entryId(), text: block.text })
          }
          continue
        }
        if (block.type === 'toolCall') {
          entries.push({
            kind: 'tool',
            id: typeof block.id === 'string' ? block.id : entryId(),
            name: typeof block.name === 'string' && block.name !== '' ? block.name : 'tool',
            status: 'running',
            detail: '',
          })
        }
      }
      continue
    }
    if (message.role === 'toolResult') {
      const callId = typeof message.toolCallId === 'string' ? message.toolCallId : ''
      const status = message.isError === true ? 'error' : 'done'
      const detail = textOf(message.content)
      const index = callId === '' ? -1 : entries.findIndex((entry) => entry.kind === 'tool' && entry.id === callId)
      const existing = index >= 0 ? entries[index] : undefined
      if (existing && existing.kind === 'tool') {
        entries[index] = { ...existing, status, detail }
      } else {
        entries.push({
          kind: 'tool',
          id: callId !== '' ? callId : entryId(),
          name: typeof message.toolName === 'string' && message.toolName !== '' ? message.toolName : 'tool',
          status,
          detail,
        })
      }
    }
  }
  return entries
}

export function appendAssistantDelta(entries: Entry[], delta: string): Entry[] {
  const last = entries[entries.length - 1]
  if (last && last.kind === 'assistant') {
    const updated: Entry = { ...last, text: last.text + delta }
    return [...entries.slice(0, -1), updated]
  }
  return [...entries, { kind: 'assistant', id: entryId(), text: delta }]
}

function textOf(content: unknown): string {
  if (typeof content === 'string') return content
  const parts: string[] = []
  for (const block of blocksOf(content)) {
    if (block.type === 'text' && typeof block.text === 'string') parts.push(block.text)
  }
  return parts.join('\n')
}

function blocksOf(content: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(content)) return []
  return content.filter(isRecord)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
