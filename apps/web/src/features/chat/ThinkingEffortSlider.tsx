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

  if (preset === "paper") {
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
        labelColor: dark ? "#f0a57e" : "#9a4424",
        trackBg: "linear-gradient(90deg, #9f3a20 0%, #c85a32 100%)",
        glow: "0 0 12px rgba(180, 70, 42, 0.28)",
        effectClass: "pi-effort-low",
        stars: false,
      },
      medium: {
        labelColor: dark ? "#f39278" : "#9a2d1b",
        trackBg: "linear-gradient(90deg, #8b2819 0%, #b93822 60%, #dc4a2e 100%)",
        glow: "0 0 14px rgba(185, 56, 34, 0.34)",
        effectClass: "pi-effort-medium",
        stars: false,
      },
      high: {
        labelColor: dark ? "#fb7185" : "#881337",
        trackBg: "linear-gradient(90deg, #6c1824 0%, #b91c1c 52%, #e11d48 100%)",
        glow: "0 0 16px rgba(225, 29, 72, 0.36)",
        effectClass: "pi-effort-high",
        stars: false,
      },
      xhigh: {
        labelColor: dark ? "#fda4af" : "#881337",
        trackBg: "linear-gradient(90deg, #52101a 0%, #9f1239 45%, #dc2626 82%, #fb7185 100%)",
        glow: "0 0 18px rgba(225, 29, 72, 0.42)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
      max: {
        labelColor: dark ? "#fda4af" : "#7f1d1d",
        trackBg: "linear-gradient(90deg, #3f0a13 0%, #9f1239 42%, #e11d48 78%, #fb7185 100%)",
        glow: "0 0 20px rgba(244, 63, 94, 0.46)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
    };
  }

  if (preset === "santorini" || preset === "oceanic") {
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
        labelColor: dark ? "#67c7d9" : "#0e657c",
        trackBg: "linear-gradient(90deg, #0e7490 0%, #22a3b8 100%)",
        glow: "0 0 12px rgba(14, 116, 144, 0.28)",
        effectClass: "pi-effort-low",
        stars: false,
      },
      medium: {
        labelColor: dark ? "#7cb2e8" : "#005a9e",
        trackBg: "linear-gradient(90deg, #005a9e 0%, #0284c7 62%, #0d9488 100%)",
        glow: "0 0 14px rgba(2, 132, 199, 0.34)",
        effectClass: "pi-effort-medium",
        stars: false,
      },
      high: {
        labelColor: dark ? "#5eead4" : "#0c5678",
        trackBg: "linear-gradient(90deg, #0c4a6e 0%, #0067b8 50%, #0d9488 100%)",
        glow: "0 0 16px rgba(13, 148, 136, 0.38)",
        effectClass: "pi-effort-high",
        stars: false,
      },
      xhigh: {
        labelColor: dark ? "#5eead4" : "#0b4f6c",
        trackBg: "linear-gradient(90deg, #0c3d59 0%, #0067b8 46%, #0d9488 80%, #2dd4bf 100%)",
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

  if (preset === "slate" || preset === "classic-blue") {
    return {
      off: {
        labelColor: "var(--pi-ink-tertiary)",
        trackBg: "linear-gradient(90deg, #8b9bb0 0%, #b8c4d4 100%)",
        glow: "none",
        effectClass: "pi-effort-off",
        stars: false,
      },
      minimal: {
        labelColor: dark ? "#b4c8e0" : "#334155",
        trackBg: "linear-gradient(90deg, #475569 0%, #64748b 100%)",
        glow: "0 0 10px rgba(71, 85, 105, 0.24)",
        effectClass: "pi-effort-minimal",
        stars: false,
      },
      low: {
        labelColor: dark ? "#7db5f8" : "#1e4b8f",
        trackBg: "linear-gradient(90deg, #2563eb 0%, #60a5fa 100%)",
        glow: "0 0 12px rgba(37, 99, 235, 0.26)",
        effectClass: "pi-effort-low",
        stars: false,
      },
      medium: {
        labelColor: dark ? "#70b0ff" : "#1d4ed8",
        trackBg: "linear-gradient(90deg, #1e3a8a 0%, #1d4ed8 60%, #3b82f6 100%)",
        glow: "0 0 14px rgba(29, 78, 216, 0.34)",
        effectClass: "pi-effort-medium",
        stars: false,
      },
      high: {
        labelColor: dark ? "#93c5fd" : "#1e3a8a",
        trackBg: "linear-gradient(90deg, #172554 0%, #1d4ed8 52%, #0ea5e9 100%)",
        glow: "0 0 16px rgba(14, 165, 233, 0.36)",
        effectClass: "pi-effort-high",
        stars: false,
      },
      xhigh: {
        labelColor: dark ? "#bae6fd" : "#172554",
        trackBg: "linear-gradient(90deg, #0f172a 0%, #1d4ed8 46%, #0284c7 80%, #38bdf8 100%)",
        glow: "0 0 18px rgba(56, 189, 248, 0.42)",
        effectClass: "pi-effort-ultra",
        stars: true,
      },
      max: {
        labelColor: dark ? "#e0f2fe" : "#0f172a",
        trackBg: "linear-gradient(90deg, #0b1220 0%, #1e40af 44%, #0284c7 78%, #7dd3fc 100%)",
        glow: "0 0 20px rgba(125, 211, 252, 0.45)",
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

  const palette = useMemo(
    () => resolveThemeEffortPalette(themePreset, customAccent, dark),
    [customAccent, dark, themePreset],
  );

  const count = levels.length;
  const activeIdx = Math.max(0, levels.indexOf(value));
  const activeLevel = levels[activeIdx] ?? "off";
  const visual = palette[activeLevel];
  const ratio = count > 1 ? activeIdx / (count - 1) : 1;

  const commitFromClientX = useCallback(
    (clientX: number) => {
      const el = trackRef.current;
      if (!el || count <= 1) return;
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0) return;
      const pad = 13;
      const usable = Math.max(1, rect.width - pad * 2);
      const frac = Math.min(1, Math.max(0, (clientX - rect.left - pad) / usable));
      const idx = Math.round(frac * (count - 1));
      const next = levels[idx];
      if (next && next !== value) {
        onChange(next);
      }
    },
    [count, levels, onChange, value],
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
          commitFromClientX(e.clientX);
        }}
        onPointerMove={(e) => {
          if (!dragging) return;
          commitFromClientX(e.clientX);
        }}
        onPointerUp={(e) => {
          if (e.currentTarget.hasPointerCapture(e.pointerId)) {
            e.currentTarget.releasePointerCapture(e.pointerId);
          }
          setDragging(false);
        }}
        onPointerCancel={() => setDragging(false)}
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
