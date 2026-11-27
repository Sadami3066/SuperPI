export interface DesktopHostConnection {
  baseUrl: string;
  token: string;
  port?: number;
  pid?: number;
  ownedSidecar?: boolean;
  homeDir?: string;
  source: "electron" | "hash" | "storage" | "manual";
}

export interface DesktopEnv {
  isElectron: boolean;
  version: string;
  platform: "darwin" | "win32" | "linux" | "web";
  arch: string;
  homeDir: string;
}

export interface BrowserBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface BrowserTabState {
  id: string;
  url: string;
  title: string;
  favicon: string;
  loading: boolean;
  canGoBack: boolean;
  canGoForward: boolean;
  error: string | null;
}

export interface BrowserNavState {
  url: string;
  title: string;
  loading: boolean;
  canGoBack: boolean;
  canGoForward: boolean;
  error: string | null;
  activeTabId?: string;
  tabs?: BrowserTabState[];
}

export interface BrowserPageContext {
  url: string;
  title: string;
  text: string;
}

export interface BrowserSiteStorageItem {
  domain: string;
  cookieCount: number;
}

export interface BrowserStorageSummary {
  persistSession: boolean;
  totalCookies: number;
  sites: BrowserSiteStorageItem[];
}

interface ElectronPreloadBridge {
  getEnv(): Promise<Omit<DesktopEnv, "isElectron"> & { isElectron: true }>;
  getHostConnection(): Promise<{
    baseUrl: string;
    token: string;
    port: number;
    pid: number;
    ownedSidecar: boolean;
    homeDir: string;
  }>;
  reconnectHost(): Promise<{
    baseUrl: string;
    token: string;
    port: number;
    pid: number;
    ownedSidecar: boolean;
    homeDir: string;
  }>;
  pickDirectory(title?: string): Promise<string | null>;
  openExternal(url: string): Promise<void>;
  revealInFileManager(absPath: string): Promise<void>;
  setLanguage(locale: "zh-CN" | "en"): Promise<void>;
  setThemeAppearance(dark: boolean, surfaceColor: string, inkColor: string): Promise<void>;
  windowMinimize(): Promise<void>;
  windowToggleMaximize(): Promise<boolean>;
  windowClose(): Promise<void>;
  windowIsMaximized(): Promise<boolean>;
  getCloseToTray(): Promise<boolean>;
  setCloseToTray(enabled: boolean): Promise<boolean>;
  onWindowMaximizedChange(cb: (maximized: boolean) => void): () => void;
  browserMount(bounds: BrowserBounds, url?: string, tabId?: string): Promise<BrowserNavState>;
  browserSetBounds(bounds: BrowserBounds, tabId?: string): Promise<void>;
  browserHide(tabId?: string): Promise<void>;
  browserNavigate(rawUrl: string, tabId?: string): Promise<BrowserNavState>;
  browserCreateTab(bounds: BrowserBounds, url?: string): Promise<BrowserNavState>;
  browserSwitchTab(tabId: string, bounds: BrowserBounds): Promise<BrowserNavState>;
  browserCloseTab(tabId: string): Promise<BrowserNavState>;
  browserBack(tabId?: string): Promise<BrowserNavState>;
  browserForward(tabId?: string): Promise<BrowserNavState>;
  browserReload(tabId?: string): Promise<BrowserNavState>;
  browserStop(tabId?: string): Promise<BrowserNavState>;
  browserDestroy(): Promise<void>;
  browserGetPageContext(tabId?: string): Promise<BrowserPageContext | null>;
  browserGetStorageSummary(): Promise<BrowserStorageSummary>;
  browserSetPersistSession(persist: boolean): Promise<BrowserStorageSummary>;
  browserClearDomainCookies(domain: string): Promise<BrowserStorageSummary>;
  browserClearCache(): Promise<void>;
  browserClearAllData(): Promise<BrowserStorageSummary>;
  onBrowserState(cb: (state: BrowserNavState) => void): () => void;
  onBrowserOpenTabRequest(cb: (url: string) => void): () => void;
}

declare global {
  interface Window {
    superpiDesktop?: ElectronPreloadBridge;
  }
}

const SESSION_TOKEN_KEY = "superpi:browserToken";

function getElectronBridge(): ElectronPreloadBridge | null {
  if (typeof window === "undefined") return null;
  return window.superpiDesktop ?? null;
}

export function isElectronApp(): boolean {
  return getElectronBridge() !== null;
}

export async function getDesktopEnv(): Promise<DesktopEnv> {
  const native = getElectronBridge();
  if (native) {
    return native.getEnv();
  }
  return {
    isElectron: false,
    version: "2.0.3",
    platform: "web",
    arch: "browser",
    homeDir: "",
  };
}

function readBrowserHashToken(): string | null {
  if (typeof window === "undefined") return null;
  const rawHash = window.location.hash;
  if (!rawHash || !rawHash.includes("token=")) return null;
  const params = new URLSearchParams(rawHash.replace(/^#/, ""));
  const token = params.get("token")?.trim();
  if (!token) return null;
  try {
    window.sessionStorage.setItem(SESSION_TOKEN_KEY, token);
    const cleanUrl = window.location.pathname + window.location.search;
    window.history.replaceState(null, "", cleanUrl);
  } catch {
    return token;
  }
  return token;
}

export function saveManualBrowserToken(token: string): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(SESSION_TOKEN_KEY, token.trim());
  } catch {
    return;
  }
}

