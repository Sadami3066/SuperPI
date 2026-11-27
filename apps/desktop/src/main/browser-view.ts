import fs from "node:fs";
import path from "node:path";
import { app, session, WebContentsView, type BrowserWindow } from "electron";
import {
  DESKTOP_IPC_CHANNELS,
  type BrowserBounds,
  type BrowserNavState,
  type BrowserPageContext,
  type BrowserStorageSummary,
  type BrowserTabState,
} from "../shared/bridge.js";

const BROWSER_PERSIST_PARTITION = "persist:superpi-browser";
const BROWSER_EPHEMERAL_PARTITION = "superpi-browser-ephemeral";
const MAX_TABS = 12;

function prefsFilePath(): string | null {
  try {
    return path.join(app.getPath("userData"), "browser-prefs.json");
  } catch {
    return null;
  }
}

function readPersistSessionPref(): boolean {
  const file = prefsFilePath();
  if (!file) return true;
  try {
    if (!fs.existsSync(file)) return true;
    const parsed = JSON.parse(fs.readFileSync(file, "utf8")) as { persistSession?: boolean };
    return parsed.persistSession !== false;
  } catch {
    return true;
  }
}

function writePersistSessionPref(persistSession: boolean): void {
  const file = prefsFilePath();
  if (!file) return;
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify({ persistSession }), "utf8");
  } catch {
    void 0;
  }
}

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

function emptyNavState(tabs: BrowserTabState[] = [], activeTabId = ""): BrowserNavState {
  return {
    url: "",
    title: "",
    loading: false,
    canGoBack: false,
    canGoForward: false,
    error: null,
    activeTabId,
    tabs,
  };
}

interface ManagedTab {
  id: string;
  view: WebContentsView;
  state: BrowserTabState;
}

export class EmbeddedBrowserManager {
  private tabs = new Map<string, ManagedTab>();
  private tabOrder: string[] = [];
  private activeTabId = "";
  private hostWindow: BrowserWindow | null = null;
  private lastBounds: BrowserBounds = { x: 0, y: 0, width: 480, height: 480 };
  private visible = false;
  private tabSeq = 0;
  private persistSession = readPersistSessionPref();

  private activePartition(): string {
    return this.persistSession ? BROWSER_PERSIST_PARTITION : BROWSER_EPHEMERAL_PARTITION;
  }

  private buildSnapshot(): BrowserNavState {
    const tabList = this.tabOrder
      .map((id) => this.tabs.get(id)?.state)
      .filter((t): t is BrowserTabState => Boolean(t));
    const active = this.tabs.get(this.activeTabId)?.state;
    if (!active) {
      return emptyNavState(tabList, "");
    }
    return {
      url: active.url,
      title: active.title,
      loading: active.loading,
      canGoBack: active.canGoBack,
      canGoForward: active.canGoForward,
      error: active.error,
      activeTabId: active.id,
      tabs: tabList,
    };
  }

  private emitSnapshot(): BrowserNavState {
    const snapshot = this.buildSnapshot();
    if (this.hostWindow && !this.hostWindow.isDestroyed()) {
      this.hostWindow.webContents.send(DESKTOP_IPC_CHANNELS.browserStateChanged, snapshot);
    }
    return snapshot;
  }

  private syncVisibility(): void {
    for (const [id, tab] of this.tabs) {
      const shouldShow = this.visible && id === this.activeTabId && Boolean(tab.state.url);
      if (shouldShow) {
        tab.view.setBounds(this.lastBounds);
      }
      tab.view.setVisible(shouldShow);
    }
  }

