import { useEffect, useMemo, useState } from "react";
import { parseTodoPayload, type TodoItem } from "../../api/todo";
import { useI18n } from "../../i18n";
import { BrandIcon } from "../shared/BrandAssets";

export function InlineTodoCard({
  payload,
  todos: directTodos,
  streaming = false,
}: {
  payload?: string | null;
  todos?: TodoItem[];
  streaming?: boolean;
}) {
  const { t } = useI18n();
  const visibleTodos = useMemo(
    () => directTodos ?? parseTodoPayload(payload),
    [directTodos, payload],
  );
  const doneCount = visibleTodos.filter((item) => item.status === "completed").length;
  const allDone = visibleTodos.length > 0 && doneCount === visibleTodos.length;
  const [userToggled, setUserToggled] = useState(false);
  const [expanded, setExpanded] = useState(() => streaming || !allDone);

  useEffect(() => {
    if (!userToggled) {
      setExpanded(streaming || !allDone);
    }
  }, [allDone, streaming, userToggled]);

  if (visibleTodos.length === 0) return null;

  const inProgressItem = visibleTodos.find((item) => item.status === "in_progress");
  const progressPct = Math.round((doneCount / visibleTodos.length) * 100);

  return (
    <div className="my-2 w-full">
      <div
        className="overflow-hidden rounded-[12px]"
        style={{
          background: "var(--pi-chrome)",
          border: "1px solid var(--pi-canvas-border)",
        }}
      >
        <button
          type="button"
          onClick={() => {
            setUserToggled(true);
            setExpanded((v) => !v);
          }}
          className="flex w-full items-center justify-between gap-2.5 px-3 py-2 text-left transition-colors hover:bg-[var(--pi-hover)]"
        >
          <div className="flex min-w-0 items-center gap-2">
            {allDone ? (
              <BrandIcon
                name="status/confirm"
                size={14}
                style={{ color: "var(--pi-success)" }}
              />
            ) : inProgressItem ? (
              <span
                className="h-2 w-2 shrink-0 rounded-full"
                style={{ background: "var(--pi-accent)" }}
              />
            ) : (
              <span
                className="h-2 w-2 shrink-0 rounded-full border"
                style={{ borderColor: "var(--pi-ink-tertiary)" }}
              />
            )}
            <span
              className="truncate text-[12.5px] font-medium"
              style={{ color: allDone && !expanded ? "var(--pi-ink-tertiary)" : "var(--pi-ink)" }}
            >
              {!expanded && inProgressItem ? inProgressItem.content : t("todo.title")}
            </span>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <span
              className="text-[11.5px] font-medium tabular-nums"
              style={{ color: allDone ? "var(--pi-success)" : "var(--pi-ink-tertiary)" }}
            >
              {t("todo.progress", { done: doneCount, total: visibleTodos.length })}
            </span>
            <BrandIcon
              name="navigation/chevron_down"
              size={13}
              style={{
                color: "var(--pi-ink-tertiary)",
                transform: expanded ? "rotate(180deg)" : "rotate(0deg)",
                transition: "transform 160ms ease",
              }}
            />
          </div>
        </button>

        <div className="h-[2px] w-full" style={{ background: "var(--pi-wash)" }}>
          <div
            className="h-full transition-[width] duration-200 ease-out"
            style={{
              width: `${progressPct}%`,
              background: allDone ? "var(--pi-success)" : "var(--pi-accent)",
            }}
          />
        </div>

        <div className="pi-collapse" data-open={expanded ? "true" : "false"} aria-hidden={!expanded}>
          <div className="pi-collapse-inner">
            <div
              className="space-y-1 px-3 py-2"
              style={{
                background: "var(--pi-canvas)",
              }}
            >
              {visibleTodos.map((item, idx) => {
                const completed = item.status === "completed";
                const running = item.status === "in_progress";
                return (
                  <div
                    key={`${idx}-${item.content}`}
                    className="flex items-start gap-2 rounded-[8px] px-1.5 py-1"
                  >
                    <span className="mt-[3px] flex h-3.5 w-3.5 shrink-0 items-center justify-center">
                      {completed ? (
                        <BrandIcon name="status/confirm" size={12.5} style={{ color: "var(--pi-success)" }} />
                      ) : running ? (
                        <span
                          className="h-2 w-2 rounded-full"
                          style={{ background: "var(--pi-accent)" }}
                        />
                      ) : (
                        <span
                          className="h-2 w-2 rounded-full border"
                          style={{ borderColor: "var(--pi-ink-tertiary)" }}
                        />
                      )}
                    </span>
                    <span
                      className={`text-[12.5px] leading-[1.45] ${completed ? "line-through" : running ? "font-medium" : ""}`}
                      style={{
                        color: completed
                          ? "var(--pi-ink-tertiary)"
                          : running
                            ? "var(--pi-ink)"
                            : "var(--pi-ink-secondary)",
                      }}
                    >
                      {item.content}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export function TodoPanel({
  todos,
}: {
  todos: TodoItem[];
  sessionKey?: string;
  inline?: boolean;
}) {
  return <InlineTodoCard todos={todos} />;
}
