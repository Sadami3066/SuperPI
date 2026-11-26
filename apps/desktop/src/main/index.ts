import fs from "node:fs";
import path from "node:path";
import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  Menu,
  nativeImage,
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
import { loadWindowState, saveWindowState } from "./window-state.js";

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
let currentLocale: "zh-CN" | "en" = "zh-CN";

const repoRoot = resolveRepoRoot();
const hostManager = new HostManager({
  resourceDir: app.isPackaged ? process.resourcesPath : null,
  repoRoot,
});
const browserManager = new EmbeddedBrowserManager();

function focusMainWindow(): void {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (!mainWindow.isVisible()) mainWindow.show();
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.focus();
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
        allowWindowClose = true;
        app.quit();
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
  if (process.platform === "darwin") {
    Menu.setApplicationMenu(
      Menu.buildFromTemplate([
        { role: "appMenu" },
        { role: "editMenu" },
        { role: "windowMenu" },
      ]),
    );
    return;
  }
  Menu.setApplicationMenu(null);
}

function createMainWindow(): void {
  const devUrl = process.env.SUPERPI_DEV_URL?.trim();
  const bounds = loadWindowState(hostManager.homeDir);
  const appIconPath = resolveIconPath(repoRoot, "icon.png");
  const isMac = process.platform === "darwin";

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
    backgroundColor: "#f4f4f2",
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
    if (!allowWindowClose && tray && process.platform === "darwin") {
      event.preventDefault();
      win.hide();
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
    const allowedPrefix = devUrl ? devUrl.replace(/\/+$/, "") : APP_ORIGIN;
    if (!url.startsWith(allowedPrefix)) {
      event.preventDefault();
      if (isAllowedExternalUrl(url)) {
        void shell.openExternal(url);
      }
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
      if (!mainWindow || mainWindow.isDestroyed()) return;
      if (typeof surfaceColor === "string" && surfaceColor.startsWith("#")) {
        mainWindow.setBackgroundColor(surfaceColor);
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

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserMount,
    async (_event, bounds: BrowserBounds, url?: string) => {
      return browserManager.mount(mainWindow, bounds, url);
    },
  );

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserSetBounds,
    async (_event, bounds: BrowserBounds) => {
      browserManager.setBounds(bounds);
    },
  );

  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserHide, async () => {
    browserManager.hide();
  });

  ipcMain.handle(
    DESKTOP_IPC_CHANNELS.browserNavigate,
    async (_event, rawUrl: string) => {
      return browserManager.navigate(mainWindow, rawUrl);
    },
  );

  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserBack, async () => browserManager.goBack());
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserForward, async () => browserManager.goForward());
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserReload, async () => browserManager.reload());
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserStop, async () => browserManager.stop());
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserDestroy, async () => browserManager.destroy());
  ipcMain.handle(DESKTOP_IPC_CHANNELS.browserGetPageContext, async () =>
    browserManager.getPageContext(),
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
    allowWindowClose = true;
    if (shutdownStarted) return;
    shutdownStarted = true;
    event.preventDefault();
    browserManager.destroy();
    void hostManager.shutdownIfOwned().finally(() => {
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
