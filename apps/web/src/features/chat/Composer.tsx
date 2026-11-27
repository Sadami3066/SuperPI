import { memo, type MutableRefObject, useEffect, useMemo, useRef, useState } from "react";
import { classifyAttachmentKind, formatAttachmentSize } from "../../api/mappers";
import type {
  ComposerAttachment,
  HostProviderDto,
  HostQueuedInputDto,
  HostSlashCommandDto,
  PermissionMode,
  ThinkingLevel,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { pickWorkspaceDirectory } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useStreamStore } from "../../stores/streamStore";
import { DropdownItem, DropdownMenu, IconButton, Select, type SelectOption } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";
import { ModelPicker } from "./ModelPicker";

const MAX_ATTACHMENT_COUNT = 20;
const MAX_ATTACHMENT_BYTES = 100 * 1024 * 1024;
const MAX_INLINE_IMAGE_BYTES = 10 * 1024 * 1024;
const INLINE_IMAGE_MIMES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);

type AttachmentReadResult =
  | { attachment: ComposerAttachment }
  | { error: "too_large" | "read_failed" };

function readFileAsComposerAttachment(file: File): Promise<AttachmentReadResult> {
  if (file.size > MAX_ATTACHMENT_BYTES) return Promise.resolve({ error: "too_large" });
  const mimeType = file.type || "application/octet-stream";
  const kind = classifyAttachmentKind(mimeType, file.name);
  const isInlineImage =
    INLINE_IMAGE_MIMES.has(mimeType.toLowerCase()) && file.size <= MAX_INLINE_IMAGE_BYTES;
  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

  if (!isInlineImage) {
    return Promise.resolve({
      attachment: {
        id,
        name: file.name || "attachment",
        mimeType,
        size: file.size,
        kind,
        isInlineImage: false,
        file,
        previewUrl: kind === "image" ? URL.createObjectURL(file) : undefined,
      },
    });
  }

  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      const comma = result.indexOf(",");
      const dataBase64 = comma >= 0 ? result.slice(comma + 1) : "";
      resolve({
        attachment: {
          id,
          name: file.name || "attachment",
          mimeType,
          size: file.size,
          kind,
          isInlineImage: true,
          dataBase64,
          file,
          previewUrl: result,
        },
      });
    };
    reader.onerror = () => resolve({ error: "read_failed" });
    reader.readAsDataURL(file);
  });
}

function folderDisplayName(folderPath: string): string {
  const clean = folderPath.replace(/[\\/]+$/, "");
  const parts = clean.split(/[\\/]/);
  return parts[parts.length - 1] || folderPath;
}

