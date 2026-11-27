import { contextBridge, ipcRenderer } from "electron";
import {
  DESKTOP_IPC_CHANNELS,
  type BrowserBounds,
  type BrowserNavState,
  type DesktopBridge,
  type DesktopNavigateCommand,
  type DesktopNotificationPayload,
  type DesktopTrayStatePayload,
} from "../shared/bridge.js";

const bridge: DesktopBridge = {
  getEnv: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.getEnv),
  getHostConnection: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.getHostConnection),
  reconnectHost: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.reconnectHost),
  pickDirectory: (title?: string) => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.pickDirectory, title),
  openExternal: (url: string) => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.openExternal, url),
  revealInFileManager: (absPath: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.revealInFileManager, absPath),
  setLanguage: (locale: "zh-CN" | "en") =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.setLanguage, locale),
  setThemeAppearance: (dark: boolean, surfaceColor: string, inkColor: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.setThemeAppearance, dark, surfaceColor, inkColor),
  windowMinimize: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.windowMinimize),
  windowToggleMaximize: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.windowToggleMaximize),
  windowClose: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.windowClose),
  windowIsMaximized: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.windowIsMaximized),
  getCloseToTray: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.getCloseToTray),
  setCloseToTray: (enabled: boolean) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.setCloseToTray, Boolean(enabled)),
  getNotificationsEnabled: () =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.getNotificationsEnabled),
  setNotificationsEnabled: (enabled: boolean) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.setNotificationsEnabled, Boolean(enabled)),
  showNotification: (payload: DesktopNotificationPayload) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.showNotification, payload),
  updateTrayState: (payload: DesktopTrayStatePayload) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.updateTrayState, payload),
  consumePendingNavigateCommand: () =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.consumePendingNavigateCommand),
  onNavigateCommand: (cb) => {
    const listener = (_event: Electron.IpcRendererEvent, cmd: DesktopNavigateCommand) => {
      if (cmd && typeof cmd === "object") cb(cmd);
    };
    ipcRenderer.on(DESKTOP_IPC_CHANNELS.navigateCommand, listener);
    return () => {
      ipcRenderer.removeListener(DESKTOP_IPC_CHANNELS.navigateCommand, listener);
    };
  },
  checkForUpdates: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.checkForUpdates),
  listCrashLogs: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.listCrashLogs),
  clearCrashLogs: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.clearCrashLogs),
  onWindowMaximizedChange: (cb) => {
    const listener = (_event: Electron.IpcRendererEvent, maximized: boolean) => {
      cb(Boolean(maximized));
    };
    ipcRenderer.on(DESKTOP_IPC_CHANNELS.windowMaximizedChanged, listener);
    return () => {
      ipcRenderer.removeListener(DESKTOP_IPC_CHANNELS.windowMaximizedChanged, listener);
    };
  },
  browserMount: (bounds: BrowserBounds, url?: string, tabId?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserMount, bounds, url, tabId),
  browserSetBounds: (bounds: BrowserBounds, tabId?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserSetBounds, bounds, tabId),
  browserHide: (tabId?: string) => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserHide, tabId),
  browserNavigate: (rawUrl: string, tabId?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserNavigate, rawUrl, tabId),
  browserCreateTab: (bounds: BrowserBounds, url?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserCreateTab, bounds, url),
  browserSwitchTab: (tabId: string, bounds: BrowserBounds) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserSwitchTab, tabId, bounds),
  browserCloseTab: (tabId: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserCloseTab, tabId),
  browserBack: (tabId?: string) => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserBack, tabId),
  browserForward: (tabId?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserForward, tabId),
  browserReload: (tabId?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserReload, tabId),
  browserStop: (tabId?: string) => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserStop, tabId),
  browserDestroy: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserDestroy),
  browserGetPageContext: (tabId?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserGetPageContext, tabId),
  browserGetStorageSummary: () =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserGetStorageSummary),
  browserSetPersistSession: (persist: boolean) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserSetPersistSession, persist),
  browserClearDomainCookies: (domain: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserClearDomainCookies, domain),
  browserClearCache: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserClearCache),
  browserClearAllData: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserClearAllData),
  onBrowserState: (cb) => {
    const listener = (_event: Electron.IpcRendererEvent, state: BrowserNavState) => {
      cb(state);
    };
    ipcRenderer.on(DESKTOP_IPC_CHANNELS.browserStateChanged, listener);
    return () => {
      ipcRenderer.removeListener(DESKTOP_IPC_CHANNELS.browserStateChanged, listener);
    };
  },
  onBrowserOpenTabRequest: (cb) => {
    const listener = (_event: Electron.IpcRendererEvent, url: string) => {
      if (typeof url === "string" && url) cb(url);
    };
    ipcRenderer.on(DESKTOP_IPC_CHANNELS.browserOpenTabRequested, listener);
    return () => {
      ipcRenderer.removeListener(DESKTOP_IPC_CHANNELS.browserOpenTabRequested, listener);
    };
  },
};

contextBridge.exposeInMainWorld("superpiDesktop", bridge);
