import type { DesktopNavigateCommand } from "../shared/bridge.js";

const FEATURE_HOSTS = new Set(["timers", "inbox", "memory", "skills", "mcp"]);

export function parseDeepLinkUrl(rawUrl: string): DesktopNavigateCommand | null {
  const trimmed = rawUrl.trim();
  if (!trimmed) return null;
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }
  if (parsed.protocol !== "superpi:") return null;

  const host = parsed.hostname.toLowerCase();
  if (!host || host === "app") return null;

  const segments = parsed.pathname
    .split("/")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      try {
        return decodeURIComponent(s);
      } catch {
        return s;
      }
    });

  if (host === "chat") {
    const sessionId = segments[0];
    if (!sessionId) return null;
    return { kind: "chat", sessionId };
  }

  if (host === "super") {
    return { kind: "super" };
  }

  if (host === "new-chat" || host === "new") {
    return { kind: "new_chat" };
  }

  if (host === "settings") {
    const tab = segments[0] || "general";
    const autoCheckUpdate =
      tab === "diagnostics" && parsed.searchParams.get("checkUpdate") === "1";
    return {
      kind: "settings",
      tab,
      ...(autoCheckUpdate ? { autoCheckUpdate: true } : {}),
    };
  }

  if (FEATURE_HOSTS.has(host)) {
    return {
      kind: "feature",
      view: host as "timers" | "inbox" | "memory" | "skills" | "mcp",
    };
  }

  return null;
}

export function extractDeepLinkFromArgv(argv: readonly string[]): string | null {
  for (const arg of argv) {
    if (typeof arg !== "string") continue;
    const candidate = arg.trim();
    if (!candidate.toLowerCase().startsWith("superpi://")) continue;
    if (parseDeepLinkUrl(candidate)) return candidate;
  }
  return null;
}
