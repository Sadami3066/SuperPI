import {
  cloneElement,
  createContext,
  Fragment,
  isValidElement,
  memo,
  type ReactNode,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import hljs from "highlight.js/lib/core";
import bash from "highlight.js/lib/languages/bash";
import c from "highlight.js/lib/languages/c";
import cpp from "highlight.js/lib/languages/cpp";
import css from "highlight.js/lib/languages/css";
import diff from "highlight.js/lib/languages/diff";
import go from "highlight.js/lib/languages/go";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import json from "highlight.js/lib/languages/json";
import kotlin from "highlight.js/lib/languages/kotlin";
import markdown from "highlight.js/lib/languages/markdown";
import python from "highlight.js/lib/languages/python";
import rust from "highlight.js/lib/languages/rust";
import sql from "highlight.js/lib/languages/sql";
import typescript from "highlight.js/lib/languages/typescript";
import xml from "highlight.js/lib/languages/xml";
import yaml from "highlight.js/lib/languages/yaml";
import { useI18n } from "../../i18n";
import { openExternalUrl } from "../../platform/bridge";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { ContextMenu, ContextMenuItem } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";
import {
  splitMarkdownBlockSpans,
  splitTextByFadeUnit,
  STREAM_ALPHA_LEVELS,
  STREAM_COALESCE_INTERVAL_MS,
  STREAM_INITIAL_ANIMATE_MAX_LENGTH,
  StreamTextFadeAnimator,
} from "./streamReveal";

hljs.registerLanguage("bash", bash);
hljs.registerLanguage("sh", bash);
hljs.registerLanguage("shell", bash);
hljs.registerLanguage("c", c);
hljs.registerLanguage("cpp", cpp);
hljs.registerLanguage("css", css);
hljs.registerLanguage("diff", diff);
hljs.registerLanguage("go", go);
hljs.registerLanguage("java", java);
hljs.registerLanguage("javascript", javascript);
hljs.registerLanguage("js", javascript);
hljs.registerLanguage("jsx", javascript);
hljs.registerLanguage("json", json);
hljs.registerLanguage("kotlin", kotlin);
hljs.registerLanguage("kt", kotlin);
hljs.registerLanguage("markdown", markdown);
hljs.registerLanguage("md", markdown);
hljs.registerLanguage("python", python);
hljs.registerLanguage("py", python);
hljs.registerLanguage("rust", rust);
hljs.registerLanguage("rs", rust);
hljs.registerLanguage("sql", sql);
hljs.registerLanguage("typescript", typescript);
hljs.registerLanguage("ts", typescript);
hljs.registerLanguage("tsx", typescript);
hljs.registerLanguage("xml", xml);
hljs.registerLanguage("html", xml);
hljs.registerLanguage("yaml", yaml);
hljs.registerLanguage("yml", yaml);

const COLLAPSE_LINE_THRESHOLD = 22;
const HIGHLIGHT_CACHE = new Map<string, string>();
const MAX_HIGHLIGHT_CACHE = 160;

function computeHighlightedHtml(language: string, code: string): string | null {
  const lang = language.trim().toLowerCase();
  if (!lang || !hljs.getLanguage(lang)) return null;
  const key = `${lang}\0${code}`;
  const cached = HIGHLIGHT_CACHE.get(key);
  if (cached !== undefined) return cached;
  try {
    const html = hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
    if (HIGHLIGHT_CACHE.size >= MAX_HIGHLIGHT_CACHE) {
      const oldest = HIGHLIGHT_CACHE.keys().next().value;
      if (oldest) HIGHLIGHT_CACHE.delete(oldest);
    }
    HIGHLIGHT_CACHE.set(key, html);
    return html;
  } catch {
    return null;
  }
}

const CodeBlock = memo(function CodeBlock({
  language,
  code,
  streaming = false,
  rendered,
}: {
  language: string;
  code: string;
  streaming?: boolean;
  rendered?: ReactNode;
}) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [deferredHtml, setDeferredHtml] = useState<string | null>(() => {
    if (streaming) return null;
    const lang = language.trim().toLowerCase();
    return lang ? HIGHLIGHT_CACHE.get(`${lang}\0${code}`) ?? null : null;
  });
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    };
  }, []);

  useEffect(() => {
    if (streaming) {
      setDeferredHtml(null);
      return undefined;
    }
    const lang = language.trim().toLowerCase();
    if (!lang || !hljs.getLanguage(lang)) {
      setDeferredHtml(null);
      return undefined;
    }
    const cached = HIGHLIGHT_CACHE.get(`${lang}\0${code}`);
    if (cached !== undefined) {
      setDeferredHtml(cached);
      return undefined;
    }
    const raf = requestAnimationFrame(() => {
      setDeferredHtml(computeHighlightedHtml(language, code));
    });
    return () => cancelAnimationFrame(raf);
  }, [code, language, streaming]);

  const lineCount = useMemo(() => {
    let count = 1;
    for (let i = 0; i < code.length; i++) {
      if (code.charCodeAt(i) === 10) count++;
    }
    return count;
  }, [code]);
  const collapsible = !streaming && lineCount > COLLAPSE_LINE_THRESHOLD;
  const isShellCode = /^(bash|sh|shell|zsh)$/i.test(language);
  const summaryBotId = useSessionStore((s) => s.summariesById[s.activeSessionId]?.botId);
  const draftBotId = useSessionStore((s) => s.draftBotId);
  const bots = useBotStore((s) => s.bots);
  const canRunShell =
    isShellCode && !bots.find((b) => b.id === (summaryBotId || draftBotId))?.isRemote;

  const runInTerminal = () => {
    const client = useConnectionStore.getState().client;
    const activeSessionId = useSessionStore.getState().activeSessionId;
    const summary = useSessionStore.getState().summariesById[activeSessionId];
    const currentBots = useBotStore.getState().bots;
    const bot = currentBots.find((b) => b.id === summary?.botId) ?? currentBots[0];
    if (!client || !bot || bot.isRemote) return;
    const workbench = useWorkbenchStore.getState();
    const worktreeId = workbench.activeWorktreeByBot[bot.id] ?? summary?.worktreeId ?? null;
    workbench.openPanel("terminal");
    void client
      .listTerminals(bot.id)
      .then(async (list) => {
        const term = list[0] ?? (await client.createTerminal(bot.id, { worktreeId, fresh: false }));
        const cmd = code.endsWith("\n") ? code : `${code}\n`;
        await client.sendTerminalInput(term.id, cmd);
      })
      .catch(() => void 0);
  };

  return (
    <ContextMenu
      trigger={
        <div
          ref={rootRef}
          className="my-3 overflow-hidden rounded-[12px]"
          style={{
            background: "var(--pi-code-bg)",
            border: "1px solid var(--pi-canvas-border)",
          }}
        >
          <div
            className="flex items-center justify-between px-3.5 py-1.5"
            style={{ borderBottom: "1px solid var(--pi-canvas-border)" }}
          >
            <div className="flex items-center gap-2">
              <span
                className="text-[11.5px] font-medium"
                style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
              >
                {language || "code"}
              </span>
              {collapsible && (
                <span className="text-[11px] tabular-nums" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {lineCount}L
                </span>
              )}
            </div>
            <div className="flex items-center gap-1">
              {canRunShell && (
                <button
                  type="button"
                  onClick={runInTerminal}
                  title={t("code.runInTerminal")}
                  className="inline-flex h-6 items-center gap-1 rounded-md px-1.5 text-[11px] font-medium transition-colors hover:bg-[var(--pi-hover)] hover:text-[var(--pi-ink)]"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  <BrandIcon name="system/terminal" size={12.5} />
                  <span>{t("code.runShort")}</span>
                </button>
              )}
              {collapsible && (
                <button
                  type="button"
                  onClick={() => {
                    const next = !collapsed;
                    setCollapsed(next);
                    if (next) {
                      requestAnimationFrame(() => {
                        rootRef.current?.scrollIntoView({ block: "nearest" });
                      });
                    }
                  }}
                  aria-label={collapsed ? "Expand" : "Collapse"}
                  className="flex h-6 w-6 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  <BrandIcon
                    name="navigation/chevron_down"
                    size={14}
                    className="transition-transform duration-150"
                    style={{ transform: collapsed ? "rotate(-90deg)" : "rotate(0deg)" }}
                  />
                </button>
              )}
              <button
                type="button"
                onClick={() => {
                  void navigator.clipboard?.writeText(code);
                  setCopied(true);
                  if (timerRef.current !== null) clearTimeout(timerRef.current);
                  timerRef.current = setTimeout(() => setCopied(false), 1200);
                }}
                aria-label={t("chat.copy")}
                className="inline-flex h-6 items-center gap-1 rounded-md px-1.5 text-[11px] font-medium transition-colors hover:bg-[var(--pi-hover)] hover:text-[var(--pi-ink)]"
                style={{ color: copied ? "var(--pi-success)" : "var(--pi-ink-tertiary)" }}
              >
                {copied ? <BrandIcon name="status/confirm" size={13.5} /> : <BrandIcon name="action/copy" size={13.5} />}
                {copied && <span>{t("chat.copied")}</span>}
              </button>
            </div>
          </div>
          {!collapsed && (
            <pre
              data-scrollable
              className="overflow-x-auto px-3.5 py-3 text-[13px] leading-[1.6]"
              style={{ fontFamily: "var(--pi-font-mono)" }}
            >
              {deferredHtml !== null ? (
                <code className="hljs" dangerouslySetInnerHTML={{ __html: deferredHtml }} />
              ) : (
                <code>{rendered ?? code}</code>
              )}
            </pre>
          )}
        </div>
      }
    >
      <ContextMenuItem
        icon={<BrandIcon name="action/copy" size={14} />}
        label={t("chat.copy")}
        onSelect={() => {
          void navigator.clipboard?.writeText(code);
          setCopied(true);
          if (timerRef.current !== null) clearTimeout(timerRef.current);
          timerRef.current = setTimeout(() => setCopied(false), 1200);
        }}
      />
      {isShellCode && (
        <ContextMenuItem
          icon={<BrandIcon name="system/terminal" size={14} />}
          label={t("code.runInTerminal")}
          onSelect={runInTerminal}
        />
      )}
      {collapsible && (
        <ContextMenuItem
          icon={<BrandIcon name="navigation/chevron_down" size={14} />}
          label={collapsed ? t("code.expand") : t("code.collapse")}
          onSelect={() => setCollapsed((v) => !v)}
        />
      )}
    </ContextMenu>
  );
});

