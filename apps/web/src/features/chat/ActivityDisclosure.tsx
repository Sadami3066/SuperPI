import { memo, useEffect, useMemo, useRef, useState } from "react";
import { IconBook, IconBrain, IconLayoutGrid } from "@tabler/icons-react";
import type { ActivityStep, ToolTraceItem } from "../../api/types";
import { useI18n } from "../../i18n";
import { BrandIcon, PiActivityIcon } from "../shared/BrandAssets";
import {
  extractLiveThinkingLine,
  firstErrorLine,
  formatElapsed,
  groupProcessSteps,
  reasoningPreview,
  toolArgsPreview,
  toolDescriptionArg,
  toolIconKind,
  type ToolIconKind,
  toolProgressLabel,
} from "./toolPresentation";

const MIN_THINKING_DWELL_MS = 380;
const DISCLOSURE_EXPANDED_MAP = new Map<string, boolean>();
const MAX_DISCLOSURE_CACHE = 240;

function readDisclosureExpanded(persistKey?: string): boolean {
  if (!persistKey) return false;
  return DISCLOSURE_EXPANDED_MAP.get(persistKey) ?? false;
}

function writeDisclosureExpanded(persistKey: string | undefined, value: boolean): void {
  if (!persistKey) return;
  if (!DISCLOSURE_EXPANDED_MAP.has(persistKey) && DISCLOSURE_EXPANDED_MAP.size >= MAX_DISCLOSURE_CACHE) {
    const oldest = DISCLOSURE_EXPANDED_MAP.keys().next().value;
    if (oldest) DISCLOSURE_EXPANDED_MAP.delete(oldest);
  }
  DISCLOSURE_EXPANDED_MAP.set(persistKey, value);
}

function ToolKindIcon({
  kind,
  color,
  size = 13,
}: {
  kind: ToolIconKind;
  color: string;
  size?: number;
}) {
  const props = { size, stroke: 1.75, style: { color } };
  switch (kind) {
    case "book":
      return <IconBook {...props} />;
    case "pen":
      return <BrandIcon name="action/pen" size={size} style={{ color }} />;
    case "terminal":
      return <BrandIcon name="system/terminal" size={size} style={{ color }} />;
    case "search":
      return <BrandIcon name="action/search" size={size} style={{ color }} />;
    case "brain":
      return <IconBrain {...props} />;
    case "apps":
      return <IconLayoutGrid {...props} />;
    case "clock":
      return <BrandIcon name="system/timer" size={size} style={{ color }} />;
    case "folder":
      return <BrandIcon name="file/folder_closed" size={size} style={{ color }} />;
    case "file":
      return <BrandIcon name="file/plain" size={size} style={{ color }} />;
    case "target":
      return <BrandIcon name="ai/goal" size={size} style={{ color }} />;
    case "check":
      return <BrandIcon name="status/confirm" size={size} style={{ color }} />;
    default:
      return (
        <span
          className="inline-block h-[6px] w-[6px] rounded-full"
          style={{ background: color }}
        />
      );
  }
}

function TimelineRailNode({
  connectTop,
  connectBottom,
  marker,
  children,
}: {
  connectTop: boolean;
  connectBottom: boolean;
  marker: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="relative flex items-stretch">
      <div className="relative flex w-[22px] shrink-0 flex-col items-center">
        <div
          className="w-[1px]"
          style={{
            height: 9,
            background: connectTop ? "var(--pi-hairline)" : "transparent",
          }}
        />
        <div className="flex h-[16px] w-[16px] shrink-0 items-center justify-center">
          {marker}
        </div>
        <div
          className="w-[1px] flex-1"
          style={{
            minHeight: 4,
            background: connectBottom ? "var(--pi-hairline)" : "transparent",
          }}
        />
      </div>
      <div className="min-w-0 flex-1 pb-0.5">{children}</div>
    </div>
  );
}

