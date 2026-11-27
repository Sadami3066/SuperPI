import { describe, expect, it } from "vitest";
import {
  compareSemver,
  evaluateGitHubRelease,
  LATEST_RELEASE_PAGE,
  selectPlatformAsset,
} from "../src/main/updater.js";

describe("desktop GitHub release updater", () => {
  it("compares semantic versions accurately", () => {
    expect(compareSemver("2.1.0", "2.0.3")).toBe(1);
    expect(compareSemver("v2.0.3", "2.0.3")).toBe(0);
    expect(compareSemver("2.0.2", "2.0.3")).toBe(-1);
  });

  it("selects matching platform installer assets cleanly", () => {
    const assets = [
      {
        name: "SuperPI-2.1.0-x86_64.AppImage",
        browser_download_url: "https://github.com/example/SuperPI-2.1.0-x86_64.AppImage",
      },
      {
        name: "SuperPI-2.1.0-arm64.dmg",
        browser_download_url: "https://github.com/example/SuperPI-2.1.0-arm64.dmg",
      },
      {
        name: "SuperPI-2.1.0-x64.dmg",
        browser_download_url: "https://github.com/example/SuperPI-2.1.0-x64.dmg",
      },
      {
        name: "SuperPI-2.1.0-Setup.exe",
        browser_download_url: "https://github.com/example/SuperPI-2.1.0-Setup.exe",
      },
    ];

    expect(selectPlatformAsset(assets, "linux", "x64")).toContain(".AppImage");
    expect(selectPlatformAsset(assets, "darwin", "arm64")).toContain("arm64.dmg");
    expect(selectPlatformAsset(assets, "darwin", "x64")).toContain("x64.dmg");
    expect(selectPlatformAsset(assets, "win32", "x64")).toContain(".exe");
  });

  it("evaluates release payload, skips drafts, and detects updates", () => {
    expect(
      evaluateGitHubRelease({ draft: true, tag_name: "v9.9.9" }, "2.0.3", "linux", "x64", 100),
    ).toMatchObject({ status: "up_to_date", releaseUrl: LATEST_RELEASE_PAGE });

    expect(
      evaluateGitHubRelease(
        { tag_name: "v2.0.3", html_url: "https://github.com/Sadami3066/SuperPI/releases/tag/v2.0.3" },
        "2.0.3",
        "linux",
        "x64",
        100,
      ),
    ).toMatchObject({ status: "up_to_date", latestVersion: "2.0.3" });

    expect(
      evaluateGitHubRelease(
        {
          tag_name: "v2.2.0",
          body: "New features",
          html_url: "https://github.com/Sadami3066/SuperPI/releases/tag/v2.2.0",
          assets: [
            {
              name: "SuperPI-2.2.0.AppImage",
              browser_download_url: "https://github.com/Sadami3066/SuperPI/releases/download/v2.2.0/SuperPI-2.2.0.AppImage",
            },
          ],
        },
        "2.0.3",
        "linux",
        "x64",
        200,
      ),
    ).toMatchObject({
      status: "update_available",
      latestVersion: "2.2.0",
      releaseNotes: "New features",
      downloadUrl: "https://github.com/Sadami3066/SuperPI/releases/download/v2.2.0/SuperPI-2.2.0.AppImage",
    });
  });
});
