import fs from "node:fs";
import path from "node:path";
import type { DesktopCrashRecord } from "../shared/bridge.js";

const MAX_CRASH_RECORDS = 20;

function crashLogPath(homeDir: string): string {
  return path.join(homeDir, "cache", "desktop-crashes.json");
}

export function listDesktopCrashes(homeDir: string): DesktopCrashRecord[] {
  try {
    const raw = fs.readFileSync(crashLogPath(homeDir), "utf8");
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (item): item is DesktopCrashRecord =>
        Boolean(item) &&
        typeof item === "object" &&
        typeof (item as DesktopCrashRecord).id === "string" &&
        typeof (item as DesktopCrashRecord).timestamp === "number" &&
        typeof (item as DesktopCrashRecord).reason === "string",
    );
  } catch {
    return [];
  }
}

export function recordDesktopCrash(
  homeDir: string,
  entry: Omit<DesktopCrashRecord, "id" | "timestamp"> & { timestamp?: number },
): DesktopCrashRecord[] {
  const ts = entry.timestamp ?? Date.now();
  const nextItem: DesktopCrashRecord = {
    id: `${ts}-${Math.random().toString(36).slice(2, 8)}`,
    timestamp: ts,
    processType: entry.processType,
    reason: entry.reason,
    ...(typeof entry.exitCode === "number" ? { exitCode: entry.exitCode } : {}),
  };
  const updated = [nextItem, ...listDesktopCrashes(homeDir)].slice(0, MAX_CRASH_RECORDS);
  try {
    const file = crashLogPath(homeDir);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(updated, null, 2), "utf8");
  } catch {
    return updated;
  }
  return updated;
}

export function clearDesktopCrashes(homeDir: string): void {
  try {
    const file = crashLogPath(homeDir);
    if (fs.existsSync(file)) fs.unlinkSync(file);
  } catch {
    return;
  }
}
