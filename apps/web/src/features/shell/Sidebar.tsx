import { useMemo, useState } from "react";
import {
  IconArchive,
  IconChevronDown,
  IconDots,
  IconDownload,
  IconEdit,
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconMessagePlus,
  IconPin,
  IconPinnedOff,
  IconPlus,
  IconTrash,
} from "@tabler/icons-react";
import { formatRelativeTime } from "../../api/mappers";
import type { HostBotDto, SessionSummary } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import {
  ConfirmDialog,
  DropdownItem,
  DropdownMenu,
  DropdownSeparator,
  IconButton,
  Input,
} from "../../ui";
import { BotAvatar, BrandIcon, PiBrandMark } from "../shared/BrandAssets";

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
  onSelectSuper,
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
  onSelectSuper: () => void;
  onSelectChat: () => void;
  onToggleCollapse: () => void;
  onOpenSearch: () => void;
  onOpenCreateBot: () => void;
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

  const unreadInboxCount = useSuperStore((s) => s.unreadInboxCount);
  const activeWorkersCount = useSuperStore((s) => {
    const workers = s.state?.workers;
    if (!workers || workers.length === 0) return 0;
    let count = 0;
    for (const w of workers) {
      if (w.status === "running" || w.status === "working" || w.status === "waiting_approval") {
        count++;
      }
    }
    return count;
  });

  const bots = useBotStore((s) => s.bots);
  const collapsedBotIds = useBotStore((s) => s.collapsedBotIds);
  const toggleBotCollapse = useBotStore((s) => s.toggleBotCollapse);

  const summariesById = useSessionStore((s) => s.summariesById);
  const sessionIdsByBot = useSessionStore((s) => s.sessionIdsByBot);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const selectSession = useSessionStore((s) => s.selectSession);
  const createSession = useSessionStore((s) => s.createSession);
  const renameSession = useSessionStore((s) => s.renameSession);
  const togglePinSession = useSessionStore((s) => s.togglePinSession);
  const toggleArchiveSession = useSessionStore((s) => s.toggleArchiveSession);
  const toggleMemoryExclude = useSessionStore((s) => s.toggleMemoryExclude);
  const deleteSession = useSessionStore((s) => s.deleteSession);

  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<SessionSummary | null>(null);

  const localBots = useMemo(() => bots.filter((b) => !b.isRemote), [bots]);
  const remoteBots = useMemo(() => bots.filter((b) => b.isRemote), [bots]);

  const remoteByDevice = useMemo(() => {
    const map = new Map<string, { deviceName: string; online: boolean; bots: HostBotDto[] }>();
    for (const rb of remoteBots) {
      const key = rb.deviceId || rb.deviceName || rb.id;
      const entry = map.get(key) ?? {
        deviceName: rb.deviceName || t("nav.remoteBots"),
        online: Boolean(rb.online),
        bots: [],
      };
      entry.online = entry.online || Boolean(rb.online);
      entry.bots.push(rb);
      map.set(key, entry);
    }
    return Array.from(map.entries());
  }, [remoteBots, t]);

  const isMacOverlay = Boolean(desktopEnv?.isElectron && desktopEnv.platform === "darwin");
  const activeBotId = summariesById[activeSessionId]?.botId ?? bots[0]?.id;

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
              if (client && activeBotId) void createSession(client, activeBotId, locale);
            }}
          >
            <BrandIcon name="chat/new_chat" size={16} />
          </IconButton>

          <button
            type="button"
            title={t("super.title")}
            onClick={onSelectSuper}
            className="relative flex h-8 w-8 items-center justify-center rounded-[9px] transition-colors hover:bg-[var(--pi-hover)]"
            style={{
              background: activeView === "super" ? "var(--pi-wash)" : "transparent",
              color: activeView === "super" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
            }}
          >
            <BrandIcon name="ai/agent" size={16} />
            {activeWorkersCount > 0 && (
              <span
                className="absolute top-1 right-1 h-1.5 w-1.5 rounded-full"
                style={{ background: "var(--pi-accent)" }}
              />
            )}
          </button>

          <IconButton
            size="md"
            label={t("nav.searchPlaceholder")}
            onClick={onOpenSearch}
          >
            <BrandIcon name="action/search" size={15} />
          </IconButton>

          <div className="my-1 h-[0.5px] w-6" style={{ background: "var(--pi-hairline)" }} />

          <div className="flex max-h-[260px] flex-col items-center gap-1.5 overflow-y-auto py-0.5">
            {bots.slice(0, 8).map((bot) => {
              const selected = activeView === "chat" && bot.id === activeBotId;
              return (
                <button
                  key={bot.id}
                  type="button"
                  title={bot.name}
                  onClick={() => {
                    onSelectChat();
                    if (!client) return;
                    const firstSess = sessionsForBot(bot.id)[0];
                    if (firstSess) {
                      void selectSession(client, firstSess.id, locale);
                    } else if (!(bot.isRemote && !bot.online)) {
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

  const renderSessionRow = (session: SessionSummary) => {
    const isCurrent = activeView === "chat" && session.id === activeSessionId;
    const displayTitle = session.title || t("nav.untitledChat");
    const relTime = formatRelativeTime(session.updatedAtMs, locale);

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

    return (
      <div
        key={session.id}
        className="group/row flex h-[30px] items-center gap-1 rounded-[8px] px-2 transition-colors hover:bg-[var(--pi-hover)]"
        style={{
          background: isCurrent ? "var(--pi-wash)" : "transparent",
        }}
      >
        <button
          type="button"
          onClick={() => {
            onSelectChat();
            if (client) void selectSession(client, session.id, locale);
          }}
          className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
        >
          {session.busy && (
            <span
              className="inline-block h-1.5 w-1.5 shrink-0 animate-pulse rounded-full"
              style={{ background: "var(--pi-accent)" }}
            />
          )}
          {session.pinned && !session.busy && (
            <IconPin size={11.5} stroke={1.75} className="shrink-0 opacity-60" />
          )}
          <span
            className="truncate text-[12.5px]"
            style={{
              color: isCurrent ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
              fontWeight: isCurrent ? 550 : 400,
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
                <IconDots size={13.5} stroke={1.75} />
              </button>
            }
          >
            <DropdownItem
              icon={<IconEdit size={14} />}
              label={t("nav.rename")}
              onSelect={() => {
                setRenamingId(session.id);
                setRenameDraft(displayTitle);
              }}
            />
            <DropdownItem
              icon={session.pinned ? <IconPinnedOff size={14} /> : <IconPin size={14} />}
              label={session.pinned ? t("nav.unpin") : t("nav.pin")}
              onSelect={() => {
                if (client) void togglePinSession(client, session.id, !session.pinned);
              }}
            />
            <DropdownItem
              icon={<IconArchive size={14} />}
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
              icon={<IconDownload size={14} />}
              label={t("nav.export")}
                onSelect={() => {
                  if (!client) return;
                  void client
                    .exportSessionJsonl(session.id)
                    .then((raw) => {
                      downloadText(`session-${session.id}.jsonl`, raw);
                    })
                    .catch(() => void 0);
                }}
            />
            <DropdownSeparator />
            <DropdownItem
              danger
              icon={<IconTrash size={14} />}
              label={t("nav.delete")}
              onSelect={() => setDeleteTarget(session)}
            />
          </DropdownMenu>
        </div>
      </div>
    );
  };

  const renderBotGroup = (bot: HostBotDto) => {
    const collapsed = Boolean(collapsedBotIds[bot.id]);
    const list = sessionsForBot(bot.id);
    const offline = Boolean(bot.isRemote && !bot.online);

    return (
      <div key={bot.id} className="space-y-0.5">
        <div className="group/bot flex h-[30px] items-center justify-between gap-1 rounded-[8px] px-1.5 hover:bg-[var(--pi-hover)]">
          <button
            type="button"
            onClick={() => toggleBotCollapse(bot.id)}
            className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
          >
            <IconChevronDown
              size={12}
              stroke={1.75}
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
              <IconMessagePlus size={13.5} stroke={1.7} />
            </IconButton>
            <IconButton
              size="sm"
              label={t("nav.botSettings")}
              onClick={() => onOpenBotSettings(bot)}
            >
              <IconDots size={13.5} stroke={1.7} />
            </IconButton>
          </div>
        </div>

        {!collapsed && (
          <div className="space-y-0.5 pl-3">
            {list.length === 0 ? (
              <div className="px-2 py-1 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("nav.noSessions")}
              </div>
            ) : (
              list.map(renderSessionRow)
            )}
          </div>
        )}
      </div>
    );
  };

  return (
    <>
      <aside
        className="flex h-full w-[252px] shrink-0 flex-col select-none"
        style={{
          background: "var(--pi-paper)",
          borderRight: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div
          className="pi-drag-region flex h-[40px] shrink-0 items-center justify-between px-3"
          style={{ paddingLeft: isMacOverlay ? 76 : 12 }}
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

        <div className="space-y-0.5 px-2.5 pt-1 pb-2">
          <button
            type="button"
            onClick={() => {
              onSelectChat();
              if (client && activeBotId) void createSession(client, activeBotId, locale);
            }}
            className="flex h-8 w-full items-center gap-2 rounded-[8px] px-2.5 text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink)" }}
          >
            <BrandIcon name="chat/new_chat" size={15} />
            <span className="flex-1 text-left">{t("nav.newChat")}</span>
          </button>

          <button
            type="button"
            onClick={onSelectSuper}
            className="flex h-8 w-full items-center gap-2 rounded-[8px] px-2.5 text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
            style={{
              background: activeView === "super" ? "var(--pi-wash)" : "transparent",
              color: activeView === "super" ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
            }}
          >
            <BrandIcon name="ai/agent" size={15} />
            <span className="flex-1 truncate text-left">{t("super.title")}</span>
            {activeWorkersCount > 0 && (
              <span
                className="rounded-full px-1.5 py-0.5 text-[10px] font-semibold tabular-nums"
                style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
              >
                {activeWorkersCount}
              </span>
            )}
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
              style={{ background: "var(--pi-wash)", color: "var(--pi-ink-tertiary)" }}
            >
              ⌘K
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
              <IconButton size="sm" label={t("nav.createBot")} onClick={onOpenCreateBot}>
                <IconPlus size={13.5} stroke={1.85} />
              </IconButton>
            </div>
            <div className="space-y-1">{localBots.map(renderBotGroup)}</div>
          </div>

          <div className="space-y-1">
            <div className="px-1.5">
              <span
                className="text-[11px] font-semibold tracking-wide"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                {t("nav.remoteBots")}
              </span>
            </div>
            {remoteByDevice.length === 0 ? (
              <div className="px-2 py-1 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("nav.noRemoteBots")}
              </div>
            ) : (
              <div className="space-y-2">
                {remoteByDevice.map(([deviceKey, group]) => (
                  <div key={deviceKey} className="space-y-1">
                    <div
                      className="flex items-center gap-1.5 px-2 text-[11.5px] font-medium"
                      style={{ color: "var(--pi-ink-tertiary)" }}
                    >
                      <span
                        className="h-1.5 w-1.5 rounded-full"
                        style={{
                          background: group.online ? "var(--pi-success)" : "var(--pi-ink-tertiary)",
                        }}
                      />
                      <span className="truncate">{group.deviceName}</span>
                    </div>
                    {group.bots.map(renderBotGroup)}
                  </div>
                ))}
              </div>
            )}
          </div>
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
