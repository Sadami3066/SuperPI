import { useEffect, useMemo, useRef, useState } from "react";
import {
  IconLayoutGrid,
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconLoader2,
  IconPinnedOff,
  IconPlugConnected,
} from "@tabler/icons-react";
import { formatRelativeTime } from "../../api/mappers";
import type { HostBotDto, HostWorkerDto, SessionSummary } from "../../api/types";
import { useI18n } from "../../i18n";
import type { FeatureViewKind } from "./FeatureWorkspaceView";
import { revealInFileManager, windowToggleMaximize } from "../../platform/bridge";
import { formatShortcut } from "../../platform/shortcuts";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import {
  ConfirmDialog,
  ContextMenu,
  ContextMenuItem,
  ContextMenuSeparator,
  DropdownItem,
  DropdownMenu,
  DropdownSeparator,
  IconButton,
  Input,
} from "../../ui";
import { BotAvatar, BrandIcon, DevicePlatformIcon, PiBrandMark } from "../shared/BrandAssets";
import { ModeSwitcher } from "./ModeSwitcher";

const SIDEBAR_WIDTH_KEY = "superpi:sidebarWidth";
const SIDEBAR_TOOLS_OPEN_KEY = "superpi:sidebarToolsOpen";
const DEFAULT_SIDEBAR_WIDTH = 256;
const MIN_SIDEBAR_WIDTH = 210;
const MAX_SIDEBAR_WIDTH = 380;
const COLLAPSED_SIDEBAR_WIDTH = 52;
const VISIBLE_SESSIONS_LIMIT = 20;

function isWorkerActiveStatus(status: string): boolean {
  return (
    status === "running" ||
    status === "working" ||
    status === "waiting_input" ||
    status === "waiting_approval" ||
    status === "waiting" ||
    status === "queued" ||
    status === "stalled"
  );
}

function isWorkerWaiting(w: HostWorkerDto): boolean {
  return (
    w.status === "waiting_input" ||
    w.status === "waiting_approval" ||
    w.status === "waiting" ||
    Boolean(w.pendingQuestion) ||
    Boolean(w.pendingApproval)
  );
}

function readStoredToolsOpen(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.localStorage.getItem(SIDEBAR_TOOLS_OPEN_KEY) === "1";
  } catch {
    return false;
  }
}

function saveStoredToolsOpen(open: boolean): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(SIDEBAR_TOOLS_OPEN_KEY, open ? "1" : "0");
  } catch {
    return;
  }
}

function readStoredSidebarWidth(): number {
  if (typeof window === "undefined") return DEFAULT_SIDEBAR_WIDTH;
  try {
    const raw = Number(window.localStorage.getItem(SIDEBAR_WIDTH_KEY));
    if (Number.isFinite(raw) && raw >= MIN_SIDEBAR_WIDTH && raw <= MAX_SIDEBAR_WIDTH) {
      return Math.round(raw);
    }
  } catch {
    return DEFAULT_SIDEBAR_WIDTH;
  }
  return DEFAULT_SIDEBAR_WIDTH;
}

function saveStoredSidebarWidth(width: number): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(SIDEBAR_WIDTH_KEY, String(width));
  } catch {
    return;
  }
}

function downloadText(filename: string, content: string): void {
  const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1200);
}