export function clearBrowserToken(): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(SESSION_TOKEN_KEY);
  } catch {
    return;
  }
}

export async function discoverHostConnection(): Promise<DesktopHostConnection | null> {
  const native = getElectronBridge();
  if (native) {
    const conn = await native.getHostConnection();
    return { ...conn, source: "electron" };
  }
  const fromHash = readBrowserHashToken();
  if (fromHash) {
    return {
      baseUrl: window.location.origin,
      token: fromHash,
      source: "hash",
    };
  }
  if (typeof window !== "undefined") {
    try {
      const saved = window.sessionStorage.getItem(SESSION_TOKEN_KEY)?.trim();
      if (saved) {
        return {
          baseUrl: window.location.origin,
          token: saved,
          source: "storage",
        };
      }
    } catch {
      return null;
    }
  }
  return null;
}

export async function reconnectHostNative(): Promise<DesktopHostConnection | null> {
  const native = getElectronBridge();
  if (native) {
    const conn = await native.reconnectHost();
    return { ...conn, source: "electron" };
  }
  return discoverHostConnection();
}

export async function pickWorkspaceDirectory(title?: string): Promise<string | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.pickDirectory(title);
}

export async function openExternalUrl(url: string): Promise<void> {
  const native = getElectronBridge();
  if (native) {
    await native.openExternal(url);
    return;
  }
  if (typeof window !== "undefined") {
    window.open(url, "_blank", "noopener,noreferrer");
  }
}

export async function revealInFileManager(absPath: string): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.revealInFileManager(absPath);
}

export async function syncNativeLanguage(locale: "zh-CN" | "en"): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.setLanguage(locale);
}

export async function syncNativeThemeAppearance(
  dark: boolean,
  surfaceColor: string,
  inkColor: string,
): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.setThemeAppearance(dark, surfaceColor, inkColor);
}

export async function windowMinimize(): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.windowMinimize();
}

export async function windowToggleMaximize(): Promise<boolean> {
  const native = getElectronBridge();
  if (!native) return false;
  return native.windowToggleMaximize();
}

export async function windowClose(): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.windowClose();
}

export async function windowIsMaximized(): Promise<boolean> {
  const native = getElectronBridge();
  if (!native) return false;
  return native.windowIsMaximized();
}

export async function getDesktopCloseToTray(): Promise<boolean> {
  const native = getElectronBridge();
  if (!native) return false;
  return native.getCloseToTray();
}

export async function setDesktopCloseToTray(enabled: boolean): Promise<boolean> {
  const native = getElectronBridge();
  if (!native) return false;
  return native.setCloseToTray(enabled);
}

export function subscribeWindowMaximized(cb: (maximized: boolean) => void): () => void {
  const native = getElectronBridge();
  if (!native) return () => {};
  return native.onWindowMaximizedChange(cb);
}

export async function browserMount(
  bounds: BrowserBounds,
  url?: string,
  tabId?: string,
): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserMount(bounds, url, tabId);
}

export async function browserSetBounds(bounds: BrowserBounds, tabId?: string): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.browserSetBounds(bounds, tabId);
}

export async function browserHide(tabId?: string): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.browserHide(tabId);
}

export async function browserNavigate(
  rawUrl: string,
  tabId?: string,
): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserNavigate(rawUrl, tabId);
}

export async function browserCreateTab(
  bounds: BrowserBounds,
  url?: string,
): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserCreateTab(bounds, url);
}

export async function browserSwitchTab(
  tabId: string,
  bounds: BrowserBounds,
): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserSwitchTab(tabId, bounds);
}

export async function browserCloseTab(tabId: string): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserCloseTab(tabId);
}

export async function browserBack(tabId?: string): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserBack(tabId);
}

export async function browserForward(tabId?: string): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserForward(tabId);
}

export async function browserReload(tabId?: string): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserReload(tabId);
}

export async function browserStop(tabId?: string): Promise<BrowserNavState | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserStop(tabId);
}

export async function browserDestroy(): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.browserDestroy();
}

export async function browserGetPageContext(tabId?: string): Promise<BrowserPageContext | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserGetPageContext(tabId);
}

export async function browserGetStorageSummary(): Promise<BrowserStorageSummary | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserGetStorageSummary();
}

export async function browserSetPersistSession(
  persist: boolean,
): Promise<BrowserStorageSummary | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserSetPersistSession(persist);
}

export async function browserClearDomainCookies(
  domain: string,
): Promise<BrowserStorageSummary | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserClearDomainCookies(domain);
}

export async function browserClearCache(): Promise<void> {
  const native = getElectronBridge();
  if (!native) return;
  await native.browserClearCache();
}

export async function browserClearAllData(): Promise<BrowserStorageSummary | null> {
  const native = getElectronBridge();
  if (!native) return null;
  return native.browserClearAllData();
}

export function subscribeBrowserState(cb: (state: BrowserNavState) => void): () => void {
  const native = getElectronBridge();
  if (!native) return () => {};
  return native.onBrowserState(cb);
}

export function subscribeBrowserOpenTabRequest(cb: (url: string) => void): () => void {
  const native = getElectronBridge();
  if (!native?.onBrowserOpenTabRequest) return () => {};
  return native.onBrowserOpenTabRequest(cb);
}