interface MarkdownPositionNode {
  position?: {
    start?: {
      offset?: number;
    };
    end?: {
      offset?: number;
    };
  };
}

function nodeStartOffset(node: unknown): number {
  const offset = (node as MarkdownPositionNode | undefined)?.position?.start?.offset;
  return typeof offset === "number" ? offset : 0;
}

function nodeEndOffset(node: unknown): number | null {
  const offset = (node as MarkdownPositionNode | undefined)?.position?.end?.offset;
  return typeof offset === "number" ? offset : null;
}

function collectText(node: ReactNode): string {
  if (typeof node === "string") return node;
  if (Array.isArray(node)) return node.map(collectText).join("");
  if (isValidElement<{ children?: ReactNode }>(node)) {
    return node.props.children === undefined ? "" : collectText(node.props.children);
  }
  return "";
}

function fadeRuns(
  text: string,
  offset: number,
  animator: StreamTextFadeAnimator,
  nowMs: number,
): ReactNode {
  const runs = splitTextByFadeUnit(text, offset, animator, nowMs);
  if (runs.length === 1 && !runs[0].animating) return text;
  return runs.map((run) => {
    if (!run.animating) {
      return <Fragment key={`s-${run.offset}`}>{run.text}</Fragment>;
    }
    return (
      <span key={`f-${run.offset}`} data-fade-offset={run.offset} style={{ opacity: run.alpha }}>
        {run.text}
      </span>
    );
  });
}

