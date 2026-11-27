import { useEffect, useMemo, useState } from "react";
import { IconCheck } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import {
  FONT_STYLES,
  resolveThemePalette,
  THEME_PRESETS,
  type AppearanceMode,
  type FontStyleId,
  type ThemePresetDefinition,
} from "../../platform/theme";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Button, Select, Slider } from "../../ui";

const ACCENT_SWATCHES = [
  "#2563EB",
  "#006AFF",
  "#7C3AED",
  "#0F766E",
  "#D97706",
  "#DC2626",
];

function ModePreviewCard({
  mode,
  selected,
  label,
  onClick,
}: {
  mode: AppearanceMode;
  selected: boolean;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className="group flex flex-col items-center gap-1.5 outline-none"
    >
      <div
        className="relative h-[52px] w-[78px] overflow-hidden rounded-[10px] transition-all duration-150"
        style={{
          border: selected ? "2px solid var(--pi-accent)" : "1px solid var(--pi-hairline)",
          boxShadow: selected ? "0 0 0 2px var(--pi-accent-soft)" : "none",
        }}
      >
        {mode === "system" ? (
          <div className="flex h-full w-full">
            <div className="flex w-1/2 flex-col gap-1.5 bg-[#F7F7F5] p-2">
              <div className="h-1.5 w-6 rounded-full bg-[#18181B]/75" />
              <div className="h-1 w-8 rounded-full bg-[#18181B]/25" />
              <div className="mt-auto h-2 w-7 rounded bg-[#2563EB]/80" />
            </div>
            <div className="flex w-1/2 flex-col gap-1.5 bg-[#121216] p-2">
              <div className="h-1.5 w-6 rounded-full bg-[#F4F4F6]/80" />
              <div className="h-1 w-8 rounded-full bg-[#F4F4F6]/25" />
              <div className="mt-auto h-2 w-7 rounded bg-[#60A5FA]/80" />
            </div>
          </div>
        ) : mode === "light" ? (
          <div className="flex h-full w-full bg-[#F4F4F2]">
            <div className="w-[22px] border-r border-black/8 bg-[#ECECE8] p-1.5">
              <div className="h-1 w-2.5 rounded-full bg-black/25" />
              <div className="mt-1.5 h-1 w-3 rounded-full bg-black/15" />
            </div>
            <div className="flex flex-1 flex-col gap-1.5 bg-white p-2">
              <div className="h-1.5 w-8 rounded-full bg-[#18181B]/70" />
              <div className="h-1 w-10 rounded-full bg-[#18181B]/20" />
              <div className="mt-auto ml-auto h-2 w-6 rounded-full bg-[#2563EB]/85" />
            </div>
          </div>
        ) : (
          <div className="flex h-full w-full bg-[#09090B]">
            <div className="w-[22px] border-r border-white/10 bg-[#111115] p-1.5">
              <div className="h-1 w-2.5 rounded-full bg-white/30" />
              <div className="mt-1.5 h-1 w-3 rounded-full bg-white/15" />
            </div>
            <div className="flex flex-1 flex-col gap-1.5 bg-[#151519] p-2">
              <div className="h-1.5 w-8 rounded-full bg-white/75" />
              <div className="h-1 w-10 rounded-full bg-white/20" />
              <div className="mt-auto ml-auto h-2 w-6 rounded-full bg-[#60A5FA]/85" />
            </div>
          </div>
        )}
      </div>
      <span
        className="text-[11.5px] font-medium"
        style={{ color: selected ? "var(--pi-ink)" : "var(--pi-ink-tertiary)" }}
      >
        {label}
      </span>
    </button>
  );
}

