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
import { IconCheck, IconCopy } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { openExternalUrl } from "../../platform/bridge";
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

export function stabilizeStreamingMarkdown(raw: string, streaming?: boolean): string {
  if (!streaming) return raw;
  const fenceMatches = raw.match(/^\s*```/gm);
  if (fenceMatches && fenceMatches.length % 2 === 1) {
    return `${raw}\n\`\`\``;
  }
  return raw;
}

function CodeBlock({ language, code }: { language: string; code: string }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    };
  }, []);

  const highlightedHtml = useMemo(() => {
    const lang = language.trim().toLowerCase();
    try {
      if (lang && hljs.getLanguage(lang)) {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
      }
    } catch {
      return null;
    }
    return null;
  }, [code, language]);

  return (
    <div
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
        <span
          className="text-[11.5px] font-medium"
          style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
        >
          {language || "code"}
        </span>
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
          {copied ? <IconCheck size={14} stroke={1.7} /> : <IconCopy size={14} stroke={1.7} />}
        </button>
      </div>
      <pre
        data-scrollable
        className="overflow-x-auto px-3.5 py-3 text-[13px] leading-[1.6]"
        style={{ fontFamily: "var(--pi-font-mono)" }}
      >
        {highlightedHtml !== null ? (
          <code className="hljs" dangerouslySetInnerHTML={{ __html: highlightedHtml }} />
        ) : (
          <code>{code}</code>
        )}
      </pre>
    </div>
  );
}

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

function useCoalescedStreamText(text: string): string {
  const [displayed, setDisplayed] = useState(text);
  const latestRef = useRef(text);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  latestRef.current = text;

  useEffect(() => {
    if (text === displayed) return;
    if (timerRef.current !== null) return;
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setDisplayed(latestRef.current);
    }, STREAM_COALESCE_INTERVAL_MS);
  }, [text, displayed]);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    };
  }, []);

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
    if (!node) return node;
    const idx = source.indexOf(node, cursor.pos);
    if (idx < 0) return node;
    cursor.pos = idx + node.length;
    const runs = splitTextByAlpha(node, idx, animator, nowMs);
    if (runs.length === 1 && runs[0].alpha >= 1) return node;
    return runs.map((run, i) =>
      run.alpha >= 1 ? (
        <Fragment key={i}>{run.text}</Fragment>
      ) : (
        <span key={i} style={{ opacity: run.alpha }}>
          {run.text}
        </span>
      ),
    );
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

function StreamingTailBlock({ content }: { content: string }) {
  const coalesced = useCoalescedStreamText(content);
  const reducedMotion =
    typeof window !== "undefined" &&
    Boolean(window.matchMedia?.("(prefers-reduced-motion: reduce)").matches);

  const animatorRef = useRef<StreamTextFadeAnimator | null>(null);
  if (!animatorRef.current) {
    animatorRef.current = new StreamTextFadeAnimator();
  }
  const animator = animatorRef.current;

  const [, setTick] = useState(0);
  const nowMs = typeof performance !== "undefined" ? performance.now() : Date.now();
  if (reducedMotion) {
    animator.finish();
  } else {
    animator.sync(coalesced, true, true, nowMs);
  }

  const active = animator.hasActiveBatches;
  useEffect(() => {
    if (!active || reducedMotion) return;
    let rafId = 0;
    let cancelled = false;
    const step = () => {
      if (cancelled) return;
      const now = typeof performance !== "undefined" ? performance.now() : Date.now();
      if (animator.advance(now)) {
        setTick((t) => (t + 1) & 0xffff);
      }
      if (animator.hasActiveBatches) {
        rafId = requestAnimationFrame(step);
      }
    };
    rafId = requestAnimationFrame(step);
    return () => {
      cancelled = true;
      cancelAnimationFrame(rafId);
    };
  }, [active, animator, reducedMotion, coalesced]);

  if (reducedMotion) {
    return <StaticMarkdownBlock content={coalesced} />;
  }

  const cursor = { pos: 0 };
  const components: Components = {
    ...baseMarkdownComponents,
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
}

export const MarkdownView = memo(function MarkdownView({
  content,
  streaming,
}: {
  content: string;
  streaming?: boolean;
}) {
  const blocks = useMemo(
    () => splitMarkdownBlocks(content, Boolean(streaming)),
    [content, streaming],
  );

  if (blocks.length === 0) return null;

  if (!streaming) {
    return (
      <div className="pi-prose">
        <StaticMarkdownBlock content={content} />
      </div>
    );
  }

  const prefixBlocks = blocks.slice(0, -1);
  const tailBlock = blocks[blocks.length - 1];

  return (
    <div className="pi-prose space-y-2.5">
      {prefixBlocks.map((block, idx) => (
        <div key={`p-${idx}`} className={idx > 0 ? "pi-stream-block-enter" : undefined}>
          <StaticMarkdownBlock content={block} />
        </div>
      ))}
      <div key={`t-${prefixBlocks.length}`} className="pi-stream-block-enter">
        <StreamingTailBlock content={tailBlock} />
      </div>
    </div>
  );
});
