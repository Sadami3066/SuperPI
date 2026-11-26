import { describe, expect, it } from "vitest";
import { normalizeBrowserUrl } from "../src/main/browser-view.js";

describe("normalizeBrowserUrl", () => {
  it("normalizes full http and https URLs", () => {
    expect(normalizeBrowserUrl("https://example.com/docs")).toBe("https://example.com/docs");
    expect(normalizeBrowserUrl("http://127.0.0.1:8080/health")).toBe(
      "http://127.0.0.1:8080/health",
    );
  });

  it("prepends http for loopback hosts and https for bare domains", () => {
    expect(normalizeBrowserUrl("localhost:3000/app")).toBe("http://localhost:3000/app");
    expect(normalizeBrowserUrl("github.com/whywood")).toBe("https://github.com/whywood");
  });

  it("turns plain search terms into a search URL", () => {
    expect(normalizeBrowserUrl("electron webcontentsview")).toBe(
      "https://www.google.com/search?q=electron%20webcontentsview",
    );
  });

  it("rejects unsafe or empty URLs", () => {
    expect(normalizeBrowserUrl("")).toBeNull();
    expect(normalizeBrowserUrl("file:///etc/passwd")).toBeNull();
    expect(normalizeBrowserUrl("javascript:alert(1)")).toBeNull();
    expect(normalizeBrowserUrl("superpi://app")).toBeNull();
  });
});