function ThemePresetCard({
  preset,
  appearance,
  systemDark,
  selected,
  locale,
  onClick,
}: {
  preset: ThemePresetDefinition;
  appearance: AppearanceMode;
  systemDark: boolean;
  selected: boolean;
  locale: "zh-CN" | "en";
  onClick: () => void;
}) {
  const previewDark = preset.fixedMode
    ? preset.fixedMode === "dark"
    : appearance === "dark" || (appearance === "system" && systemDark);
  const p = previewDark ? preset.dark : preset.light;
  const title = locale === "en" ? preset.nameEn : preset.nameZh;
  const cardBorder = previewDark ? "rgba(255,255,255,0.12)" : "rgba(17,17,19,0.09)";

  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className="group flex flex-col gap-2 rounded-[12px] p-2 text-left transition-[background-color,border-color,box-shadow,transform] duration-150 hover:bg-[var(--pi-hover)] motion-safe:active:scale-[0.985]"
      style={{
        background: selected ? "var(--pi-chrome)" : "transparent",
        border: selected ? "1.5px solid var(--pi-accent)" : "1px solid var(--pi-canvas-border)",
        boxShadow: selected ? "0 0 0 2px var(--pi-accent-soft)" : "none",
      }}
    >
      <div
        className="flex h-[58px] w-full overflow-hidden rounded-[8px] p-1.5"
        style={{ background: p.paper, border: `1px solid ${cardBorder}` }}
      >
        <div className="flex w-7 shrink-0 flex-col justify-between pr-1.5">
          <div className="space-y-1">
            <div className="h-1.5 w-4 rounded-full" style={{ background: p.inkTertiary, opacity: 0.55 }} />
            <div className="h-1 w-5 rounded-full" style={{ background: p.inkTertiary, opacity: 0.3 }} />
            <div className="h-1 w-3.5 rounded-full" style={{ background: p.inkTertiary, opacity: 0.3 }} />
          </div>
          <div className="h-1.5 w-3 rounded-full" style={{ background: p.accent, opacity: 0.7 }} />
        </div>
        <div
          className="flex min-w-0 flex-1 flex-col justify-between rounded-[6px] p-1.5"
          style={{ background: p.surface, border: `1px solid ${cardBorder}` }}
        >
          <div className="ml-auto h-2 w-8 rounded-full" style={{ background: p.wash }} />
          <div className="space-y-1">
            <div className="h-1 w-10 rounded-full" style={{ background: p.ink, opacity: 0.65 }} />
            <div className="h-1 w-7 rounded-full" style={{ background: p.inkTertiary, opacity: 0.45 }} />
          </div>
          <div className="flex items-center justify-between pt-0.5">
            <div className="h-1.5 w-6 rounded-full" style={{ background: p.wash }} />
            <div className="h-2.5 w-5 rounded-full" style={{ background: p.accent }} />
          </div>
        </div>
      </div>

      <div className="flex items-center justify-between gap-1 px-0.5">
        <span
          className="truncate text-[12px] font-medium"
          style={{ color: selected ? "var(--pi-ink)" : "var(--pi-ink-secondary)" }}
        >
          {title}
        </span>
        {selected && (
          <span
            className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full"
            style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
          >
            <IconCheck size={10.5} stroke={2.5} />
          </span>
        )}
      </div>
    </button>
  );
}

