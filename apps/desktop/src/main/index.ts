import fs from "node:fs";
import path from "node:path";
import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  Menu,
  type MenuItemConstructorOptions,
  nativeImage,
  nativeTheme,
  protocol,
  shell,
  Tray,
} from "electron";
import {
  DESKTOP_IPC_CHANNELS,
  type BrowserBounds,
  type DesktopBridge,
  type DesktopEnvInfo,
} from "../shared/bridge.js";
import { EmbeddedBrowserManager } from "./browser-view.js";
import { HostManager } from "./host-manager.js";
import { APP_CSP_HEADER, isAllowedAppNavigation } from "./security.js";
import {
  loadCloseToTray,
  loadWindowState,
  saveCloseToTray,
  saveWindowBackgroundColor,
  saveWindowState,
} from "./window-state.js";

const APP_SCHEME = "superpi";
const APP_ORIGIN = `${APP_SCHEME}://app`;

protocol.registerSchemesAsPrivileged([
  {
    scheme: APP_SCHEME,
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
      stream: true,
    },
  },
]);

const MIME_BY_EXT: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".mjs": "application/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".webp": "image/webp",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".ttf": "font/ttf",
  ".otf": "font/otf",
};

function resolveRepoRoot(): string | null {
  const fromDist = path.resolve(__dirname, "..", "..", "..", "..");
  if (fs.existsSync(path.join(fromDist, "apps", "web"))) {
    return fromDist;
  }
  const fromCwd = path.resolve(process.cwd(), "..", "..");
  if (fs.existsSync(path.join(fromCwd, "apps", "web"))) {
    return fromCwd;
  }
  return null;
}

function resolveWebDistDir(repoRoot: string | null): string {
  const packaged = path.join(process.resourcesPath, "web-dist");
  if (app.isPackaged && fs.existsSync(path.join(packaged, "index.html"))) {
    return packaged;
  }
  if (repoRoot) {
    return path.join(repoRoot, "apps", "web", "dist");
  }
  return path.resolve(__dirname, "..", "..", "..", "web", "dist");
}

function resolveIconPath(repoRoot: string | null, fileName: string): string | null {
  const candidates = [
    path.join(process.resourcesPath, "icons", fileName),
    repoRoot ? path.join(repoRoot, "apps", "desktop", "assets", "icons", fileName) : "",
    path.resolve(__dirname, "..", "..", "assets", "icons", fileName),
  ].filter(Boolean);
  return candidates.find((p) => fs.existsSync(p)) ?? null;
}

function isAllowedExternalUrl(rawUrl: string): boolean {
  try {
    const parsed = new URL(rawUrl);
    return parsed.protocol === "http:" || parsed.protocol === "https:" || parsed.protocol === "mailto:";
  } catch {
    return false;
  }
}

function registerAppProtocol(webDistDir: string): void {
  const rootResolved = path.resolve(webDistDir);
  protocol.handle(APP_SCHEME, async (request) => {
    try {
      const parsed = new URL(request.url);
      let rel = decodeURIComponent(parsed.pathname).replace(/^\/+/, "");
      if (!rel || rel.endsWith("/")) {
        rel = `${rel}index.html`;
      }
      const candidate = path.resolve(rootResolved, rel);
      const insideRoot =
        candidate === rootResolved || candidate.startsWith(rootResolved + path.sep);
      const target =
        insideRoot && fs.existsSync(candidate) && fs.statSync(candidate).isFile()
          ? candidate
          : path.join(rootResolved, "index.html");
      const data = await fs.promises.readFile(target);
      const ext = path.extname(target).toLowerCase();
      return new Response(data, {
        status: 200,
        headers: {
          "Content-Type": MIME_BY_EXT[ext] ?? "application/octet-stream",
          ...(ext === ".html" ? { "Content-Security-Policy": APP_CSP_HEADER } : {}),
        },
      });
    } catch {
      return new Response("Not Found", { status: 404 });
    }
  });
}

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let allowWindowClose = false;
let shutdownStarted = false;
let quitConfirmationInFlight = false;
let quitConfirmed = false;
let currentLocale: "zh-CN" | "en" = "zh-CN";

const repoRoot = resolveRepoRoot();
const hostManager = new HostManager({
  resourceDir: app.isPackaged ? process.resourcesPath : null,
  repoRoot,
});
let closeToTrayEnabled = loadCloseToTray(hostManager.homeDir);
const browserManager = new EmbeddedBrowserManager();

function focusMainWindow(): void {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (!mainWindow.isVisible()) mainWindow.show();
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.focus();
}