function ReasoningRow({
  text,
  active,
  compact,
  failed,
  connectTop,
  connectBottom,
}: {
  text: string;
  active: boolean;
  compact: boolean;
  failed: boolean;
  connectTop: boolean;
  connectBottom: boolean;
}) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const preview = useMemo(() => (compact ? "" : reasoningPreview(text)), [compact, text]);

  const dotColor = failed
    ? "var(--pi-danger)"
    : active
      ? "var(--pi-ink-secondary)"
      : "color-mix(in srgb, var(--pi-ink-tertiary) 62%, transparent)";

  return (
    <TimelineRailNode
      connectTop={connectTop}
      connectBottom={connectBottom}
      marker={<span className="inline-block h-[6px] w-[6px] rounded-full" style={{ background: dotColor }} />}
    >
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex min-h-[30px] w-full items-center gap-2 rounded-[7px] px-1.5 py-1 text-left transition-colors hover:bg-[var(--pi-hover)]"
      >
        <span
          className={`shrink-0 text-[12px] font-medium ${active ? "pi-text-shimmer" : ""}`}
          style={{ color: active ? "var(--pi-ink-secondary)" : "var(--pi-ink-tertiary)" }}
        >
          {compact ? t("activity.reasoningCompact") : t("activity.reasoning")}
        </span>
        {!expanded && preview && (
          <span
            className="min-w-0 flex-1 truncate text-[11.5px]"
            style={{ color: "var(--pi-ink-tertiary)" }}
          >
            {preview}
          </span>
        )}
        {(expanded || !preview) && <span className="flex-1" />}
        <BrandIcon
          name="navigation/chevron_right"
          size={12}
          className="shrink-0 transition-transform duration-150"
          style={{
            color: "var(--pi-ink-tertiary)",
            transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
          }}
        />
      </button>

      {expanded && text.trim() && (
        <div
          className="px-1.5 pt-0.5 pb-2 text-[12px] leading-[1.65] whitespace-pre-wrap break-words"
          style={{ color: "var(--pi-ink-tertiary)" }}
        >
          {text.trim()}
        </div>
      )}
    </TimelineRailNode>
  );
}

function ToolTimelineRow({
  tool,
  compact,
  connectTop,
  connectBottom,
}: {
  tool: ToolTraceItem;
  compact: boolean;
  connectTop: boolean;
  connectBottom: boolean;
}) {
  const { t, locale } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
    };
  }, []);

  const isFailed = tool.status === "failed";
  const isRunning = tool.status === "running";
  const descTitle = !compact && !isFailed ? toolDescriptionArg(tool.rawArgs) : null;
  const title = descTitle ?? tool.name;

  const preview = useMemo(() => {
    if (compact) return "";
    if (isFailed) return firstErrorLine(tool.output);
    if (descTitle) return "";
    return toolArgsPreview(tool.rawArgs);
  }, [compact, descTitle, isFailed, tool.output, tool.rawArgs]);

  const iconColor = isFailed ? "var(--pi-danger)" : "var(--pi-ink-tertiary)";

  return (
    <TimelineRailNode
      connectTop={connectTop}
      connectBottom={connectBottom}
      marker={
        isRunning ? (
          <span
            className="inline-block h-[12px] w-[12px] animate-spin rounded-full border-[1.5px] border-current border-t-transparent"
            style={{ color: "var(--pi-accent)" }}
          />
        ) : (
          <ToolKindIcon kind={toolIconKind(tool.name)} color={iconColor} size={13} />
        )
      }
    >
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex min-h-[30px] w-full items-center gap-2 rounded-[7px] px-1.5 py-1 text-left transition-colors hover:bg-[var(--pi-hover)]"
      >
        <span
          className={`truncate text-[12px] ${preview ? "max-w-[190px] shrink-0" : "flex-1"}`}
          style={{
            fontFamily: descTitle ? "var(--pi-font-sans)" : "var(--pi-font-mono)",
            fontWeight: descTitle ? 500 : 450,
            color: isFailed
              ? "var(--pi-danger)"
              : descTitle
                ? "var(--pi-ink)"
                : "var(--pi-ink-secondary)",
          }}
        >
          {title}
        </span>

        {preview && (
          <span
            className="min-w-0 flex-1 truncate text-[11.5px]"
            style={{
              fontFamily: isFailed ? "var(--pi-font-sans)" : "var(--pi-font-mono)",
              color: isFailed ? "var(--pi-danger)" : "var(--pi-ink-tertiary)",
            }}
          >
            {preview}
          </span>
        )}
        {!preview && <span className="flex-1" />}

        {!isRunning && tool.durationMs > 0 && (
          <span
            className="shrink-0 text-[11px] tabular-nums"
            style={{ color: "var(--pi-ink-tertiary)" }}
          >
            {tool.durationMs < 1000 ? `${tool.durationMs}ms` : formatElapsed(tool.durationMs, locale)}
          </span>
        )}

        <BrandIcon
          name="navigation/chevron_right"
          size={12}
          className="shrink-0 transition-transform duration-150"
          style={{
            color: "var(--pi-ink-tertiary)",
            transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
          }}
        />
      </button>

      {expanded && (
        <div className="space-y-2 px-1.5 pt-1 pb-2.5">
          {tool.argsText.trim() && (
            <div>
              <div className="mb-0.5 text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("activity.toolArgs")}
              </div>
              <pre
                data-scrollable
                className="max-h-28 overflow-auto text-[11px] leading-[1.55] whitespace-pre-wrap break-words"
                style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink-tertiary)" }}
              >
                {tool.argsText}
              </pre>
            </div>
          )}

          {tool.output && (
            <div>
              <div className="mb-0.5 flex items-center justify-between gap-2">
                <span className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("activity.toolOutput")}
                </span>
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    void navigator.clipboard?.writeText(tool.output);
                    setCopied(true);
                    if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
                    copyTimerRef.current = setTimeout(() => setCopied(false), 1200);
                  }}
                  aria-label={t("activity.copyOutput")}
                  className="flex h-6 w-6 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  {copied ? <BrandIcon name="status/confirm" size={12.5} /> : <BrandIcon name="action/copy" size={12.5} />}
                </button>
              </div>
              <pre
                data-scrollable
                className="max-h-52 overflow-auto rounded-[8px] px-2.5 py-2 text-[11.5px] leading-[1.55] whitespace-pre-wrap break-words"
                style={{
                  background: "var(--pi-code-bg)",
                  fontFamily: "var(--pi-font-mono)",
                  color: isFailed ? "var(--pi-danger)" : "var(--pi-ink-secondary)",
                }}
              >
                {tool.output}
              </pre>
            </div>
          )}
        </div>
      )}
    </TimelineRailNode>
  );
}

