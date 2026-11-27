import { useCallback, useEffect, useMemo, useState } from "react";
import {
  IconArrowBackUp,
  IconExternalLink,
  IconGitBranch,
  IconGitCommit,
  IconGitMerge,
  IconMinus,
  IconPlus,
  IconRefresh,
  IconTrash,
} from "@tabler/icons-react";
import type {
  HostGitSummaryResponse,
  HostWorktreeDiffResponse,
  HostWorktreeDto,
} from "../api/types";
import { useI18n } from "../i18n";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  IconButton,
  Input,
  Select,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "../ui";
import { parseUnifiedDiff } from "./diffParser";
import type { WorkbenchPanelContext } from "./registry";

const INITIAL_FILES_LIMIT = 100;
const AUTO_ALL_DIFF_FILE_LIMIT = 40;
const INITIAL_DIFF_LINES_LIMIT = 400;

function DiffCodeView({ diffText, truncated }: { diffText: string; truncated?: boolean }) {
  const { t } = useI18n();
  const [maxLines, setMaxLines] = useState(INITIAL_DIFF_LINES_LIMIT);

  useEffect(() => {
    setMaxLines(INITIAL_DIFF_LINES_LIMIT);
  }, [diffText]);

  const parsed = useMemo(() => parseUnifiedDiff(diffText), [diffText]);

  if (!diffText.trim()) {
    return (
      <div className="py-8 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
        {t("git.diffEmpty")}
      </div>
    );
  }

  let remainingLines = maxLines;
  let totalLines = 0;
  for (const f of parsed) {
    for (const h of f.hunks) totalLines += h.lines.length;
  }

  return (
    <div className="space-y-3">
      {truncated && (
        <div
          className="rounded-[8px] px-3 py-1.5 text-[11.5px]"
          style={{ background: "var(--pi-wash)", color: "var(--pi-warning)" }}
        >
          {t("git.diffTruncated")}
        </div>
      )}
      {parsed.map((file, idx) => {
        if (remainingLines <= 0) return null;
        return (
          <div
            key={`${file.displayPath}-${idx}`}
            className="overflow-hidden rounded-[10px]"
            style={{
              background: "var(--pi-surface)",
              border: "0.5px solid var(--pi-hairline)",
            }}
          >
            <div
              className="flex items-center justify-between gap-2 px-3 py-1.5 text-[12px]"
              style={{
                background: "var(--pi-code-bg)",
                borderBottom: "0.5px solid var(--pi-hairline)",
              }}
            >
              <span
                className="truncate font-medium"
                style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink)" }}
              >
                {file.displayPath}
              </span>
              <span className="shrink-0 text-[11px] tabular-nums">
                <span style={{ color: "var(--pi-success)" }}>+{file.additions}</span>{" "}
                <span style={{ color: "var(--pi-danger)" }}>-{file.deletions}</span>
              </span>
            </div>

            {file.isBinary ? (
              <div className="px-3 py-2 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                Binary file
              </div>
            ) : (
              <div data-scrollable className="overflow-x-auto text-[11.5px] leading-[1.55]">
                {file.hunks.map((hunk, hIdx) => {
                  if (remainingLines <= 0) return null;
                  const slice = hunk.lines.slice(0, remainingLines);
                  remainingLines -= slice.length;
                  return (
                    <div key={hIdx}>
                      <div
                        className="px-3 py-0.5"
                        style={{
                          background: "var(--pi-wash)",
                          color: "var(--pi-ink-tertiary)",
                          fontFamily: "var(--pi-font-mono)",
                        }}
                      >
                        {hunk.header}
                      </div>
                      {slice.map((line, lIdx) => {
                        const bg =
                          line.kind === "add"
                            ? "color-mix(in srgb, var(--pi-success) 13%, transparent)"
                            : line.kind === "del"
                              ? "color-mix(in srgb, var(--pi-danger) 13%, transparent)"
                              : "transparent";
                        const fg =
                          line.kind === "add"
                            ? "var(--pi-success)"
                            : line.kind === "del"
                              ? "var(--pi-danger)"
                              : "var(--pi-ink)";
                        const prefix = line.kind === "add" ? "+" : line.kind === "del" ? "-" : " ";
                        return (
                          <div
                            key={lIdx}
                            className="flex px-2 whitespace-pre"
                            style={{ background: bg, fontFamily: "var(--pi-font-mono)" }}
                          >
                            <span
                              className="w-9 shrink-0 pr-2 text-right select-none"
                              style={{ color: "var(--pi-ink-tertiary)", opacity: 0.65 }}
                            >
                              {line.oldLine ?? ""}
                            </span>
                            <span
                              className="w-9 shrink-0 pr-2 text-right select-none"
                              style={{ color: "var(--pi-ink-tertiary)", opacity: 0.65 }}
                            >
                              {line.newLine ?? ""}
                            </span>
                            <span className="w-4 shrink-0 select-none" style={{ color: fg }}>
                              {prefix}
                            </span>
                            <span
                              style={{
                                color: line.kind === "meta" ? "var(--pi-ink-tertiary)" : "var(--pi-ink)",
                              }}
                            >
                              {line.text}
                            </span>
                          </div>
                        );
                      })}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}

      {totalLines > maxLines && (
        <div className="flex justify-center pt-1">
          <Button variant="secondary" size="sm" onClick={() => setMaxLines((n) => n + 600)}>
            {t("git.showMoreLines", { count: totalLines - maxLines })}
          </Button>
        </div>
      )}
    </div>
  );
}

export default function DiffWorktreePanel({ context }: { context: WorkbenchPanelContext }) {
  const { t, locale } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const bots = useBotStore((s) => s.bots);
  const bot = bots.find((b) => b.id === context.botId) ?? bots[0];
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const refreshSessionLists = useSessionStore((s) => s.refreshSessionLists);
  const selectSession = useSessionStore((s) => s.selectSession);
  const sessionSummary = useSessionStore((s) => s.summariesById[activeSessionId]);

  const activeWtId = useWorkbenchStore((s) => s.activeWorktreeByBot[bot?.id ?? ""] ?? null);
  const setBotWorktree = useWorkbenchStore((s) => s.setBotWorktree);

  const [tab, setTab] = useState("changes");
  const [gitSummary, setGitSummary] = useState<HostGitSummaryResponse | null>(null);
  const [fileQuery, setFileQuery] = useState("");
  const [visibleFilesLimit, setVisibleFilesLimit] = useState(INITIAL_FILES_LIMIT);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [forceLoadAllDiff, setForceLoadAllDiff] = useState(false);
  const [diffText, setDiffText] = useState("");
  const [diffTruncated, setDiffTruncated] = useState(false);
  const [loadingDiff, setLoadingDiff] = useState(false);
  const [commitMsg, setCommitMsg] = useState("");
  const [revertTarget, setRevertTarget] = useState<string | null>(null);

  const [worktrees, setWorktrees] = useState<HostWorktreeDto[]>([]);
  const [wtDiff, setWtDiff] = useState<HostWorktreeDiffResponse | null>(null);
  const [wtDeleteTarget, setWtDeleteTarget] = useState<HostWorktreeDto | null>(null);
  const [conflicts, setConflicts] = useState<string[]>([]);

  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{ tone: "ok" | "err"; text: string } | null>(null);

  const effectiveWtId = activeWtId ?? sessionSummary?.worktreeId ?? null;

  const refreshSummary = useCallback(async () => {
    if (!client || !bot || bot.isRemote) return;
    setBusy(true);
    try {
      const [sum, wtList] = await Promise.all([
        client.getGitSummary(bot.id, effectiveWtId),
        client.listWorktrees(bot.id),
      ]);
      setGitSummary(sum);
      setWorktrees(wtList);
    } catch (err) {
      setFeedback({ tone: "err", text: err instanceof Error ? err.message : "Failed" });
    } finally {
      setBusy(false);
    }
  }, [bot, client, effectiveWtId]);

  useEffect(() => {
    setVisibleFilesLimit(INITIAL_FILES_LIMIT);
    setForceLoadAllDiff(false);
    setSelectedFile(null);
    void refreshSummary();
  }, [refreshSummary]);

  const fileCount = gitSummary?.files.length ?? 0;
  const deferLargeAllDiff =
    selectedFile === null && fileCount > AUTO_ALL_DIFF_FILE_LIMIT && !forceLoadAllDiff;

  useEffect(() => {
    if (!client || !bot || bot.isRemote || !gitSummary?.isGitRepo) {
      setDiffText("");
      setDiffTruncated(false);
      return;
    }
    if (fileCount === 0 || deferLargeAllDiff) {
      setDiffText("");
      setDiffTruncated(false);
      return;
    }
    let cancelled = false;
    setLoadingDiff(true);
    void client
      .getGitDiff(bot.id, { path: selectedFile, worktreeId: effectiveWtId })
      .then((res) => {
        if (cancelled) return;
        setDiffText(res.diff);
        setDiffTruncated(res.truncated);
      })
      .catch((err) => {
        if (cancelled) return;
        setFeedback({ tone: "err", text: err instanceof Error ? err.message : "Failed" });
      })
      .finally(() => {
        if (!cancelled) setLoadingDiff(false);
      });
    return () => {
      cancelled = true;
    };
  }, [bot, client, deferLargeAllDiff, effectiveWtId, fileCount, gitSummary?.isGitRepo, selectedFile]);

  const filteredFiles = useMemo(() => {
    const files = gitSummary?.files ?? [];
    const q = fileQuery.trim().toLowerCase();
    if (!q) return files;
    return files.filter((f) => f.path.toLowerCase().includes(q));
  }, [fileQuery, gitSummary?.files]);

  const visibleFiles = useMemo(
    () => filteredFiles.slice(0, visibleFilesLimit),
    [filteredFiles, visibleFilesLimit],
  );

  if (!bot) return null;

  if (bot.isRemote) {
    return (
      <div className="p-4 text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
        {t("git.remoteUnsupported")}
      </div>
    );
  }

  const scopeOptions = [
    { value: "__main__", label: t("git.mainWorkspace") },
    ...worktrees.map((w) => ({ value: w.id, label: `${w.branch} (${w.label})` })),
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div
        className="flex shrink-0 items-center justify-between gap-2 px-3 py-2"
        style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
      >
        <Tabs value={tab} onValueChange={setTab} className="w-full">
          <div className="flex items-center justify-between gap-2">
            <TabsList>
              <TabsTrigger value="changes">{t("git.tabChanges")}</TabsTrigger>
              <TabsTrigger value="worktrees">{t("git.tabWorktrees")}</TabsTrigger>
            </TabsList>

            <IconButton
              size="sm"
              label={t("common.refresh")}
              disabled={busy}
              onClick={() => void refreshSummary()}
            >
              <IconRefresh size={14} stroke={1.7} />
            </IconButton>
          </div>

          {feedback && (
            <div
              className="mt-2 rounded-[8px] px-2.5 py-1.5 text-[12px]"
              style={{
                background: "var(--pi-wash)",
                color: feedback.tone === "err" ? "var(--pi-danger)" : "var(--pi-success)",
              }}
            >
              {feedback.text}
            </div>
          )}

          <TabsContent value="changes" className="mt-2 space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-1.5">
                {worktrees.length > 0 && (
                  <Select
                    size="sm"
                    value={effectiveWtId ?? "__main__"}
                    onValueChange={(v) => {
                      setSelectedFile(null);
                      setBotWorktree(bot.id, v === "__main__" ? null : v);
                    }}
                    options={scopeOptions}
                  />
                )}
                {gitSummary?.branch && (
                  <Badge tone="neutral">
                    <IconGitBranch size={11.5} />
                    <span>{gitSummary.branch}</span>
                  </Badge>
                )}
              </div>

              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  if (!client) return;
                  void client
                    .openInExternalEditor(bot.id, { worktreeId: effectiveWtId })
                    .then((r) => setFeedback({ tone: "ok", text: r.message }))
                    .catch((e) =>
                      setFeedback({ tone: "err", text: e instanceof Error ? e.message : "Failed" }),
                    );
                }}
              >
                <IconExternalLink size={13} />
                <span>{t("git.openEditor")}</span>
              </Button>
            </div>
          </TabsContent>
        </Tabs>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        {tab === "changes" && (
          <div className="space-y-3">
            {!gitSummary?.isGitRepo ? (
              <div className="py-10 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("git.notRepo")}
              </div>
            ) : (
              <>
                <div className="flex items-center justify-between gap-2">
                  <div className="text-[12px]" style={{ color: "var(--pi-ink-secondary)" }}>
                    {fileCount === 0 ? (
                      t("git.cleanTree")
                    ) : (
                      <span>
                        {fileCount} files ·{" "}
                        <span style={{ color: "var(--pi-success)" }}>
                          +{gitSummary.totalAdditions}
                        </span>{" "}
                        <span style={{ color: "var(--pi-danger)" }}>
                          -{gitSummary.totalDeletions}
                        </span>
                      </span>
                    )}
                  </div>
                  {fileCount > 0 && (
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          client &&
                          void client
                            .stageGitPath(bot.id, { all: true, worktreeId: effectiveWtId })
                            .then((next) => setGitSummary(next))
                            .catch((err) =>
                              setFeedback({
                                tone: "err",
                                text: err instanceof Error ? err.message : "Failed",
                              }),
                            )
                        }
                      >
                        {t("git.stageAll")}
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          client &&
                          void client
                            .unstageGitPath(bot.id, { all: true, worktreeId: effectiveWtId })
                            .then((next) => setGitSummary(next))
                            .catch((err) =>
                              setFeedback({
                                tone: "err",
                                text: err instanceof Error ? err.message : "Failed",
                              }),
                            )
                        }
                      >
                        {t("git.unstageAll")}
                      </Button>
                    </div>
                  )}
                </div>

                {fileCount > 20 && (
                  <Input
                    value={fileQuery}
                    onChange={(e) => {
                      setFileQuery(e.target.value);
                      setVisibleFilesLimit(INITIAL_FILES_LIMIT);
                    }}
                    placeholder={t("files.searchPlaceholder")}
                    className="h-8 text-[12px]"
                  />
                )}

                {fileCount > 0 && (
                  <Card className="divide-y divide-[var(--pi-hairline)] overflow-hidden">
                    <button
                      type="button"
                      onClick={() => setSelectedFile(null)}
                      className="flex w-full items-center justify-between px-3 py-1.5 text-left text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                      style={{
                        background: selectedFile === null ? "var(--pi-wash)" : "transparent",
                        color: "var(--pi-ink)",
                      }}
                    >
                      <span>{t("git.allChanges")}</span>
                    </button>

                    <div className="max-h-[280px] divide-y divide-[var(--pi-hairline)] overflow-y-auto">
                      {visibleFiles.map((f) => {
                        const active = selectedFile === f.path;
                        const badgeLabel = f.workTreeStatus || f.indexStatus || "M";
                        const handleToggleStage = () => {
                          if (!client) return;
                          const op = f.staged
                            ? client.unstageGitPath(bot.id, { path: f.path, worktreeId: effectiveWtId })
                            : client.stageGitPath(bot.id, { path: f.path, worktreeId: effectiveWtId });
                          void op
                            .then((next) => setGitSummary(next))
                            .catch((err) =>
                              setFeedback({
                                tone: "err",
                                text: err instanceof Error ? err.message : "Failed",
                              }),
                            );
                        };
                        return (
                          <div
                            key={f.path}
                            className="group flex items-center gap-2 px-3 py-1.5 text-[12px]"
                            style={{ background: active ? "var(--pi-wash)" : "transparent" }}
                          >
                            <button
                              type="button"
                              onClick={() => setSelectedFile(f.path)}
                              className="flex min-w-0 flex-1 items-center gap-2 text-left"
                            >
                              <span
                                className="w-4 shrink-0 text-center text-[11px] font-semibold"
                                style={{
                                  fontFamily: "var(--pi-font-mono)",
                                  color: f.staged ? "var(--pi-success)" : "var(--pi-warning)",
                                }}
                              >
                                {badgeLabel}
                              </span>
                              <span
                                className="min-w-0 flex-1 truncate"
                                style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink)" }}
                              >
                                {f.path}
                              </span>
                            </button>

                            <span className="shrink-0 text-[11px] tabular-nums">
                              <span style={{ color: "var(--pi-success)" }}>+{f.additions}</span>{" "}
                              <span style={{ color: "var(--pi-danger)" }}>-{f.deletions}</span>
                            </span>

                            <div className="flex shrink-0 items-center gap-0.5">
                              {f.staged ? (
                                <IconButton
                                  size="sm"
                                  label={t("git.unstage")}
                                  onClick={handleToggleStage}
                                >
                                  <IconMinus size={13} />
                                </IconButton>
                              ) : (
                                <IconButton
                                  size="sm"
                                  label={t("git.stage")}
                                  onClick={handleToggleStage}
                                >
                                  <IconPlus size={13} />
                                </IconButton>
                              )}
                              <IconButton
                                size="sm"
                                label={t("git.revert")}
                                onClick={() => setRevertTarget(f.path)}
                              >
                                <IconArrowBackUp size={13} />
                              </IconButton>
                            </div>
                          </div>
                        );
                      })}
                    </div>

                    {filteredFiles.length > visibleFilesLimit && (
                      <button
                        type="button"
                        onClick={() => setVisibleFilesLimit((n) => n + 200)}
                        className="w-full py-2 text-center text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                        style={{ color: "var(--pi-accent)" }}
                      >
                        {t("git.showMoreFiles", {
                          count: filteredFiles.length - visibleFilesLimit,
                        })}
                      </button>
                    )}
                  </Card>
                )}

                {fileCount > 0 && (
                  <form
                    className="flex items-center gap-2"
                    onSubmit={(e) => {
                      e.preventDefault();
                      if (!client || !commitMsg.trim()) return;
                      setBusy(true);
                      void client
                        .commitGit(bot.id, {
                          message: commitMsg.trim(),
                          stageAll: !gitSummary.files.some((f) => f.staged),
                          worktreeId: effectiveWtId,
                        })
                        .then((r) => {
                          setCommitMsg("");
                          setFeedback({ tone: "ok", text: r.message });
                          void refreshSummary();
                        })
                        .catch((err) =>
                          setFeedback({
                            tone: "err",
                            text: err instanceof Error ? err.message : "Commit failed",
                          }),
                        )
                        .finally(() => setBusy(false));
                    }}
                  >
                    <Input
                      value={commitMsg}
                      onChange={(e) => setCommitMsg(e.target.value)}
                      placeholder={t("git.commitPlaceholder")}
                      className="h-8 text-[12.5px]"
                    />
                    <Button
                      type="submit"
                      variant="primary"
                      size="sm"
                      disabled={busy || !commitMsg.trim()}
                    >
                      <IconGitCommit size={14} />
                      <span>{t("git.commitBtn")}</span>
                    </Button>
                  </form>
                )}

                {deferLargeAllDiff ? (
                  <Card className="space-y-2.5 p-4 text-center">
                    <div className="text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
                      {t("git.largeDiffHint", { count: fileCount })}
                    </div>
                    <div>
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => setForceLoadAllDiff(true)}
                      >
                        {t("git.loadAllDiff")}
                      </Button>
                    </div>
                  </Card>
                ) : loadingDiff ? (
                  <div className="py-8 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("common.loading")}
                  </div>
                ) : (
                  <DiffCodeView diffText={diffText} truncated={diffTruncated} />
                )}
              </>
            )}
          </div>
        )}

        {tab === "worktrees" && (
          <div className="space-y-3">
            {conflicts.length > 0 && (
              <Card className="space-y-1 p-3">
                <div className="text-[12.5px] font-semibold" style={{ color: "var(--pi-danger)" }}>
                  {t("wt.conflictsTitle")}
                </div>
                {conflicts.map((c) => (
                  <div key={c} className="text-[12px]" style={{ fontFamily: "var(--pi-font-mono)" }}>
                    {c}
                  </div>
                ))}
              </Card>
            )}

            {worktrees.length === 0 ? (
              <div className="py-10 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("wt.empty")}
              </div>
            ) : (
              <div className="space-y-2.5">
                {worktrees.map((wt) => (
                  <Card key={wt.id} className="space-y-2.5 p-3.5">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex min-w-0 items-center gap-2">
                        <IconGitBranch size={14} style={{ color: "var(--pi-accent)" }} />
                        <span
                          className="truncate text-[13px] font-semibold"
                          style={{ color: "var(--pi-ink)" }}
                        >
                          {wt.branch}
                        </span>
                      </div>
                      <Badge tone="neutral">{wt.baseBranch}</Badge>
                    </div>

                    <div
                      className="truncate text-[11px]"
                      style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink-tertiary)" }}
                    >
                      {wt.worktreePath}
                    </div>

                    <div className="flex flex-wrap gap-1.5">
                      {wt.sessionId && (
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => {
                            if (client && wt.sessionId) {
                              setBotWorktree(bot.id, wt.id);
                              void selectSession(client, wt.sessionId, locale);
                            }
                          }}
                        >
                          {t("wt.openSession")}
                        </Button>
                      )}

                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => {
                          if (!client) return;
                          void client
                            .getWorktreeDiff(wt.id)
                            .then((res) => setWtDiff(res))
                            .catch((e) =>
                              setFeedback({
                                tone: "err",
                                text: e instanceof Error ? e.message : "Failed",
                              }),
                            );
                        }}
                      >
                        {t("wt.inspectDiff")}
                      </Button>

                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => {
                          if (!client) return;
                          setConflicts([]);
                          void client
                            .applyWorktree(wt.id)
                            .then((res) => {
                              setFeedback({ tone: "ok", text: res.message });
                              void refreshSummary();
                            })
                            .catch((e) =>
                              setFeedback({
                                tone: "err",
                                text: e instanceof Error ? e.message : "Apply failed",
                              }),
                            );
                        }}
                      >
                        <IconGitMerge size={13.5} />
                        <span>{t("wt.applyBase")}</span>
                      </Button>

                      <Button variant="ghost" size="sm" onClick={() => setWtDeleteTarget(wt)}>
                        <IconTrash size={13.5} />
                        <span>{t("wt.deleteBtn")}</span>
                      </Button>
                    </div>
                  </Card>
                ))}
              </div>
            )}

            {wtDiff && (
              <div className="space-y-2 pt-2">
                <div className="flex items-center justify-between text-[12px]">
                  <span className="font-semibold" style={{ color: "var(--pi-ink)" }}>
                    {wtDiff.worktree.branch} ({wtDiff.aheadCommits} commits ahead)
                  </span>
                  <Button variant="ghost" size="sm" onClick={() => setWtDiff(null)}>
                    {t("common.close")}
                  </Button>
                </div>
                <DiffCodeView diffText={wtDiff.diff} truncated={wtDiff.truncated} />
              </div>
            )}
          </div>
        )}
      </div>

      <ConfirmDialog
        open={Boolean(revertTarget)}
        onOpenChange={(v) => {
          if (!v) setRevertTarget(null);
        }}
        title={t("git.revert")}
        description={t("git.revertConfirm", { path: revertTarget ?? "" })}
        confirmLabel={t("git.revert")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !revertTarget) return;
          void client
            .revertGitPath(bot.id, { path: revertTarget, worktreeId: effectiveWtId })
            .then(() => refreshSummary())
            .catch((e) =>
              setFeedback({ tone: "err", text: e instanceof Error ? e.message : "Revert failed" }),
            );
        }}
      />

      <ConfirmDialog
        open={Boolean(wtDeleteTarget)}
        onOpenChange={(v) => {
          if (!v) setWtDeleteTarget(null);
        }}
        title={t("wt.deleteBtn")}
        description={t("wt.deleteConfirm", { branch: wtDeleteTarget?.branch ?? "" })}
        confirmLabel={t("wt.deleteBtn")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !wtDeleteTarget) return;
          void client
            .deleteWorktree(wtDeleteTarget.id, true)
            .then(async () => {
              if (activeWtId === wtDeleteTarget.id) setBotWorktree(bot.id, null);
              await refreshSessionLists(client, bots);
              await refreshSummary();
            })
            .catch((e) =>
              setFeedback({ tone: "err", text: e instanceof Error ? e.message : "Delete failed" }),
            );
        }}
      />
    </div>
  );
}