function wrapStreamingText(
  text: string,
  source: string,
  baseOffset: number,
  cursor: { pos: number },
  animator: StreamTextFadeAnimator,
  nowMs: number,
): ReactNode {
  if (!text || !animator.hasActiveBatches) return text;
  const idx = source.indexOf(text, cursor.pos);
  if (idx >= 0) {
    cursor.pos = idx + text.length;
    return fadeRuns(text, baseOffset + idx, animator, nowMs);
  }
  if (!text.includes("\n")) return text;
  const lines = text.split("\n");
  let anyMatched = false;
  const out: ReactNode[] = [];
  for (let i = 0; i < lines.length; i++) {
    if (i > 0) out.push("\n");
    const line = lines[i];
    if (!line) continue;
    const at = source.indexOf(line, cursor.pos);
    if (at >= 0) {
      anyMatched = true;
      cursor.pos = at + line.length;
      out.push(<Fragment key={`l-${baseOffset + at}`}>{fadeRuns(line, baseOffset + at, animator, nowMs)}</Fragment>);
    } else {
      out.push(line);
    }
  }
  return anyMatched ? out : text;
}

function wrapStreamingChildren(
  node: ReactNode,
  source: string,
  baseOffset: number,
  cursor: { pos: number },
  animator: StreamTextFadeAnimator,
  nowMs: number,
  skipBlockTags = false,
): ReactNode {
  if (typeof node === "string") {
    return wrapStreamingText(node, source, baseOffset, cursor, animator, nowMs);
  }
  if (Array.isArray(node)) {
    return node.map((child, idx) => (
      <Fragment key={idx}>
        {wrapStreamingChildren(child, source, baseOffset, cursor, animator, nowMs, skipBlockTags)}
      </Fragment>
    ));
  }
  if (isValidElement<{ children?: ReactNode; node?: MarkdownPositionNode }>(node)) {
    if (skipBlockTags && typeof node.type === "string" && ["p", "ul", "ol"].includes(node.type)) {
      return node;
    }
    if (typeof node.type !== "string") {
      const endOffset = nodeEndOffset(node.props.node);
      if (endOffset !== null) {
        cursor.pos = Math.max(cursor.pos, endOffset);
      } else if (node.props.children !== undefined) {
        const text = collectText(node.props.children);
        if (text) {
          const at = source.indexOf(text, cursor.pos);
          if (at >= 0) cursor.pos = at + text.length;
        }
      }
      return node;
    }
    if (node.props.children === undefined) return node;
    return cloneElement(
      node,
      undefined,
      wrapStreamingChildren(
        node.props.children,
        source,
        baseOffset,
        cursor,
        animator,
        nowMs,
        skipBlockTags,
      ),
    );
  }
  return node;
}

