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
};

contextBridge.exposeInMainWorld("superpiDesktop", bridge);
