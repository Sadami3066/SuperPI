import { useMemo, useState } from "react";
import {
  IconFolder,
  IconGitBranch,
  IconMessage,
  IconPlus,
  IconSearch,
  IconSettings,
  IconTerminal2,
  IconWorld,
} from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Dialog, Input } from "../../ui";
import { type SettingsTabId } from "../settings/SettingsModal";
import { BrandIcon } from "../shared/BrandAssets";

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onOpenSettings: (tab?: SettingsTabId) => void;
  onOpenCreateBot: () => void;
  onSelectChatView: () => void;
  onSelectSuperView: () => void;
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
}: CommandPaletteProps) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const client = useConnectionStore((s) => s.client);
  const locale = useSettingsStore((s) => s.locale);
  const bots = useBotStore((s) => s.bots);
  const summariesById = useSessionStore((s) => s.summariesById);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const selectSession = useSessionStore((s) => s.selectSession);
  const createSession = useSessionStore((s) => s.createSession);
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));
  const openPanel = useWorkbenchStore((s) => s.openPanel);

  const botNameById = useMemo(() => {
    const map = new Map<string, string>();
    for (const b of bots) map.set(b.id, b.name);
    return map;
  }, [bots]);

  const items = useMemo(() => {
    const q = query.trim().toLowerCase();
    const sessions = Object.values(summariesById)
      .sort((a, b) => b.updatedAtMs - a.updatedAtMs)
      .filter((s) => {
        if (!q) return true;
        const botName = botNameById.get(s.botId) ?? "";
        return (
          (s.title || t("nav.untitledChat")).toLowerCase().includes(q) ||
          botName.toLowerCase().includes(q)
        );
      })
      .slice(0, 12);

    return sessions;
  }, [botNameById, query, summariesById, t]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange} maxWidthClass="max-w-[520px]">
      <div className="space-y-3">
        <div className="relative">
          <IconSearch
            size={15}
            stroke={1.7}
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
            style={{ color: "var(--pi-ink-tertiary)" }}
          />
          <Input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("nav.searchPlaceholder")}
            className="pl-9"
          />
        </div>

        <div className="max-h-[320px] space-y-1 overflow-y-auto">
          <button
            type="button"
            onClick={() => {
              onOpenChange(false);
              onSelectChatView();
              const targetBot = summariesById[activeSessionId]?.botId ?? bots[0]?.id;
              if (client && targetBot) void createSession(client, targetBot, locale);
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <IconPlus size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
            <span className="font-medium">{t("nav.newChat")}</span>
          </button>

          <button
            type="button"
            onClick={() => {
              onOpenChange(false);
              onSelectSuperView();
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <BrandIcon name="ai/agent" size={15} />
            <span className="font-medium">{t("super.title")}</span>
          </button>

          <button
            type="button"
            onClick={() => {
              onOpenChange(false);
              onOpenCreateBot();
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <IconPlus size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
            <span className="font-medium">{t("nav.createBot")}</span>
          </button>

          <button
            type="button"
            onClick={() => {
              onOpenChange(false);
              onOpenSettings("general");
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <IconSettings size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
            <span className="font-medium">{t("nav.settings")}</span>
          </button>

          {(
            [
              { tab: "memory" as const, icon: "ai/memory", label: t("settings.navMemory") },
              { tab: "automations" as const, icon: "system/timer", label: t("settings.navAutomations") },
              { tab: "skills" as const, icon: "ai/skills", label: t("settings.navSkills") },
              { tab: "mcp" as const, icon: "ai/connectors", label: t("settings.navMcp") },
              { tab: "diagnostics" as const, icon: "status/info", label: t("settings.navDiagnostics") },
            ] as const
          ).map((entry) => (
            <button
              key={entry.tab}
              type="button"
              onClick={() => {
                onOpenChange(false);
                onOpenSettings(entry.tab);
              }}
              className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
            >
              <BrandIcon name={entry.icon} size={15} />
              <span className="font-medium">{entry.label}</span>
            </button>
          ))}

          <button
            type="button"
            onClick={() => {
              onOpenChange(false);
              openPanel("files");
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <IconFolder size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
            <span className="font-medium">{t("wb.filesTitle")}</span>
          </button>

          <button
            type="button"
            onClick={() => {
              onOpenChange(false);
              openPanel("diff");
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <IconGitBranch size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
            <span className="font-medium">{t("wb.diffTitle")}</span>
          </button>

          <button
            type="button"
            onClick={() => {
              onOpenChange(false);
              openPanel("terminal");
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <IconTerminal2 size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
            <span className="font-medium">{t("wb.terminalTitle")}</span>
          </button>

          {isElectron && (
            <button
              type="button"
              onClick={() => {
                onOpenChange(false);
                openPanel("browser");
              }}
              className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
            >
              <IconWorld size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
              <span className="font-medium">{t("wb.browserTitle")}</span>
            </button>
          )}

          {items.length > 0 && (
            <div className="my-1 h-[0.5px]" style={{ background: "var(--pi-hairline)" }} />
          )}

          {items.map((sess) => (
            <button
              key={sess.id}
              type="button"
              onClick={() => {
                onOpenChange(false);
                onSelectChatView();
                if (client) void selectSession(client, sess.id, locale);
              }}
              className="flex w-full items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
            >
              <div className="flex min-w-0 items-center gap-2.5">
                <IconMessage size={15} stroke={1.65} style={{ color: "var(--pi-ink-tertiary)" }} />
                <span className="truncate font-medium" style={{ color: "var(--pi-ink)" }}>
                  {sess.title || t("nav.untitledChat")}
                </span>
              </div>
              <span className="shrink-0 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {botNameById.get(sess.botId) ?? ""}
              </span>
            </button>
          ))}
        </div>
      </div>
    </Dialog>
  );
}
