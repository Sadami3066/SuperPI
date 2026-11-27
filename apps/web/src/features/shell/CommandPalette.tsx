import { type ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { IconMessage, IconWorld } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { formatShortcut } from "../../platform/shortcuts";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Dialog, Input } from "../../ui";
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

  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const isElectron = Boolean(desktopEnv?.isElectron);
  const platform = desktopEnv?.platform;
  const locale = useSettingsStore((s) => s.locale);
  const bots = useBotStore((s) => s.bots);
  const summariesById = useSessionStore((s) => s.summariesById);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const selectSession = useSessionStore((s) => s.selectSession);
  const createSession = useSessionStore((s) => s.createSession);
  const openPanel = useWorkbenchStore((s) => s.openPanel);

  const botNameById = useMemo(() => new Map(bots.map((b) => [b.id, b.name])), [bots]);

  const entries = useMemo(() => {
    const q = query.trim().toLowerCase();
    const actions: PaletteEntry[] = [
      {
        id: "act-new-chat",
        label: t("nav.newChat"),
        shortcut: formatShortcut(["mod", "n"], platform),
        icon: <BrandIcon name="chat/new_chat" size={15} style={{ color: "var(--pi-ink-secondary)" }} />,
        run: () => {
          onOpenChange(false);
          onSelectChatView();
          const targetBot = summariesById[activeSessionId]?.botId ?? bots[0]?.id;
          if (client && targetBot) void createSession(client, targetBot, locale);
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
        id: "act-memory",
        label: t("settings.navMemory"),
        icon: <BrandIcon name="ai/memory" size={15} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("memory");
        },
      },
      {
        id: "act-automations",
        label: t("settings.navAutomations"),
        icon: <BrandIcon name="system/timer" size={15} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("automations");
        },
      },
      {
        id: "act-capabilities",
        label: t("nav.capabilities"),
        icon: <BrandIcon name="ai/skills" size={15} />,
        run: () => {
          onOpenChange(false);
          onSelectFeatureView("capabilities");
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
      actions.push({
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

    const filteredActions = q
      ? actions.filter((a) => a.label.toLowerCase().includes(q))
      : actions;

    const sessions = Object.values(summariesById)
      .filter((s) => !s.archived)
      .sort((a, b) => b.updatedAtMs - a.updatedAtMs)
      .filter((s) => {
        if (!q) return true;
        const botName = botNameById.get(s.botId) ?? "";
        return s.title.toLowerCase().includes(q) || botName.toLowerCase().includes(q);
      })
      .slice(0, 12)
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

    return [...filteredActions, ...sessions];
  }, [
    activeSessionId,
    botNameById,
    bots,
    client,
    createSession,
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
  }, [activeIndex, entries.length]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange} maxWidthClass="max-w-[520px]">
      <div className="space-y-3">
        <div className="relative">
          <BrandIcon
            name="action/search"
            size={15}
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
            style={{ color: "var(--pi-ink-tertiary)" }}
          />
          <Input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (entries.length === 0) return;
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setActiveIndex((i) => (i + 1) % entries.length);
              } else if (e.key === "ArrowUp") {
                e.preventDefault();
                setActiveIndex((i) => (i - 1 + entries.length) % entries.length);
              } else if (e.key === "Enter" && !e.nativeEvent.isComposing) {
                e.preventDefault();
                entries[activeIndex]?.run();
              }
            }}
            placeholder={t("nav.searchPlaceholder")}
            className="pl-9"
          />
        </div>

        <div className="max-h-[340px] space-y-0.5 overflow-y-auto">
          {entries.length === 0 ? (
            <div className="py-6 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("files.searchEmpty")}
            </div>
          ) : (
            entries.map((entry, idx) => {
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
                  className="flex w-full items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
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
                          background: "var(--pi-paper)",
                          border: "0.5px solid var(--pi-hairline)",
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
            })
          )}
        </div>
      </div>
    </Dialog>
  );
}
