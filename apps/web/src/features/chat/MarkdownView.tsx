import {
  cloneElement,
  Fragment,
  isValidElement,
  memo,
  type ReactNode,
  useEffect,
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
import { BrandIcon } from "../shared/BrandAssets";
import {
  splitMarkdownBlocks,
  splitTextByAlpha,
  STREAM_COALESCE_INTERVAL_MS,
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
}: {
  language: string;
  code: string;
  streaming?: boolean;
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

  return (
    <div
      ref={rootRef}
      className="my-3 overflow-hidden rounded-[12px]"
      style={{
        background: "var(--pi-code-bg)",
        border: "0.5px solid var(--pi-surface-rim)",
      }}
    >
      <div
        className="flex items-center justify-between px-3.5 py-1.5"
        style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
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
            className="flex h-6 w-6 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink-tertiary)" }}
          >
            {copied ? <BrandIcon name="status/confirm" size={14} /> : <BrandIcon name="action/copy" size={14} />}
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
            <code>{code}</code>
          )}
        </pre>
      )}
    </div>
  );
});

const baseMarkdownComponents: Components = {
  pre({ children }) {
    return <>{children}</>;
  },
  code({ className, children, ...props }) {
    const match = /language-([\w-]+)/.exec(className || "");
    const raw = String(children);
    const isBlock = Boolean(match) || raw.includes("\n");
    const rawText = raw.replace(/\n$/, "");
    if (isBlock) {
      return <CodeBlock language={match?.[1] ?? ""} code={rawText} />;
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
        {children}
      </code>
    );
  },
  a({ href, children }) {
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
        {children}
      </a>
    );
  },
  table({ children }) {
    return (
      <div data-scrollable className="my-2 overflow-x-auto">
        <table>{children}</table>
      </div>
    );
  },
};

const StaticMarkdownBlock = memo(function StaticMarkdownBlock({ content }: { content: string }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={baseMarkdownComponents}>
      {content}
    </ReactMarkdown>
  );
});

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

function wrapStreamingChildren(
  node: ReactNode,
  source: string,
  cursor: { pos: number },
  animator: StreamTextFadeAnimator,
  nowMs: number,
  skipBlockTags = false,
): ReactNode {
  if (typeof node === "string") {
    if (!node || !animator.hasActiveBatches) return node;
    const idx = source.indexOf(node, cursor.pos);
    if (idx < 0) return node;
    cursor.pos = idx + node.length;
    const runs = splitTextByAlpha(node, idx, animator, nowMs);
    if (runs.length === 1 && runs[0].alpha >= 1) return node;
    let offset = idx;
    return runs.map((run, i) => {
      const spanStart = offset;
      offset += run.text.length;
      if (run.alpha >= 1) {
        return <Fragment key={i}>{run.text}</Fragment>;
      }
      return (
        <span key={i} data-fade-offset={spanStart} style={{ opacity: run.alpha }}>
          {run.text}
        </span>
      );
    });
  }
  if (Array.isArray(node)) {
    return node.map((child, idx) => (
      <Fragment key={idx}>
        {wrapStreamingChildren(child, source, cursor, animator, nowMs, skipBlockTags)}
      </Fragment>
    ));
  }
  if (isValidElement<{ children?: ReactNode }>(node)) {
    if (skipBlockTags && typeof node.type === "string" && ["p", "ul", "ol"].includes(node.type)) {
      return node;
    }
    if (node.props.children === undefined) return node;
    return cloneElement(
      node,
      undefined,
      wrapStreamingChildren(node.props.children, source, cursor, animator, nowMs, skipBlockTags),
    );
  }
  return node;
}

