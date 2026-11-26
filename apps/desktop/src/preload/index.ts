import { contextBridge, ipcRenderer } from "electron";
import {
  DESKTOP_IPC_CHANNELS,
  type BrowserBounds,
  type BrowserNavState,
  type DesktopBridge,
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
  onWindowMaximizedChange: (cb) => {
    const listener = (_event: Electron.IpcRendererEvent, maximized: boolean) => {
      cb(Boolean(maximized));
    };
    ipcRenderer.on(DESKTOP_IPC_CHANNELS.windowMaximizedChanged, listener);
    return () => {
      ipcRenderer.removeListener(DESKTOP_IPC_CHANNELS.windowMaximizedChanged, listener);
    };
  },
  browserMount: (bounds: BrowserBounds, url?: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserMount, bounds, url),
  browserSetBounds: (bounds: BrowserBounds) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserSetBounds, bounds),
  browserHide: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserHide),
  browserNavigate: (rawUrl: string) =>
    ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserNavigate, rawUrl),
  browserBack: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserBack),
  browserForward: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserForward),
  browserReload: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserReload),
  browserStop: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserStop),
  browserDestroy: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserDestroy),
  browserGetPageContext: () => ipcRenderer.invoke(DESKTOP_IPC_CHANNELS.browserGetPageContext),
  onBrowserState: (cb) => {
    const listener = (_event: Electron.IpcRendererEvent, state: BrowserNavState) => {
      cb(state);
    };
    ipcRenderer.on(DESKTOP_IPC_CHANNELS.browserStateChanged, listener);
    return () => {
      ipcRenderer.removeListener(DESKTOP_IPC_CHANNELS.browserStateChanged, listener);
    };
  },
};

contextBridge.exposeInMainWorld("superpiDesktop", bridge);