export function AppearanceSettings() {
  const { t, locale } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const themePreset = useSettingsStore((s) => s.themePreset);
  const appearance = useSettingsStore((s) => s.appearance);
  const fontStyle = useSettingsStore((s) => s.fontStyle);
  const customAccent = useSettingsStore((s) => s.customAccent);
  const hostSettings = useSettingsStore((s) => s.hostSettings);

  const setThemePreset = useSettingsStore((s) => s.setThemePreset);
  const setAppearance = useSettingsStore((s) => s.setAppearance);
  const setFontStyle = useSettingsStore((s) => s.setFontStyle);
  const setCustomAccent = useSettingsStore((s) => s.setCustomAccent);
  const resetAppearance = useSettingsStore((s) => s.resetAppearance);
  const patchHostSettings = useSettingsStore((s) => s.patchHostSettings);

  const [fontScaleDraft, setFontScaleDraft] = useState(hostSettings?.chatFontScale ?? 100);

  useEffect(() => {
    if (hostSettings) {
      setFontScaleDraft(hostSettings.chatFontScale);
    }
  }, [hostSettings]);

  const systemDark =
    typeof window !== "undefined" &&
    Boolean(window.matchMedia?.("(prefers-color-scheme: dark)").matches);

  const activePreset = THEME_PRESETS[themePreset];
  const presetDefinitions = useMemo(
    () => Object.values(THEME_PRESETS) as ThemePresetDefinition[],
    [],
  );

  const fontOptions = useMemo(
    () =>
      FONT_STYLES.map((f) => ({
        value: f.id,
        label: locale === "en" ? f.nameEn : f.nameZh,
      })),
    [locale],
  );

  const activePalette = resolveThemePalette(themePreset, appearance);
  const effectiveHex = customAccent ?? activePalette.accent;

  return (
    <div className="space-y-8">
      <section className="space-y-1">
        <h3 className="pb-2 text-[12px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("settings.sectionVisualStyle")}
        </h3>

        <div className="divide-y divide-[var(--pi-hairline)]">
          {!activePreset?.fixedMode && (
            <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
              <div className="min-w-0">
                <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  {t("settings.appearance")}
                </div>
                <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("settings.appearanceModeDesc")}
                </div>
              </div>

              <div className="flex shrink-0 items-center gap-2.5">
                <ModePreviewCard
                  mode="system"
                  selected={appearance === "system"}
                  label={t("settings.appearanceSystem")}
                  onClick={() => setAppearance("system")}
                />
                <ModePreviewCard
                  mode="light"
                  selected={appearance === "light"}
                  label={t("settings.appearanceLight")}
                  onClick={() => setAppearance("light")}
                />
                <ModePreviewCard
                  mode="dark"
                  selected={appearance === "dark"}
                  label={t("settings.appearanceDark")}
                  onClick={() => setAppearance("dark")}
                />
              </div>
            </div>
          )}

          <div className="space-y-3 py-4">
            <div className="min-w-0">
              <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("settings.themePreset")}
              </div>
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("settings.themePresetDesc")}
              </div>
            </div>

            <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-3">
              {presetDefinitions.map((preset) => (
                <ThemePresetCard
                  key={preset.id}
                  preset={preset}
                  appearance={appearance}
                  systemDark={systemDark}
                  selected={themePreset === preset.id}
                  locale={locale}
                  onClick={() => setThemePreset(preset.id)}
                />
              ))}
            </div>
          </div>

          <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
            <div className="min-w-0">
              <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("settings.accentColor")}
              </div>
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("settings.accentColorDesc")}
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => setCustomAccent(null)}
                className="rounded-full px-2.5 py-1 text-[11.5px] font-medium transition-colors"
                style={{
                  background: !customAccent ? "var(--pi-wash)" : "transparent",
                  color: !customAccent ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                  border: "0.5px solid var(--pi-hairline)",
                }}
              >
                {t("settings.accentDefault")}
              </button>

              <div className="flex items-center gap-1.5">
                {ACCENT_SWATCHES.map((hex) => {
                  const selected = customAccent?.toLowerCase() === hex.toLowerCase();
                  return (
                    <button
                      key={hex}
                      type="button"
                      aria-label={hex}
                      title={hex}
                      onClick={() => setCustomAccent(hex)}
                      className="h-5 w-5 rounded-full transition-transform hover:scale-110"
                      style={{
                        background: hex,
                        boxShadow: selected
                          ? "0 0 0 2px var(--pi-surface), 0 0 0 3.5px var(--pi-ink)"
                          : "inset 0 0 0 1px rgba(0,0,0,0.12)",
                      }}
                    />
                  );
                })}
              </div>

              <label
                className="inline-flex h-7 cursor-pointer items-center gap-1.5 rounded-[8px] px-2 text-[11.5px]"
                style={{
                  background: "var(--pi-chrome)",
                  border: "1px solid var(--pi-canvas-border)",
                  color: "var(--pi-ink-secondary)",
                  fontFamily: "var(--pi-font-mono)",
                }}
              >
                <span
                  className="h-3.5 w-3.5 rounded-full"
                  style={{
                    background: effectiveHex,
                    border: "1px solid var(--pi-canvas-border)",
                  }}
                />
                <input
                  type="color"
                  value={effectiveHex}
                  onChange={(e) => setCustomAccent(e.target.value)}
                  className="sr-only"
                />
                <span>{effectiveHex.toUpperCase()}</span>
              </label>
            </div>
          </div>
        </div>
      </section>

      <section className="space-y-1">
        <h3 className="pb-2 text-[12px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("settings.sectionTypography")}
        </h3>

        <div className="divide-y divide-[var(--pi-hairline)]">
          <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
            <div className="min-w-0">
              <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("settings.fontStyle")}
              </div>
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("settings.fontStyleDesc")}
              </div>
            </div>

            <Select
              value={fontStyle}
              onValueChange={(v) => setFontStyle(v as FontStyleId)}
              options={fontOptions}
              className="w-[176px]"
            />
          </div>

          {hostSettings && (
            <div className="space-y-3 py-4">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("settings.chatFontScale")}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.chatFontScaleDesc")}
                  </div>
                </div>
                <span
                  className="shrink-0 text-[13px] font-semibold tabular-nums"
                  style={{ color: "var(--pi-ink-secondary)" }}
                >
                  {fontScaleDraft}%
                </span>
              </div>
              <Slider
                value={fontScaleDraft}
                min={85}
                max={140}
                step={5}
                ariaLabel={t("settings.chatFontScale")}
                onValueChange={(val) => {
                  setFontScaleDraft(val);
                  if (typeof document !== "undefined") {
                    document.documentElement.style.setProperty("--pi-chat-scale", String(val / 100));
                  }
                }}
                onValueCommit={(val) => {
                  if (client) void patchHostSettings(client, { chatFontScale: val });
                }}
              />
            </div>
          )}
        </div>
      </section>

      <div
        className="flex justify-end pt-4"
        style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
      >
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            resetAppearance();
            if (client && hostSettings && hostSettings.chatFontScale !== 100) {
              setFontScaleDraft(100);
              void patchHostSettings(client, { chatFontScale: 100 });
            }
          }}
        >
          {t("settings.resetAppearance")}
        </Button>
      </div>
    </div>
  );
}
