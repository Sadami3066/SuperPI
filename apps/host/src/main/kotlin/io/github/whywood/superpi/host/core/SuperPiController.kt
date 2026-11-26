package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.data.CustomProviderStore
import io.github.whywood.superpi.data.SkillRepository
import io.github.whywood.superpi.data.WebSearchSettingsStore
import io.github.whywood.superpi.data.relay.RelayAgentTransport
import io.github.whywood.superpi.data.relay.RelayClient
import io.github.whywood.superpi.data.relay.RelayController
import io.github.whywood.superpi.data.relay.RelayLive
import io.github.whywood.superpi.data.relay.RelayMemoryClient
import io.github.whywood.superpi.data.relay.RelayMemorySync
import io.github.whywood.superpi.data.relay.RelayStore
import io.github.whywood.superpi.data.relay.RemoteBot
import io.github.whywood.superpi.data.relay.RemoteCapabilitiesInventory
import io.github.whywood.superpi.data.relay.RemoteCommandSpec
import io.github.whywood.superpi.data.relay.RemoteWorkspaces
import io.github.whywood.superpi.data.toProviderConfig
import io.github.whywood.superpi.remote.RemoteAgentTransport
import io.github.whywood.superpi.remote.RemoteArtifactClient
import io.github.whywood.superpi.remote.RemoteBotAdminClient
import io.github.whywood.superpi.remote.RemoteBotControlClient
import io.github.whywood.superpi.remote.RemoteFileClient
import io.github.whywood.superpi.remote.RemoteSessionEngine
import io.github.whywood.superpi.remote.RemoteSessionTarget
import io.github.whywood.superpi.remote.RemoteWorkerClient
import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.host.config.BotRegistry
import io.github.whywood.superpi.host.config.HostSettingsStore
import io.github.whywood.superpi.host.extensions.ExtensionRegistry
import io.github.whywood.superpi.host.platform.HostPlatform
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.host.platform.HostSecretEnv
import io.github.whywood.superpi.host.platform.PathLayout
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.AnswerCardPayload
import io.github.whywood.superpi.runtime.ApiKeyStore
import io.github.whywood.superpi.runtime.AppToolContext
import io.github.whywood.superpi.runtime.BoundSession
import io.github.whywood.superpi.runtime.EncryptedFileSecretStore
import io.github.whywood.superpi.runtime.GoalPhase
import io.github.whywood.superpi.runtime.GoalService
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.InstallationIdStore
import io.github.whywood.superpi.runtime.JobRegistry
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.JsonFileKeyValueStore
import io.github.whywood.superpi.runtime.McpConfigStore
import io.github.whywood.superpi.runtime.McpOAuthService
import io.github.whywood.superpi.runtime.McpServerProbe
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MiniappStore
import io.github.whywood.superpi.runtime.OAuthCredentialStore
import io.github.whywood.superpi.runtime.Personalization
import io.github.whywood.superpi.runtime.PiSessionEngine
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.ProviderOAuthService
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.QuestionPersistence
import io.github.whywood.superpi.runtime.RunLedger
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.RuntimeHooks
import io.github.whywood.superpi.runtime.SdkCredentialStore
import io.github.whywood.superpi.runtime.SessionBinding
import io.github.whywood.superpi.runtime.SessionCatalog
import io.github.whywood.superpi.runtime.SessionCloseReason
import io.github.whywood.superpi.runtime.SessionMetaStore
import io.github.whywood.superpi.runtime.SkillCatalog
import io.github.whywood.superpi.runtime.SkillSourceDir
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.TimerService
import io.github.whywood.superpi.runtime.TimerStore
import io.github.whywood.superpi.runtime.TimerToolScope
import io.github.whywood.superpi.runtime.TurnSource
import io.github.whywood.superpi.runtime.WorkerJobSummary
import io.github.whywood.superpi.runtime.WorkerTimerSummary
import io.github.whywood.superpi.runtime.defaultExternalSkillDirs
import io.github.whywood.superpi.runtime.goalTools
import io.github.whywood.superpi.runtime.host.HostEnvironment
import io.github.whywood.superpi.runtime.host.HostEnvironmentProvider
import io.github.whywood.superpi.runtime.jobTools
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.runtime.memory.CatalogMemoryAgentGateway
import io.github.whywood.superpi.runtime.memory.ConsolidationLeaseResult
import io.github.whywood.superpi.runtime.memory.MemoryAgentService
import io.github.whywood.superpi.runtime.memory.MemoryConsolidationCoordinator
import io.github.whywood.superpi.runtime.memory.MemoryModelRouter
import io.github.whywood.superpi.runtime.memory.MemoryPreferencesStore
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.memoryTools
import io.github.whywood.superpi.runtime.miniappLookup
import io.github.whywood.superpi.runtime.presentTool
import io.github.whywood.superpi.runtime.superagent.LocalWorkerBotSpec
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import io.github.whywood.superpi.runtime.superagent.SuperOrchestrator
import io.github.whywood.superpi.runtime.superagent.SuperTaskStore
import io.github.whywood.superpi.runtime.superagent.SuperThreadEngine
import io.github.whywood.superpi.runtime.superagent.WorkerInspectExtras
import io.github.whywood.superpi.runtime.timerTools
import io.github.whywood.superpi.runtime.todoWriteTool
import io.github.whywood.superpi.sdk.ai.http.defaultClient
import io.github.whywood.superpi.sdk.ai.http.okHttpTransport
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.settings.CompactionSettings
import io.github.whywood.superpi.sdk.mcp.oauth.FileMcpTokenStore
import io.github.whywood.superpi.web.DeepSeekWebProvider
import io.github.whywood.superpi.web.RoutingWebProvider
import io.github.whywood.superpi.web.braveBackend
import io.github.whywood.superpi.web.exaBackend
import io.github.whywood.superpi.web.serperBackend
import io.github.whywood.superpi.web.tavilyBackend
import io.github.whywood.superpi.web.webTools
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

/**
 * Single business kernel shared by CLI modes (REPL, -p, json, rpc), `super-pi serve`,
 * and Stage 2/3 local HTTP/Web UI planes.
 */