function ToolGroupNode({
  tools,
  compact,
  connectTop,
  connectBottom,
}: {
  tools: ToolTraceItem[];
  compact: boolean;
  connectTop: boolean;
  connectBottom: boolean;
}) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const running = tools.some((x) => x.status === "running");
  const failed = tools.some((x) => x.status === "failed");
  const dotColor = failed
    ? "var(--pi-danger)"
    : running
      ? "var(--pi-warning)"
      : "color-mix(in srgb, var(--pi-ink-tertiary) 62%, transparent)";

  return (
    <TimelineRailNode
      connectTop={connectTop}
      connectBottom={connectBottom}
      marker={<span className="inline-block h-[6px] w-[6px] rounded-full" style={{ background: dotColor }} />}
    >
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        className="flex min-h-[30px] w-full items-center justify-between gap-2 rounded-[7px] px-1.5 py-1 text-left transition-colors hover:bg-[var(--pi-hover)]"
      >
        <span className="truncate text-[12px]" style={{ color: "var(--pi-ink-secondary)" }}>
          {t("activity.toolGroup", { count: tools.length })}
        </span>
        <BrandIcon
          name="navigation/chevron_right"
          size={12}
          className="shrink-0 transition-transform duration-150"
          style={{
            color: "var(--pi-ink-tertiary)",
            transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
          }}
        />
      </button>

      {expanded && (
        <div className="pt-1 pl-2">
          {tools.map((tool, idx) => (
            <ToolTimelineRow
              key={tool.id}
              tool={tool}
              compact={compact}
              connectTop={idx > 0}
              connectBottom={idx < tools.length - 1}
            />
          ))}
        </div>
      )}
    </TimelineRailNode>
  );
}

