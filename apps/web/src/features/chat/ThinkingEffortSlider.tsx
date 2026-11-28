import { useCallback, useMemo, useRef, useState } from "react";
import type { ThinkingLevel } from "../../api/types";
import { type I18nKey, useI18n } from "../../i18n";
import type { ThemePresetId } from "../../platform/theme";
import { useSettingsStore } from "../../stores/settingsStore";

const THINKING_KEY_MAP: Record<ThinkingLevel, I18nKey> = {
  off: "model.thinkingOff",
  minimal: "model.thinkingMinimal",
  low: "model.thinkingLow",
  medium: "model.thinkingMedium",
  high: "model.thinkingHigh",
  xhigh: "model.thinkingXhigh",
  max: "model.thinkingMax",
};

interface LevelVisual {
  labelColor: string;
  trackBg: string;
  glow: string;
  effectClass: string;
  stars: boolean;
}

function resolveThemeEffortPalette(
  preset: ThemePresetId,
  customAccent: string | null,
  dark: boolean,
): Record<ThinkingLevel, LevelVisual> {
  if (customAccent) {
    const a = "var(--pi-accent)";
    return {
      off: {
        labelColor: "var(--pi-ink-tertiary)",
        trackBg: "linear-gradient(90deg, var(--pi-hairline) 0%, var(--pi-wash) 100%)",
        glow: "none",
        effectClass: "pi-effort-off",
        stars: false,
      },
      minimal: {
        labelColor: "var(--pi-ink-secondary)",
        trackBg: `linear-gradient(90deg, color-mix(in srgb, ${a} 36%, var(--pi-ink-tertiary)) 0%, color-mix(in srgb, ${a} 54%, var(--pi-wash)) 100%)`,
        glow: `0 0 10px color-mix(in srgb, ${a} 20%, transparent)`,
        effectClass: "pi-effort-minimal",
        stars: false,
      },
      low: {
        labelColor: a,
        trackBg: `linear-gradient(90deg, color-mix(in srgb, ${a} 66%, var(--pi-ink-secondary)) 0%, color-mix(in srgb, ${a} 88%, #ffffff) 100%)`,
        glow: `0 0 12px color-mix(in srgb, ${a} 26%, transparent)`,
        effectClass: "pi-effort-low",
        stars: false,
      },
      medium: {
        labelColor: a,
        trackBg: `linear-gradient(90deg, color-mix(in srgb, ${a} 85%, #000000) 0%, ${a} 100%)`,
        glow: `0 0 14px color-mix(in srgb, ${a} 34%, transparent)`,
        effectClass: "pi-effort-medium",
        stars: false,
      },
      high: {
        labelColor: a,
        trackBg: `linear-gradient(90deg, color-mix(in srgb, ${a} 78%, #111113) 0%, ${a} 60%, color-mix(in srgb, ${a} 78%, #ffffff) 100%)`,
        glow: `0 0 16px color-mix(in srgb, ${a} 40%, transparent)`,
        effectClass: "pi-effort-high",
        stars: false,
      },
      xhigh: {
        labelColor: a,
        trackBg: `linear-gradient(90deg, color-mix(in srgb, ${a} 68%, #111113) 0%, ${a} 52%, color-mix(in srgb, ${a} 68%, #ffffff) 100%)`,
        glow: `0 0 18px color-mix(in srgb, ${a} 46%, transparent)`,
        effectClass: "pi-effort-ultra",
        stars: true,
      },
      max: {
        labelColor: a,
        trackBg: `linear-gradient(90deg, color-mix(in srgb, ${a} 60%, #09090b) 0%, ${a} 48%, color-mix(in srgb, ${a} 62%, #ffffff) 100%)`,
        glow: `0 0 20px color-mix(in srgb, ${a} 50%, transparent)`,
        effectClass: "pi-effort-ultra",
        stars: true,
      },
    };
  }

  if (preset === "gruvbox" || preset === "rose-pine") {
    return {
      off: {
        labelColor: "var(--pi-ink-tertiary)",
        trackBg: "linear-gradient(90deg, #a8a29e 0%, #d6d3d1 100%)",
        glow: "none",
        effectClass: "pi-effort-off",
        stars: false,
      },
      minimal: {
        labelColor: dark ? "#d6c5b3" : "#574c42",
        trackBg: "linear-gradient(90deg, #78716c 0%, #a8a29e 100%)",
        glow: "0 0 10px rgba(120, 113, 108, 0.22)",
        effectClass: "pi-effort-minimal",
        stars: false,
      },
      low: {
        labelColor: dark ? "#f6c177" : "#9a4424",
        trackBg: "linear-gradient(90deg, #9f3a20 0%, #d97706 100%)",
        glow: "0 0 12px rgba(180, 70, 42, 0.28)",
        effectClass: "pi-effort-low",
        stars: false,
      },
      medium: {
        labelColor: dark ? "#fe8019" : "#9a2d1b",
        trackBg: "linear-gradient(90deg, #8b2819 0%, #c2410c 60%, #ea580c 100%)",
        glow: "0 0 14px rgba(185, 56, 34, 0.34)",
        effectClass: "pi-effort-medium",
        stars: false,
      },
      high: {
        labelColor: dark ? "#eb6f92" : "#881337",
        trackBg: "linear-gradient(90deg, #6c1824 0%, #b44e6b 52%, #e11d48 100%)",
        glow: "0 0 16px rgba(225, 29, 72, 0.36)",
        effectClass: "pi-effort-high",
        stars: false,
      },
      xhigh: {
        labelColor: dark ? "#ebbcba" : "#881337",
        trackBg: "linear-gradient(90deg, #52101a 0%, #9f1239 45%, #d946ef 82%, #f472b6 100%)",
        glow: "0 0 18px rgba(225, 29, 72, 0.42)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
      max: {
        labelColor: dark ? "#f5c2e7" : "#7f1d1d",
        trackBg: "linear-gradient(90deg, #3f0a13 0%, #9f1239 40%, #e11d48 74%, #fb7185 100%)",
        glow: "0 0 20px rgba(244, 63, 94, 0.46)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
    };
  }

  if (preset === "night-owl" || preset === "solarized" || preset === "nord") {
    return {
      off: {
        labelColor: "var(--pi-ink-tertiary)",
        trackBg: "linear-gradient(90deg, #7c96a4 0%, #a8beca 100%)",
        glow: "none",
        effectClass: "pi-effort-off",
        stars: false,
      },
      minimal: {
        labelColor: dark ? "#9ec5d8" : "#2e5466",
        trackBg: "linear-gradient(90deg, #4b7387 0%, #739eb2 100%)",
        glow: "0 0 10px rgba(75, 115, 135, 0.24)",
        effectClass: "pi-effort-minimal",
        stars: false,
      },
      low: {
        labelColor: dark ? "#7fdbca" : "#0e657c",
        trackBg: "linear-gradient(90deg, #0e7490 0%, #2aa198 100%)",
        glow: "0 0 12px rgba(14, 116, 144, 0.28)",
        effectClass: "pi-effort-low",
        stars: false,
      },
      medium: {
        labelColor: dark ? "#82aaff" : "#005a9e",
        trackBg: "linear-gradient(90deg, #005a9e 0%, #0284c7 62%, #0d9488 100%)",
        glow: "0 0 14px rgba(2, 132, 199, 0.34)",
        effectClass: "pi-effort-medium",
        stars: false,
      },
      high: {
        labelColor: dark ? "#88c0d0" : "#0c5678",
        trackBg: "linear-gradient(90deg, #0c4a6e 0%, #268bd2 50%, #2aa198 100%)",
        glow: "0 0 16px rgba(13, 148, 136, 0.38)",
        effectClass: "pi-effort-high",
        stars: false,
      },
      xhigh: {
        labelColor: dark ? "#7fdbca" : "#0b4f6c",
        trackBg: "linear-gradient(90deg, #0c3d59 0%, #268bd2 46%, #0d9488 80%, #2dd4bf 100%)",
        glow: "0 0 18px rgba(45, 212, 191, 0.42)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
      max: {
        labelColor: dark ? "#99f6e4" : "#0a4560",
        trackBg: "linear-gradient(90deg, #0a3147 0%, #0369a1 44%, #0f766e 76%, #2dd4bf 100%)",
        glow: "0 0 20px rgba(45, 212, 191, 0.46)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
    };
  }

  if (preset === "tokyo-night" || preset === "catppuccin" || preset === "dracula") {
    return {
      off: {
        labelColor: "var(--pi-ink-tertiary)",
        trackBg: "linear-gradient(90deg, #8b9bb0 0%, #b8c4d4 100%)",
        glow: "none",
        effectClass: "pi-effort-off",
        stars: false,
      },
      minimal: {
        labelColor: dark ? "#a9b1d6" : "#454e6e",
        trackBg: "linear-gradient(90deg, #475569 0%, #64748b 100%)",
        glow: "0 0 10px rgba(71, 85, 105, 0.24)",
        effectClass: "pi-effort-minimal",
        stars: false,
      },
      low: {
        labelColor: dark ? "#7aa2f7" : "#2e6be6",
        trackBg: "linear-gradient(90deg, #2563eb 0%, #6366f1 100%)",
        glow: "0 0 12px rgba(99, 102, 241, 0.28)",
        effectClass: "pi-effort-low",
        stars: false,
      },
      medium: {
        labelColor: dark ? "#bb9af7" : "#6d28d9",
        trackBg: "linear-gradient(90deg, #3730a3 0%, #6366f1 56%, #8b5cf6 100%)",
        glow: "0 0 14px rgba(139, 92, 246, 0.36)",
        effectClass: "pi-effort-medium",
        stars: false,
      },
      high: {
        labelColor: dark ? "#cba6f7" : "#7c3aed",
        trackBg: "linear-gradient(90deg, #2e1065 0%, #6d28d9 50%, #a855f7 100%)",
        glow: "0 0 16px rgba(168, 85, 247, 0.4)",
        effectClass: "pi-effort-high",
        stars: false,
      },
      xhigh: {
        labelColor: dark ? "#d8b4fe" : "#6b21a8",
        trackBg: "linear-gradient(90deg, #1e1b4b 0%, #6d28d9 46%, #a855f7 80%, #f472b6 100%)",
        glow: "0 0 18px rgba(192, 132, 252, 0.44)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
      max: {
        labelColor: dark ? "#f5d0fe" : "#581c87",
        trackBg: "linear-gradient(90deg, #17153b 0%, #6d28d9 40%, #c026d3 76%, #f472b6 100%)",
        glow: "0 0 20px rgba(232, 121, 249, 0.48)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
    };
  }

  // Default "pi" (PI Mono): sharp obsidian -> crimson (猩红)
  return {
    off: {
      labelColor: "var(--pi-ink-tertiary)",
      trackBg: "linear-gradient(90deg, #8e8e96 0%, #c4c4cc 100%)",
      glow: "none",
      effectClass: "pi-effort-off",
      stars: false,
    },
    minimal: {
      labelColor: dark ? "#d4d4dc" : "#3f3f46",
      trackBg: "linear-gradient(90deg, #52525b 0%, #71717a 100%)",
      glow: "0 0 10px rgba(82, 82, 91, 0.2)",
      effectClass: "pi-effort-minimal",
      stars: false,
    },
    low: {
      labelColor: dark ? "#fda4af" : "#881337",
      trackBg: "linear-gradient(90deg, #3f3f46 0%, #881337 100%)",
      glow: "0 0 12px rgba(159, 18, 57, 0.26)",
      effectClass: "pi-effort-low",
      stars: false,
    },
    medium: {
      labelColor: dark ? "#fb7185" : "#9f1239",
      trackBg: "linear-gradient(90deg, #27272a 0%, #881337 52%, #be123c 100%)",
      glow: "0 0 14px rgba(190, 18, 60, 0.34)",
      effectClass: "pi-effort-medium",
      stars: false,
    },
    high: {
      labelColor: dark ? "#fb7185" : "#be123c",
      trackBg: "linear-gradient(90deg, #18181b 0%, #9f1239 48%, #e11d48 100%)",
      glow: "0 0 16px rgba(225, 29, 72, 0.4)",
      effectClass: "pi-effort-high",
      stars: false,
    },
    xhigh: {
      labelColor: dark ? "#fda4af" : "#be123c",
      trackBg: "linear-gradient(90deg, #141416 0%, #881337 38%, #dc2626 74%, #f43f5e 100%)",
      glow: "0 0 18px rgba(225, 29, 72, 0.46)",
      effectClass: "pi-effort-ultra",
      stars: true,
    },
    max: {
      labelColor: dark ? "#fecdd3" : "#9f1239",
      trackBg: "linear-gradient(90deg, #09090b 0%, #881337 35%, #dc2626 70%, #fb7185 100%)",
      glow: "0 0 20px rgba(244, 63, 94, 0.5)",
      effectClass: "pi-effort-ultra",
      stars: true,
    },
  };
}

const STAR_DOTS = [
  { left: "10%", top: "30%", size: 2.5, delay: "0s" },
  { left: "19%", top: "68%", size: 2, delay: "0.4s" },
  { left: "30%", top: "40%", size: 2.5, delay: "0.9s" },
  { left: "42%", top: "62%", size: 2, delay: "0.2s" },
  { left: "53%", top: "28%", size: 3, delay: "1.1s" },
  { left: "63%", top: "70%", size: 2, delay: "0.6s" },
  { left: "73%", top: "38%", size: 2.5, delay: "1.3s" },
  { left: "82%", top: "64%", size: 2, delay: "0.3s" },
];

export function ThinkingEffortSlider({
  levels,
  value,
  dark,
  onChange,
}: {
  levels: ThinkingLevel[];
  value: ThinkingLevel;
  dark: boolean;
  onChange: (next: ThinkingLevel) => void;
}) {
  const { t } = useI18n();
  const themePreset = useSettingsStore((s) => s.themePreset);
  const customAccent = useSettingsStore((s) => s.customAccent);
  const trackRef = useRef<HTMLDivElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [previewIdx, setPreviewIdx] = useState<number | null>(null);
  const previewIdxRef = useRef<number | null>(null);

  const palette = useMemo(
    () => resolveThemeEffortPalette(themePreset, customAccent, dark),
    [customAccent, dark, themePreset],
  );

  const count = levels.length;
  const committedIdx = Math.max(0, levels.indexOf(value));
  const activeIdx = dragging && previewIdx !== null ? previewIdx : committedIdx;
  const activeLevel = levels[activeIdx] ?? "off";
  const visual = palette[activeLevel];
  const ratio = count > 1 ? activeIdx / (count - 1) : 1;

  const indexFromClientX = useCallback(
    (clientX: number): number | null => {
      const el = trackRef.current;
      if (!el || count <= 1) return null;
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0) return null;
      const pad = 13;
      const usable = Math.max(1, rect.width - pad * 2);
      const frac = Math.min(1, Math.max(0, (clientX - rect.left - pad) / usable));
      return Math.round(frac * (count - 1));
    },
    [count],
  );

  if (count <= 1) return null;

  const labelText = t(THINKING_KEY_MAP[activeLevel]);

  return (
    <div className="space-y-2 px-1 py-1 select-none">
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11.5px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("model.thinkingLevel")}
        </span>
        <span
          className="text-[12px] font-semibold transition-colors duration-200"
          style={{ color: visual.labelColor }}
        >
          {labelText}
        </span>
      </div>

      <div
        ref={trackRef}
        role="slider"
        tabIndex={0}
        aria-label={t("model.thinkingLevel")}
        aria-valuemin={0}
        aria-valuemax={count - 1}
        aria-valuenow={activeIdx}
        aria-valuetext={labelText}
        onPointerDown={(e) => {
          e.preventDefault();
          e.currentTarget.setPointerCapture(e.pointerId);
          setDragging(true);
          const idx = indexFromClientX(e.clientX);
          if (idx !== null) {
            previewIdxRef.current = idx;
            setPreviewIdx(idx);
          }
        }}
        onPointerMove={(e) => {
          if (!dragging) return;
          const idx = indexFromClientX(e.clientX);
          if (idx !== null && idx !== previewIdxRef.current) {
            previewIdxRef.current = idx;
            setPreviewIdx(idx);
          }
        }}
        onPointerUp={(e) => {
          if (e.currentTarget.hasPointerCapture(e.pointerId)) {
            e.currentTarget.releasePointerCapture(e.pointerId);
          }
          const finalIdx = indexFromClientX(e.clientX) ?? previewIdxRef.current;
          setDragging(false);
          setPreviewIdx(null);
          previewIdxRef.current = null;
          if (finalIdx !== null) {
            const next = levels[finalIdx];
            if (next && next !== value) {
              onChange(next);
            }
          }
        }}
        onPointerCancel={() => {
          setDragging(false);
          setPreviewIdx(null);
          previewIdxRef.current = null;
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowRight" || e.key === "ArrowUp") {
            e.preventDefault();
            const next = levels[Math.min(count - 1, activeIdx + 1)];
            if (next) onChange(next);
          } else if (e.key === "ArrowLeft" || e.key === "ArrowDown") {
            e.preventDefault();
            const next = levels[Math.max(0, activeIdx - 1)];
            if (next) onChange(next);
          } else if (e.key === "Home") {
            e.preventDefault();
            onChange(levels[0]);
          } else if (e.key === "End") {
            e.preventDefault();
            onChange(levels[count - 1]);
          }
        }}
        className="relative h-[26px] w-full cursor-pointer rounded-full outline-none focus-visible:ring-2 focus-visible:ring-[var(--pi-accent)]"
        style={{
          background: "var(--pi-wash)",
          border: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div className="pointer-events-none absolute inset-0 flex items-center justify-between px-[11px]">
          {levels.map((lvl, idx) => (
            <span
              key={lvl}
              className="h-1 w-1 rounded-full transition-opacity duration-200"
              style={{
                background: "var(--pi-ink-tertiary)",
                opacity: idx > activeIdx ? 0.35 : 0,
              }}
            />
          ))}
        </div>

        <div
          className={`pi-effort-fill ${visual.effectClass} pointer-events-none absolute top-0 bottom-0 left-0 overflow-hidden rounded-full ${
            dragging ? "" : "transition-[width] duration-200 ease-out"
          }`}
          style={{
            width: `calc(26px + (100% - 26px) * ${ratio})`,
            backgroundImage: visual.trackBg,
            boxShadow: visual.glow,
          }}
        >
          {visual.stars &&
            STAR_DOTS.map((star, idx) => (
              <span
                key={idx}
                className="pi-effort-star"
                style={{
                  left: star.left,
                  top: star.top,
                  width: star.size,
                  height: star.size,
                  animationDelay: star.delay,
                }}
              />
            ))}
        </div>

        <div
          className={`pointer-events-none absolute top-[3px] h-[20px] w-[20px] rounded-full bg-white ${
            dragging ? "scale-105" : "transition-[left,transform] duration-200 ease-out"
          }`}
          style={{
            left: `calc(3px + (100% - 26px) * ${ratio})`,
            boxShadow: "0 1px 4px rgba(0,0,0,0.22), 0 0 0 0.5px rgba(0,0,0,0.08)",
          }}
        />
      </div>
    </div>
  );
}
