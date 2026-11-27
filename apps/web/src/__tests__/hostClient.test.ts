import { afterEach, describe, expect, it, vi } from "vitest";
import { HostApiError, HostClient } from "../api/hostClient";

describe("HostClient", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("parses non-JSON error responses cleanly using text fallback", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("Bad gateway from proxy", { status: 502 })),
    );
    const client = new HostClient({ baseUrl: "http://127.0.0.1:4300", token: "tok" });
    await expect(client.getHealth()).rejects.toMatchObject({
      status: 502,
      message: "Bad gateway from proxy",
    });
  });

  it("extracts structured error message from JSON error responses", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ error: "File modified on disk" }), {
            status: 409,
            headers: { "Content-Type": "application/json" },
          }),
      ),
    );
    const client = new HostClient({ baseUrl: "http://127.0.0.1:4300", token: "tok" });
    await expect(
      client.uploadBotFile("home", { path: "a.txt", dataBase64: "YQ==" }),
    ).rejects.toMatchObject({
      status: 409,
      message: "File modified on disk",
    });
  });

  it("aborts and throws 408 when request exceeds timeout", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_input: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => {
              reject(new DOMException("Aborted", "AbortError"));
            });
          }),
      ),
    );
    const client = new HostClient({ baseUrl: "http://127.0.0.1:4300", token: "tok" });
    const reqPromise = client.getHealth();
    const expectation = expect(reqPromise).rejects.toBeInstanceOf(HostApiError);
    await vi.advanceTimersByTimeAsync(15_001);
    await expectation;
    await expect(reqPromise).rejects.toMatchObject({ status: 408 });
  });

  it("notifies onError when terminal SSE stream ends unexpectedly without exit frame", async () => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"type":"chunk","data":"hi"}\n\n'));
        controller.close();
      },
    });
    vi.stubGlobal("fetch", vi.fn(async () => new Response(stream, { status: 200 })));

    const client = new HostClient({ baseUrl: "http://127.0.0.1:4300", token: "tok" });
    const frames: string[] = [];
    const errors: string[] = [];

    client.streamTerminal(
      "term-1",
      (f) => frames.push(f.type),
      (err) => errors.push(err.message),
    );

    await vi.waitFor(() => {
      expect(errors.length).toBe(1);
    });
    expect(frames).toEqual(["chunk"]);
  });

  it("does not report error when terminal SSE stream emits exit frame before closing", async () => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"type":"exit","exitCode":0}\n\n'));
        controller.close();
      },
    });
    vi.stubGlobal("fetch", vi.fn(async () => new Response(stream, { status: 200 })));

    const client = new HostClient({ baseUrl: "http://127.0.0.1:4300", token: "tok" });
    const frames: string[] = [];
    const errors: string[] = [];

    client.streamTerminal(
      "term-2",
      (f) => frames.push(f.type),
      (err) => errors.push(err.message),
    );

    await vi.waitFor(() => {
      expect(frames).toEqual(["exit"]);
    });
    expect(errors).toEqual([]);
  });
});
