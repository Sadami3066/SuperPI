import type { ThemePresetId } from "../../platform/theme";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import { BrandIcon, PiBrandMark } from "../shared/BrandAssets";

function superPillVisual(preset: ThemePresetId, customAccent: string | null) {
  if (customAccent) {
    return {
      bg: "linear-gradient(115deg, color-mix(in srgb, var(--pi-accent) 82%, #111113) 0%, var(--pi-accent) 62%, color-mix(in srgb, var(--pi-accent) 76%, #ffffff) 100%)",
      text: "var(--pi-on-accent)",
      glow: "0 2px 10px color-mix(in srgb, var(--pi-accent) 36%, transparent)",
      ring: "color-mix(in srgb, var(--pi-accent) 48%, transparent)",
    };
  }
  switch (preset) {
    case "paper":
      return {
        bg: "linear-gradient(115deg, #6c1824 0%, #b91c1c 56%, #e11d48 100%)",
        text: "#FFFDF9",
        glow: "0 2px 10px rgba(225, 29, 72, 0.34)",
        ring: "rgba(225, 29, 72, 0.44)",
      };
    case "santorini":
    case "oceanic":
      return {
        bg: "linear-gradient(115deg, #0c4a6e 0%, #0067b8 55%, #0d9488 100%)",
        text: "#F4FBFC",
        glow: "0 2px 10px rgba(13, 148, 136, 0.34)",
        ring: "rgba(13, 148, 136, 0.42)",
      };
    case "slate":
    case "classic-blue":
      return {
        bg: "linear-gradient(115deg, #172554 0%, #1d4ed8 58%, #0284c7 100%)",
        text: "#FFFFFF",
        glow: "0 2px 10px rgba(29, 78, 216, 0.34)",
        ring: "rgba(29, 78, 216, 0.42)",
      };
    default:
      return {
        bg: "linear-gradient(115deg, #18181b 0%, #9f1239 58%, #e11d48 100%)",
        text: "#FFFFFF",
        glow: "0 2px 10px rgba(225, 29, 72, 0.36)",
        ring: "rgba(225, 29, 72, 0.46)",
      };
  }
}

export function ModeSwitcher({
  activeView,
  onChange,
}: {
  activeView: "chat" | "super";
  onChange: (view: "chat" | "super") => void;
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
  const superStyle = superPillVisual(themePreset, customAccent);

  return (
    <div
      role="tablist"
      aria-label="Workspace mode"
      className="pi-no-drag relative grid h-[30px] w-[190px] shrink-0 grid-cols-2 items-center rounded-full p-[2px] select-none"
      style={{
        background: "var(--pi-wash)",
        border: isSuper ? `0.5px solid ${superStyle.ring}` : "0.5px solid var(--pi-hairline)",
        boxShadow: isSuper ? `0 0 0 2px color-mix(in srgb, ${superStyle.ring} 28%, transparent)` : "none",
        transition: "border-color 240ms ease, box-shadow 240ms ease",
      }}
    >
      <span
        aria-hidden="true"
        className={`pointer-events-none absolute top-[2px] bottom-[2px] left-[2px] w-[calc(50%-2px)] overflow-hidden rounded-full motion-reduce:transition-none ${
          isSuper ? "pi-mode-super-pill" : ""
        }`}
        style={{
          background: isSuper ? superStyle.bg : "var(--pi-surface)",
          transform: isSuper ? "translateX(100%)" : "translateX(0%)",
          boxShadow: isSuper ? superStyle.glow : "0 1px 2.5px rgba(0, 0, 0, 0.09)",
          transition:
            "transform 260ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 240ms ease, background 240ms ease",
        }}
      />

      <button
        type="button"
        role="tab"
        aria-selected={!isSuper}
        onClick={() => onChange("chat")}
        className="relative z-10 flex h-full items-center justify-center gap-1.5 rounded-full text-[12px] font-semibold whitespace-nowrap transition-colors duration-200 active:scale-[0.97] motion-reduce:transition-none"
        style={{
          color: !isSuper ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
        }}
      >
        <PiBrandMark size={13} dark={dark} className="rounded-full" />
        <span>PI</span>
      </button>

      <button
        type="button"
        role="tab"
        aria-selected={isSuper}
        onClick={() => onChange("super")}
        className="relative z-10 flex h-full items-center justify-center gap-1 rounded-full text-[12px] font-semibold whitespace-nowrap transition-colors duration-200 active:scale-[0.97] motion-reduce:transition-none"
        style={{
          color: isSuper ? superStyle.text : "var(--pi-ink-tertiary)",
        }}
      >
        <BrandIcon name="ai/agent" size={12.5} />
        <span>Super PI</span>
        {activeWorkersCount > 0 && (
          <span
            className="inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[10px] font-bold tabular-nums"
            style={{
              background: isSuper ? "rgba(255, 255, 255, 0.22)" : "var(--pi-accent)",
              color: isSuper ? superStyle.text : "var(--pi-on-accent)",
            }}
          >
            {activeWorkersCount}
          </span>
        )}
      </button>
    </div>
  );
}
