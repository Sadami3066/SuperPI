import * as DialogPrimitive from "@radix-ui/react-dialog";
import { type ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { IconInbox, IconMessage, IconPlugConnected, IconWorld } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { formatShortcut } from "../../platform/shortcuts";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { type SettingsTabId } from "../settings/SettingsModal";
import { BrandIcon } from "../shared/BrandAssets";
import type { FeatureViewKind } from "./FeatureWorkspaceView";

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onOpenSettings: (tab?: SettingsTabId) => void;
  onOpenCreateBot: () => void;
  onSelectChatView: () => void;
  onSelectSuperView: () => void;
  onSelectFeatureView: (view: FeatureViewKind) => void;
}

interface PaletteEntry {
  id: string;
  label: string;
  subtitle?: string;
  shortcut?: string;
  icon: ReactNode;
  run: () => void;
}

interface PaletteGroup {
  id: string;
  label: string;
  items: PaletteEntry[];
}

export function CommandPalette(props: CommandPaletteProps) {
  if (!props.open) return null;
  return <CommandPaletteBody {...props} />;
}

function CommandPaletteBody({
  open,
  onOpenChange,
  onOpenSettings,
  onOpenCreateBot,
  onSelectChatView,
  onSelectSuperView,
  onSelectFeatureView,
}: CommandPaletteProps) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const itemRefs = useRef<Array<HTMLButtonElement | null>>([]);

  useEffect(() => {
    if (!open) return undefined;
    useWorkbenchStore.getState().pushOverlay();
    return () => {
      useWorkbenchStore.getState().popOverlay();
    };
  }, [open]);

  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const isElectron = Boolean(desktopEnv?.isElectron);
  const platform = desktopEnv?.platform;
  const locale = useSettingsStore((s) => s.locale);
  const bots = useBotStore((s) => s.bots);
  const summariesById = useSessionStore((s) => s.summariesById);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const draftBotId = useSessionStore((s) => s.draftBotId);
  const selectSession = useSessionStore((s) => s.selectSession);
  const startDraftSession = useSessionStore((s) => s.startDraftSession);
  const openPanel = useWorkbenchStore((s) => s.openPanel);

  const botNameById = useMemo(() => new Map(bots.map((b) => [b.id, b.name])), [bots]);

  const { groups, flatEntries } = useMemo(() => {
    const q = query.trim().toLowerCase();

    const quickActions: PaletteEntry[] = [
      {
        id: "act-new-chat",
        label: t("nav.newChat"),
        shortcut: formatShortcut(["mod", "n"], platform),
        icon: <BrandIcon name="chat/new_chat" size={15} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          onSelectChatView();
          const targetBot = summariesById[activeSessionId]?.botId ?? draftBotId ?? bots[0]?.id;
          if (targetBot) startDraftSession(targetBot);
        },
      },
      {
        id: "act-super",
        label: t("super.title"),
        shortcut: formatShortcut(["mod", "shift", "s"], platform),
        icon: <BrandIcon name="ai/agent" size={15} />,
        run: () => {
          onOpenChange(false);
          onSelectSuperView();
        },
      },
      {
        id: "act-create-bot",
        label: t("nav.createBot"),
        icon: <BrandIcon name="action/add" size={15} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          onOpenCreateBot();
        },
      },
      {
        id: "act-settings",
        label: t("nav.settings"),
        shortcut: formatShortcut(["mod", ","], platform),
        icon: <BrandIcon name="system/settings" size={15} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          onOpenSettings("general");
        },
      },
      {
        id: "act-timers",
        label: t("nav.timers"),
        icon: <BrandIcon name="system/timer" size={15} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("timers");
        },
      },
      {
        id: "act-inbox",
        label: t("nav.inbox"),
        icon: <IconInbox size={15} stroke={1.7} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("inbox");
        },
      },
      {
        id: "act-memory",
        label: t("settings.navMemory"),
        icon: <BrandIcon name="ai/memory" size={15} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("memory");
        },
      },
      {
        id: "act-skills",
        label: t("nav.skills"),
        icon: <BrandIcon name="ai/skills" size={15} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("skills");
        },
      },
      {
        id: "act-mcp",
        label: t("nav.mcp"),
        icon: <IconPlugConnected size={15} stroke={1.7} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("mcp");
        },
      },
      {
        id: "act-diagnostics",
        label: t("settings.navDiagnostics"),
        icon: <BrandIcon name="status/info" size={15} />,
        run: () => {
          onOpenChange(false);
          onOpenSettings("diagnostics");
        },
      },
    ];

    const workbenchActions: PaletteEntry[] = [
      {
        id: "act-files",
        label: t("wb.filesTitle"),
        shortcut: formatShortcut(["mod", "shift", "e"], platform),
        icon: <BrandIcon name="file/folder_closed" size={15} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          openPanel("files");
        },
      },
      {
        id: "act-diff",
        label: t("wb.diffTitle"),
        shortcut: formatShortcut(["mod", "shift", "g"], platform),
        icon: <BrandIcon name="system/git_branch" size={15} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          openPanel("diff");
        },
      },
      {
        id: "act-terminal",
        label: t("wb.terminalTitle"),
        shortcut: formatShortcut(["mod", "j"], platform),
        icon: <BrandIcon name="system/terminal" size={15} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          openPanel("terminal");
        },
      },
    ];

    if (isElectron) {
      workbenchActions.push({
        id: "act-browser",
        label: t("wb.browserTitle"),
        shortcut: formatShortcut(["mod", "shift", "b"], platform),
        icon: <IconWorld size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          openPanel("browser");
        },
      });
    }

    const sessions = Object.values(summariesById)
      .filter((s) => !s.archived)
      .sort((a, b) => b.updatedAtMs - a.updatedAtMs)
      .filter((s) => {
        if (!q) return true;
        const botName = botNameById.get(s.botId) ?? "";
        return s.title.toLowerCase().includes(q) || botName.toLowerCase().includes(q);
      })
      .slice(0, q ? 10 : 5)
      .map<PaletteEntry>((sess) => ({
        id: `sess-${sess.id}`,
        label: sess.title || t("nav.untitledChat"),
        subtitle: botNameById.get(sess.botId) ?? "",
        icon: <IconMessage size={15} stroke={1.65} style={{ color: "var(--pi-ink-tertiary)" }} />,
        run: () => {
          onOpenChange(false);
          onSelectChatView();
          if (client) void selectSession(client, sess.id, locale);
        },
      }));

    const filteredActions = q
      ? quickActions.filter((a) => a.label.toLowerCase().includes(q))
      : quickActions;
    const filteredWorkbench = q
      ? workbenchActions.filter((a) => a.label.toLowerCase().includes(q))
      : workbenchActions;

    const rawGroups: PaletteGroup[] = [
      { id: "recent", label: t("palette.recentChats"), items: sessions },
      { id: "actions", label: t("palette.actions"), items: filteredActions },
      { id: "workbench", label: t("palette.workbench"), items: filteredWorkbench },
    ].filter((g) => g.items.length > 0);

    const flat = rawGroups.flatMap((g) => g.items);
    return { groups: rawGroups, flatEntries: flat };
  }, [
    activeSessionId,
    botNameById,
    bots,
    client,
    draftBotId,
    isElectron,
    locale,
    onOpenChange,
    onOpenCreateBot,
    onOpenSettings,
    onSelectChatView,
    onSelectFeatureView,
    onSelectSuperView,
    openPanel,
    platform,
    query,
    selectSession,
    startDraftSession,
    summariesById,
    t,
  ]);

  useEffect(() => {
    setActiveIndex(0);
  }, [query]);

  useEffect(() => {
    const el = itemRefs.current[activeIndex];
    if (el) {
      el.scrollIntoView({ block: "nearest" });
    }
  }, [activeIndex, flatEntries.length]);

  let flatCursor = 0;

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          className="pi-dialog-overlay fixed inset-0 z-50"
          style={{ background: "var(--pi-scrim)" }}
        />
        <DialogPrimitive.Content
          aria-describedby={undefined}
          className="pi-popover pi-dialog-content fixed top-[16%] left-1/2 z-50 flex w-[min(540px,calc(100vw-32px))] -translate-x-1/2 flex-col overflow-hidden p-0 outline-none"
        >
          <DialogPrimitive.Title className="sr-only">
            {t("nav.searchShort")}
          </DialogPrimitive.Title>

          <div
            className="flex h-12 shrink-0 items-center gap-2.5 px-4"
            style={{ borderBottom: "1px solid var(--pi-canvas-border)" }}
          >
            <BrandIcon
              name="action/search"
              size={15.5}
              className="shrink-0"
              style={{ color: "var(--pi-ink-tertiary)" }}
            />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (flatEntries.length === 0) return;
                if (e.key === "ArrowDown") {
                  e.preventDefault();
                  setActiveIndex((i) => (i + 1) % flatEntries.length);
                } else if (e.key === "ArrowUp") {
                  e.preventDefault();
                  setActiveIndex((i) => (i - 1 + flatEntries.length) % flatEntries.length);
                } else if (e.key === "Enter" && !e.nativeEvent.isComposing && e.keyCode !== 229) {
                  e.preventDefault();
                  flatEntries[activeIndex]?.run();
                }
              }}
              placeholder={t("nav.searchPlaceholder")}
              className="min-w-0 flex-1 bg-transparent text-[14px] outline-none placeholder:opacity-55"
              style={{ color: "var(--pi-ink)" }}
            />
            {query && (
              <button
                type="button"
                aria-label={t("common.close")}
                onClick={() => setQuery("")}
                className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full transition-colors hover:bg-[var(--pi-hover)]"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                <BrandIcon name="navigation/close" size={12.5} />
              </button>
            )}
          </div>

          <div className="max-h-[360px] overflow-y-auto px-2 py-2">
            {flatEntries.length === 0 ? (
              <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("files.searchEmpty")}
              </div>
            ) : (
              <div className="space-y-3">
                {groups.map((grp) => (
                  <div key={grp.id} className="space-y-0.5">
                    <div
                      className="px-2.5 pt-1 pb-0.5 text-[11px] font-semibold tracking-wide"
                      style={{ color: "var(--pi-ink-tertiary)" }}
                    >
                      {grp.label}
                    </div>
                    {grp.items.map((entry) => {
                      const idx = flatCursor++;
                      const selected = idx === activeIndex;
                      return (
                        <button
                          key={entry.id}
                          ref={(el) => {
                            itemRefs.current[idx] = el;
                          }}
                          type="button"
                          onMouseEnter={() => setActiveIndex(idx)}
                          onClick={entry.run}
                          className="flex w-full items-center justify-between gap-2 rounded-[9px] px-2.5 py-2 text-left text-[13px] transition-colors"
                          style={{
                            background: selected ? "var(--pi-wash)" : "transparent",
                            color: "var(--pi-ink)",
                          }}
                        >
                          <div className="flex min-w-0 items-center gap-2.5">
                            {entry.icon}
                            <span className="truncate font-medium">{entry.label}</span>
                          </div>
                          <div className="flex shrink-0 items-center gap-2">
                            {entry.subtitle && (
                              <span className="text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                                {entry.subtitle}
                              </span>
                            )}
                            {entry.shortcut && (
                              <kbd
                                className="rounded px-1.5 py-0.5 text-[10.5px]"
                                style={{
                                  background: "var(--pi-chrome)",
                                  border: "1px solid var(--pi-canvas-border)",
                                  color: "var(--pi-ink-tertiary)",
                                  fontFamily: "var(--pi-font-mono)",
                                }}
                              >
                                {entry.shortcut}
                              </kbd>
                            )}
                          </div>
                        </button>
                      );
                    })}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div
            className="flex h-8 shrink-0 items-center justify-between px-4 text-[11px]"
            style={{
              background: "var(--pi-chrome)",
              borderTop: "1px solid var(--pi-canvas-border)",
              color: "var(--pi-ink-tertiary)",
            }}
          >
            <div className="flex items-center gap-3">
              <span>↑↓ {t("palette.hintNav")}</span>
              <span>Enter {t("palette.hintOpen")}</span>
            </div>
            <span>Esc {t("palette.hintClose")}</span>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
