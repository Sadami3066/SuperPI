import { useEffect, useMemo, useRef, useState } from "react";
import { useI18n, type AppLanguagePreference } from "../../i18n";
import { FONT_STYLES, THEME_PRESETS, type AppearanceMode, type FontStyleId, type ThemePresetId } from "../../platform/theme";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Button, Card, Input, Select, Slider, Switch, Tabs, TabsList, TabsTrigger } from "../../ui";

export function GeneralSettings() {
  const { t, locale } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const themePreset = useSettingsStore((s) => s.themePreset);
  const appearance = useSettingsStore((s) => s.appearance);
  const fontStyle = useSettingsStore((s) => s.fontStyle);
  const languagePref = useSettingsStore((s) => s.languagePref);
  const hostSettings = useSettingsStore((s) => s.hostSettings);

  const setThemePreset = useSettingsStore((s) => s.setThemePreset);
  const setAppearance = useSettingsStore((s) => s.setAppearance);
  const setFontStyle = useSettingsStore((s) => s.setFontStyle);
  const setLanguagePref = useSettingsStore((s) => s.setLanguagePref);
  const patchHostSettings = useSettingsStore((s) => s.patchHostSettings);

  const [fontScaleDraft, setFontScaleDraft] = useState(hostSettings?.chatFontScale ?? 100);
  const [compactDraft, setCompactDraft] = useState(hostSettings?.compactionTriggerPercent ?? 75);
  const [nicknameDraft, setNicknameDraft] = useState(hostSettings?.userNickname ?? "");
  const [nicknameSaved, setNicknameSaved] = useState(false);
  const nicknameTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (nicknameTimerRef.current !== null) clearTimeout(nicknameTimerRef.current);
    };
  }, []);

  useEffect(() => {
    if (hostSettings) {
      setFontScaleDraft(hostSettings.chatFontScale);
      setCompactDraft(hostSettings.compactionTriggerPercent);
      setNicknameDraft(hostSettings.userNickname ?? "");
    }
  }, [hostSettings]);

  const activePreset = THEME_PRESETS[themePreset];
  const fontOptions = useMemo(
    () =>
      FONT_STYLES.map((f) => ({
        value: f.id,
        label: locale === "en" ? f.nameEn : f.nameZh,
      })),
    [locale],
  );

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
    <div className="space-y-5">
      {hostSettings && (
        <Card className="space-y-3 p-5">
          <div className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("settings.profileSection")}
          </div>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {t("settings.nicknameLabel")}
            </span>
            <div className="flex items-center gap-2 sm:w-[280px]">
              <Input
                value={nicknameDraft}
                maxLength={64}
                placeholder={t("settings.nicknamePlaceholder")}
                onChange={(e) => {
                  setNicknameDraft(e.target.value);
                  setNicknameSaved(false);
                }}
              />
              <Button
                variant="secondary"
                size="sm"
                disabled={!client || nicknameDraft.trim() === (hostSettings.userNickname ?? "").trim()}
                onClick={() => {
                  if (!client) return;
                  void patchHostSettings(client, { userNickname: nicknameDraft.trim() }).then(() => {
                    setNicknameSaved(true);
                    if (nicknameTimerRef.current !== null) clearTimeout(nicknameTimerRef.current);
                    nicknameTimerRef.current = setTimeout(() => setNicknameSaved(false), 1500);
                  });
                }}
              >
                {nicknameSaved ? t("bot.saved") : t("common.save")}
              </Button>
            </div>
          </div>
        </Card>
      )}
      <Card className="space-y-4 p-5">
        <div className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
          {t("settings.themePreset")}
        </div>

        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3">
          {(Object.values(THEME_PRESETS) as Array<(typeof THEME_PRESETS)[ThemePresetId]>).map(
            (preset) => {
              const selected = preset.id === themePreset;
              const previewPalette = preset.fixedMode === "dark" ? preset.dark : preset.light;
              return (
                <button
                  key={preset.id}
                  type="button"
                  onClick={() => setThemePreset(preset.id)}
                  className="flex items-center gap-2.5 rounded-[12px] p-2.5 text-left transition-colors"
                  style={{
                    background: selected ? "var(--pi-accent-soft)" : "var(--pi-paper)",
                    border: selected
                      ? "1px solid var(--pi-accent)"
                      : "0.5px solid var(--pi-hairline)",
                  }}
                >
                  <div className="flex shrink-0 -space-x-1">
                    <span
                      className="h-4 w-4 rounded-full border border-black/10"
                      style={{ background: previewPalette.paper }}
                    />
                    <span
                      className="h-4 w-4 rounded-full border border-black/10"
                      style={{ background: previewPalette.accent }}
                    />
                  </div>
                  <span
                    className="truncate text-[13px] font-medium"
                    style={{ color: selected ? "var(--pi-accent)" : "var(--pi-ink)" }}
                  >
                    {locale === "en" ? preset.nameEn : preset.nameZh}
                  </span>
                </button>
              );
            },
          )}
        </div>

        {!activePreset?.fixedMode && (
          <div className="flex items-center justify-between gap-3 pt-1">
            <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {t("settings.appearance")}
            </span>
            <Tabs value={appearance} onValueChange={(v) => setAppearance(v as AppearanceMode)}>
              <TabsList>
                <TabsTrigger value="system">{t("settings.appearanceSystem")}</TabsTrigger>
                <TabsTrigger value="light">{t("settings.appearanceLight")}</TabsTrigger>
                <TabsTrigger value="dark">{t("settings.appearanceDark")}</TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
        )}

        <div className="flex items-center justify-between gap-3">
          <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
            {t("settings.fontStyle")}
          </span>
          <Select
            value={fontStyle}
            onValueChange={(v) => setFontStyle(v as FontStyleId)}
            options={fontOptions}
            className="w-[180px]"
          />
        </div>

        <div className="flex items-center justify-between gap-3">
          <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
            {t("settings.language")}
          </span>
          <Select
            value={languagePref}
            onValueChange={(v) => void setLanguagePref(client, v as AppLanguagePreference)}
            options={languageOptions}
            className="w-[180px]"
          />
        </div>
      </Card>

      {hostSettings && (
        <Card className="space-y-5 p-5">
          <div>
            <div className="mb-2 flex items-center justify-between text-[13.5px]">
              <span className="font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("settings.chatFontScale")}
              </span>
              <span className="font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
                {fontScaleDraft}%
              </span>
            </div>
            <Slider
              value={fontScaleDraft}
              min={85}
              max={140}
              step={5}
              ariaLabel={t("settings.chatFontScale")}
              onValueChange={setFontScaleDraft}
              onValueCommit={(val) => {
                if (client) void patchHostSettings(client, { chatFontScale: val });
              }}
            />
          </div>

          <div>
            <div className="mb-2 flex items-center justify-between text-[13.5px]">
              <span className="font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("settings.compactionThreshold")}
              </span>
              <span className="font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
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

          <div className="flex items-center justify-between gap-3">
            <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {t("settings.sendKey")}
            </span>
            <Select
              value={hostSettings.sendKey === "mod_enter" ? "mod_enter" : "enter"}
              onValueChange={(v) => {
                if (client) void patchHostSettings(client, { sendKey: v });
              }}
              options={sendKeyOptions}
              className="w-[210px]"
            />
          </div>

          <div className="flex items-center justify-between gap-3">
            <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {t("settings.sendWhileRunning")}
            </span>
            <Select
              value={hostSettings.sendWhileRunning === "queue" ? "queue" : "steer"}
              onValueChange={(v) => {
                if (client) void patchHostSettings(client, { sendWhileRunning: v });
              }}
              options={runningSendOptions}
              className="w-[210px]"
            />
          </div>

          <div className="flex items-center justify-between gap-3">
            <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {t("settings.processDetailLabel")}
            </span>
            <Select
              value={hostSettings.processDetail === "compact" ? "compact" : "detailed"}
              onValueChange={(v) => {
                if (client) void patchHostSettings(client, { processDetail: v });
              }}
              options={processDetailOptions}
              className="w-[210px]"
            />
          </div>

          <div className="flex items-center justify-between gap-3">
            <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {t("settings.autoRetry")}
            </span>
            <Switch
              checked={hostSettings.autoRetry}
              ariaLabel={t("settings.autoRetry")}
              onCheckedChange={(checked) => {
                if (client) void patchHostSettings(client, { autoRetry: checked });
              }}
            />
          </div>

          <div className="flex items-center justify-between gap-3">
            <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {t("settings.backgroundWake")}
            </span>
            <Switch
              checked={hostSettings.backgroundJobWake}
              ariaLabel={t("settings.backgroundWake")}
              onCheckedChange={(checked) => {
                if (client) void patchHostSettings(client, { backgroundJobWake: checked });
              }}
            />
          </div>
        </Card>
      )}
    </div>
  );
}