  private createManagedTab(win: BrowserWindow, requestedId?: string): ManagedTab {
    if (this.hostWindow && this.hostWindow !== win) {
      this.destroy();
    }
    this.hostWindow = win;

    const partition = this.activePartition();
    const activeSess = session.fromPartition(partition);
    const cleanUa = activeSess
      .getUserAgent()
      .replace(/\sElectron\/[^\s]+/g, "")
      .replace(/\sSuperPI\/[^\s]+/gi, "");
    if (cleanUa && cleanUa !== activeSess.getUserAgent()) {
      activeSess.setUserAgent(cleanUa);
    }

    const view = new WebContentsView({
      webPreferences: {
        partition,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        spellcheck: false,
      },
    });

    const id = requestedId?.trim() || `tab-${++this.tabSeq}`;
    const managed: ManagedTab = {
      id,
      view,
      state: {
        id,
        url: "",
        title: "",
        favicon: "",
        loading: false,
        canGoBack: false,
        canGoForward: false,
        error: null,
      },
    };

    const wc = view.webContents;

    wc.setWindowOpenHandler(({ url }) => {
      const safe = normalizeBrowserUrl(url);
      if (safe) {
        if (this.hostWindow && !this.hostWindow.isDestroyed()) {
          this.hostWindow.webContents.send(DESKTOP_IPC_CHANNELS.browserOpenTabRequested, safe);
        } else {
          void wc.loadURL(safe);
        }
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

    const syncTab = (patch: Partial<BrowserTabState> = {}) => {
      if (!this.tabs.has(id)) return;
      const history = wc.navigationHistory;
      managed.state = {
        ...managed.state,
        url: wc.getURL() || managed.state.url,
        title: wc.getTitle() || managed.state.title,
        canGoBack: history ? history.canGoBack() : false,
        canGoForward: history ? history.canGoForward() : false,
        ...patch,
      };
      this.syncVisibility();
      this.emitSnapshot();
    };

    wc.on("did-start-loading", () => syncTab({ loading: true, error: null }));
    wc.on("did-stop-loading", () => syncTab({ loading: false }));
    wc.on("did-navigate", (_e, url) => syncTab({ url, error: null }));
    wc.on("did-navigate-in-page", (_e, url) => syncTab({ url }));
    wc.on("page-title-updated", (_e, title) => syncTab({ title }));
    wc.on("page-favicon-updated", (_e, favicons) => {
      const icon = favicons[0] ?? "";
      if (icon) syncTab({ favicon: icon });
    });
    wc.on("did-fail-load", (_e, errorCode, errorDescription, validatedUrl, isMainFrame) => {
      if (!isMainFrame || errorCode === -3) return;
      syncTab({
        loading: false,
        url: validatedUrl || managed.state.url,
        error: errorDescription || `Load error (${errorCode})`,
      });
    });

    win.contentView.addChildView(view);
    view.setVisible(false);
    this.tabs.set(id, managed);
    this.tabOrder.push(id);
    return managed;
  }

  private ensureTab(win: BrowserWindow, tabId?: string): ManagedTab {
    const key = tabId?.trim();
    if (key) {
      if (this.hostWindow === win) {
        const existing = this.tabs.get(key);
        if (existing) {
          this.activeTabId = existing.id;
          return existing;
        }
      }
      const created = this.createManagedTab(win, key);
      this.activeTabId = created.id;
      return created;
    }
    if (this.hostWindow === win && this.activeTabId) {
      const existing = this.tabs.get(this.activeTabId);
      if (existing) return existing;
    }
    const created = this.createManagedTab(win);
    this.activeTabId = created.id;
    return created;
  }

  mount(
    win: BrowserWindow | null,
    bounds: BrowserBounds,
    url?: string,
    tabId?: string,
  ): BrowserNavState {
    if (!win || win.isDestroyed()) return this.buildSnapshot();
    this.lastBounds = clampBounds(bounds);
    this.visible = true;
    const tab = this.ensureTab(win, tabId);
    if (url) {
      const normalized = normalizeBrowserUrl(url);
      if (normalized) {
        tab.state = { ...tab.state, url: normalized, loading: true, error: null };
        this.syncVisibility();
        void tab.view.webContents.loadURL(normalized);
        return this.emitSnapshot();
      }
    }
    this.syncVisibility();
    return this.emitSnapshot();
  }

  createTab(win: BrowserWindow | null, bounds: BrowserBounds, url?: string): BrowserNavState {
    if (!win || win.isDestroyed()) return this.buildSnapshot();
    this.lastBounds = clampBounds(bounds);
    this.visible = true;
    if (this.tabOrder.length >= MAX_TABS) {
      return this.emitSnapshot();
    }
    const tab = this.createManagedTab(win);
    this.activeTabId = tab.id;
    if (url) {
      const normalized = normalizeBrowserUrl(url);
      if (normalized) {
        tab.state = { ...tab.state, url: normalized, loading: true, error: null };
        this.syncVisibility();
        void tab.view.webContents.loadURL(normalized);
        return this.emitSnapshot();
      }
    }
    this.syncVisibility();
    return this.emitSnapshot();
  }

  switchTab(win: BrowserWindow | null, tabId: string, bounds?: BrowserBounds): BrowserNavState {
    if (!win || win.isDestroyed()) return this.buildSnapshot();
    if (!this.tabs.has(tabId)) return this.buildSnapshot();
    if (bounds) this.lastBounds = clampBounds(bounds);
    this.activeTabId = tabId;
    this.visible = true;
    this.syncVisibility();
    return this.emitSnapshot();
  }

  closeTab(tabId: string): BrowserNavState {
    const tab = this.tabs.get(tabId);
    if (!tab) return this.buildSnapshot();
    const idx = this.tabOrder.indexOf(tabId);
    this.tabs.delete(tabId);
    if (idx >= 0) this.tabOrder.splice(idx, 1);

    try {
      if (this.hostWindow && !this.hostWindow.isDestroyed()) {
        this.hostWindow.contentView.removeChildView(tab.view);
      }
    } catch {
      void 0;
    }
    try {
      const wc = tab.view.webContents as unknown as { close?: () => void };
      wc.close?.();
    } catch {
      void 0;
    }

    if (this.activeTabId === tabId) {
      const nextId = this.tabOrder[Math.min(idx, this.tabOrder.length - 1)] ?? "";
      this.activeTabId = nextId;
    }
    this.syncVisibility();
    return this.emitSnapshot();
  }

  setBounds(bounds: BrowserBounds, tabId?: string): void {
    this.lastBounds = clampBounds(bounds);
    const targetId = tabId?.trim() || this.activeTabId;
    const active = this.tabs.get(targetId);
    if (active && this.visible && targetId === this.activeTabId && active.state.url) {
      active.view.setBounds(this.lastBounds);
    }
  }

  hide(tabId?: string): void {
    if (tabId?.trim()) {
      const key = tabId.trim();
      const tab = this.tabs.get(key);
      if (tab) tab.view.setVisible(false);
      if (this.activeTabId === key) {
        this.visible = false;
      }
      return;
    }
    this.visible = false;
    for (const tab of this.tabs.values()) {
      tab.view.setVisible(false);
    }
  }

  navigate(win: BrowserWindow | null, rawUrl: string, tabId?: string): BrowserNavState {
    if (!win || win.isDestroyed()) return this.buildSnapshot();
    const tab = this.ensureTab(win, tabId);
    const normalized = normalizeBrowserUrl(rawUrl);
    if (!normalized) {
      tab.state = { ...tab.state, error: "Unsupported URL" };
      return this.emitSnapshot();
    }
    this.visible = true;
    tab.state = { ...tab.state, url: normalized, loading: true, error: null };
    this.syncVisibility();
    void tab.view.webContents.loadURL(normalized);
    return this.emitSnapshot();
  }

  goBack(tabId?: string): BrowserNavState {
    const tab = this.tabs.get(tabId?.trim() || this.activeTabId);
    const history = tab?.view.webContents.navigationHistory;
    if (history?.canGoBack()) {
      history.goBack();
    }
    return this.buildSnapshot();
  }

  goForward(tabId?: string): BrowserNavState {
    const tab = this.tabs.get(tabId?.trim() || this.activeTabId);
    const history = tab?.view.webContents.navigationHistory;
    if (history?.canGoForward()) {
      history.goForward();
    }
    return this.buildSnapshot();
  }

  reload(tabId?: string): BrowserNavState {
    const tab = this.tabs.get(tabId?.trim() || this.activeTabId);
    if (tab && tab.state.url) {
      tab.view.webContents.reload();
    }
    return this.buildSnapshot();
  }

  stop(tabId?: string): BrowserNavState {
    const tab = this.tabs.get(tabId?.trim() || this.activeTabId);
    if (tab) {
      tab.view.webContents.stop();
      tab.state = { ...tab.state, loading: false };
      return this.emitSnapshot();
    }
    return this.buildSnapshot();
  }

  async getPageContext(tabId?: string): Promise<BrowserPageContext | null> {
    const tab = this.tabs.get(tabId?.trim() || this.activeTabId);
    if (!tab) return null;
    const wc = tab.view.webContents;
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

  async getStorageSummary(): Promise<BrowserStorageSummary> {
    try {
      const cookies = await session.fromPartition(BROWSER_PERSIST_PARTITION).cookies.get({});
      const domainCounts = new Map<string, number>();
      for (const c of cookies) {
        const domain = (c.domain || "").replace(/^\.+/, "").toLowerCase();
        if (!domain) continue;
        domainCounts.set(domain, (domainCounts.get(domain) ?? 0) + 1);
      }
      const sites = Array.from(domainCounts.entries())
        .map(([domain, cookieCount]) => ({ domain, cookieCount }))
        .sort((a, b) => b.cookieCount - a.cookieCount || a.domain.localeCompare(b.domain));
      return {
        persistSession: this.persistSession,
        totalCookies: cookies.length,
        sites,
      };
    } catch {
      return {
        persistSession: this.persistSession,
        totalCookies: 0,
        sites: [],
      };
    }
  }

  async setPersistSession(persist: boolean): Promise<BrowserStorageSummary> {
    const next = Boolean(persist);
    if (next !== this.persistSession) {
      this.persistSession = next;
      writePersistSessionPref(next);
      this.destroy();
      this.emitSnapshot();
    }
    return this.getStorageSummary();
  }

  async clearDomainCookies(domain: string): Promise<BrowserStorageSummary> {
    const target = domain.trim().replace(/^\.+/, "").toLowerCase();
    if (!target) return this.getStorageSummary();
    try {
      const sess = session.fromPartition(BROWSER_PERSIST_PARTITION);
      const all = await sess.cookies.get({});
      for (const c of all) {
        const normalized = (c.domain || "").replace(/^\.+/, "").toLowerCase();
        if (normalized === target || normalized.endsWith(`.${target}`)) {
          const scheme = c.secure ? "https" : "http";
          const host = (c.domain || target).replace(/^\.+/, "");
          const cookiePath = c.path || "/";
          await sess.cookies.remove(`${scheme}://${host}${cookiePath}`, c.name).catch(() => void 0);
        }
      }
      await sess.cookies.flushStore();
    } catch {
      void 0;
    }
    return this.getStorageSummary();
  }

  async clearCache(): Promise<void> {
    try {
      await session.fromPartition(BROWSER_PERSIST_PARTITION).clearCache();
      await session.fromPartition(BROWSER_EPHEMERAL_PARTITION).clearCache();
    } catch {
      void 0;
    }
  }

  async clearAllData(): Promise<BrowserStorageSummary> {
    try {
      const sess = session.fromPartition(BROWSER_PERSIST_PARTITION);
      await sess.clearStorageData();
      await sess.clearCache();
      await sess.cookies.flushStore();
      await session.fromPartition(BROWSER_EPHEMERAL_PARTITION).clearStorageData();
    } catch {
      void 0;
    }
    return this.getStorageSummary();
  }

  async flushCookies(): Promise<void> {
    try {
      await session.fromPartition(BROWSER_PERSIST_PARTITION).cookies.flushStore();
    } catch {
      void 0;
    }
  }

  destroy(): void {
    const win = this.hostWindow;
    const allTabs = Array.from(this.tabs.values());
    this.tabs.clear();
    this.tabOrder = [];
    this.activeTabId = "";
    this.hostWindow = null;
    this.visible = false;
    for (const tab of allTabs) {
      try {
        if (win && !win.isDestroyed()) {
          win.contentView.removeChildView(tab.view);
        }
      } catch {
        void 0;
      }
      try {
        const wc = tab.view.webContents as unknown as { close?: () => void };
        wc.close?.();
      } catch {
        void 0;
      }
    }
  }
}
