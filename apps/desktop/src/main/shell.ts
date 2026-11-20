import type { WindowBounds } from './desktop-state'

export interface Rectangle {
  x: number
  y: number
  width: number
  height: number
}

export type CloseAction = 'close' | 'hide' | 'minimize'

export const MIN_WINDOW_WIDTH = 880
export const MIN_WINDOW_HEIGHT = 560
const DEFAULT_WINDOW_WIDTH = 1180
const DEFAULT_WINDOW_HEIGHT = 780
const MIN_VISIBLE_WIDTH = 120
const MIN_VISIBLE_HEIGHT = 48

export function closeAction(quitting: boolean, trayAvailable: boolean): CloseAction {
  if (quitting) return 'close'
  return trayAvailable ? 'hide' : 'minimize'
}

export function restoreWindowBounds(saved: WindowBounds | null, workAreas: Rectangle[]): WindowBounds {
  const areas = workAreas.filter((area) => area.width > 0 && area.height > 0)
  const primary = areas[0]
  if (!primary) return { x: 0, y: 0, width: DEFAULT_WINDOW_WIDTH, height: DEFAULT_WINDOW_HEIGHT }
  if (!saved) return centered(primary, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT)
  const area = areas.find((candidate) => isVisible(saved, candidate)) ?? primary
  const width = clampDimension(saved.width, MIN_WINDOW_WIDTH, area.width, DEFAULT_WINDOW_WIDTH)
  const height = clampDimension(saved.height, MIN_WINDOW_HEIGHT, area.height, DEFAULT_WINDOW_HEIGHT)
  if (!isVisible(saved, area)) return centered(area, width, height)
  return {
    width,
    height,
    x: clamp(saved.x, area.x - width + MIN_VISIBLE_WIDTH, area.x + area.width - MIN_VISIBLE_WIDTH),
    y: clamp(saved.y, area.y, area.y + area.height - MIN_VISIBLE_HEIGHT),
  }
}

function centered(area: Rectangle, width: number, height: number): WindowBounds {
  return {
    width,
    height,
    x: Math.round(area.x + (area.width - width) / 2),
    y: Math.round(area.y + (area.height - height) / 2),
  }
}

function isVisible(bounds: WindowBounds, area: Rectangle): boolean {
  const overlapX = Math.min(bounds.x + bounds.width, area.x + area.width) - Math.max(bounds.x, area.x)
  const overlapY = Math.min(bounds.y + bounds.height, area.y + area.height) - Math.max(bounds.y, area.y)
  return overlapX >= MIN_VISIBLE_WIDTH && overlapY >= MIN_VISIBLE_HEIGHT
}

function clampDimension(value: number, minimum: number, available: number, fallback: number): number {
  const finite = Number.isFinite(value) && value > 0 ? value : fallback
  return Math.round(Math.max(320, Math.min(Math.max(finite, minimum), Math.max(available, 320))))
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.round(Math.min(Math.max(value, minimum), Math.max(minimum, maximum)))
}
