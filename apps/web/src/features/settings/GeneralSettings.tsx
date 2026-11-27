import { useEffect, useMemo, useState } from "react";
import { useI18n, type AppLanguagePreference } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Select, Slider, Switch } from "../../ui";

export function GeneralSettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const languagePref = useSettingsStore((s) => s.languagePref);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const setLanguagePref = useSettingsStore((s) => s.setLanguagePref);
  const patchHostSettings = useSettingsStore((s) => s.patchHostSettings);

  const [compactDraft, setCompactDraft] = useState(hostSettings?.compactionTriggerPercent ?? 75);

  useEffect(() => {
    if (hostSettings) {
      setCompactDraft(hostSettings.compactionTriggerPercent);
    }
  }, [hostSettings]);

  const languageOptions = useMemo(
    () => [
      { value: "system", label: t("settings.langSystem") },
      { value: "zh-CN", label: "简体中文" },
      { value: "en", label: "English" },
    ],
    [t],
  );

  const sendKeyOptions = useMemo(
    () => [
      { value: "enter", label: t("settings.sendEnter") },
      { value: "mod_enter", label: t("settings.sendModEnter") },
    ],
    [t],
  );

  const runningSendOptions = useMemo(
    () => [
      { value: "steer", label: t("settings.steer") },
      { value: "queue", label: t("settings.queue") },
    ],
    [t],
  );

  const processDetailOptions = useMemo(
    () => [
      { value: "detailed", label: t("settings.processDetailed") },
      { value: "compact", label: t("settings.processCompact") },
    ],
    [t],
  );

  return (
    <div className="space-y-8">
      <section className="space-y-1">
        <h3 className="pb-2 text-[12px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("settings.sectionChatBehavior")}
        </h3>

        <div className="divide-y divide-[var(--pi-hairline)]">
          <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
            <div className="min-w-0">
              <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("settings.language")}
              </div>
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("settings.langDesc")}
              </div>
            </div>
            <Select
              value={languagePref}
              onValueChange={(v) => void setLanguagePref(client, v as AppLanguagePreference)}
              options={languageOptions}
              className="w-[190px]"
            />
          </div>

          {hostSettings && (
            <>
              <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
                <div className="min-w-0">
                  <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("settings.sendKey")}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.sendKeyDesc")}
                  </div>
                </div>
                <Select
                  value={hostSettings.sendKey === "mod_enter" ? "mod_enter" : "enter"}
                  onValueChange={(v) => {
                    if (client) void patchHostSettings(client, { sendKey: v });
                  }}
                  options={sendKeyOptions}
                  className="w-[190px]"
                />
              </div>

              <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
                <div className="min-w-0">
                  <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("settings.sendWhileRunning")}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.sendWhileRunningDesc")}
                  </div>
                </div>
                <Select
                  value={hostSettings.sendWhileRunning === "queue" ? "queue" : "steer"}
                  onValueChange={(v) => {
                    if (client) void patchHostSettings(client, { sendWhileRunning: v });
                  }}
                  options={runningSendOptions}
                  className="w-[190px]"
                />
              </div>

              <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
                <div className="min-w-0">
                  <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("settings.processDetailLabel")}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.processDetailDesc")}
                  </div>
                </div>
                <Select
                  value={hostSettings.processDetail === "compact" ? "compact" : "detailed"}
                  onValueChange={(v) => {
                    if (client) void patchHostSettings(client, { processDetail: v });
                  }}
                  options={processDetailOptions}
                  className="w-[190px]"
                />
              </div>
            </>
          )}
        </div>
      </section>

      {hostSettings && (
        <section className="space-y-1">
          <h3 className="pb-2 text-[12px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("settings.sectionReliability")}
          </h3>

          <div className="divide-y divide-[var(--pi-hairline)]">
            <div className="space-y-3 py-4">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("settings.compactionThreshold")}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.compactionDesc")}
                  </div>
                </div>
                <span
                  className="shrink-0 text-[13px] font-semibold tabular-nums"
                  style={{ color: "var(--pi-ink-secondary)" }}
                >
                  {compactDraft}%
                </span>
              </div>
              <Slider
                value={compactDraft}
                min={50}
                max={90}
                step={5}
                ariaLabel={t("settings.compactionThreshold")}
                onValueChange={setCompactDraft}
                onValueCommit={(val) => {
                  if (client) void patchHostSettings(client, { compactionTriggerPercent: val });
                }}
              />
            </div>

            <div className="flex items-center justify-between gap-3 py-4">
              <div className="min-w-0">
                <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  {t("settings.autoRetry")}
                </div>
                <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("settings.autoRetryDesc")}
                </div>
              </div>
              <Switch
                checked={hostSettings.autoRetry}
                ariaLabel={t("settings.autoRetry")}
                onCheckedChange={(checked) => {
                  if (client) void patchHostSettings(client, { autoRetry: checked });
                }}
              />
            </div>

            <div className="flex items-center justify-between gap-3 py-4">
              <div className="min-w-0">
                <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  {t("settings.backgroundWake")}
                </div>
                <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("settings.backgroundWakeDesc")}
                </div>
              </div>
              <Switch
                checked={hostSettings.backgroundJobWake}
                ariaLabel={t("settings.backgroundWake")}
                onCheckedChange={(checked) => {
                  if (client) void patchHostSettings(client, { backgroundJobWake: checked });
                }}
              />
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