interface StreamingFadeContextValue {
  content: string;
  baseOffset: number;
  animator: StreamTextFadeAnimator;
  nowMs: number;
  activeFade: boolean;
  streamingCode: boolean;
}

const StreamingFadeContext = createContext<StreamingFadeContextValue | null>(null);

function wrapWithContext(
  children: ReactNode,
  node: unknown,
  ctx: StreamingFadeContextValue | null,
  skipBlockTags = false,
): ReactNode {
  if (!ctx?.activeFade) return children;
  return wrapStreamingChildren(
    children,
    ctx.content,
    ctx.baseOffset,
    { pos: nodeStartOffset(node) },
    ctx.animator,
    ctx.nowMs,
    skipBlockTags,
  );
}

// Module-level stable component map so react-markdown never unmounts/remounts
// block elements or CodeBlock instances on streaming ticks or fade settle.
const markdownComponents: Components = {
  pre({ children }) {
    return <>{children}</>;
  },
  code({ className, children, node, ...props }) {
    const ctx = useContext(StreamingFadeContext);
    const match = /language-([\w-]+)/.exec(className || "");
    const raw = String(children);
    const isBlock = Boolean(match) || raw.includes("\n");
    const rawText = raw.replace(/\n$/, "");
    if (isBlock) {
      let rendered: ReactNode;
      if (ctx?.activeFade && rawText) {
        const startPos = nodeStartOffset(node);
        const at = ctx.content.indexOf(rawText, startPos);
        const offset = ctx.baseOffset + (at >= 0 ? at : startPos);
        rendered = fadeRuns(rawText, offset, ctx.animator, ctx.nowMs);
      }
      return (
        <CodeBlock
          language={match?.[1] ?? ""}
          code={rawText}
          streaming={Boolean(ctx && (ctx.streamingCode || ctx.activeFade))}
          rendered={rendered}
        />
      );
    }
    let inlineContent: ReactNode = children;
    if (ctx?.activeFade && raw) {
      const startPos = nodeStartOffset(node);
      const at = ctx.content.indexOf(raw, startPos);
      const offset = ctx.baseOffset + (at >= 0 ? at : startPos);
      inlineContent = fadeRuns(raw, offset, ctx.animator, ctx.nowMs);
    }
    return (
      <code
        {...props}
        className="rounded-[5px] px-1.5 py-[1px] text-[0.88em]"
        style={{
          background: "var(--pi-inline-code-bg)",
          fontFamily: "var(--pi-font-mono)",
        }}
      >
        {inlineContent}
      </code>
    );
  },
  a({ href, children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return (
      <a
        href={href}
        rel="noopener noreferrer"
        onClick={(e) => {
          if (href) {
            e.preventDefault();
            void openExternalUrl(href);
          }
        }}
      >
        {wrapWithContext(children, node, ctx)}
      </a>
    );
  },
  table({ children }) {
    return (
      <div data-scrollable className="pi-table-wrap my-3 overflow-x-auto">
        <table>{children}</table>
      </div>
    );
  },
  p({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <p>{wrapWithContext(children, node, ctx)}</p>;
  },
  h1({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <h1>{wrapWithContext(children, node, ctx)}</h1>;
  },
  h2({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <h2>{wrapWithContext(children, node, ctx)}</h2>;
  },
  h3({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <h3>{wrapWithContext(children, node, ctx)}</h3>;
  },
  h4({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <h4>{wrapWithContext(children, node, ctx)}</h4>;
  },
  li({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <li>{wrapWithContext(children, node, ctx, true)}</li>;
  },
  td({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <td>{wrapWithContext(children, node, ctx)}</td>;
  },
  th({ children, node }) {
    const ctx = useContext(StreamingFadeContext);
    return <th>{wrapWithContext(children, node, ctx)}</th>;
  },
};

function useCoalescedStreamText(text: string, live: boolean): string {
  const [displayed, setDisplayed] = useState(text);
  const latestRef = useRef(text);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  latestRef.current = text;

  useEffect(() => {
    if (!live) {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      if (displayed !== text) {
        setDisplayed(text);
      }
      return undefined;
    }
    if (text === displayed) return undefined;
    if (timerRef.current !== null) return undefined;
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setDisplayed(latestRef.current);
    }, STREAM_COALESCE_INTERVAL_MS);
    return undefined;
  }, [text, displayed, live]);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    };
  }, []);

  if (!live) return text;
  return displayed || text;
}

const MarkdownBlock = memo(function MarkdownBlock({
  content,
  baseOffset,
  animator,
  nowMs,
  activeFade,
  streamingCode,
}: {
  content: string;
  baseOffset: number;
  animator: StreamTextFadeAnimator | null;
  nowMs: number;
  activeFade: boolean;
  streamingCode: boolean;
}) {
  const fadeCtx = useMemo<StreamingFadeContextValue | null>(
    () =>
      (activeFade || streamingCode) && animator
        ? { content, baseOffset, animator, nowMs, activeFade, streamingCode }
        : null,
    [activeFade, animator, baseOffset, content, nowMs, streamingCode],
  );

  return (
    <StreamingFadeContext.Provider value={fadeCtx}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
        {content}
      </ReactMarkdown>
    </StreamingFadeContext.Provider>
  );
});

interface FadeNode {
  el: HTMLElement;
  offset: number;
  level: number;
}

export const MarkdownView = memo(function MarkdownView({
  content,
  streaming = false,
}: {
  content: string;
  streaming?: boolean;
}) {
  const live = Boolean(streaming);
  const coalesced = useCoalescedStreamText(content, live);
  const normalized = useMemo(() => coalesced.replace(/\r\n/g, "\n"), [coalesced]);
  const reducedMotion =
    typeof window !== "undefined" &&
    Boolean(window.matchMedia?.("(prefers-reduced-motion: reduce)").matches);

  const animatorRef = useRef<StreamTextFadeAnimator | null>(null);
  if (!animatorRef.current) {
    animatorRef.current = new StreamTextFadeAnimator();
  }
  const animator = animatorRef.current;
  const wasLiveRef = useRef(false);
  if (live) {
    wasLiveRef.current = true;
  }

  const [fadeSettledTick, setFadeSettledTick] = useState(0);

  const spans = useMemo(
    () => splitMarkdownBlockSpans(normalized, live),
    [normalized, live],
  );

  const shouldAnimate = !reducedMotion && (live || (wasLiveRef.current && animator.hasActiveBatches));

  const fadeState = useMemo<{ nowMs: number; activeFlags: boolean[] | null }>(() => {
    void fadeSettledTick;
    if (reducedMotion || !shouldAnimate) {
      animator.finish();
      wasLiveRef.current = false;
      return { nowMs: 0, activeFlags: null };
    }
    const nowMs = typeof performance !== "undefined" ? performance.now() : Date.now();
    animator.sync(normalized, live, normalized.length <= STREAM_INITIAL_ANIMATE_MAX_LENGTH, nowMs);
    animator.advance(nowMs);
    if (!animator.hasActiveBatches) {
      if (!live) wasLiveRef.current = false;
      return { nowMs, activeFlags: null };
    }
    return {
      nowMs,
      activeFlags: spans.map((span) => animator.hasBatchesInRange(span.start, span.end)),
    };
  }, [animator, fadeSettledTick, live, normalized, reducedMotion, shouldAnimate, spans]);

  const activeFlags = fadeState.activeFlags;
  const rootRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    if (!activeFlags) return undefined;
    const container = rootRef.current;
    if (!container) return undefined;

    let nodes: FadeNode[] = Array.from(
      container.querySelectorAll<HTMLElement>("[data-fade-offset]"),
    )
      .map((el) => ({
        el,
        offset: Number.parseInt(el.getAttribute("data-fade-offset") ?? "", 10),
        level: -1,
      }))
      .filter((node) => Number.isFinite(node.offset));

    const applyAt = (now: number) => {
      animator.advance(now);
      if (nodes.length > 0) {
        const remaining: FadeNode[] = [];
        for (const node of nodes) {
          if (!node.el.isConnected) continue;
          const level = animator.levelAt(node.offset, now);
          if (level !== node.level) {
            node.level = level;
            if (level >= STREAM_ALPHA_LEVELS) {
              node.el.style.opacity = "";
              node.el.removeAttribute("data-fade-offset");
              continue;
            }
            node.el.style.opacity = String(level / STREAM_ALPHA_LEVELS);
          }
          remaining.push(node);
        }
        nodes = remaining;
      }
    };

    let rafId = 0;
    let cancelled = false;

    const initialNow = typeof performance !== "undefined" ? performance.now() : Date.now();
    applyAt(initialNow);

    if (!animator.hasActiveBatches) {
      if (!live) wasLiveRef.current = false;
      setFadeSettledTick((v) => v + 1);
      return undefined;
    }

    const step = () => {
      if (cancelled) return;
      const now = typeof performance !== "undefined" ? performance.now() : Date.now();
      applyAt(now);
      if (animator.hasActiveBatches) {
        rafId = requestAnimationFrame(step);
      } else {
        if (!live) wasLiveRef.current = false;
        setFadeSettledTick((v) => v + 1);
      }
    };
    rafId = requestAnimationFrame(step);
    return () => {
      cancelled = true;
      cancelAnimationFrame(rafId);
    };
  }, [activeFlags, animator, live, normalized]);

  if (spans.length === 0) return null;

  return (
    <div ref={rootRef} className="pi-prose space-y-2.5">
      {spans.map((span, idx) => {
        const activeFade = Boolean(activeFlags?.[idx]);
        const streamingCode = live && idx === spans.length - 1;
        const needsCtx = activeFade || streamingCode;
        return (
          <MarkdownBlock
            key={`b-${idx}`}
            content={span.text}
            baseOffset={span.start}
            animator={needsCtx ? animator : null}
            nowMs={activeFade ? fadeState.nowMs : 0}
            activeFade={activeFade}
            streamingCode={streamingCode}
          />
        );
      })}
    </div>
  );
});
