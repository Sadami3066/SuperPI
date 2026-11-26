import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..", "..", "..");
const hostDistRoot = path.join(repoRoot, "apps", "host", "build", "distributions");
const targetSidecarDir = path.join(repoRoot, "apps", "desktop", "resources", "sidecar");

function detectHostPlatform() {
  const osPart =
    process.platform === "win32"
      ? "windows"
      : process.platform === "darwin"
        ? "macos"
        : "linux";
  const archPart =
    process.arch === "arm64" ? "arm64" : process.arch === "x64" ? "x64" : process.arch;
  return `${osPart}-${archPart}`;
}

function findBundleDir(platformQualifier) {
  const platformDir = path.join(hostDistRoot, platformQualifier);
  if (!fs.existsSync(platformDir)) {
    throw new Error(
      `Missing Host distribution directory: ${platformDir}\nRun ./gradlew -Psuperpi.hostOnly=true -Psuperpi.strictBoundaries=true :apps:host:packageHostDist first.`,
    );
  }
  const entries = fs.readdirSync(platformDir, { withFileTypes: true });
  const match = entries.find(
    (e) => e.isDirectory() && e.name.startsWith("super-pi-") && e.name.endsWith(platformQualifier),
  );
  if (!match) {
    throw new Error(`No staged super-pi bundle directory found in ${platformDir}`);
  }
  return path.join(platformDir, match.name);
}

function ensureExecPerms(dir) {
  if (process.platform === "win32") return;
  const binDirs = [path.join(dir, "bin"), path.join(dir, "runtime", "bin")];
  for (const b of binDirs) {
    if (!fs.existsSync(b)) continue;
    for (const entry of fs.readdirSync(b)) {
      const full = path.join(b, entry);
      if (fs.statSync(full).isFile()) {
        fs.chmodSync(full, 0o755);
      }
    }
  }
  const jspawn = path.join(dir, "runtime", "lib", "jspawnhelper");
  if (fs.existsSync(jspawn)) {
    fs.chmodSync(jspawn, 0o755);
  }
}

function copyDereferenced(src, dst) {
  const st = fs.statSync(src);
  if (st.isDirectory()) {
    fs.mkdirSync(dst, { recursive: true });
    for (const entry of fs.readdirSync(src)) {
      copyDereferenced(path.join(src, entry), path.join(dst, entry));
    }
  } else if (st.isFile()) {
    fs.copyFileSync(src, dst);
    if (process.platform !== "win32") {
      const execBits = st.mode & 0o111;
      fs.chmodSync(dst, execBits ? 0o755 : 0o644);
    }
  }
}

const requestedTarget = process.env.SUPERPI_TARGET_PLATFORM || detectHostPlatform();
const sourceBundle = findBundleDir(requestedTarget);
console.log(`Staging sidecar from ${sourceBundle} -> ${targetSidecarDir}`);

fs.rmSync(targetSidecarDir, { recursive: true, force: true });
copyDereferenced(sourceBundle, targetSidecarDir);
ensureExecPerms(targetSidecarDir);

const javaBin = requestedTarget.startsWith("windows-")
  ? path.join(targetSidecarDir, "runtime", "bin", "java.exe")
  : path.join(targetSidecarDir, "runtime", "bin", "java");
const libDir = path.join(targetSidecarDir, "lib");

if (!fs.existsSync(javaBin) || !fs.existsSync(libDir)) {
  throw new Error(`Sidecar verification failed: missing ${javaBin} or ${libDir}`);
}
console.log(`Sidecar staged cleanly (${requestedTarget}, host=${os.platform()}/${os.arch()}).`);
