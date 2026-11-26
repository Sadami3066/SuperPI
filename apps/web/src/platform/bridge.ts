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