export function Sidebar({
  open,
  hideHeader = false,
  appMode,
  activeView,
  onChangeAppMode,
  onSelectChat,
  onSelectSuperMain,
  onSelectFeature,
  onToggleCollapse,
  onOpenSearch,
  onOpenCreateBot,
  onOpenBotSettings,
  onOpenSuperTeam,
  onOpenSettings,
}: {
  open: boolean;
  hideHeader?: boolean;
  appMode: "pi" | "super";
  activeView: "chat" | "super" | FeatureViewKind;
  onChangeAppMode: (mode: "pi" | "super") => void;
  onSelectChat: () => void;
  onSelectSuperMain: () => void;
  onSelectFeature: (view: FeatureViewKind) => void;
  onToggleCollapse: () => void;
  onOpenSearch: () => void;
  onOpenCreateBot: (target?: { deviceId: string; deviceName: string }) => void;
  onOpenBotSettings: (bot: HostBotDto) => void;
  onOpenSuperTeam: () => void;
  onOpenSettings: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);
  const relayDevices = useSettingsStore((s) => s.relayDevices);

  const superState = useSuperStore((s) => s.state);
  const superTeam = useSuperStore((s) => s.team);
  const selectedWorkerId = useSuperStore((s) => s.selectedWorkerId);
  const setSelectedWorkerId = useSuperStore((s) => s.setSelectedWorkerId);
  const deleteWorker = useSuperStore((s) => s.deleteWorker);

  const bots = useBotStore((s) => s.bots);
  const collapsedBotIds = useBotStore((s) => s.collapsedBotIds);
  const toggleBotCollapse = useBotStore((s) => s.toggleBotCollapse);

  const summariesById = useSessionStore((s) => s.summariesById);
  const sessionIdsByBot = useSessionStore((s) => s.sessionIdsByBot);
  const pendingQuestions = useSessionStore((s) => s.pendingQuestions);
  const pendingApprovals = useSessionStore((s) => s.pendingApprovals);
  const unreadSessionIds = useSessionStore((s) => s.unreadSessionIds);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const draftBotId = useSessionStore((s) => s.draftBotId);
  const selectSession = useSessionStore((s) => s.selectSession);
  const startDraftSession = useSessionStore((s) => s.startDraftSession);
  const forkSession = useSessionStore((s) => s.forkSession);
  const renameSession = useSessionStore((s) => s.renameSession);
  const togglePinSession = useSessionStore((s) => s.togglePinSession);
  const toggleArchiveSession = useSessionStore((s) => s.toggleArchiveSession);
  const toggleMemoryExclude = useSessionStore((s) => s.toggleMemoryExclude);
  const scheduleDeleteSession = useSessionStore((s) => s.scheduleDeleteSession);

  const [sidebarWidth, setSidebarWidth] = useState<number>(readStoredSidebarWidth);
  const [toolsOpen, setToolsOpen] = useState<boolean>(readStoredToolsOpen);
  const [resizing, setResizing] = useState(false);
  const [expandedBots, setExpandedBots] = useState<Record<string, boolean>>({});
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [workerDeleteTarget, setWorkerDeleteTarget] = useState<HostWorkerDto | null>(null);
  const dragCleanupRef = useRef<(() => void) | null>(null);

  const toggleToolsOpen = () => {
    setToolsOpen((prev) => {
      const next = !prev;
      saveStoredToolsOpen(next);
      return next;
    });
  };

  useEffect(() => {
    return () => {
      dragCleanupRef.current?.();
    };
  }, []);

  const isElectron = Boolean(desktopEnv?.isElectron);
  const isMacOverlay = Boolean(isElectron && desktopEnv?.platform === "darwin");
  const isFrameless = Boolean(isElectron && desktopEnv?.platform !== "darwin");
  const activeBotId = summariesById[activeSessionId]?.botId ?? draftBotId ?? bots[0]?.id;
  const newChatTargetBotId = useMemo(() => {
    const current = bots.find((b) => b.id === activeBotId);
    if (current && (!current.isRemote || current.online)) return current.id;
    return bots.find((b) => !b.isRemote || b.online)?.id ?? bots[0]?.id;
  }, [activeBotId, bots]);

  const botsById = useMemo(() => {
    const map = new Map<string, HostBotDto>();
    for (const b of bots) map.set(b.id, b);
    return map;
  }, [bots]);

  const teamByBotId = useMemo(() => {
    const map = new Map<string, (typeof superTeam)[number]>();
    for (const m of superTeam) map.set(m.botId, m);
    return map;
  }, [superTeam]);

  const resolveWorkerMeta = (w: HostWorkerDto) => {
    const matchedBot =
      botsById.get(w.botId) ??
      (w.deviceId ? botsById.get(`remote:${w.deviceId}:${w.botId}`) : undefined) ??
      bots.find(
        (b) => b.remoteBotId === w.botId && (!w.deviceId || b.deviceId === w.deviceId),
      );
    const matchedTeam =
      teamByBotId.get(w.botId) ??
      superTeam.find(
        (m) =>
          w.deviceId &&
          m.deviceId === w.deviceId &&
          (m.botId === w.botId || m.name === w.botName),
      );
    return {
      matchedBot,
      matchedTeam,
      avatar: matchedBot?.avatar ?? matchedTeam?.avatar ?? null,
    };
  };

  const enabledSuperTeamCount = useMemo(
    () => superTeam.filter((m) => m.enabledInSuper).length,
    [superTeam],
  );

  const superWorkers = useMemo(() => {
    const list = superState?.workers ?? [];
    return list.toSorted((a, b) => {
      const aAct = isWorkerActiveStatus(a.status) ? 0 : 1;
      const bAct = isWorkerActiveStatus(b.status) ? 0 : 1;
      if (aAct !== bAct) return aAct - bAct;
      return b.updatedAt - a.updatedAt;
    });
  }, [superState?.workers]);

  const activeSuperWorkers = useMemo(
    () => superWorkers.filter((w) => isWorkerActiveStatus(w.status)),
    [superWorkers],
  );

  const savedSuperWorkers = useMemo(
    () => superWorkers.filter((w) => !isWorkerActiveStatus(w.status)),
    [superWorkers],
  );

  const pendingSessionIds = useMemo(() => {
    const ids = new Set(pendingQuestions.map((q) => q.sessionId));
    for (const a of pendingApprovals) {
      ids.add(a.sessionId);
    }
    return ids;
  }, [pendingApprovals, pendingQuestions]);

  const localBots = useMemo(() => bots.filter((b) => !b.isRemote), [bots]);
  const remoteBots = useMemo(() => bots.filter((b) => b.isRemote), [bots]);

  const remoteGroups = useMemo(() => {
    const map = new Map<
      string,
      {
        deviceId: string;
        deviceName: string;
        platform: string;
        capabilities: string[];
        online: boolean;
        bots: HostBotDto[];
      }
    >();
    for (const dev of relayDevices) {
      if (dev.isSelf) continue;
      map.set(dev.deviceId, {
        deviceId: dev.deviceId,
        deviceName: dev.name,
        platform: dev.platform || "",
        capabilities: dev.capabilities ?? [],
        online: dev.online,
        bots: [],
      });
    }
    for (const rb of remoteBots) {
      const key = rb.deviceId || rb.deviceName || rb.id;
      const existing = map.get(key) ?? {
        deviceId: key,
        deviceName: rb.deviceName || t("nav.remoteBots"),
        platform: "",
        capabilities: rb.capabilities ?? [],
        online: Boolean(rb.online),
        bots: [],
      };
      if (existing.capabilities.length === 0 && rb.capabilities?.length) {
        existing.capabilities = rb.capabilities;
      }
      existing.online = existing.online || Boolean(rb.online);
      existing.bots.push(rb);
      map.set(key, existing);
    }
    return Array.from(map.values()).sort((a, b) => {
      if (a.online !== b.online) return a.online ? -1 : 1;
      return a.deviceName.localeCompare(b.deviceName);
    });
  }, [relayDevices, remoteBots, t]);

  const sessionsForBot = (botId: string): SessionSummary[] => {
    const ids = sessionIdsByBot[botId] ?? [];
    return ids
      .map((id) => summariesById[id])
      .filter((s): s is SessionSummary => Boolean(s))
      .sort((a, b) => {
        if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
        return b.updatedAtMs - a.updatedAtMs;
      });
  };

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = sidebarWidth;
    let latestX = startX;
    let moveRaf: number | null = null;
    setResizing(true);

    const onMove = (ev: MouseEvent) => {
      latestX = ev.clientX;
      if (moveRaf !== null) return;
      moveRaf = requestAnimationFrame(() => {
        moveRaf = null;
        const candidate = startW + (latestX - startX);
        const clamped = Math.min(MAX_SIDEBAR_WIDTH, Math.max(MIN_SIDEBAR_WIDTH, Math.round(candidate)));
        setSidebarWidth(clamped);
      });
    };

    const cleanup = () => {
      if (moveRaf !== null) {
        cancelAnimationFrame(moveRaf);
        moveRaf = null;
      }
      setResizing(false);
      const finalW = Math.min(
        MAX_SIDEBAR_WIDTH,
        Math.max(MIN_SIDEBAR_WIDTH, Math.round(startW + (latestX - startX))),
      );
      setSidebarWidth(finalW);
      saveStoredSidebarWidth(finalW);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", cleanup);
      dragCleanupRef.current = null;
    };

    dragCleanupRef.current = cleanup;
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", cleanup);
  };

  const renderCompactRail = () => (
    <div className="flex h-full w-[52px] shrink-0 flex-col items-center justify-between py-2">
      <div className="flex flex-col items-center gap-1.5">
        {!hideHeader && (
          <>
            {isMacOverlay && <div className="pi-drag-region h-[32px] w-full" />}
            <IconButton
              size="md"
              label={t("nav.expandSidebar")}
              onClick={onToggleCollapse}
            >
              <IconLayoutSidebarLeftExpand size={17} stroke={1.65} />
            </IconButton>

            <button
              type="button"
              onClick={onToggleCollapse}
              title={t("app.name")}
              className="my-0.5 flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
            >
              <PiBrandMark size={20} dark={dark} />
            </button>
          </>
        )}

        {appMode === "super" ? (
          <>
            <button
              type="button"
              title={t("super.drawerTeam")}
              onClick={onOpenSuperTeam}
              className="flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
              style={{ color: "var(--pi-ink-secondary)" }}
            >
              <BrandIcon name="ai/agent" size={16} />
            </button>
            <button
              type="button"
              title={t("auto.superTimersTitle")}
              onClick={() => onSelectFeature("timers")}
              className="flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
              style={{
                background: activeView === "timers" ? "var(--pi-wash)" : "transparent",
                color: activeView === "timers" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
              }}
            >
              <BrandIcon name="system/timer" size={15.5} />
            </button>

            {superWorkers.length > 0 && (
              <>
                <div className="my-0.5 h-[0.5px] w-6" style={{ background: "var(--pi-hairline)" }} />
                <div className="flex max-h-[260px] flex-col items-center gap-1.5 overflow-y-auto py-0.5">
                  {superWorkers.slice(0, 10).map((w) => {
                    const { matchedBot, avatar } = resolveWorkerMeta(w);
                    const waiting = isWorkerWaiting(w);
                    const active = isWorkerActiveStatus(w.status);
                    const selected = activeView === "super" && selectedWorkerId === w.workerId;
                    return (
                      <button
                        key={w.workerId}
                        type="button"
                        title={`${w.title || w.botName} · ${w.botName}`}
                        onClick={() => {
                          onSelectSuperMain();
                          setSelectedWorkerId(w.workerId);
                        }}
                        className="relative flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
                        style={{ background: selected ? "var(--pi-wash)" : "transparent" }}
                      >
                        <BotAvatar
                          avatar={avatar}
                          name={w.botName}
                          isHome={matchedBot?.isHome}
                          isRemote={w.isRemote}
                          dark={dark}
                          size={20}
                        />
                        {(active || w.status === "failed") && (
                          <span
                            className={`absolute right-0.5 bottom-0.5 h-1.5 w-1.5 rounded-full ${
                              active && !waiting ? "animate-pulse" : ""
                            }`}
                            style={{
                              background:
                                w.status === "failed"
                                  ? "var(--pi-danger)"
                                  : waiting
                                    ? "var(--pi-warning)"
                                    : "var(--pi-accent)",
                            }}
                          />
                        )}
                      </button>
                    );
                  })}
                </div>
              </>
            )}
          </>
        ) : (
          <>
            <IconButton
              size="md"
              label={t("nav.newChat")}
              onClick={() => {
                onSelectChat();
                if (newChatTargetBotId) startDraftSession(newChatTargetBotId);
              }}
            >
              <BrandIcon name="chat/new_chat" size={16} />
            </IconButton>

            <IconButton
              size="md"
              label={t("nav.searchPlaceholder")}
              onClick={onOpenSearch}
            >
              <BrandIcon name="action/search" size={15} />
            </IconButton>

            <div className="my-0.5 h-[0.5px] w-6" style={{ background: "var(--pi-hairline)" }} />

            <button
              type="button"
              title={t("nav.timers")}
              onClick={() => onSelectFeature("timers")}
              className="flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
              style={{
                background: activeView === "timers" ? "var(--pi-wash)" : "transparent",
                color: activeView === "timers" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
              }}
            >
              <BrandIcon name="system/timer" size={15.5} />
            </button>
            <button
              type="button"
              title={t("settings.navMemory")}
              onClick={() => onSelectFeature("memory")}
              className="flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
              style={{
                background: activeView === "memory" ? "var(--pi-wash)" : "transparent",
                color: activeView === "memory" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
              }}
            >
              <BrandIcon name="ai/memory" size={15.5} />
            </button>
            <button
              type="button"
              title={t("nav.skills")}
              onClick={() => onSelectFeature("skills")}
              className="flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
              style={{
                background: activeView === "skills" ? "var(--pi-wash)" : "transparent",
                color: activeView === "skills" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
              }}
            >
              <BrandIcon name="ai/skills" size={15.5} />
            </button>
            <button
              type="button"
              title={t("nav.mcp")}
              onClick={() => onSelectFeature("mcp")}
              className="flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
              style={{
                background: activeView === "mcp" ? "var(--pi-wash)" : "transparent",
                color: activeView === "mcp" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
              }}
            >
              <IconPlugConnected size={16} stroke={1.65} />
            </button>

            <div className="my-0.5 h-[0.5px] w-6" style={{ background: "var(--pi-hairline)" }} />

            <div className="flex max-h-[260px] flex-col items-center gap-1.5 overflow-y-auto py-0.5">
              {bots.slice(0, 10).map((bot) => {
                const selected = activeView === "chat" && bot.id === activeBotId;
                const offline = Boolean(bot.isRemote && !bot.online);
                return (
                  <ContextMenu
                    key={bot.id}
                    trigger={
                      <button
                        type="button"
                        title={bot.name}
                        onClick={() => {
                          onSelectChat();
                          const firstSess = sessionsForBot(bot.id)[0];
                          if (firstSess && client) {
                            void selectSession(client, firstSess.id, locale);
                          } else if (!offline) {
                            startDraftSession(bot.id);
                          }
                        }}
                        className="relative flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
                        style={{
                          background: selected ? "var(--pi-wash)" : "transparent",
                        }}
                      >
                        <BotAvatar
                          avatar={bot.avatar}
                          name={bot.name}
                          isHome={bot.isHome}
                          isRemote={bot.isRemote}
                          dark={dark}
                          size={20}
                        />
                        {bot.isRemote && (
                          <span
                            className="absolute right-0.5 bottom-0.5 h-1.5 w-1.5 rounded-full"
                            style={{
                              background: bot.online ? "var(--pi-success)" : "var(--pi-ink-tertiary)",
                            }}
                          />
                        )}
                      </button>
                    }
                  >
                    <ContextMenuItem
                      icon={<BrandIcon name="chat/new_chat" size={14} />}
                      label={t("nav.newChat")}
                      disabled={offline}
                      onSelect={() => {
                        onSelectChat();
                        startDraftSession(bot.id);
                      }}
                    />
                    <ContextMenuItem
                      icon={<BrandIcon name="system/settings" size={14} />}
                      label={t("nav.botSettings")}
                      onSelect={() => onOpenBotSettings(bot)}
                    />
                    {!bot.isRemote && isElectron && bot.cwd && (
                      <ContextMenuItem
                        icon={<BrandIcon name="file/folder_open" size={14} />}
                        label={t("nav.revealFolder")}
                        onSelect={() => void revealInFileManager(bot.cwd)}
                      />
                    )}
                  </ContextMenu>
                );
              })}
            </div>
          </>
        )}
      </div>

      <div className="flex flex-col items-center gap-1">
        <div className="my-0.5 h-[0.5px] w-6" style={{ background: "var(--pi-hairline)" }} />
        <IconButton size="md" label={t("nav.settings")} onClick={onOpenSettings}>
          <BrandIcon name="system/settings" size={16} />
        </IconButton>
      </div>
    </div>
  );

  const renderSessionRow = (session: SessionSummary, isRemoteBot: boolean) => {
    const isCurrent = activeView === "chat" && session.id === activeSessionId;
    const displayTitle = session.title || t("nav.untitledChat");
    const relTime = formatRelativeTime(session.updatedAtMs, locale);
    const hasPendingQuestion = pendingSessionIds.has(session.id);
    const hasUnread = Boolean(unreadSessionIds[session.id]);

    if (renamingId === session.id) {
      return (
        <div key={session.id} className="px-1 py-0.5">
          <Input
            autoFocus
            value={renameDraft}
            onChange={(e) => setRenameDraft(e.target.value)}
            onBlur={() => {
              const trimmed = renameDraft.trim();
              if (client && trimmed && trimmed !== displayTitle) {
                void renameSession(client, session.id, trimmed);
              }
              setRenamingId(null);
            }}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (e.key === "Enter") {
                const trimmed = renameDraft.trim();
                if (client && trimmed && trimmed !== displayTitle) {
                  void renameSession(client, session.id, trimmed);
                }
                setRenamingId(null);
              } else if (e.key === "Escape") {
                setRenamingId(null);
              }
            }}
            className="h-7 text-[12.5px]"
          />
        </div>
      );
    }

    const startRename = () => {
      setRenamingId(session.id);
      setRenameDraft(displayTitle);
    };

    const doExport = () => {
      if (!client) return;
      void client
        .exportSessionJsonl(session.id)
        .then((raw) => {
          downloadText(`session-${session.id}.jsonl`, raw);
        })
        .catch((err) => {
          const msg = err instanceof Error ? err.message : "Export failed";
          useSessionStore.setState({ actionError: t("nav.exportFailed", { error: msg }) });
        });
    };

    return (
      <ContextMenu
        key={session.id}
        trigger={
          <div
            className="group/row flex h-[31px] items-center gap-1 rounded-[9px] px-2 transition-[background-color,border-color,box-shadow] duration-150 hover:bg-[var(--pi-hover)]"
            style={{
              background: isCurrent ? "var(--pi-canvas)" : "transparent",
              border: isCurrent ? "1px solid var(--pi-canvas-border)" : "1px solid transparent",
              boxShadow: isCurrent ? "0 1px 2px rgba(0, 0, 0, 0.04)" : "none",
            }}
            onDoubleClick={startRename}
          >
            <button
              type="button"
              onClick={() => {
                onSelectChat();
                if (client) void selectSession(client, session.id, locale);
              }}
              onKeyDown={(e) => {
                if (e.key === "F2") {
                  e.preventDefault();
                  startRename();
                }
              }}
              className="flex min-w-0 flex-1 items-center gap-1.5 text-left outline-none"
            >
              {hasPendingQuestion ? (
                <span
                  className="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
                  style={{ background: "var(--pi-warning)" }}
                />
              ) : session.busy ? (
                <span
                  className="inline-block h-1.5 w-1.5 shrink-0 animate-pulse rounded-full"
                  style={{ background: "var(--pi-accent)" }}
                />
              ) : hasUnread ? (
                <span
                  className="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
                  style={{ background: "var(--pi-accent)" }}
                />
              ) : session.pinned ? (
                <BrandIcon name="action/pin" size={11.5} className="shrink-0 opacity-60" />
              ) : null}
              <span
                className="truncate text-[12.5px]"
                style={{
                  color: isCurrent ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                  fontWeight: isCurrent || hasUnread ? 550 : 400,
                }}
              >
                {displayTitle}
              </span>
            </button>

            <div className="relative flex w-7 shrink-0 items-center justify-end">
              {relTime && (
                <span
                  className="text-[11px] tabular-nums transition-opacity group-hover/row:opacity-0 group-focus-within/row:opacity-0"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  {relTime}
                </span>
              )}
              <DropdownMenu
                trigger={
                  <button
                    type="button"
                    aria-label={displayTitle}
                    className="pi-hover-reveal absolute right-0 flex h-5 w-5 items-center justify-center rounded-md hover:bg-[var(--pi-hover)]"
                    style={{ color: "var(--pi-ink-tertiary)" }}
                  >
                    <BrandIcon name="navigation/more" size={13.5} />
                  </button>
                }
              >
                <DropdownItem
                  icon={<BrandIcon name="action/pen" size={14} />}
                  label={t("nav.rename")}
                  onSelect={startRename}
                />
                <DropdownItem
                  icon={session.pinned ? <IconPinnedOff size={14} /> : <BrandIcon name="action/pin" size={14} />}
                  label={session.pinned ? t("nav.unpin") : t("nav.pin")}
                  onSelect={() => {
                    if (client) void togglePinSession(client, session.id, !session.pinned);
                  }}
                />
                {!isRemoteBot && (
                  <DropdownItem
                    icon={<BrandIcon name="system/git_branch" size={14} />}
                    label={t("nav.fork")}
                    onSelect={() => {
                      if (client) {
                        onSelectChat();
                        void forkSession(client, session.id, locale);
                      }
                    }}
                  />
                )}
                <DropdownItem
                  icon={<BrandIcon name="action/archive" size={14} />}
                  label={t("nav.archive")}
                  onSelect={() => {
                    if (client) void toggleArchiveSession(client, session.id, true);
                  }}
                />
                <DropdownItem
                  label={session.memoryExcluded ? t("nav.memoryInclude") : t("nav.memoryExclude")}
                  onSelect={() => {
                    if (client) void toggleMemoryExclude(client, session.id, !session.memoryExcluded);
                  }}
                />
                <DropdownItem
                  icon={<BrandIcon name="action/download" size={14} />}
                  label={t("nav.export")}
                  onSelect={doExport}
                />
                <DropdownSeparator />
                <DropdownItem
                  danger
                  icon={<BrandIcon name="action/delete" size={14} />}
                  label={t("nav.delete")}
                  onSelect={() => {
                    if (client) scheduleDeleteSession(client, session.id);
                  }}
                />
              </DropdownMenu>
            </div>
          </div>
        }
      >
        <ContextMenuItem
          icon={<BrandIcon name="action/pen" size={14} />}
          label={t("nav.rename")}
          shortcut="F2"
          onSelect={startRename}
        />
        <ContextMenuItem
          icon={session.pinned ? <IconPinnedOff size={14} /> : <BrandIcon name="action/pin" size={14} />}
          label={session.pinned ? t("nav.unpin") : t("nav.pin")}
          onSelect={() => {
            if (client) void togglePinSession(client, session.id, !session.pinned);
          }}
        />
        {!isRemoteBot && (
          <ContextMenuItem
            icon={<BrandIcon name="system/git_branch" size={14} />}
            label={t("nav.fork")}
            onSelect={() => {
              if (client) {
                onSelectChat();
                void forkSession(client, session.id, locale);
              }
            }}
          />
        )}
        <ContextMenuItem
          icon={<BrandIcon name="action/archive" size={14} />}
          label={t("nav.archive")}
          onSelect={() => {
            if (client) void toggleArchiveSession(client, session.id, true);
          }}
        />
        <ContextMenuItem
          label={session.memoryExcluded ? t("nav.memoryInclude") : t("nav.memoryExclude")}
          onSelect={() => {
            if (client) void toggleMemoryExclude(client, session.id, !session.memoryExcluded);
          }}
        />
        <ContextMenuItem
          icon={<BrandIcon name="action/download" size={14} />}
          label={t("nav.export")}
          onSelect={doExport}
        />
        <ContextMenuSeparator />
        <ContextMenuItem
          danger
          icon={<BrandIcon name="action/delete" size={14} />}
          label={t("nav.delete")}
          onSelect={() => {
            if (client) scheduleDeleteSession(client, session.id);
          }}
        />
      </ContextMenu>
    );
  };

  const renderBotGroup = (bot: HostBotDto) => {
    const collapsed = Boolean(collapsedBotIds[bot.id]);
    const list = sessionsForBot(bot.id);
    const offline = Boolean(bot.isRemote && !bot.online);
    const expandedList = Boolean(expandedBots[bot.id]);
    const visibleList = expandedList ? list : list.slice(0, VISIBLE_SESSIONS_LIMIT);
    const hasMore = list.length > VISIBLE_SESSIONS_LIMIT;

    return (
      <div key={bot.id} className="space-y-0.5">
        <ContextMenu
          trigger={
            <div className="group/bot flex h-[30px] items-center justify-between gap-1 rounded-[8px] px-1.5 hover:bg-[var(--pi-hover)]">
              <button
                type="button"
                onClick={() => toggleBotCollapse(bot.id)}
                className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
              >
                <BrandIcon
                  name="navigation/chevron_down"
                  size={12}
                  className="shrink-0 transition-transform duration-150"
                  style={{
                    color: "var(--pi-ink-tertiary)",
                    transform: collapsed ? "rotate(-90deg)" : "rotate(0deg)",
                  }}
                />
                <BotAvatar
                  avatar={bot.avatar}
                  name={bot.name}
                  isHome={bot.isHome}
                  isRemote={bot.isRemote}
                  dark={dark}
                  size={18}
                />
                <span
                  className="truncate text-[12.5px] font-medium"
                  style={{ color: offline ? "var(--pi-ink-tertiary)" : "var(--pi-ink)" }}
                >
                  {bot.name}
                </span>
              </button>

              <div className="pi-hover-reveal flex shrink-0 items-center gap-0.5">
                <IconButton
                  size="sm"
                  label={t("nav.botSettings")}
                  onClick={() => onOpenBotSettings(bot)}
                >
                  <BrandIcon name="navigation/more" size={13.5} />
                </IconButton>
                <IconButton
                  size="sm"
                  label={t("nav.newChat")}
                  disabled={offline}
                  onClick={() => {
                    onSelectChat();
                    startDraftSession(bot.id);
                  }}
                >
                  <BrandIcon name="chat/new_chat" size={13.5} />
                </IconButton>
              </div>
            </div>
          }
        >
          <ContextMenuItem
            icon={<BrandIcon name="chat/new_chat" size={14} />}
            label={t("nav.newChat")}
            disabled={offline}
            onSelect={() => {
              onSelectChat();
              startDraftSession(bot.id);
            }}
          />
          <ContextMenuItem
            icon={<BrandIcon name="system/settings" size={14} />}
            label={t("nav.botSettings")}
            onSelect={() => onOpenBotSettings(bot)}
          />
          {!bot.isRemote && isElectron && bot.cwd && (
            <ContextMenuItem
              icon={<BrandIcon name="file/folder_open" size={14} />}
              label={t("nav.revealFolder")}
              onSelect={() => void revealInFileManager(bot.cwd)}
            />
          )}
          {bot.cwd && (
            <ContextMenuItem
              icon={<BrandIcon name="action/copy" size={14} />}
              label={t("nav.copyPath")}
              onSelect={() => void navigator.clipboard?.writeText(bot.cwd)}
            />
          )}
          <ContextMenuSeparator />
          <ContextMenuItem
            label={collapsed ? t("nav.expandSidebar") : t("nav.collapseSidebar")}
            onSelect={() => toggleBotCollapse(bot.id)}
          />
        </ContextMenu>

        <div className="pi-collapse" data-open={collapsed ? "false" : "true"} aria-hidden={collapsed}>
          <div className="pi-collapse-inner">
            <div className="space-y-0.5 pl-3 pt-0.5">
              {list.length === 0 ? (
                offline ? (
                  <div className="px-2 py-1 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("nav.offline")}
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={() => {
                      onSelectChat();
                      startDraftSession(bot.id);
                    }}
                    className="flex h-[28px] w-full items-center gap-1.5 rounded-[8px] px-2 text-left text-[12px] transition-colors hover:bg-[var(--pi-hover)] hover:text-[var(--pi-ink)]"
                    style={{ color: "var(--pi-ink-tertiary)" }}
                  >
                    <BrandIcon name="action/add" size={12.5} className="shrink-0 opacity-75" />
                    <span className="truncate">{t("nav.startNewChat")}</span>
                  </button>
                )
              ) : (
                <>
                  {visibleList.map((s) => renderSessionRow(s, Boolean(bot.isRemote)))}
                  {hasMore && (
                    <button
                      type="button"
                      onClick={() =>
                        setExpandedBots((prev) => ({ ...prev, [bot.id]: !expandedList }))
                      }
                      className="w-full rounded-[7px] px-2 py-1 text-left text-[11.5px] transition-colors hover:bg-[var(--pi-hover)]"
                      style={{ color: "var(--pi-ink-tertiary)" }}
                    >
                      {expandedList
                        ? t("nav.collapseSessions")
                        : t("nav.showAllSessions", { count: list.length })}
                    </button>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    );
  };

  const renderWorkerRow = (w: HostWorkerDto) => {
    const { matchedBot, matchedTeam, avatar } = resolveWorkerMeta(w);
    const active = isWorkerActiveStatus(w.status);
    const waiting = isWorkerWaiting(w);
    const selected = activeView === "super" && selectedWorkerId === w.workerId;
    const deviceLabel = w.isRemote && w.deviceName ? w.deviceName : t("super.teamGroupLocal");
    const slotText =
      matchedTeam && matchedTeam.maxWorkerSlots > 0
        ? ` · ${matchedTeam.usedWorkerSlots}/${matchedTeam.maxWorkerSlots}`
        : "";
    const relTime = formatRelativeTime(w.updatedAt, locale);

    return (
      <div
        key={w.workerId}
        className="group/worker flex items-center gap-2 rounded-[9px] px-2 py-1.5 transition-[background-color,border-color,box-shadow] duration-150 hover:bg-[var(--pi-hover)]"
        style={{
          background: selected ? "var(--pi-canvas)" : "transparent",
          border: selected ? "1px solid var(--pi-canvas-border)" : "1px solid transparent",
          boxShadow: selected ? "0 1px 2px rgba(0, 0, 0, 0.04)" : "none",
        }}
      >
        <button
          type="button"
          onClick={() => {
            onSelectSuperMain();
            setSelectedWorkerId(w.workerId);
          }}
          className="flex min-w-0 flex-1 items-center gap-2 text-left outline-none"
        >
          <BotAvatar
            avatar={avatar}
            name={w.botName}
            isHome={matchedBot?.isHome}
            isRemote={w.isRemote}
            dark={dark}
            size={20}
          />
          <div className="min-w-0 flex-1">
            <div
              className="truncate text-[12.5px]"
              style={{
                color: selected ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                fontWeight: selected || active ? 550 : 450,
              }}
            >
              {w.title || w.botName}
            </div>
            <div className="truncate text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {w.botName} · {deviceLabel}
              {slotText}
            </div>
          </div>
        </button>

        <div className="relative flex shrink-0 items-center justify-end gap-1">
          {active ? (
            waiting ? (
              <span
                className="h-2 w-2 rounded-full"
                style={{ background: "var(--pi-warning)" }}
              />
            ) : (
              <IconLoader2
                size={13}
                className="animate-spin"
                style={{ color: "var(--pi-accent)" }}
              />
            )
          ) : (
            <>
              <span
                className="text-[11px] tabular-nums transition-opacity group-hover/worker:opacity-0"
                style={{
                  color: w.status === "failed" ? "var(--pi-danger)" : "var(--pi-ink-tertiary)",
                }}
              >
                {relTime}
              </span>
              <button
                type="button"
                aria-label={t("super.drawerWorkerDelete")}
                title={t("super.drawerWorkerDelete")}
                onClick={(e) => {
                  e.stopPropagation();
                  setWorkerDeleteTarget(w);
                }}
                className="pi-hover-reveal absolute right-0 flex h-5 w-5 items-center justify-center rounded-md hover:bg-[var(--pi-hover)]"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                <BrandIcon name="action/delete" size={12.5} />
              </button>
            </>
          )}
        </div>
      </div>
    );
  };

  return (
    <>
      {resizing && <div className="fixed inset-0 z-40 cursor-col-resize" />}

      <aside
        className={`relative flex h-full shrink-0 flex-col overflow-hidden select-none border-r border-[var(--pi-canvas-border)] sm:border-r-0 ${
          resizing ? "" : "transition-[width] duration-200 ease-out"
        }`}
        style={{
          width: open ? sidebarWidth : COLLAPSED_SIDEBAR_WIDTH,
          background: "var(--pi-chrome)",
        }}
      >
        {open && (
          <div
            onMouseDown={startResize}
            onDoubleClick={() => {
              setSidebarWidth(DEFAULT_SIDEBAR_WIDTH);
              saveStoredSidebarWidth(DEFAULT_SIDEBAR_WIDTH);
            }}
            role="separator"
            aria-orientation="vertical"
            className="absolute top-0 right-0 bottom-0 z-10 w-1 cursor-col-resize transition-colors hover:bg-[var(--pi-accent)]/35"
          />
        )}

        {!open ? (
          renderCompactRail()
        ) : (
          <div className="flex h-full flex-col" style={{ width: sidebarWidth }}>
            {!hideHeader && (
              <div
                className="pi-drag-region flex h-[44px] shrink-0 items-center justify-between px-3"
                style={{ paddingLeft: isMacOverlay ? 76 : 12 }}
                onDoubleClick={() => {
                  if (isFrameless) void windowToggleMaximize();
                }}
              >
                <div className="pi-no-drag flex items-center gap-2">
                  {!isMacOverlay && <PiBrandMark size={19} dark={dark} />}
                  <span className="text-[13.5px] font-semibold tracking-tight" style={{ color: "var(--pi-ink)" }}>
                    {t("app.name")}
                  </span>
                </div>

                <div className="pi-no-drag flex items-center gap-0.5">
                  <IconButton size="sm" label={t("nav.collapseSidebar")} onClick={onToggleCollapse}>
                    <IconLayoutSidebarLeftCollapse size={16} stroke={1.7} />
                  </IconButton>
                </div>
              </div>
            )}

            {!hideHeader && (
              <div className="px-2.5 pb-2">
                <ModeSwitcher
                  activeView={appMode === "super" ? "super" : "chat"}
                  onChange={(v) => onChangeAppMode(v === "super" ? "super" : "pi")}
                  className="w-full max-w-none"
                />
              </div>
            )}

            {appMode === "super" ? (
              <>
                <div className={`space-y-0.5 px-2.5 pb-2 ${hideHeader ? "pt-1.5" : "pt-0.5"}`}>
                  <button
                    type="button"
                    onClick={onOpenSuperTeam}
                    className="flex h-[32px] w-full items-center gap-2 rounded-[9px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                    style={{ color: "var(--pi-ink-secondary)" }}
                  >
                    <BrandIcon name="ai/agent" size={14.5} />
                    <span className="min-w-0 flex-1 truncate text-left">{t("super.drawerTeam")}</span>
                    <span
                      className="rounded-full px-1.5 py-0.5 text-[10.5px] font-medium tabular-nums"
                      style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
                    >
                      {enabledSuperTeamCount}
                    </span>
                  </button>

                  <button
                    type="button"
                    onClick={() => onSelectFeature("timers")}
                    className="flex h-[32px] w-full items-center gap-2 rounded-[9px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      background: activeView === "timers" ? "var(--pi-canvas)" : "transparent",
                      border:
                        activeView === "timers"
                          ? "1px solid var(--pi-canvas-border)"
                          : "1px solid transparent",
                      color: activeView === "timers" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                    }}
                  >
                    <BrandIcon name="system/timer" size={14.5} />
                    <span className="min-w-0 flex-1 truncate text-left">{t("auto.superTimersTitle")}</span>
                  </button>
                </div>

                <div
                  className="pi-scroll-shadow min-h-0 flex-1"
                  style={{ borderTop: "1px solid var(--pi-canvas-border)" }}
                >
                  <div className="space-y-3.5 px-2.5 py-2.5">
                    <div className="flex items-center justify-between px-1.5">
                      <span
                        className="text-[11px] font-semibold tracking-wide"
                        style={{ color: "var(--pi-ink-tertiary)" }}
                      >
                        {t("super.workersDrawerTitle")}
                      </span>
                      {superWorkers.length > 0 && (
                        <span className="text-[11px] tabular-nums" style={{ color: "var(--pi-ink-tertiary)" }}>
                          {superWorkers.length}
                        </span>
                      )}
                    </div>

                    {superWorkers.length === 0 ? (
                      <div
                        className="px-2 py-4 text-[12px] leading-[1.6]"
                        style={{ color: "var(--pi-ink-tertiary)" }}
                      >
                        {t("super.drawerWorkersEmpty")}
                      </div>
                    ) : (
                      <>
                        {activeSuperWorkers.length > 0 && (
                          <div className="space-y-1">
                            <div
                              className="px-1.5 text-[10.5px] font-medium"
                              style={{ color: "var(--pi-ink-tertiary)" }}
                            >
                              {t("super.workersActiveGroup")}
                            </div>
                            <div className="space-y-0.5">{activeSuperWorkers.map(renderWorkerRow)}</div>
                          </div>
                        )}
                        {savedSuperWorkers.length > 0 && (
                          <div className="space-y-1">
                            <div
                              className="px-1.5 text-[10.5px] font-medium"
                              style={{ color: "var(--pi-ink-tertiary)" }}
                            >
                              {t("super.workersSavedGroup")}
                            </div>
                            <div className="space-y-0.5">{savedSuperWorkers.map(renderWorkerRow)}</div>
                          </div>
                        )}
                      </>
                    )}
                  </div>
                </div>
              </>
            ) : (
              <>
                <div className={`space-y-1.5 px-2.5 pb-2 ${hideHeader ? "pt-1.5" : "pt-0.5"}`}>
                  <button
                    type="button"
                    onClick={() => {
                      onSelectChat();
                      if (newChatTargetBotId) startDraftSession(newChatTargetBotId);
                    }}
                    className="flex h-[34px] w-full items-center gap-2 rounded-[10px] px-2.5 text-[13px] font-medium transition-[background-color,transform] duration-150 hover:bg-[var(--pi-hover)] motion-safe:active:scale-[0.985]"
                    style={{
                      background: "var(--pi-canvas)",
                      border: "1px solid var(--pi-canvas-border)",
                      boxShadow: "0 1px 2px rgba(0, 0, 0, 0.04)",
                      color: "var(--pi-ink)",
                    }}
                  >
                    <BrandIcon name="chat/new_chat" size={15} />
                    <span className="flex-1 text-left">{t("nav.newChat")}</span>
                    <kbd
                      className="rounded px-1.5 py-0.5 text-[10px]"
                      style={{
                        background: "var(--pi-wash)",
                        color: "var(--pi-ink-tertiary)",
                        fontFamily: "var(--pi-font-mono)",
                      }}
                    >
                      {formatShortcut(["mod", "n"], desktopEnv?.platform)}
                    </kbd>
                  </button>

                  <button
                    type="button"
                    onClick={onOpenSearch}
                    className="flex h-8 w-full items-center gap-2 rounded-[9px] px-2.5 text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
                    style={{ color: "var(--pi-ink-secondary)" }}
                  >
                    <BrandIcon name="action/search" size={14} />
                    <span className="flex-1 truncate text-left">{t("nav.searchShort")}</span>
                    <kbd
                      className="rounded px-1.5 py-0.5 text-[10px]"
                      style={{
                        background: "var(--pi-wash)",
                        color: "var(--pi-ink-tertiary)",
                        fontFamily: "var(--pi-font-mono)",
                      }}
                    >
                      {formatShortcut(["mod", "k"], desktopEnv?.platform)}
                    </kbd>
                  </button>

                  <div>
                    <button
                      type="button"
                      aria-expanded={toolsOpen}
                      onClick={toggleToolsOpen}
                      className="flex h-[30px] w-full items-center gap-2 rounded-[9px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                      style={{
                        background:
                          !toolsOpen &&
                          (activeView === "timers" ||
                            activeView === "memory" ||
                            activeView === "skills" ||
                            activeView === "mcp")
                            ? "var(--pi-wash)"
                            : "transparent",
                        color:
                          toolsOpen ||
                          activeView === "timers" ||
                          activeView === "memory" ||
                          activeView === "skills" ||
                          activeView === "mcp"
                            ? "var(--pi-ink)"
                            : "var(--pi-ink-secondary)",
                      }}
                    >
                      <IconLayoutGrid size={14.5} stroke={1.65} className="shrink-0" />
                      <span className="min-w-0 flex-1 truncate text-left">{t("nav.tools")}</span>
                      <BrandIcon
                        name="navigation/chevron_down"
                        size={12}
                        className="shrink-0 transition-transform duration-150"
                        style={{
                          color: "var(--pi-ink-tertiary)",
                          transform: toolsOpen ? "rotate(0deg)" : "rotate(-90deg)",
                        }}
                      />
                    </button>

                    <div className="pi-collapse" data-open={toolsOpen ? "true" : "false"} aria-hidden={!toolsOpen}>
                      <div className="pi-collapse-inner">
                        <div className="mt-0.5 space-y-0.5 pl-2">
                          <button
                            type="button"
                            tabIndex={toolsOpen ? 0 : -1}
                            onClick={() => onSelectFeature("timers")}
                            className="flex h-[29px] w-full items-center gap-2 rounded-[8px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                            style={{
                              background: activeView === "timers" ? "var(--pi-wash)" : "transparent",
                              color: activeView === "timers" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                            }}
                          >
                            <BrandIcon name="system/timer" size={14} />
                            <span className="min-w-0 flex-1 truncate text-left">{t("nav.timers")}</span>
                          </button>

                          <button
                            type="button"
                            tabIndex={toolsOpen ? 0 : -1}
                            onClick={() => onSelectFeature("memory")}
                            className="flex h-[29px] w-full items-center gap-2 rounded-[8px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                            style={{
                              background: activeView === "memory" ? "var(--pi-wash)" : "transparent",
                              color: activeView === "memory" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                            }}
                          >
                            <BrandIcon name="ai/memory" size={14} />
                            <span className="truncate">{t("settings.navMemory")}</span>
                          </button>

                          <button
                            type="button"
                            tabIndex={toolsOpen ? 0 : -1}
                            onClick={() => onSelectFeature("skills")}
                            className="flex h-[29px] w-full items-center gap-2 rounded-[8px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                            style={{
                              background: activeView === "skills" ? "var(--pi-wash)" : "transparent",
                              color: activeView === "skills" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                            }}
                          >
                            <BrandIcon name="ai/skills" size={14} />
                            <span className="truncate">{t("nav.skills")}</span>
                          </button>

                          <button
                            type="button"
                            tabIndex={toolsOpen ? 0 : -1}
                            onClick={() => onSelectFeature("mcp")}
                            className="flex h-[29px] w-full items-center gap-2 rounded-[8px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                            style={{
                              background: activeView === "mcp" ? "var(--pi-wash)" : "transparent",
                              color: activeView === "mcp" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                            }}
                          >
                            <IconPlugConnected size={14.5} stroke={1.65} className="shrink-0" />
                            <span className="truncate">{t("nav.mcp")}</span>
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <div
                  className="pi-scroll-shadow min-h-0 flex-1"
                  style={{ borderTop: "1px solid var(--pi-canvas-border)" }}
                >
                  <div className="space-y-4 px-2.5 py-2">
                    <div className="space-y-1">
                      <div className="flex items-center justify-between px-1.5">
                        <span
                          className="text-[11px] font-semibold tracking-wide"
                          style={{ color: "var(--pi-ink-tertiary)" }}
                        >
                          {t("nav.localBots")}
                        </span>
                        <IconButton size="sm" label={t("nav.createBot")} onClick={() => onOpenCreateBot()}>
                          <BrandIcon name="action/add" size={13.5} />
                        </IconButton>
                      </div>
                      <div className="space-y-1">{localBots.map(renderBotGroup)}</div>
                    </div>

                    {remoteGroups.length > 0 && (
                      <div className="space-y-1.5">
                        <div className="px-1.5">
                          <span
                            className="text-[11px] font-semibold tracking-wide"
                            style={{ color: "var(--pi-ink-tertiary)" }}
                          >
                            {t("nav.remoteBots")}
                          </span>
                        </div>
                        <div className="space-y-2.5">
                          {remoteGroups.map((group) => (
                            <div key={group.deviceId} className="space-y-1">
                              <div
                                className="flex items-center justify-between gap-1.5 px-2 text-[11.5px] font-medium"
                                style={{ color: "var(--pi-ink-tertiary)" }}
                              >
                                <div className="flex min-w-0 items-center gap-1.5">
                                  <DevicePlatformIcon
                                    platform={group.platform}
                                    capabilities={group.capabilities}
                                    size={13.5}
                                  />
                                  <span
                                    className="h-1.5 w-1.5 shrink-0 rounded-full"
                                    style={{
                                      background: group.online ? "var(--pi-success)" : "var(--pi-ink-tertiary)",
                                    }}
                                  />
                                  <span className="truncate">{group.deviceName}</span>
                                </div>
                                {group.online && (
                                  <IconButton
                                    size="sm"
                                    label={t("nav.createBot")}
                                    onClick={() =>
                                      onOpenCreateBot({
                                        deviceId: group.deviceId,
                                        deviceName: group.deviceName,
                                      })
                                    }
                                  >
                                    <BrandIcon name="action/add" size={12} />
                                  </IconButton>
                                )}
                              </div>
                              {group.bots.map(renderBotGroup)}
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </>
            )}

            <div
              className="shrink-0 px-2.5 py-2"
              style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
            >
              <button
                type="button"
                onClick={onOpenSettings}
                className="flex h-8 w-full items-center gap-2 rounded-[8px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                style={{ color: "var(--pi-ink-secondary)" }}
              >
                <BrandIcon name="system/settings" size={15} />
                <span>{t("nav.settings")}</span>
              </button>
            </div>
          </div>
        )}
      </aside>

      <ConfirmDialog
        open={Boolean(workerDeleteTarget)}
        onOpenChange={(v) => {
          if (!v) setWorkerDeleteTarget(null);
        }}
        title={t("super.drawerWorkerDelete")}
        description={t("super.drawerWorkerDeleteConfirm", {
          title: workerDeleteTarget?.title || workerDeleteTarget?.botName || "",
        })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !workerDeleteTarget) return;
          const id = workerDeleteTarget.workerId;
          setWorkerDeleteTarget(null);
          void deleteWorker(client, id).catch(() => void 0);
        }}
      />
    </>
  );
}
