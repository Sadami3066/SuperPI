import { useCallback, useEffect, useRef, useState } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { IconPlus, IconRefresh, IconTrash } from "@tabler/icons-react";
import type { HostTerminalInfoDto } from "../api/types";
import { useI18n } from "../i18n";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useSettingsStore } from "../stores/settingsStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { Badge, Button, IconButton } from "../ui";
import type { WorkbenchPanelContext } from "./registry";

function readCssVar(name: string, fallback: string): string {
  if (typeof window === "undefined") return fallback;
  const val = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return val || fallback;
}

export default function TerminalPanel({ context }: { context: WorkbenchPanelContext }) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const bot = useBotStore((s) => s.bots.find((b) => b.id === context.botId) ?? s.bots[0]);
  const dark = useSettingsStore((s) => s.dark);
  const themePreset = useSettingsStore((s) => s.themePreset);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const summary = useSessionStore((s) => s.summariesById[activeSessionId]);
  const activeWtId = useWorkbenchStore((s) => s.activeWorktreeByBot[bot?.id ?? ""] ?? null);

  const worktreeId = activeWtId ?? summary?.worktreeId ?? null;

  const [termInfo, setTermInfo] = useState<HostTerminalInfoDto | null>(null);
  const [exitCode, setExitCode] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const hostRef = useRef<HTMLDivElement | null>(null);
  const xtermRef = useRef<Terminal | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const termIdRef = useRef<string | null>(null);
  const streamStopRef = useRef<(() => void) | null>(null);

  const attachTerminal = useCallback(
    async (fresh: boolean) => {
      if (!client || !bot || bot.isRemote) return;
      streamStopRef.current?.();
      streamStopRef.current = null;
      setError(null);
      setExitCode(null);

      try {
        const info = await client.createTerminal(bot.id, { worktreeId, fresh });
        setTermInfo(info);
        termIdRef.current = info.id;

        const term = xtermRef.current;
        if (term) {
          term.reset();
          if (info.history) {
            term.write(info.history);
          }
          fitRef.current?.fit();
        }

        const stop = client.streamTerminal(
          info.id,
          (frame) => {
            if (frame.type === "output" && frame.data) {
              xtermRef.current?.write(frame.data);
            } else if (frame.type === "exit") {
              setExitCode(frame.exitCode ?? 0);
            }
          },
          (err) => {
            setError(err.message);
          },
        );
        streamStopRef.current = stop;
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to start terminal");
      }
    },
    [bot, client, worktreeId],
  );

  useEffect(() => {
    const container = hostRef.current;
    if (!container || bot?.isRemote) return undefined;

    const term = new Terminal({
      cursorBlink: true,
      convertEol: false,
      fontSize: 12.5,
      lineHeight: 1.4,
      fontFamily: readCssVar("--pi-font-mono", "ui-monospace, monospace"),
      theme: {
        background: readCssVar("--pi-code-bg", "#141418"),
        foreground: readCssVar("--pi-ink", "#f5f5f7"),
        cursor: readCssVar("--pi-accent", "#ffffff"),
        selectionBackground: readCssVar("--pi-accent-soft", "#333344"),
      },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(container);
    fit.fit();

    xtermRef.current = term;
    fitRef.current = fit;

    const dataSub = term.onData((data) => {
      const id = termIdRef.current;
      if (id && client) {
        void client.sendTerminalInput(id, data).catch(() => void 0);
      }
    });

    const ro = new ResizeObserver(() => {
      try {
        fit.fit();
      } catch {
        void 0;
      }
    });
    ro.observe(container);

    void attachTerminal(false);

    return () => {
      ro.disconnect();
      dataSub.dispose();
      streamStopRef.current?.();
      streamStopRef.current = null;
      term.dispose();
      xtermRef.current = null;
      fitRef.current = null;
    };
  }, [attachTerminal, bot?.isRemote, client]);

  useEffect(() => {
    const term = xtermRef.current;
    if (!term) return;
    term.options.theme = {
      background: readCssVar("--pi-code-bg", "#141418"),
      foreground: readCssVar("--pi-ink", "#f5f5f7"),
      cursor: readCssVar("--pi-accent", "#ffffff"),
      selectionBackground: readCssVar("--pi-accent-soft", "#333344"),
    };
  }, [dark, themePreset]);

  if (!bot) return null;

  if (bot.isRemote) {
    return (
      <div className="p-4 text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
        {t("term.remoteUnsupported")}
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div
        className="flex h-[38px] shrink-0 items-center justify-between gap-2 px-3"
        style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
      >
        <div className="flex min-w-0 items-center gap-2">
          {termInfo && (
            <span
              className="truncate text-[11.5px]"
              style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink-secondary)" }}
            >
              {termInfo.shell.split("/").pop()} · {termInfo.cwd}
            </span>
          )}
          {worktreeId && <Badge tone="accent">worktree</Badge>}
        </div>

        <div className="flex shrink-0 items-center gap-1">
          <IconButton
            size="sm"
            label={t("term.newSession")}
            onClick={() => void attachTerminal(true)}
          >
            <IconPlus size={14} stroke={1.75} />
          </IconButton>
          <IconButton
            size="sm"
            label={t("term.clear")}
            onClick={() => xtermRef.current?.clear()}
          >
            <IconTrash size={13.5} stroke={1.7} />
          </IconButton>
          <IconButton
            size="sm"
            label={t("term.restart")}
            onClick={() => {
              const id = termIdRef.current;
              if (client && id) {
                void client.deleteTerminal(id).finally(() => void attachTerminal(true));
              } else {
                void attachTerminal(true);
              }
            }}
          >
            <IconRefresh size={13.5} stroke={1.7} />
          </IconButton>
        </div>
      </div>

      {error && (
        <div className="px-3 py-1.5 text-[12px]" style={{ color: "var(--pi-danger)" }}>
          {error}
        </div>
      )}

      {exitCode !== null && (
        <div
          className="flex items-center justify-between px-3 py-1.5 text-[12px]"
          style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
        >
          <span>{t("term.exited", { code: exitCode })}</span>
          <Button variant="secondary" size="sm" onClick={() => void attachTerminal(true)}>
            {t("term.restart")}
          </Button>
        </div>
      )}

      <div
        ref={hostRef}
        className="min-h-0 flex-1 overflow-hidden p-2"
        style={{ background: "var(--pi-code-bg)" }}
      />
    </div>
  );
}
