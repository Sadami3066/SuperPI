import { useMemo, useState } from "react";
import { IconSparkles } from "@tabler/icons-react";
import { normalizeThinkingLevel } from "../../api/mappers";
import type { HostProviderDto, ThinkingLevel } from "../../api/types";
import { useI18n, type I18nKey } from "../../i18n";
import { Button, Input, Popover } from "../../ui";
import { BrandIcon, ProviderIcon } from "../shared/BrandAssets";

const THINKING_ORDER: ThinkingLevel[] = ["off", "minimal", "low", "medium", "high", "xhigh"];

const THINKING_KEY_MAP: Record<ThinkingLevel, I18nKey> = {
  off: "model.thinkingOff",
  minimal: "model.thinkingMinimal",
  low: "model.thinkingLow",
  medium: "model.thinkingMedium",
  high: "model.thinkingHigh",
  xhigh: "model.thinkingXhigh",
};

export function ModelPicker({
  providers,
  currentModel,
  currentThinking,
  dark,
  onSelect,
  onOpenProvidersSettings,
}: {
  providers: HostProviderDto[];
  currentModel: string;
  currentThinking: ThinkingLevel;
  dark: boolean;
  onSelect: (model: string, thinking: ThinkingLevel) => void;
  onOpenProvidersSettings: () => void;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");

  const configuredProviders = useMemo(
    () => providers.filter((p) => p.configured && p.models.length > 0),
    [providers],
  );

  const selectedModelMeta = useMemo(() => {
    for (const p of configuredProviders) {
      for (const m of p.models) {
        if (`${p.id}/${m.id}` === currentModel) {
          return { provider: p, model: m };
        }
      }
    }
    return null;
  }, [configuredProviders, currentModel]);

  const availableThinking = useMemo(() => {
    const raw = selectedModelMeta?.model.thinkingLevels ?? [];
    const parsed = raw
      .map((x) => normalizeThinkingLevel(x))
      .filter((lvl, idx, arr) => arr.indexOf(lvl) === idx);
    return parsed.sort((a, b) => THINKING_ORDER.indexOf(a) - THINKING_ORDER.indexOf(b));
  }, [selectedModelMeta]);

  const filteredProviders = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return configuredProviders;
    return configuredProviders
      .map((p) => ({
        ...p,
        models: p.models.filter(
          (m) =>
            m.id.toLowerCase().includes(q) ||
            m.displayName.toLowerCase().includes(q) ||
            p.name.toLowerCase().includes(q),
        ),
      }))
      .filter((p) => p.models.length > 0);
  }, [configuredProviders, query]);

  const triggerLabel =
    selectedModelMeta?.model.displayName ||
    selectedModelMeta?.model.id ||
    currentModel.split("/").pop() ||
    t("model.select");

  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      side="top"
      align="start"
      className="w-[340px] p-2.5"
      trigger={
        <button
          type="button"
          className="inline-flex h-7 max-w-[220px] items-center gap-1.5 rounded-full px-2.5 text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
          style={{ color: "var(--pi-ink-secondary)" }}
        >
          {selectedModelMeta ? (
            <ProviderIcon
              providerId={selectedModelMeta.provider.id}
              name={selectedModelMeta.provider.name}
              dark={dark}
              size={14}
            />
          ) : (
            <IconSparkles size={13.5} stroke={1.7} />
          )}
          <span className="truncate">{triggerLabel}</span>
          {currentThinking !== "off" && availableThinking.length > 1 && (
            <span
              className="shrink-0 rounded-full px-1.5 py-0.2 text-[10.5px]"
              style={{ background: "var(--pi-wash)", color: "var(--pi-ink-tertiary)" }}
            >
              {t(THINKING_KEY_MAP[currentThinking])}
            </span>
          )}
          <BrandIcon name="navigation/chevron_down" size={12} className="shrink-0 opacity-65" />
        </button>
      }
    >
      {configuredProviders.length === 0 ? (
        <div className="space-y-3 p-3 text-center">
          <div className="text-[13px]" style={{ color: "var(--pi-ink-secondary)" }}>
            {t("model.empty")}
          </div>
          <Button
            variant="primary"
            size="sm"
            onClick={() => {
              setOpen(false);
              onOpenProvidersSettings();
            }}
          >
            {t("model.goProviders")}
          </Button>
        </div>
      ) : (
        <div className="space-y-2.5">
          <div className="relative">
            <BrandIcon
              name="action/search"
              size={14}
              className="pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2"
              style={{ color: "var(--pi-ink-tertiary)" }}
            />
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("model.search")}
              className="h-8 pl-8 text-[12.5px]"
            />
          </div>

          {availableThinking.length > 1 && (
            <div className="space-y-1">
              <div className="px-1 text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("model.thinkingLevel")}
              </div>
              <div className="flex flex-wrap gap-1">
                {availableThinking.map((lvl) => {
                  const active = currentThinking === lvl;
                  return (
                    <button
                      key={lvl}
                      type="button"
                      onClick={() => {
                        if (currentModel) onSelect(currentModel, lvl);
                      }}
                      className="rounded-full px-2.5 py-0.5 text-[11.5px] font-medium transition-colors"
                      style={{
                        background: active ? "var(--pi-accent)" : "var(--pi-wash)",
                        color: active ? "var(--pi-on-accent)" : "var(--pi-ink-secondary)",
                      }}
                    >
                      {t(THINKING_KEY_MAP[lvl])}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          <div data-scrollable className="max-h-[260px] space-y-2 overflow-y-auto pr-0.5">
            {filteredProviders.map((provider) => (
              <div key={provider.id} className="space-y-0.5">
                <div
                  className="flex items-center gap-1.5 px-2 py-1 text-[11px] font-semibold"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  <ProviderIcon
                    providerId={provider.id}
                    name={provider.name}
                    dark={dark}
                    size={13}
                  />
                  <span>{provider.name}</span>
                </div>
                {provider.models.map((mod) => {
                  const full = `${provider.id}/${mod.id}`;
                  const active = full === currentModel;
                  const levels = mod.thinkingLevels.map((l) => normalizeThinkingLevel(l));
                  const nextThinking = levels.includes(currentThinking)
                    ? currentThinking
                    : levels[0] ?? "off";
                  return (
                    <button
                      key={full}
                      type="button"
                      onClick={() => {
                        onSelect(full, nextThinking);
                        setOpen(false);
                      }}
                      className="flex w-full items-center justify-between gap-2 rounded-[9px] px-2.5 py-1.5 text-left text-[12.5px] transition-colors hover:bg-[var(--pi-hover)]"
                      style={{
                        background: active ? "var(--pi-accent-soft)" : "transparent",
                        color: active ? "var(--pi-accent)" : "var(--pi-ink)",
                      }}
                    >
                      <span className="truncate font-medium">{mod.displayName || mod.id}</span>
                      {active && <BrandIcon name="status/confirm" size={14} className="shrink-0" />}
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
      )}
    </Popover>
  );
}
