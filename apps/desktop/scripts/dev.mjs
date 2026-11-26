import http from "node:http";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const desktopDir = path.resolve(__dirname, "..");
const repoRoot = path.resolve(desktopDir, "..", "..");
const devPort = Number(process.env.SUPERPI_VITE_PORT || 1420);
const devUrl = `http://127.0.0.1:${devPort}`;

function runPnpm(args, cwd) {
  return spawn("pnpm", args, {
    cwd,
    stdio: "inherit",
    shell: process.platform === "win32",
  });
}

function waitForUrl(url, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const req = http.get(url, (res) => {
        res.resume();
        resolve();
      });
      req.on("error", () => {
        if (Date.now() >= deadline) {
          reject(new Error(`Timed out waiting for Vite server at ${url}`));
          return;
        }
        setTimeout(attempt, 200);
      });
    };
    attempt();
  });
}

const buildRes = spawnSync("pnpm", ["run", "build:main"], {
  cwd: desktopDir,
  stdio: "inherit",
  shell: process.platform === "win32",
});
if (buildRes.status !== 0) {
  process.exit(buildRes.status ?? 1);
}

const viteProc = runPnpm(["--filter", "@superpi/web", "dev", "--port", String(devPort), "--strictPort"], repoRoot);

const cleanup = () => {
  if (viteProc.exitCode === null) {
    viteProc.kill("SIGTERM");
  }
};
process.on("SIGINT", cleanup);
process.on("SIGTERM", cleanup);

try {
  await waitForUrl(devUrl);
  const electronArgs = ["exec", "electron"];
  if (process.env.SUPERPI_NO_SANDBOX === "1") {
    electronArgs.push("--no-sandbox");
  }
  electronArgs.push(".");
  const appProc = spawn("pnpm", electronArgs, {
    cwd: desktopDir,
    stdio: "inherit",
    shell: process.platform === "win32",
    env: {
      ...process.env,
      SUPERPI_DEV_URL: devUrl,
    },
  });
  appProc.on("exit", (code) => {
    cleanup();
    process.exit(code ?? 0);
  });
} catch (err) {
  cleanup();
  console.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
}
