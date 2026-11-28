import { IconSparkles } from "@tabler/icons-react";
import type { ThemePresetId } from "../../platform/theme";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import { PiBrandMark } from "../shared/BrandAssets";

const SUPER_PILL_STARS = [
  { left: "14%", top: "24%", size: 2, delay: "0s" },
  { left: "34%", top: "74%", size: 1.8, delay: "0.55s" },
  { left: "68%", top: "22%", size: 2.2, delay: "0.95s" },
  { left: "86%", top: "68%", size: 1.8, delay: "0.3s" },
];

function resolveSuperPillTheme(preset: ThemePresetId, customAccent: string | null): {
  bg: string;
  glow: string;
} {
  if (customAccent) {
    return {
      bg: `linear-gradient(105deg, color-mix(in srgb, ${customAccent} 38%, #0b0b12) 0%, color-mix(in srgb, ${customAccent} 58%, #0b0b12) 55%, color-mix(in srgb, ${customAccent} 66%, #141422) 100%)`,
      glow: `0 2px 12px -2px color-mix(in srgb, ${customAccent} 48%, transparent), inset 0 1px 0 rgba(255, 255, 255, 0.22)`,
    };
  }
  switch (preset) {
    case "tokyo-night":
    case "catppuccin":
    case "dracula":
      return {
        bg: "linear-gradient(105deg, #1e1b4b 0%, #4c1d95 48%, #6d28d9 82%, #7e22ce 100%)",
        glow: "0 2px 14px -2px rgba(126, 34, 206, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.24)",
      };
    case "night-owl":
    case "solarized":
    case "nord":
      return {
        bg: "linear-gradient(105deg, #0b2942 0%, #035388 48%, #0f6d66 84%, #0b7a75 100%)",
        glow: "0 2px 14px -2px rgba(15, 118, 110, 0.48), inset 0 1px 0 rgba(255, 255, 255, 0.24)",
      };
    case "gruvbox":
    case "rose-pine":
      return {
        bg: "linear-gradient(105deg, #431407 0%, #881337 46%, #b43434 82%, #b91c3c 100%)",
        glow: "0 2px 14px -2px rgba(185, 28, 60, 0.46), inset 0 1px 0 rgba(255, 255, 255, 0.22)",
      };
    default:
      return {
        bg: "linear-gradient(105deg, #111116 0%, #7f1d3a 46%, #a91038 82%, #be123c 100%)",
        glow: "0 2px 14px -2px rgba(190, 18, 60, 0.48), inset 0 1px 0 rgba(255, 255, 255, 0.22)",
      };
  }
}

export function ModeSwitcher({
  activeView,
  onChange,
  compact = false,
  className = "",
}: {
  activeView: "chat" | "super";
  onChange: (view: "chat" | "super") => void;
  compact?: boolean;
  className?: string;
}) {
  const dark = useSettingsStore((s) => s.dark);
  const themePreset = useSettingsStore((s) => s.themePreset);
  const customAccent = useSettingsStore((s) => s.customAccent);
  const activeWorkersCount = useSuperStore((s) => {
    const workers = s.state?.workers;
    if (!workers || workers.length === 0) return 0;
    let count = 0;
    for (const w of workers) {
      if (
        w.status === "running" ||
        w.status === "working" ||
        w.status === "waiting_input" ||
        w.status === "waiting_approval" ||
        w.status === "waiting"
      ) {
        count++;
      }
    }
    return count;
  });

  const isSuper = activeView === "super";
  const superVisual = resolveSuperPillTheme(themePreset, customAccent);

  return (
    <div
      role="tablist"
      aria-label="Workspace mode"
      className={`pi-no-drag relative grid shrink-0 grid-cols-2 items-center rounded-full p-[2px] select-none ${
        compact ? "h-[26px] w-[184px]" : "h-[30px] w-full max-w-[204px]"
      } ${className}`}
      style={{
        background: "var(--pi-wash)",
        border: "1px solid var(--pi-canvas-border)",
      }}
    >
      <span
        aria-hidden="true"
        className={`pointer-events-none absolute top-[2px] bottom-[2px] left-[2px] w-[calc(50%-2px)] overflow-hidden rounded-full motion-reduce:transition-none ${
          isSuper ? "pi-effort-ultra" : ""
        }`}
        style={{
          background: isSuper ? undefined : "var(--pi-canvas)",
          backgroundImage: isSuper ? superVisual.bg : undefined,
          transform: isSuper ? "translateX(100%)" : "translateX(0%)",
          boxShadow: isSuper ? superVisual.glow : "0 1px 2px rgba(0, 0, 0, 0.08)",
          transition:
            "transform 240ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 220ms ease, background-color 180ms ease",
        }}
      >
        {isSuper &&
          SUPER_PILL_STARS.map((star, idx) => (
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
      </span>

      <button
        type="button"
        role="tab"
        aria-selected={!isSuper}
        onClick={() => onChange("chat")}
        className="relative z-10 flex h-full items-center justify-center gap-1.5 rounded-full text-[11.5px] font-semibold whitespace-nowrap transition-colors duration-150 motion-safe:active:scale-[0.97]"
        style={{
          color: !isSuper ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
        }}
      >
        <PiBrandMark size={12.5} dark={dark} className="rounded-full" />
        <span>PI</span>
      </button>

      <button
        type="button"
        role="tab"
        aria-selected={isSuper}
        onClick={() => onChange("super")}
        className="relative z-10 flex h-full items-center justify-center gap-1 rounded-full text-[11.5px] font-semibold whitespace-nowrap transition-colors duration-150 motion-safe:active:scale-[0.97]"
        style={{
          color: isSuper ? "#FAFAFC" : "var(--pi-ink-tertiary)",
        }}
      >
        <IconSparkles size={12} stroke={2} />
        <span>Super PI</span>
        {activeWorkersCount > 0 && (
          <span
            className="inline-flex h-3.5 min-w-3.5 items-center justify-center rounded-full px-1 text-[9.5px] font-bold tabular-nums"
            style={{
              background: isSuper ? "rgba(255, 255, 255, 0.24)" : "var(--pi-accent)",
              color: isSuper ? "#FFFFFF" : "var(--pi-on-accent)",
            }}
          >
            {activeWorkersCount}
          </span>
        )}
      </button>
    </div>
  );
}