async function requestGracefulQuit(): Promise<void> {
  if (quitConfirmationInFlight || quitConfirmed || shutdownStarted) return;
  quitConfirmationInFlight = true;
  try {
    const activeCount = await hostManager.queryActiveRunCount();
    if (activeCount > 0) {
      const isEn = currentLocale === "en";
      const message = isEn
        ? `${activeCount} conversation task${activeCount > 1 ? "s are" : " is"} still running. Quitting now will stop active tasks.`
        : `当前仍有 ${activeCount} 个对话任务正在运行，立即退出将中断这些任务。`;
      const detail = isEn
        ? "Are you sure you want to quit Super PI?"
        : "确认要退出 Super PI 吗？";
      const options: Electron.MessageBoxOptions = {
        type: "question",
        title: isEn ? "Quit Super PI" : "退出 Super PI",
        message,
        detail,
        buttons: isEn ? ["Cancel", "Quit"] : ["取消", "仍然退出"],
        defaultId: 0,
        cancelId: 0,
      };
      const parentWin =
        mainWindow && !mainWindow.isDestroyed() && mainWindow.isVisible() ? mainWindow : undefined;
      const { response } = parentWin
        ? await dialog.showMessageBox(parentWin, options)
        : await dialog.showMessageBox(options);
      if (response !== 1) return;
    }
    quitConfirmed = true;
    allowWindowClose = true;
    app.quit();
  } finally {
    quitConfirmationInFlight = false;
  }
}

function buildTrayMenu(): Menu {
  const isEn = currentLocale === "en";
  return Menu.buildFromTemplate([
    {
      label: isEn ? "Show Super PI" : "显示 Super PI",
      click: () => focusMainWindow(),
    },
    { type: "separator" },
    {
      label: isEn ? "Quit Super PI" : "退出 Super PI",
      click: () => {
        void requestGracefulQuit();
      },
    },
  ]);
}

function createTray(): void {
  const iconPath =
    resolveIconPath(repoRoot, "tray.png") ??
    resolveIconPath(repoRoot, "32x32.png") ??
    resolveIconPath(repoRoot, "icon.png");
  if (!iconPath) return;
  const img = nativeImage.createFromPath(iconPath);
  if (img.isEmpty()) return;
  tray = new Tray(img);
  tray.setToolTip("Super PI");
  tray.setContextMenu(buildTrayMenu());
  tray.on("click", () => focusMainWindow());
}

function configureApplicationMenu(): void {
  const editMenu: MenuItemConstructorOptions = { role: "editMenu" };
  const viewSubmenu: MenuItemConstructorOptions[] = [
    { role: "reload" },
    { role: "forceReload" },
    { type: "separator" },
    { role: "resetZoom" },
    { role: "zoomIn" },
    { role: "zoomOut" },
    { type: "separator" },
    { role: "togglefullscreen" },
  ];
  if (!app.isPackaged) {
    viewSubmenu.push({ type: "separator" }, { role: "toggleDevTools" });
  }
  const viewMenu: MenuItemConstructorOptions = {
    label: "View",
    submenu: viewSubmenu,
  };
  if (process.platform === "darwin") {
    Menu.setApplicationMenu(
      Menu.buildFromTemplate([
        { role: "appMenu" },
        editMenu,
        viewMenu,
        { role: "windowMenu" },
      ]),
    );
    return;
  }
  Menu.setApplicationMenu(Menu.buildFromTemplate([editMenu, viewMenu]));
}

