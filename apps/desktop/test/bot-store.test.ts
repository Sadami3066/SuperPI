import { afterEach, describe, expect, it } from 'vitest'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { BotStore, DEFAULT_BOT_ID } from '../src/main/bot-store'

const dirs: string[] = []

function tempHome(): string {
  const dir = mkdtempSync(path.join(tmpdir(), 'superpi-bots-'))
  dirs.push(dir)
  return dir
}

afterEach(() => {
  while (dirs.length > 0) {
    const dir = dirs.pop()
    if (dir) rmSync(dir, { recursive: true, force: true })
  }
})

describe('bot store', () => {
  it('loads old records and keeps only the desktop fields', () => {
    const home = tempHome()
    writeFileSync(
      path.join(home, 'bots.json'),
      JSON.stringify({
        version: 1,
        bots: [
          {
            id: 'pi',
            name: 'PI',
            cwd: path.join(home, 'workspace'),
            isHome: true,
            avatar: 'avatars/pi.jpg',
            intro: 'the default bot',
            unknownField: 7,
          },
          { id: 'work', name: 'Work', cwd: '/tmp/work', isHome: false, avatar: null, intro: '' },
        ],
      }),
      'utf8',
    )
    const bots = new BotStore(home).list()
    expect(bots).toEqual([
      { id: 'pi', name: 'PI', cwd: path.join(home, 'workspace') },
      { id: 'work', name: 'Work', cwd: '/tmp/work' },
    ])
  })

  it('creates the default bot when the list is missing or unusable', () => {
    const home = tempHome()
    const store = new BotStore(home)
    expect(store.list()).toEqual([{ id: DEFAULT_BOT_ID, name: 'PI', cwd: path.join(home, 'workspace') }])
    expect(existsSync(path.join(home, 'workspace'))).toBe(true)

    writeFileSync(path.join(home, 'bots.json'), JSON.stringify({ version: 1, bots: [{ id: 'no-cwd' }] }), 'utf8')
    expect(store.list()[0]?.id).toBe(DEFAULT_BOT_ID)

    writeFileSync(path.join(home, 'bots.json'), 'not json', 'utf8')
    expect(store.list()[0]?.id).toBe(DEFAULT_BOT_ID)
  })

  it('always keeps a usable default bot', () => {
    const home = tempHome()
    const store = new BotStore(home)
    store.add({ id: 'work', name: 'Work', cwd: '/tmp/work' })
    expect(store.remove('work').map((bot) => bot.id)).toEqual([DEFAULT_BOT_ID])
    expect(store.remove(DEFAULT_BOT_ID).map((bot) => bot.id)).toEqual([DEFAULT_BOT_ID])
    expect(store.save([]).map((bot) => bot.id)).toEqual([DEFAULT_BOT_ID])
    expect(JSON.parse(readFileSync(path.join(home, 'bots.json'), 'utf8')).bots).toEqual([
      { id: DEFAULT_BOT_ID, name: 'PI', cwd: path.join(home, 'workspace') },
    ])
  })

  it('resolves cwd and drops duplicate or invalid records on save', () => {
    const home = tempHome()
    const store = new BotStore(home)
    const saved = store.save([
      { id: 'work', name: 'Work', cwd: 'relative/dir' },
      { id: 'work', name: 'Work again', cwd: '/tmp/other' },
      { id: '', name: 'Broken', cwd: '/tmp/broken' },
    ])
    expect(saved).toEqual([{ id: 'work', name: 'Work', cwd: path.resolve('relative/dir') }])
  })
})
