import { memo, useEffect, useMemo, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
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

export const MarkdownView = memo(function MarkdownView({
  content,
  streaming,
}: {
  content: string;
  streaming?: boolean;
}) {
  const normalized = useMemo(
    () => stabilizeStreamingMarkdown(content, streaming),
    [content, streaming],
  );

  return (
    <div className="pi-prose">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
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
        }}
      >
        {normalized}
      </ReactMarkdown>
    </div>
  );
});