function createMainWindow(): void {
  const devUrl = process.env.SUPERPI_DEV_URL?.trim();
  const bounds = loadWindowState(hostManager.homeDir);
  const appIconPath = resolveIconPath(repoRoot, "icon.png");
  const isMac = process.platform === "darwin";
  const initialBg =
    bounds.backgroundColor || (nativeTheme.shouldUseDarkColors ? "#161614" : "#f7f6f2");

  const win = new BrowserWindow({
    width: bounds.width,
    height: bounds.height,
    x: bounds.x,
    y: bounds.y,
    minWidth: 960,
    minHeight: 640,
    show: false,
    title: "Super PI",
    icon: appIconPath ?? undefined,
    backgroundColor: initialBg,
    frame: isMac,
    ...(isMac
      ? {
          titleBarStyle: "hiddenInset" as const,
          trafficLightPosition: { x: 14, y: 12 },
        }
      : {
          autoHideMenuBar: true,
        }),
    webPreferences: {
      preload: path.join(__dirname, "..", "preload", "index.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });

  if (bounds.maximized) {
    win.maximize();
  }

  let saveTimer: ReturnType<typeof setTimeout> | null = null;
  const scheduleSave = () => {
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      if (!win.isDestroyed()) saveWindowState(hostManager.homeDir, win);
    }, 300);
  };

  win.on("resize", scheduleSave);
  win.on("move", scheduleSave);
  win.on("maximize", () => {
    if (!win.isDestroyed()) {
      win.webContents.send(DESKTOP_IPC_CHANNELS.windowMaximizedChanged, true);
    }
  });
  win.on("unmaximize", () => {
    if (!win.isDestroyed()) {
      win.webContents.send(DESKTOP_IPC_CHANNELS.windowMaximizedChanged, false);
    }
  });
  win.on("close", (event) => {
    if (saveTimer) clearTimeout(saveTimer);
    saveWindowState(hostManager.homeDir, win);
    if (!allowWindowClose) {
      event.preventDefault();
      if (tray && closeToTrayEnabled) {
        win.hide();
      } else {
        void requestGracefulQuit();
      }
    }
  });
  win.on("closed", () => {
    browserManager.destroy();
    if (mainWindow === win) mainWindow = null;
  });

  win.once("ready-to-show", () => {
    win.show();
  });

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isAllowedExternalUrl(url)) {
      void shell.openExternal(url);
    }
    return { action: "deny" };
  });

  win.webContents.on("will-navigate", (event, url) => {
    const allowedOrigin = devUrl || APP_ORIGIN;
    if (!isAllowedAppNavigation(url, allowedOrigin)) {
      event.preventDefault();
      if (isAllowedExternalUrl(url)) {
        void shell.openExternal(url);
      }
    }
  });

  win.webContents.on("context-menu", (_event, params) => {
    const hasSelection = params.selectionText.trim().length > 0;
    if (!params.isEditable && !hasSelection) return;
    const isEn = currentLocale === "en";
    const template: MenuItemConstructorOptions[] = [];
    if (params.isEditable) {
      template.push(
        { role: "undo", label: isEn ? "Undo" : "撤销", enabled: params.editFlags.canUndo },
        { role: "redo", label: isEn ? "Redo" : "重做", enabled: params.editFlags.canRedo },
        { type: "separator" },
        { role: "cut", label: isEn ? "Cut" : "剪切", enabled: params.editFlags.canCut },
        { role: "copy", label: isEn ? "Copy" : "复制", enabled: params.editFlags.canCopy },
        { role: "paste", label: isEn ? "Paste" : "粘贴", enabled: params.editFlags.canPaste },
        { type: "separator" },
        { role: "selectAll", label: isEn ? "Select All" : "全选" },
      );
    } else if (hasSelection) {
      template.push(
        { role: "copy", label: isEn ? "Copy" : "复制", enabled: params.editFlags.canCopy },
        { role: "selectAll", label: isEn ? "Select All" : "全选" },
      );
    }
    if (!app.isPackaged) {
      template.push(
        { type: "separator" },
        {
          label: isEn ? "Inspect Element" : "检查元素",
          click: () => win.webContents.inspectElement(params.x, params.y),
        },
      );
    }
    if (template.length > 0) {
      Menu.buildFromTemplate(template).popup({ window: win });
    }
  });

  if (devUrl) {
    void win.loadURL(devUrl);
  } else {
    void win.loadURL(`${APP_ORIGIN}/index.html`);
  }

  mainWindow = win;
}

