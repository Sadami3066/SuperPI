import { describe, expect, it } from "vitest";
import { extractDeepLinkFromArgv, parseDeepLinkUrl } from "../src/main/deeplink.js";

describe("desktop deep-link routing", () => {
  it("parses chat, super, new-chat, feature, and settings deep links", () => {
    expect(parseDeepLinkUrl("superpi://chat/sess%2D123")).toEqual({
      kind: "chat",
      sessionId: "sess-123",
    });
    expect(parseDeepLinkUrl("superpi://super")).toEqual({ kind: "super" });
    expect(parseDeepLinkUrl("superpi://new-chat")).toEqual({ kind: "new_chat" });
    expect(parseDeepLinkUrl("superpi://timers")).toEqual({ kind: "feature", view: "timers" });
    expect(parseDeepLinkUrl("superpi://inbox")).toEqual({ kind: "feature", view: "inbox" });
    expect(parseDeepLinkUrl("superpi://settings/diagnostics?checkUpdate=1")).toEqual({
      kind: "settings",
      tab: "diagnostics",
      autoCheckUpdate: true,
    });
  });

  it("ignores static app origin and invalid deep links", () => {
    expect(parseDeepLinkUrl("superpi://app/index.html")).toBeNull();
    expect(parseDeepLinkUrl("superpi://chat/")).toBeNull();
    expect(parseDeepLinkUrl("https://example.com/chat/123")).toBeNull();
    expect(parseDeepLinkUrl("superpi://unknown/foo")).toBeNull();
    expect(parseDeepLinkUrl("not a url")).toBeNull();
  });

  it("extracts valid deep link from process argv", () => {
    expect(extractDeepLinkFromArgv(["/usr/bin/superpi", "--flag", "superpi://inbox"])).toBe(
      "superpi://inbox",
    );
    expect(extractDeepLinkFromArgv(["/usr/bin/superpi", "superpi://app/index.html"])).toBeNull();
  });
});
