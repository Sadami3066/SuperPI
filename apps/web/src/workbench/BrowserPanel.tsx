import { useCallback, useEffect, useRef, useState } from "react";
import {
  IconArrowLeft,
  IconArrowRight,
  IconExternalLink,
  IconHome,
  IconMessageForward,
  IconRefresh,
  IconWorld,
  IconX,
} from "@tabler/icons-react";
import { PiMascot } from "../features/shared/BrandAssets";
import { useI18n } from "../i18n";
import {
  type BrowserTabState,
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
import { DEFAULT_BROWSER_HOMEPAGE, useSettingsStore } from "../stores/settingsStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { Button, IconButton, Input } from "../ui";
import type { WorkbenchPanelContext } from "./registry";

const EMPTY_TAB: BrowserTabState = {
  id: "",
  url: "",
  title: "",
  favicon: "",
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

function tabDisplayLabel(title: string, url: string, fallback: string): string {
  if (title.trim()) return title.trim();
  if (url) {
    try {
      return new URL(url).hostname || url;
    } catch {
      return url;
    }
  }
  return fallback;
}

function resolveUserBrowserInput(
  raw: string,
  engine: "google" | "bing" | "duckduckgo" | "baidu",
): string {
  const trimmed = raw.trim();
  if (!trimmed) return "";
  if (
    /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed) ||
    /^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/.*)?$/i.test(trimmed) ||
    (!/\s/.test(trimmed) && /^[^/\s]+\.[a-zA-Z]{2,}(:\d+)?(\/.*)?$/.test(trimmed))
  ) {
    return trimmed;
  }
  const encoded = encodeURIComponent(trimmed);
  switch (engine) {
    case "bing":
      return `https://www.bing.com/search?q=${encoded}`;
    case "duckduckgo":
      return `https://duckduckgo.com/?q=${encoded}`;
    case "baidu":
      return `https://www.baidu.com/s?wd=${encoded}`;
    default:
      return `https://www.google.com/search?q=${encoded}`;
  }
}

