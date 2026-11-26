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
  HostGitDiffResponse,
  HostGitSummaryResponse,
  HostHealthDto,
  HostOAuthStatusDto,
  HostOpenEditorResponse,
  HostPendingQuestionDto,
  HostProviderDto,
  HostQueuedInputDto,
  HostRelayDeviceDto,
  HostRelayStatusDto,
  HostRestoreCheckpointResponse,
  HostSessionDetailDto,
  HostSessionSummaryDto,
  HostSettingsDto,
  HostSlashCommandDto,
  HostSseEvent,
  HostStagedAttachmentDto,
  HostStatusDto,
  HostTerminalInfoDto,
  HostTerminalStreamFrame,
  HostWorkspaceFileListResponse,
  HostWorkspaceFileReadResponse,
  HostWorkspaceSearchResponse,
  HostWorktreeDiffResponse,
  HostWorktreeDto,
  HostWorktreeOpResponse,
  PermissionMode,
  QuestionAnswerInput,
} from "./types";

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

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${this.token}`);
    if (init.body && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
    const resp = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers,
    });
    if (!resp.ok) {
      let msg = `HTTP ${resp.status}`;
      try {
        const body = (await resp.json()) as { error?: string; message?: string };
        msg = body.error || body.message || msg;
      } catch {
        const text = await resp.text().catch(() => "");
        if (text.trim()) msg = text.trim();
      }
      throw new HostApiError(resp.status, msg);
    }
    const contentType = resp.headers.get("content-type") ?? "";
    if (contentType.includes("application/json")) {
      return (await resp.json()) as T;
    }
    return (await resp.text()) as unknown as T;
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

  getSession(sessionId: string): Promise<HostSessionDetailDto> {
    return this.request(`/api/v1/sessions/${encodeURIComponent(sessionId)}`);
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
      planFirst?: boolean;
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
      "questions_changed",
      "devices_changed",
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
    body: { path: string; dataBase64: string; overwrite?: boolean },
  ): Promise<HostHealthDto> {
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

  applyWorktree(wtId: string): Promise<HostWorktreeOpResponse> {
    return this.request(`/api/v1/worktrees/${encodeURIComponent(wtId)}/apply`, {
      method: "POST",
      body: JSON.stringify({}),
    });
  }

  deleteWorktree(wtId: string, deleteBranch = true): Promise<HostHealthDto> {
    return this.request(
      `/api/v1/worktrees/${encodeURIComponent(wtId)}?deleteBranch=${deleteBranch ? "true" : "false"}`,
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
}