export const ActivityDisclosure = memo(function ActivityDisclosure({
  steps,
  streaming,
  status = "done",
  durationMs,
  compact = false,
  dark = false,
  persistKey,
}: {
  steps: ActivityStep[];
  streaming?: boolean;
  status?: "done" | "stopped" | "failed";
  durationMs?: number;
  compact?: boolean;
  dark?: boolean;
  persistKey?: string;
}) {
  const { t, locale } = useI18n();
  const [expanded, setExpanded] = useState(() => readDisclosureExpanded(persistKey));
  const [displayedLine, setDisplayedLine] = useState<{ slotKey: string; text: string } | null>(null);
  const lastSwapAtRef = useRef(0);

  useEffect(() => {
    setExpanded(readDisclosureExpanded(persistKey));
  }, [persistKey]);

  const runningTool = useMemo(
    () =>
      steps.find((s): s is Extract<ActivityStep, { kind: "tool" }> =>
        s.kind === "tool" && s.tool.status === "running",
      ) ?? null,
    [steps],
  );

  const lastStep = steps[steps.length - 1] ?? null;
  const activeThinking = useMemo(() => {
    if (!streaming || runningTool || !lastStep || lastStep.kind !== "thinking" || compact) {
      return null;
    }
    return extractLiveThinkingLine(lastStep.text);
  }, [compact, lastStep, runningTool, streaming]);

  useEffect(() => {
    if (!activeThinking) {
      setDisplayedLine(null);
      lastSwapAtRef.current = 0;
      return;
    }
    const now = Date.now();
    if (!displayedLine || displayedLine.slotKey === activeThinking.slotKey) {
      if (displayedLine?.text !== activeThinking.text) {
        setDisplayedLine(activeThinking);
      }
      return;
    }
    const elapsed = now - lastSwapAtRef.current;
    if (elapsed >= MIN_THINKING_DWELL_MS) {
      lastSwapAtRef.current = now;
      setDisplayedLine(activeThinking);
      return;
    }
    const timer = setTimeout(() => {
      lastSwapAtRef.current = Date.now();
      setDisplayedLine(activeThinking);
    }, MIN_THINKING_DWELL_MS - elapsed);
    return () => clearTimeout(timer);
  }, [activeThinking, displayedLine]);

  const nodes = useMemo(() => groupProcessSteps(steps), [steps]);

  if (steps.length === 0) return null;

  const isFailed = !streaming && status === "failed";
  const isStopped = !streaming && status === "stopped";

  const resolvedDurationMs =
    typeof durationMs === "number" && durationMs > 0
      ? durationMs
      : steps.reduce((acc, s) => acc + (s.kind === "tool" && s.tool.durationMs > 0 ? s.tool.durationMs : 0), 0);

  const elapsedText = resolvedDurationMs > 0 ? formatElapsed(resolvedDurationMs, locale) : "";

  let summaryText: string;
  if (streaming) {
    if (runningTool) {
      summaryText = compact
        ? t("activity.callingTool")
        : toolProgressLabel(runningTool.tool.name, runningTool.tool.rawArgs);
    } else if (displayedLine) {
      summaryText = displayedLine.text;
    } else {
      summaryText = t("activity.thinking");
    }
  } else if (isFailed) {
    summaryText = elapsedText ? `${t("activity.failed")} · ${elapsedText}` : t("activity.failed");
  } else if (isStopped) {
    summaryText = elapsedText ? `${t("activity.stopped")} · ${elapsedText}` : t("activity.stopped");
  } else {
    summaryText = elapsedText
      ? t("activity.doneElapsed", { elapsed: elapsedText })
      : t("activity.doneShort");
  }

  return (
    <div className="mb-2.5 w-full">
      <button
        type="button"
        onClick={() =>
          setExpanded((v) => {
            const next = !v;
            writeDisclosureExpanded(persistKey, next);
            return next;
          })
        }
        className="flex min-h-[34px] w-full items-center justify-between gap-2.5 rounded-[8px] px-1 py-1 text-left transition-colors hover:bg-[var(--pi-hover)]"
      >
        <div className="flex min-w-0 flex-1 items-center gap-2">
          {streaming && <PiActivityIcon size={16} dark={dark} />}
          <span
            key={streaming && displayedLine ? displayedLine.slotKey : "summary"}
            className={`min-w-0 truncate text-[12.5px] font-medium ${
              streaming && displayedLine ? "pi-text-shimmer pi-thinking-line-enter" : ""
            }`}
            style={{
              color: streaming
                ? "var(--pi-ink-secondary)"
                : isFailed
                  ? "var(--pi-danger)"
                  : "var(--pi-ink-tertiary)",
            }}
          >
            {summaryText}
          </span>
        </div>

        <BrandIcon
          name="navigation/chevron_right"
          size={13}
          className="shrink-0 transition-transform duration-150"
          style={{
            color: "var(--pi-ink-tertiary)",
            transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
          }}
        />
      </button>

      {expanded && (
        <div className="mt-1 mb-1">
          {nodes.map((node, idx) => {
            const connectTop = idx > 0;
            const connectBottom = idx < nodes.length - 1;
            if (node.kind === "tool_run") {
              return (
                <ToolGroupNode
                  key={node.id}
                  tools={node.tools}
                  compact={compact}
                  connectTop={connectTop}
                  connectBottom={connectBottom}
                />
              );
            }
            if (node.step.kind === "thinking") {
              return (
                <ReasoningRow
                  key={node.id}
                  text={node.step.text}
                  active={Boolean(streaming && idx === nodes.length - 1)}
                  compact={compact}
                  failed={isFailed && idx === nodes.length - 1}
                  connectTop={connectTop}
                  connectBottom={connectBottom}
                />
              );
            }
            return (
              <ToolTimelineRow
                key={node.id}
                tool={node.step.tool}
                compact={compact}
                connectTop={connectTop}
                connectBottom={connectBottom}
              />
            );
          })}
        </div>
      )}
    </div>
  );
});
