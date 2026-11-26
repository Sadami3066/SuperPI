import { useEffect, useRef, useState } from "react";
import {
  IconArrowLeft,
  IconArrowRight,
  IconExternalLink,
  IconMessageForward,
  IconRefresh,
  IconWorld,
  IconX,
} from "@tabler/icons-react";
import { PiMascot } from "../features/shared/BrandAssets";
import { useI18n } from "../i18n";
import {
  type BrowserNavState,
  browserBack,
  browserForward,
  browserGetPageContext,
  browserHide,
  browserMount,
  browserNavigate,
  browserReload,
  browserSetBounds,
  browserStop,
  openExternalUrl,
  subscribeBrowserState,
} from "../platform/bridge";
import { useSessionStore } from "../stores/sessionStore";
import { useSettingsStore } from "../stores/settingsStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { Button, IconButton, Input } from "../ui";
import type { WorkbenchPanelContext } from "./registry";

const EMPTY_STATE: BrowserNavState = {
  url: "",
  title: "",
  loading: false,
  canGoBack: false,
  canGoForward: false,
  error: null,
};

const QUICK_BOOKMARKS = [
  { label: "GitHub", url: "https://github.com" },
  { label: "MDN Docs", url: "https://developer.mozilla.org" },
  { label: "localhost:3000", url: "http://localhost:3000" },
  { label: "localhost:5173", url: "http://localhost:5173" },
];

