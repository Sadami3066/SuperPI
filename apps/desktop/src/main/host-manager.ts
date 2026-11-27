import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import type { HostConnectionInfo } from "../shared/bridge.js";

const MAIN_CLASS = "io.github.whywood.superpi.host.MainKt";
const MAX_SIDECAR_LOG_BYTES = 5 * 1024 * 1024;
const STARTUP_TIMEOUT_MS = 25_000;
const SHUTDOWN_WAIT_MS = 2_500;

export interface ServeLockInfo {
  pid: number;
  port: number;
  token: string;
  startedAt?: number;
}

export interface HostLaunchSpec {
  command: string;
  args: string[];
  kind: "sidecar" | "binary";
}

export function resolveSuperPiHome(): string {
  const envHome = process.env.SUPER_PI_HOME?.trim();
  if (envHome) return envHome;
  const home = os.homedir();
  if (home) return path.join(home, ".super-pi");
  return path.resolve(".super-pi");
}

export function normalizePathForJvm(inputPath: string): string {
  if (inputPath.startsWith("\\\\?\\UNC\\")) {
    return `\\\\${inputPath.slice(8)}`;
  }
  if (inputPath.startsWith("\\\\?\\")) {
    return inputPath.slice(4);
  }
  return inputPath;
}

export function serveLockPath(homeDir: string): string {
  return path.join(homeDir, "cache", "serve.lock");
}

export function readServeLock(lockFile: string): ServeLockInfo | null {
  try {
    const raw = fs.readFileSync(lockFile, "utf8").trim();
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<ServeLockInfo>;
    if (
      typeof parsed.pid !== "number" ||
      typeof parsed.port !== "number" ||
      parsed.port <= 0 ||
      parsed.port > 65535 ||
      typeof parsed.token !== "string" ||
      parsed.token.trim().length === 0
    ) {
      return null;
    }
    return {
      pid: parsed.pid,
      port: parsed.port,
      token: parsed.token,
      startedAt: typeof parsed.startedAt === "number" ? parsed.startedAt : undefined,
    };
  } catch {
    return null;
  }
}

export function sendLoopbackRequest(
  method: "GET" | "POST",
  pathname: string,
  port: number,
  token: string,
  timeoutMs = 1200,
): Promise<number | null> {
  return new Promise((resolve) => {
    const req = http.request(
      {
        hostname: "127.0.0.1",
        port,
        path: pathname,
        method,
        timeout: timeoutMs,
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Length": "0",
        },
      },
      (res) => {
        res.resume();
        resolve(res.statusCode ?? null);
      },
    );
    req.on("timeout", () => {
      req.destroy();
      resolve(null);
    });
    req.on("error", () => resolve(null));
    req.end();
  });
}

export async function probeHealth(port: number, token: string): Promise<boolean> {
  const status = await sendLoopbackRequest("GET", "/api/v1/health", port, token);
  return status === 200;
}

export async function requestShutdown(port: number, token: string): Promise<boolean> {
  const status = await sendLoopbackRequest("POST", "/api/v1/shutdown", port, token);
  return status !== null && status >= 200 && status <= 204;
}

export function fetchActiveRunCount(port: number, token: string, timeoutMs = 1500): Promise<number> {
  return new Promise((resolve) => {
    const req = http.request(
      {
        hostname: "127.0.0.1",
        port,
        path: "/api/v1/status",
        method: "GET",
        timeout: timeoutMs,
        headers: {
          Authorization: `Bearer ${token}`,
        },
      },
      (res) => {
        if ((res.statusCode ?? 0) !== 200) {
          res.resume();
          resolve(0);
          return;
        }
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer) => chunks.push(chunk));
        res.on("end", () => {
          try {
            const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8")) as {
              busySessionsCount?: unknown;
            };
            const count =
              typeof parsed.busySessionsCount === "number" && Number.isFinite(parsed.busySessionsCount)
                ? parsed.busySessionsCount
                : 0;
            resolve(count > 0 ? count : 0);
          } catch {
            resolve(0);
          }
        });
        res.on("error", () => resolve(0));
      },
    );
    req.on("timeout", () => {
      req.destroy();
      resolve(0);
    });
    req.on("error", () => resolve(0));
    req.end();
  });
}

export function hasChildExited(child: Pick<ChildProcess, "exitCode" | "signalCode">): boolean {
  return child.exitCode !== null || child.signalCode !== null;
}