const TailMarkdownBlock = memo(function TailMarkdownBlock({
  content,
  live,
}: {
  content: string;
  live: boolean;
}) {
  const coalesced = useCoalescedStreamText(content, live);
  const reducedMotion =
    typeof window !== "undefined" &&
    Boolean(window.matchMedia?.("(prefers-reduced-motion: reduce)").matches);

  const animatorRef = useRef<StreamTextFadeAnimator | null>(null);
  if (!animatorRef.current) {
    animatorRef.current = new StreamTextFadeAnimator();
  }
  const animator = animatorRef.current;
  const wasLiveRef = useRef(live);
  if (live) {
    wasLiveRef.current = true;
  }

  const tailRef = useRef<HTMLDivElement | null>(null);
  const shouldAnimate = !reducedMotion && (live || (wasLiveRef.current && animator.hasActiveBatches));

  const rendered = useMemo(() => {
    const nowMs = typeof performance !== "undefined" ? performance.now() : Date.now();
    if (reducedMotion || !shouldAnimate) {
      animator.finish();
      wasLiveRef.current = false;
      return <StaticMarkdownBlock content={coalesced} />;
    }

    animator.sync(coalesced, true, true, nowMs);
    if (!animator.hasActiveBatches) {
      if (!live) wasLiveRef.current = false;
      return <StaticMarkdownBlock content={coalesced} />;
    }

    const cursor = { pos: 0 };
    const components: Components = {
      ...baseMarkdownComponents,
      code({ className, children, ...props }) {
        const match = /language-([\w-]+)/.exec(className || "");
        const raw = String(children);
        const isBlock = Boolean(match) || raw.includes("\n");
        const rawText = raw.replace(/\n$/, "");
        if (isBlock) {
          return <CodeBlock language={match?.[1] ?? ""} code={rawText} streaming={live} />;
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
            {children}
          </code>
        );
      },
      p({ children }) {
        return <p>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs)}</p>;
      },
      h1({ children }) {
        return <h1>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs)}</h1>;
      },
      h2({ children }) {
        return <h2>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs)}</h2>;
      },
      h3({ children }) {
        return <h3>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs)}</h3>;
      },
      h4({ children }) {
        return <h4>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs)}</h4>;
      },
      li({ children }) {
        return <li>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs, true)}</li>;
      },
      td({ children }) {
        return <td>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs)}</td>;
      },
      th({ children }) {
        return <th>{wrapStreamingChildren(children, coalesced, cursor, animator, nowMs)}</th>;
      },
    };

    return (
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {coalesced}
      </ReactMarkdown>
    );
  }, [animator, coalesced, live, reducedMotion, shouldAnimate]);

  useEffect(() => {
    if (reducedMotion || !animator.hasActiveBatches) return undefined;
    let rafId = 0;
    let cancelled = false;
    const step = () => {
      if (cancelled) return;
      const now = typeof performance !== "undefined" ? performance.now() : Date.now();
      const changed = animator.advance(now);
      const container = tailRef.current;
      if (container && (changed || !animator.hasActiveBatches)) {
        const nodes = container.querySelectorAll<HTMLElement>("[data-fade-offset]");
        nodes.forEach((el) => {
          const offsetAttr = el.getAttribute("data-fade-offset");
          if (offsetAttr === null) return;
          const offset = Number.parseInt(offsetAttr, 10);
          const alpha = Number.isFinite(offset) ? animator.alphaAt(offset, now) : 1;
          if (alpha >= 1) {
            el.style.opacity = "";
            el.removeAttribute("data-fade-offset");
          } else {
            el.style.opacity = String(alpha);
          }
        });
      }
      if (animator.hasActiveBatches) {
        rafId = requestAnimationFrame(step);
      } else if (!live) {
        wasLiveRef.current = false;
      }
    };
    rafId = requestAnimationFrame(step);
    return () => {
      cancelled = true;
      cancelAnimationFrame(rafId);
    };
  }, [animator, coalesced, live, reducedMotion]);

  return <div ref={tailRef}>{rendered}</div>;
});

export const MarkdownView = memo(function MarkdownView({
  content,
  streaming = false,
}: {
  content: string;
  streaming?: boolean;
}) {
  const blocks = useMemo(
    () => splitMarkdownBlocks(content, Boolean(streaming)),
    [content, streaming],
  );

  if (blocks.length === 0) return null;

  const lastIdx = blocks.length - 1;

  return (
    <div className="pi-prose space-y-2.5">
      {blocks.map((block, idx) =>
        idx < lastIdx ? (
          <StaticMarkdownBlock key={`b-${idx}`} content={block} />
        ) : (
          <TailMarkdownBlock key={`b-${idx}`} content={block} live={Boolean(streaming)} />
        ),
      )}
    </div>
  );
});
