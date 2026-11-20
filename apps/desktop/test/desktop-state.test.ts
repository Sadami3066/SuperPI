import { afterEach, describe, expect, it } from 'vitest'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import {
  defaultDesktopState,
  loadDesktopState,
  normalizeDesktopState,
  rememberSession,
  saveDesktopState,
  type DesktopState,
} from '../src/main/desktop-state'

const dirs: string[] = []

function tempHome(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-state-'))
  dirs.push(dir)
  return dir
}

afterEach(() => {
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

describe('desktop state', () => {
  it('starts empty when nothing was saved', () => {
    expect(loadDesktopState(tempHome())).toEqual(defaultDesktopState())
  })

  it('falls back to defaults when the file is not valid state', () => {
    const home = tempHome()
    writeFileSync(path.join(home, 'desktop.json'), '{ not json', 'utf8')
    expect(loadDesktopState(home)).toEqual(defaultDesktopState())
  })

  it('round-trips window bounds, the last bot and per-bot sessions', () => {
    const home = tempHome()
    const state: DesktopState = {
      window: { bounds: { x: 120, y: 80, width: 1440, height: 900 }, maximized: true },
      lastBotId: 'work',
      lastSession: {
        pi: '/home/me/.pi/agent/sessions/--home-me-.superpi-workspace--/a.jsonl',
        work: '/home/me/.pi/agent/sessions/--home-me-project--/b.jsonl',
      },
    }
    saveDesktopState(state, home)
    expect(loadDesktopState(home)).toEqual(state)
    expect(existsSync(path.join(home, 'desktop.json.tmp'))).toBe(false)
    expect(readFileSync(path.join(home, 'desktop.json'), 'utf8').endsWith('\n')).toBe(true)
  })

  it('drops fields that are not usable instead of failing to load', () => {
    const normalized = normalizeDesktopState({
      window: { bounds: { x: 0, y: 0, width: -10, height: 900 }, maximized: true },
      lastBotId: 42,
      lastSession: { pi: 7, work: '/tmp/session.jsonl', '': '/tmp/empty.jsonl' },
    })
    expect(normalized.window).toBeNull()
    expect(normalized.lastBotId).toBeNull()
    expect(normalized.lastSession).toEqual({ work: '/tmp/session.jsonl' })
  })

  it('remembers a session per bot and forgets it when the view starts over', () => {
    const first = rememberSession(defaultDesktopState(), 'pi', '/tmp/one.jsonl')
    const second = rememberSession(first, 'pi', '/tmp/two.jsonl')
    const third = rememberSession(second, 'pi', null)
    expect(first.lastSession).toEqual({ pi: '/tmp/one.jsonl' })
    expect(second.lastSession).toEqual({ pi: '/tmp/two.jsonl' })
    expect(third.lastSession).toEqual({})
    expect(first.lastSession).toEqual({ pi: '/tmp/one.jsonl' })
  })
})