export function isValidSidecarDir(dir: string, platform: NodeJS.Platform = process.platform): boolean {
  const javaBin =
    platform === "win32"
      ? path.join(dir, "runtime", "bin", "java.exe")
      : path.join(dir, "runtime", "bin", "java");
  const libDir = path.join(dir, "lib");
  return fs.existsSync(javaBin) && fs.statSync(javaBin).isFile() && fs.existsSync(libDir) && fs.statSync(libDir).isDirectory();
}

export function locateHostLaunchSpec(options: {
  homeDir: string;
  resourceDir?: string | null;
  repoRoot?: string | null;
  platform?: NodeJS.Platform;
  envHostBin?: string | null;
}): HostLaunchSpec | null {
  const platform = options.platform ?? process.platform;
  const cleanHome = normalizePathForJvm(options.homeDir);
  const serveArgs = ["--home", cleanHome, "serve", "--port", "0"];

  const sidecarCandidates: string[] = [];
  if (options.resourceDir) {
    const cleanRes = normalizePathForJvm(options.resourceDir);
    sidecarCandidates.push(path.join(cleanRes, "sidecar"));
    sidecarCandidates.push(path.join(cleanRes, "resources", "sidecar"));
  }
  if (options.repoRoot) {
    sidecarCandidates.push(path.join(normalizePathForJvm(options.repoRoot), "apps", "desktop", "resources", "sidecar"));
  }

  for (const dir of sidecarCandidates) {
    if (isValidSidecarDir(dir, platform)) {
      const cleanDir = normalizePathForJvm(dir);
      const javaBin =
        platform === "win32"
          ? path.join(cleanDir, "runtime", "bin", "java.exe")
          : path.join(cleanDir, "runtime", "bin", "java");
      const launcherPath =
        platform === "win32"
          ? path.join(cleanDir, "bin", "super-pi.bat")
          : path.join(cleanDir, "bin", "super-pi");
      return {
        kind: "sidecar",
        command: javaBin,
        args: [
          "-Dfile.encoding=UTF-8",
          "-Dstdout.encoding=UTF-8",
          "-Dstderr.encoding=UTF-8",
          `-Dsuperpi.launcher=${launcherPath}`,
          "-Xms64m",
          "-Xmx1g",
          "-cp",
          path.join(cleanDir, "lib", "*"),
          MAIN_CLASS,
          ...serveArgs,
        ],
      };
    }
  }

  const binCandidates: string[] = [];
  if (options.envHostBin?.trim()) {
    binCandidates.push(options.envHostBin.trim());
  }
  if (options.repoRoot) {
    const binName = platform === "win32" ? "super-pi.bat" : "super-pi";
    binCandidates.push(
      path.join(options.repoRoot, "apps", "host", "build", "install", "super-pi", "bin", binName),
    );
  }

  for (const binPath of binCandidates) {
    if (fs.existsSync(binPath) && fs.statSync(binPath).isFile()) {
      return {
        kind: "binary",
        command: binPath,
        args: serveArgs,
      };
    }
  }

  return null;
}

function rotateLogIfLarge(logFile: string): void {
  try {
    if (!fs.existsSync(logFile)) return;
    const st = fs.statSync(logFile);
    if (st.size > MAX_SIDECAR_LOG_BYTES) {
      fs.renameSync(logFile, `${logFile}.prev`);
    }
  } catch {
    return;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function killChildTree(child: ChildProcess): void {
  if (child.pid && process.platform === "win32") {
    try {
      spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], {
        stdio: "ignore",
        windowsHide: true,
      });
    } catch {
      void 0;
    }
  }
  try {
    child.kill("SIGTERM");
  } catch {
    return;
  }
  setTimeout(() => {
    try {
      if (!hasChildExited(child)) {
        child.kill("SIGKILL");
      }
    } catch {
      return;
    }
  }, 600);
}

export class HostManager {
  readonly homeDir: string;
  private readonly resourceDir: string | null;
  private readonly repoRoot: string | null;
  private connection: HostConnectionInfo | null = null;
  private child: ChildProcess | null = null;
  private owned = false;
  private shuttingDown = false;
  private inFlight: Promise<HostConnectionInfo> | null = null;

  constructor(options?: {
    homeDir?: string;
    resourceDir?: string | null;
    repoRoot?: string | null;
  }) {
    this.homeDir = options?.homeDir ?? resolveSuperPiHome();
    this.resourceDir = options?.resourceDir ?? null;
    this.repoRoot = options?.repoRoot ?? null;
  }

  async ensureConnected(): Promise<HostConnectionInfo> {
    if (this.shuttingDown) {
      throw new Error("Super PI Desktop is shutting down.");
    }
    if (this.inFlight) {
      return this.inFlight;
    }
    this.inFlight = this.doEnsureConnected().finally(() => {
      this.inFlight = null;
    });
    return this.inFlight;
  }

