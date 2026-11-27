import type {
  HostAgentDocDto,
  HostBotDto,
  HostBotModelsResponse,
  HostBotPolicyDto,
  HostCheckpointDto,
  HostCompactResponse,
  HostCreateWorktreeResponse,
  HostCustomModelDto,
  HostCustomProviderDto,
  HostDoctorReportDto,
  HostGitDiffResponse,
  HostGitSummaryResponse,
  HostGoalDto,
  HostHealthDto,
  HostInboxItemDto,
  HostInstalledSkillDto,
  HostJobOutputDto,
  HostJobSnapshotDto,
  HostMarketCatalogResponse,
  HostMarketInstallResponse,
  HostMcpServerDto,
  HostMemoryBlockDto,
  HostMemoryEntityDetailDto,
  HostMemoryEntityDto,
  HostMemoryEventDto,
  HostMemoryFullSnapshotResponse,
  HostMemoryPreferencesDto,
  HostMemoryRelationDto,
  HostMemorySyncStatusDto,
  HostOAuthStatusDto,
  HostOpenEditorResponse,
  HostPendingQuestionDto,
  HostPendingToolApprovalDto,
  HostProviderDto,
  HostQueuedInputDto,
  HostRelayDeviceDto,
  HostRelayStatusDto,
  HostRestoreCheckpointResponse,
  HostSaveTimerRequest,
  HostSessionDetailDto,
  HostSessionSummaryDto,
  HostSettingsDto,
  HostSkillContentResponse,
  HostSlashCommandDto,
  HostSseEvent,
  HostStagedAttachmentDto,
  HostStatusDto,
  HostSuperSettingsDto,
  HostSuperStateResponse,
  HostSuperTeamBotDto,
  HostTerminalInfoDto,
  HostTerminalStreamFrame,
  HostTimerDto,
  HostUpsertMcpRequest,
  HostWebSearchProviderDto,
  HostWorkerDetailDto,
  HostWorkspaceFileListResponse,
  HostWorkspaceFileReadResponse,
  HostWorkspaceSearchResponse,
  HostWorktreeDiffResponse,
  HostWorktreeDto,
  HostWorktreeOpResponse,
  PermissionMode,
  QuestionAnswerInput,
  ToolApprovalDecision,
} from "./types";

async function readErrorMessage(resp: Response): Promise<string> {
  const fallback = `HTTP ${resp.status}`;
  const raw = await resp.text().catch(() => "");
  if (!raw.trim()) return fallback;
  try {
    const body = JSON.parse(raw) as { error?: string; message?: string };
    return body.error || body.message || raw.trim() || fallback;
  } catch {
    return raw.trim();
  }
}

export class HostApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export interface HostClientConfig {
  baseUrl: string;
  token: string;
}

export class HostClient {
  private readonly baseUrl: string;
  private readonly token: string;

  constructor(config: HostClientConfig) {
    this.baseUrl = config.baseUrl.replace(/\/+$/, "");
    this.token = config.token;
  }

