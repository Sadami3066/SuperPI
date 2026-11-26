import { memo, useEffect, useRef, useState } from "react";
import {
  IconCheck,
  IconChevronRight,
  IconCopy,
  IconFileText,
  IconSparkles,
  IconTerminal2,
} from "@tabler/icons-react";
import type { ActivityStep, ToolTraceItem } from "../../api/types";
import { useI18n } from "../../i18n";

function ToolCardRow({ tool }: { tool: ToolTraceItem }) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    };
  }, []);

  const isFailed = tool.status === "failed";
  const isRunning = tool.status === "running";

  return (
    <div className="w-full">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex min-h-[34px] w-full items-center gap-2 rounded-[9px] px-2.5 py-1 text-left transition-colors hover:bg-[var(--pi-hover)]"
      >
        {isRunning ? (
          <span
            className="inline-block h-3 w-3 shrink-0 animate-spin rounded-full border-[1.5px] border-current border-t-transparent"
            style={{ color: "var(--pi-accent)" }}
          />
        ) : tool.name === "bash" ? (
          <IconTerminal2
            size={14}
            stroke={1.65}
            className="shrink-0"
            style={{ color: isFailed ? "var(--pi-danger)" : "var(--pi-ink-tertiary)" }}
          />
        ) : (
          <IconFileText
            size={14}
            stroke={1.65}
            className="shrink-0"
            style={{ color: isFailed ? "var(--pi-danger)" : "var(--pi-ink-tertiary)" }}
          />
        )}

        <span
          className="shrink-0 text-[12px] font-medium"
          style={{
            fontFamily: "var(--pi-font-mono)",
            color: isFailed ? "var(--pi-danger)" : "var(--pi-ink-secondary)",
          }}
        >
          {tool.name}
        </span>

        <span
          className="min-w-0 flex-1 truncate text-[12px]"
          style={{
            fontFamily: "var(--pi-font-mono)",
            color: isFailed ? "var(--pi-danger)" : "var(--pi-ink-tertiary)",
          }}
        >
          {tool.summary}
        </span>

        {!isRunning && tool.durationMs > 0 && (
          <span className="shrink-0 text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            {tool.durationMs >= 1000
              ? `${(tool.durationMs / 1000).toFixed(1)}s`
              : `${tool.durationMs}ms`}
          </span>
        )}

        <IconChevronRight
          size={13}
          stroke={1.7}
          className="shrink-0 transition-transform duration-150"
          style={{
            color: "var(--pi-ink-tertiary)",
            transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
          }}
        />
      </button>

      {expanded && (
        <div
          className="mt-1 mb-2 ml-4 rounded-[10px] p-3"
          style={{
            background: "var(--pi-code-bg)",
            border: "0.5px solid var(--pi-surface-rim)",
          }}
        >
          <div className="mb-1.5 flex items-start justify-between gap-2">
            <pre
              data-scrollable
              className="max-h-28 min-w-0 flex-1 overflow-auto text-[11px] leading-[1.45] whitespace-pre-wrap break-words"
              style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink-tertiary)" }}
            >
              {tool.argsText}
            </pre>
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                void navigator.clipboard?.writeText(tool.output);
                setCopied(true);
                if (timerRef.current !== null) clearTimeout(timerRef.current);
                timerRef.current = setTimeout(() => setCopied(false), 1200);
              }}
              aria-label={t("activity.copyOutput")}
              className="flex h-6 w-6 shrink-0 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
              style={{ color: "var(--pi-ink-tertiary)" }}
            >
              {copied ? <IconCheck size={13} stroke={1.7} /> : <IconCopy size={13} stroke={1.7} />}
            </button>
          </div>
          <pre
            data-scrollable
            className="max-h-52 overflow-auto text-[12px] leading-[1.55] whitespace-pre-wrap break-words"
            style={{
              fontFamily: "var(--pi-font-mono)",
              color: isFailed ? "var(--pi-danger)" : "var(--pi-ink)",
            }}
          >
            {tool.output || (isRunning ? t("activity.running") : "—")}
          </pre>
        </div>
      )}
    </div>
  );
}

export const ActivityDisclosure = memo(function ActivityDisclosure({
  steps,
  streaming,
  compact = false,
}: {
  steps: ActivityStep[];
  streaming?: boolean;
  compact?: boolean;
}) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);

  if (steps.length === 0) return null;

  const runningTool = steps.find((s) => s.kind === "tool" && s.tool.status === "running");
  const lastThinking = [...steps].reverse().find((s) => s.kind === "thinking");
  const latestLine = lastThinking
    ? lastThinking.text
        .split(/\r?\n/)
        .map((l) => l.trim().replace(/^[-*#>\s]+/, ""))
        .filter(Boolean)
        .at(-1)
    : undefined;

  const headerText = streaming
    ? runningTool && runningTool.kind === "tool"
      ? t("activity.runningTool", {
          name: runningTool.tool.name,
          summary: runningTool.tool.summary,
        })
      : !compact && latestLine
        ? latestLine
        : t("activity.thinking")
    : `${t("activity.done")} · ${t("activity.stepCount", { count: steps.length })}`;

  return (
    <div className="mb-2 w-full">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex min-h-[32px] w-full items-center justify-between gap-3 rounded-[8px] px-1 py-1 text-left transition-opacity hover:opacity-85"
      >
        <div className="flex min-w-0 items-center gap-2">
          {streaming && (
            <span
              className="inline-block h-2 w-2 shrink-0 animate-pulse rounded-full"
              style={{ background: "var(--pi-accent)" }}
            />
          )}
          <span
            className="min-w-0 truncate text-[12.5px] font-medium"
            style={{ color: streaming ? "var(--pi-ink-secondary)" : "var(--pi-ink-tertiary)" }}
          >
            {headerText}
          </span>
        </div>

        <IconChevronRight
          size={14}
          stroke={1.7}
          className="shrink-0 transition-transform duration-150"
          style={{
            color: "var(--pi-ink-tertiary)",
            transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
          }}
        />
      </button>

      {expanded && (
        <div
          className="mt-1 space-y-1 pl-2.5"
          style={{ borderLeft: "1px solid var(--pi-hairline)" }}
        >
          {steps.map((step) => {
            if (step.kind === "thinking") {
              return (
                <div key={step.id} className="flex items-start gap-2 px-2.5 py-1.5">
                  <IconSparkles
                    size={13}
                    stroke={1.65}
                    className="mt-0.5 shrink-0"
                    style={{ color: "var(--pi-ink-tertiary)" }}
                  />
                  <p
                    className="text-[12.5px] leading-[1.55] whitespace-pre-wrap"
                    style={{ color: "var(--pi-ink-tertiary)" }}
                  >
                    {step.text}
                  </p>
                </div>
              );
            }
            return <ToolCardRow key={step.id} tool={step.tool} />;
          })}
        </div>
      )}
    </div>
  );
});
