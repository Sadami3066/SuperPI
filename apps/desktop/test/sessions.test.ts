import { afterEach, describe, expect, it } from 'vitest'
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { listSessions, resumableSession, resolveSessionLocation } from '../src/main/pi/sessions'

const dirs: string[] = []

function tempDir(prefix: string): string {
  const dir = mkdtempSync(path.join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}

afterEach(() => {
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

function encodedDirectory(cwd: string): string {
  return `--${path.resolve(cwd).replace(/^[/\\]/, '').replace(/[/\\:]/g, '-')}--`
}

interface SessionFixture {
  cwd: string
  id?: string
  name?: string
  users?: Array<{ text: string; timestamp: number }>
  assistants?: Array<{ text: string; timestamp: number }>
}

function sessionFile(home: string, fixture: SessionFixture): string {
  const agentDir = path.join(home, '.pi', 'agent')
  const directory = path.join(agentDir, 'sessions', encodedDirectory(fixture.cwd))
  mkdirSync(directory, { recursive: true })
  const file = path.join(directory, `${String(fixture.users?.[0]?.timestamp ?? 0).padStart(16, '0')}_${fixture.id ?? 'session'}.jsonl`)
  const lines: unknown[] = [
    { type: 'session', version: 3, id: fixture.id ?? 'session', timestamp: new Date(fixture.users?.[0]?.timestamp ?? 0).toISOString(), cwd: fixture.cwd },
  ]
  const users = fixture.users ?? []
  const assistants = fixture.assistants ?? []
  for (let index = 0; index < Math.max(users.length, assistants.length); index += 1) {
    const user = users[index]
    if (user) lines.push({ type: 'message', id: `u${index}`, parentId: null, timestamp: new Date(user.timestamp).toISOString(), message: { role: 'user', content: [{ type: 'text', text: user.text }], timestamp: user.timestamp } })
    const assistant = assistants[index]
    if (assistant) lines.push({ type: 'message', id: `a${index}`, parentId: null, timestamp: new Date(assistant.timestamp).toISOString(), message: { role: 'assistant', content: [{ type: 'text', text: assistant.text }], timestamp: assistant.timestamp } })
  }
  if (fixture.name !== undefined) {
    lines.push({ type: 'session_info', id: 'name', parentId: null, timestamp: new Date().toISOString(), name: fixture.name })
  }
  writeFileSync(file, `${lines.map((line) => JSON.stringify(line)).join('\n')}\n`, 'utf8')
  return file
}

describe('session location', () => {
  it('encodes the working directory the way pi does', () => {
    const home = tempDir('superpi-home-')
    const cwd = path.join(home, '.superpi', 'workspace')
    const location = resolveSessionLocation(cwd, {}, home)
    expect(location.directory).toBe(path.join(home, '.pi', 'agent', 'sessions', encodedDirectory(cwd)))
    const encoded = path.basename(location.directory)
    expect(encoded.startsWith('--')).toBe(true)
    expect(encoded.endsWith('--')).toBe(true)
    expect(encoded).not.toMatch(/[/\\:]/)
  })

  it('honours the agent directory and session directory environment', () => {
    const home = tempDir('superpi-home-')
    const agentDir = path.join(home, 'custom-agent')
    expect(resolveSessionLocation('/work/one', { PI_CODING_AGENT_DIR: agentDir }, home).directory).toBe(
      path.join(agentDir, 'sessions', '--work-one--'),
    )
    expect(
      resolveSessionLocation('/work/one', { PI_CODING_AGENT_SESSION_DIR: path.join(home, 'flat-sessions') }, home).directory,
    ).toBe(path.join(home, 'flat-sessions'))
  })

  it('honours the sessionDir setting from project and agent settings', () => {
    const home = tempDir('superpi-home-')
    const cwd = path.join(home, 'project')
    mkdirSync(path.join(cwd, '.pi'), { recursive: true })
    writeFileSync(path.join(cwd, '.pi', 'settings.json'), JSON.stringify({ sessionDir: 'project-sessions' }), 'utf8')
    expect(resolveSessionLocation(cwd, {}, home).directory).toBe(path.join(cwd, 'project-sessions'))
    mkdirSync(path.join(home, '.pi', 'agent'), { recursive: true })
    writeFileSync(path.join(home, '.pi', 'agent', 'settings.json'), JSON.stringify({ sessionDir: '~/agent-sessions' }), 'utf8')
    expect(resolveSessionLocation(path.join(home, 'other'), {}, home).directory).toBe(path.join(home, 'agent-sessions'))
  })
})

describe('session listing', () => {
  it('returns nothing when the bot has never run pi', () => {
    const home = tempDir('superpi-home-')
    expect(listSessions(path.join(home, 'missing'), {}, home)).toEqual([])
  })

  it('lists sessions of the bot cwd newest first with names and titles', () => {
    const home = tempDir('superpi-home-')
    const cwd = path.join(home, 'work')
    const older = sessionFile(home, {
      cwd,
      id: 'older',
      users: [{ text: 'Fix the login bug', timestamp: 1_000_000 }],
      assistants: [{ text: 'On it', timestamp: 1_001_000 }],
    })
    const newer = sessionFile(home, {
      cwd,
      id: 'newer',
      name: 'Release prep',
      users: [{ text: 'Ship the release   on Friday', timestamp: 2_000_000 }],
    })
    const future = new Date('2026-11-20T12:00:00.000Z')
    utimesSync(older, future, future)

    const sessions = listSessions(cwd, {}, home)
    expect(sessions.map((session) => session.id)).toEqual(['newer', 'older'])
    expect(sessions[0]).toMatchObject({ path: newer, name: 'Release prep', title: 'Release prep', messageCount: 1, modified: 2_000_000 })
    expect(sessions[1]).toMatchObject({ path: older, name: null, title: 'Fix the login bug', messageCount: 2, modified: 1_001_000 })
  })

  it('skips other projects, empty sessions and broken files', () => {
    const home = tempDir('superpi-home-')
    const cwd = path.join(home, 'work')
    sessionFile(home, { cwd: path.join(home, 'other'), id: 'foreign', users: [{ text: 'elsewhere', timestamp: 10 }] })
    const empty = sessionFile(home, { cwd, id: 'empty' })
    const broken = path.join(path.dirname(empty), 'broken.jsonl')
    writeFileSync(broken, 'not json\n{"type":"session","id":"broken"}\n', 'utf8')
    const valid = sessionFile(home, { cwd, id: 'valid', users: [{ text: 'real work', timestamp: 20 }] })
    const sessions = listSessions(cwd, {}, home)
    expect(sessions.map((session) => session.id)).toEqual(['valid'])
    expect(sessions[0]?.path).toBe(valid)
  })

  it('lists flat sessions from a configured session directory', () => {
    const home = tempDir('superpi-home-')
    const cwd = path.join(home, 'work')
    const flat = path.join(home, 'flat-sessions')
    mkdirSync(flat, { recursive: true })
    const file = path.join(flat, '2026-11-20T00-00-00-000Z_flat.jsonl')
    writeFileSync(
      file,
      `${JSON.stringify({ type: 'session', version: 3, id: 'flat', timestamp: new Date(50).toISOString(), cwd })}\n${JSON.stringify({ type: 'message', message: { role: 'user', content: 'flat session', timestamp: 50 } })}\n`,
      'utf8',
    )
    const sessions = listSessions(cwd, { PI_CODING_AGENT_SESSION_DIR: flat }, home)
    expect(sessions.map((session) => session.title)).toEqual(['flat session'])
  })
})

describe('session resume', () => {
  it('only resumes an absolute jsonl file that still exists', () => {
    const file = '/home/me/.pi/agent/sessions/--work--/a.jsonl'
    expect(resumableSession(file, () => true)).toBe(file)
    expect(resumableSession(file, () => false)).toBeNull()
    expect(resumableSession('sessions/a.jsonl', () => true)).toBeNull()
    expect(resumableSession('/tmp/a.txt', () => true)).toBeNull()
    expect(resumableSession(null, () => true)).toBeNull()
  })
})
