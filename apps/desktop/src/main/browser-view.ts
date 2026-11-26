import { WebContentsView, type BrowserWindow } from "electron";
import {
  DESKTOP_IPC_CHANNELS,
  type BrowserBounds,
  type BrowserNavState,
  type BrowserPageContext,
} from "../shared/bridge.js";

export function normalizeBrowserUrl(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;

  const schemeMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(trimmed);
  if (schemeMatch) {
    const scheme = schemeMatch[1].toLowerCase();
    if (scheme !== "http" && scheme !== "https") {
      if (scheme === "localhost" || scheme === "127.0.0.1") {
        try {
          return new URL(`http://${trimmed}`).href;
        } catch {
          return null;
        }
      }
      return null;
    }
    try {
      return new URL(trimmed).href;
    } catch {
      return null;
    }
  }

  if (/^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(\/.*)?$/i.test(trimmed)) {
    try {
      return new URL(`http://${trimmed}`).href;
    } catch {
      return null;
    }
  }

  if (!/\s/.test(trimmed) && /^[^/\s]+\.[a-zA-Z]{2,}(:\d+)?(\/.*)?$/.test(trimmed)) {
    try {
      return new URL(`https://${trimmed}`).href;
    } catch {
      return null;
    }
  }

  return `https://www.google.com/search?q=${encodeURIComponent(trimmed)}`;
}

function clampBounds(bounds: BrowserBounds): BrowserBounds {
  return {
    x: Math.max(0, Math.round(bounds.x)),
    y: Math.max(0, Math.round(bounds.y)),
    width: Math.max(120, Math.round(bounds.width)),
    height: Math.max(120, Math.round(bounds.height)),
  };
}

function emptyNavState(): BrowserNavState {
  return {
    url: "",
    title: "",
    loading: false,
    canGoBack: false,
    canGoForward: false,
    error: null,
  };
}

export class EmbeddedBrowserManager {
  private view: WebContentsView | null = null;
  private hostWindow: BrowserWindow | null = null;
  private state: BrowserNavState = emptyNavState();

  private ensureView(win: BrowserWindow): WebContentsView {
    if (this.view && this.hostWindow === win) {
      return this.view;
    }
    this.destroy();

    const view = new WebContentsView({
      webPreferences: {
        partition: "persist:superpi-browser",
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        spellcheck: false,
      },
    });

    const wc = view.webContents;

    wc.setWindowOpenHandler(({ url }) => {
      const safe = normalizeBrowserUrl(url);
      if (safe) {
        void wc.loadURL(safe);
      }
      return { action: "deny" };
    });

    wc.on("will-navigate", (event, url) => {
      try {
        const parsed = new URL(url);
        if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
          event.preventDefault();
        }
      } catch {
        event.preventDefault();
      }
    });

    const syncState = (patch: Partial<BrowserNavState> = {}) => {
      const history = wc.navigationHistory;
      this.state = {
        ...this.state,
        url: wc.getURL() || this.state.url,
        title: wc.getTitle() || this.state.title,
        canGoBack: history ? history.canGoBack() : false,
        canGoForward: history ? history.canGoForward() : false,
        ...patch,
      };
      if (!win.isDestroyed()) {
        win.webContents.send(DESKTOP_IPC_CHANNELS.browserStateChanged, this.state);
      }
    };

    wc.on("did-start-loading", () => syncState({ loading: true, error: null }));
    wc.on("did-stop-loading", () => syncState({ loading: false }));
    wc.on("did-navigate", (_e, url) => syncState({ url, error: null }));
    wc.on("did-navigate-in-page", (_e, url) => syncState({ url }));
    wc.on("page-title-updated", (_e, title) => syncState({ title }));
    wc.on("did-fail-load", (_e, errorCode, errorDescription, validatedUrl, isMainFrame) => {
      if (!isMainFrame || errorCode === -3) return;
      syncState({
        loading: false,
        url: validatedUrl || this.state.url,
        error: errorDescription || `Load error (${errorCode})`,
      });
    });

    win.contentView.addChildView(view);
    view.setVisible(false);
    this.view = view;
    this.hostWindow = win;
    return view;
  }

  mount(win: BrowserWindow | null, bounds: BrowserBounds, url?: string): BrowserNavState {
    if (!win || win.isDestroyed()) return this.state;
    const view = this.ensureView(win);
    view.setBounds(clampBounds(bounds));
    if (url) {
      const normalized = normalizeBrowserUrl(url);
      if (normalized) {
        view.setVisible(true);
        void view.webContents.loadURL(normalized);
        return this.state;
      }
    }
    view.setVisible(Boolean(this.state.url));
    return this.state;
  }

  setBounds(bounds: BrowserBounds): void {
    if (!this.view) return;
    this.view.setBounds(clampBounds(bounds));
  }

  hide(): void {
    if (!this.view) return;
    this.view.setVisible(false);
  }

  navigate(win: BrowserWindow | null, rawUrl: string): BrowserNavState {
    if (!win || win.isDestroyed()) return this.state;
    const normalized = normalizeBrowserUrl(rawUrl);
    if (!normalized) {
      this.state = { ...this.state, error: "Unsupported URL" };
      return this.state;
    }
    const view = this.ensureView(win);
    view.setVisible(true);
    this.state = { ...this.state, url: normalized, loading: true, error: null };
    void view.webContents.loadURL(normalized);
    return this.state;
  }

  goBack(): BrowserNavState {
    const history = this.view?.webContents.navigationHistory;
    if (history?.canGoBack()) {
      history.goBack();
    }
    return this.state;
  }

  goForward(): BrowserNavState {
    const history = this.view?.webContents.navigationHistory;
    if (history?.canGoForward()) {
      history.goForward();
    }
    return this.state;
  }

  reload(): BrowserNavState {
    if (this.view && this.state.url) {
      this.view.webContents.reload();
    }
    return this.state;
  }

  stop(): BrowserNavState {
    if (this.view) {
      this.view.webContents.stop();
      this.state = { ...this.state, loading: false };
    }
    return this.state;
  }

  async getPageContext(): Promise<BrowserPageContext | null> {
    if (!this.view) return null;
    const wc = this.view.webContents;
    const url = wc.getURL();
    if (!url) return null;
    try {
      const result = (await wc.executeJavaScript(
        `(() => ({
          url: window.location.href || "",
          title: document.title || "",
          text: (document.body ? document.body.innerText : "").slice(0, 12000)
        }))()`,
        true,
      )) as BrowserPageContext;
      return result;
    } catch {
      return {
        url,
        title: wc.getTitle() || "",
        text: "",
      };
    }
  }

  destroy(): void {
    const view = this.view;
    const win = this.hostWindow;
    this.view = null;
    this.hostWindow = null;
    this.state = emptyNavState();
    if (!view) return;
    try {
      if (win && !win.isDestroyed()) {
        win.contentView.removeChildView(view);
      }
    } catch {
      void 0;
    }
    try {
      const wc = view.webContents as unknown as { close?: () => void };
      wc.close?.();
    } catch {
      void 0;
    }
  }
}
