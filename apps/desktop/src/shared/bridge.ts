export interface HostConnectionInfo {
  baseUrl: string;
  token: string;
  port: number;
  pid: number;
  ownedSidecar: boolean;
  homeDir: string;
}

export interface DesktopEnvInfo {
  isElectron: true;
  version: string;
  platform: "darwin" | "win32" | "linux";
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

export type DesktopFeatureView = "timers" | "inbox" | "memory" | "skills" | "mcp";

export type DesktopNavigateCommand =
  | { kind: "chat"; sessionId: string }
  | { kind: "super" }
  | { kind: "new_chat" }
  | { kind: "settings"; tab: string; autoCheckUpdate?: boolean }
  | { kind: "feature"; view: DesktopFeatureView };

export interface DesktopNotificationPayload {
  tag: string;
  title: string;
  body: string;
  command?: DesktopNavigateCommand;
}

export interface DesktopTraySessionItem {
  id: string;
  title: string;
}

export interface DesktopTrayStatePayload {
  activeRunsCount: number;
  recentSessions: DesktopTraySessionItem[];
}

export interface DesktopUpdateCheckResult {
  status: "up_to_date" | "update_available" | "error";
  currentVersion: string;
  latestVersion?: string;
  releaseNotes?: string;
  downloadUrl?: string;
  releaseUrl?: string;
  checkedAt: number;
  error?: string;
}

export interface DesktopCrashRecord {
  id: string;
  timestamp: number;
  processType: string;
  reason: string;
  exitCode?: number;
}

export interface DesktopBridge {
  getEnv(): Promise<DesktopEnvInfo>;
  getHostConnection(): Promise<HostConnectionInfo>;
  reconnectHost(): Promise<HostConnectionInfo>;
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
  getNotificationsEnabled(): Promise<boolean>;
  setNotificationsEnabled(enabled: boolean): Promise<boolean>;
  showNotification(payload: DesktopNotificationPayload): Promise<void>;
  updateTrayState(payload: DesktopTrayStatePayload): Promise<void>;
  consumePendingNavigateCommand(): Promise<DesktopNavigateCommand | null>;
  onNavigateCommand(cb: (cmd: DesktopNavigateCommand) => void): () => void;
  checkForUpdates(): Promise<DesktopUpdateCheckResult>;
  listCrashLogs(): Promise<DesktopCrashRecord[]>;
  clearCrashLogs(): Promise<void>;
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

export const DESKTOP_IPC_CHANNELS = {
  getEnv: "superpi:get-env",
  getHostConnection: "superpi:get-host-connection",
  reconnectHost: "superpi:reconnect-host",
  pickDirectory: "superpi:pick-directory",
  openExternal: "superpi:open-external",
  revealInFileManager: "superpi:reveal-in-file-manager",
  setLanguage: "superpi:set-language",
  setThemeAppearance: "superpi:set-theme-appearance",
  windowMinimize: "superpi:window-minimize",
  windowToggleMaximize: "superpi:window-toggle-maximize",
  windowClose: "superpi:window-close",
  windowIsMaximized: "superpi:window-is-maximized",
  getCloseToTray: "superpi:get-close-to-tray",
  setCloseToTray: "superpi:set-close-to-tray",
  getNotificationsEnabled: "superpi:get-notifications-enabled",
  setNotificationsEnabled: "superpi:set-notifications-enabled",
  showNotification: "superpi:show-notification",
  updateTrayState: "superpi:update-tray-state",
  consumePendingNavigateCommand: "superpi:consume-pending-navigate",
  navigateCommand: "superpi:navigate-command",
  checkForUpdates: "superpi:check-for-updates",
  listCrashLogs: "superpi:list-crash-logs",
  clearCrashLogs: "superpi:clear-crash-logs",
  windowMaximizedChanged: "superpi:window-maximized-changed",
  browserMount: "superpi:browser-mount",
  browserSetBounds: "superpi:browser-set-bounds",
  browserHide: "superpi:browser-hide",
  browserNavigate: "superpi:browser-navigate",
  browserCreateTab: "superpi:browser-create-tab",
  browserSwitchTab: "superpi:browser-switch-tab",
  browserCloseTab: "superpi:browser-close-tab",
  browserBack: "superpi:browser-back",
  browserForward: "superpi:browser-forward",
  browserReload: "superpi:browser-reload",
  browserStop: "superpi:browser-stop",
  browserDestroy: "superpi:browser-destroy",
  browserGetPageContext: "superpi:browser-get-page-context",
  browserGetStorageSummary: "superpi:browser-get-storage-summary",
  browserSetPersistSession: "superpi:browser-set-persist-session",
  browserClearDomainCookies: "superpi:browser-clear-domain-cookies",
  browserClearCache: "superpi:browser-clear-cache",
  browserClearAllData: "superpi:browser-clear-all-data",
  browserStateChanged: "superpi:browser-state-changed",
  browserOpenTabRequested: "superpi:browser-open-tab-requested",
} as const;
