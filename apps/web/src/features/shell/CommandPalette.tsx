import { useMemo, useState } from "react";
import { IconMessage, IconPlus, IconSearch, IconSettings } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Dialog, Input } from "../../ui";

export function CommandPalette({
  open,
  onOpenChange,
  onOpenSettings,
  onOpenCreateBot,
  onSelectChatView,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onOpenSettings: () => void;
  onOpenCreateBot: () => void;
  onSelectChatView: () => void;
}) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");
  const client = useConnectionStore((s) => s.client);
  const locale = useSettingsStore((s) => s.locale);
  const bots = useBotStore((s) => s.bots);
  const summariesById = useSessionStore((s) => s.summariesById);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const selectSession = useSessionStore((s) => s.selectSession);
  const createSession = useSessionStore((s) => s.createSession);

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
              onOpenSettings();
            }}
            className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
          >
            <IconSettings size={15} stroke={1.75} style={{ color: "var(--pi-ink-secondary)" }} />
            <span className="font-medium">{t("nav.settings")}</span>
          </button>

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
