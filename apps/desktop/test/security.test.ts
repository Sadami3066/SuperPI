import { describe, expect, it } from "vitest";
import { APP_CSP_HEADER, isAllowedAppNavigation } from "../src/main/security.js";

describe("desktop navigation and CSP security", () => {
  it("allows same-origin app URLs and blocks prefix-spoofed origins", () => {
    expect(isAllowedAppNavigation("superpi://app/index.html", "superpi://app")).toBe(true);
    expect(isAllowedAppNavigation("superpi://app.evil.com/index.html", "superpi://app")).toBe(
      false,
    );

    expect(isAllowedAppNavigation("http://localhost:5173/chat", "http://localhost:5173")).toBe(
      true,
    );
    expect(
      isAllowedAppNavigation("http://localhost:5173.evil.com/chat", "http://localhost:5173"),
    ).toBe(false);
    expect(isAllowedAppNavigation("http://localhost:51730/chat", "http://localhost:5173")).toBe(
      false,
    );
    expect(isAllowedAppNavigation("not-a-valid-url", "superpi://app")).toBe(false);
  });

  it("includes strict default-src and script-src directives in APP_CSP_HEADER", () => {
    expect(APP_CSP_HEADER).toContain("default-src 'self' superpi:");
    expect(APP_CSP_HEADER).toContain("script-src 'self'");
  });
});