export default function BrowserPanel({ context }: { context: WorkbenchPanelContext }) {
  const { t } = useI18n();
  const dark = useSettingsStore((s) => s.dark);
  const searchEngine = useSettingsStore((s) => s.browserSearchEngine);
  const browserHomepage = useSettingsStore((s) => s.browserHomepage);

  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const activePanelId = useWorkbenchStore((s) => s.activePanelId);
  const overlayCount = useWorkbenchStore((s) => s.overlayCount);
  const updateTabTitle = useWorkbenchStore((s) => s.updateTabTitle);

  const activeSessionId = useSessionStore((s) => s.activeSessionId) || context.sessionId || "";
  const setComposerText = useSessionStore((s) => s.setComposerText);

  const myTabId = context.tabId || "browser-default";
  const initialPayloadUrl =
    typeof context.payload?.url === "string" ? context.payload.url.trim() : "";
  const effectiveInitialUrl = initialPayloadUrl || browserHomepage.trim();

  const [tabState, setTabState] = useState<BrowserTabState>({
    ...EMPTY_TAB,
    id: myTabId,
    url: effectiveInitialUrl,
  });
  const [urlInput, setUrlInput] = useState(effectiveInitialUrl);
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const initialNavDoneRef = useRef(false);

  const isTabVisible =
    context.isActiveTab !== undefined
      ? context.isActiveTab
      : workbenchOpen && activePanelId === "browser";
  const shouldShowView = isTabVisible && overlayCount === 0 && Boolean(tabState.url);

  const getViewportBounds = useCallback(() => {
    const el = viewportRef.current;
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    if (rect.width < 40 || rect.height < 40) return null;
    return {
      x: Math.round(rect.left),
      y: Math.round(rect.top),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
    };
  }, []);

  const lastKnownUrlRef = useRef<string>("");
  const addressFocusedRef = useRef(false);

  const syncFromNavSnapshot = useCallback(
    (snapshot: { tabs?: BrowserTabState[] } | null) => {
      if (!snapshot?.tabs) return;
      const mine = snapshot.tabs.find((x) => x.id === myTabId);
      if (!mine) return;
      setTabState(mine);
      const nextUrl = mine.url || "";
      if (nextUrl) {
        lastKnownUrlRef.current = nextUrl;
      }
      if (!addressFocusedRef.current) {
        setUrlInput(nextUrl);
      }
      const label = tabDisplayLabel(mine.title, mine.url, t("wb.browserTitle"));
      if (context.tabId) {
        updateTabTitle(context.tabId, label);
      }
    },
    [context.tabId, myTabId, t, updateTabTitle],
  );

  useEffect(() => {
    const unsubscribe = subscribeBrowserState((next) => {
      syncFromNavSnapshot(next);
    });
    return () => {
      unsubscribe();
      void browserHide(myTabId);
    };
  }, [myTabId, syncFromNavSnapshot]);

  useEffect(() => {
    const el = viewportRef.current;
    if (!el || !shouldShowView) {
      void browserHide(myTabId);
      return undefined;
    }

    let boundsRaf: number | null = null;
    let mountedOnce = false;
    const syncBounds = (allowMount: boolean) => {
      const bounds = getViewportBounds();
      if (!bounds) return;
      if (allowMount && !mountedOnce) {
        mountedOnce = true;
        let mountUrl: string | undefined;
        if (lastKnownUrlRef.current) {
          mountUrl = lastKnownUrlRef.current;
        } else if (effectiveInitialUrl && !initialNavDoneRef.current) {
          initialNavDoneRef.current = true;
          mountUrl = resolveUserBrowserInput(effectiveInitialUrl, searchEngine) || undefined;
        }
        void browserMount(bounds, mountUrl, myTabId).then(syncFromNavSnapshot);
      } else if (mountedOnce) {
        if (boundsRaf !== null) cancelAnimationFrame(boundsRaf);
        boundsRaf = requestAnimationFrame(() => {
          boundsRaf = null;
          void browserSetBounds(bounds, myTabId);
        });
      }
    };

    syncBounds(true);
    const ro = new ResizeObserver(() => syncBounds(true));
    ro.observe(el);
    const onResize = () => syncBounds(false);
    window.addEventListener("resize", onResize);

    return () => {
      if (boundsRaf !== null) cancelAnimationFrame(boundsRaf);
      ro.disconnect();
      window.removeEventListener("resize", onResize);
      void browserHide(myTabId);
    };
  }, [effectiveInitialUrl, getViewportBounds, myTabId, searchEngine, shouldShowView, syncFromNavSnapshot]);

  const navigateTo = async (raw: string) => {
    const resolved = resolveUserBrowserInput(raw, searchEngine);
    if (!resolved) return;
    lastKnownUrlRef.current = resolved;
    initialNavDoneRef.current = true;
    if (shouldShowView) {
      const next = await browserNavigate(resolved, myTabId);
      syncFromNavSnapshot(next);
      return;
    }
    const bounds = getViewportBounds();
    if (!bounds) return;
    const mounted = await browserMount(bounds, resolved, myTabId);
    syncFromNavSnapshot(mounted);
  };

  const handleQuotePage = async () => {
    const ctx = await browserGetPageContext(myTabId);
    if (!ctx) return;
    const sessionState = useSessionStore.getState();
    const targetKey =
      activeSessionId || (sessionState.draftBotId ? `__draft__:${sessionState.draftBotId}` : "");
    if (!targetKey) return;
    const snippet = ctx.text.trim().slice(0, 1200);
    const header = ctx.title ? `[${ctx.title}](${ctx.url})` : ctx.url;
    const formatted = snippet ? `${header}\n\n${snippet}` : header;
    const current = sessionState.composerTextBySession[targetKey] ?? "";
    const next = current ? `${current.trimEnd()}\n\n${formatted}\n` : `${formatted}\n`;
    setComposerText(targetKey, next);
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
          disabled={!tabState.canGoBack}
          onClick={() => void browserBack(myTabId).then(syncFromNavSnapshot)}
        >
          <IconArrowLeft size={14.5} stroke={1.7} />
        </IconButton>

        <IconButton
          size="sm"
          label={t("browser.forward")}
          disabled={!tabState.canGoForward}
          onClick={() => void browserForward(myTabId).then(syncFromNavSnapshot)}
        >
          <IconArrowRight size={14.5} stroke={1.7} />
        </IconButton>

        <IconButton
          size="sm"
          label={tabState.loading ? t("browser.stop") : t("browser.reload")}
          disabled={!tabState.url}
          onClick={() =>
            void (tabState.loading ? browserStop(myTabId) : browserReload(myTabId)).then(
              syncFromNavSnapshot,
            )
          }
        >
          {tabState.loading ? (
            <IconX size={14.5} stroke={1.7} />
          ) : (
            <IconRefresh size={14.5} stroke={1.7} />
          )}
        </IconButton>

        <IconButton
          size="sm"
          label={t("browser.home")}
          onClick={() => void navigateTo(browserHomepage.trim() || DEFAULT_BROWSER_HOMEPAGE)}
        >
          <IconHome size={14.5} stroke={1.7} />
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
            onFocus={() => {
              addressFocusedRef.current = true;
            }}
            onBlur={() => {
              addressFocusedRef.current = false;
            }}
            placeholder={t("browser.urlPlaceholder")}
            className="h-8 text-[12.5px]"
          />
        </form>

        <IconButton
          size="sm"
          label={t("browser.sendToChat")}
          disabled={!tabState.url}
          onClick={() => void handleQuotePage()}
        >
          <IconMessageForward size={14.5} stroke={1.7} />
        </IconButton>

        <IconButton
          size="sm"
          label={t("browser.openExternal")}
          disabled={!tabState.url}
          onClick={() => {
            if (tabState.url) void openExternalUrl(tabState.url);
          }}
        >
          <IconExternalLink size={14.5} stroke={1.7} />
        </IconButton>
      </div>

      {tabState.error && (
        <div
          className="shrink-0 px-3 py-1.5 text-[12px]"
          style={{ background: "var(--pi-wash)", color: "var(--pi-danger)" }}
        >
          {tabState.error}
        </div>
      )}

      <div
        ref={viewportRef}
        className="relative min-h-0 flex-1 overflow-hidden"
        style={{ background: "var(--pi-surface)" }}
      >
        {!tabState.url && (
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
