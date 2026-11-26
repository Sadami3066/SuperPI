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
} as const;
