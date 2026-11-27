import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import DOMPurify from "dompurify";
import mammoth from "mammoth";
import {
  IconCheck,
  IconDeviceFloppy,
  IconDownload,
  IconFile,
  IconFolder,
  IconMessageForward,
  IconZoomIn,
  IconZoomOut,
  IconZoomReset,
  IconX,
} from "@tabler/icons-react";
import { HostApiError } from "../api/hostClient";
import type { OpenPreviewTarget } from "../api/types";
import { MarkdownView } from "../features/chat/MarkdownView";
import { BrandIcon, PiMascot } from "../features/shared/BrandAssets";
import { useI18n } from "../i18n";
import { revealInFileManager } from "../platform/bridge";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useSettingsStore } from "../stores/settingsStore";
import { previewTargetKey, useWorkbenchStore } from "../stores/workbenchStore";
import {
  Button,
  ConfirmDialog,
  ContextMenu,
  ContextMenuItem,
  ContextMenuSeparator,
  IconButton,
} from "../ui";
import { CodeEditorView } from "./CodeEditorView";
import { classifyPreviewKind, parseCsvRows, type PreviewKind } from "./previewClassifier";
import type { WorkbenchPanelContext } from "./registry";

function utf8ToBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1200);
}

interface LoadedContent {
  kind: PreviewKind;
  text: string;
  draft: string;
  truncated: boolean;
  mtimeMs?: number;
  blobUrl: string | null;
  docxHtml: string;
  error: string | null;
}

function initialLoaded(kind: PreviewKind): LoadedContent {
  return {
    kind,
    text: "",
    draft: "",
    truncated: false,
    mtimeMs: undefined,
    blobUrl: null,
    docxHtml: "",
    error: null,
  };
}

