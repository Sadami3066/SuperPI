import { useMemo, useState } from "react";
import type { ChatTurn } from "../../api/types";

export function MessageAnchorRail({
  turns,
  activeTurnId,
  onJumpToIndex,
}: {
  turns: ChatTurn[];
  activeTurnId?: string | null;
  onJumpToIndex: (turnIndex: number) => void;
}) {
  const [hoveredId, setHoveredId] = useState<string | null>(null);

  const userAnchors = useMemo(() => {
    const anchors: Array<{ index: number; number: number; id: string; preview: string }> = [];
    for (let i = 0; i < turns.length; i++) {
      const turn = turns[i];
      if (turn.role === "user") {
        const clean = turn.rawText.replace(/\s+/g, " ").trim();
        const num = anchors.length + 1;
        anchors.push({
          index: i,
          number: num,
          id: turn.id,
          preview: clean ? (clean.length > 64 ? `${clean.slice(0, 64)}…` : clean) : `#${num}`,
        });
      }
    }
    return anchors;
  }, [turns]);

  if (userAnchors.length < 3) return null;

  const visibleAnchors = userAnchors.slice(-18);

  return (
    <nav
      aria-label="Message index"
      className="pi-anchor-rail pointer-events-none absolute top-1/2 right-2 z-10 -translate-y-1/2 flex-col items-end"
    >
      <div
        className="pointer-events-auto flex flex-col items-end gap-0.5 py-1.5"
        onMouseLeave={() => setHoveredId(null)}
      >
        {visibleAnchors.map((a) => {
          const isCurrent = activeTurnId === a.id;
          const isHovered = hoveredId === a.id;
          return (
            <div key={a.id} className="relative flex items-center justify-end">
              {isHovered && (
                <div
                  role="tooltip"
                  className="pi-popover pointer-events-none absolute right-full mr-2.5 w-max max-w-[224px] rounded-[10px] px-2.5 py-1.5 text-left"
                >
                  <div className="flex items-start gap-1.5">
                    <span
                      className="mt-[1px] shrink-0 text-[10.5px] font-semibold tabular-nums"
                      style={{ color: "var(--pi-accent)" }}
                    >
                      #{a.number}
                    </span>
                    <span
                      className="line-clamp-2 text-[12px] leading-[1.45]"
                      style={{ color: "var(--pi-ink)" }}
                    >
                      {a.preview}
                    </span>
                  </div>
                </div>
              )}

              <button
                type="button"
                aria-label={`#${a.number} ${a.preview}`}
                onClick={() => onJumpToIndex(a.index)}
                onMouseEnter={() => setHoveredId(a.id)}
                onFocus={() => setHoveredId(a.id)}
                onBlur={() => setHoveredId((prev) => (prev === a.id ? null : prev))}
                className="flex h-3.5 w-6 items-center justify-end pr-0.5 outline-none"
              >
                <span
                  className="block rounded-full transition-[width,height,background-color,opacity] duration-150"
                  style={{
                    width: isCurrent ? 16 : isHovered ? 14 : 9,
                    height: isCurrent || isHovered ? 2.5 : 2,
                    background: isCurrent
                      ? "var(--pi-accent)"
                      : isHovered
                        ? "var(--pi-ink)"
                        : "var(--pi-ink-tertiary)",
                    opacity: isCurrent ? 1 : isHovered ? 0.9 : 0.45,
                  }}
                />
              </button>
            </div>
          );
        })}
      </div>
    </nav>
  );
}
