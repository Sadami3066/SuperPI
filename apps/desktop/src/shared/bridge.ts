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

export interface BrowserNavState {
  url: string;
  title: string;
  loading: boolean;
  canGoBack: boolean;
  canGoForward: boolean;
  error: string | null;
}

export interface BrowserPageContext {
  url: string;
  title: string;
  text: string;
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
  onWindowMaximizedChange(cb: (maximized: boolean) => void): () => void;
  browserMount(bounds: BrowserBounds, url?: string): Promise<BrowserNavState>;
  browserSetBounds(bounds: BrowserBounds): Promise<void>;
  browserHide(): Promise<void>;
  browserNavigate(rawUrl: string): Promise<BrowserNavState>;
  browserBack(): Promise<BrowserNavState>;
  browserForward(): Promise<BrowserNavState>;
  browserReload(): Promise<BrowserNavState>;
  browserStop(): Promise<BrowserNavState>;
  browserDestroy(): Promise<void>;
  browserGetPageContext(): Promise<BrowserPageContext | null>;
  onBrowserState(cb: (state: BrowserNavState) => void): () => void;
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
  windowMaximizedChanged: "superpi:window-maximized-changed",
  browserMount: "superpi:browser-mount",
  browserSetBounds: "superpi:browser-set-bounds",
  browserHide: "superpi:browser-hide",
  browserNavigate: "superpi:browser-navigate",
  browserBack: "superpi:browser-back",
  browserForward: "superpi:browser-forward",
  browserReload: "superpi:browser-reload",
  browserStop: "superpi:browser-stop",
  browserDestroy: "superpi:browser-destroy",
  browserGetPageContext: "superpi:browser-get-page-context",
  browserStateChanged: "superpi:browser-state-changed",
} as const;
