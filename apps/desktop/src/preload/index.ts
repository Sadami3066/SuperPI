import { contextBridge, ipcRenderer } from "electron";
import { DESKTOP_IPC_CHANNELS, type DesktopBridge } from "../shared/bridge.js";

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
};

contextBridge.exposeInMainWorld("superpiDesktop", bridge);
