import { useCallback, useEffect, useRef, useState } from "react";
import { IconFolderPlus, IconMessageForward, IconUpload } from "@tabler/icons-react";
import { classifyAttachmentKind, formatAttachmentSize } from "../api/mappers";
import type { HostWorkspaceFileEntryDto } from "../api/types";
import { BrandIcon } from "../features/shared/BrandAssets";
import { useI18n } from "../i18n";
import { revealInFileManager } from "../platform/bridge";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { Button, ConfirmDialog, ContextMenu, ContextMenuItem, ContextMenuSeparator, Dialog, IconButton, Input } from "../ui";
import type { WorkbenchPanelContext } from "./registry";

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      const comma = result.indexOf(",");
      resolve(comma >= 0 ? result.slice(comma + 1) : "");
    };
    reader.onerror = () => reject(new Error("Read failed"));
    reader.readAsDataURL(file);
  });
}

function EntryIcon({ entry }: { entry: HostWorkspaceFileEntryDto }) {
  if (entry.isDirectory) {
    return <BrandIcon name="file/folder_closed" size={15} style={{ color: "var(--pi-accent)" }} />;
  }
  const kind = classifyAttachmentKind("", entry.name);
  if (kind === "image") {
    return <BrandIcon name="file/image" size={15} style={{ color: "var(--pi-ink-secondary)" }} />;
  }
  if (kind === "text") {
    return <BrandIcon name="file/code" size={15} style={{ color: "var(--pi-ink-secondary)" }} />;
  }
  return <BrandIcon name="file/plain" size={15} style={{ color: "var(--pi-ink-tertiary)" }} />;
}

