import type { ActivityStep, ToolTraceItem } from "../../api/types";

export type ToolIconKind =
  | "book"
  | "pen"
  | "terminal"
  | "search"
  | "brain"
  | "apps"
  | "clock"
  | "file"
  | "folder"
  | "target"
  | "check"
  | "dot";

export function toolIconKind(name: string): ToolIconKind {
  if (name === "read") return "book";
  if (name === "write" || name === "edit") return "pen";
  if (name === "bash" || name === "system_shell") return "terminal";
  if (name === "web_search" || name === "web_fetch") return "search";
  if (name.startsWith("memory_")) return "brain";
  if (name === "miniapp_data" || name.startsWith("miniapp_") || name === "worker") return "apps";
  if (name === "job" || name.startsWith("job_") || name === "timer" || name.startsWith("timer_")) {
    return "clock";
  }
  if (name === "ls" || name === "find") return "folder";
  if (name === "grep") return "file";
  if (name === "todo_write") return "check";
  if (name === "goal" || name.endsWith("_goal")) return "target";
  return "dot";
}

const ANDROID_PRIVATE_FILES_PREFIX = new RegExp("/data/(?:user/\\d+|data)/[^/\\s]+/files/", "g");
const ANDROID_SHARED_STORAGE_PREFIX = new RegExp("/storage/emulated/\\d+/?", "g");

export function sanitizeToolPath(raw: string): string {
  return raw
    .replace(ANDROID_PRIVATE_FILES_PREFIX, "~/")
    .replace(ANDROID_SHARED_STORAGE_PREFIX, (match) => (match.endsWith("/") ? "Storage/" : "Storage"));
}

export function toolDescriptionArg(rawArgs: Record<string, string>): string | null {
  const raw = rawArgs.description?.trim().replace(/\s+/g, " ");
  return raw ? raw : null;
}

const PREFERRED_ARG_KEYS = [
  "command",
  "cmd",
  "path",
  "file_path",
  "pattern",
  "query",
  "url",
  "prompt",
];

export function toolArgsPreview(rawArgs: Record<string, string>): string {
  for (const key of PREFERRED_ARG_KEYS) {
    const val = rawArgs[key]?.trim();
    if (val && val !== "null" && val !== "\"\"") {
      return sanitizeToolPath(val.replace(/\r?\n/g, " ").trim());
    }
  }
  for (const [key, val] of Object.entries(rawArgs)) {
    if (key === "description") continue;
    const trimmed = val?.trim();
    if (trimmed && trimmed !== "null" && trimmed !== "\"\"") {
      return sanitizeToolPath(trimmed.replace(/\r?\n/g, " ").trim());
    }
  }
  return "";
}

export function toolProgressLabel(name: string, rawArgs: Record<string, string>): string {
  const desc = toolDescriptionArg(rawArgs);
  if (desc) return desc;
  const preview = toolArgsPreview(rawArgs);
  return preview ? `${name} ${preview}` : name;
}

export function firstErrorLine(output: string): string {
  const line = output
    .split(/\r?\n/)
    .map((l) => l.trim())
    .find(Boolean);
  return line ? sanitizeToolPath(line) : "";
}

const THINKING_PREFIX_REGEX = /^(?:\s*(?:#{1,6}\s+|>\s*|[-*+]\s+(?:\[[ xX]\]\s+)?|\d+[.)]\s+))+/;
const THINKING_INLINE_MARKER_REGEX = /(\*\*|__|~~|`)/g;
const THINKING_SYMBOL_ONLY_REGEX = /^[-*_~=>#\s.·•]+$/;

export function cleanThinkingLine(line: string): string | null {
  const withoutPrefix = line.trim().replace(THINKING_PREFIX_REGEX, "");
  const withoutInline = withoutPrefix
    .replace(THINKING_INLINE_MARKER_REGEX, "")
    .replace(/\s+/g, " ")
    .trim();
  if (!withoutInline || THINKING_SYMBOL_ONLY_REGEX.test(withoutInline)) {
    return null;
  }
  return withoutInline;
}

export function reasoningPreview(raw: string): string {
  const lines = raw.split(/\r?\n/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const cleaned = cleanThinkingLine(lines[i]);
    if (cleaned) return cleaned;
  }
  return "";
}

export function splitThinkingSegments(text: string, maxUnits = 52): string[] {
  if (!text.trim()) return [];
  if (maxUnits <= 0) {
    const trimmed = text.trim();
    return trimmed ? [trimmed] : [];
  }
  const segments: string[] = [];
  let start = 0;
  while (start < text.length && /\s/.test(text[start])) start++;

  while (start < text.length) {
    let units = 0;
    let end = start;
    let lastBreak = -1;
    while (end < text.length) {
      const ch = text[end];
      const charUnits = ch.charCodeAt(0) < 0x80 ? 1 : 2;
      if (end > start && units + charUnits > maxUnits) break;
      units += charUnits;
      end++;
      if (end < text.length && units * 2 >= maxUnits && (/[\s，。、；：！？）》】]/.test(ch))) {
        lastBreak = end;
      }
    }
    const splitEnd = end < text.length && lastBreak > start ? lastBreak : end;
    const seg = text.slice(start, splitEnd).trim();
    if (seg) segments.push(seg);
    start = splitEnd;
    while (start < text.length && /\s/.test(text[start])) start++;
  }
  return segments;
}

export interface LiveThinkingLine {
  slotKey: string;
  text: string;
}

export function extractLiveThinkingLine(raw: string, maxUnits = 52): LiveThinkingLine | null {
  if (!raw.trim()) return null;
  const lines = raw.split(/\r?\n/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const cleaned = cleanThinkingLine(lines[i]);
    if (!cleaned) continue;
    const segments = splitThinkingSegments(cleaned, maxUnits);
    if (segments.length === 0) continue;
    const segIdx = segments.length - 1;
    return {
      slotKey: `${i}:${segIdx}`,
      text: segments[segIdx],
    };
  }
  return null;
}

export type ProcessNode =
  | { kind: "single"; id: string; step: ActivityStep }
  | { kind: "tool_run"; id: string; tools: ToolTraceItem[] };

const TOOL_GROUP_MIN_COUNT = 3;

export function groupProcessSteps(steps: ActivityStep[]): ProcessNode[] {
  const nodes: ProcessNode[] = [];
  let index = 0;
  while (index < steps.length) {
    const step = steps[index];
    if (step.kind === "tool") {
      let end = index;
      const tools: ToolTraceItem[] = [];
      while (end < steps.length && steps[end].kind === "tool") {
        const s = steps[end];
        if (s.kind === "tool") tools.push(s.tool);
        end++;
      }
      if (tools.length >= TOOL_GROUP_MIN_COUNT) {
        nodes.push({ kind: "tool_run", id: `tg-${tools[0].id}`, tools });
      } else {
        for (let k = index; k < end; k++) {
          const item = steps[k];
          nodes.push({ kind: "single", id: item.id, step: item });
        }
      }
      index = end;
    } else {
      nodes.push({ kind: "single", id: step.id, step });
      index++;
    }
  }
  return nodes;
}

export function formatElapsed(durationMs: number, locale: "zh-CN" | "en"): string {
  const totalSeconds = Math.max(1, Math.floor(durationMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (locale === "en") {
    if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
    if (minutes > 0) return `${minutes}m ${seconds}s`;
    return `${seconds}s`;
  }
  if (hours > 0) return `${hours} 小时 ${minutes} 分 ${seconds} 秒`;
  if (minutes > 0) return `${minutes} 分 ${seconds} 秒`;
  return `${seconds} 秒`;
}