export const Composer = memo(function Composer({
  dark,
  variant = "docked",
  sessionId,
  controlledValue,
  onControlledValueChange,
  queuedInputs = [],
  providers,
  slashCommands = [],
  currentModel,
  currentThinking,
  permissionMode = "workspace_auto",
  showPermissions = true,
  allowFolderAttach = true,
  isRemote = false,
  remoteOffline = false,
  isBusy = false,
  isStreamingOverride,
  placeholderOverride,
  sendKey,
  sendWhileRunning,
  onChangePermissionMode,
  onModelChange,
  onOpenProvidersSettings,
  onSend,
  onStop,
  onRemoveQueued,
  onPromoteQueued,
  onSnapToBottom,
  attachFilesRef,
}: {
  dark: boolean;
  variant?: "hero" | "docked";
  sessionId: string;
  controlledValue?: string;
  onControlledValueChange?: (value: string) => void;
  queuedInputs?: HostQueuedInputDto[];
  providers: HostProviderDto[];
  slashCommands?: HostSlashCommandDto[];
  currentModel: string;
  currentThinking: ThinkingLevel;
  permissionMode?: PermissionMode;
  showPermissions?: boolean;
  allowFolderAttach?: boolean;
  isRemote?: boolean;
  remoteOffline?: boolean;
  isBusy?: boolean;
  isStreamingOverride?: boolean;
  placeholderOverride?: string;
  sendKey: "enter" | "mod_enter";
  sendWhileRunning: "steer" | "queue";
  onChangePermissionMode?: (mode: PermissionMode) => Promise<void>;
  onModelChange: (model: string, thinking: ThinkingLevel) => Promise<void> | void;
  onOpenProvidersSettings: () => void;
  onSend: (overrideText?: string, attachments?: ComposerAttachment[]) => Promise<boolean | void> | boolean | void;
  onStop: () => void;
  onRemoveQueued?: (kind: "steer" | "follow_up", text: string) => Promise<void>;
  onPromoteQueued?: (text: string) => Promise<void>;
  onSnapToBottom?: () => void;
  attachFilesRef?: MutableRefObject<((files: FileList | File[]) => void) | null>;
}) {
  const { t } = useI18n();
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));
  const storeText = useSessionStore((s) => s.composerTextBySession[sessionId] ?? "");
  const quotedText = useSessionStore((s) => s.quotedTextBySession[sessionId] ?? null);
  const composerFocusTick = useSessionStore((s) => s.composerFocusTick);
  const setStoreComposerText = useSessionStore((s) => s.setComposerText);
  const setQuotedText = useSessionStore((s) => s.setQuotedText);
  const liveStreaming = useStreamStore((s) => Boolean(s.activeBySession[sessionId]?.streaming));

  const isControlled = controlledValue !== undefined && onControlledValueChange !== undefined;
  const composerText = isControlled ? controlledValue : storeText;
  const onComposerChange = (next: string) => {
    if (isControlled) {
      onControlledValueChange(next);
    } else {
      setStoreComposerText(sessionId, next);
    }
  };

  const isStreaming = isStreamingOverride !== undefined ? isStreamingOverride : liveStreaming || isBusy;
  const onClearQuote = () => setQuotedText(sessionId, null);

  const [attachments, setAttachments] = useState<ComposerAttachment[]>([]);
  const [warning, setWarning] = useState<string | null>(null);
  const [slashIndex, setSlashIndex] = useState(0);
  const [queueCollapsed, setQueueCollapsed] = useState(false);
  const prevQueueLenRef = useRef(0);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    const prev = prevQueueLenRef.current;
    const cur = queuedInputs.length;
    prevQueueLenRef.current = cur;
    if (cur === 0) {
      setQueueCollapsed(false);
    } else if (prev <= 3 && cur > 3) {
      setQueueCollapsed(true);
    }
  }, [queuedInputs.length]);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const folderInputRef = useRef<HTMLInputElement | null>(null);
  const warningTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const historyRef = useRef<Record<string, string[]>>({});
  const historyIdxRef = useRef<number>(-1);
  const historyDraftRef = useRef<string>("");
  const sendingRef = useRef(false);
  const attachmentsCountRef = useRef(0);
  attachmentsCountRef.current = attachments.length;
  const pendingObjectUrlsRef = useRef<Set<string>>(new Set());

  const revokePendingObjectUrl = (url?: string) => {
    if (!url || !pendingObjectUrlsRef.current.has(url)) return;
    pendingObjectUrlsRef.current.delete(url);
    URL.revokeObjectURL(url);
  };

  const revokeAllPendingObjectUrls = () => {
    for (const url of pendingObjectUrlsRef.current) {
      URL.revokeObjectURL(url);
    }
    pendingObjectUrlsRef.current.clear();
  };

  useEffect(() => {
    revokeAllPendingObjectUrls();
    setAttachments([]);
    setWarning(null);
    historyIdxRef.current = -1;
    historyDraftRef.current = "";
    return () => {
      revokeAllPendingObjectUrls();
    };
  }, [sessionId]);

  useEffect(() => {
    if (remoteOffline || composerFocusTick === 0) return;
    requestAnimationFrame(() => {
      textareaRef.current?.focus();
    });
  }, [composerFocusTick, remoteOffline]);

  useEffect(() => {
    return () => {
      if (warningTimerRef.current !== null) clearTimeout(warningTimerRef.current);
    };
  }, []);

  useEffect(() => {
    if (typeof CSS !== "undefined" && CSS.supports("field-sizing", "content")) {
      return undefined;
    }
    const el = textareaRef.current;
    if (!el) return undefined;
    const raf = requestAnimationFrame(() => {
      el.style.height = "auto";
      el.style.height = `${Math.min(el.scrollHeight, 168)}px`;
    });
    return () => cancelAnimationFrame(raf);
  }, [composerText]);

  const allCommands = useMemo(() => {
    const seen = new Set<string>();
    const list: Array<{ command: string; description: string; insertOnly?: boolean }> = [];
    const push = (command: string, description: string, insertOnly = false) => {
      const key = command.toLowerCase();
      if (seen.has(key)) return;
      seen.add(key);
      list.push({ command, description, insertOnly });
    };
    push("/compact", t("usage.compactNow"));
    if (!isControlled) {
      push("/goal", t("slash.goalDesc"), true);
    }
    for (const c of slashCommands) {
      const cmd = c.name.startsWith("/") ? c.name : `/${c.name}`;
      push(cmd, c.description || c.source, cmd === "/goal" || cmd.startsWith("/skill:"));
    }
    return list;
  }, [isControlled, slashCommands, t]);

  const showSlashMenu = composerText.startsWith("/") && !composerText.includes(" ");
  const filteredCommands = useMemo(() => {
    if (!showSlashMenu) return [];
    const needle = composerText.toLowerCase();
    return allCommands.filter((c) => c.command.toLowerCase().includes(needle)).slice(0, 8);
  }, [allCommands, composerText, showSlashMenu]);

  useEffect(() => {
    setSlashIndex(0);
  }, [composerText]);

  const showWarning = (message: string) => {
    setWarning(message);
    if (warningTimerRef.current !== null) clearTimeout(warningTimerRef.current);
    warningTimerRef.current = setTimeout(() => setWarning(null), 3200);
  };

  const removeAttachment = (id: string) => {
    setAttachments((prev) => {
      const target = prev.find((x) => x.id === id);
      if (target?.previewUrl) revokePendingObjectUrl(target.previewUrl);
      return prev.filter((x) => x.id !== id);
    });
  };

  const appendFiles = async (fileList: FileList | File[]) => {
    const files = Array.from(fileList);
    if (files.length === 0) return;
    const remaining = Math.max(0, MAX_ATTACHMENT_COUNT - attachmentsCountRef.current);
    if (remaining === 0) {
      showWarning(t("chat.attachmentTooMany", { max: MAX_ATTACHMENT_COUNT }));
      return;
    }
    const overflowCount = files.length > remaining;
    const slice = files.slice(0, remaining);
    const results = await Promise.all(slice.map(readFileAsComposerAttachment));
    const loaded: ComposerAttachment[] = [];
    let hasTooLarge = false;
    for (const res of results) {
      if ("attachment" in res) {
        if (res.attachment.previewUrl?.startsWith("blob:")) {
          pendingObjectUrlsRef.current.add(res.attachment.previewUrl);
        }
        loaded.push(res.attachment);
      } else if (res.error === "too_large") {
        hasTooLarge = true;
      }
    }
    if (hasTooLarge && overflowCount) {
      showWarning(
        `${t("chat.attachmentTooMany", { max: MAX_ATTACHMENT_COUNT })} · ${t("chat.attachmentTooLarge")}`,
      );
    } else if (hasTooLarge) {
      showWarning(t("chat.attachmentTooLarge"));
    } else if (overflowCount) {
      showWarning(t("chat.attachmentTooMany", { max: MAX_ATTACHMENT_COUNT }));
    }
    if (loaded.length > 0) {
      setAttachments((prev) => [...prev, ...loaded].slice(0, MAX_ATTACHMENT_COUNT));
    }
  };

  const handleAttachFolder = async () => {
    if (isElectron) {
      const picked = await pickWorkspaceDirectory();
      if (!picked) return;
      if (attachmentsCountRef.current >= MAX_ATTACHMENT_COUNT) {
        showWarning(t("chat.attachmentTooMany", { max: MAX_ATTACHMENT_COUNT }));
        return;
      }
      setAttachments((prev) => {
        if (prev.some((x) => x.kind === "folder" && x.folderPath === picked)) return prev;
        const folderAtt: ComposerAttachment = {
          id: `folder-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
          name: folderDisplayName(picked),
          mimeType: "inode/directory",
          size: 0,
          kind: "folder",
          isInlineImage: false,
          folderPath: picked,
        };
        return [...prev, folderAtt].slice(0, MAX_ATTACHMENT_COUNT);
      });
      return;
    }
    folderInputRef.current?.click();
  };

  const appendFilesRef = useRef(appendFiles);
  appendFilesRef.current = appendFiles;

  useEffect(() => {
    if (!attachFilesRef) return undefined;
    attachFilesRef.current = (files) => {
      void appendFilesRef.current(files);
    };
    return () => {
      attachFilesRef.current = null;
    };
  }, [attachFilesRef]);

  const triggerSend = async (overrideText?: string) => {
    if (sendingRef.current) return;
    const files = overrideText ? [] : attachments;
    const textToSend = (overrideText ?? composerText).trim();
    if (!textToSend && files.length === 0) return;
    if (!overrideText && textToSend) {
      const prevHist = historyRef.current[sessionId] ?? [];
      if (prevHist[prevHist.length - 1] !== textToSend) {
        historyRef.current[sessionId] = [...prevHist.slice(-49), textToSend];
      }
      historyIdxRef.current = -1;
      historyDraftRef.current = "";
    }
    onSnapToBottom?.();
    sendingRef.current = true;
    try {
      const ok = await onSend(overrideText, files);
      if (ok !== false && !overrideText) {
        for (const f of files) {
          if (f.previewUrl) pendingObjectUrlsRef.current.delete(f.previewUrl);
        }
        setAttachments([]);
      }
    } finally {
      sendingRef.current = false;
    }
  };

  const permissionOptions: SelectOption[] = useMemo(
    () => [
      { value: "read_only", label: t("perm.read_only") },
      { value: "ask_commands", label: t("perm.ask_commands") },
      { value: "workspace_auto", label: t("perm.workspace_auto") },
      { value: "full_auto", label: t("perm.full_auto") },
    ],
    [t],
  );

  const canSend = (composerText.trim().length > 0 || attachments.length > 0) && !remoteOffline;
  const isHero = variant === "hero";

  return (
    <div className={isHero ? "w-full" : "pi-chat-gutter shrink-0 pt-1 pb-4"}>
      <div className={`relative mx-auto w-full ${isHero ? "max-w-[680px]" : "max-w-[760px]"}`}>
        {showSlashMenu && filteredCommands.length > 0 && (
          <div className="pi-popover absolute right-0 bottom-full left-0 z-30 mb-2 max-h-[240px] overflow-y-auto p-1.5">
            {filteredCommands.map((cmd, idx) => (
              <button
                key={cmd.command}
                type="button"
                onClick={() => {
                  if (cmd.insertOnly) {
                    onComposerChange(`${cmd.command} `);
                    textareaRef.current?.focus();
                  } else {
                    onComposerChange("");
                    triggerSend(cmd.command);
                  }
                }}
                className="flex w-full items-center justify-between gap-3 rounded-[10px] px-3 py-1.5 text-left text-[13px] transition-colors hover:bg-[var(--pi-hover)]"
                style={{
                  background: idx === slashIndex ? "var(--pi-wash)" : "transparent",
                }}
              >
                <span
                  className="shrink-0 font-medium"
                  style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink)" }}
                >
                  {cmd.command}
                </span>
                <span className="truncate text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {cmd.description}
                </span>
              </button>
            ))}
          </div>
        )}

        {queuedInputs.length > 0 && (
          <div
            className="mb-2 overflow-hidden rounded-[12px]"
            style={{
              background: "var(--pi-chrome)",
              border: "1px solid var(--pi-canvas-border)",
            }}
          >
            {queuedInputs.length > 1 && (
              <button
                type="button"
                aria-expanded={!queueCollapsed}
                onClick={() => setQueueCollapsed((v) => !v)}
                className="flex h-7 w-full items-center justify-between gap-2 px-3 text-[11.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                style={{
                  color: "var(--pi-ink-secondary)",
                  borderBottom: queueCollapsed ? "none" : "1px solid var(--pi-canvas-border)",
                }}
              >
                <span>{t("chat.queueTrayTitle", { count: queuedInputs.length })}</span>
                <BrandIcon
                  name="navigation/chevron_down"
                  size={12}
                  className="transition-transform duration-150"
                  style={{
                    color: "var(--pi-ink-tertiary)",
                    transform: queueCollapsed ? "rotate(-90deg)" : "rotate(0deg)",
                  }}
                />
              </button>
            )}
            {!queueCollapsed && (
              <div className="max-h-[132px] divide-y divide-[var(--pi-hairline)] overflow-y-auto">
                {queuedInputs.map((q, i) => (
                  <div
                    key={`${q.kind}-${i}-${q.text.slice(0, 12)}`}
                    className="flex items-center gap-2 px-3 py-1.5 text-[12px]"
                  >
                    <span
                      className="shrink-0 rounded-[6px] px-1.5 py-0.5 text-[10.5px] font-medium"
                      style={{
                        background: q.kind === "steer" ? "var(--pi-accent-soft)" : "var(--pi-wash)",
                        color: q.kind === "steer" ? "var(--pi-accent)" : "var(--pi-ink-secondary)",
                      }}
                    >
                      {q.kind === "steer" ? t("chat.steerTag") : t("chat.followUpTag")}
                    </span>
                    <span
                      className="min-w-0 flex-1 truncate"
                      style={{ color: "var(--pi-ink)" }}
                      title={q.text}
                    >
                      {q.text}
                    </span>
                    <div className="flex shrink-0 items-center gap-0.5">
                      {q.kind === "follow_up" && onPromoteQueued && (
                        <button
                          type="button"
                          onClick={() => void onPromoteQueued(q.text)}
                          title={t("chat.steerQueue")}
                          aria-label={t("chat.steerQueue")}
                          className="flex h-5 w-5 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
                          style={{ color: "var(--pi-ink-secondary)" }}
                        >
                          <BrandIcon name="navigation/arrow_up" size={12} />
                        </button>
                      )}
                      {onRemoveQueued && (
                        <button
                          type="button"
                          onClick={() => void onRemoveQueued(q.kind, q.text)}
                          title={t("chat.cancelQueue")}
                          aria-label={t("chat.cancelQueue")}
                          className="flex h-5 w-5 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
                          style={{ color: "var(--pi-ink-tertiary)" }}
                        >
                          <BrandIcon name="navigation/close" size={12} />
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        <div className="pi-composer-shell px-3.5 pt-2.5 pb-2" data-streaming={isStreaming ? "true" : "false"}>
          {!isControlled && quotedText && (
            <div
              className="mb-2 flex items-center justify-between gap-2 rounded-[9px] px-3 py-1.5 text-[12px]"
              style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
            >
              <span className="truncate">{t("chat.quotedPrefix", { text: quotedText })}</span>
              <button type="button" onClick={onClearQuote} aria-label={t("chat.clearQuote")}>
                <BrandIcon name="navigation/close" size={13.5} />
              </button>
            </div>
          )}

          {attachments.length > 0 && (
            <div className="mb-2 flex flex-wrap gap-2">
              {attachments.map((att) =>
                att.previewUrl ? (
                  <div
                    key={att.id}
                    className="relative h-13 w-13 overflow-hidden rounded-[9px]"
                    style={{ border: "0.5px solid var(--pi-hairline)" }}
                  >
                    <img src={att.previewUrl} alt={att.name} className="h-full w-full object-cover" />
                    <button
                      type="button"
                      onClick={() => removeAttachment(att.id)}
                      aria-label={t("chat.removeAttachment")}
                      className="absolute top-1 right-1 flex h-4 w-4 items-center justify-center rounded-full bg-black/65 text-white"
                    >
                      <BrandIcon name="navigation/close" size={10} />
                    </button>
                  </div>
                ) : (
                  <div
                    key={att.id}
                    title={att.folderPath || att.name}
                    className="inline-flex h-9 max-w-[240px] items-center gap-2 rounded-[9px] px-2.5 text-[12px]"
                    style={{
                      background: "var(--pi-wash)",
                      border: "0.5px solid var(--pi-hairline)",
                      color: "var(--pi-ink)",
                    }}
                  >
                    {att.kind === "folder" ? (
                      <BrandIcon name="file/folder_closed" size={14} className="shrink-0" style={{ color: "var(--pi-accent)" }} />
                    ) : att.kind === "text" ? (
                      <BrandIcon name="file/plain" size={14} className="shrink-0" />
                    ) : (
                      <BrandIcon name="file/attachment" size={14} className="shrink-0" />
                    )}
                    <div className="min-w-0">
                      <div className="truncate font-medium">{att.name}</div>
                      {att.kind === "folder" ? (
                        <div className="truncate text-[10.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                          {isRemote ? t("chat.folderRemoteHint") : att.folderPath}
                        </div>
                      ) : (
                        <div className="text-[10.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                          {formatAttachmentSize(att.size)}
                        </div>
                      )}
                    </div>
                    <button
                      type="button"
                      onClick={() => removeAttachment(att.id)}
                      aria-label={t("chat.removeAttachment")}
                      className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full hover:bg-[var(--pi-hover)]"
                    >
                      <BrandIcon name="navigation/close" size={11} />
                    </button>
                  </div>
                ),
              )}
            </div>
          )}

          {warning && (
            <div className="mb-2 text-[12px]" style={{ color: "var(--pi-danger)" }}>
              {warning}
            </div>
          )}

          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={(e) => {
              if (e.target.files?.length) void appendFiles(e.target.files);
              e.target.value = "";
            }}
          />
          <input
            ref={folderInputRef}
            type="file"
            multiple
            {...({ webkitdirectory: "" } as Record<string, string>)}
            className="hidden"
            onChange={(e) => {
              if (e.target.files?.length) void appendFiles(e.target.files);
              e.target.value = "";
            }}
          />

          <textarea
            ref={textareaRef}
            rows={isHero ? 2 : 1}
            disabled={remoteOffline}
            value={composerText}
            onChange={(e) => onComposerChange(e.target.value)}
            onPaste={(e) => {
              const files = Array.from(e.clipboardData?.files ?? []);
              if (files.length > 0) {
                e.preventDefault();
                void appendFiles(files);
              }
            }}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (e.key === "Escape") {
                if (showSlashMenu) {
                  e.preventDefault();
                  onComposerChange("");
                  return;
                }
                if (!isControlled && quotedText) {
                  e.preventDefault();
                  onClearQuote();
                  return;
                }
                textareaRef.current?.blur();
                return;
              }
              if (showSlashMenu && filteredCommands.length > 0) {
                if (e.key === "ArrowDown") {
                  e.preventDefault();
                  setSlashIndex((i) => (i + 1) % filteredCommands.length);
                  return;
                }
                if (e.key === "ArrowUp") {
                  e.preventDefault();
                  setSlashIndex((i) => (i - 1 + filteredCommands.length) % filteredCommands.length);
                  return;
                }
                if (e.key === "Tab" || (e.key === "Enter" && !e.shiftKey)) {
                  e.preventDefault();
                  const chosen = filteredCommands[slashIndex] ?? filteredCommands[0];
                  if (chosen) {
                    if (chosen.insertOnly) {
                      onComposerChange(`${chosen.command} `);
                    } else {
                      onComposerChange("");
                      triggerSend(chosen.command);
                    }
                  }
                  return;
                }
              }
              const hist = historyRef.current[sessionId] ?? [];
              const el = textareaRef.current;
              if (
                hist.length > 0 &&
                el &&
                !e.shiftKey &&
                !e.metaKey &&
                !e.ctrlKey &&
                !e.altKey
              ) {
                if (e.key === "ArrowUp" && el.selectionStart === 0 && el.selectionEnd === 0) {
                  e.preventDefault();
                  if (historyIdxRef.current < 0) {
                    historyDraftRef.current = composerText;
                    historyIdxRef.current = hist.length - 1;
                  } else if (historyIdxRef.current > 0) {
                    historyIdxRef.current -= 1;
                  }
                  onComposerChange(hist[historyIdxRef.current] ?? "");
                  return;
                }
                if (
                  e.key === "ArrowDown" &&
                  historyIdxRef.current >= 0 &&
                  el.selectionStart === composerText.length
                ) {
                  e.preventDefault();
                  if (historyIdxRef.current < hist.length - 1) {
                    historyIdxRef.current += 1;
                    onComposerChange(hist[historyIdxRef.current] ?? "");
                  } else {
                    historyIdxRef.current = -1;
                    onComposerChange(historyDraftRef.current);
                  }
                  return;
                }
              }
              if (sendKey === "mod_enter") {
                if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
                  e.preventDefault();
                  triggerSend();
                }
              } else if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                triggerSend();
              }
            }}
            placeholder={
              placeholderOverride ??
              (isStreaming
                ? sendWhileRunning === "queue"
                  ? t("chat.placeholderQueue")
                  : t("chat.placeholderStreaming")
                : sendKey === "mod_enter"
                  ? t("chat.placeholderModEnter")
                  : t("chat.placeholder"))
            }
            className={`pi-composer-textarea pi-field-auto max-h-[168px] w-full resize-none bg-transparent px-1 py-1 text-[14px] leading-[1.55] placeholder:opacity-55 disabled:opacity-45 ${
              isHero ? "min-h-[58px]" : "min-h-[36px]"
            }`}
            style={{
              color: composerText.trimStart().startsWith("/") ? "var(--pi-accent)" : "var(--pi-ink)",
            }}
          />

          <div className="mt-1.5 flex items-center justify-between gap-1.5 pt-0.5">
            <div className="flex shrink-0 items-center gap-1">
              <DropdownMenu
                align="start"
                trigger={
                  <IconButton
                    size="sm"
                    label={t("chat.attach")}
                    disabled={remoteOffline}
                  >
                    <BrandIcon name="action/add" size={16} />
                  </IconButton>
                }
              >
                <DropdownItem
                  icon={<BrandIcon name="file/plain" size={14} />}
                  label={t("chat.attachFile")}
                  onSelect={() => fileInputRef.current?.click()}
                />
                {allowFolderAttach && (
                  <DropdownItem
                    icon={<BrandIcon name="file/folder_closed" size={14} />}
                    label={t("chat.attachFolder")}
                    onSelect={() => void handleAttachFolder()}
                  />
                )}
              </DropdownMenu>
            </div>

            <div className="flex min-w-0 items-center gap-1.5 whitespace-nowrap">
              <ModelPicker
                providers={providers}
                currentModel={currentModel}
                currentThinking={currentThinking}
                dark={dark}
                onSelect={(model, thinking) => {
                  void Promise.resolve(onModelChange(model, thinking)).catch((err) => {
                    showWarning(err instanceof Error ? err.message : t("chat.switchFailed"));
                  });
                }}
                onOpenProvidersSettings={onOpenProvidersSettings}
              />

              {showPermissions && !isRemote && onChangePermissionMode && (
                <>
                  <span
                    aria-hidden="true"
                    className="pi-composer-perm mx-0.5 h-3.5 w-[1px] shrink-0"
                    style={{ background: "var(--pi-canvas-border)" }}
                  />
                  <div className="pi-composer-perm shrink-0 items-center gap-0.5">
                    <BrandIcon
                      name="system/permissions"
                      size={13.5}
                      style={{ color: "var(--pi-ink-tertiary)" }}
                    />
                    <Select
                      size="sm"
                      ariaLabel={t("perm.label")}
                      value={permissionMode}
                      onValueChange={(v) => {
                        void onChangePermissionMode(v as PermissionMode).catch((err) => {
                          showWarning(err instanceof Error ? err.message : t("chat.switchFailed"));
                        });
                      }}
                      options={permissionOptions}
                    />
                  </div>
                </>
              )}

              {isStreaming && !composerText.trim() && attachments.length === 0 ? (
                <button
                  type="button"
                  onClick={onStop}
                  aria-label={t("chat.stop")}
                  title={t("chat.stop")}
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-[opacity,transform] duration-150 hover:opacity-90 motion-safe:active:scale-[0.94]"
                  style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
                >
                  <BrandIcon name="action/stop" size={15} />
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => triggerSend()}
                  disabled={!canSend}
                  aria-label={t("chat.send")}
                  title={t("chat.send")}
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-[opacity,transform] duration-150 hover:opacity-90 motion-safe:active:scale-[0.94] disabled:opacity-35"
                  style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
                >
                  <BrandIcon name="navigation/arrow_up" size={16} />
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
});
