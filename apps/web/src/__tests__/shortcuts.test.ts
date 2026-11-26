import { describe, expect, it } from "vitest";
import { formatShortcut, isMacPlatform } from "../platform/shortcuts";

describe("shortcuts helpers", () => {
  it("formats OS-specific modifier keys for macOS and Linux/Windows", () => {
    expect(isMacPlatform("darwin")).toBe(true);
    expect(isMacPlatform("linux")).toBe(false);
    expect(formatShortcut(["mod", "k"], "darwin")).toBe("⌘K");
    expect(formatShortcut(["mod", "k"], "linux")).toBe("Ctrl+K");
    expect(formatShortcut(["mod", "shift", "s"], "darwin")).toBe("⌘⇧S");
    expect(formatShortcut(["mod", "shift", "s"], "win32")).toBe("Ctrl+Shift+S");
    expect(formatShortcut(["ctrl", "tab"], "linux")).toBe("Ctrl+Tab");
  });
});
