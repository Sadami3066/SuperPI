import { useMemo, useState } from "react";
import {
  IconArchive,
  IconChevronDown,
  IconDots,
  IconDownload,
  IconEdit,
  IconLayoutSidebarLeftCollapse,
  IconMessagePlus,
  IconPin,
  IconPinnedOff,
  IconPlus,
  IconSearch,
  IconSettings,
  IconTrash,
} from "@tabler/icons-react";
import type { HostBotDto, SessionSummary } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import {
  ConfirmDialog,
  DropdownItem,
  DropdownMenu,
  DropdownSeparator,
  IconButton,
  Input,
} from "../../ui";
import { BotAvatar, PiBrandMark } from "../shared/BrandAssets";

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
  mainView,
  onToggleCollapse,
  onOpenSearch,
  onOpenCreateBot,
  onOpenBotSettings,
  onOpenSettings,
  onOpenArchived,
  onSelectChatView,
}: {
  open: boolean;
  mainView: "chat" | "settings";
  onToggleCollapse: () => void;
  onOpenSearch: () => void;
  onOpenCreateBot: () => void;
  onOpenBotSettings: (bot: HostBotDto) => void;
  onOpenSettings: () => void;
  onOpenArchived: () => void;
  onSelectChatView: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);

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

  if (!open) return null;

  const isMacOverlay = desktopEnv?.isElectron && desktopEnv.platform === "darwin";

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

  const renderSessionRow = (session: SessionSummary) => {
    const isCurrent = mainView === "chat" && session.id === activeSessionId;
    const displayTitle = session.title || t("nav.untitledChat");

    if (renamingId === session.id) {
      return (
        <div key={session.id} className="px-1.5 py-0.5">
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
        className="group/row flex items-center gap-1 rounded-[9px] px-2 py-1.5 transition-colors hover:bg-[var(--pi-hover)]"
        style={{
          background: isCurrent ? "var(--pi-wash)" : "transparent",
        }}
      >
        <button
          type="button"
          onClick={() => {
            onSelectChatView();
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
            <IconPin size={12} stroke={1.75} className="shrink-0 opacity-60" />
          )}
          <span
            className="truncate text-[13px]"
            style={{
              color: isCurrent ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
              fontWeight: isCurrent ? 550 : 400,
            }}
          >
            {displayTitle}
          </span>
        </button>

        <DropdownMenu
          trigger={
            <button
              type="button"
              aria-label={displayTitle}
              className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md opacity-0 transition-opacity group-hover/row:opacity-100 hover:bg-[var(--pi-hover)]"
              style={{ color: "var(--pi-ink-tertiary)" }}
            >
              <IconDots size={14} stroke={1.75} />
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
              void client.exportSessionJsonl(session.id).then((raw) => {
                downloadText(`session-${session.id}.jsonl`, raw);
              });
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
    );
  };

  const renderBotGroup = (bot: HostBotDto) => {
    const collapsed = Boolean(collapsedBotIds[bot.id]);
    const list = sessionsForBot(bot.id);
    const offline = Boolean(bot.isRemote && !bot.online);

    return (
      <div key={bot.id} className="space-y-0.5">
        <div className="group/bot flex items-center justify-between gap-1 rounded-[9px] px-1.5 py-1 hover:bg-[var(--pi-hover)]">
          <button
            type="button"
            onClick={() => toggleBotCollapse(bot.id)}
            className="flex min-w-0 flex-1 items-center gap-2 text-left"
          >
            <IconChevronDown
              size={13}
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
              size={20}
            />
            <span
              className="truncate text-[13px] font-medium"
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
                onSelectChatView();
                if (client) void createSession(client, bot.id, locale);
              }}
            >
              <IconMessagePlus size={14} stroke={1.7} />
            </IconButton>
            <IconButton
              size="sm"
              label={t("nav.botSettings")}
              onClick={() => onOpenBotSettings(bot)}
            >
              <IconDots size={14} stroke={1.7} />
            </IconButton>
          </div>
        </div>

        {!collapsed && (
          <div className="space-y-0.5 pl-3.5">
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
        className="flex h-full w-[264px] shrink-0 flex-col select-none"
        style={{
          background: "var(--pi-surface)",
          borderRight: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div
          className="pi-drag-region flex h-[48px] shrink-0 items-center justify-between px-3"
          style={{ paddingTop: isMacOverlay ? 6 : 0 }}
        >
          <div
            className="pi-no-drag flex items-center gap-2"
            style={{ paddingLeft: isMacOverlay ? 64 : 0 }}
          >
            {!isMacOverlay && <PiBrandMark size={20} dark={dark} />}
            <span className="text-[14px] font-semibold tracking-tight" style={{ color: "var(--pi-ink)" }}>
              {t("app.name")}
            </span>
          </div>

          <div className="pi-no-drag flex items-center gap-0.5">
            <IconButton label={t("nav.searchPlaceholder")} onClick={onOpenSearch}>
              <IconSearch size={16} stroke={1.7} />
            </IconButton>
            <IconButton label={t("nav.collapseSidebar")} onClick={onToggleCollapse}>
              <IconLayoutSidebarLeftCollapse size={17} stroke={1.7} />
            </IconButton>
          </div>
        </div>

        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-2.5 py-2">
          <div className="space-y-1.5">
            <div className="flex items-center justify-between px-1.5">
              <span
                className="text-[11px] font-semibold uppercase tracking-wider"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                {t("nav.localBots")}
              </span>
              <IconButton size="sm" label={t("nav.createBot")} onClick={onOpenCreateBot}>
                <IconPlus size={14} stroke={1.85} />
              </IconButton>
            </div>
            <div className="space-y-1">{localBots.map(renderBotGroup)}</div>
          </div>

          <div className="space-y-1.5">
            <div className="px-1.5">
              <span
                className="text-[11px] font-semibold uppercase tracking-wider"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                {t("nav.remoteBots")}
              </span>
            </div>
            {remoteByDevice.length === 0 ? (
              <div className="px-2 py-1 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("nav.noRemoteBots")}
              </div>
            ) : (
              <div className="space-y-2.5">
                {remoteByDevice.map(([deviceKey, group]) => (
                  <div key={deviceKey} className="space-y-1">
                    <div className="flex items-center gap-1.5 px-2 text-[11.5px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
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
          className="flex shrink-0 items-center justify-between gap-2 px-3 py-2.5"
          style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
        >
          <button
            type="button"
            onClick={onOpenSettings}
            className="flex h-8 flex-1 items-center gap-2 rounded-[9px] px-2.5 text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
            style={{
              background: mainView === "settings" ? "var(--pi-wash)" : "transparent",
              color: "var(--pi-ink)",
            }}
          >
            <IconSettings size={16} stroke={1.65} />
            <span>{t("nav.settings")}</span>
          </button>
          <IconButton label={t("nav.archived")} onClick={onOpenArchived}>
            <IconArchive size={16} stroke={1.65} />
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