  private async request<T>(path: string, init: RequestInit = {}, timeoutMs = 15_000): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${this.token}`);
    if (init.body && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }

    const controller = new AbortController();
    let timedOut = false;
    const timer =
      timeoutMs > 0
        ? setTimeout(() => {
            timedOut = true;
            controller.abort();
          }, timeoutMs)
        : null;

    const extSignal = init.signal;
    const onExtAbort = () => controller.abort();
    if (extSignal) {
      if (extSignal.aborted) {
        controller.abort();
      } else {
        extSignal.addEventListener("abort", onExtAbort, { once: true });
      }
    }

    try {
      const resp = await fetch(`${this.baseUrl}${path}`, {
        ...init,
        headers,
        signal: controller.signal,
      });
      if (!resp.ok) {
        throw new HostApiError(resp.status, await readErrorMessage(resp));
      }
      const contentType = resp.headers.get("content-type") ?? "";
      if (contentType.includes("application/json")) {
        return (await resp.json()) as T;
      }
      return (await resp.text()) as unknown as T;
    } catch (err) {
      if (timedOut) {
        throw new HostApiError(408, "Request timed out");
      }
      throw err;
    } finally {
      if (timer !== null) clearTimeout(timer);
      if (extSignal) extSignal.removeEventListener("abort", onExtAbort);
    }
  }

  getHealth(): Promise<HostHealthDto> {
    return this.request("/api/v1/health");
  }

  getStatus(): Promise<HostStatusDto> {
    return this.request("/api/v1/status");
  }

  listBots(): Promise<HostBotDto[]> {
    return this.request("/api/v1/bots");
  }

  createBot(body: {
    dir?: string;
    name?: string;
    intro?: string;
    targetDeviceId?: string;
  }): Promise<HostBotDto> {
    return this.request("/api/v1/bots", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  updateBot(
    botId: string,
    body: {
      name?: string;
      intro?: string;
      clearIntro?: boolean;
      avatar?: string;
      clearAvatar?: boolean;
      defaultModel?: string;
      defaultThinkingLevel?: string;
      updateDefaultModel?: boolean;
    },
  ): Promise<HostBotDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  }

  deleteBot(botId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}`, {
      method: "DELETE",
    });
  }

  getBotAgentsDoc(botId: string): Promise<HostAgentDocDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/agents-doc`);
  }

  saveBotAgentsDoc(botId: string, content: string): Promise<HostAgentDocDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/agents-doc`, {
      method: "PUT",
      body: JSON.stringify({ content }),
    });
  }

  deleteBotAgentsDoc(botId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/agents-doc`, {
      method: "DELETE",
    });
  }

  getBotPolicy(botId: string): Promise<HostBotPolicyDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/policy`);
  }

  updateBotPolicy(
    botId: string,
    body: {
      allCapabilities?: boolean;
      skills?: string[];
      mcpServers?: string[];
      memoryScope?: string;
      memoryDispatch?: boolean;
      superEnabled?: boolean;
    },
  ): Promise<HostBotPolicyDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/policy`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  setBotPermission(botId: string, mode: PermissionMode): Promise<HostHealthDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/permission`, {
      method: "PUT",
      body: JSON.stringify({ mode }),
    });
  }

  getBotCommands(botId: string): Promise<HostSlashCommandDto[]> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/commands`);
  }

  getBotModels(botId: string, sessionId?: string): Promise<HostBotModelsResponse> {
    const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : "";
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/models${query}`);
  }

  listSessions(botId: string): Promise<HostSessionSummaryDto[]> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/sessions`);
  }

  listArchivedSessions(): Promise<HostSessionSummaryDto[]> {
    return this.request("/api/v1/sessions/archived");
  }

  createSession(botId: string): Promise<{ sessionId: string; cwd: string }> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/sessions`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  getSession(
    sessionId: string,
    opts?: { limit?: number; before?: number },
  ): Promise<HostSessionDetailDto> {
    const params = new URLSearchParams();
    if (opts?.limit !== undefined) params.set("limit", String(opts.limit));
    if (opts?.before !== undefined) params.set("before", String(opts.before));
    const suffix = params.toString();
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}${suffix ? `?${suffix}` : ""}`);
  }

  updateSession(
    sessionId: string,
    patch: {
      title?: string;
      pinned?: boolean;
      archived?: boolean;
      memoryExcluded?: boolean;
    },
  ): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
  }

  deleteSession(sessionId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}`, {
      method: "DELETE",
      keepalive: true,
    });
  }

  exportSessionJsonl(sessionId: string): Promise<string> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/export`);
  }

  sendPrompt(
    sessionId: string,
    body: {
      text: string;
      steer?: boolean;
      images?: Array<{ data: string; mimeType: string; name?: string }>;
    },
  ): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/prompt`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  followUpSession(
    sessionId: string,
    text: string,
    images: Array<{ data: string; mimeType: string; name?: string }> = [],
  ): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/followup`, {
      method: "POST",
      body: JSON.stringify({ text, images }),
    });
  }

  removeQueuedInput(
    sessionId: string,
    kind: "steer" | "follow_up",
    text: string,
  ): Promise<HostQueuedInputDto[]> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/queue`, {
      method: "DELETE",
      body: JSON.stringify({ kind, text }),
    });
  }

  promoteQueuedInput(sessionId: string, text: string): Promise<HostQueuedInputDto[]> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/queue/promote`, {
      method: "POST",
      body: JSON.stringify({ text }),
    });
  }

  retrySession(sessionId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/retry`, {
      method: "POST",
    });
  }

  abortSession(sessionId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/abort`, {
      method: "POST",
    });
  }

  compactSession(sessionId: string): Promise<HostCompactResponse> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/compact`, {
      method: "POST",
    });
  }

  setSessionModel(
    sessionId: string,
    providerId: string,
    modelId: string,
    thinkingLevel: string,
  ): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/model`, {
      method: "PUT",
      body: JSON.stringify({ providerId, modelId, thinkingLevel }),
    });
  }

  stageAttachment(
    botId: string,
    body: { name: string; mimeType: string; dataBase64: string },
  ): Promise<HostStagedAttachmentDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/attachments`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  stageAttachmentStream(
    botId: string,
    file: File,
    name: string,
    mimeType: string,
  ): Promise<HostStagedAttachmentDto> {
    const query = `name=${encodeURIComponent(name)}&mime=${encodeURIComponent(mimeType)}`;
    return this.request(
      `/api/v1/bots/${encodeURIComponent(botId)}/attachments/stream?${query}`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/octet-stream",
        },
        body: file,
      },
      120_000,
    );
  }

  stageFolderAttachment(botId: string, path: string): Promise<HostStagedAttachmentDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/attachments/folder`, {
      method: "POST",
      body: JSON.stringify({ path }),
    });
  }

  async fetchSessionArtifact(
    sessionId: string,
    filePath: string,
    download = false,
  ): Promise<Blob> {
    const query = `path=${encodeURIComponent(filePath)}${download ? "&download=1" : ""}`;
    const resp = await fetch(
      `${this.baseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/artifact?${query}`,
      {
        headers: { Authorization: `Bearer ${this.token}` },
      },
    );
    if (!resp.ok) {
      throw new HostApiError(resp.status, `HTTP ${resp.status}`);
    }
    return resp.blob();
  }

  listQuestions(): Promise<HostPendingQuestionDto[]> {
    return this.request("/api/v1/questions");
  }

  answerQuestion(requestId: string, answers: QuestionAnswerInput[]): Promise<HostHealthDto> {
    return this.request(`/api/v1/questions/${encodeURIComponent(requestId)}/answer`, {
      method: "POST",
      body: JSON.stringify({ answers }),
    });
  }

  dismissQuestion(requestId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/questions/${encodeURIComponent(requestId)}/dismiss`, {
      method: "POST",
    });
  }

  listApprovals(sessionId?: string, workerId?: string): Promise<HostPendingToolApprovalDto[]> {
    const params = new URLSearchParams();
    if (sessionId) params.set("sessionId", sessionId);
    if (workerId) params.set("workerId", workerId);
    const suffix = params.toString();
    return this.request(`/api/v1/approvals${suffix ? `?${suffix}` : ""}`);
  }

  resolveApproval(
    approvalId: string,
    decision: ToolApprovalDecision,
    note?: string,
  ): Promise<HostHealthDto> {
    return this.request(`/api/v1/approvals/${encodeURIComponent(approvalId)}/resolve`, {
      method: "POST",
      body: JSON.stringify({ decision, note }),
    });
  }

  getSettings(): Promise<HostSettingsDto> {
    return this.request("/api/v1/settings");
  }

  patchSettings(patch: Record<string, unknown>): Promise<HostSettingsDto> {
    return this.request("/api/v1/settings", {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
  }

  listProviders(): Promise<HostProviderDto[]> {
    return this.request("/api/v1/providers");
  }

  saveProviderKey(providerId: string, apiKey: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/providers/${encodeURIComponent(providerId)}/key`, {
      method: "PUT",
      body: JSON.stringify({ apiKey }),
    });
  }

  clearProviderKey(providerId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/providers/${encodeURIComponent(providerId)}/key`, {
      method: "DELETE",
    });
  }

  listCustomProviders(): Promise<HostCustomProviderDto[]> {
    return this.request("/api/v1/providers/custom");
  }

  saveCustomProvider(body: {
    id?: string;
    name: string;
    api: string;
    baseUrl: string;
    apiKey: string;
    models: HostCustomModelDto[];
  }): Promise<HostCustomProviderDto> {
    if (body.id) {
      return this.request(`/api/v1/providers/custom/${encodeURIComponent(body.id)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      });
    }
    return this.request("/api/v1/providers/custom", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  deleteCustomProvider(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/providers/custom/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  discoverCustomModels(body: {
    baseUrl: string;
    apiKey: string;
    api: string;
  }): Promise<{ models: string[]; detailedModels: HostCustomModelDto[] }> {
    return this.request("/api/v1/providers/custom/discover", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  startOAuth(providerId: string): Promise<HostOAuthStatusDto> {
    return this.request(`/api/v1/providers/${encodeURIComponent(providerId)}/oauth/start`, {
      method: "POST",
    });
  }

  getOAuthStatus(providerId: string): Promise<HostOAuthStatusDto> {
    return this.request(`/api/v1/providers/${encodeURIComponent(providerId)}/oauth/status`);
  }

  submitOAuth(providerId: string, value: string): Promise<HostOAuthStatusDto> {
    return this.request(`/api/v1/providers/${encodeURIComponent(providerId)}/oauth/submit`, {
      method: "POST",
      body: JSON.stringify({ value }),
    });
  }

  cancelOAuth(providerId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/providers/${encodeURIComponent(providerId)}/oauth/cancel`, {
      method: "POST",
    });
  }

  logoutOAuth(providerId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/providers/${encodeURIComponent(providerId)}/oauth`, {
      method: "DELETE",
    });
  }

  getRelayStatus(): Promise<HostRelayStatusDto> {
    return this.request("/api/v1/relay/status");
  }

  generatePairCode(): Promise<{ link: string; expiresAt?: number | null }> {
    return this.request("/api/v1/relay/pair-code", { method: "POST" });
  }

  pairRelay(body: {
    link: string;
    loginSecret?: string;
    deviceName?: string;
  }): Promise<HostRelayStatusDto> {
    return this.request("/api/v1/relay/pair", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  logoutRelay(): Promise<HostHealthDto> {
    return this.request("/api/v1/relay/logout", { method: "POST" });
  }

  listRelayDevices(): Promise<HostRelayDeviceDto[]> {
    return this.request("/api/v1/relay/devices");
  }

  renameRelayDevice(deviceId: string, name: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/relay/devices/${encodeURIComponent(deviceId)}`, {
      method: "PATCH",
      body: JSON.stringify({ name }),
    });
  }

  revokeRelayDevice(deviceId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/relay/devices/${encodeURIComponent(deviceId)}`, {
      method: "DELETE",
    });
  }

  setRelayAllowRemote(allowRemote: boolean): Promise<HostRelayStatusDto> {
    return this.request("/api/v1/relay/allow-remote", {
      method: "PUT",
      body: JSON.stringify({ allowRemote }),
    });
  }

  subscribeEvents(
    onEvent: (event: HostSseEvent) => void,
    onStateChange?: (connected: boolean) => void,
  ): () => void {
    let closed = false;
    let currentSource: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let backoffMs = 1000;

    const eventNames = [
      "ready",
      "run_started",
      "activity_changed",
      "text_delta",
      "reasoning_delta",
      "tool_call_started",
      "tool_started",
      "tool_finished",
      "run_finished",
      "run_failed",
      "custom_card",
      "queue_changed",
      "queue_dropped",
      "user_input_admitted",
      "turn_injected",
      "session_removed",
      "session_archived",
      "session_list_changed",
      "capabilities_changed",
      "session_updated",
      "usage_changed",
      "notice",
      "questions_changed",
      "approvals_changed",
      "devices_changed",
      "super_run_started",
      "super_activity_changed",
      "super_text_delta",
      "super_reasoning_delta",
      "super_tool_call_started",
      "super_tool_started",
      "super_tool_finished",
      "super_run_finished",
      "super_run_failed",
      "super_custom_card",
      "super_queue_changed",
      "super_queue_dropped",
      "super_user_input_admitted",
      "super_turn_injected",
      "super_capabilities_changed",
      "super_session_updated",
      "super_usage_changed",
      "super_notice",
      "worker_progress",
      "worker_approval",
      "goal_changed",
      "jobs_changed",
      "inbox_changed",
    ];

    const scheduleReconnect = () => {
      if (closed || reconnectTimer !== null) return;
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        void connect();
      }, backoffMs);
      backoffMs = Math.min(5000, backoffMs * 2);
    };

    const connect = async () => {
      if (closed) return;
      try {
        const { ticket } = await this.request<{ ticket: string }>("/api/v1/auth/sse-ticket", {
          method: "POST",
        });
        if (closed) return;
        const url = `${this.baseUrl}/api/v1/events?ticket=${encodeURIComponent(ticket)}`;
        const es = new EventSource(url);
        currentSource = es;

        const handleEvent = (ev: MessageEvent<string>) => {
          if (closed) return;
          try {
            const parsed = JSON.parse(ev.data) as HostSseEvent;
            if (parsed.type === "ready") {
              backoffMs = 1000;
              onStateChange?.(true);
            }
            onEvent(parsed);
          } catch {
            return;
          }
        };

        for (const name of eventNames) {
          es.addEventListener(name, handleEvent as EventListener);
        }

        es.onerror = () => {
          es.close();
          if (currentSource === es) currentSource = null;
          onStateChange?.(false);
          scheduleReconnect();
        };
      } catch {
        onStateChange?.(false);
        scheduleReconnect();
      }
    };

    void connect();

    return () => {
      closed = true;
      if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      if (currentSource) {
        currentSource.close();
        currentSource = null;
      }
    };
  }

  listBotFiles(botId: string, relPath = ""): Promise<HostWorkspaceFileListResponse> {
    const query = relPath ? `?path=${encodeURIComponent(relPath)}` : "";
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/files${query}`);
  }

  readBotFile(botId: string, relPath: string): Promise<HostWorkspaceFileReadResponse> {
    return this.request(
      `/api/v1/bots/${encodeURIComponent(botId)}/files/read?path=${encodeURIComponent(relPath)}`,
    );
  }

  async fetchBotFileRaw(botId: string, relPath: string, download = false): Promise<Blob> {
    const query = `path=${encodeURIComponent(relPath)}${download ? "&download=1" : ""}`;
    const resp = await fetch(
      `${this.baseUrl}/api/v1/bots/${encodeURIComponent(botId)}/files/raw?${query}`,
      {
        headers: { Authorization: `Bearer ${this.token}` },
      },
    );
    if (!resp.ok) {
      throw new HostApiError(resp.status, `HTTP ${resp.status}`);
    }
    return resp.blob();
  }

  uploadBotFile(
    botId: string,
    body: { path: string; dataBase64: string; overwrite?: boolean; expectedMtimeMs?: number },
  ): Promise<{ ok: boolean; mtimeMs?: number }> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/files/upload`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  mkdirBotFile(botId: string, relPath: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/files/mkdir`, {
      method: "POST",
      body: JSON.stringify({ path: relPath }),
    });
  }

  renameBotFile(botId: string, from: string, to: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/files/rename`, {
      method: "POST",
      body: JSON.stringify({ from, to }),
    });
  }

  deleteBotFile(botId: string, relPath: string, recursive = false): Promise<HostHealthDto> {
    return this.request(
      `/api/v1/bots/${encodeURIComponent(botId)}/files?path=${encodeURIComponent(relPath)}&recursive=${recursive ? "true" : "false"}`,
      { method: "DELETE" },
    );
  }

  searchBotFiles(
    botId: string,
    query: string,
    basePath = "",
  ): Promise<HostWorkspaceSearchResponse> {
    const params = new URLSearchParams({ q: query });
    if (basePath) params.set("path", basePath);
    return this.request(
      `/api/v1/bots/${encodeURIComponent(botId)}/files/search?${params.toString()}`,
    );
  }

  getGitSummary(botId: string, worktreeId?: string | null): Promise<HostGitSummaryResponse> {
    const q = worktreeId ? `?worktreeId=${encodeURIComponent(worktreeId)}` : "";
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/git/summary${q}`);
  }

  getGitDiff(
    botId: string,
    options?: { path?: string | null; worktreeId?: string | null },
  ): Promise<HostGitDiffResponse> {
    const params = new URLSearchParams();
    if (options?.path) params.set("path", options.path);
    if (options?.worktreeId) params.set("worktreeId", options.worktreeId);
    const qs = params.toString();
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/git/diff${qs ? `?${qs}` : ""}`);
  }

  stageGitPath(
    botId: string,
    body: { path?: string; all?: boolean; worktreeId?: string | null },
  ): Promise<HostGitSummaryResponse> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/git/stage`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  unstageGitPath(
    botId: string,
    body: { path?: string; all?: boolean; worktreeId?: string | null },
  ): Promise<HostGitSummaryResponse> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/git/unstage`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  revertGitPath(
    botId: string,
    body: { path: string; worktreeId?: string | null },
  ): Promise<HostGitSummaryResponse> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/git/revert`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  commitGit(
    botId: string,
    body: { message: string; stageAll?: boolean; worktreeId?: string | null },
  ): Promise<HostWorktreeOpResponse> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/git/commit`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  openInExternalEditor(
    botId: string,
    body?: { editor?: string; worktreeId?: string | null },
  ): Promise<HostOpenEditorResponse> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/open-editor`, {
      method: "POST",
      body: JSON.stringify(body ?? {}),
    });
  }

  listWorktrees(botId: string): Promise<HostWorktreeDto[]> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/worktrees`);
  }

  createWorktree(
    botId: string,
    body?: { label?: string; createSession?: boolean },
  ): Promise<HostCreateWorktreeResponse> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/worktrees`, {
      method: "POST",
      body: JSON.stringify(body ?? {}),
    });
  }

  getWorktreeDiff(wtId: string): Promise<HostWorktreeDiffResponse> {
    return this.request(`/api/v1/worktrees/${encodeURIComponent(wtId)}/diff`);
  }

  commitWorktree(wtId: string, message = ""): Promise<HostWorktreeOpResponse> {
    return this.request(`/api/v1/worktrees/${encodeURIComponent(wtId)}/commit`, {
      method: "POST",
      body: JSON.stringify({ message }),
    });
  }

  async applyWorktree(wtId: string): Promise<HostWorktreeOpResponse> {
    const resp = await fetch(`${this.baseUrl}/api/v1/worktrees/${encodeURIComponent(wtId)}/apply`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({}),
    });
    if (resp.status === 409) {
      const body = (await resp.json().catch(() => null)) as HostWorktreeOpResponse | null;
      if (body && typeof body === "object") {
        return {
          ok: Boolean(body.ok),
          message: body.message || "Merge conflicts",
          conflicts: Array.isArray(body.conflicts) ? body.conflicts : [],
        };
      }
    }
    if (!resp.ok) {
      throw new HostApiError(resp.status, await readErrorMessage(resp));
    }
    return (await resp.json()) as HostWorktreeOpResponse;
  }

  deleteWorktree(wtId: string, deleteBranch = true, force = false): Promise<HostHealthDto> {
    const forcePart = force ? "&force=true" : "";
    return this.request(
      `/api/v1/worktrees/${encodeURIComponent(wtId)}?deleteBranch=${deleteBranch ? "true" : "false"}${forcePart}`,
      { method: "DELETE" },
    );
  }

  listCheckpoints(sessionId: string): Promise<HostCheckpointDto[]> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/checkpoints`);
  }

  restoreCheckpoint(
    sessionId: string,
    cpId: string,
    mode: "files_only" | "files_and_chat" = "files_only",
  ): Promise<HostRestoreCheckpointResponse> {
    return this.request(
      `/api/v1/sessions/${encodeURIComponent(sessionId)}/checkpoints/${encodeURIComponent(cpId)}/restore`,
      {
        method: "POST",
        body: JSON.stringify({ mode }),
      },
    );
  }

  rewindSession(
    sessionId: string,
    body: {
      turnIndex: number;
      mode: "chat_only" | "files_only" | "files_and_chat";
      checkpointId?: string;
    },
  ): Promise<HostRestoreCheckpointResponse> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/rewind`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  forkSession(
    sessionId: string,
    body?: { upToUserTurn?: number; title?: string },
  ): Promise<{ sessionId: string; filePath?: string | null; cwd: string }> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/fork`, {
      method: "POST",
      body: JSON.stringify(body ?? {}),
    });
  }

  listTerminals(botId: string): Promise<HostTerminalInfoDto[]> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/terminals`);
  }

  createTerminal(
    botId: string,
    body?: { worktreeId?: string | null; fresh?: boolean },
  ): Promise<HostTerminalInfoDto> {
    return this.request(`/api/v1/bots/${encodeURIComponent(botId)}/terminals`, {
      method: "POST",
      body: JSON.stringify(body ?? {}),
    });
  }

  sendTerminalInput(termId: string, data: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/terminals/${encodeURIComponent(termId)}/input`, {
      method: "POST",
      body: JSON.stringify({ data }),
    });
  }

  resizeTerminal(termId: string, cols: number, rows: number): Promise<HostHealthDto> {
    return this.request(`/api/v1/terminals/${encodeURIComponent(termId)}/resize`, {
      method: "POST",
      body: JSON.stringify({ cols, rows }),
    });
  }

  deleteTerminal(termId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/terminals/${encodeURIComponent(termId)}`, {
      method: "DELETE",
    });
  }

  streamTerminal(
    termId: string,
    onFrame: (frame: HostTerminalStreamFrame) => void,
    onError?: (err: Error) => void,
  ): () => void {
    const controller = new AbortController();
    let closed = false;
    let sawExit = false;

    const run = async () => {
      try {
        const resp = await fetch(
          `${this.baseUrl}/api/v1/terminals/${encodeURIComponent(termId)}/stream`,
          {
            headers: {
              Authorization: `Bearer ${this.token}`,
              Accept: "text/event-stream",
            },
            signal: controller.signal,
          },
        );
        if (!resp.ok || !resp.body) {
          throw new HostApiError(resp.status, `HTTP ${resp.status}`);
        }
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let dataLines: string[] = [];

        const flushBlock = () => {
          if (dataLines.length === 0) return;
          const payload = dataLines.join("\n");
          dataLines = [];
          try {
            const frame = JSON.parse(payload) as HostTerminalStreamFrame;
            if (frame.type === "exit") sawExit = true;
            onFrame(frame);
          } catch {
            void 0;
          }
        };

        while (!closed) {
          const { done, value } = await reader.read();
          if (done) {
            flushBlock();
            break;
          }
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          buffer = lines.pop() ?? "";
          for (const rawLine of lines) {
            const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
            if (line === "") {
              flushBlock();
            } else if (line.startsWith("data:")) {
              dataLines.push(line.slice(5).trimStart());
            }
          }
        }
        if (!closed && !controller.signal.aborted && !sawExit) {
          onError?.(new Error("Terminal connection closed unexpectedly"));
        }
      } catch (err) {
        if (!closed && !controller.signal.aborted) {
          onError?.(err instanceof Error ? err : new Error(String(err)));
        }
      }
    };

    void run();

    return () => {
      closed = true;
      controller.abort();
    };
  }

  getSuperState(): Promise<HostSuperStateResponse> {
    return this.request("/api/v1/super/state");
  }

  setSuperModel(
    providerId: string,
    modelId: string,
    thinkingLevel: string,
  ): Promise<HostHealthDto> {
    return this.request("/api/v1/super/model", {
      method: "PUT",
      body: JSON.stringify({ providerId, modelId, thinkingLevel }),
    });
  }

  compactSuper(): Promise<HostCompactResponse> {
    return this.request("/api/v1/super/compact", {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  resetSuper(): Promise<HostHealthDto> {
    return this.request("/api/v1/super/reset", {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  sendSuperPrompt(body: {
    text: string;
    steer?: boolean;
    images?: Array<{ data: string; mimeType: string; name?: string }>;
  }): Promise<HostHealthDto> {
    return this.request("/api/v1/super/prompt", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  abortSuper(): Promise<HostHealthDto> {
    return this.request("/api/v1/super/abort", {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  answerSuperQuestion(
    requestId: string,
    answers: QuestionAnswerInput[],
  ): Promise<HostHealthDto> {
    return this.request(
      `/api/v1/super/question/${encodeURIComponent(requestId)}/answer`,
      {
        method: "POST",
        body: JSON.stringify({ answers }),
      },
    );
  }

  dismissSuperQuestion(requestId: string): Promise<HostHealthDto> {
    return this.request(
      `/api/v1/super/question/${encodeURIComponent(requestId)}/dismiss`,
      {
        method: "POST",
        body: JSON.stringify({}),
      },
    );
  }

  getSuperTeam(): Promise<HostSuperTeamBotDto[]> {
    return this.request("/api/v1/super/team");
  }

  setSuperTeamBot(botRef: string, enabled: boolean): Promise<HostSuperTeamBotDto[]> {
    return this.request("/api/v1/super/team", {
      method: "PUT",
      body: JSON.stringify({ botRef, enabled }),
    });
  }

  getSuperSettings(): Promise<HostSuperSettingsDto> {
    return this.request("/api/v1/super/settings");
  }

  updateSuperSettings(patch: Partial<HostSuperSettingsDto>): Promise<HostSuperSettingsDto> {
    return this.request("/api/v1/super/settings", {
      method: "PUT",
      body: JSON.stringify(patch),
    });
  }

  getSuperAgentsDoc(): Promise<HostAgentDocDto> {
    return this.request("/api/v1/super/agents-doc");
  }

  saveSuperAgentsDoc(content: string): Promise<HostAgentDocDto> {
    return this.request("/api/v1/super/agents-doc", {
      method: "PUT",
      body: JSON.stringify({ content }),
    });
  }

  deleteSuperAgentsDoc(): Promise<HostHealthDto> {
    return this.request("/api/v1/super/agents-doc", {
      method: "DELETE",
    });
  }

  getSuperWorker(workerId: string): Promise<HostWorkerDetailDto> {
    return this.request(`/api/v1/super/workers/${encodeURIComponent(workerId)}`);
  }

  streamSuperWorker(
    workerId: string,
    handlers: {
      onSnapshot: (detail: HostWorkerDetailDto) => void;
      onDeleted?: () => void;
      onError?: (err: Error) => void;
    },
  ): () => void {
    const controller = new AbortController();
    let closed = false;

    const run = async () => {
      try {
        const resp = await fetch(
          `${this.baseUrl}/api/v1/super/workers/${encodeURIComponent(workerId)}/stream`,
          {
            headers: {
              Authorization: `Bearer ${this.token}`,
              Accept: "text/event-stream",
            },
            signal: controller.signal,
          },
        );
        if (!resp.ok || !resp.body) {
          throw new HostApiError(resp.status, `HTTP ${resp.status}`);
        }
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let eventName = "message";
        let dataLines: string[] = [];

        const flushBlock = () => {
          if (dataLines.length === 0) {
            eventName = "message";
            return;
          }
          const payload = dataLines.join("\n");
          const currentEvent = eventName;
          dataLines = [];
          eventName = "message";
          if (currentEvent === "worker_deleted") {
            handlers.onDeleted?.();
            return;
          }
          try {
            const detail = JSON.parse(payload) as HostWorkerDetailDto;
            handlers.onSnapshot(detail);
          } catch {
            void 0;
          }
        };

        while (!closed) {
          const { done, value } = await reader.read();
          if (done) {
            flushBlock();
            break;
          }
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          buffer = lines.pop() ?? "";
          for (const rawLine of lines) {
            const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
            if (line === "") {
              flushBlock();
            } else if (line.startsWith("event:")) {
              eventName = line.slice(6).trim();
            } else if (line.startsWith("data:")) {
              dataLines.push(line.slice(5).trimStart());
            }
          }
        }
      } catch (err) {
        if (!closed && !controller.signal.aborted) {
          handlers.onError?.(err instanceof Error ? err : new Error(String(err)));
        }
      }
    };

    void run();

    return () => {
      closed = true;
      controller.abort();
    };
  }

  sendSuperWorkerPrompt(
    workerId: string,
    body: {
      text: string;
      images?: Array<{ data: string; mimeType: string; name?: string }>;
    },
  ): Promise<HostHealthDto> {
    return this.request(`/api/v1/super/workers/${encodeURIComponent(workerId)}/prompt`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  setSuperWorkerModel(
    workerId: string,
    providerId: string,
    modelId: string,
    thinkingLevel: string,
  ): Promise<HostHealthDto> {
    return this.request(`/api/v1/super/workers/${encodeURIComponent(workerId)}/model`, {
      method: "PUT",
      body: JSON.stringify({ providerId, modelId, thinkingLevel }),
    });
  }

  compactSuperWorker(workerId: string): Promise<HostCompactResponse> {
    return this.request(`/api/v1/super/workers/${encodeURIComponent(workerId)}/compact`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  answerSuperWorkerQuestion(
    workerId: string,
    requestId: string,
    answers: QuestionAnswerInput[],
  ): Promise<HostHealthDto> {
    return this.request(
      `/api/v1/super/workers/${encodeURIComponent(workerId)}/question/${encodeURIComponent(requestId)}/answer`,
      {
        method: "POST",
        body: JSON.stringify({ answers }),
      },
    );
  }

  dismissSuperWorkerQuestion(workerId: string, requestId: string): Promise<HostHealthDto> {
    return this.request(
      `/api/v1/super/workers/${encodeURIComponent(workerId)}/question/${encodeURIComponent(requestId)}/dismiss`,
      {
        method: "POST",
        body: JSON.stringify({}),
      },
    );
  }

  stopSuperWorker(workerId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/super/workers/${encodeURIComponent(workerId)}/stop`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  deleteSuperWorker(workerId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/super/workers/${encodeURIComponent(workerId)}`, {
      method: "DELETE",
    });
  }

  getMemorySnapshot(params?: {
    includeArchived?: boolean;
    includeHistory?: boolean;
    query?: string;
    limit?: number;
  }): Promise<HostMemoryFullSnapshotResponse> {
    const qs = new URLSearchParams();
    if (params?.includeArchived) qs.set("includeArchived", "true");
    if (params?.includeHistory) qs.set("includeHistory", "true");
    if (params?.query) qs.set("query", params.query);
    if (params?.limit) qs.set("limit", String(params.limit));
    const q = qs.toString();
    return this.request(`/api/v1/memory/snapshot${q ? `?${q}` : ""}`);
  }

  createMemoryBlock(body: {
    title: string;
    purpose?: string;
    content?: string;
    domain?: string;
    pinned?: boolean;
  }): Promise<HostMemoryBlockDto> {
    return this.request("/api/v1/memory/blocks", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  updateMemoryBlock(
    id: string,
    body: {
      title?: string;
      purpose?: string;
      content?: string;
      domain?: string;
      pinned?: boolean;
      status?: string;
    },
  ): Promise<HostMemoryBlockDto> {
    return this.request(`/api/v1/memory/blocks/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  deleteMemoryBlock(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/memory/blocks/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  acceptMemoryProposal(id: string): Promise<HostMemoryBlockDto> {
    return this.request(`/api/v1/memory/proposals/${encodeURIComponent(id)}/accept`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  dismissMemoryProposal(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/memory/proposals/${encodeURIComponent(id)}/dismiss`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  createMemoryEntity(body: {
    canonicalName: string;
    entityType?: string;
    aliases?: string[];
    summary?: string;
    domain?: string;
  }): Promise<HostMemoryEntityDto> {
    return this.request("/api/v1/memory/entities", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  getMemoryEntity(id: string, includeHistory = false): Promise<HostMemoryEntityDetailDto> {
    const q = includeHistory ? "?includeHistory=true" : "";
    return this.request(`/api/v1/memory/entities/${encodeURIComponent(id)}${q}`);
  }

  updateMemoryEntity(
    id: string,
    body: {
      canonicalName?: string;
      entityType?: string;
      aliases?: string[];
      summary?: string;
      clearSummary?: boolean;
      domain?: string;
      pinned?: boolean;
    },
  ): Promise<HostMemoryEntityDto> {
    return this.request(`/api/v1/memory/entities/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  mergeMemoryEntities(body: {
    keepId: string;
    mergeIds: string[];
    mergedSummary?: string;
  }): Promise<HostHealthDto> {
    return this.request("/api/v1/memory/entities/merge", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  createMemoryRelation(body: {
    fromEntityName: string;
    relationType?: string;
    toEntityName: string;
    label?: string;
    domain?: string;
  }): Promise<HostMemoryRelationDto> {
    return this.request("/api/v1/memory/relations", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  deleteMemoryRelation(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/memory/relations/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  createMemoryEvent(body: {
    eventKind?: string;
    content: string;
    summary?: string;
    tags?: string[];
    domain?: string;
    importance?: number;
    pinned?: boolean;
  }): Promise<HostMemoryEventDto> {
    return this.request("/api/v1/memory/events", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  updateMemoryEvent(
    id: string,
    body: {
      eventKind?: string;
      content?: string;
      summary?: string;
      tags?: string[];
      domain?: string;
      pinned?: boolean;
    },
  ): Promise<HostMemoryEventDto> {
    return this.request(`/api/v1/memory/events/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  deleteMemoryEvent(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/memory/events/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  restoreMemoryEvent(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/memory/events/${encodeURIComponent(id)}/restore`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  revertMemoryAudit(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/memory/audit/${encodeURIComponent(id)}/revert`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  updateMemoryPreferences(
    patch: Partial<HostMemoryPreferencesDto> & {
      clearHotModel?: boolean;
      clearColdModel?: boolean;
      clearSectionBudgets?: boolean;
    },
  ): Promise<HostMemoryPreferencesDto> {
    return this.request("/api/v1/memory/preferences", {
      method: "PUT",
      body: JSON.stringify(patch),
    });
  }

  consolidateMemory(): Promise<HostHealthDto> {
    return this.request("/api/v1/memory/consolidate", {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  retryPendingMemory(): Promise<HostHealthDto> {
    return this.request("/api/v1/memory/retry-pending", {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  syncMemoryNow(): Promise<HostHealthDto> {
    return this.request("/api/v1/memory/sync", {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  resolveMemorySync(mode: "MERGE" | "REPLACE_LOCAL"): Promise<HostMemorySyncStatusDto> {
    return this.request("/api/v1/memory/sync/resolve", {
      method: "POST",
      body: JSON.stringify({ mode }),
    });
  }

  async exportMemoryMarkdown(): Promise<string> {
    const resp = await fetch(`${this.baseUrl}/api/v1/memory/export`, {
      headers: { Authorization: `Bearer ${this.token}` },
    });
    if (!resp.ok) {
      throw new HostApiError(resp.status, `HTTP ${resp.status}`);
    }
    return resp.text();
  }

  listSkills(): Promise<HostInstalledSkillDto[]> {
    return this.request("/api/v1/skills");
  }

  getSkillContent(id: string): Promise<HostSkillContentResponse> {
    return this.request(`/api/v1/skills/${encodeURIComponent(id)}/content`);
  }

  installSkillZip(fileName: string, base64Zip: string): Promise<HostInstalledSkillDto> {
    return this.request("/api/v1/skills/install", {
      method: "POST",
      body: JSON.stringify({ fileName, base64Zip }),
    });
  }

  toggleSkill(name: string, enabled: boolean): Promise<HostHealthDto> {
    return this.request(`/api/v1/skills/${encodeURIComponent(name)}/toggle`, {
      method: "PUT",
      body: JSON.stringify({ enabled }),
    });
  }

  deleteSkill(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/skills/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  listMcpServers(): Promise<HostMcpServerDto[]> {
    return this.request("/api/v1/mcp");
  }

  upsertMcpServer(name: string, body: HostUpsertMcpRequest): Promise<HostHealthDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  importMcpRaw(json: string, originalName?: string): Promise<HostHealthDto> {
    return this.request("/api/v1/mcp/raw", {
      method: "PUT",
      body: JSON.stringify({ json, originalName }),
    });
  }

  testMcpServer(body: HostUpsertMcpRequest): Promise<HostMcpServerDto> {
    return this.request("/api/v1/mcp/test", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  probeMcpServer(name: string): Promise<HostMcpServerDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}/probe`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  probeAllMcpServers(): Promise<HostMcpServerDto[]> {
    return this.request("/api/v1/mcp/probe", {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  toggleMcpServer(name: string, enabled: boolean): Promise<HostHealthDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}/toggle`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    });
  }

  deleteMcpServer(name: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}`, {
      method: "DELETE",
    });
  }

  startMcpOAuth(name: string): Promise<HostOAuthStatusDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}/oauth/start`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  getMcpOAuthStatus(name: string): Promise<HostOAuthStatusDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}/oauth/status`);
  }

  submitMcpOAuth(name: string, value: string): Promise<HostOAuthStatusDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}/oauth/submit`, {
      method: "POST",
      body: JSON.stringify({ value }),
    });
  }

  cancelMcpOAuth(name: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}/oauth/cancel`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  logoutMcpOAuth(name: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/mcp/${encodeURIComponent(name)}/oauth`, {
      method: "DELETE",
    });
  }

  listTimers(): Promise<HostTimerDto[]> {
    return this.request("/api/v1/timers");
  }

  createTimer(body: HostSaveTimerRequest): Promise<HostTimerDto> {
    return this.request("/api/v1/timers", {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  updateTimer(id: string, body: HostSaveTimerRequest): Promise<HostTimerDto> {
    return this.request(`/api/v1/timers/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
  }

  toggleTimer(id: string, enabled: boolean): Promise<HostTimerDto> {
    return this.request(`/api/v1/timers/${encodeURIComponent(id)}/toggle`, {
      method: "POST",
      body: JSON.stringify({ enabled }),
    });
  }

  runTimerNow(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/timers/${encodeURIComponent(id)}/run-now`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  deleteTimer(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/timers/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  listInbox(): Promise<HostInboxItemDto[]> {
    return this.request("/api/v1/inbox");
  }

  updateInboxStatus(id: string, status: "unread" | "approved" | "dismissed"): Promise<HostInboxItemDto> {
    return this.request(`/api/v1/inbox/${encodeURIComponent(id)}/status`, {
      method: "POST",
      body: JSON.stringify({ status }),
    });
  }

  clearSettledInbox(): Promise<HostInboxItemDto[]> {
    return this.request("/api/v1/inbox/cleared", {
      method: "DELETE",
    });
  }

  deleteInboxItem(id: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/inbox/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  getSessionGoal(sessionId: string): Promise<{ goal?: HostGoalDto | null }> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/goal`);
  }

  createSessionGoal(
    sessionId: string,
    body: { objective: string; maxRounds?: number },
  ): Promise<{ goal?: HostGoalDto | null }> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/goal`, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  pauseSessionGoal(sessionId: string): Promise<{ goal?: HostGoalDto | null }> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/goal/pause`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  resumeSessionGoal(sessionId: string): Promise<{ goal?: HostGoalDto | null }> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/goal/resume`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  deleteSessionGoal(sessionId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/goal`, {
      method: "DELETE",
    });
  }

  listSessionJobs(sessionId: string): Promise<HostJobSnapshotDto[]> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/jobs`);
  }

  getSessionJobOutput(sessionId: string, jobId: string): Promise<HostJobOutputDto> {
    return this.request(
      `/api/v1/sessions/${encodeURIComponent(sessionId)}/jobs/${encodeURIComponent(jobId)}/output`,
    );
  }

  killSessionJob(sessionId: string, jobId: string): Promise<HostHealthDto> {
    return this.request(
      `/api/v1/sessions/${encodeURIComponent(sessionId)}/jobs/${encodeURIComponent(jobId)}/kill`,
      {
        method: "POST",
        body: JSON.stringify({}),
      },
    );
  }

  clearSettledSessionJobs(sessionId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}/jobs/settled`, {
      method: "DELETE",
    });
  }

  listWebSearchProviders(): Promise<HostWebSearchProviderDto[]> {
    return this.request("/api/v1/web-search");
  }

  saveWebSearchKey(providerId: string, apiKey: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/web-search/${encodeURIComponent(providerId)}`, {
      method: "PUT",
      body: JSON.stringify({ apiKey }),
    });
  }

  deleteWebSearchKey(providerId: string): Promise<HostHealthDto> {
    return this.request(`/api/v1/web-search/${encodeURIComponent(providerId)}`, {
      method: "DELETE",
    });
  }

  runDoctor(probeMcp = false): Promise<HostDoctorReportDto> {
    return this.request(`/api/v1/doctor?probeMcp=${probeMcp ? "true" : "false"}`);
  }

  async exportDoctorMarkdown(probeMcp = true): Promise<string> {
    const resp = await fetch(
      `${this.baseUrl}/api/v1/doctor/export?probeMcp=${probeMcp ? "true" : "false"}`,
      {
        headers: { Authorization: `Bearer ${this.token}` },
      },
    );
    if (!resp.ok) {
      throw new HostApiError(resp.status, `HTTP ${resp.status}`);
    }
    return resp.text();
  }

  listMarketEntries(type?: "skill" | "mcp"): Promise<HostMarketCatalogResponse> {
    const query = type ? `?type=${encodeURIComponent(type)}` : "";
    return this.request(`/api/v1/market/entries${query}`);
  }

  refreshMarketEntries(type?: "skill" | "mcp"): Promise<HostMarketCatalogResponse> {
    const query = type ? `?type=${encodeURIComponent(type)}` : "";
    return this.request(`/api/v1/market/refresh${query}`, { method: "POST" }, 30_000);
  }

  installMarketEntry(entryId: string, apiKey?: string): Promise<HostMarketInstallResponse> {
    return this.request(
      "/api/v1/market/install",
      {
        method: "POST",
        body: JSON.stringify({ entryId, apiKey }),
      },
      60_000,
    );
  }
}
