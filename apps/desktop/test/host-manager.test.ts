import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  HostManager,
  locateHostLaunchSpec,
  normalizePathForJvm,
  probeHealth,
  readServeLock,
  serveLockPath,
} from "../src/main/host-manager.js";

const tempDirs: string[] = [];

function makeTempDir(prefix: string): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  tempDirs.push(dir);
  return dir;
}

afterEach(() => {
  while (tempDirs.length > 0) {
    const dir = tempDirs.pop();
    if (dir) fs.rmSync(dir, { recursive: true, force: true });
  }
});

describe("host-manager", () => {
  it("parses valid serve.lock and rejects malformed payloads", () => {
    const dir = makeTempDir("superpi-lock-");
    const lockFile = serveLockPath(dir);
    fs.mkdirSync(path.dirname(lockFile), { recursive: true });

    fs.writeFileSync(
      lockFile,
      JSON.stringify({ pid: 4321, port: 45123, token: "tok_secret", startedAt: 1700000000 }),
      "utf8",
    );
    expect(readServeLock(lockFile)).toEqual({
      pid: 4321,
      port: 45123,
      token: "tok_secret",
      startedAt: 1700000000,
    });

    fs.writeFileSync(lockFile, JSON.stringify({ pid: 1, port: 0, token: "" }), "utf8");
    expect(readServeLock(lockFile)).toBeNull();
  });

  it("verifies bearer token during loopback health probe", async () => {
    const server = http.createServer((req, res) => {
      if (req.url === "/api/v1/health" && req.headers.authorization === "Bearer valid-secret") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end('{"ok":true}');
      } else {
        res.writeHead(401);
        res.end();
      }
    });

    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const addr = server.address();
    if (!addr || typeof addr === "string") throw new Error("Expected TCP address");

    try {
      expect(await probeHealth(addr.port, "valid-secret")).toBe(true);
      expect(await probeHealth(addr.port, "wrong-secret")).toBe(false);
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });

  it("attaches to existing healthy daemon without owning it and deduplicates concurrent connects", async () => {
    const server = http.createServer((req, res) => {
      if (req.url === "/api/v1/health" && req.headers.authorization === "Bearer shared-tok") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end('{"ok":true}');
      } else {
        res.writeHead(404);
        res.end();
      }
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const addr = server.address();
    if (!addr || typeof addr === "string") throw new Error("Expected TCP address");

    const homeDir = makeTempDir("superpi-home-");
    const lockFile = serveLockPath(homeDir);
    fs.mkdirSync(path.dirname(lockFile), { recursive: true });
    fs.writeFileSync(
      lockFile,
      JSON.stringify({ pid: 99999, port: addr.port, token: "shared-tok" }),
      "utf8",
    );

    try {
      const manager = new HostManager({ homeDir });
      const [c1, c2] = await Promise.all([manager.ensureConnected(), manager.ensureConnected()]);
      expect(c1).toEqual(c2);
      expect(c1.port).toBe(addr.port);
      expect(c1.ownedSidecar).toBe(false);
      await manager.shutdownIfOwned();
    } finally {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });

  it("locates sidecar java runtime before fallback binary and normalizes Windows UNC paths", () => {
    expect(normalizePathForJvm("\\\\?\\C:\\Super PI")).toBe("C:\\Super PI");
    expect(normalizePathForJvm("\\\\?\\UNC\\server\\share")).toBe("\\\\server\\share");

    const resDir = makeTempDir("superpi-res-");
    const sidecar = path.join(resDir, "sidecar");
    fs.mkdirSync(path.join(sidecar, "runtime", "bin"), { recursive: true });
    fs.mkdirSync(path.join(sidecar, "lib"), { recursive: true });
    const javaBin =
      process.platform === "win32"
        ? path.join(sidecar, "runtime", "bin", "java.exe")
        : path.join(sidecar, "runtime", "bin", "java");
    fs.writeFileSync(javaBin, "stub", "utf8");

    const spec = locateHostLaunchSpec({
      homeDir: "/tmp/sp-home",
      resourceDir: resDir,
    });
    expect(spec?.kind).toBe("sidecar");
    expect(spec?.command).toBe(javaBin);
    expect(spec?.args).toContain("serve");
  });
});
