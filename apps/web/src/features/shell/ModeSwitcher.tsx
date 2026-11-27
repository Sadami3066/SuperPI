import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import { BrandIcon, PiBrandMark } from "../shared/BrandAssets";

export function ModeSwitcher({
  activeView,
  onChange,
}: {
  activeView: "chat" | "super";
  onChange: (view: "chat" | "super") => void;
}) {
  const dark = useSettingsStore((s) => s.dark);
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

  return (
    <div
      role="tablist"
      aria-label="Workspace mode"
      className="pi-no-drag relative grid h-[30px] w-[168px] shrink-0 grid-cols-2 items-center rounded-full p-[2px] select-none"
      style={{
        background: "var(--pi-wash)",
        border: "1px solid var(--pi-canvas-border)",
      }}
    >
      <span
        aria-hidden="true"
        className="pointer-events-none absolute top-[2px] bottom-[2px] left-[2px] w-[calc(50%-2px)] rounded-full motion-reduce:transition-none"
        style={{
          background: isSuper ? "var(--pi-accent)" : "var(--pi-canvas)",
          transform: isSuper ? "translateX(100%)" : "translateX(0%)",
          boxShadow: "0 1px 2px rgba(0, 0, 0, 0.08)",
          transition:
            "transform 240ms cubic-bezier(0.22, 1, 0.36, 1), background-color 180ms ease",
        }}
      />

      <button
        type="button"
        role="tab"
        aria-selected={!isSuper}
        onClick={() => onChange("chat")}
        className="relative z-10 flex h-full items-center justify-center gap-1.5 rounded-full text-[12px] font-semibold whitespace-nowrap transition-colors duration-150 motion-safe:active:scale-[0.97]"
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
        className="relative z-10 flex h-full items-center justify-center gap-1 rounded-full text-[12px] font-semibold whitespace-nowrap transition-colors duration-150 motion-safe:active:scale-[0.97]"
        style={{
          color: isSuper ? "var(--pi-on-accent)" : "var(--pi-ink-tertiary)",
        }}
      >
        <BrandIcon name="ai/agent" size={12.5} />
        <span>Super PI</span>
        {activeWorkersCount > 0 && (
          <span
            className="inline-flex h-4 min-w-4 items-center justify-center rounded-full px-1 text-[10px] font-bold tabular-nums"
            style={{
              background: isSuper
                ? "color-mix(in oklab, var(--pi-on-accent) 20%, transparent)"
                : "var(--pi-accent)",
              color: isSuper ? "var(--pi-on-accent)" : "var(--pi-on-accent)",
            }}
          >
            {activeWorkersCount}
          </span>
        )}
      </button>
    </div>
  );
}
