package io.github.whywood.superpi.graph

import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.arrange.AndroidArrangeCalendarGateway
import io.github.whywood.superpi.arrange.arrangeTools
import io.github.whywood.superpi.conversation.ConversationStore
import io.github.whywood.superpi.core.SessionTitleService
import io.github.whywood.superpi.data.BundledSkills
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.isSharedStorageGranted
import io.github.whywood.superpi.plugin.host.events.PluginHooksDelegate
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.AppToolContext
import io.github.whywood.superpi.runtime.GoalPhase
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MiniappAgentService
import io.github.whywood.superpi.remote.RemoteSessionEngine
import io.github.whywood.superpi.remote.RemoteSessionRegistry
import io.github.whywood.superpi.runtime.PiSessionEngine
import io.github.whywood.superpi.runtime.SessionEngine
import io.github.whywood.superpi.runtime.SessionCloseReason
import io.github.whywood.superpi.runtime.RunLedger
import io.github.whywood.superpi.runtime.memory.CatalogMemoryCompletionGateway
import io.github.whywood.superpi.runtime.memory.MemoryDreamer
import io.github.whywood.superpi.runtime.memory.MemoryIngestor
import io.github.whywood.superpi.runtime.memory.MemoryModelRouter
import io.github.whywood.superpi.runtime.memory.MemoryPreferencesStore
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.memoryTools
import io.github.whywood.superpi.runtime.todoWriteTool
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.RuntimeHooks
import io.github.whywood.superpi.runtime.SessionBinding
import io.github.whywood.superpi.runtime.StorageMountInfo
import io.github.whywood.superpi.runtime.goalTools
import io.github.whywood.superpi.runtime.jobTools
import io.github.whywood.superpi.runtime.miniappEditTool
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val IDLE_DEBOUNCE_MS = 5 * 60_000L

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

    private val memoryStore: MemoryStore = MemoryStore(File(deps.agentDir, "memory"))
    private val memoryPreferencesStore: MemoryPreferencesStore = MemoryPreferencesStore(
        context = deps.appContext,
        onPauseModeChanged = { paused -> memoryIngestorService.onPauseModeChanged(paused) },
    )
    private val memoryModelRouter: MemoryModelRouter = MemoryModelRouter(
        providers = deps.providerCatalog,
        modelSettings = deps.modelConfigStore,
        memorySettings = memoryPreferencesStore,
        configuredProviders = deps.configuredProviderIds,
    )
    private val memoryGateway: CatalogMemoryCompletionGateway =
        CatalogMemoryCompletionGateway(deps.providerCatalog, memoryModelRouter)
    private val memoryIngestorService: MemoryIngestor = MemoryIngestor(
        store = memoryStore,
        completionGateway = memoryGateway,
        scope = deps.scope,
        isSessionExcluded = { sessionId -> host.isSessionMemoryExcluded(sessionId) },
        isPaused = { memoryPreferencesStore.read().mode == MemoryModelMode.PAUSED },
    )
    private val memoryDreamerService = MemoryDreamer(
        store = memoryStore,
        completionGateway = memoryGateway,
        scope = deps.scope,
        coordinator = deps.memoryDreamCoordinator,
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
        add(presentTool(miniappLookup(deps.miniappStore)) { binding.current() })
        add(systemShellTool { deps.hostEnvironment.current().system })
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
            add(miniappEditTool(deps.miniappStore))
        }
        addAll(
            memoryTools(
                store = memoryStore,
                sessionId = { binding.current()?.sessionId },
                isSessionExcluded = { sessionId -> host.isSessionMemoryExcluded(sessionId) },
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
                memoryDreamerService.launchIfDue()
                memoryIngestorService.launchRetryPending()
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
        val excludedScreenSkill = if (policy.screenTools) emptySet() else setOf(BundledSkills.SCREEN_OPS_SKILL)
        val mcp = installedMcpServers()
        return BotPolicyExclusions(
            plugins = excludedPlugins,
            skills = (allSkillNames - enabledSkills) + excludedPluginSkills + excludedScreenSkill,
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

    private suspend fun localEngine(cwd: File): PiSessionEngine {
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
            hooks = pluginRuntimeHooks(),
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
                    memoryIngestorService.onSessionExclusionChanged(
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

    val memoryIngestor: MemoryIngestor get() = memoryIngestorService

    val memoryDreamer: MemoryDreamer get() = memoryDreamerService

    val memoryPreferences: MemoryPreferencesStore get() = memoryPreferencesStore

    init {
        memoryDreamScheduler.ensureScheduled()
        deps.scope.launch {
            memoryIngestorService.onPauseModeChanged(memoryPreferencesStore.read().mode == MemoryModelMode.PAUSED)
            if (deps.configuredProviderIds().isEmpty()) {
                deps.credentials.authKinds.filter { it.isNotEmpty() }.first()
            }
            memoryDreamerService.launchIfDue()
            memoryIngestorService.launchRetryPending()
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
                                delay(IDLE_DEBOUNCE_MS)
                                val branch = host.sessionBranch(sessionId)
                                memoryIngestorService.launchIdleDebounce(
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
                            memoryIngestorService.launchCompactionFlush(event.sessionId, branch.first, branch.second)
                        }
                        memoryStore.refresh("compaction")
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
                    memoryIngestorService.launchSessionClose(closure.sessionId)
                } else {
                    memoryIngestorService.discard(closure.sessionId)
                }
            }
        }
    }

    val conversationStore: ConversationStore by lazy {
        ConversationStore(
            runtime = host,
            scope = deps.scope,
            decorate = deps.decorateInterrupted,
        ).also { it.start() }
    }
}
