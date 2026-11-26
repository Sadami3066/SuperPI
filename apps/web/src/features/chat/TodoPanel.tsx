import { useEffect, useMemo, useState } from "react";
import type { TodoItem } from "../../api/todo";
import { useI18n } from "../../i18n";
import { IconButton } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

export function TodoPanel({
  todos,
  sessionKey,
  inline = true,
}: {
  todos: TodoItem[];
  sessionKey: string;
  inline?: boolean;
}) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const [hiddenContents, setHiddenContents] = useState<string[]>([]);
  const [stickyTodos, setStickyTodos] = useState<TodoItem[]>([]);

  useEffect(() => {
    setHiddenContents([]);
    setStickyTodos([]);
    setExpanded(false);
  }, [sessionKey]);

  useEffect(() => {
    if (todos.length > 0) {
      setStickyTodos(todos);
    }
  }, [todos]);

  const source = todos.length > 0 ? todos : stickyTodos;
  const visibleTodos = useMemo(
    () => source.filter((item) => !hiddenContents.includes(item.content)),
    [hiddenContents, source],
  );

  if (visibleTodos.length === 0) return null;

  const doneCount = visibleTodos.filter((item) => item.status === "completed").length;
  const inProgressItem = visibleTodos.find((item) => item.status === "in_progress");

  return (
    <div className={inline ? "mx-auto mb-2 w-full max-w-[820px]" : "w-full"}>
      <div
        className="overflow-hidden rounded-[12px]"
        style={{
          background: "var(--pi-surface)",
          border: "0.5px solid var(--pi-hairline)",
        }}
      >
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="flex w-full items-center justify-between gap-2.5 px-3 py-2 text-left transition-colors hover:bg-[var(--pi-hover)]"
        >
          <div className="flex min-w-0 items-center gap-2">
            <BrandIcon
              name="status/confirm"
              size={14}
              style={{ color: doneCount === visibleTodos.length ? "var(--pi-success)" : "var(--pi-accent)" }}
            />
            <span className="truncate text-[12.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {!expanded && inProgressItem ? inProgressItem.content : t("todo.title")}
            </span>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <span
              className="text-[11.5px] tabular-nums"
              style={{ color: "var(--pi-ink-tertiary)" }}
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

        {expanded && (
          <div
            data-scrollable
            className="max-h-[210px] space-y-1 overflow-y-auto px-3 py-2"
            style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
          >
            {visibleTodos.map((item) => {
              const completed = item.status === "completed";
              const running = item.status === "in_progress";
              return (
                <div
                  key={item.content}
                  className="group flex items-center justify-between gap-2 rounded-[8px] px-2 py-1"
                >
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center">
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
                      className={`truncate text-[12.5px] ${completed ? "line-through" : ""}`}
                      style={{
                        color: completed ? "var(--pi-ink-tertiary)" : "var(--pi-ink)",
                      }}
                    >
                      {item.content}
                    </span>
                  </div>
                  <IconButton
                    size="sm"
                    label={t("todo.hideItem")}
                    className="opacity-0 group-hover:opacity-100"
                    onClick={() =>
                      setHiddenContents((prev) =>
                        prev.includes(item.content) ? prev : [...prev, item.content],
                      )
                    }
                  >
                    <BrandIcon name="navigation/close" size={12} />
                  </IconButton>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
