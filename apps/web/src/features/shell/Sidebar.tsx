import { useEffect, useMemo, useRef, useState } from "react";
import {
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconPinnedOff,
} from "@tabler/icons-react";
import { formatRelativeTime } from "../../api/mappers";
import type { HostBotDto, SessionSummary } from "../../api/types";
import { useI18n } from "../../i18n";
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
import { BotAvatar, BrandIcon, PiBrandMark } from "../shared/BrandAssets";

const SIDEBAR_WIDTH_KEY = "superpi:sidebarWidth";
const DEFAULT_SIDEBAR_WIDTH = 256;
const MIN_SIDEBAR_WIDTH = 210;
const MAX_SIDEBAR_WIDTH = 380;
const SNAP_COLLAPSE_PX = 150;
const VISIBLE_SESSIONS_LIMIT = 5;

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
  activeView,
  onSelectChat,
  onToggleCollapse,
  onOpenSearch,
  onOpenCreateBot,
  onOpenBotSettings,
  onOpenSettings,
  onOpenAutomations,
  onOpenArchived,
}: {
  open: boolean;
  activeView: "chat" | "super";
  onSelectChat: () => void;
  onToggleCollapse: () => void;
  onOpenSearch: () => void;
  onOpenCreateBot: (target?: { deviceId: string; deviceName: string }) => void;
  onOpenBotSettings: (bot: HostBotDto) => void;
  onOpenSettings: () => void;
  onOpenAutomations: () => void;
  onOpenArchived: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);
  const relayDevices = useSettingsStore((s) => s.relayDevices);

  const unreadInboxCount = useSuperStore((s) => s.unreadInboxCount);
  const bots = useBotStore((s) => s.bots);
  const collapsedBotIds = useBotStore((s) => s.collapsedBotIds);
  const toggleBotCollapse = useBotStore((s) => s.toggleBotCollapse);

  const summariesById = useSessionStore((s) => s.summariesById);
  const sessionIdsByBot = useSessionStore((s) => s.sessionIdsByBot);
  const pendingQuestions = useSessionStore((s) => s.pendingQuestions);
  const unreadSessionIds = useSessionStore((s) => s.unreadSessionIds);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const selectSession = useSessionStore((s) => s.selectSession);
  const createSession = useSessionStore((s) => s.createSession);
  const forkSession = useSessionStore((s) => s.forkSession);
  const renameSession = useSessionStore((s) => s.renameSession);
  const togglePinSession = useSessionStore((s) => s.togglePinSession);
  const toggleArchiveSession = useSessionStore((s) => s.toggleArchiveSession);
  const toggleMemoryExclude = useSessionStore((s) => s.toggleMemoryExclude);
  const deleteSession = useSessionStore((s) => s.deleteSession);

  const [sidebarWidth, setSidebarWidth] = useState<number>(readStoredSidebarWidth);
  const [resizing, setResizing] = useState(false);
  const [expandedBots, setExpandedBots] = useState<Record<string, boolean>>({});
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<SessionSummary | null>(null);
  const dragCleanupRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    return () => {
      dragCleanupRef.current?.();
    };
  }, []);

  const isElectron = Boolean(desktopEnv?.isElectron);
  const isMacOverlay = Boolean(isElectron && desktopEnv?.platform === "darwin");
  const isFrameless = Boolean(isElectron && desktopEnv?.platform !== "darwin");
  const activeBotId = summariesById[activeSessionId]?.botId ?? bots[0]?.id;
  const newChatTargetBotId = useMemo(() => {
    const current = bots.find((b) => b.id === activeBotId);
    if (current && (!current.isRemote || current.online)) return current.id;
    return bots.find((b) => !b.isRemote || b.online)?.id ?? bots[0]?.id;
  }, [activeBotId, bots]);

  const pendingSessionIds = useMemo(
    () => new Set(pendingQuestions.map((q) => q.sessionId)),
    [pendingQuestions],
  );

  const localBots = useMemo(() => bots.filter((b) => !b.isRemote), [bots]);
  const remoteBots = useMemo(() => bots.filter((b) => b.isRemote), [bots]);

  const remoteGroups = useMemo(() => {
    const map = new Map<
      string,
      { deviceId: string; deviceName: string; platform: string; online: boolean; bots: HostBotDto[] }
    >();
    for (const dev of relayDevices) {
      if (dev.isSelf) continue;
      map.set(dev.deviceId, {
        deviceId: dev.deviceId,
        deviceName: dev.name,
        platform: dev.platform || "",
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
        online: Boolean(rb.online),
        bots: [],
      };
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
    let snappedClosed = false;
    setResizing(true);

    const onMove = (ev: MouseEvent) => {
      latestX = ev.clientX;
      if (moveRaf !== null) return;
      moveRaf = requestAnimationFrame(() => {
        moveRaf = null;
        const delta = latestX - startX;
        const candidate = startW + delta;
        if (candidate < SNAP_COLLAPSE_PX) {
          snappedClosed = true;
          onToggleCollapse();
          cleanup();
          return;
        }
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
      if (!snappedClosed) {
        const finalW = Math.min(
          MAX_SIDEBAR_WIDTH,
          Math.max(MIN_SIDEBAR_WIDTH, Math.round(startW + (latestX - startX))),
        );
        setSidebarWidth(finalW);
        saveStoredSidebarWidth(finalW);
      }
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", cleanup);
      dragCleanupRef.current = null;
    };

    dragCleanupRef.current = cleanup;
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", cleanup);
  };

  if (!open) {
    return (
      <aside
        className="flex h-full w-[52px] shrink-0 flex-col items-center justify-between py-2 select-none"
        style={{
          background: "var(--pi-paper)",
          borderRight: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div className="flex flex-col items-center gap-1.5">
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

          <IconButton
            size="md"
            label={t("nav.newChat")}
            onClick={() => {
              onSelectChat();
              if (client && newChatTargetBotId) void createSession(client, newChatTargetBotId, locale);
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

          <div className="my-1 h-[0.5px] w-6" style={{ background: "var(--pi-hairline)" }} />

          <div className="flex max-h-[300px] flex-col items-center gap-1.5 overflow-y-auto py-0.5">
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
                        if (!client) return;
                        const firstSess = sessionsForBot(bot.id)[0];
                        if (firstSess) {
                          void selectSession(client, firstSess.id, locale);
                        } else if (!offline) {
                          void createSession(client, bot.id, locale);
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
                      if (client) void createSession(client, bot.id, locale);
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
        </div>

        <div className="flex flex-col items-center gap-1">
          <button
            type="button"
            title={t("settings.navAutomations")}
            onClick={onOpenAutomations}
            className="relative flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            <BrandIcon name="status/notifications" size={15.5} />
            {unreadInboxCount > 0 && (
              <span
                className="absolute top-1 right-1 h-1.5 w-1.5 rounded-full"
                style={{ background: "var(--pi-accent)" }}
              />
            )}
          </button>
          <IconButton size="md" label={t("nav.archived")} onClick={onOpenArchived}>
            <BrandIcon name="action/archive" size={15} />
          </IconButton>
          <IconButton size="md" label={t("nav.settings")} onClick={onOpenSettings}>
            <BrandIcon name="system/settings" size={16} />
          </IconButton>
        </div>
      </aside>
    );
  }

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
              if (client && renameDraft.trim()) {
                void renameSession(client, session.id, renameDraft);
              }
              setRenamingId(null);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                if (client && renameDraft.trim()) {
                  void renameSession(client, session.id, renameDraft);
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
        .catch(() => void 0);
    };

    return (
      <ContextMenu
        key={session.id}
        trigger={
          <div
            className="group/row flex h-[30px] items-center gap-1 rounded-[8px] px-2 transition-colors hover:bg-[var(--pi-hover)]"
            style={{
              background: isCurrent ? "var(--pi-wash)" : "transparent",
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

            <div className="relative flex w-6 shrink-0 items-center justify-end">
              {relTime && (
                <span
                  className="text-[11px] tabular-nums transition-opacity group-hover/row:opacity-0"
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
                    className="absolute right-0 flex h-5 w-5 items-center justify-center rounded-md opacity-0 transition-opacity group-hover/row:opacity-100 hover:bg-[var(--pi-hover)]"
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
                  onSelect={() => setDeleteTarget(session)}
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
          onSelect={() => setDeleteTarget(session)}
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

              <div className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover/bot:opacity-100">
                <IconButton
                  size="sm"
                  label={t("nav.newChat")}
                  disabled={offline}
                  onClick={() => {
                    onSelectChat();
                    if (client) void createSession(client, bot.id, locale);
                  }}
                >
                  <BrandIcon name="chat/new_chat" size={13.5} />
                </IconButton>
                <IconButton
                  size="sm"
                  label={t("nav.botSettings")}
                  onClick={() => onOpenBotSettings(bot)}
                >
                  <BrandIcon name="navigation/more" size={13.5} />
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
              if (client) void createSession(client, bot.id, locale);
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

        {!collapsed && (
          <div className="space-y-0.5 pl-3">
            {list.length === 0 ? (
              <div className="px-2 py-1 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("nav.noSessions")}
              </div>
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
        )}
      </div>
    );
  };

  return (
    <>
      {resizing && <div className="fixed inset-0 z-40 cursor-col-resize" />}

      <aside
        className="relative flex h-full shrink-0 flex-col select-none"
        style={{
          width: sidebarWidth,
          background: "var(--pi-paper)",
          borderRight: "0.5px solid var(--pi-hairline)",
        }}
      >
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

        <div
          className="pi-drag-region flex h-[40px] shrink-0 items-center justify-between px-3"
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

        <div className="space-y-1 px-2.5 pt-1 pb-2">
          <button
            type="button"
            onClick={() => {
              onSelectChat();
              if (client && newChatTargetBotId) void createSession(client, newChatTargetBotId, locale);
            }}
            className="flex h-8 w-full items-center gap-2 rounded-[8px] px-2.5 text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink)" }}
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
            className="flex h-8 w-full items-center gap-2 rounded-[8px] px-2.5 text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            <BrandIcon name="action/search" size={14} />
            <span className="flex-1 truncate text-left">{t("nav.searchPlaceholder").replace(/…$/, "")}</span>
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
        </div>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-2.5 py-1">
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
                        <BrandIcon
                          name={group.platform.toLowerCase().includes("android") ? "system/device" : "system/desktop"}
                          size={12.5}
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

        <div
          className="flex shrink-0 items-center justify-between gap-1 px-2.5 py-2"
          style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
        >
          <button
            type="button"
            onClick={onOpenSettings}
            className="flex h-8 flex-1 items-center gap-2 rounded-[8px] px-2.5 text-[12.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            <BrandIcon name="system/settings" size={15} />
            <span>{t("nav.settings")}</span>
          </button>
          <button
            type="button"
            title={t("settings.navAutomations")}
            onClick={onOpenAutomations}
            className="relative flex h-8 w-8 items-center justify-center rounded-[8px] transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            <BrandIcon name="status/notifications" size={14.5} />
            {unreadInboxCount > 0 && (
              <span
                className="absolute top-1 right-1 h-1.5 w-1.5 rounded-full"
                style={{ background: "var(--pi-accent)" }}
              />
            )}
          </button>
          <IconButton size="sm" label={t("nav.archived")} onClick={onOpenArchived}>
            <BrandIcon name="action/archive" size={14.5} />
          </IconButton>
        </div>
      </aside>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteTarget(null);
        }}
        title={t("nav.deleteConfirmTitle")}
        description={t("nav.deleteConfirmDesc", {
          title: deleteTarget?.title || t("nav.untitledChat"),
        })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (client && deleteTarget) {
            void deleteSession(client, deleteTarget.id);
          }
          setDeleteTarget(null);
        }}
      />
    </>
  );
}
