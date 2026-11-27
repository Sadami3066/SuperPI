import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  clearDesktopCrashes,
  listDesktopCrashes,
  recordDesktopCrash,
} from "../src/main/crash-log.js";

const tempDirs: string[] = [];

afterEach(() => {
  while (tempDirs.length > 0) {
    const dir = tempDirs.pop();
    if (dir) fs.rmSync(dir, { recursive: true, force: true });
  }
});

describe("desktop crash log store", () => {
  it("records, caps at 20 entries, and clears crash records", () => {
    const homeDir = fs.mkdtempSync(path.join(os.tmpdir(), "superpi-crash-"));
    tempDirs.push(homeDir);

    for (let i = 0; i < 25; i += 1) {
      recordDesktopCrash(homeDir, {
        processType: "renderer",
        reason: `crashed-${i}`,
        exitCode: i,
        timestamp: 1000 + i,
      });
    }

    const list = listDesktopCrashes(homeDir);
    expect(list.length).toBe(20);
    expect(list[0]?.reason).toBe("crashed-24");
    expect(list[19]?.reason).toBe("crashed-5");

    clearDesktopCrashes(homeDir);
    expect(listDesktopCrashes(homeDir)).toEqual([]);
  });
});