  async reconnect(): Promise<HostConnectionInfo> {
    this.connection = null;
    return this.ensureConnected();
  }

  private async doEnsureConnected(): Promise<HostConnectionInfo> {
    if (this.connection && (await probeHealth(this.connection.port, this.connection.token))) {
      return this.connection;
    }
    this.connection = null;

    const lockFile = serveLockPath(this.homeDir);
    const existingLock = readServeLock(lockFile);
    if (existingLock && (await probeHealth(existingLock.port, existingLock.token))) {
      const isOwned = this.owned && this.child?.pid === existingLock.pid;
      const dto: HostConnectionInfo = {
        baseUrl: `http://127.0.0.1:${existingLock.port}`,
        token: existingLock.token,
        port: existingLock.port,
        pid: existingLock.pid,
        ownedSidecar: isOwned,
        homeDir: this.homeDir,
      };
      this.connection = dto;
      this.owned = isOwned;
      return dto;
    }

    if (this.child && hasChildExited(this.child)) {
      this.child = null;
      this.owned = false;
    }

    if (!this.child) {
      const spec = locateHostLaunchSpec({
        homeDir: this.homeDir,
        resourceDir: this.resourceDir,
        repoRoot: this.repoRoot,
        envHostBin: process.env.SUPER_PI_HOST_BIN ?? null,
      });
      if (!spec) {
        throw new Error(
          "Super PI runtime was not found. Build :apps:host:installDist for dev or stage sidecar for packaging.",
        );
      }
      this.child = this.spawnHostProcess(spec);
      this.owned = true;
    }

    const spawnedPid = this.child.pid ?? -1;
    const deadline = Date.now() + STARTUP_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if (this.shuttingDown) {
        throw new Error("Super PI Desktop is shutting down.");
      }
      if (!this.child) {
        throw new Error("Super PI host exited unexpectedly during startup.");
      }
      if (hasChildExited(this.child)) {
        const reason =
          this.child.exitCode !== null
            ? `code ${this.child.exitCode}`
            : `signal ${this.child.signalCode}`;
        this.child = null;
        this.owned = false;
        throw new Error(`Super PI host exited early with ${reason}`);
      }
      const lock = readServeLock(lockFile);
      if (lock && (await probeHealth(lock.port, lock.token))) {
        const isOurs = lock.pid === spawnedPid;
        const dto: HostConnectionInfo = {
          baseUrl: `http://127.0.0.1:${lock.port}`,
          token: lock.token,
          port: lock.port,
          pid: lock.pid,
          ownedSidecar: isOurs,
          homeDir: this.homeDir,
        };
        this.connection = dto;
        this.owned = isOurs;
        return dto;
      }
      await sleep(150);
    }

    throw new Error("Timed out waiting for Super PI host to start.");
  }

  private spawnHostProcess(spec: HostLaunchSpec): ChildProcess {
    const logsDir = path.join(this.homeDir, "cache", "logs");
    fs.mkdirSync(logsDir, { recursive: true });
    const logFile = path.join(logsDir, "desktop-sidecar.log");
    rotateLogIfLarge(logFile);
    const outFd = fs.openSync(logFile, "a");

    const useShell = process.platform === "win32" && spec.command.toLowerCase().endsWith(".bat");
    let child: ChildProcess;
    try {
      child = spawn(spec.command, spec.args, {
        env: {
          ...process.env,
          SUPER_PI_HOME: normalizePathForJvm(this.homeDir),
        },
        stdio: ["ignore", outFd, outFd],
        windowsHide: true,
        shell: useShell,
      });
    } finally {
      fs.closeSync(outFd);
    }

    child.on("exit", () => {
      if (this.child === child) {
        this.owned = false;
      }
    });
    return child;
  }

  async queryActiveRunCount(): Promise<number> {
    const conn = this.connection ?? readServeLock(serveLockPath(this.homeDir));
    if (!conn) return 0;
    return fetchActiveRunCount(conn.port, conn.token);
  }

  async shutdownIfOwned(): Promise<void> {
    this.shuttingDown = true;
    const isOwned = this.owned;
    const conn = this.connection;
    const proc = this.child;
    this.owned = false;
    this.connection = null;
    this.child = null;

    if (!isOwned) return;
    if (conn) {
      await requestShutdown(conn.port, conn.token);
    }
    if (!proc || hasChildExited(proc)) return;

    const deadline = Date.now() + SHUTDOWN_WAIT_MS;
    while (Date.now() < deadline) {
      if (hasChildExited(proc)) return;
      await sleep(100);
    }
    killChildTree(proc);
  }
}
