import { type RefObject, useEffect, useRef, useState } from "react";
import { useI18n } from "../../i18n";
import { BrandIcon } from "../shared/BrandAssets";

interface SelectionBarState {
  text: string;
  x: number;
  y: number;
}

export function SelectionReplyBar({
  containerRef,
  onQuote,
}: {
  containerRef: RefObject<HTMLElement | null>;
  onQuote: (text: string) => void;
}) {
  const { t } = useI18n();
  const [bar, setBar] = useState<SelectionBarState | null>(null);
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const dismiss = () => setBar(null);

    const handleMouseUp = () => {
      window.requestAnimationFrame(() => {
        const container = containerRef.current;
        const selection = window.getSelection();
        if (!container || !selection || selection.isCollapsed) {
          setBar(null);
          return;
        }
        const text = selection.toString().trim();
        if (!text) {
          setBar(null);
          return;
        }
        const anchorNode = selection.anchorNode;
        const focusNode = selection.focusNode;
        if (
          !anchorNode ||
          !focusNode ||
          !container.contains(anchorNode) ||
          !container.contains(focusNode)
        ) {
          setBar(null);
          return;
        }
        const range = selection.rangeCount > 0 ? selection.getRangeAt(0) : null;
        if (!range) {
          setBar(null);
          return;
        }
        const rect = range.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) {
          setBar(null);
          return;
        }
        const x = Math.max(80, Math.min(window.innerWidth - 110, rect.left + rect.width / 2));
        const y = Math.max(48, rect.top - 8);
        setBar({ text, x, y });
        setCopied(false);
      });
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") dismiss();
    };

    const containerEl = containerRef.current;
    document.addEventListener("mouseup", handleMouseUp);
    document.addEventListener("keydown", handleKeyDown);
    containerEl?.addEventListener("scroll", dismiss, { passive: true });
    return () => {
      document.removeEventListener("mouseup", handleMouseUp);
      document.removeEventListener("keydown", handleKeyDown);
      containerEl?.removeEventListener("scroll", dismiss);
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    };
  }, [containerRef]);

  if (!bar) return null;

  return (
    <div
      className="pi-popover fixed z-40 flex -translate-x-1/2 -translate-y-full items-center gap-0.5 rounded-[10px] p-1"
      style={{
        left: bar.x,
        top: bar.y,
        background: "var(--pi-surface)",
        border: "0.5px solid var(--pi-hairline)",
      }}
      onMouseDown={(e) => e.preventDefault()}
    >
      <button
        type="button"
        onClick={() => {
          void navigator.clipboard?.writeText(bar.text);
          setCopied(true);
          if (timerRef.current !== null) clearTimeout(timerRef.current);
          timerRef.current = setTimeout(() => {
            setCopied(false);
            setBar(null);
          }, 800);
        }}
        className="flex h-7 items-center gap-1.5 rounded-[7px] px-2 text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
        style={{ color: "var(--pi-ink)" }}
      >
        <BrandIcon name={copied ? "status/confirm" : "action/copy"} size={13} />
        <span>{copied ? t("chat.copied") : t("chat.copy")}</span>
      </button>
      <button
        type="button"
        onClick={() => {
          onQuote(bar.text.slice(0, 220));
          window.getSelection()?.removeAllRanges();
          setBar(null);
        }}
        className="flex h-7 items-center gap-1.5 rounded-[7px] px-2 text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
        style={{ color: "var(--pi-accent)" }}
      >
        <BrandIcon name="action/quote" size={13} />
        <span>{t("chat.selectionQuote")}</span>
      </button>
    </div>
  );
}
