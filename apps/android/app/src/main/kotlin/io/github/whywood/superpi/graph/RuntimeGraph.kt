package io.github.whywood.superpi.graph

import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.coding.settings.CompactionSettings
import io.github.whywood.superpi.arrange.AndroidArrangeCalendarGateway
import io.github.whywood.superpi.arrange.arrangeTools
import io.github.whywood.superpi.conversation.ConversationStore
import io.github.whywood.superpi.core.SessionTitleService
import io.github.whywood.superpi.data.BundledSkills
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_DEFAULT
import io.github.whywood.superpi.data.ImageNormalizer
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.guessMime
import io.github.whywood.superpi.data.isSharedStorageGranted
import io.github.whywood.superpi.data.toSuperRuntimeSettings
import io.github.whywood.superpi.plugin.host.events.PluginHooksDelegate
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.AppToolContext
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.BotCapabilitiesSummary
import io.github.whywood.superpi.runtime.BoundSession
import io.github.whywood.superpi.runtime.GoalPhase
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.JobRegistry
import io.github.whywood.superpi.runtime.JobSnapshot
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MiniappAgentService
import io.github.whywood.superpi.remote.RemoteSessionEngine
import io.github.whywood.superpi.remote.RemoteSessionRegistry
import io.github.whywood.superpi.runtime.PiSessionEngine
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.SessionEngine
import io.github.whywood.superpi.runtime.SessionCloseReason
import io.github.whywood.superpi.runtime.RunLedger
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import io.github.whywood.superpi.runtime.TimerToolScope
import io.github.whywood.superpi.runtime.WorkerJobSummary
import io.github.whywood.superpi.runtime.WorkerTimerSummary
import io.github.whywood.superpi.runtime.memory.CatalogMemoryAgentGateway
import io.github.whywood.superpi.runtime.memory.ConsolidationLeaseResult
import io.github.whywood.superpi.runtime.memory.MemoryAgentService
import io.github.whywood.superpi.runtime.memory.MemoryConsolidationCoordinator
import io.github.whywood.superpi.runtime.memory.MemoryModelRouter
import io.github.whywood.superpi.runtime.memory.MemoryPreferencesStore
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.memoryTools
import io.github.whywood.superpi.runtime.superagent.LocalWorkerBotSpec
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import io.github.whywood.superpi.runtime.superagent.SuperOrchestrator
import io.github.whywood.superpi.runtime.superagent.SuperTaskStore
import io.github.whywood.superpi.runtime.superagent.SuperThreadEngine
import io.github.whywood.superpi.runtime.superagent.WorkerInspectExtras
import io.github.whywood.superpi.runtime.todoWriteTool
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.RuntimeHooks
import io.github.whywood.superpi.runtime.SessionBinding
import io.github.whywood.superpi.runtime.StorageMountInfo
import io.github.whywood.superpi.runtime.goalTools
import io.github.whywood.superpi.runtime.jobTools
import io.github.whywood.superpi.runtime.miniappDataTool
import io.github.whywood.superpi.runtime.miniappLookup
import io.github.whywood.superpi.runtime.miniappTools
import io.github.whywood.superpi.runtime.presentTool
import io.github.whywood.superpi.runtime.systemShellTool
import io.github.whywood.superpi.runtime.timerTools
import io.github.whywood.superpi.service.LinuxJobRunner
import io.github.whywood.superpi.service.MemoryDreamScheduler
import io.github.whywood.superpi.web.DeepSeekWebProvider
import io.github.whywood.superpi.web.RoutingWebProvider
import io.github.whywood.superpi.web.WebProvider
import io.github.whywood.superpi.web.braveBackend
import io.github.whywood.superpi.web.exaBackend
import io.github.whywood.superpi.web.serperBackend
import io.github.whywood.superpi.web.tavilyBackend
import io.github.whywood.superpi.web.webTools
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import okio.Path.Companion.toPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RuntimeGraph(private val deps: RuntimeGraphDeps) {
    private val webProvider by lazy {
        RoutingWebProvider(
            backends = listOf(
                exaBackend { deps.webSearchKeyProvider("exa") },
                tavilyBackend { deps.webSearchKeyProvider("tavily") },
                braveBackend { deps.webSearchKeyProvider("brave") },
                serperBackend { deps.webSearchKeyProvider("serper") },
            ),
            fallback = DeepSeekWebProvider(deps.apiKeyProvider),
        )
    }

    private val arrangeCalendar by lazy { AndroidArrangeCalendarGateway(deps.appContext) }

    internal val pluginWebProvider: WebProvider get() = webProvider

    internal val pluginArrangeCalendar: io.github.whywood.superpi.arrange.ArrangeCalendarGateway get() = arrangeCalendar

    private val memoryStore: MemoryStore = MemoryStore(
        rootDir = File(deps.agentDir, "memory"),
        injectionBudgetProvider = { memoryPreferencesStore.read().injectionBudget() },
        deviceIdProvider = deps.memoryDeviceIdProvider,
    )
    private val memoryPreferencesStore: MemoryPreferencesStore = MemoryPreferencesStore(
        context = deps.appContext,
        onPauseModeChanged = { paused -> memoryAgentService.onPauseModeChanged(paused) },
        onInjectionBudgetChanged = { memoryStore.invalidateContextCache() },
    )
    private val memoryModelRouter: MemoryModelRouter = MemoryModelRouter(
        providers = deps.providerCatalog,
        modelSettings = deps.modelConfigStore,
        memorySettings = memoryPreferencesStore,
        configuredProviders = deps.configuredProviderIds,
    )
    private val memoryGateway: CatalogMemoryAgentGateway =
        CatalogMemoryAgentGateway(deps.providerCatalog, memoryModelRouter)
    private val memoryAgentService: MemoryAgentService = MemoryAgentService(
        store = memoryStore,
        gateway = memoryGateway,
        scope = deps.scope,
        isSessionExcluded = ::isSessionMemoryDisabled,
        isPaused = { memoryPreferencesStore.read().mode == MemoryModelMode.PAUSED },
        settingsProvider = { memoryPreferencesStore.read() },
        userGuidanceProvider = ::readMemoryAgentGuidance,
        consolidationCoordinator = DeferredMemoryConsolidationCoordinator(deps.memoryConsolidationCoordinator),
    )
    private val memoryDreamScheduler = MemoryDreamScheduler(deps.appContext)
    private val idleDebounceJobs = ConcurrentHashMap<String, Job>()

    val appToolContext: AppToolContext by lazy {
        AppToolContext(
            questionCenter = deps.questionCenter,
            pluginTools = { binding ->
                deps.pluginManager().tools(binding, excludedPluginsFor(binding))
            },
            pluginDeferredTools = { binding ->
                deps.pluginManager().deferredTools(binding, excludedPluginsFor(binding))
            },
            extraToolFactories = listOf({ _, binding -> builtInTools(binding) }),
        )
    }

    private fun builtInTools(binding: SessionBinding): List<CodingTool> = buildList {
        val policy = policyFor(binding)
        add(
            presentTool(
                miniappLookup = miniappLookup(deps.miniappStore),
                session = { binding.current() },
                cardExists = { cardId -> binding.hasCardToolResult(cardId) },
            ),
        )
        if (deps.hostEnvironment.current().system != null) {
            add(systemShellTool { deps.hostEnvironment.current().system })
        }
        add(todoWriteTool(
            sessionId = { binding.current()?.sessionId },
            post = deps.postCustomMessage,
        ))
        addAll(webTools(webProvider))
        addAll(arrangeTools(arrangeCalendar))
        addAll(goalTools(binding, deps.goalService))
        addAll(jobTools(binding, deps.jobRegistry()))
        addAll(timerTools(deps.timerService(), binding))
        if (policy.miniappTools) {
            addAll(miniappTools(deps.miniappStore, binding.supportsChatDelivery))
            add(miniappDataTool(deps.miniappStore))
        }
        addAll(
            memoryTools(
                store = memoryStore,
                agentService = memoryAgentService,
                scope = { policyFor(binding).effectiveMemoryScope() },
                allowDispatch = { policyFor(binding).effectiveMemoryDispatch() },
                sessionId = { binding.current()?.sessionId },
                isSessionExcluded = ::isSessionMemoryDisabled,
            ),
        )
        if (policy.screenTools && deps.hostEnvironment.current().capabilities.hasScreenControl) {
            addAll(deps.screenTools())
            addAll(deps.virtualScreenTools())
        }
    }

    private fun pluginRuntimeHooks(): RuntimeHooks {
        val delegate = PluginHooksDelegate()
        return RuntimeHooks(
            promptTransform = delegate.promptTransform,
            systemContext = delegate.systemContext,
            inputHandler = delegate.inputHandler,
            toolHooks = delegate.toolHooks,
            onSessionBound = { binding ->
                deps.pluginManager().bindSessionHooks(binding, delegate, excludedPluginsFor(binding))
                memoryAgentService.launchConsolidationIfDue()
                memoryAgentService.launchRetryPending()
            },
            onSessionClosed = { sessionId -> deps.pluginManager().onSessionClosed(sessionId) },
        )
    }

    private fun storageMountInfos(): List<StorageMountInfo> =
        deps.storageMountStore.cached().map { mount ->
            StorageMountInfo(name = mount.name, description = mount.description, path = mount.path)
        }

    private fun policyFor(binding: SessionBinding): WorkspaceToolPolicy =
        resolvedPolicy(binding.current()?.cwd.orEmpty())

    private fun resolvedPolicy(cwd: String): WorkspaceToolPolicy {
        val stored = deps.workspaceToolPolicies.policyForCwd(cwd)
        val home = runCatching { File(cwd).canonicalPath == deps.homeDir.canonicalPath }.getOrDefault(false)
        return if (home) stored.copy(allCapabilities = true) else stored
    }

    private fun installedPluginIds(): Set<String> =
        deps.pluginManager().state.value.plugins.mapTo(mutableSetOf()) { it.id }

    private fun excludedPluginsFor(binding: SessionBinding): Set<String> {
        val policy = policyFor(binding)
        return installedPluginIds() - policy.enabledPlugins(installedPluginIds())
    }

    @Volatile
    private var cachedSkills: List<io.github.whywood.superpi.data.InstalledSkill>? = null

    @Volatile
    private var cachedMcpServers: Set<String>? = null

    private suspend fun installedSkills(): List<io.github.whywood.superpi.data.InstalledSkill> {
        val listed = runCatching { deps.skillRepository.list().getOrNull() }.getOrNull()
        if (listed != null) {
            cachedSkills = listed
            return listed
        }
        android.util.Log.w("SuperPIRuntime", "skill list failed; using cached skill exclusions")
        return cachedSkills.orEmpty()
    }

    private suspend fun installedMcpServers(): Set<String> {
        val loaded = runCatching { deps.mcpConfigStore.load().servers.keys }.getOrNull()
        if (loaded != null) {
            cachedMcpServers = loaded
            return loaded
        }
        android.util.Log.w("SuperPIRuntime", "mcp config read failed; using cached mcp exclusions")
        return cachedMcpServers.orEmpty()
    }

    private suspend fun exclusionsFor(cwd: String): BotPolicyExclusions {
        val policy = resolvedPolicy(cwd)
        val installedPlugins = installedPluginIds()
        val excludedPlugins = installedPlugins - policy.enabledPlugins(installedPlugins)
        val skills = installedSkills()
        val allSkillNames = skills.mapTo(mutableSetOf()) { it.name }
        val nameById = skills.associate { it.id to it.name }
        val excludedPluginSkills = excludedPlugins.flatMapTo(mutableSetOf()) { pluginId ->
            deps.pluginManager().skillIds(pluginId).mapTo(mutableSetOf()) { id -> nameById[id] ?: id }
        }
        val enabledSkills = policy.enabledSkills(allSkillNames)
        val excludedBundledSkills = buildSet {
            if (!policy.screenTools || !deps.hostEnvironment.current().capabilities.hasScreenControl) {
                add(BundledSkills.SCREEN_OPS_SKILL)
            }
            if (!policy.miniappTools) {
                add(BundledSkills.MINIAPP_SKILL)
            }
        }
        val mcp = installedMcpServers()
        return BotPolicyExclusions(
            plugins = excludedPlugins,
            skills = (allSkillNames - enabledSkills) + excludedPluginSkills + excludedBundledSkills,
            mcpServers = mcp - policy.enabledMcpServers(mcp),
        )
    }

    private suspend fun homeExcludedPlugins(): Set<String> =
        exclusionsFor(deps.homeDir.absolutePath).plugins

    private suspend fun homeExcludedSkills(): Set<String> =
        exclusionsFor(deps.homeDir.absolutePath).skills

    private suspend fun homeExcludedMcpServers(): Set<String> =
        exclusionsFor(deps.homeDir.absolutePath).mcpServers

    private data class BotPolicyExclusions(
        val plugins: Set<String>,
        val skills: Set<String>,
        val mcpServers: Set<String>,
    )

    private val engineFactory: suspend (File) -> SessionEngine = { cwd ->
        val remote = deps.remoteTarget(cwd.absolutePath)
        if (remote == null && isRemoteCwd(cwd)) {
            error("This bot is not available from its device right now. Connect the device again to continue.")
        }
        if (remote != null) {
            RemoteSessionEngine(
                workspace = cwd,
                agentDir = deps.agentDir,
                target = remote,
                transport = deps.remoteTransport,
                scope = deps.scope,
                questionCenter = deps.questionCenter,
                onPassiveRuntimeEvent = { event -> conversationStore.ingestRemoteEvent(event) },
            )
        } else {
            localEngine(cwd)
        }
    }

    private fun isRemoteCwd(cwd: File): Boolean {
        val root = runCatching { deps.remoteRoot.canonicalPath }.getOrElse { deps.remoteRoot.absolutePath }
        val path = runCatching { cwd.canonicalPath }.getOrElse { cwd.absolutePath }
        return path.startsWith(root + File.separator)
    }

    @Volatile
    private var compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT

    @Volatile
    private var compactionPercentLoaded: Boolean = false

    @Volatile
    private var superSettings: SuperRuntimeSettings = SuperRuntimeSettings()

    @Volatile
    private var superSettingsLoaded: Boolean = false

    fun currentSuperSettings(): SuperRuntimeSettings = superSettings

    val workerJobs: StateFlow<List<JobSnapshot>> get() = workerJobRegistry.jobs

    fun hasRunningWorkerJobs(sessionId: String): Boolean =
        workerJobRegistry.jobs.value.any { it.sessionId == sessionId && it.status == JobStatus.RUNNING }

    fun hasAnyRunningWorkerJobs(): Boolean =
        workerJobRegistry.jobs.value.any { it.status == JobStatus.RUNNING }

    private suspend fun ensurePreferencesLoaded() {
        if (compactionPercentLoaded && superSettingsLoaded) return
        val prefs = deps.appPreferences.read()
        compactionTriggerPercent = prefs.compactionTriggerPercent
        compactionPercentLoaded = true
        superSettings = prefs.toSuperRuntimeSettings()
        superSettingsLoaded = true
    }

    private suspend fun localEngine(cwd: File): PiSessionEngine {
        ensurePreferencesLoaded()
        val exclusions = exclusionsFor(cwd.absolutePath)
        return PiSessionEngine(
            workspace = cwd,
            agentDir = deps.agentDir,
            credentials = deps.credentials,
            transport = deps.httpTransport,
            hostEnvironment = deps.hostEnvironment,
            providers = deps.providerCatalog,
            personalization = { deps.personalizationStore.read().toRuntime() },
            sharedStorage = { deps.linuxSettings.isSharedStorageEnabled() && isSharedStorageGranted() },
            storageMounts = { storageMountInfos() },
            pluginPromptBody = { deps.pluginManager().promptBody(exclusions.plugins) },
            pluginPromptOverrides = { deps.pluginManager().promptOverrides(exclusions.plugins) },
            excludedSkills = exclusions.skills,
            excludedMcpServers = exclusions.mcpServers,
            toolContext = appToolContext,
            memory = memoryStore,
            memoryScope = { resolvedPolicy(cwd.absolutePath).effectiveMemoryScope() },
            awaitMemoryReady = { memoryAgentService.awaitHighPriority(1_500L) },
            hooks = pluginRuntimeHooks(),
            compactionSettingsProvider = {
                CompactionSettings(enabled = true, triggerRatio = compactionTriggerPercent / 100.0)
            },
        )
    }

    val miniappAgentService: MiniappAgentService by lazy {
        MiniappAgentService(
            providers = deps.providerCatalog,
            credentials = deps.credentials,
            transport = deps.httpTransport,
            agentDir = deps.agentDir,
            runRoot = File(deps.appContext.filesDir, "agent-runs"),
            appToolContext = appToolContext,
            defaultConfig = { deps.modelConfigStore.read() },
            configuredProviders = deps.configuredProviderIds,
            personalization = { deps.personalizationStore.read().toRuntime() },
            pluginPromptBody = { deps.pluginManager().promptBody(homeExcludedPlugins()) },
            pluginPromptOverrides = { deps.pluginManager().promptOverrides(homeExcludedPlugins()) },
            excludedSkills = { homeExcludedSkills() },
            excludedMcpServers = { homeExcludedMcpServers() },
            sharedStorage = { deps.linuxSettings.isSharedStorageEnabled() && isSharedStorageGranted() },
            storageMounts = { storageMountInfos() },
            defaultMaxTurns = { 16 },
            workspace = deps.homeDir,
            hostEnvironment = deps.hostEnvironment,
            shellExecutorFor = { workspace ->
                runCatching { deps.hostEnvironment.current().bash(workspace.absolutePath) }.getOrNull()
            },
            backgroundRunnerFactory = { cwd -> LinuxJobRunner(deps.hostEnvironment, cwd) },
            hooksFactory = { pluginRuntimeHooks() },
        )
    }

    val host: AgentRuntimeHost by lazy {
        AgentRuntimeHost(
            homeDir = deps.homeDir,
            workspacesDir = deps.workspacesDir,
            engineFactory = engineFactory,
            modelConfigStore = deps.modelConfigStore,
            catalog = deps.sessionCatalog,
            workspaceStore = deps.workspaceStore,
            providers = deps.providerCatalog,
            backgroundScope = deps.scope,
            goalDriver = deps.goalService,
            titleNamer = SessionTitleService(
                catalog = deps.providerCatalog,
                configuredProviders = deps.configuredProviderIds,
                preferences = deps.appPreferences,
            ),
            sessionCleaner = { sessionId: String ->
                deps.jobRegistry().clearSession(sessionId)
                deps.scope.launch { deps.goalService.clearSession(sessionId) }
            },
            sessionArchived = { sessionId -> deps.jobRegistry().clearSession(sessionId) },
            remoteWorkspaces = { deps.remoteWorkspaces() },
            runLedger = RunLedger(File(deps.appContext.filesDir, "tasks")),
            keepHandleAlive = { sessionId ->
                deps.timerService().timers.value.any { timer ->
                    timer.sessionId == sessionId && timer.enabled && timer.nextRunAt != null
                } ||
                    deps.goalService.goals.value[sessionId]?.goal?.phase == GoalPhase.ACTIVE ||
                    deps.jobRegistry().jobs.value.any { job ->
                        job.sessionId == sessionId && job.status == JobStatus.RUNNING
                    } ||
                    deps.questionCenter.pending.value.any { question -> question.sessionId == sessionId } ||
                    RemoteSessionRegistry.isWatching(sessionId)
            },
            sessionMemoryExclusionChanged = { sessionId, excluded ->
                deps.scope.launch {
                    val branch = if (!excluded) host.sessionBranch(sessionId) else null
                    memoryAgentService.onSessionExclusionChanged(
                        sessionId = sessionId,
                        excluded = excluded,
                        sessionFile = branch?.first,
                        entries = branch?.second,
                    )
                }
            },
        )
    }

    val memory: MemoryStore get() = memoryStore

    val memoryAgent: MemoryAgentService get() = memoryAgentService

    val memoryPreferences: MemoryPreferencesStore get() = memoryPreferencesStore

    val memoryRouter: MemoryModelRouter get() = memoryModelRouter

    private suspend fun isSessionMemoryDisabled(sessionId: String): Boolean {
        if (sessionId.startsWith(SuperRuntime.SUPER_THREAD_SESSION_ID) ||
            sessionId.startsWith(SuperRuntime.WORKER_SESSION_PREFIX)
        ) {
            return false
        }
        if (host.isSessionMemoryExcluded(sessionId)) return true
        val cwd = host.sessionCwd(sessionId) ?: return false
        return resolvedPolicy(cwd).effectiveMemoryScope() == MemoryAccessScope.OFF
    }

    private fun workerMemoryScope(botCwd: String): MemoryAccessScope =
        if (resolvedPolicy(botCwd).effectiveMemoryScope() == MemoryAccessScope.OFF) {
            MemoryAccessScope.OFF
        } else {
            MemoryAccessScope.WORK_ONLY
        }

    private suspend fun readMemoryAgentGuidance(): String? = withContext(Dispatchers.IO) {
        val memoryDir = File(deps.agentDir, "memory")
        val docFile = listOf("AGENTS.override.md", "AGENTS.md")
            .map { name -> File(memoryDir, name) }
            .firstOrNull { file -> file.isFile && file.canRead() }
            ?: return@withContext null
        runCatching { docFile.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }

    val conversationStore: ConversationStore by lazy {
        ConversationStore(
            runtime = host,
            scope = deps.scope,
            decorate = deps.decorateInterrupted,
        ).also { it.start() }
    }

    val superTaskStore: SuperTaskStore by lazy {
        SuperTaskStore(File(deps.agentDir, "super"))
    }

    private val workerJobRegistry: JobRegistry by lazy {
        JobRegistry(
            scope = deps.scope,
            notifier = { sessionId, _, prompt ->
                deps.scope.launch {
                    localWorkerPool.deliverJobCompletion(sessionId, prompt)
                }
            },
            wakeEnabled = { currentSuperSettings().workerJobWake },
            runnerFactory = { cwd -> LinuxJobRunner(deps.hostEnvironment, cwd) },
            maxRunningPerSession = { 6 },
        )
    }

    private val localWorkerPoolLazy = lazy {
        LocalWorkerPool(
            workersRoot = File(deps.agentDir, "super/workers"),
            taskStore = superTaskStore,
            sessionFactory = { spec, workerId, workerWorkspaceDir, sessionFile, approvalCenter ->
                val exclusions = exclusionsFor(spec.cwd.absolutePath)
                val toolContext = workerToolContext(
                    botCwd = spec.cwd.absolutePath,
                    workerMemoryKey = "worker:${spec.botId}:$workerId",
                    approvalCenter = approvalCenter,
                    workerId = workerId,
                    workerSessionFile = sessionFile,
                )
                val engine = PiSessionEngine(
                    workspace = workerWorkspaceDir,
                    agentDir = deps.agentDir,
                    credentials = deps.credentials,
                    transport = deps.httpTransport,
                    hostEnvironment = deps.hostEnvironment,
                    providers = deps.providerCatalog,
                    personalization = { deps.personalizationStore.read().toRuntime() },
                    sharedStorage = { deps.linuxSettings.isSharedStorageEnabled() && isSharedStorageGranted() },
                    storageMounts = { storageMountInfos() },
                    pluginPromptBody = { deps.pluginManager().promptBody(exclusions.plugins) },
                    pluginPromptOverrides = { deps.pluginManager().promptOverrides(exclusions.plugins) },
                    excludedSkills = exclusions.skills,
                    excludedMcpServers = exclusions.mcpServers,
                    toolContext = toolContext,
                    supportsChatDelivery = true,
                    memory = memoryStore,
                    memoryScope = { workerMemoryScope(spec.cwd.absolutePath) },
                    awaitMemoryReady = null,
                    hooks = workerPluginHooks(spec.cwd.absolutePath),
                    compactionSettingsProvider = { currentWorkerCompactionSettings() },
                    promptResourceCwd = spec.cwd.absolutePath.toPath(),
                    captureMemoryOnClose = false,
                )
                engine.create(file = sessionFile.absolutePath, config = spec.defaultModel)
            },
            resolveBotSpec = ::resolveLocalWorkerBotSpec,
            providers = deps.providerCatalog,
            onWorkerSessionCleanup = { sessionId -> workerJobRegistry.clearSession(sessionId) },
            settingsProvider = ::currentSuperSettings,
            hasRunningJobs = ::hasRunningWorkerJobs,
            onWorkerDeleted = { wid -> deleteWorkerTimers(SuperRuntime.workerSessionId(wid)) },
            inspectExtrasProvider = ::buildWorkerInspectExtras,
            scope = deps.scope,
            deviceNameProvider = deps.localDeviceNameProvider,
        )
    }

    val localWorkerPool: LocalWorkerPool by localWorkerPoolLazy

    private val superRuntimeLazy = lazy {
        val superDir = File(deps.agentDir, "super")
        SuperOrchestrator(
            taskStore = superTaskStore,
            localWorkerPool = localWorkerPool,
            remoteWorkerGateway = deps.remoteWorkerGateway(),
            localBotsProvider = ::buildLocalBlueprints,
            remoteBotsProvider = deps.remoteBotsProvider,
            timerService = deps.timerService,
            imageNormalizer = { file ->
                val normalized = ImageNormalizer.normalize(file, guessMime(file.name)) ?: return@SuperOrchestrator null
                ImagePart(data = normalized.data, mimeType = normalized.mimeType, path = file.absolutePath)
            },
            scope = deps.scope,
            settingsProvider = ::currentSuperSettings,
            threadEngineFactory = { gateway, cardExists, artifactResolver ->
                SuperThreadEngine(
                    superDir = superDir,
                    providers = deps.providerCatalog,
                    initialModelConfig = { deps.modelConfigStore.read() },
                    sessionFactory = SuperThreadEngine.defaultFactory(
                        agentDir = deps.agentDir,
                        credentials = deps.credentials,
                        transport = deps.httpTransport,
                        providers = deps.providerCatalog,
                        personalization = { deps.personalizationStore.read().toRuntime() },
                        memoryStore = memoryStore,
                        timerService = deps.timerService,
                        miniappStore = deps.miniappStore,
                        extraTools = { webTools(webProvider) + arrangeTools(arrangeCalendar) },
                        workerGateway = gateway,
                        cardExists = cardExists,
                        artifactResolver = artifactResolver,
                        compactionSettingsProvider = { currentCompactionSettings() },
                        hostEnvironment = deps.hostEnvironment,
                        sharedStorage = { deps.linuxSettings.isSharedStorageEnabled() && isSharedStorageGranted() },
                        storageMounts = { storageMountInfos() },
                        timerFactory = { service, binding ->
                            timerTools(
                                service = service,
                                binding = binding,
                                scope = TimerToolScope.SUPER_AND_WORKERS,
                                targetResolver = { targetWorkerId ->
                                    val rec = superTaskStore.currentWorker(targetWorkerId) ?: return@timerTools null
                                    val file = if (rec.isRemote) {
                                        File(File(deps.agentDir, "super/workers/${rec.botId}/${rec.workerId}"), "session.jsonl")
                                    } else {
                                        localWorkerPool.workerSessionFile(rec.botId, rec.workerId)
                                    }
                                    val cwd = localWorkerPool.workerWorkspaceDirFor(rec.workerId)?.absolutePath
                                        ?: File(deps.agentDir, "super/router/workspace").absolutePath
                                    BoundSession(
                                        sessionId = SuperRuntime.workerSessionId(rec.workerId),
                                        sessionFile = file.absolutePath,
                                        cwd = cwd,
                                    )
                                },
                            )
                        },
                        memoryAgent = memoryAgentService,
                    ),
                    memoryStore = memoryStore,
                    memoryAgent = memoryAgentService,
                    scope = deps.scope,
                    idleDebounceMsProvider = {
                        memoryPreferencesStore.read().hotIdleDebounceSeconds.coerceIn(15, 600) * 1_000L
                    },
                    maxSlotsProvider = { currentSuperSettings().maxSlotsPerBot },
                )
            },
        )
    }

    val superRuntime: SuperOrchestrator by superRuntimeLazy

    init {
        deps.scope.launch {
            deps.appPreferences.preferences.collect { prefs ->
                compactionTriggerPercent = prefs.compactionTriggerPercent
                compactionPercentLoaded = true
                val previous = superSettings
                val wasLoaded = superSettingsLoaded
                val nextSuper = prefs.toSuperRuntimeSettings()
                val changed = nextSuper != previous
                superSettings = nextSuper
                superSettingsLoaded = true
                if (changed || !wasLoaded) {
                    if (localWorkerPoolLazy.isInitialized()) {
                        localWorkerPool.onSettingsChanged()
                    }
                    deps.onSuperSettingsChanged()
                    if (superRuntimeLazy.isInitialized() && nextSuper.maxSlotsPerBot != previous.maxSlotsPerBot) {
                        try {
                            superRuntime.refreshBlueprints()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w("SuperPIRuntime", "Failed to refresh blueprints on settings change", error)
                        }
                    }
                }
            }
        }
        deps.scope.launch {
            deps.modelConfigStore.config
                .distinctUntilChanged()
                .collect { config ->
                    if (superRuntimeLazy.isInitialized()) {
                        runCatching { superRuntime.syncDefaultModel(config) }
                    }
                }
        }
        deps.scope.launch {
            var lastScheduledHours: Int? = null
            memoryPreferencesStore.settings.collect { settings ->
                val paused = settings.mode == MemoryModelMode.PAUSED
                memoryAgentService.onPauseModeChanged(paused)
                val targetHours = if (paused) 0 else settings.coldConsolidationHours
                if (lastScheduledHours != targetHours) {
                    val replace = lastScheduledHours != null
                    lastScheduledHours = targetHours
                    memoryDreamScheduler.syncSchedule(targetHours, replace)
                }
            }
        }
        deps.scope.launch {
            if (deps.configuredProviderIds().isEmpty()) {
                deps.credentials.authKinds.filter { it.isNotEmpty() }.first()
            }
            memoryAgentService.launchConsolidationIfDue()
            memoryAgentService.launchRetryPending()
        }
    }

    init {
        deps.scope.launch {
            host.events.collect { sessionEvent ->
                when (sessionEvent.event) {
                    is RuntimeEvent.RunStarted -> {
                        idleDebounceJobs.remove(sessionEvent.sessionId)?.cancel()
                    }
                    is RuntimeEvent.RunFinished -> {
                        val sessionId = sessionEvent.sessionId
                        idleDebounceJobs.remove(sessionId)?.cancel()
                        idleDebounceJobs[sessionId] = deps.scope.launch {
                            val currentJob = coroutineContext[Job]
                            try {
                                val debounceMs = memoryPreferencesStore.read()
                                    .hotIdleDebounceSeconds
                                    .coerceIn(15, 600) * 1_000L
                                delay(debounceMs)
                                val branch = host.sessionBranch(sessionId)
                                memoryAgentService.launchIdleSession(
                                    sessionId = sessionId,
                                    sessionFile = branch?.first,
                                    entries = branch?.second,
                                )
                            } finally {
                                if (currentJob != null) {
                                    idleDebounceJobs.remove(sessionId, currentJob)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        deps.scope.launch {
            host.sdkEvents.collect { event ->
                if (event.event is SessionEvent.CompactionStarted) {
                    try {
                        val branch = host.sessionBranch(event.sessionId)
                        if (branch != null) {
                            memoryAgentService.launchCompactionSession(event.sessionId, branch.first, branch.second)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("Memory", "compaction snapshot refresh failed", error)
                    }
                }
            }
        }
        deps.scope.launch {
            host.sessionClosures.collect { closure ->
                idleDebounceJobs.remove(closure.sessionId)?.cancel()
                if (closure.reason == SessionCloseReason.Idle || closure.reason == SessionCloseReason.Closed) {
                    memoryAgentService.launchSessionClose(closure.sessionId)
                } else {
                    memoryAgentService.discardCaptured(closure.sessionId)
                }
            }
        }
    }

    private fun currentCompactionSettings(): CompactionSettings =
        CompactionSettings(enabled = true, triggerRatio = compactionTriggerPercent / 100.0)

    private fun currentWorkerCompactionSettings(): CompactionSettings {
        val settings = currentSuperSettings()
        val ratio = if (settings.workerCompactionOverrideEnabled) {
            settings.workerCompactionTriggerPercent / 100.0
        } else {
            compactionTriggerPercent / 100.0
        }
        return CompactionSettings(enabled = true, triggerRatio = ratio)
    }

    private suspend fun deleteWorkerTimers(workerSessionId: String) {
        deps.timerService().deleteBySession(workerSessionId)
    }

    private suspend fun buildWorkerInspectExtras(workerId: String, sdkSessionId: String?): WorkerInspectExtras {
        val targetTimerSessionId = SuperRuntime.workerSessionId(workerId)
        val activeTimers = runCatching {
            deps.timerService().list()
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
        return WorkerInspectExtras(
            activeTimers = activeTimers,
            runningJobs = runningJobs,
        )
    }

    private fun excludedPluginsForCwd(cwd: String): Set<String> {
        val installed = installedPluginIds()
        return installed - resolvedPolicy(cwd).enabledPlugins(installed)
    }

    private suspend fun resolveLocalWorkerBotSpec(botId: String): LocalWorkerBotSpec? {
        ensurePreferencesLoaded()
        val workspace = host.workspaces().firstOrNull { it.remote == null && it.id == botId } ?: return null
        val defaultModel = workspace.defaultModel ?: deps.modelConfigStore.read()
        return LocalWorkerBotSpec(
            botId = workspace.id,
            name = workspace.name,
            avatar = workspace.avatar,
            cwd = File(workspace.cwd),
            defaultModel = defaultModel,
        )
    }

    private suspend fun buildLocalBlueprints(): List<BotBlueprint> {
        ensurePreferencesLoaded()
        val deviceName = deps.localDeviceNameProvider()
        val allSkills = installedSkills().map { it.name }.sorted()
        val enabledPlugins = deps.pluginManager().state.value.plugins.filter { it.enabled }
        val allMcp = installedMcpServers().sorted()
        val screenCapable = deps.hostEnvironment.current().capabilities.hasScreenControl
        return host.workspaces().filter { it.remote == null }.map { ws ->
            val policy = resolvedPolicy(ws.cwd)
            val exclusions = exclusionsFor(ws.cwd)
            BotBlueprint(
                ref = SuperRuntime.localBotRef(ws.id),
                botId = ws.id,
                name = ws.name,
                avatar = ws.avatar,
                intro = ws.intro.orEmpty(),
                deviceId = null,
                deviceName = deviceName,
                online = true,
                capabilities = BotCapabilitiesSummary(
                    skills = allSkills.filterNot { it in exclusions.skills },
                    plugins = enabledPlugins
                        .filterNot { it.id in exclusions.plugins }
                        .map { it.name.ifBlank { it.id } }
                        .sorted(),
                    mcpServers = allMcp.filterNot { it in exclusions.mcpServers },
                    screenTools = policy.screenTools && screenCapable,
                    miniappTools = policy.miniappTools,
                    memoryDispatch = policy.effectiveMemoryDispatch(),
                    memoryScope = policy.effectiveMemoryScope(),
                ),
                supportsSuperWorker = true,
                defaultModel = ws.defaultModel,
            )
        }
    }

    private fun workerToolContext(
        botCwd: String,
        workerMemoryKey: String,
        approvalCenter: QuestionCenter,
        workerId: String,
        workerSessionFile: File,
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
            pluginTools = { binding ->
                deps.pluginManager().tools(binding, excludedPluginsForCwd(botCwd))
            },
            pluginDeferredTools = { binding ->
                deps.pluginManager().deferredTools(binding, excludedPluginsForCwd(botCwd))
            },
            extraToolFactories = listOf(
                { _, binding ->
                    val policy = resolvedPolicy(botCwd)
                    buildList {
                        add(
                            presentTool(
                                miniappLookup = miniappLookup(deps.miniappStore),
                                session = { binding.current() },
                                cardExists = { cardId -> binding.hasCardToolResult(cardId) },
                            ),
                        )
                        if (deps.hostEnvironment.current().system != null) {
                            add(systemShellTool { deps.hostEnvironment.current().system })
                        }
                        add(
                            todoWriteTool(
                                sessionId = { binding.current()?.sessionId },
                                post = { sid, type, payload, text ->
                                    localWorkerPool.postCustomMessage(sid, type, payload, text)
                                },
                            ),
                        )
                        addAll(timerTools(deps.timerService(), workerTimerBinding))
                        addAll(webTools(webProvider))
                        addAll(arrangeTools(arrangeCalendar))
                        addAll(jobTools(binding, workerJobRegistry))
                        if (policy.miniappTools) {
                            addAll(miniappTools(deps.miniappStore, supportsChatDelivery = true))
                            add(miniappDataTool(deps.miniappStore))
                        }
                        addAll(
                            memoryTools(
                                store = memoryStore,
                                agentService = null,
                                scope = { workerMemoryScope(botCwd) },
                                allowDispatch = { false },
                                sessionId = { workerMemoryKey },
                                isSessionExcluded = { false },
                            ),
                        )
                        if (policy.screenTools && deps.hostEnvironment.current().capabilities.hasScreenControl) {
                            addAll(deps.screenTools())
                            addAll(deps.virtualScreenTools())
                        }
                    }
                },
            ),
        )
    }

    private fun workerPluginHooks(botCwd: String): RuntimeHooks {
        val delegate = PluginHooksDelegate()
        return RuntimeHooks(
            promptTransform = delegate.promptTransform,
            systemContext = delegate.systemContext,
            inputHandler = delegate.inputHandler,
            toolHooks = delegate.toolHooks,
            onSessionBound = { binding ->
                deps.pluginManager().bindSessionHooks(binding, delegate, excludedPluginsForCwd(botCwd))
            },
            onSessionClosed = { sessionId -> deps.pluginManager().onSessionClosed(sessionId) },
        )
    }
}

private class DeferredMemoryConsolidationCoordinator(
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