export default function BrowserPanel({ context }: { context: WorkbenchPanelContext }) {
  const { t } = useI18n();
  const dark = useSettingsStore((s) => s.dark);

  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const activePanelId = useWorkbenchStore((s) => s.activePanelId);
  const overlayCount = useWorkbenchStore((s) => s.overlayCount);

  const activeSessionId = useSessionStore((s) => s.activeSessionId) || context.sessionId || "";
  const composerText = useSessionStore((s) => s.composerTextBySession[activeSessionId] ?? "");
  const setComposerText = useSessionStore((s) => s.setComposerText);

  const [navState, setNavState] = useState<BrowserNavState>(EMPTY_STATE);
  const [urlInput, setUrlInput] = useState("");
  const viewportRef = useRef<HTMLDivElement | null>(null);

  const shouldShowView =
    workbenchOpen && activePanelId === "browser" && overlayCount === 0 && Boolean(navState.url);

  useEffect(() => {
    const unsubscribe = subscribeBrowserState((next) => {
      setNavState(next);
      if (next.url) {
        setUrlInput(next.url);
      }
    });
    return () => {
      unsubscribe();
      void browserHide();
    };
  }, []);

  useEffect(() => {
    const el = viewportRef.current;
    if (!el || !shouldShowView) {
      void browserHide();
      return undefined;
    }

    const syncBounds = (mountInitially: boolean) => {
      const rect = el.getBoundingClientRect();
      if (rect.width < 40 || rect.height < 40) return;
      const bounds = {
        x: Math.round(rect.left),
        y: Math.round(rect.top),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      };
      if (mountInitially) {
        void browserMount(bounds).then((state) => {
          if (state) setNavState(state);
        });
      } else {
        void browserSetBounds(bounds);
      }
    };

    syncBounds(true);
    const ro = new ResizeObserver(() => syncBounds(false));
    ro.observe(el);
    const onResize = () => syncBounds(false);
    window.addEventListener("resize", onResize);

    return () => {
      ro.disconnect();
      window.removeEventListener("resize", onResize);
      void browserHide();
    };
  }, [shouldShowView]);

  const navigateTo = async (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed) return;
    if (shouldShowView) {
      const next = await browserNavigate(trimmed);
      if (next) setNavState(next);
      return;
    }
    const el = viewportRef.current;
    if (el) {
      const rect = el.getBoundingClientRect();
      const mounted = await browserMount(
        {
          x: Math.round(rect.left),
          y: Math.round(rect.top),
          width: Math.max(120, Math.round(rect.width)),
          height: Math.max(120, Math.round(rect.height)),
        },
        trimmed,
      );
      if (mounted) setNavState(mounted);
    }
  };

  const handleQuotePage = async () => {
    const ctx = await browserGetPageContext();
    if (!ctx || !activeSessionId) return;
    const snippet = ctx.text.trim().slice(0, 1200);
    const header = ctx.title ? `[${ctx.title}](${ctx.url})` : ctx.url;
    const formatted = snippet ? `${header}\n\n${snippet}` : header;
    const next = composerText ? `${composerText.trimEnd()}\n\n${formatted}\n` : `${formatted}\n`;
    setComposerText(activeSessionId, next);
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div
        className="flex shrink-0 items-center gap-1 px-2.5 py-2"
        style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
      >
        <IconButton
          size="sm"
          label={t("browser.back")}
          disabled={!navState.canGoBack}
          onClick={() => void browserBack().then((s) => s && setNavState(s))}
        >
          <IconArrowLeft size={14.5} stroke={1.7} />
        </IconButton>

        <IconButton
          size="sm"
          label={t("browser.forward")}
          disabled={!navState.canGoForward}
          onClick={() => void browserForward().then((s) => s && setNavState(s))}
        >
          <IconArrowRight size={14.5} stroke={1.7} />
        </IconButton>

        <IconButton
          size="sm"
          label={navState.loading ? t("browser.stop") : t("browser.reload")}
          disabled={!navState.url}
          onClick={() =>
            void (navState.loading ? browserStop() : browserReload()).then((s) => s && setNavState(s))
          }
        >
          {navState.loading ? (
            <IconX size={14.5} stroke={1.7} />
          ) : (
            <IconRefresh size={14.5} stroke={1.7} />
          )}
        </IconButton>

        <form
          className="min-w-0 flex-1"
          onSubmit={(e) => {
            e.preventDefault();
            void navigateTo(urlInput);
          }}
        >
          <Input
            value={urlInput}
            onChange={(e) => setUrlInput(e.target.value)}
            placeholder={t("browser.urlPlaceholder")}
            className="h-8 text-[12.5px]"
          />
        </form>

        <IconButton
          size="sm"
          label={t("browser.sendToChat")}
          disabled={!navState.url || !activeSessionId}
          onClick={() => void handleQuotePage()}
        >
          <IconMessageForward size={14.5} stroke={1.7} />
        </IconButton>

        <IconButton
          size="sm"
          label={t("browser.openExternal")}
          disabled={!navState.url}
          onClick={() => {
            if (navState.url) void openExternalUrl(navState.url);
          }}
        >
          <IconExternalLink size={14.5} stroke={1.7} />
        </IconButton>
      </div>

      {navState.error && (
        <div
          className="shrink-0 px-3 py-1.5 text-[12px]"
          style={{ background: "var(--pi-wash)", color: "var(--pi-danger)" }}
        >
          {navState.error}
        </div>
      )}

      <div
        ref={viewportRef}
        className="relative min-h-0 flex-1 overflow-hidden"
        style={{ background: "var(--pi-surface)" }}
      >
        {!navState.url && (
          <div className="flex h-full flex-col items-center justify-center px-6 text-center">
            <PiMascot mood="wave" size={52} dark={dark} className="mb-3" />
            <div className="text-[14.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {t("browser.emptyTitle")}
            </div>
            <p
              className="mt-1.5 max-w-[340px] text-[12.5px] leading-[1.55]"
              style={{ color: "var(--pi-ink-tertiary)" }}
            >
              {t("browser.emptyDesc")}
            </p>
            <div className="mt-4 flex flex-wrap justify-center gap-2">
              {QUICK_BOOKMARKS.map((bm) => (
                <Button
                  key={bm.url}
                  variant="secondary"
                  size="sm"
                  onClick={() => void navigateTo(bm.url)}
                >
                  <IconWorld size={13.5} />
                  <span>{bm.label}</span>
                </Button>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
