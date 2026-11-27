import type { DesktopUpdateCheckResult } from "../shared/bridge.js";

export const RELEASE_REPO = "Sadami3066/SuperPI";
export const LATEST_RELEASE_API = `https://api.github.com/repos/${RELEASE_REPO}/releases/latest`;
export const LATEST_RELEASE_PAGE = `https://github.com/${RELEASE_REPO}/releases/latest`;

const SEMVER_RE = /^[vV]?(\d+)\.(\d+)\.(\d+)/;

export interface GitHubReleaseAsset {
  name?: string;
  browser_download_url?: string;
}

interface GitHubLatestReleasePayload {
  draft?: boolean;
  prerelease?: boolean;
  tag_name?: string;
  body?: string;
  html_url?: string;
  assets?: GitHubReleaseAsset[];
}

export function parseSemver(raw: string): [number, number, number] | null {
  const m = SEMVER_RE.exec(raw.trim());
  if (!m) return null;
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

export function compareSemver(a: string, b: string): number {
  const left = parseSemver(a);
  const right = parseSemver(b);
  if (!left || !right) return 0;
  for (let i = 0; i < 3; i += 1) {
    if (left[i] !== right[i]) return left[i] > right[i] ? 1 : -1;
  }
  return 0;
}

export function selectPlatformAsset(
  assets: readonly GitHubReleaseAsset[] | undefined,
  platform: NodeJS.Platform,
  arch: string,
): string | null {
  if (!assets || assets.length === 0) return null;
  const valid = assets.filter(
    (a): a is { name: string; browser_download_url: string } =>
      typeof a.name === "string" &&
      a.name.trim().length > 0 &&
      typeof a.browser_download_url === "string" &&
      a.browser_download_url.startsWith("https://"),
  );
  if (valid.length === 0) return null;

  const archToken = arch === "arm64" ? "arm64" : "x64";
  const pickByExt = (exts: string[]): string | null => {
    for (const ext of exts) {
      const matching = valid.filter((a) => a.name.toLowerCase().endsWith(ext));
      if (matching.length === 0) continue;
      const archHit = matching.find((a) => a.name.toLowerCase().includes(archToken));
      return (archHit ?? matching[0]).browser_download_url;
    }
    return null;
  };

  if (platform === "linux") return pickByExt([".appimage", ".deb"]);
  if (platform === "darwin") return pickByExt([".dmg", ".zip"]);
  if (platform === "win32") return pickByExt([".exe", ".msi"]);
  return null;
}

export function evaluateGitHubRelease(
  payload: unknown,
  currentVersion: string,
  platform: NodeJS.Platform,
  arch: string,
  now = Date.now(),
): DesktopUpdateCheckResult {
  if (!payload || typeof payload !== "object") {
    return {
      status: "error",
      currentVersion,
      checkedAt: now,
      error: "Invalid release response",
    };
  }
  const rel = payload as GitHubLatestReleasePayload;
  if (rel.draft || rel.prerelease) {
    return {
      status: "up_to_date",
      currentVersion,
      checkedAt: now,
      releaseUrl: LATEST_RELEASE_PAGE,
    };
  }
  const tag = (rel.tag_name ?? "").trim();
  const parsedLatest = parseSemver(tag);
  if (!parsedLatest) {
    return {
      status: "error",
      currentVersion,
      checkedAt: now,
      error: "No valid version tag found",
    };
  }
  const latestVersion = parsedLatest.join(".");
  const releaseUrl =
    typeof rel.html_url === "string" && rel.html_url.startsWith("https://")
      ? rel.html_url
      : LATEST_RELEASE_PAGE;

  if (compareSemver(latestVersion, currentVersion) <= 0) {
    return {
      status: "up_to_date",
      currentVersion,
      latestVersion,
      releaseUrl,
      checkedAt: now,
    };
  }

  const downloadUrl = selectPlatformAsset(rel.assets, platform, arch) ?? releaseUrl;
  return {
    status: "update_available",
    currentVersion,
    latestVersion,
    releaseNotes: (rel.body ?? "").trim(),
    downloadUrl,
    releaseUrl,
    checkedAt: now,
  };
}

export async function checkGitHubUpdate(
  currentVersion: string,
  platform: NodeJS.Platform = process.platform,
  arch: string = process.arch,
): Promise<DesktopUpdateCheckResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10_000);
  try {
    const resp = await fetch(LATEST_RELEASE_API, {
      headers: {
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": `SuperPI-Desktop/${currentVersion}`,
      },
      signal: controller.signal,
    });
    if (!resp.ok) {
      return {
        status: "error",
        currentVersion,
        checkedAt: Date.now(),
        releaseUrl: LATEST_RELEASE_PAGE,
        error: `HTTP ${resp.status}`,
      };
    }
    const json = (await resp.json()) as unknown;
    return evaluateGitHubRelease(json, currentVersion, platform, arch);
  } catch (err) {
    return {
      status: "error",
      currentVersion,
      checkedAt: Date.now(),
      releaseUrl: LATEST_RELEASE_PAGE,
      error: err instanceof Error ? err.message : "Failed to check updates",
    };
  } finally {
    clearTimeout(timer);
  }
}
