import { type MutableRefObject, useEffect, useMemo, useRef, useState } from "react";
import {
  IconArrowUp,
  IconChecklist,
  IconFile,
  IconFileText,
  IconPaperclip,
  IconPlayerStop,
  IconShieldCheck,
  IconX,
} from "@tabler/icons-react";
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
import { IconButton, Select, type SelectOption } from "../../ui";
import { ModelPicker } from "./ModelPicker";

const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
const INLINE_IMAGE_MIMES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);

function readFileAsComposerAttachment(file: File): Promise<ComposerAttachment | null> {
  if (file.size > MAX_ATTACHMENT_BYTES) return Promise.resolve(null);
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      const comma = result.indexOf(",");
      const dataBase64 = comma >= 0 ? result.slice(comma + 1) : "";
      const mimeType = file.type || "application/octet-stream";
      const kind = classifyAttachmentKind(mimeType, file.name);
      const isInlineImage = INLINE_IMAGE_MIMES.has(mimeType.toLowerCase());
      resolve({
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        name: file.name || "attachment",
        mimeType,
        size: file.size,
        kind,
        isInlineImage,
        dataBase64,
        previewUrl: kind === "image" ? result : undefined,
      });
    };
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(file);
  });
}

export function Composer({
  dark,
  sessionId,
  composerText,
  quotedText,
  queuedInputs,
  providers,
  slashCommands,
  currentModel,
  currentThinking,
  permissionMode,
  isRemote,
  remoteOffline,
  isStreaming,
  sendKey,
  sendWhileRunning,
  planFirst,
  onComposerChange,
  onClearQuote,
  onTogglePlanFirst,
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
  sessionId: string;
  composerText: string;
  quotedText: string | null;
  queuedInputs: HostQueuedInputDto[];
  providers: HostProviderDto[];
  slashCommands: HostSlashCommandDto[];
  currentModel: string;
  currentThinking: ThinkingLevel;
  permissionMode: PermissionMode;
  isRemote: boolean;
  remoteOffline: boolean;
  isStreaming: boolean;
  sendKey: "enter" | "mod_enter";
  sendWhileRunning: "steer" | "queue";
  planFirst: boolean;
  onComposerChange: (val: string) => void;
  onClearQuote: () => void;
  onTogglePlanFirst: () => void;
  onChangePermissionMode: (mode: PermissionMode) => Promise<void>;
  onModelChange: (model: string, thinking: ThinkingLevel) => void;
  onOpenProvidersSettings: () => void;
  onSend: (overrideText?: string, attachments?: ComposerAttachment[]) => void;
  onStop: () => void;
  onRemoveQueued: (kind: "steer" | "follow_up", text: string) => Promise<void>;
  onPromoteQueued: (text: string) => Promise<void>;
  onSnapToBottom: () => void;
  attachFilesRef?: MutableRefObject<((files: FileList | File[]) => void) | null>;
}) {
  const { t } = useI18n();
  const [attachments, setAttachments] = useState<ComposerAttachment[]>([]);
  const [warning, setWarning] = useState<string | null>(null);
  const [slashIndex, setSlashIndex] = useState(0);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const warningTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const attachmentsCountRef = useRef(0);
  attachmentsCountRef.current = attachments.length;

  useEffect(() => {
    setAttachments([]);
    setWarning(null);
  }, [sessionId]);

  useEffect(() => {
    return () => {
      if (warningTimerRef.current !== null) clearTimeout(warningTimerRef.current);
    };
  }, []);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
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
    for (const c of slashCommands) {
      const cmd = c.name.startsWith("/") ? c.name : `/${c.name}`;
      push(cmd, c.description || c.source, cmd === "/goal" || cmd.startsWith("/skill:"));
    }
    return list;
  }, [slashCommands, t]);

  const showSlashMenu = composerText.startsWith("/") && !composerText.includes(" ");
  const filteredCommands = useMemo(() => {
    if (!showSlashMenu) return [];
    const needle = composerText.toLowerCase();
    return allCommands.filter((c) => c.command.toLowerCase().includes(needle)).slice(0, 8);
  }, [allCommands, composerText, showSlashMenu]);

  useEffect(() => {
    setSlashIndex(0);
  }, [composerText]);

  const appendFiles = async (fileList: FileList | File[]) => {
    const files = Array.from(fileList);
    const remaining = Math.max(0, 8 - attachmentsCountRef.current);
    if (remaining === 0) return;
    const slice = files.slice(0, remaining);
    const results = await Promise.all(slice.map(readFileAsComposerAttachment));
    const loaded = results.filter((x): x is ComposerAttachment => x !== null);
    if (loaded.length < slice.length) {
      setWarning(t("chat.attachmentTooLarge"));
      if (warningTimerRef.current !== null) clearTimeout(warningTimerRef.current);
      warningTimerRef.current = setTimeout(() => setWarning(null), 3200);
    }
    if (loaded.length > 0) {
      setAttachments((prev) => [...prev, ...loaded].slice(0, 8));
    }
  };

  useEffect(() => {
    if (!attachFilesRef) return;
    attachFilesRef.current = (files) => {
      void appendFiles(files);
    };
    return () => {
      attachFilesRef.current = null;
    };
  });

  const triggerSend = (overrideText?: string) => {
    const files = overrideText ? [] : attachments;
    if (!(overrideText ?? composerText).trim() && files.length === 0) return;
    onSnapToBottom();
    onSend(overrideText, files);
    if (!overrideText) setAttachments([]);
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

  return (
    <div className="shrink-0 px-6 pt-1 pb-5">
      <div className="relative mx-auto w-full max-w-[740px]">
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
          <div className="mb-2 flex flex-wrap gap-1.5 px-2">
            {queuedInputs.map((q, i) => (
              <span
                key={`${q.kind}-${i}-${q.text.slice(0, 12)}`}
                className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[12px]"
                style={{
                  background: "var(--pi-surface)",
                  color: "var(--pi-ink-secondary)",
                  border: "0.5px solid var(--pi-surface-rim)",
                }}
              >
                <span
                  className="rounded px-1.5 py-0.2 text-[10.5px] font-medium"
                  style={{ background: "var(--pi-wash)", color: "var(--pi-ink)" }}
                >
                  {q.kind === "steer" ? t("chat.steerTag") : t("chat.followUpTag")}
                </span>
                <span className="max-w-[200px] truncate">{q.text}</span>
                {q.kind === "follow_up" && (
                  <button
                    type="button"
                    onClick={() => void onPromoteQueued(q.text)}
                    title={t("chat.steerQueue")}
                    aria-label={t("chat.steerQueue")}
                    className="rounded-full p-0.5 hover:bg-[var(--pi-hover)]"
                  >
                    <IconArrowUp size={12} stroke={1.8} />
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => void onRemoveQueued(q.kind, q.text)}
                  aria-label={t("chat.cancelQueue")}
                  className="rounded-full p-0.5 hover:bg-[var(--pi-hover)]"
                >
                  <IconX size={12} />
                </button>
              </span>
            ))}
          </div>
        )}

        <div className="pi-composer-shell px-3.5 py-2.5">
          {quotedText && (
            <div
              className="mb-2 flex items-center justify-between gap-2 rounded-[10px] px-3 py-1.5 text-[12.5px]"
              style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
            >
              <span className="truncate">{t("chat.quotedPrefix", { text: quotedText })}</span>
              <button type="button" onClick={onClearQuote} aria-label={t("chat.clearQuote")}>
                <IconX size={14} />
              </button>
            </div>
          )}

          {attachments.length > 0 && (
            <div className="mb-2 flex flex-wrap gap-2">
              {attachments.map((att) =>
                att.previewUrl ? (
                  <div
                    key={att.id}
                    className="relative h-14 w-14 overflow-hidden rounded-[10px]"
                    style={{ border: "0.5px solid var(--pi-hairline)" }}
                  >
                    <img src={att.previewUrl} alt={att.name} className="h-full w-full object-cover" />
                    <button
                      type="button"
                      onClick={() => setAttachments((prev) => prev.filter((x) => x.id !== att.id))}
                      aria-label={t("chat.removeAttachment")}
                      className="absolute top-1 right-1 flex h-4 w-4 items-center justify-center rounded-full bg-black/65 text-white"
                    >
                      <IconX size={10} stroke={2} />
                    </button>
                  </div>
                ) : (
                  <div
                    key={att.id}
                    className="inline-flex h-10 max-w-[220px] items-center gap-2 rounded-[10px] px-2.5 text-[12px]"
                    style={{
                      background: "var(--pi-wash)",
                      border: "0.5px solid var(--pi-hairline)",
                      color: "var(--pi-ink)",
                    }}
                  >
                    {att.kind === "text" ? (
                      <IconFileText size={14} stroke={1.65} className="shrink-0" />
                    ) : (
                      <IconFile size={14} stroke={1.65} className="shrink-0" />
                    )}
                    <div className="min-w-0">
                      <div className="truncate font-medium">{att.name}</div>
                      <div className="text-[10.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                        {formatAttachmentSize(att.size)}
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => setAttachments((prev) => prev.filter((x) => x.id !== att.id))}
                      aria-label={t("chat.removeAttachment")}
                      className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full hover:bg-[var(--pi-hover)]"
                    >
                      <IconX size={11} stroke={1.85} />
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

          <textarea
            ref={textareaRef}
            rows={1}
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
                    if (chosen.insertOnly) onComposerChange(`${chosen.command} `);
                    else triggerSend(chosen.command);
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
              isStreaming
                ? sendWhileRunning === "queue"
                  ? t("chat.placeholderQueue")
                  : t("chat.placeholderStreaming")
                : sendKey === "mod_enter"
                  ? t("chat.placeholderModEnter")
                  : t("chat.placeholder")
            }
            className="max-h-[160px] min-h-[36px] w-full resize-none bg-transparent px-1 py-1 text-[14.5px] leading-[1.5] outline-none placeholder:opacity-55 disabled:opacity-45"
            style={{ color: "var(--pi-ink)" }}
          />

          <div className="mt-1.5 flex items-center justify-between gap-2 pt-0.5">
            <div className="flex min-w-0 items-center gap-1">
              <IconButton
                label={t("chat.attach")}
                disabled={remoteOffline}
                onClick={() => fileInputRef.current?.click()}
              >
                <IconPaperclip size={16} stroke={1.65} />
              </IconButton>

              <ModelPicker
                providers={providers}
                currentModel={currentModel}
                currentThinking={currentThinking}
                dark={dark}
                onSelect={onModelChange}
                onOpenProvidersSettings={onOpenProvidersSettings}
              />

              {!isRemote && (
                <>
                  <button
                    type="button"
                    onClick={onTogglePlanFirst}
                    title={t("chat.planFirstHint")}
                    aria-pressed={planFirst}
                    className="inline-flex h-7 items-center gap-1 rounded-full px-2.5 text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      background: planFirst ? "var(--pi-accent-soft)" : "transparent",
                      color: planFirst ? "var(--pi-accent)" : "var(--pi-ink-tertiary)",
                    }}
                  >
                    <IconChecklist size={14} stroke={1.7} />
                    <span className="hidden sm:inline">{t("chat.planFirst")}</span>
                  </button>

                  <div className="hidden items-center gap-0.5 sm:inline-flex">
                    <IconShieldCheck
                      size={13.5}
                      stroke={1.7}
                      style={{ color: "var(--pi-ink-tertiary)" }}
                    />
                    <Select
                      size="sm"
                      ariaLabel={t("perm.label")}
                      value={permissionMode}
                      onValueChange={(v) => void onChangePermissionMode(v as PermissionMode)}
                      options={permissionOptions}
                    />
                  </div>
                </>
              )}
            </div>

            {isStreaming && !composerText.trim() && attachments.length === 0 ? (
              <button
                type="button"
                onClick={onStop}
                aria-label={t("chat.stop")}
                title={t("chat.stop")}
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-opacity hover:opacity-90"
                style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
              >
                <IconPlayerStop size={16} stroke={1.85} />
              </button>
            ) : (
              <button
                type="button"
                onClick={() => triggerSend()}
                disabled={!canSend}
                aria-label={t("chat.send")}
                title={t("chat.send")}
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-opacity disabled:opacity-35"
                style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
              >
                <IconArrowUp size={18} stroke={1.85} />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