class SuperPiController(
    val platform: HostPlatform,
) {
    val paths: PathLayout = platform.paths
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val hostEnv: HostEnvironment = platform.createHostEnvironment()
    val hostEnvironmentProvider: HostEnvironmentProvider = object : HostEnvironmentProvider {
        override fun current(): HostEnvironment = hostEnv
    }

    val settingsStore: HostSettingsStore = HostSettingsStore(paths.settingsFile)
    val secretStore = platform.createSecretStore()
    val apiKeyStore = ApiKeyStore(secretStore)
    val oauthCredentialStore = OAuthCredentialStore(secretStore)
    val credentials = SdkCredentialStore(apiKeyStore, oauthCredentialStore, scope)
    private val httpClient: OkHttpClient = defaultClient()
    internal val sharedHttpClient: OkHttpClient get() = httpClient
    val httpTransport = okHttpTransport(httpClient)
    private val cardJson = Json { ignoreUnknownKeys = true }

    val customProviderStore = CustomProviderStore(paths.customProvidersFile)
    val providerCatalog: ProviderCatalog = ProviderCatalog(
        httpTransport,
        credentials,
        customProviderStore.entries.value.map { it.toProviderConfig() },
    )
    val installationId = InstallationIdStore(paths.installationIdDir)
    val providerOAuthService = ProviderOAuthService(
        credentials = credentials,
        transport = httpTransport,
        deviceId = { installationId.get() },
    )

    val webSearchSettings = WebSearchSettingsStore(secretStore)
    private val webProvider by lazy {
        RoutingWebProvider(
            backends = listOf(
                exaBackend { webSearchSettings.key(WebSearchSettingsStore.EXA) },
                tavilyBackend { webSearchSettings.key(WebSearchSettingsStore.TAVILY) },
                braveBackend { webSearchSettings.key(WebSearchSettingsStore.BRAVE) },
                serperBackend { webSearchSettings.key(WebSearchSettingsStore.SERPER) },
            ),
            fallback = DeepSeekWebProvider(
                apiKeyProvider = { credentials.readApiKey("deepseek") },
            ),
        )
    }

    private val botsKvStore = JsonFileKeyValueStore(paths.botsFile)
    val botRegistry = BotRegistry(
        paths = paths,
        botsKvStore = botsKvStore,
        settingsStore = settingsStore,
    )

    val mcpConfigStore = McpConfigStore(paths.homeDir)
    val mcpTokenStore = FileMcpTokenStore(paths.mcpAuthFile.absolutePath.toPath())
    val mcpServerProbe = McpServerProbe(httpTransport, mcpTokenStore)
    val mcpOAuthService = McpOAuthService(httpTransport, mcpTokenStore)
    val externalSkillDirs: List<SkillSourceDir> = defaultExternalSkillDirs()
    val skillCatalog = SkillCatalog(paths.homeDir, externalSkillDirs)
    val skillRepository = SkillRepository(
        repoDir = paths.skillsRepoDir,
        agentDir = paths.homeDir,
        homeDir = paths.defaultWorkspaceDir,
        workspacesDir = paths.workspacesDir,
    )
    val extensionRegistry = ExtensionRegistry(
        paths = paths,
        settingsStore = settingsStore,
        mcpConfigStore = mcpConfigStore,
        mcpServerProbe = mcpServerProbe,
        skillCatalog = skillCatalog,
    )

    private val sessionMetaKvStore = JsonFileKeyValueStore(paths.sessionMetaFile)
    val sessionMetaStore = SessionMetaStore(sessionMetaKvStore)
    val sessionCatalog = SessionCatalog(paths.homeDir, sessionMetaStore)
    val questionCenter = QuestionCenter(QuestionPersistence(paths.homeDir))
    val goalService = GoalService()
    val worktreeManager = WorktreeManager(File(paths.homeDir, "worktrees"))
    val checkpointStore = ShadowCheckpointStore(File(paths.homeDir, "checkpoints"))
    val ptySessionManager = PtySessionManager()
    val miniappStore = MiniappStore(File(paths.homeDir, "miniapps"))
    val inboxStore = AutomationInboxStore(File(paths.homeDir, "inbox.json"))
    val planFirstSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var cachedDeviceId: String? = null

    private val memoryPrefsKvStore = JsonFileKeyValueStore(paths.memoryPreferencesFile)
    val memoryPreferencesStore = MemoryPreferencesStore(
        store = memoryPrefsKvStore,
        onPauseModeChanged = { paused -> memoryAgentService.onPauseModeChanged(paused) },
        onInjectionBudgetChanged = { memoryStore.invalidateContextCache() },
    )
    val memoryStore: MemoryStore = MemoryStore(
        rootDir = paths.memoryDir,
        injectionBudgetProvider = { memoryPreferencesStore.read().injectionBudget() },
        deviceIdProvider = { cachedDeviceId },
    )
    private val memoryModelRouter = MemoryModelRouter(
        providers = providerCatalog,
        modelSettings = settingsStore,
        memorySettings = memoryPreferencesStore,
        configuredProviders = ::configuredProviderIds,
    )
    private val memoryGateway = CatalogMemoryAgentGateway(providerCatalog, memoryModelRouter)
    private val consolidationCoordinator = DeferredCoordinator { relayMemorySync }
    val memoryAgentService: MemoryAgentService = MemoryAgentService(
        store = memoryStore,
        gateway = memoryGateway,
        scope = scope,
        isSessionExcluded = ::isSessionMemoryDisabled,
        isPaused = { memoryPreferencesStore.read().mode == MemoryModelMode.PAUSED },
        settingsProvider = { memoryPreferencesStore.read() },
        userGuidanceProvider = ::readMemoryGuidance,
        consolidationCoordinator = consolidationCoordinator,
    )

    private val relayKvStore = JsonFileKeyValueStore(paths.relayFile)
    val relayStore: RelayStore = RelayStore(
        store = relayKvStore,
        secrets = secretStore,
    )
    val relayClient: RelayClient = RelayClient(
        platform = AppBrand.RELAY_PLATFORM_CLI,
        appVersion = AppBrand.VERSION,
    )
    val relayController: RelayController = RelayController(
        store = relayStore,
        enroller = relayClient,
        scope = scope,
    )

    val remoteWorkspaceRoot: File
        get() = RemoteWorkspaces.remoteRoot(paths.homeDir)

    private val _remoteBots = MutableStateFlow<List<RemoteBot>>(emptyList())
    val remoteBots: StateFlow<List<RemoteBot>> = _remoteBots.asStateFlow()

    private val _superThreadEventsBridge = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 256)
    val superThreadEventsFlow: SharedFlow<RuntimeEvent> = _superThreadEventsBridge.asSharedFlow()

    private val _workerProgressBridge = MutableSharedFlow<io.github.whywood.superpi.runtime.WorkerProgressEvent>(extraBufferCapacity = 256)
    val workerProgressFlow: SharedFlow<io.github.whywood.superpi.runtime.WorkerProgressEvent> = _workerProgressBridge.asSharedFlow()

    private val _relayDevicesChangedBridge = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val relayDevicesChangedFlow: SharedFlow<String> = _relayDevicesChangedBridge.asSharedFlow()

    private val _passiveRemoteEventsBridge = MutableSharedFlow<io.github.whywood.superpi.runtime.SessionRuntimeEvent>(extraBufferCapacity = 256)
    val passiveRemoteEventsFlow: SharedFlow<io.github.whywood.superpi.runtime.SessionRuntimeEvent> = _passiveRemoteEventsBridge.asSharedFlow()

    suspend fun emitSessionActivity(sessionId: String, runId: String, activity: io.github.whywood.superpi.runtime.RuntimeActivity?) {
        _passiveRemoteEventsBridge.emit(
            io.github.whywood.superpi.runtime.SessionRuntimeEvent(
                sessionId = sessionId,
                event = RuntimeEvent.ActivityChanged(runId = runId, activity = activity),
            ),
        )
    }

    val autoRetryController: HostAutoRetryController by lazy {
        HostAutoRetryController(
            scope = scope,
            isEnabled = { settingsStore.current().autoRetry },
            isBusy = { sid -> sid in runtimeHost.busySessionIds.value },
            awaitIdle = { sid -> runtimeHost.busySessionIds.first { sid !in it } },
            emitRetrying = { sid, runId, attempt ->
                emitSessionActivity(sid, runId, io.github.whywood.superpi.runtime.RuntimeActivity.Retrying(attempt))
            },
            retryTurn = { sid -> runtimeHost.retryLastTurn(sid) },
        )
    }

    private val autoRetryCollectorStarted = AtomicBoolean(false)

    fun ensureAutoRetryStarted() {
        if (!autoRetryCollectorStarted.compareAndSet(false, true)) return
        scope.launch {
            runtimeHost.events.collect { evt ->
                if (evt.event is RuntimeEvent.RunFinished ||
                    evt.event is RuntimeEvent.RunFailed ||
                    evt.event is RuntimeEvent.SessionRemoved
                ) {
                    planFirstSessions.remove(evt.sessionId)
                }
                autoRetryController.onRuntimeEvent(evt.sessionId, evt.event)
            }
        }
    }

    suspend fun emitSessionError(sessionId: String, message: String) {
        _passiveRemoteEventsBridge.emit(
            io.github.whywood.superpi.runtime.SessionRuntimeEvent(
                sessionId = sessionId,
                event = RuntimeEvent.RunFailed(runId = "host-${System.currentTimeMillis()}", message = message),
            ),
        )
    }

    suspend fun ensureConfiguredDefaultModel(preferredProviderId: String? = null) {
        val configured = configuredProviderIds()
        if (configured.isEmpty()) return
        val current = settingsStore.current()
        val currentValid = current.defaultProvider in configured &&
            providerCatalog.models(current.defaultProvider).any { it.id == current.defaultModel }
        if (currentValid) return
        val targetProvider = preferredProviderId
            ?.takeIf { it in configured && providerCatalog.models(it).isNotEmpty() }
            ?: configured.sorted().firstOrNull { providerCatalog.models(it).isNotEmpty() }
            ?: return
        val targetModel = providerCatalog.models(targetProvider).firstOrNull() ?: return
        settingsStore.update { cur ->
            cur.copy(
                defaultProvider = targetProvider,
                defaultModel = targetModel.id,
                defaultThinkingLevel = ThinkingLevel.OFF.name,
            )
        }
    }

    private val _superQuestionChangedBridge = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val superQuestionChangedFlow: SharedFlow<String> = _superQuestionChangedBridge.asSharedFlow()

    internal fun isRemoteCwd(cwd: String): Boolean {
        val normalized = runCatching { paths.normalizePath(cwd) }.getOrDefault(cwd)
        val root = runCatching { paths.normalizePath(remoteWorkspaceRoot.absolutePath) }
            .getOrDefault(remoteWorkspaceRoot.absolutePath)
            .trimEnd(File.separatorChar)
        return normalized == root || normalized.startsWith(root + File.separatorChar)
    }

    internal fun resolveRemoteTargetForCwd(cwd: String) = remoteTargetFor(cwd)

    val remoteTransport: RemoteAgentTransport by lazy {
        RelayAgentTransport(relayLive, scope)
    }
    val remoteBotAdminClient: RemoteBotAdminClient by lazy {
        RemoteBotAdminClient(remoteTransport)
    }
    val remoteBotControlClient: RemoteBotControlClient by lazy {
        RemoteBotControlClient(remoteTransport)
    }
    val remoteFileClient: RemoteFileClient by lazy {
        RemoteFileClient(remoteTransport)
    }
    val remoteArtifactClient: RemoteArtifactClient by lazy {
        RemoteArtifactClient(
            transport = remoteTransport,
            cacheRootForEviction = remoteWorkspaceRoot,
        )
    }
    private val remoteWorkerClientLazy = lazy {
        RemoteWorkerClient(
            transport = remoteTransport,
            taskStore = superTaskStore,
            artifactClient = remoteArtifactClient,
            remoteCacheRoot = File(paths.superDir, "remote-cache"),
            scope = scope,
            settingsProvider = { settingsStore.current().superMode.toRuntime() },
        )
    }
    val remoteWorkerClient: RemoteWorkerClient by remoteWorkerClientLazy

    private val superTaskStoreLazy = lazy {
        SuperTaskStore(paths.superDir)
    }
    val superTaskStore: SuperTaskStore by superTaskStoreLazy

    private val runtimeHostLazy = lazy {
        AgentRuntimeHost(
            homeDir = paths.defaultWorkspaceDir,
            workspacesDir = paths.workspacesDir,
            engineFactory = { cwd ->
                val remoteTarget = remoteTargetFor(cwd.absolutePath)
                if (remoteTarget != null) {
                    RemoteSessionEngine(
                        workspace = cwd,
                        agentDir = paths.homeDir,
                        target = remoteTarget,
                        transport = remoteTransport,
                        scope = scope,
                        questionCenter = questionCenter,
                        onPassiveRuntimeEvent = { evt -> _passiveRemoteEventsBridge.emit(evt) },
                    )
                } else if (isRemoteCwd(cwd.absolutePath)) {
                    throw IllegalStateException("Remote bot is currently offline or unavailable.")
                } else {
                    buildLocalEngine(cwd)
                }
            },
            modelConfigStore = settingsStore,
            catalog = sessionCatalog,
            workspaceStore = botRegistry.workspaceStore,
            providers = providerCatalog,
            backgroundScope = scope,
            goalDriver = goalService,
            titleNamer = PcSessionTitleNamer(providerCatalog, ::configuredProviderIds),
            sessionCleaner = { sessionId ->
                if (jobRegistryLazy.isInitialized()) jobRegistry.clearSession(sessionId)
                scope.launch { goalService.clearSession(sessionId) }
            },
            sessionArchived = { sessionId ->
                if (jobRegistryLazy.isInitialized()) jobRegistry.clearSession(sessionId)
            },
            runLedger = RunLedger(paths.tasksDir),
            keepHandleAlive = { sessionId ->
                (timerServiceLazy.isInitialized() && timerService.timers.value.any { it.sessionId == sessionId && it.enabled && it.nextRunAt != null }) ||
                    goalService.goals.value[sessionId]?.goal?.phase == GoalPhase.ACTIVE ||
                    (jobRegistryLazy.isInitialized() && jobRegistry.jobs.value.any { it.sessionId == sessionId && it.status == JobStatus.RUNNING }) ||
                    questionCenter.pending.value.any { it.sessionId == sessionId } ||
                    io.github.whywood.superpi.remote.RemoteSessionRegistry.isWatching(sessionId)
            },
            remoteWorkspaces = {
                RemoteWorkspaces.workspaces(remoteWorkspaceRoot, _remoteBots.value)
            },
            sessionMemoryExclusionChanged = { sessionId, excluded ->
                scope.launch {
                    val branch = if (!excluded) runtimeHost.sessionBranch(sessionId) else null
                    memoryAgentService.onSessionExclusionChanged(
                        sessionId = sessionId,
                        excluded = excluded,
                        sessionFile = branch?.first,
                        entries = branch?.second,
                    )
                }
            },
            extraVisibleCwds = {
                worktreeManager.listAll().map { it.worktreePath }
            },
        )
    }
    val runtimeHost: AgentRuntimeHost by runtimeHostLazy

    private val jobRegistryLazy = lazy {
        JobRegistry(
            scope = scope,
            notifier = { sessionId, notice, prompt ->
                scope.launch {
                    runCatching { runtimeHost.deliverBackgroundTurn(sessionId, notice, prompt, TurnSource.JOB) }
                }
            },
            wakeEnabled = { settingsStore.current().backgroundJobWake },
            runnerFactory = { cwd -> PcJobRunner(hostEnvironmentProvider, cwd) },
            maxRunningPerSession = { 6 },
        )
    }
    val jobRegistry: JobRegistry by jobRegistryLazy

    private val workerJobRegistryLazy = lazy {
        JobRegistry(
            scope = scope,
            notifier = { sessionId, _, prompt ->
                scope.launch {
                    localWorkerPool.deliverJobCompletion(sessionId, prompt)
                }
            },
            wakeEnabled = { settingsStore.current().superMode.workerJobWake },
            runnerFactory = { cwd -> PcJobRunner(hostEnvironmentProvider, cwd) },
            maxRunningPerSession = { 6 },
        )
    }
    private val workerJobRegistry: JobRegistry by workerJobRegistryLazy

    private val timerServiceLazy = lazy {
        TimerService(
            runtime = runtimeHost,
            store = TimerStore(TimerStore.prepareDataDirectory(paths.timersDir)),
            scope = scope,
            intentsFile = File(paths.timersDir, "delivery-intents.json"),
        )
    }
    val timerService: TimerService by timerServiceLazy

    private val appToolContext: AppToolContext by lazy {
        AppToolContext(
            questionCenter = questionCenter,
            extraToolFactories = listOf({ _, binding -> builtInTools(binding) }),
        )
    }

    private val localWorkerPoolLazy = lazy {
        LocalWorkerPool(
            workersRoot = paths.superWorkersDir,
            taskStore = superTaskStore,
            sessionFactory = { spec, workerId, workerWorkspaceDir, sessionFile, approvalCenter ->
                val resolved = botRegistry.resolveForCwd(spec.cwd)
                val disabledExt = settingsStore.current().disabledExtensions.toSet()
                val workerMemoryScope = if (resolved.policy.effectiveMemoryScope() == MemoryAccessScope.OFF) {
                    MemoryAccessScope.OFF
                } else {
                    MemoryAccessScope.WORK_ONLY
                }
                val toolContext = workerToolContext(
                    botCwd = spec.cwd.absolutePath,
                    workerMemoryKey = "worker:${spec.botId}:$workerId",
                    approvalCenter = approvalCenter,
                    workerId = workerId,
                    workerSessionFile = sessionFile,
                    memoryScope = workerMemoryScope,
                    disabledExtensions = disabledExt,
                )
                val exclusions = exclusionsForCwd(spec.cwd.absolutePath, resolved.policy, disabledExt)
                val engine = PiSessionEngine(
                    workspace = workerWorkspaceDir,
                    agentDir = paths.homeDir,
                    credentials = credentials,
                    transport = httpTransport,
                    hostEnvironment = hostEnvironmentProvider,
                    providers = providerCatalog,
                    personalization = { currentPersonalization() },
                    sharedStorage = { true },
                    excludedSkills = exclusions.skills,
                    excludedMcpServers = exclusions.mcpServers,
                    toolContext = toolContext,
                    supportsChatDelivery = true,
                    memory = memoryStore,
                    memoryScope = { workerMemoryScope },
                    compactionSettingsProvider = { currentWorkerCompactionSettings() },
                    promptResourceCwd = spec.cwd.absolutePath.toPath(),
                    captureMemoryOnClose = false,
                    extraSkillDirs = externalSkillDirs,
                    toolInterceptor = { tool, binding ->
                        ToolPermissionGuard.wrapTool(
                            tool = tool,
                            binding = binding,
                            workspaceRoot = workerWorkspaceDir,
                            modeProvider = { permissionModeForBot(spec.botId) },
                            questionCenter = approvalCenter,
                        )
                    },
                )
                engine.create(file = sessionFile.absolutePath, config = spec.defaultModel)
            },
            resolveBotSpec = { botId ->
                val workspace = botRegistry.listAllBots().firstOrNull { it.id == botId } ?: return@LocalWorkerPool null
                val defaultModel = workspace.defaultModel ?: settingsStore.read()
                LocalWorkerBotSpec(
                    botId = workspace.id,
                    name = workspace.name,
                    avatar = workspace.avatar,
                    cwd = File(workspace.cwd),
                    defaultModel = defaultModel,
                )
            },
            providers = providerCatalog,
            onWorkerSessionCleanup = { sessionId ->
                if (workerJobRegistryLazy.isInitialized()) workerJobRegistry.clearSession(sessionId)
            },
            settingsProvider = { settingsStore.current().superMode.toRuntime() },
            hasRunningJobs = { sid ->
                workerJobRegistryLazy.isInitialized() &&
                    workerJobRegistry.jobs.value.any { it.sessionId == sid && it.status == JobStatus.RUNNING }
            },
            onWorkerDeleted = { wid ->
                scope.launch { runCatching { timerService.deleteBySession(SuperRuntime.workerSessionId(wid)) } }
            },
            inspectExtrasProvider = ::buildWorkerInspectExtras,
            scope = scope,
            deviceNameProvider = { settingsStore.current().deviceName },
        )
    }
    val localWorkerPool: LocalWorkerPool by localWorkerPoolLazy

    private val superOrchestratorLazy = lazy {
        SuperOrchestrator(
            taskStore = superTaskStore,
            localWorkerPool = localWorkerPool,
            remoteWorkerGateway = remoteWorkerClient,
            localBotsProvider = ::buildLocalBlueprints,
            remoteBotsProvider = ::buildRemoteBlueprints,
            timerService = { timerService },
            imageNormalizer = ::normalizeImageFile,
            scope = scope,
            settingsProvider = { settingsStore.current().superMode.toRuntime() },
            threadEngineFactory = { gateway, cardExists, artifactResolver ->
                SuperThreadEngine(
                    superDir = paths.superDir,
                    providers = providerCatalog,
                    initialModelConfig = { settingsStore.read() },
                    sessionFactory = SuperThreadEngine.defaultFactory(
                        agentDir = paths.homeDir,
                        credentials = credentials,
                        transport = httpTransport,
                        providers = providerCatalog,
                        personalization = { currentPersonalization() },
                        memoryStore = memoryStore,
                        timerService = { timerService },
                        miniappStore = miniappStore,
                        extraTools = {
                            val disabled = settingsStore.current().disabledExtensions.toSet()
                            if ("builtin:web" in disabled) emptyList() else webTools(webProvider)
                        },
                        workerGateway = gateway,
                        cardExists = cardExists,
                        artifactResolver = artifactResolver,
                        compactionSettingsProvider = { currentWorkerCompactionSettings() },
                        hostEnvironment = hostEnvironmentProvider,
                        sharedStorage = { true },
                        timerFactory = { service, binding ->
                            val disabled = settingsStore.current().disabledExtensions.toSet()
                            if ("builtin:planning" in disabled) {
                                emptyList()
                            } else {
                                timerTools(
                                    service = service,
                                    binding = binding,
                                    scope = TimerToolScope.SUPER_AND_WORKERS,
                                    targetResolver = { targetWorkerId ->
                                        val rec = superTaskStore.currentWorker(targetWorkerId) ?: return@timerTools null
                                        val file = localWorkerPool.workerSessionFile(rec.botId, rec.workerId)
                                        val cwd = localWorkerPool.workerWorkspaceDirFor(rec.workerId)?.absolutePath
                                            ?: File(paths.superDir, "router/workspace").absolutePath
                                        BoundSession(
                                            sessionId = SuperRuntime.workerSessionId(rec.workerId),
                                            sessionFile = file.absolutePath,
                                            cwd = cwd,
                                        )
                                    },
                                )
                            }
                        },
                        memoryAgent = memoryAgentService,
                        hostPlatformLabel = "${platform.profile.osName} Host",
                    ),
                    memoryStore = memoryStore,
                    memoryAgent = memoryAgentService,
                    scope = scope,
                    idleDebounceMsProvider = {
                        memoryPreferencesStore.read().hotIdleDebounceSeconds.coerceIn(15, 600) * 1_000L
                    },
                    maxSlotsProvider = { settingsStore.current().superMode.workerMaxSlotsPerBot.coerceIn(1, 16) },
                    localDeviceLabel = settingsStore.current().deviceName,
                )
            },
        ).also { orch ->
            scope.launch {
                orch.superThreadEvents.collect { evt -> _superThreadEventsBridge.emit(evt) }
            }
            scope.launch {
                val lastRecordedWorkerTurn = ConcurrentHashMap<String, Long>()
                orch.workerProgress.collect { evt ->
                    _workerProgressBridge.emit(evt)
                    if (evt is WorkerProgressEvent.StatusChanged) {
                        val rec = superTaskStore.currentWorker(evt.workerId)
                        val isTerminalSuccess =
                            evt.status == WorkerStatus.IDLE && rec?.lastOutcome == WorkerOutcome.SUCCEEDED
                        val isTerminalFailure = evt.status == WorkerStatus.FAILED
                        if (rec != null && (isTerminalSuccess || isTerminalFailure)) {
                            val stamp = rec.updatedAt
                            val prev = lastRecordedWorkerTurn.put(rec.workerId, stamp)
                            if (prev != stamp) {
                                inboxStore.record(
                                    source = "worker",
                                    title = rec.title.ifBlank { rec.botName },
                                    summary = rec.lastSummary?.takeIf { it.isNotBlank() }
                                        ?: rec.lastError?.takeIf { it.isNotBlank() }
                                        ?: evt.activeStepText?.takeIf { it.isNotBlank() }
                                        ?: evt.status.name.lowercase(),
                                    botId = rec.botId,
                                    workerId = rec.workerId,
                                )
                            }
                        }
                    }
                }
            }
            scope.launch {
                var lastQid: String? = orch.threadState.value.pendingQuestion?.requestId
                orch.threadState.collect { st ->
                    val nextQid = st.pendingQuestion?.requestId
                    if (nextQid != lastQid) {
                        lastQid = nextQid
                        _superQuestionChangedBridge.emit(nextQid ?: "cleared")
                    }
                }
            }
        }
    }
    val superOrchestrator: SuperOrchestrator by superOrchestratorLazy
    val isSuperOrchestratorInitialized: Boolean get() = superOrchestratorLazy.isInitialized()
    val isRemoteWorkerClientInitialized: Boolean get() = remoteWorkerClientLazy.isInitialized()

    private val relayMemorySyncLazy = lazy {
        val client = RelayMemoryClient(tokenProvider = relayStore::token)
        RelayMemorySync(
            scope = scope,
            accounts = relayStore,
            memory = memoryStore,
            client = client,
            relayCapabilities = {
                if (relayLiveLazy.isInitialized()) relayLive.relayCapabilities.value.toSet() else emptySet()
            },
            onConsolidationTrigger = { memoryAgentService.launchConsolidationIfDue() },
        )
    }
    val relayMemorySync: RelayMemorySync by relayMemorySyncLazy

    private val relayLiveLazy = lazy {
        RelayLive(
            scope = scope,
            accounts = relayStore,
            host = runtimeHost,
            appVersion = AppBrand.VERSION,
            questionCenter = questionCenter,
            avatarStorageDir = paths.botAvatarsDir,
            providerCatalog = { providerCatalog },
            configuredProviderIds = ::configuredProviderIds,
            agentDocStore = { botRegistry.agentDocStore },
            workspaceToolPolicies = { botRegistry.policyStore },
            installedCapabilities = { cwd ->
                val disabled = settingsStore.current().disabledExtensions.toSet()
                val ext = extensionRegistry.snapshot(cwd)
                val skills = ext.skills.map { it.name }.filterNot { "skill:$it" in disabled }.sorted()
                val mcpServers = ext.mcp.servers
                    .filterValues { it.enabled }
                    .keys
                    .filterNot { "mcp:$it" in disabled }
                    .sorted()
                RemoteCapabilitiesInventory(
                    skills = skills,
                    plugins = emptyList(),
                    mcpServers = mcpServers,
                    screenToolsReady = false,
                )
            },
            slashCommands = { cwd ->
                val resolvedCwd = cwd.ifBlank { paths.defaultWorkspaceDir.absolutePath }
                val disabled = settingsStore.current().disabledExtensions.toSet()
                val ext = extensionRegistry.snapshot(resolvedCwd)
                buildList {
                    add(RemoteCommandSpec("compact", "Compact conversation", "builtin"))
                    ext.skills.filterNot { "skill:${it.name}" in disabled }.forEach { skill ->
                        add(RemoteCommandSpec("skill:${skill.name}", skill.description, "skill"))
                    }
                    ext.prompts.filterNot { "prompt:${it.name}" in disabled }.forEach { prompt ->
                        add(RemoteCommandSpec(prompt.name, prompt.description, "prompt"))
                    }
                }
            },
            expandSkillPrompt = ::expandPromptCommand,
            localWorkerPool = localWorkerPool,
            onRemoteMemoryChanged = { version -> relayMemorySync.onRemoteVersionHint(version) },
            platform = AppBrand.RELAY_PLATFORM_CLI,
        ).also { live ->
            scope.launch {
                live.remoteBots.collect { bots ->
                    _remoteBots.value = bots
                    _relayDevicesChangedBridge.tryEmit("bots")
                    if (superOrchestratorLazy.isInitialized()) {
                        runCatching { superOrchestrator.refreshBlueprints() }
                    }
                }
            }
            scope.launch {
                live.devices.collect {
                    _relayDevicesChangedBridge.tryEmit("devices")
                }
            }
            scope.launch {
                live.state.collect { state ->
                    _relayDevicesChangedBridge.tryEmit("state:${state.name.lowercase()}")
                }
            }
            scope.launch {
                live.syncEvents.collect { evt ->
                    _relayDevicesChangedBridge.tryEmit("sync:${evt.kind}:${evt.botId}:${evt.sessionId.orEmpty()}")
                }
            }
        }
    }
    val relayLive: RelayLive by relayLiveLazy
    val isRelayLiveInitialized: Boolean get() = relayLiveLazy.isInitialized()
    val isRelayMemorySyncInitialized: Boolean get() = relayMemorySyncLazy.isInitialized()

    private val bootstrapped = AtomicBoolean(false)
    private val daemonStarted = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val idleDebounceJobs = ConcurrentHashMap<String, Job>()
    private var memoryEventJob: Job? = null
    private var consolidationTickerJob: Job? = null

    suspend fun bootstrap() {
        if (!bootstrapped.compareAndSet(false, true)) return
        paths.ensureDirectories()
        settingsStore.ensureInitialized()
        botRegistry.ensureDefaultHomeBot()
        credentials.preloadAll()
        ensureConfiguredDefaultModel()
        cachedDeviceId = relayStore.read()?.deviceId ?: installationId.get()
        scope.launch {
            relayStore.account.collect { acc ->
                cachedDeviceId = acc?.deviceId ?: installationId.get()
            }
        }
        memoryStore.ensure()
        runtimeHost.ensureStarted()
        extensionRegistry.refreshStaticCatalogs()
        startMemoryObservers()
    }

    suspend fun startDaemonServices() {
        bootstrap()
        if (!daemonStarted.compareAndSet(false, true)) return
        if (!relayKvStore.readAll().containsKey("allowRemote")) {
            relayStore.setAllowRemote(true)
        }
        superOrchestrator.bindTimerHandler()
        timerService.start()
        scope.launch {
            val seenDelivered = ConcurrentHashMap<String, Long>()
            timerService.timers.value.forEach { t ->
                t.lastDeliveredAt?.let { seenDelivered[t.id] = it }
            }
            timerService.timers.collect { list ->
                for (t in list) {
                    val delivered = t.lastDeliveredAt ?: continue
                    val prev = seenDelivered.put(t.id, delivered)
                    if (prev != null && delivered > prev || (prev == null && delivered > System.currentTimeMillis() - 30_000L)) {
                        inboxStore.record(
                            source = "timer",
                            title = t.name,
                            summary = t.prompt.take(240),
                            sessionId = t.sessionId,
                        )
                    }
                }
            }
        }
        relayLive.start()
        relayMemorySync.start()
        consolidationTickerJob = scope.launch {
            while (isActive) {
                val hours = memoryPreferencesStore.read().coldConsolidationHours.coerceIn(0, 168)
                if (hours > 0) {
                    memoryAgentService.launchConsolidationIfDue()
                }
                ptySessionManager.pruneIdleAndDead()
                delay(5 * 60_000L)
            }
        }
    }

    suspend fun reloadAll() {
        settingsStore.reload()
        botRegistry.reload()
        relayKvStore.reload()
        sessionMetaKvStore.reload()
        memoryPrefsKvStore.reload()
        (secretStore as? EncryptedFileSecretStore)?.reload()
        customProviderStore.reload()
        extensionRegistry.reload()
        providerCatalog.updateCustomProviders(customProviderStore.entries.value.map { it.toProviderConfig() })
        credentials.preloadAll()
        ensureConfiguredDefaultModel()
        cachedDeviceId = relayStore.read()?.deviceId ?: installationId.get()
        if (runtimeHostLazy.isInitialized()) {
            runtimeHost.reconfigure()
        }
        if (relayLiveLazy.isInitialized()) {
            relayLive.refreshBots()
        }
        if (superOrchestratorLazy.isInitialized()) {
            runCatching { superOrchestrator.refreshBlueprints() }
        }
    }

    fun configuredProviderIds(): Set<String> {
        val stored = credentials.authKinds.value.keys
        val envSummaryDetected = providerCatalog.summaries.mapNotNull { summary ->
            val fallback = HostSecretEnv.envSecretFallback("api_key_", summary.id)
            summary.id.takeIf { !fallback.isNullOrBlank() }
        }.toSet()
        val envDynamicDetected = HostSecretEnv.envSecretEntries("api_key_").keys
        val custom = customProviderStore.entries.value.map { it.id }.toSet()
        return stored + envSummaryDetected + envDynamicDetected + custom
    }

    suspend fun expandPromptCommand(cwd: String, rawText: String): String {
        val text = rawText.trim()
        if (!text.startsWith("/")) return rawText
        val body = text.removePrefix("/").trim()
        val space = body.indexOf(' ')
        val commandName = if (space < 0) body else body.substring(0, space)
        val args = if (space < 0) "" else body.substring(space + 1).trim()
        if (commandName.startsWith("skill:")) {
            val skillName = commandName.removePrefix("skill:")
            val expansion = skillCatalog.expand(cwd.ifBlank { paths.defaultWorkspaceDir.absolutePath }, skillName, args)
            if (expansion != null) return expansion.prompt
        }
        extensionRegistry.findPromptTemplate(commandName)?.let { template ->
            return template.render(args)
        }
        return rawText
    }

    suspend fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        runCatching { consolidationTickerJob?.cancel() }
        runCatching { memoryEventJob?.cancel() }
        idleDebounceJobs.values.forEach { runCatching { it.cancel() } }
        idleDebounceJobs.clear()
        if (relayMemorySyncLazy.isInitialized()) runCatching { relayMemorySync.stop() }
        if (relayLiveLazy.isInitialized()) runCatching { relayLive.stop() }
        if (timerServiceLazy.isInitialized()) runCatching { timerService.stop() }
        if (jobRegistryLazy.isInitialized()) {
            runCatching {
                jobRegistry.jobs.value.map { it.sessionId }.distinct().forEach { jobRegistry.clearSession(it) }
            }
        }
        if (workerJobRegistryLazy.isInitialized()) {
            runCatching {
                workerJobRegistry.jobs.value.map { it.sessionId }.distinct().forEach { workerJobRegistry.clearSession(it) }
            }
        }
        if (superOrchestratorLazy.isInitialized()) {
            runCatching { superOrchestrator.abortSuper() }
        }
        if (runtimeHostLazy.isInitialized()) {
            runCatching {
                runtimeHost.busySessionIds.value.forEach { sid -> runtimeHost.stop(sid) }
            }
            runCatching { runtimeHost.closeAllSessions() }
        }
        runCatching { ptySessionManager.shutdownAll() }
        platform.cleanupAllChildProcesses()
        scope.cancel()
        runCatching { memoryStore.close() }
        if (superTaskStoreLazy.isInitialized()) runCatching { superTaskStore.close() }
        runCatching {
            httpClient.dispatcher.executorService.shutdownNow()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun currentPersonalization(): Personalization {
        val s = settingsStore.current()
        return Personalization(
            nickname = s.userNickname.ifBlank { s.deviceName },
            instruction = s.customSystemInstruction,
        )
    }

    fun superRouterWorkspaceDir(): File = File(paths.superDir, "router/workspace")

    fun permissionModeForBot(botId: String?): PermissionMode {
        val raw = botId?.let { settingsStore.current().botPermissionModes[it] }
        return PermissionMode.fromWire(raw)
    }

    private suspend fun buildLocalEngine(cwd: File): PiSessionEngine {
        val resolved = botRegistry.resolveForCwd(cwd)
        val botIdForPermission = resolved.workspace?.id
            ?: worktreeManager.listAll()
                .firstOrNull { paths.normalizePath(it.worktreePath) == paths.normalizePath(cwd.absolutePath) }
                ?.botId
        val disabledExt = settingsStore.current().disabledExtensions.toSet()
        val exclusions = exclusionsForCwd(cwd.absolutePath, resolved.policy, disabledExt)
        return PiSessionEngine(
            workspace = cwd,
            agentDir = paths.homeDir,
            credentials = credentials,
            transport = httpTransport,
            hostEnvironment = hostEnvironmentProvider,
            providers = providerCatalog,
            personalization = { currentPersonalization() },
            sharedStorage = { true },
            excludedSkills = exclusions.skills,
            excludedMcpServers = exclusions.mcpServers,
            toolContext = appToolContext,
            memory = memoryStore,
            memoryScope = { resolved.policy.effectiveMemoryScope() },
            awaitMemoryReady = { memoryAgentService.awaitHighPriority(1_500L) },
            hooks = RuntimeHooks(
                onSessionBound = {
                    memoryAgentService.launchConsolidationIfDue()
                    memoryAgentService.launchRetryPending()
                },
            ),
            compactionSettingsProvider = {
                CompactionSettings(enabled = true, triggerRatio = settingsStore.current().compactionTriggerPercent / 100.0)
            },
            promptResourceCwd = cwd.absolutePath.toPath(),
            extraSkillDirs = externalSkillDirs,
            toolInterceptor = { tool, binding ->
                ToolPermissionGuard.wrapTool(
                    tool = tool,
                    binding = binding,
                    workspaceRoot = cwd,
                    modeProvider = { permissionModeForBot(botIdForPermission) },
                    questionCenter = questionCenter,
                    planFirstCheck = { sid -> sid in planFirstSessions },
                )
            },
        )
    }

    private fun builtInTools(binding: SessionBinding): List<CodingTool> = buildList {
        add(
            presentTool(
                miniappLookup = miniappLookup(miniappStore),
                session = { binding.current() },
                cardExists = { cardId -> binding.hasCardToolResult(cardId) },
            ),
        )
        add(
            todoWriteTool(
                sessionId = { binding.current()?.sessionId },
                post = { sid, type, payload, text ->
                    runtimeHost.postPluginMessage(sid, type, payload, text, display = true)
                },
            ),
        )
        val disabled = settingsStore.current().disabledExtensions.toSet()
        if ("builtin:web" !in disabled) {
            addAll(webTools(webProvider))
        }
        if ("builtin:planning" !in disabled) {
            addAll(goalTools(binding, goalService))
            addAll(jobTools(binding, jobRegistry))
            addAll(timerTools(timerService, binding))
        }
        if ("builtin:memory" !in disabled) {
            addAll(
                memoryTools(
                    store = memoryStore,
                    agentService = memoryAgentService,
                    scope = {
                        botRegistry.resolvePolicySync(binding.current()?.cwd).first.effectiveMemoryScope()
                    },
                    allowDispatch = {
                        val (policy, isHome) = botRegistry.resolvePolicySync(binding.current()?.cwd)
                        policy.effectiveMemoryDispatch(isHome)
                    },
                    sessionId = { binding.current()?.sessionId },
                    isSessionExcluded = ::isSessionMemoryDisabled,
                ),
            )
        }
    }

    private fun workerToolContext(
        botCwd: String,
        workerMemoryKey: String,
        approvalCenter: QuestionCenter,
        workerId: String,
        workerSessionFile: File,
        memoryScope: MemoryAccessScope,
        disabledExtensions: Set<String>,
    ): AppToolContext {
        val workerTimerBinding = SessionBinding(supportsChatDelivery = true).apply {
            bind(
                BoundSession(
                    sessionId = SuperRuntime.workerSessionId(workerId),
                    sessionFile = workerSessionFile.absolutePath,
                    cwd = botCwd,
                ),
            )
        }
        return AppToolContext(
            questionCenter = approvalCenter,
            extraToolFactories = listOf(
                { _, binding ->
                    buildList {
                        add(
                            presentTool(
                                miniappLookup = miniappLookup(miniappStore),
                                session = { binding.current() },
                                cardExists = { cardId -> binding.hasCardToolResult(cardId) },
                            ),
                        )
                        add(
                            todoWriteTool(
                                sessionId = { binding.current()?.sessionId },
                                post = { sid, type, payload, text ->
                                    localWorkerPool.postCustomMessage(sid, type, payload, text)
                                },
                            ),
                        )
                        if ("builtin:planning" !in disabledExtensions) {
                            addAll(timerTools(timerService, workerTimerBinding))
                            addAll(jobTools(binding, workerJobRegistry))
                        }
                        if ("builtin:web" !in disabledExtensions) {
                            addAll(webTools(webProvider))
                        }
                        if ("builtin:memory" !in disabledExtensions && memoryScope != MemoryAccessScope.OFF) {
                            addAll(
                                memoryTools(
                                    store = memoryStore,
                                    agentService = null,
                                    scope = { memoryScope },
                                    allowDispatch = { false },
                                    sessionId = { workerMemoryKey },
                                    isSessionExcluded = { false },
                                ),
                            )
                        }
                    }
                },
            ),
        )
    }

    fun findRemoteBotByWorkspaceId(workspaceId: String): RemoteBot? =
        _remoteBots.value.firstOrNull { RemoteWorkspaces.workspaceId(it) == workspaceId }

    suspend fun listMergedBots(): List<io.github.whywood.superpi.runtime.RuntimeWorkspace> =
        botRegistry.listAllBots() + RemoteWorkspaces.workspaces(remoteWorkspaceRoot, _remoteBots.value)

    private fun remoteTargetFor(cwd: String): RemoteSessionTarget? {
        val bot = _remoteBots.value.firstOrNull { candidate ->
            RemoteWorkspaces.directory(remoteWorkspaceRoot, candidate).absolutePath == cwd
        } ?: return null
        return RemoteSessionTarget(
            deviceId = bot.deviceId,
            deviceName = bot.deviceName,
            botId = bot.botId,
            botName = bot.botName,
            isOnline = {
                _remoteBots.value.any { cur ->
                    cur.deviceId == bot.deviceId && cur.botId == bot.botId && cur.online
                }
            },
        )
    }

    private suspend fun normalizeImageFile(file: File): ImagePart? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L || file.length() > 10L * 1024L * 1024L) return@withContext null
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> return@withContext null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        ImagePart(
            data = Base64.getEncoder().encodeToString(bytes),
            mimeType = mime,
            path = file.absolutePath,
        )
    }

    private suspend fun buildRemoteBlueprints(): List<io.github.whywood.superpi.runtime.BotBlueprint> {
        superTaskStore.ensure()
        return _remoteBots.value.map { rb ->
            val caps = rb.botCapabilities
            val supportsWorker = "super-worker" in rb.capabilities
            io.github.whywood.superpi.runtime.BotBlueprint(
                ref = SuperRuntime.remoteBotRef(rb.deviceId, rb.botId),
                botId = rb.botId,
                name = rb.botName,
                avatar = rb.avatar,
                intro = rb.intro,
                deviceId = rb.deviceId,
                deviceName = rb.deviceName,
                online = rb.online,
                capabilities = io.github.whywood.superpi.runtime.BotCapabilitiesSummary(
                    skills = caps?.skills.orEmpty(),
                    plugins = caps?.plugins.orEmpty(),
                    mcpServers = caps?.mcpServers.orEmpty(),
                    screenTools = caps?.screenTools ?: false,
                    miniappTools = caps?.miniappTools ?: false,
                    memoryDispatch = caps?.memoryDispatch ?: false,
                    memoryScope = caps?.memoryScope?.let { MemoryAccessScope.fromWire(it) }
                        ?: MemoryAccessScope.PERSONAL,
                ),
                supportsSuperWorker = supportsWorker,
            )
        }
    }

    private suspend fun buildLocalBlueprints(): List<io.github.whywood.superpi.runtime.BotBlueprint> {
        superTaskStore.ensure()
        val bots = botRegistry.listAllBots()
        val whitelist = superTaskStore.whitelist.value
        bots.forEach { ws ->
            val ref = SuperRuntime.localBotRef(ws.id)
            if (ref !in whitelist) {
                superTaskStore.setBotEnabled(ref, true)
            }
        }
        val deviceName = settingsStore.current().deviceName
        val disabledExt = settingsStore.current().disabledExtensions.toSet()
        return bots.map { ws ->
            val resolved = botRegistry.resolveForCwd(File(ws.cwd))
            val ext = extensionRegistry.snapshot(ws.cwd)
            val allSkills = ext.skills.map { it.name }.sorted()
            val allMcp = ext.mcp.servers.filterValues { it.enabled }.keys.sorted()
            val exclusions = exclusionsForCwd(ws.cwd, resolved.policy, disabledExt)
            io.github.whywood.superpi.runtime.BotBlueprint(
                ref = SuperRuntime.localBotRef(ws.id),
                botId = ws.id,
                name = ws.name,
                avatar = ws.avatar,
                intro = ws.intro.orEmpty(),
                deviceId = null,
                deviceName = deviceName,
                online = true,
                capabilities = io.github.whywood.superpi.runtime.BotCapabilitiesSummary(
                    skills = allSkills.filterNot { it in exclusions.skills },
                    plugins = emptyList(),
                    mcpServers = allMcp.filterNot { it in exclusions.mcpServers },
                    screenTools = false,
                    miniappTools = false,
                    memoryDispatch = resolved.policy.effectiveMemoryDispatch(ws.isHome),
                    memoryScope = resolved.policy.effectiveMemoryScope(),
                ),
                supportsSuperWorker = true,
                defaultModel = ws.defaultModel ?: settingsStore.read(),
            )
        }
    }

    private suspend fun buildWorkerInspectExtras(workerId: String, sdkSessionId: String?): WorkerInspectExtras {
        val targetTimerSessionId = SuperRuntime.workerSessionId(workerId)
        val activeTimers = runCatching {
            timerService.list()
                .filter { it.sessionId == targetTimerSessionId }
                .map { timer ->
                    WorkerTimerSummary(
                        id = timer.id,
                        name = timer.name,
                        enabled = timer.enabled,
                        nextRunAt = timer.nextRunAt,
                    )
                }
        }.getOrDefault(emptyList())
        val runningJobs = if (!sdkSessionId.isNullOrBlank()) {
            workerJobRegistry.jobs.value
                .filter { it.sessionId == sdkSessionId && it.status == JobStatus.RUNNING }
                .map { job -> WorkerJobSummary(jobId = job.id, command = job.command, status = job.status) }
        } else {
            emptyList()
        }
        return WorkerInspectExtras(activeTimers = activeTimers, runningJobs = runningJobs)
    }

    private data class ResolvedExclusions(
        val skills: Set<String>,
        val mcpServers: Set<String>,
    )

    private suspend fun exclusionsForCwd(
        cwd: String,
        policy: io.github.whywood.superpi.data.WorkspaceToolPolicy,
        disabledExtensions: Set<String>,
    ): ResolvedExclusions {
        val skills = runCatching { skillCatalog.list(cwd).map { it.name }.toSet() }.getOrDefault(emptySet())
        val enabledSkills = policy.enabledSkills(skills).filterNot { "skill:$it" in disabledExtensions }.toSet()
        val mcpServers = runCatching { mcpConfigStore.load().servers.keys }.getOrDefault(emptySet())
        val enabledMcp = policy.enabledMcpServers(mcpServers).filterNot { "mcp:$it" in disabledExtensions }.toSet()
        return ResolvedExclusions(
            skills = skills - enabledSkills,
            mcpServers = mcpServers - enabledMcp,
        )
    }

    private fun currentWorkerCompactionSettings(): CompactionSettings {
        val superCfg = settingsStore.current().superMode
        val ratio = if (superCfg.workerCompactionOverrideEnabled) {
            superCfg.workerCompactionTriggerPercent
        } else {
            settingsStore.current().compactionTriggerPercent
        }
        return CompactionSettings(enabled = true, triggerRatio = ratio / 100.0)
    }

    private suspend fun isSessionMemoryDisabled(sessionId: String): Boolean {
        if (sessionId.startsWith(SuperRuntime.SUPER_THREAD_SESSION_ID) ||
            sessionId.startsWith(SuperRuntime.WORKER_SESSION_PREFIX)
        ) {
            return false
        }
        if (runtimeHost.isSessionMemoryExcluded(sessionId)) return true
        val cwd = runtimeHost.sessionCwd(sessionId) ?: return false
        return botRegistry.policyForCwd(cwd).effectiveMemoryScope() == MemoryAccessScope.OFF
    }

    private suspend fun readMemoryGuidance(): String? = withContext(Dispatchers.IO) {
        val docFile = listOf("AGENTS.override.md", "AGENTS.md")
            .map { File(paths.memoryDir, it) }
            .firstOrNull { it.isFile && it.canRead() }
            ?: return@withContext null
        runCatching { docFile.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }

    private fun startMemoryObservers() {
        memoryEventJob = scope.launch {
            runtimeHost.events.collect { sessionEvent ->
                when (val event = sessionEvent.event) {
                    is RuntimeEvent.PluginCustomMessage -> {
                        val card = runCatching {
                            cardJson.decodeFromString(AnswerCardPayload.serializer(), event.payload)
                        }.getOrNull()
                        if (card != null) {
                            extensionRegistry.emitAnswerCard(card)
                        }
                    }
                    is RuntimeEvent.RunStarted -> idleDebounceJobs.remove(sessionEvent.sessionId)?.cancel()
                    is RuntimeEvent.RunFinished -> {
                        val sid = sessionEvent.sessionId
                        idleDebounceJobs.remove(sid)?.cancel()
                        idleDebounceJobs[sid] = scope.launch {
                            val currentJob = kotlin.coroutines.coroutineContext[Job]
                            try {
                                val debounceMs = memoryPreferencesStore.read()
                                    .hotIdleDebounceSeconds
                                    .coerceIn(15, 600) * 1_000L
                                delay(debounceMs)
                                val branch = runtimeHost.sessionBranch(sid)
                                memoryAgentService.launchIdleSession(
                                    sessionId = sid,
                                    sessionFile = branch?.first,
                                    entries = branch?.second,
                                )
                            } finally {
                                if (currentJob != null) {
                                    idleDebounceJobs.remove(sid, currentJob)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private class DeferredCoordinator(
        private val delegate: () -> MemoryConsolidationCoordinator,
    ) : MemoryConsolidationCoordinator {
        override suspend fun prepareForConsolidation(
            forceManual: Boolean,
            minIntervalMs: Long,
        ): ConsolidationLeaseResult = delegate().prepareForConsolidation(forceManual, minIntervalMs)

        override suspend fun onConsolidationCompleted(leaseId: String?, finishedAt: Long) {
            delegate().onConsolidationCompleted(leaseId, finishedAt)
        }

        override suspend fun onConsolidationFinished(leaseId: String?) {
            delegate().onConsolidationFinished(leaseId)
        }
    }
}
