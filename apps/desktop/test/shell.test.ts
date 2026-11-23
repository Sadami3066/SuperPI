import { describe, expect, it } from 'vitest'
import { closeAction, restoreWindowBounds } from '../src/main/shell'

const primary = { x: 0, y: 0, width: 1920, height: 1080 }
const secondary = { x: 1920, y: 0, width: 1600, height: 900 }

describe('window close behaviour', () => {
  it('hides to the tray while the app keeps running', () => {
    expect(closeAction(false, true)).toBe('hide')
  })

  it('falls back to minimising when no tray is available', () => {
    expect(closeAction(false, false)).toBe('minimize')
  })

  it('lets the window close during an explicit quit', () => {
    expect(closeAction(true, true)).toBe('close')
    expect(closeAction(true, false)).toBe('close')
  })
})

describe('window bounds restore', () => {
  it('keeps bounds that are already on screen', () => {
    const saved = { x: 100, y: 60, width: 1200, height: 800 }
    expect(restoreWindowBounds(saved, [primary])).toEqual(saved)
  })

  it('restores bounds from a secondary display', () => {
    const saved = { x: 2100, y: 120, width: 1400, height: 800 }
    expect(restoreWindowBounds(saved, [primary, secondary])).toEqual(saved)
  })

  it('centres bounds that no longer touch a display', () => {
    const restored = restoreWindowBounds({ x: 5000, y: 4000, width: 1180, height: 780 }, [primary])
    expect(restored).toEqual({ x: 370, y: 150, width: 1180, height: 780 })
  })

  it('pulls partially off-screen bounds back into view', () => {
    const restored = restoreWindowBounds({ x: -1000, y: -200, width: 1180, height: 780 }, [primary])
    expect(restored.x).toBe(-1000)
    expect(restored.y).toBe(0)
    expect(restored.width).toBe(1180)
    expect(restored.height).toBe(780)
  })

  it('clamps oversized bounds and enforces a minimum size', () => {
    const oversized = restoreWindowBounds({ x: 0, y: 0, width: 4000, height: 3000 }, [primary])
    expect(oversized.width).toBe(1920)
    expect(oversized.height).toBe(1080)
    const tiny = restoreWindowBounds({ x: 10, y: 10, width: 200, height: 100 }, [primary])
    expect(tiny.width).toBe(880)
    expect(tiny.height).toBe(560)
  })

  it('needs a display list to place the default window', () => {
    const restored = restoreWindowBounds(null, [])
    expect(restored).toEqual({ x: 0, y: 0, width: 1180, height: 780 })
    const centred = restoreWindowBounds(null, [primary])
    expect(centred).toEqual({ x: 370, y: 150, width: 1180, height: 780 })
  })
})
