import { useCallback, useEffect, useMemo, useState } from "react";
import {
  IconArrowBackUp,
  IconCheck,
  IconExternalLink,
  IconGitBranch,
  IconGitCommit,
  IconGitFork,
  IconGitMerge,
  IconMinus,
  IconPlus,
  IconRefresh,
  IconTrash,
} from "@tabler/icons-react";
import { formatClockTime } from "../api/mappers";
import type {
  HostCheckpointDto,
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
  ContextMenu,
  ContextMenuItem,
  ContextMenuSeparator,
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

function DiffCodeView({ diffText, truncated }: { diffText: string; truncated?: boolean }) {
  const { t } = useI18n();
  const parsed = useMemo(() => parseUnifiedDiff(diffText), [diffText]);

  if (!diffText.trim()) {
    return (
      <div className="py-8 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
        {t("git.diffEmpty")}
      </div>
    );
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
      {parsed.map((file, idx) => (
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
              {file.hunks.map((hunk, hIdx) => (
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
                  {hunk.lines.map((line, lIdx) => {
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
                        <span style={{ color: line.kind === "meta" ? "var(--pi-ink-tertiary)" : "var(--pi-ink)" }}>
                          {line.text}
                        </span>
                      </div>
                    );
                  })}
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
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
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [diffText, setDiffText] = useState("");
  const [diffTruncated, setDiffTruncated] = useState(false);
  const [commitMsg, setCommitMsg] = useState("");
  const [revertTarget, setRevertTarget] = useState<string | null>(null);

  const [worktrees, setWorktrees] = useState<HostWorktreeDto[]>([]);
  const [wtLabel, setWtLabel] = useState("");
  const [wtDiff, setWtDiff] = useState<HostWorktreeDiffResponse | null>(null);
  const [wtDeleteTarget, setWtDeleteTarget] = useState<HostWorktreeDto | null>(null);
  const [conflicts, setConflicts] = useState<string[]>([]);

  const [checkpoints, setCheckpoints] = useState<HostCheckpointDto[]>([]);
  const [restoreBothTarget, setRestoreBothTarget] = useState<HostCheckpointDto | null>(null);

  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState<{ tone: "ok" | "err"; text: string } | null>(null);

  const effectiveWtId = activeWtId ?? sessionSummary?.worktreeId ?? null;

  const refreshChanges = useCallback(async () => {
    if (!client || !bot || bot.isRemote) return;
    setBusy(true);
    try {
      const [sum, diffRes, wtList] = await Promise.all([
        client.getGitSummary(bot.id, effectiveWtId),
        client.getGitDiff(bot.id, { path: selectedFile, worktreeId: effectiveWtId }),
        client.listWorktrees(bot.id),
      ]);
      setGitSummary(sum);
      setDiffText(diffRes.diff);
      setDiffTruncated(diffRes.truncated);
      setWorktrees(wtList);
    } catch (err) {
      setFeedback({ tone: "err", text: err instanceof Error ? err.message : "Failed" });
    } finally {
      setBusy(false);
    }
  }, [bot, client, effectiveWtId, selectedFile]);

  const refreshCheckpoints = useCallback(async () => {
    if (!client || !activeSessionId || bot?.isRemote) return;
    try {
      const list = await client.listCheckpoints(activeSessionId);
      setCheckpoints(list);
    } catch {
      setCheckpoints([]);
    }
  }, [activeSessionId, bot?.isRemote, client]);

  useEffect(() => {
    void refreshChanges();
  }, [refreshChanges]);

  useEffect(() => {
    void refreshCheckpoints();
  }, [refreshCheckpoints]);

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

  const handleCreateWorktree = async () => {
    if (!client) return;
    setBusy(true);
    setFeedback(null);
    try {
      const res = await client.createWorktree(bot.id, {
        label: wtLabel.trim(),
        createSession: true,
      });
      setWtLabel("");
      setBotWorktree(bot.id, res.worktree.id);
      await refreshSessionLists(client, bots);
      if (res.sessionId) {
        await selectSession(client, res.sessionId, locale);
      }
      await refreshChanges();
    } catch (err) {
      setFeedback({ tone: "err", text: err instanceof Error ? err.message : "Failed" });
    } finally {
      setBusy(false);
    }
  };

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
              <TabsTrigger value="checkpoints">{t("git.tabCheckpoints")}</TabsTrigger>
            </TabsList>

            <IconButton
              size="sm"
              label={t("common.refresh")}
              disabled={busy}
              onClick={() => {
                void refreshChanges();
                void refreshCheckpoints();
              }}
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
                    {gitSummary.files.length === 0 ? (
                      t("git.cleanTree")
                    ) : (
                      <span>
                        {gitSummary.files.length} files ·{" "}
                        <span style={{ color: "var(--pi-success)" }}>
                          +{gitSummary.totalAdditions}
                        </span>{" "}
                        <span style={{ color: "var(--pi-danger)" }}>
                          -{gitSummary.totalDeletions}
                        </span>
                      </span>
                    )}
                  </div>
                  {gitSummary.files.length > 0 && (
                    <div className="flex items-center gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          client &&
                          void client
                            .stageGitPath(bot.id, { all: true, worktreeId: effectiveWtId })
                            .then((next) => {
                              setGitSummary(next);
                              void refreshChanges();
                            })
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
                            .then((next) => {
                              setGitSummary(next);
                              void refreshChanges();
                            })
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

                {gitSummary.files.length > 0 && (
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

                    {gitSummary.files.map((f) => {
                      const active = selectedFile === f.path;
                      const badgeLabel = f.workTreeStatus || f.indexStatus || "M";
                      const handleToggleStage = () => {
                        if (!client) return;
                        const op = f.staged
                          ? client.unstageGitPath(bot.id, { path: f.path, worktreeId: effectiveWtId })
                          : client.stageGitPath(bot.id, { path: f.path, worktreeId: effectiveWtId });
                        void op
                          .then((next) => {
                            setGitSummary(next);
                            void refreshChanges();
                          })
                          .catch((err) =>
                            setFeedback({
                              tone: "err",
                              text: err instanceof Error ? err.message : "Failed",
                            }),
                          );
                      };
                      return (
                        <ContextMenu
                          key={f.path}
                          trigger={
                            <div
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
                          }
                        >
                          <ContextMenuItem
                            label={t("preview.modePreview")}
                            onSelect={() =>
                              useWorkbenchStore.getState().openFileInPreview({
                                source: "workspace",
                                botId: bot.id,
                                path: f.path,
                                name: f.path.split("/").pop() || f.path,
                              })
                            }
                          />
                          <ContextMenuItem
                            label={f.staged ? t("git.unstage") : t("git.stage")}
                            onSelect={handleToggleStage}
                          />
                          <ContextMenuItem
                            label={t("files.copyPath")}
                            onSelect={() => void navigator.clipboard?.writeText(f.path)}
                          />
                          <ContextMenuSeparator />
                          <ContextMenuItem
                            danger
                            label={t("git.revert")}
                            onSelect={() => setRevertTarget(f.path)}
                          />
                        </ContextMenu>
                      );
                    })}
                  </Card>
                )}

                {gitSummary.files.length > 0 && (
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
                          void refreshChanges();
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

                <DiffCodeView diffText={diffText} truncated={diffTruncated} />
              </>
            )}
          </div>
        )}

        {tab === "worktrees" && (
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <Input
                value={wtLabel}
                onChange={(e) => setWtLabel(e.target.value)}
                placeholder={t("wt.createPlaceholder")}
                className="h-8 text-[12.5px]"
              />
              <Button variant="primary" size="sm" disabled={busy} onClick={() => void handleCreateWorktree()}>
                <IconPlus size={14} />
                <span>{t("wt.createBtn")}</span>
              </Button>
            </div>

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
                              void refreshChanges();
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

        {tab === "checkpoints" && (
          <div className="space-y-3">
            {activeSessionId && (
              <Button
                variant="secondary"
                size="sm"
                className="w-full"
                onClick={() => {
                  if (!client) return;
                  void client
                    .forkSession(activeSessionId)
                    .then(async (res) => {
                      await refreshSessionLists(client, bots);
                      await selectSession(client, res.sessionId, locale);
                    })
                    .catch((e) =>
                      setFeedback({
                        tone: "err",
                        text: e instanceof Error ? e.message : "Fork failed",
                      }),
                    );
                }}
              >
                <IconGitFork size={14} />
                <span>{t("cp.forkBtn")}</span>
              </Button>
            )}

            {checkpoints.length === 0 ? (
              <div className="py-10 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("cp.empty")}
              </div>
            ) : (
              <div className="space-y-2">
                {checkpoints.map((cp) => (
                  <Card key={cp.id} className="space-y-2 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-[13px] font-medium" style={{ color: "var(--pi-ink)" }}>
                        {cp.label}
                      </span>
                      <span className="shrink-0 text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                        {formatClockTime(cp.createdAt, locale)}
                      </span>
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => {
                          if (!client || !activeSessionId) return;
                          void client
                            .restoreCheckpoint(activeSessionId, cp.id, "files_only")
                            .then((res) => {
                              setFeedback({ tone: "ok", text: res.message });
                              void refreshChanges();
                            })
                            .catch((e) =>
                              setFeedback({
                                tone: "err",
                                text: e instanceof Error ? e.message : "Restore failed",
                              }),
                            );
                        }}
                      >
                        <IconCheck size={13} />
                        <span>{t("cp.restoreFiles")}</span>
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setRestoreBothTarget(cp)}
                      >
                        {t("cp.restoreBoth")}
                      </Button>
                    </div>
                  </Card>
                ))}
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
            .then(() => refreshChanges())
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
              await refreshChanges();
            })
            .catch((e) =>
              setFeedback({ tone: "err", text: e instanceof Error ? e.message : "Delete failed" }),
            );
        }}
      />

      <ConfirmDialog
        open={Boolean(restoreBothTarget)}
        onOpenChange={(v) => {
          if (!v) setRestoreBothTarget(null);
        }}
        title={t("cp.restoreBoth")}
        description={t("cp.restoreBothConfirm", { label: restoreBothTarget?.label ?? "" })}
        confirmLabel={t("common.confirm")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !activeSessionId || !restoreBothTarget) return;
          void client
            .restoreCheckpoint(activeSessionId, restoreBothTarget.id, "files_and_chat")
            .then((res) => {
              setFeedback({ tone: "ok", text: res.message });
              void useSessionStore.getState().loadSession(client, activeSessionId, locale);
            })
            .catch((e) =>
              setFeedback({
                tone: "err",
                text: e instanceof Error ? e.message : "Restore failed",
              }),
            );
        }}
      />
    </div>
  );
}
