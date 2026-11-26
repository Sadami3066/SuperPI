import fs from "node:fs";
import path from "node:path";
import { screen, type BrowserWindow } from "electron";

export interface WindowBoundsState {
  width: number;
  height: number;
  x?: number;
  y?: number;
  maximized?: boolean;
}

const DEFAULT_WIDTH = 1280;
const DEFAULT_HEIGHT = 820;
const MIN_WIDTH = 960;
const MIN_HEIGHT = 640;

function statePath(homeDir: string): string {
  return path.join(homeDir, "cache", "desktop-window.json");
}

function isRectVisible(x: number, y: number, width: number, height: number): boolean {
  const displays = screen.getAllDisplays();
  return displays.some((d) => {
    const area = d.workArea;
    const overlapX = Math.min(x + width, area.x + area.width) - Math.max(x, area.x);
    const overlapY = Math.min(y + height, area.y + area.height) - Math.max(y, area.y);
    return overlapX >= 120 && overlapY >= 80;
  });
}

export function loadWindowState(homeDir: string): WindowBoundsState {
  try {
    const raw = fs.readFileSync(statePath(homeDir), "utf8");
    const parsed = JSON.parse(raw) as Partial<WindowBoundsState>;
    const width =
      typeof parsed.width === "number" && Number.isFinite(parsed.width)
        ? Math.max(MIN_WIDTH, Math.round(parsed.width))
        : DEFAULT_WIDTH;
    const height =
      typeof parsed.height === "number" && Number.isFinite(parsed.height)
        ? Math.max(MIN_HEIGHT, Math.round(parsed.height))
        : DEFAULT_HEIGHT;
    const hasPos =
      typeof parsed.x === "number" &&
      Number.isFinite(parsed.x) &&
      typeof parsed.y === "number" &&
      Number.isFinite(parsed.y) &&
      isRectVisible(parsed.x, parsed.y, width, height);

    return {
      width,
      height,
      x: hasPos ? Math.round(parsed.x!) : undefined,
      y: hasPos ? Math.round(parsed.y!) : undefined,
      maximized: Boolean(parsed.maximized),
    };
  } catch {
    return { width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT };
  }
}

export function saveWindowState(homeDir: string, win: BrowserWindow): void {
  try {
    const file = statePath(homeDir);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const maximized = win.isMaximized();
    const bounds = maximized ? win.getNormalBounds() : win.getBounds();
    const next: WindowBoundsState = {
      width: Math.max(MIN_WIDTH, bounds.width),
      height: Math.max(MIN_HEIGHT, bounds.height),
      x: bounds.x,
      y: bounds.y,
      maximized,
    };
    fs.writeFileSync(file, JSON.stringify(next), "utf8");
  } catch {
    return;
  }
}