export default function PreviewPanel({
  context,
  onBackToFiles,
}: {
  context: WorkbenchPanelContext;
  onBackToFiles?: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const dark = useSettingsStore((s) => s.dark);

  const openFiles = useWorkbenchStore((s) => s.openFiles);
  const activeFileKey = useWorkbenchStore((s) => s.activeFileKey);
  const setActivePreviewFile = useWorkbenchStore((s) => s.setActivePreviewFile);
  const closePreviewFile = useWorkbenchStore((s) => s.closePreviewFile);
  const openPanel = useWorkbenchStore((s) => s.openPanel);

  const activeSessionId = useSessionStore((s) => s.activeSessionId) || context.sessionId || "";
  const setComposerText = useSessionStore((s) => s.setComposerText);

  const activeTarget: OpenPreviewTarget | null = useMemo(() => {
    if (openFiles.length === 0) return null;
    return openFiles.find((f) => previewTargetKey(f) === activeFileKey) ?? openFiles[openFiles.length - 1];
  }, [activeFileKey, openFiles]);

  const activeKey = activeTarget ? previewTargetKey(activeTarget) : "";

  const [viewMode, setViewMode] = useState<"preview" | "split" | "source">("preview");
  const [zoomPct, setZoomPct] = useState(100);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedNotice, setSavedNotice] = useState(false);
  const [saveConflict, setSaveConflict] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const [dirtyCloseKey, setDirtyCloseKey] = useState<string | null>(null);
  const [loaded, setLoaded] = useState<LoadedContent>(() => initialLoaded("code"));
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const activeTargetRef = useRef<OpenPreviewTarget | null>(activeTarget);
  activeTargetRef.current = activeTarget;
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));
  const bots = useBotStore((s) => s.bots);

  useEffect(() => {
    return () => {
      if (savedTimerRef.current !== null) clearTimeout(savedTimerRef.current);
    };
  }, []);

  useEffect(() => {
    setViewMode("preview");
    setZoomPct(100);
    setSavedNotice(false);
    setSaveConflict(false);
  }, [activeKey]);

  useEffect(() => {
    const target = activeTargetRef.current;
    if (!client || !target || !activeKey) return undefined;
    let cancelled = false;
    let activeBlobUrl: string | null = null;

    const kind = classifyPreviewKind(
      target.name,
      target.source === "artifact" ? target.mime : undefined,
    );

    const load = async () => {
      setLoading(true);
      setLoaded(initialLoaded(kind));

      try {
        if (kind === "pdf" || kind === "image") {
          const rawBlob =
            target.source === "workspace"
              ? await client.fetchBotFileRaw(target.botId, target.path, false)
              : await client.fetchSessionArtifact(target.sessionId, target.path, false);
          if (cancelled) return;
          const typed =
            kind === "pdf" ? new Blob([rawBlob], { type: "application/pdf" }) : rawBlob;
          activeBlobUrl = URL.createObjectURL(typed);
          setLoaded({ ...initialLoaded(kind), blobUrl: activeBlobUrl });
          return;
        }

        if (kind === "docx") {
          const rawBlob =
            target.source === "workspace"
              ? await client.fetchBotFileRaw(target.botId, target.path, false)
              : await client.fetchSessionArtifact(target.sessionId, target.path, false);
          if (cancelled) return;
          const buf = await rawBlob.arrayBuffer();
          const result = await mammoth.convertToHtml({ arrayBuffer: buf });
          if (cancelled) return;
          const safe = DOMPurify.sanitize(result.value);
          setLoaded({ ...initialLoaded(kind), docxHtml: safe });
          return;
        }

        if (kind === "binary") {
          setLoaded(initialLoaded("binary"));
          return;
        }

        if (target.source === "workspace") {
          const res = await client.readBotFile(target.botId, target.path);
          if (cancelled) return;
          if (res.isBinary) {
            setLoaded(initialLoaded("binary"));
          } else {
            setLoaded({
              ...initialLoaded(kind),
              text: res.content,
              draft: res.content,
              truncated: res.truncated,
              mtimeMs: res.mtimeMs,
            });
          }
          return;
        }

        const rawBlob = await client.fetchSessionArtifact(
          target.sessionId,
          target.path,
          false,
        );
        if (cancelled) return;
        const text = await rawBlob.text();
        if (cancelled) return;
        setLoaded({
          ...initialLoaded(kind),
          text,
          draft: text,
          truncated: false,
        });
      } catch (err) {
        if (!cancelled) {
          setLoaded({
            ...initialLoaded(kind),
            error: err instanceof Error ? err.message : "Failed to load file",
          });
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void load();

    return () => {
      cancelled = true;
      if (activeBlobUrl) {
        URL.revokeObjectURL(activeBlobUrl);
      }
    };
  }, [activeKey, client, reloadTick]);

  const canSave =
    Boolean(activeTarget && activeTarget.source === "workspace") &&
    !loaded.truncated &&
    (loaded.kind === "code" ||
      loaded.kind === "markdown" ||
      loaded.kind === "html" ||
      loaded.kind === "csv");
  const dirty = canSave && loaded.draft !== loaded.text;

  const handleSave = useCallback(
    async (forceOverwrite = false) => {
      if (!client || !activeTarget || activeTarget.source !== "workspace" || !dirty || saving) return;
      setSaving(true);
      setSaveConflict(false);
      try {
        const res = await client.uploadBotFile(activeTarget.botId, {
          path: activeTarget.path,
          dataBase64: utf8ToBase64(loaded.draft),
          overwrite: true,
          expectedMtimeMs: forceOverwrite ? undefined : loaded.mtimeMs,
        });
        setLoaded((cur) => ({
          ...cur,
          text: cur.draft,
          mtimeMs: res.mtimeMs ?? cur.mtimeMs,
          error: null,
        }));
        setSavedNotice(true);
        if (savedTimerRef.current !== null) clearTimeout(savedTimerRef.current);
        savedTimerRef.current = setTimeout(() => setSavedNotice(false), 1800);
      } catch (err) {
        if (err instanceof HostApiError && err.status === 409) {
          setSaveConflict(true);
        } else {
          setLoaded((cur) => ({
            ...cur,
            error: err instanceof Error ? err.message : "Save failed",
          }));
        }
      } finally {
        setSaving(false);
      }
    },
    [activeTarget, client, dirty, loaded.draft, loaded.mtimeMs, saving],
  );

  const handleDownload = async () => {
    if (!client || !activeTarget) return;
    try {
      const blob =
        activeTarget.source === "workspace"
          ? await client.fetchBotFileRaw(activeTarget.botId, activeTarget.path, true)
          : await client.fetchSessionArtifact(activeTarget.sessionId, activeTarget.path, true);
      triggerDownload(blob, activeTarget.name);
    } catch {
      void 0;
    }
  };

  const handleQuoteToChat = () => {
    if (!activeTarget || !activeSessionId) return;
    const ref = `@${activeTarget.path}`;
    const current = useSessionStore.getState().composerTextBySession[activeSessionId] ?? "";
    const next = current ? `${current.trimEnd()} ${ref} ` : `${ref} `;
    setComposerText(activeSessionId, next);
    useSessionStore.getState().requestComposerFocus();
  };

  const requestCloseTab = useCallback(
    (key: string) => {
      if (key === activeKey && dirty) {
        setDirtyCloseKey(key);
        return;
      }
      closePreviewFile(key);
    },
    [activeKey, closePreviewFile, dirty],
  );

  const closeOtherTabs = useCallback(
    (keepKey: string) => {
      for (const f of openFiles) {
        const k = previewTargetKey(f);
        if (k !== keepKey) {
          closePreviewFile(k);
        }
      }
      setActivePreviewFile(keepKey);
    },
    [closePreviewFile, openFiles, setActivePreviewFile],
  );

  const csvRows = useMemo(() => {
    if (loaded.kind !== "csv" || !loaded.draft) return [];
    const delimiter = activeTarget?.name.toLowerCase().endsWith(".tsv") ? "\t" : ",";
    return parseCsvRows(loaded.draft, delimiter);
  }, [activeTarget?.name, loaded.draft, loaded.kind]);

  if (!activeTarget) {
    return (
      <div className="flex h-full flex-col items-center justify-center px-6 text-center">
        <PiMascot mood="wave" size={54} dark={dark} className="mb-3" />
        <div className="text-[14.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
          {t("preview.emptyTitle")}
        </div>
        <p className="mt-1.5 max-w-[320px] text-[12.5px] leading-[1.55]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("preview.emptyDesc")}
        </p>
        <Button
          variant="secondary"
          size="sm"
          className="mt-4"
          onClick={() => (onBackToFiles ? onBackToFiles() : openPanel("files"))}
        >
          <IconFolder size={14} />
          <span>{t("preview.goFiles")}</span>
        </Button>
      </div>
    );
  }

  const supportsDualMode =
    loaded.kind === "markdown" || loaded.kind === "html" || loaded.kind === "csv";
  const supportsSplit = loaded.kind === "markdown" || loaded.kind === "html";

  return (
    <div
      className="flex h-full min-h-0 flex-col outline-none"
      tabIndex={-1}
      onKeyDown={(e) => {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "w" && activeKey) {
          e.preventDefault();
          e.stopPropagation();
          requestCloseTab(activeKey);
        }
      }}
    >
      <div
        className="flex shrink-0 items-center gap-1 overflow-x-auto px-2 py-1.5"
        style={{
          background: "var(--pi-paper)",
          borderBottom: "0.5px solid var(--pi-hairline)",
        }}
      >
        {onBackToFiles && (
          <button
            type="button"
            title={t("wb.backToFiles")}
            onClick={onBackToFiles}
            className="flex h-7 shrink-0 items-center gap-1 rounded-[7px] px-2 text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            <BrandIcon name="navigation/back" size={13} />
            <span>{t("wb.backToFiles")}</span>
          </button>
        )}
        {openFiles.map((file) => {
          const key = previewTargetKey(file);
          const isCur = key === activeKey;
          const isTabDirty = isCur && dirty;
          const fileBot =
            file.source === "workspace" ? bots.find((b) => b.id === file.botId) : undefined;
          return (
            <ContextMenu
              key={key}
              trigger={
                <div
                  onAuxClick={(e) => {
                    if (e.button === 1) {
                      e.preventDefault();
                      requestCloseTab(key);
                    }
                  }}
                  className="group flex h-7 max-w-[180px] shrink-0 items-center gap-1.5 rounded-[7px] px-2 text-[12px] transition-colors"
                  style={{
                    background: isCur ? "var(--pi-surface)" : "transparent",
                    color: isCur ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                    border: isCur ? "0.5px solid var(--pi-hairline)" : "0.5px solid transparent",
                  }}
                >
                  {isTabDirty && (
                    <span
                      className="h-1.5 w-1.5 shrink-0 rounded-full"
                      style={{ background: "var(--pi-accent)" }}
                    />
                  )}
                  <button
                    type="button"
                    onClick={() => setActivePreviewFile(key)}
                    className="min-w-0 flex-1 truncate text-left font-medium"
                  >
                    {file.name}
                  </button>
                  <button
                    type="button"
                    aria-label={t("common.close")}
                    onClick={() => requestCloseTab(key)}
                    className="flex h-4 w-4 shrink-0 items-center justify-center rounded opacity-60 hover:bg-[var(--pi-hover)] hover:opacity-100"
                  >
                    <IconX size={11} stroke={1.8} />
                  </button>
                </div>
              }
            >
              <ContextMenuItem
                label={t("preview.closeTab")}
                onSelect={() => requestCloseTab(key)}
              />
              {openFiles.length > 1 && (
                <ContextMenuItem
                  label={t("preview.closeOthers")}
                  onSelect={() => closeOtherTabs(key)}
                />
              )}
              <ContextMenuSeparator />
              <ContextMenuItem
                label={t("files.copyPath")}
                onSelect={() => void navigator.clipboard?.writeText(file.path)}
              />
              {file.source === "workspace" &&
                isElectron &&
                fileBot &&
                !fileBot.isRemote &&
                fileBot.cwd && (
                  <ContextMenuItem
                    label={t("nav.revealFolder")}
                    onSelect={() =>
                      void revealInFileManager(`${fileBot.cwd.replace(/\/+$/, "")}/${file.path}`)
                    }
                  />
                )}
            </ContextMenu>
          );
        })}
      </div>

      <div
        className="flex shrink-0 items-center justify-between gap-2 px-3 py-1.5"
        style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
      >
        <span
          className="min-w-0 flex-1 truncate text-[11.5px]"
          style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink-tertiary)" }}
        >
          {activeTarget.path}
        </span>

        <div className="flex shrink-0 items-center gap-1">
          {supportsDualMode && (
            <div
              className="mr-1 inline-flex rounded-[7px] p-0.5"
              style={{ background: "var(--pi-wash)" }}
            >
              <button
                type="button"
                onClick={() => setViewMode("preview")}
                className="rounded-[5px] px-2 py-0.5 text-[11.5px] font-medium"
                style={{
                  background: viewMode === "preview" ? "var(--pi-surface)" : "transparent",
                  color: viewMode === "preview" ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                }}
              >
                {t("preview.modePreview")}
              </button>
              {supportsSplit && (
                <button
                  type="button"
                  onClick={() => setViewMode("split")}
                  className="rounded-[5px] px-2 py-0.5 text-[11.5px] font-medium"
                  style={{
                    background: viewMode === "split" ? "var(--pi-surface)" : "transparent",
                    color: viewMode === "split" ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                  }}
                >
                  {t("preview.modeSplit")}
                </button>
              )}
              <button
                type="button"
                onClick={() => setViewMode("source")}
                className="rounded-[5px] px-2 py-0.5 text-[11.5px] font-medium"
                style={{
                  background: viewMode === "source" ? "var(--pi-surface)" : "transparent",
                  color: viewMode === "source" ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                }}
              >
                {t("preview.modeSource")}
              </button>
            </div>
          )}

          {loaded.kind === "image" && loaded.blobUrl && (
            <div className="mr-1 flex items-center gap-0.5">
              <IconButton
                size="sm"
                label={t("preview.zoomOut")}
                onClick={() => setZoomPct((z) => Math.max(25, z - 25))}
              >
                <IconZoomOut size={13.5} />
              </IconButton>
              <span className="w-10 text-center text-[11px] tabular-nums" style={{ color: "var(--pi-ink-secondary)" }}>
                {zoomPct}%
              </span>
              <IconButton
                size="sm"
                label={t("preview.zoomIn")}
                onClick={() => setZoomPct((z) => Math.min(300, z + 25))}
              >
                <IconZoomIn size={13.5} />
              </IconButton>
              <IconButton size="sm" label={t("preview.zoomReset")} onClick={() => setZoomPct(100)}>
                <IconZoomReset size={13.5} />
              </IconButton>
            </div>
          )}

          {canSave && (dirty || savedNotice) && (
            <Button variant="primary" size="sm" disabled={saving || !dirty} onClick={() => void handleSave()}>
              {savedNotice ? <IconCheck size={13} /> : <IconDeviceFloppy size={13} />}
              <span>{savedNotice ? t("preview.saved") : t("common.save")}</span>
            </Button>
          )}

          {activeSessionId && (
            <IconButton size="sm" label={t("preview.quoteToChat")} onClick={handleQuoteToChat}>
              <IconMessageForward size={14} stroke={1.7} />
            </IconButton>
          )}

          <IconButton size="sm" label={t("present.download")} onClick={() => void handleDownload()}>
            <IconDownload size={14} stroke={1.7} />
          </IconButton>
        </div>
      </div>

      {loaded.truncated && (
        <div
          className="shrink-0 px-3 py-1 text-[11.5px]"
          style={{ background: "var(--pi-wash)", color: "var(--pi-warning)" }}
        >
          {t("preview.truncated")}
        </div>
      )}

      {saveConflict && (
        <div
          className="flex shrink-0 items-center justify-between gap-2 px-3 py-1.5 text-[12px]"
          style={{
            background: "var(--pi-wash)",
            borderBottom: "0.5px solid var(--pi-hairline)",
            color: "var(--pi-warning)",
          }}
        >
          <span>{t("preview.conflictError")}</span>
          <div className="flex shrink-0 items-center gap-1.5">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setSaveConflict(false);
                setReloadTick((n) => n + 1);
              }}
            >
              {t("preview.reloadFile")}
            </Button>
            <Button
              variant="primary"
              size="sm"
              disabled={saving}
              onClick={() => void handleSave(true)}
            >
              {t("preview.overwriteSave")}
            </Button>
          </div>
        </div>
      )}

      {loaded.error && (
        <div className="shrink-0 px-3 py-2 text-[12px]" style={{ color: "var(--pi-danger)" }}>
          {loaded.error}
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-hidden" style={{ background: "var(--pi-surface)" }}>
        {loading ? (
          <div className="flex h-full items-center justify-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("common.loading")}
          </div>
        ) : loaded.kind === "binary" ? (
          <div className="flex h-full flex-col items-center justify-center p-6 text-center">
            <div
              className="mb-3 flex h-12 w-12 items-center justify-center rounded-full"
              style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
            >
              <IconFile size={22} />
            </div>
            <div className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {activeTarget.name}
            </div>
            <p className="mt-1.5 max-w-[300px] text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("preview.binary")}
            </p>
            <Button variant="primary" size="sm" className="mt-4" onClick={() => void handleDownload()}>
              <IconDownload size={14} />
              <span>{t("present.download")}</span>
            </Button>
          </div>
        ) : loaded.kind === "pdf" && loaded.blobUrl ? (
          <iframe
            src={loaded.blobUrl}
            title={activeTarget.name}
            className="h-full w-full border-0"
          />
        ) : loaded.kind === "image" && loaded.blobUrl ? (
          <div className="flex h-full w-full items-center justify-center overflow-auto p-4" style={{ background: "var(--pi-code-bg)" }}>
            <img
              src={loaded.blobUrl}
              alt={activeTarget.name}
              className="max-w-none rounded-[6px] transition-transform duration-150"
              style={{ transform: `scale(${zoomPct / 100})` }}
            />
          </div>
        ) : loaded.kind === "docx" ? (
          <div className="h-full overflow-y-auto p-5" style={{ background: "var(--pi-paper)" }}>
            <div
              className="pi-prose mx-auto max-w-[680px] rounded-[12px] p-6"
              style={{
                background: "var(--pi-surface)",
                border: "0.5px solid var(--pi-hairline)",
              }}
              dangerouslySetInnerHTML={{ __html: loaded.docxHtml }}
            />
          </div>
        ) : loaded.kind === "html" && viewMode === "split" ? (
          <div className="grid h-full grid-cols-2 divide-x divide-[var(--pi-hairline)]">
            <CodeEditorView
              value={loaded.draft}
              filename={activeTarget.name}
              dark={dark}
              readOnly={!canSave}
              onChange={(val) => setLoaded((cur) => ({ ...cur, draft: val }))}
              onSave={() => void handleSave()}
            />
            <iframe
              sandbox="allow-scripts"
              srcDoc={loaded.draft}
              title={activeTarget.name}
              className="h-full w-full border-0 bg-white"
            />
          </div>
        ) : loaded.kind === "html" && viewMode === "preview" ? (
          <iframe
            sandbox="allow-scripts"
            srcDoc={loaded.draft}
            title={activeTarget.name}
            className="h-full w-full border-0 bg-white"
          />
        ) : loaded.kind === "markdown" && viewMode === "split" ? (
          <div className="grid h-full grid-cols-2 divide-x divide-[var(--pi-hairline)]">
            <CodeEditorView
              value={loaded.draft}
              filename={activeTarget.name}
              dark={dark}
              readOnly={!canSave}
              onChange={(val) => setLoaded((cur) => ({ ...cur, draft: val }))}
              onSave={() => void handleSave()}
            />
            <div className="h-full overflow-y-auto p-4">
              <MarkdownView content={loaded.draft} />
            </div>
          </div>
        ) : loaded.kind === "markdown" && viewMode === "preview" ? (
          <div className="h-full overflow-y-auto p-5">
            <div className="mx-auto max-w-[680px]">
              <MarkdownView content={loaded.draft} />
            </div>
          </div>
        ) : loaded.kind === "csv" && viewMode === "preview" ? (
          <div data-scrollable className="h-full overflow-auto p-3">
            <table className="w-full border-collapse text-[12px]">
              {csvRows.length > 0 && (
                <thead>
                  <tr>
                    {csvRows[0].map((cell, ci) => (
                      <th
                        key={ci}
                        className="px-2.5 py-1.5 text-left font-semibold"
                        style={{
                          background: "var(--pi-wash)",
                          border: "0.5px solid var(--pi-hairline)",
                          color: "var(--pi-ink)",
                        }}
                      >
                        {cell}
                      </th>
                    ))}
                  </tr>
                </thead>
              )}
              <tbody>
                {csvRows.slice(1).map((row, ri) => (
                  <tr key={ri}>
                    {row.map((cell, ci) => (
                      <td
                        key={ci}
                        className="px-2.5 py-1.5"
                        style={{
                          border: "0.5px solid var(--pi-hairline)",
                          color: "var(--pi-ink-secondary)",
                        }}
                      >
                        {cell}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <CodeEditorView
            value={loaded.draft}
            filename={activeTarget.name}
            dark={dark}
            readOnly={!canSave}
            onChange={(val) => setLoaded((cur) => ({ ...cur, draft: val }))}
            onSave={() => void handleSave()}
          />
        )}
      </div>

      <ConfirmDialog
        open={Boolean(dirtyCloseKey)}
        onOpenChange={(v) => {
          if (!v) setDirtyCloseKey(null);
        }}
        title={t("preview.dirtyCloseConfirm")}
        description={t("preview.dirtyCloseDesc", { name: activeTarget.name })}
        confirmLabel={t("preview.closeTab")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (dirtyCloseKey) closePreviewFile(dirtyCloseKey);
          setDirtyCloseKey(null);
        }}
      />
    </div>
  );
}