export default function FilesPanel({
  context,
  openFilesCount = 0,
  onOpenEditor,
}: {
  context: WorkbenchPanelContext;
  openFilesCount?: number;
  onOpenEditor?: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));
  const bot = useBotStore((s) => s.bots.find((b) => b.id === context.botId) ?? s.bots[0]);
  const openFileInPreview = useWorkbenchStore((s) => s.openFileInPreview);

  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const setComposerText = useSessionStore((s) => s.setComposerText);

  const [currentPath, setCurrentPath] = useState("");
  const [entries, setEntries] = useState<HostWorkspaceFileEntryDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [searchEntries, setSearchEntries] = useState<HostWorkspaceFileEntryDto[] | null>(null);

  const [mkdirOpen, setMkdirOpen] = useState(false);
  const [mkdirDraft, setMkdirDraft] = useState("");
  const [newFileOpen, setNewFileOpen] = useState(false);
  const [newFileDraft, setNewFileDraft] = useState("");
  const [renameTarget, setRenameTarget] = useState<HostWorkspaceFileEntryDto | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<HostWorkspaceFileEntryDto | null>(null);

  const uploadInputRef = useRef<HTMLInputElement | null>(null);

  const loadDir = useCallback(
    async (relPath: string) => {
      if (!client || !bot) return;
      setLoading(true);
      setError(null);
      try {
        const res = await client.listBotFiles(bot.id, relPath);
        setEntries(res.entries);
        setCurrentPath(res.path);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load files");
      } finally {
        setLoading(false);
      }
    },
    [bot, client],
  );

  useEffect(() => {
    setCurrentPath("");
    setQuery("");
    setSearchEntries(null);
    void loadDir("");
  }, [bot?.id, loadDir]);

  useEffect(() => {
    const trimmed = query.trim();
    if (!trimmed || !client || !bot) {
      setSearchEntries(null);
      return undefined;
    }
    const timer = setTimeout(() => {
      void client
        .searchBotFiles(bot.id, trimmed, currentPath)
        .then((res) => setSearchEntries(res.entries))
        .catch(() => setSearchEntries([]));
    }, 250);
    return () => clearTimeout(timer);
  }, [bot, client, currentPath, query]);

  if (!bot) return null;

  const segments = currentPath ? currentPath.split("/").filter(Boolean) : [];
  const visibleEntries = searchEntries ?? entries;

  const handleOpenEntry = (entry: HostWorkspaceFileEntryDto) => {
    if (entry.isDirectory) {
      setQuery("");
      setSearchEntries(null);
      void loadDir(entry.path);
      return;
    }
    openFileInPreview({
      source: "workspace",
      botId: bot.id,
      path: entry.path,
      name: entry.name,
    });
  };

  const handleInsertToChat = (entry: HostWorkspaceFileEntryDto) => {
    if (!activeSessionId) return;
    const token = `@${entry.path}`;
    const current = useSessionStore.getState().composerTextBySession[activeSessionId] ?? "";
    const next = current ? `${current.trimEnd()} ${token} ` : `${token} `;
    setComposerText(activeSessionId, next);
    useSessionStore.getState().requestComposerFocus();
  };

  const handleUploadFiles = async (files: FileList | null) => {
    if (!client || !files || files.length === 0) return;
    setError(null);
    try {
      for (const file of Array.from(files)) {
        const dataBase64 = await fileToBase64(file);
        const rel = currentPath ? `${currentPath}/${file.name}` : file.name;
        await client.uploadBotFile(bot.id, { path: rel, dataBase64, overwrite: true });
      }
      await loadDir(currentPath);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div
        className="shrink-0 space-y-2 px-3 py-2.5"
        style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
      >
        <div className="flex items-center gap-1.5">
          <div className="relative min-w-0 flex-1">
            <BrandIcon
              name="action/search"
              size={13.5}
              className="pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2"
              style={{ color: "var(--pi-ink-tertiary)" }}
            />
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("files.searchPlaceholder")}
              className="h-8 pl-8 text-[12.5px]"
            />
          </div>

          <IconButton size="sm" label={t("files.newFile")} onClick={() => setNewFileOpen(true)}>
            <BrandIcon name="action/add" size={15} />
          </IconButton>

          <IconButton size="sm" label={t("files.newFolder")} onClick={() => setMkdirOpen(true)}>
            <IconFolderPlus size={15} stroke={1.7} />
          </IconButton>

          <IconButton
            size="sm"
            label={t("files.upload")}
            onClick={() => uploadInputRef.current?.click()}
          >
            <IconUpload size={15} stroke={1.7} />
          </IconButton>

          {isElectron && !bot.isRemote && bot.cwd && (
            <IconButton
              size="sm"
              label={t("wb.revealDir")}
              onClick={() => void revealInFileManager(bot.cwd)}
            >
              <BrandIcon name="file/folder_open" size={15} />
            </IconButton>
          )}

          <IconButton
            size="sm"
            label={t("common.refresh")}
            onClick={() => void loadDir(currentPath)}
          >
            <BrandIcon name="action/retry" size={14.5} />
          </IconButton>
        </div>

        <input
          ref={uploadInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => {
            void handleUploadFiles(e.target.files);
            e.target.value = "";
          }}
        />

        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto text-[11.5px]">
            <button
              type="button"
              onClick={() => void loadDir("")}
              className="shrink-0 rounded px-1.5 py-0.5 font-medium transition-colors hover:bg-[var(--pi-hover)]"
              style={{ color: segments.length === 0 ? "var(--pi-ink)" : "var(--pi-ink-tertiary)" }}
            >
              {t("files.root")}
            </button>
            {segments.map((seg, i) => {
              const targetPath = segments.slice(0, i + 1).join("/");
              const isLast = i === segments.length - 1;
              return (
                <div key={targetPath} className="flex shrink-0 items-center gap-1">
                  <BrandIcon name="navigation/chevron_right" size={11} style={{ color: "var(--pi-ink-tertiary)" }} />
                  <button
                    type="button"
                    onClick={() => void loadDir(targetPath)}
                    className="rounded px-1.5 py-0.5 transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      color: isLast ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                      fontWeight: isLast ? 550 : 400,
                    }}
                  >
                    {seg}
                  </button>
                </div>
              );
            })}
          </div>

          {openFilesCount > 0 && onOpenEditor && (
            <button
              type="button"
              onClick={onOpenEditor}
              className="flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
              style={{
                background: "var(--pi-surface)",
                color: "var(--pi-accent)",
                border: "0.5px solid var(--pi-hairline)",
              }}
            >
              <span>{t("wb.openTabsCount", { count: openFilesCount })}</span>
              <BrandIcon name="navigation/chevron_right" size={10} />
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="px-3 pt-2 text-[12px]" style={{ color: "var(--pi-danger)" }}>
          {error}
        </div>
      )}

      <div className="min-h-0 flex-1 overflow-y-auto px-2 py-1.5">
        {loading && visibleEntries.length === 0 ? (
          <div className="py-10 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("common.loading")}
          </div>
        ) : visibleEntries.length === 0 ? (
          <div className="py-10 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            {query.trim() ? t("files.searchEmpty") : t("files.empty")}
          </div>
        ) : (
          <div className="space-y-0.5">
            {visibleEntries.map((entry) => (
              <ContextMenu
                key={entry.path}
                trigger={
                  <div className="group flex h-[32px] items-center gap-1.5 rounded-[8px] px-2 transition-colors hover:bg-[var(--pi-hover)]">
                    <button
                      type="button"
                      onClick={() => handleOpenEntry(entry)}
                      className="flex min-w-0 flex-1 items-center gap-2 text-left"
                    >
                      <EntryIcon entry={entry} />
                      <span
                        className="min-w-0 flex-1 truncate text-[12.5px]"
                        style={{ color: "var(--pi-ink)" }}
                      >
                        {searchEntries ? entry.path : entry.name}
                      </span>
                    </button>

                    <div className="relative flex h-6 w-[108px] shrink-0 items-center justify-end">
                      {!entry.isDirectory && (
                        <span
                          className="pointer-events-none text-[11px] tabular-nums transition-opacity duration-150 group-hover:opacity-0"
                          style={{ color: "var(--pi-ink-tertiary)" }}
                        >
                          {formatAttachmentSize(entry.sizeBytes)}
                        </span>
                      )}

                      <div className="absolute right-0 flex items-center gap-0.5 opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                        <IconButton
                          size="sm"
                          label={t("files.insertPath")}
                          onClick={() => handleInsertToChat(entry)}
                        >
                          <IconMessageForward size={13.5} stroke={1.7} />
                        </IconButton>
                        <IconButton
                          size="sm"
                          label={t("files.copyPath")}
                          onClick={() => void navigator.clipboard?.writeText(entry.path)}
                        >
                          <BrandIcon name="action/copy" size={13} />
                        </IconButton>
                        <IconButton
                          size="sm"
                          label={t("files.rename")}
                          onClick={() => {
                            setRenameTarget(entry);
                            setRenameDraft(entry.path);
                          }}
                        >
                          <BrandIcon name="action/pen" size={13} />
                        </IconButton>
                        <IconButton
                          size="sm"
                          label={t("common.delete")}
                          onClick={() => setDeleteTarget(entry)}
                        >
                          <BrandIcon name="action/delete" size={13} />
                        </IconButton>
                      </div>
                    </div>
                  </div>
                }
              >
                {!entry.isDirectory && (
                  <ContextMenuItem
                    icon={<BrandIcon name="action/eye" size={14} />}
                    label={t("preview.modePreview")}
                    onSelect={() => handleOpenEntry(entry)}
                  />
                )}
                <ContextMenuItem
                  icon={<IconMessageForward size={14} />}
                  label={t("files.insertPath")}
                  onSelect={() => handleInsertToChat(entry)}
                />
                <ContextMenuItem
                  icon={<BrandIcon name="action/copy" size={14} />}
                  label={t("files.copyPath")}
                  onSelect={() => void navigator.clipboard?.writeText(entry.path)}
                />
                {!bot.isRemote && isElectron && bot.cwd && (
                  <ContextMenuItem
                    icon={<BrandIcon name="file/folder_open" size={14} />}
                    label={t("nav.revealFolder")}
                    onSelect={() => {
                      const abs = `${bot.cwd.replace(/\/+$/, "")}/${entry.path}`;
                      void revealInFileManager(abs);
                    }}
                  />
                )}
                <ContextMenuSeparator />
                <ContextMenuItem
                  icon={<BrandIcon name="action/pen" size={14} />}
                  label={t("files.rename")}
                  onSelect={() => {
                    setRenameTarget(entry);
                    setRenameDraft(entry.path);
                  }}
                />
                <ContextMenuItem
                  danger
                  icon={<BrandIcon name="action/delete" size={14} />}
                  label={t("common.delete")}
                  onSelect={() => setDeleteTarget(entry)}
                />
              </ContextMenu>
            ))}
          </div>
        )}
      </div>

      <Dialog open={newFileOpen} onOpenChange={setNewFileOpen} title={t("files.newFile")} maxWidthClass="max-w-[380px]">
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            const raw = newFileDraft.trim().replace(/^\/+/, "");
            if (!client || !raw) return;
            const target = raw.includes("/") || !currentPath ? raw : `${currentPath}/${raw}`;
            const fileName = target.split("/").pop() || target;
            void client
              .uploadBotFile(bot.id, { path: target, dataBase64: "", overwrite: false })
              .then(() => {
                setNewFileDraft("");
                setNewFileOpen(false);
                void loadDir(currentPath);
                openFileInPreview({
                  source: "workspace",
                  botId: bot.id,
                  path: target,
                  name: fileName,
                });
              })
              .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
          }}
        >
          <Input
            autoFocus
            value={newFileDraft}
            onChange={(e) => setNewFileDraft(e.target.value)}
            placeholder={t("files.newFileName")}
          />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => setNewFileOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" variant="primary" size="sm" disabled={!newFileDraft.trim()}>
              {t("common.confirm")}
            </Button>
          </div>
        </form>
      </Dialog>

      <Dialog open={mkdirOpen} onOpenChange={setMkdirOpen} title={t("files.newFolder")} maxWidthClass="max-w-[380px]">
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            const raw = mkdirDraft.trim().replace(/^\/+/, "");
            if (!client || !raw) return;
            const target = raw.includes("/") || !currentPath ? raw : `${currentPath}/${raw}`;
            void client
              .mkdirBotFile(bot.id, target)
              .then(() => {
                setMkdirDraft("");
                setMkdirOpen(false);
                void loadDir(currentPath);
              })
              .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
          }}
        >
          <Input
            autoFocus
            value={mkdirDraft}
            onChange={(e) => setMkdirDraft(e.target.value)}
            placeholder={t("files.newFolderName")}
          />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => setMkdirOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" variant="primary" size="sm" disabled={!mkdirDraft.trim()}>
              {t("common.confirm")}
            </Button>
          </div>
        </form>
      </Dialog>

      <Dialog
        open={Boolean(renameTarget)}
        onOpenChange={(v) => {
          if (!v) setRenameTarget(null);
        }}
        title={t("files.rename")}
        maxWidthClass="max-w-[400px]"
      >
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (!client || !renameTarget || !renameDraft.trim()) return;
            void client
              .renameBotFile(bot.id, renameTarget.path, renameDraft.trim())
              .then(() => {
                setRenameTarget(null);
                void loadDir(currentPath);
              })
              .catch((err) => setError(err instanceof Error ? err.message : "Rename failed"));
          }}
        >
          <Input
            autoFocus
            value={renameDraft}
            onChange={(e) => setRenameDraft(e.target.value)}
            placeholder={t("files.renameTarget")}
          />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" size="sm" onClick={() => setRenameTarget(null)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" variant="primary" size="sm" disabled={!renameDraft.trim()}>
              {t("common.save")}
            </Button>
          </div>
        </form>
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteTarget(null);
        }}
        title={t("common.delete")}
        description={t("files.deleteConfirm", { name: deleteTarget?.name ?? "" })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !deleteTarget) return;
          void client
            .deleteBotFile(bot.id, deleteTarget.path, deleteTarget.isDirectory)
            .then(() => loadDir(currentPath))
            .catch((err) => setError(err instanceof Error ? err.message : "Delete failed"));
        }}
      />
    </div>
  );
}