function registerIpcHandlers(): void {
  ipcMain.handle(DESKTOP_IPC_CHANNELS.getEnv, async (): Promise<DesktopEnvInfo> => {
    const platform =
      process.platform === "darwin" || process.platform === "win32"
        ? process.platform
        : "linux";
    return {
      isElectron: true,
      version: app.getVersion(),
      platform,
      arch: process.arch,
      homeDir: hostManager.homeDir,
    };
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.getHostConnection, async () => {
    return hostManager.ensureConnected();
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.reconnectHost, async () => {
    return hostManager.reconnect();
  });

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.pickDirectory,
    async (_event, title?: string): Promise<string | null> => {
      const targetWin = mainWindow && !mainWindow.isDestroyed() ? mainWindow : undefined;
      const result = targetWin
        ? await dialog.showOpenDialog(targetWin, {
            title: title || (currentLocale === "en" ? "Select Workspace Folder" : "选择工作区目录"),
            properties: ["openDirectory", "createDirectory"],
          })
        : await dialog.showOpenDialog({
            title: title || (currentLocale === "en" ? "Select Workspace Folder" : "选择工作区目录"),
            properties: ["openDirectory", "createDirectory"],
          });
      if (result.canceled || result.filePaths.length === 0) return null;
      return result.filePaths[0] ?? null;
    },
  );

  ipcMain.handle(DESKTOP_IPC_CHANNELS.openExternal, async (_event, url: string) => {
    if (typeof url === "string" && isAllowedExternalUrl(url)) {
      await shell.openExternal(url);
    }
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.revealInFileManager, async (_event, absPath: string) => {
    if (typeof absPath !== "string" || !absPath.trim()) return;
    if (fs.existsSync(absPath)) {
      shell.showItemInFolder(absPath);
    }
  });

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.setLanguage,
    async (_event, locale: "zh-CN" | "en") => {
      currentLocale = locale === "en" ? "en" : "zh-CN";
      if (tray) {
        tray.setContextMenu(buildTrayMenu());
      }
    },
  );

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.setThemeAppearance,
    async (_event, _dark: boolean, surfaceColor: string) => {
      if (typeof surfaceColor === "string" && surfaceColor.startsWith("#")) {
        saveWindowBackgroundColor(hostManager.homeDir, surfaceColor);
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.setBackgroundColor(surfaceColor);
        }
      }
    },
  );

  ipcMain.handle(DESKTOP_IPC_CHANNELS.windowMinimize, async () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.minimize();
    }
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.windowToggleMaximize, async () => {
    if (!mainWindow || mainWindow.isDestroyed()) return false;
    if (mainWindow.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow.maximize();
    }
    return mainWindow.isMaximized();
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.windowClose, async () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.close();
    }
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.windowIsMaximized, async () => {
    return Boolean(mainWindow && !mainWindow.isDestroyed() && mainWindow.isMaximized());
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.getCloseToTray, async () => {
    return closeToTrayEnabled;
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.setCloseToTray, async (_event, enabled: boolean) => {
    closeToTrayEnabled = saveCloseToTray(hostManager.homeDir, Boolean(enabled));
    return closeToTrayEnabled;
  });

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserMount,
    async (_event, bounds: BrowserBounds, url?: string, tabId?: string) => {
      return browserManager.mount(mainWindow, bounds, url, tabId);
    },
  );

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserSetBounds,
    async (_event, bounds: BrowserBounds, tabId?: string) => {
      browserManager.setBounds(bounds, tabId);
    },
  );

  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserHide, async (_event, tabId?: string) => {
    browserManager.hide(tabId);
  });

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserNavigate,
    async (_event, rawUrl: string, tabId?: string) => {
      return browserManager.navigate(mainWindow, rawUrl, tabId);
    },
  );

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserCreateTab,
    async (_event, bounds: BrowserBounds, url?: string) => {
      return browserManager.createTab(mainWindow, bounds, url);
    },
  );

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserSwitchTab,
    async (_event, tabId: string, bounds: BrowserBounds) => {
      return browserManager.switchTab(mainWindow, tabId, bounds);
    },
  );

  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserCloseTab, async (_event, tabId: string) => {
    return browserManager.closeTab(tabId);
  });

  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserBack, async (_event, tabId?: string) =>
    browserManager.goBack(tabId),
  );
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserForward, async (_event, tabId?: string) =>
    browserManager.goForward(tabId),
  );
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserReload, async (_event, tabId?: string) =>
    browserManager.reload(tabId),
  );
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserStop, async (_event, tabId?: string) =>
    browserManager.stop(tabId),
  );
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserDestroy, async () => browserManager.destroy());
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserGetPageContext, async (_event, tabId?: string) =>
    browserManager.getPageContext(tabId),
  );
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserGetStorageSummary, async () =>
    browserManager.getStorageSummary(),
  );
  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserSetPersistSession,
    async (_event, persist: boolean) => browserManager.setPersistSession(Boolean(persist)),
  );
  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserClearDomainCookies,
    async (_event, domain: string) => browserManager.clearDomainCookies(String(domain ?? "")),
  );
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserClearCache, async () => browserManager.clearCache());
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserClearAllData, async () =>
    browserManager.clearAllData(),
  );
}

const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on("second-instance", () => {
    focusMainWindow();
  });

  void app.whenReady().then(() => {
    configureApplicationMenu();
    if (!process.env.SUPERPI_DEV_URL) {
      registerAppProtocol(resolveWebDistDir(repoRoot));
    }
    registerIpcHandlers();
    createTray();
    createMainWindow();

    void hostManager.ensureConnected().catch(() => undefined);

    app.on("activate", () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createMainWindow();
      } else {
        focusMainWindow();
      }
    });
  });

  app.on("before-quit", (event) => {
    if (shutdownStarted) {
      event.preventDefault();
      return;
    }
    if (!quitConfirmed) {
      event.preventDefault();
      void requestGracefulQuit();
      return;
    }
    allowWindowClose = true;
    shutdownStarted = true;
    event.preventDefault();
    void browserManager
      .flushCookies()
      .catch(() => undefined)
      .then(() => {
        browserManager.destroy();
        return hostManager.shutdownIfOwned();
      })
      .finally(() => {
        app.exit(0);
      });
  });

  app.on("window-all-closed", () => {
    if (process.platform !== "darwin") {
      app.quit();
    }
  });
}

export type { DesktopBridge };
