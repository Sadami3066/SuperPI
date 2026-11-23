import { describe, expect, it } from 'vitest'
import {
  entriesFromMessages,
  finishTool,
  startTool,
  toolArgsDetail,
  toolResultDetail,
  updateTool,
  type Entry,
} from '../src/renderer/entries'

describe('history rendering', () => {
  it('renders user and assistant text', () => {
    const entries = entriesFromMessages([
      { role: 'system', content: '' },
      { role: 'user', content: [{ type: 'text', text: 'Run pwd' }] },
      { role: 'assistant', content: [{ type: 'text', text: 'Here it is' }] },
    ])
    expect(entries.map((entry) => [entry.kind, entry.kind === 'user' || entry.kind === 'assistant' ? entry.text : null])).toEqual([
      ['user', 'Run pwd'],
      ['assistant', 'Here it is'],
    ])
  })

  it('pairs tool calls with their results in order', () => {
    const entries = entriesFromMessages([
      { role: 'user', content: 'Run pwd' },
      {
        role: 'assistant',
        content: [{ type: 'toolCall', id: 'call_1', name: 'bash', arguments: { command: 'pwd' } }],
      },
      {
        role: 'toolResult',
        toolCallId: 'call_1',
        toolName: 'bash',
        isError: false,
        content: [{ type: 'text', text: '/home/me' }],
      },
      { role: 'assistant', content: [{ type: 'text', text: 'You are in /home/me' }] },
    ])
    expect(entries).toEqual([
      { kind: 'user', id: expect.any(String), text: 'Run pwd' },
      { kind: 'tool', id: 'call_1', name: 'bash', status: 'done', detail: '/home/me' },
      { kind: 'assistant', id: expect.any(String), text: 'You are in /home/me' },
    ])
  })

  it('marks failed tool results as errors', () => {
    const entries = entriesFromMessages([
      { role: 'assistant', content: [{ type: 'toolCall', id: 'call_2', name: 'read', arguments: {} }] },
      { role: 'toolResult', toolCallId: 'call_2', toolName: 'read', isError: true, content: [{ type: 'text', text: 'missing' }] },
    ])
    expect(entries).toEqual([{ kind: 'tool', id: 'call_2', name: 'read', status: 'error', detail: 'missing' }])
  })

  it('keeps a tool card when its call is not in the history', () => {
    const entries: Entry[] = entriesFromMessages([
      { role: 'toolResult', toolCallId: 'orphan', toolName: 'bash', isError: false, content: [{ type: 'text', text: 'out' }] },
    ])
    expect(entries).toEqual([{ kind: 'tool', id: 'orphan', name: 'bash', status: 'done', detail: 'out' }])
  })

  it('ignores messages it cannot render', () => {
    expect(entriesFromMessages([null, 42, {}, { role: 'system', content: 'x' }, { role: 'custom' }])).toEqual([])
  })
})

describe('live tool cards', () => {
  it('starts, updates and finishes a tool card', () => {
    let entries = startTool([], 'call_1', 'bash', { command: 'pwd' })
    expect(entries).toEqual([{ kind: 'tool', id: 'call_1', name: 'bash', status: 'running', detail: '{"command":"pwd"}' }])
    entries = updateTool(entries, 'call_1', { detail: '/home/me' })
    expect(entries[0]).toMatchObject({ status: 'running', detail: '/home/me' })
    entries = finishTool(entries, 'call_1', 'bash', 'done', { content: [{ type: 'text', text: '/home/me' }] })
    expect(entries).toEqual([{ kind: 'tool', id: 'call_1', name: 'bash', status: 'done', detail: '/home/me' }])
  })

  it('marks a failed tool result as an error without losing the card', () => {
    const entries = finishTool(startTool([], 'call_2', 'read', {}), 'call_2', 'read', 'error', { content: [{ type: 'text', text: 'no such file' }] })
    expect(entries).toEqual([{ kind: 'tool', id: 'call_2', name: 'read', status: 'error', detail: 'no such file' }])
  })

  it('adds a card for a result that never announced a start', () => {
    const entries = finishTool([], 'call_3', 'bash', 'done', 'plain output')
    expect(entries).toEqual([{ kind: 'tool', id: 'call_3', name: 'bash', status: 'done', detail: 'plain output' }])
  })

  it('reads text out of result payloads and truncates long arguments', () => {
    expect(toolResultDetail({ content: [{ type: 'text', text: 'one' }, { type: 'text', text: 'two' }] })).toBe('one\ntwo')
    expect(toolResultDetail(null)).toBe('')
    expect(toolArgsDetail({ command: 'x'.repeat(400) })).toHaveLength(200)
    expect(toolArgsDetail(null)).toBe('')
  })
})
