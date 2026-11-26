import { IconFolderOpen, IconSettings } from "@tabler/icons-react";
import { useI18n } from "../i18n";
import { revealInFileManager } from "../platform/bridge";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useSettingsStore } from "../stores/settingsStore";
import { Badge, Button, Card } from "../ui";
import type { WorkbenchPanelContext } from "./registry";

export default function SessionInspectorPanel({
  context,
}: {
  context: WorkbenchPanelContext;
}) {
  const { t } = useI18n();
  const bot = useBotStore((s) => s.bots.find((b) => b.id === context.botId) ?? s.bots[0]);
  const transcript = useSessionStore((s) =>
    context.sessionId ? s.transcriptsById[context.sessionId] : undefined,
  );
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));

  if (!bot) return null;

  const activeModel =
    transcript?.model ||
    bot.defaultModel ||
    (hostSettings ? `${hostSettings.defaultProvider}/${hostSettings.defaultModel}` : "—");
  const permKey = `perm.${bot.permissionMode || "workspace_auto"}` as const;

  return (
    <div className="space-y-4">
      <Card className="space-y-3 p-4">
        <div className="flex items-center justify-between gap-2">
          <div className="truncate text-[14.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {bot.name}
          </div>
          {bot.isRemote && (
            <Badge tone={bot.online ? "success" : "neutral"}>
              {bot.online ? t("nav.online") : t("nav.offline")}
            </Badge>
          )}
        </div>
        {bot.intro && (
          <p className="text-[12.5px] leading-[1.55]" style={{ color: "var(--pi-ink-secondary)" }}>
            {bot.intro}
          </p>
        )}

        <div className="space-y-2 pt-1 text-[12.5px]">
          <div>
            <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("wb.model")}
            </div>
            <div
              className="mt-0.5 truncate font-medium"
              style={{ color: "var(--pi-ink)", fontFamily: "var(--pi-font-mono)" }}
            >
              {activeModel}
            </div>
          </div>

          {!bot.isRemote && (
            <div>
              <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("wb.permission")}
              </div>
              <div className="mt-0.5" style={{ color: "var(--pi-ink)" }}>
                {t(permKey as Parameters<typeof t>[0])}
              </div>
            </div>
          )}

          {bot.deviceName && (
            <div>
              <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("wb.remoteDevice")}
              </div>
              <div className="mt-0.5" style={{ color: "var(--pi-ink)" }}>
                {bot.deviceName}
              </div>
            </div>
          )}

          {bot.cwd && !bot.isRemote && (
            <div>
              <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("wb.path")}
              </div>
              <div
                className="mt-0.5 break-all rounded-[8px] px-2.5 py-1.5 text-[11.5px]"
                style={{
                  background: "var(--pi-paper)",
                  color: "var(--pi-ink-secondary)",
                  fontFamily: "var(--pi-font-mono)",
                }}
              >
                {bot.cwd}
              </div>
              {isElectron && (
                <Button
                  variant="secondary"
                  size="sm"
                  className="mt-2 w-full"
                  onClick={() => void revealInFileManager(bot.cwd)}
                >
                  <IconFolderOpen size={14} stroke={1.7} />
                  <span>{t("wb.revealDir")}</span>
                </Button>
              )}
            </div>
          )}
        </div>
      </Card>

      <div
        className="flex items-start gap-2 rounded-[12px] p-3 text-[12px] leading-[1.55]"
        style={{ background: "var(--pi-paper)", color: "var(--pi-ink-tertiary)" }}
      >
        <IconSettings size={14} stroke={1.65} className="mt-0.5 shrink-0" />
        <span>{t("wb.futureHint")}</span>
      </div>
    </div>
  );
}
