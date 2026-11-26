import { useMemo } from "react";
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
  const userAnchors = useMemo(() => {
    const anchors: Array<{ index: number; id: string; label: string }> = [];
    for (let i = 0; i < turns.length; i++) {
      const turn = turns[i];
      if (turn.role === "user") {
        const clean = turn.rawText.replace(/\s+/g, " ").trim();
        anchors.push({
          index: i,
          id: turn.id,
          label: clean ? clean.slice(0, 40) : `#${anchors.length + 1}`,
        });
      }
    }
    return anchors;
  }, [turns]);

  if (userAnchors.length < 4) return null;

  return (
    <div
      className="pointer-events-none absolute top-1/2 right-2.5 z-10 hidden -translate-y-1/2 flex-col items-center gap-1.5 sm:flex"
      aria-hidden="false"
    >
      <div
        className="pointer-events-auto flex flex-col items-center gap-1.5 rounded-full px-1 py-2"
        style={{
          background: "var(--pi-surface)",
          border: "0.5px solid var(--pi-hairline)",
          boxShadow: "var(--pi-composer-shadow)",
        }}
      >
        {userAnchors.slice(-16).map((a) => {
          const isCurrent = activeTurnId === a.id;
          return (
            <button
              key={a.id}
              type="button"
              title={a.label}
              aria-label={a.label}
              onClick={() => onJumpToIndex(a.index)}
              className="group flex h-2.5 w-2.5 items-center justify-center rounded-full transition-transform hover:scale-125"
            >
              <span
                className="block rounded-full transition-all"
                style={{
                  width: isCurrent ? 7 : 5,
                  height: isCurrent ? 7 : 5,
                  background: isCurrent ? "var(--pi-accent)" : "var(--pi-ink-tertiary)",
                  opacity: isCurrent ? 1 : 0.45,
                }}
              />
            </button>
          );
        })}
      </div>
    </div>
  );
}
