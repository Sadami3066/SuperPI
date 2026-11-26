package io.github.whywood.superpi

import android.content.Context
import io.github.whywood.superpi.sdk.ai.auth.oauth.OAuthRuntime
import io.github.whywood.superpi.conversation.ConversationState
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.memory.MemoryAgentService
import io.github.whywood.superpi.runtime.memory.MemoryPreferencesStore
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.conversation.ConversationStore
import io.github.whywood.superpi.conversation.nextMessageId
import io.github.whywood.superpi.data.AppLanguage
import io.github.whywood.superpi.data.AppPreferences
import io.github.whywood.superpi.data.BundledSkills
import io.github.whywood.superpi.data.CustomProviderStore
import io.github.whywood.superpi.data.AttachmentStore
import io.github.whywood.superpi.data.AgentDocStore
import io.github.whywood.superpi.data.DevPackageManager
import io.github.whywood.superpi.data.HostFiles
import io.github.whywood.superpi.data.LanguagePreferences
import io.github.whywood.superpi.data.LinuxSettingsStore
import io.github.whywood.superpi.data.StorageMountStore
import io.github.whywood.superpi.data.crash.CrashLogStore
import io.github.whywood.superpi.data.WorkspaceToolPolicyStore
import io.github.whywood.superpi.data.LocalizedContext
import io.github.whywood.superpi.data.OverlayEntryPreferences
import io.github.whywood.superpi.data.PersonalizationStore
import io.github.whywood.superpi.data.ShareStaging
import io.github.whywood.superpi.data.SkillRepository
import io.github.whywood.superpi.data.ThemePreferences
import io.github.whywood.superpi.data.VoicePreferences
import io.github.whywood.superpi.data.update.AndroidUpdateInstaller
import io.github.whywood.superpi.data.update.DataStoreUpdateStore
import io.github.whywood.superpi.data.update.UpdateManager
import io.github.whywood.superpi.graph.DataGraph
import io.github.whywood.superpi.graph.DataGraphDeps
import io.github.whywood.superpi.graph.PlatformGraph
import io.github.whywood.superpi.graph.PlatformGraphDeps
import io.github.whywood.superpi.graph.PluginGraph
import io.github.whywood.superpi.graph.PluginGraphDeps
import io.github.whywood.superpi.market.MarketRepository
import io.github.whywood.superpi.market.MarketRepositoryImpl
import io.github.whywood.superpi.graph.RuntimeGraph
import io.github.whywood.superpi.graph.RuntimeGraphDeps
import io.github.whywood.superpi.graph.ServiceGraph
import io.github.whywood.superpi.graph.ServiceGraphDeps
import io.github.whywood.superpi.plugin.manager.PluginHostManager
import io.github.whywood.superpi.host.AndroidHostAccessController
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.host.HostAccess
import io.github.whywood.superpi.runtime.host.HostEnvironmentProvider
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.SuperPIOAuthCallbackServerFactory
import io.github.whywood.superpi.runtime.GoalService
import io.github.whywood.superpi.runtime.JobRegistry
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ModelSettings
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderOAuthService
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.BotCapabilitiesSummary
import io.github.whywood.superpi.runtime.McpConfigStore
import io.github.whywood.superpi.runtime.McpOAuthService
import io.github.whywood.superpi.runtime.McpServerProbe
import io.github.whywood.superpi.runtime.SdkCredentialStore
import io.github.whywood.superpi.runtime.SkillCatalog
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.TimerService
import io.github.whywood.superpi.runtime.TurnNotice
import io.github.whywood.superpi.runtime.MiniappAgentService
import io.github.whywood.superpi.runtime.MiniappStore
import io.github.whywood.superpi.service.Diagnostics
import io.github.whywood.superpi.service.NotificationCenter
import io.github.whywood.superpi.service.ProcessRuntime
import io.github.whywood.superpi.service.ProcessState
import io.github.whywood.superpi.shell.LinuxEnvironment
import io.github.whywood.superpi.shell.RootfsBootstrap
import io.github.whywood.superpi.shell.shouldAutoInstallLinux
import io.github.whywood.superpi.theme.ThemeCatalog
import io.github.whywood.superpi.theme.ThemeController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppGraph(context: Context) : AppDependencies {
    private val appContext = context.applicationContext
    val uiContext: Context = LocalizedContext(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appScope: CoroutineScope get() = scope
    val activeSessionId = MutableStateFlow<String?>(null)
    private val prepareMutex = Mutex()
    @Volatile
    private var questionRecoveryLoaded = false

    private val dataGraph by lazy {
        DataGraph(
            DataGraphDeps(
                appContext = appContext,
                uiContext = uiContext,
                scope = scope,
                hostEnvironmentProvider = { platformGraph.hostEnvironmentProvider },
                stagingRoot = File(appContext.cacheDir, "handoff").apply { mkdirs() },
            ),
        )
    }
    private val platformGraph by lazy { PlatformGraph(platformGraphDeps()) }
    private val runtimeGraph by lazy { RuntimeGraph(runtimeGraphDeps()) }
    private val serviceGraph by lazy { ServiceGraph(serviceGraphDeps()) }

    override val homeDir: File get() = dataGraph.homeDir
    val workspacesDir: File get() = dataGraph.workspacesDir
    override val agentDir: File get() = dataGraph.agentDir

    fun linuxBinds(): () -> List<Pair<File, String>> = platformGraph.linuxBinds()

    override val crashLogs: CrashLogStore = CrashLogStore(File(appContext.filesDir, "crash"))

    override val attachmentStore: AttachmentStore get() = dataGraph.attachmentStore
    override val hostFiles: HostFiles get() = dataGraph.hostFiles
    val themePreferences: ThemePreferences get() = dataGraph.themePreferences
    override val appPreferences: AppPreferences get() = dataGraph.appPreferences
    override val voicePreferences: VoicePreferences get() = dataGraph.voicePreferences
    override val overlayEntryPreferences: OverlayEntryPreferences get() = dataGraph.overlayEntryPreferences
    override val shareStaging: ShareStaging get() = dataGraph.shareStaging
    override val personalizationStore: PersonalizationStore get() = dataGraph.personalizationStore
    val fontPreferences get() = dataGraph.fontPreferences
    override val modelConfigStore: ModelSettings get() = dataGraph.modelConfigStore
    override val linuxSettings: LinuxSettingsStore get() = dataGraph.linuxSettings

    override val storageMounts: StorageMountStore get() = dataGraph.storageMountStore
    override val workspaceToolPolicies: WorkspaceToolPolicyStore get() = dataGraph.workspaceToolPolicies
    override val credentials: SdkCredentialStore get() = dataGraph.credentials
    override val providerCatalog: ProviderDirectory get() = dataGraph.providerCatalog
    override val customProviderStore: CustomProviderStore get() = dataGraph.customProviderStore

    override fun configuredProviderIds(): Set<String> =
        credentials.authKinds.value.keys + customProviderStore.entries.value.map { it.id }

    override suspend fun reloadCustomProviders() {
        dataGraph.reloadCustomProviders()
        reconcileModelSelection()
        host.syncCustomProviders(dataGraph.providerCatalog.customSdkProviders())
    }
    override val providerOAuthService: ProviderOAuthService get() = dataGraph.providerOAuthService
    override val mcpConfigStore: McpConfigStore get() = dataGraph.mcpConfigStore
    override val mcpServerProbe: McpServerProbe get() = dataGraph.mcpServerProbe
    override val mcpOAuthService: McpOAuthService get() = dataGraph.mcpOAuthService
    override val skillRepository: SkillRepository get() = dataGraph.skillRepository
    override val agentDocStore: AgentDocStore get() = dataGraph.agentDocStore
    override val webSearchSettings: io.github.whywood.superpi.data.WebSearchSettingsStore get() = dataGraph.webSearchSettings
    override val miniappStore: MiniappStore get() = dataGraph.miniappStore
    override val skillCatalog: SkillCatalog get() = dataGraph.skillCatalog
    override val questionCenter: QuestionCenter get() = dataGraph.questionCenter
    override val goalService: GoalService get() = dataGraph.goalService
    override val relayController: io.github.whywood.superpi.data.relay.RelayController get() = dataGraph.relayController

    private val remoteBots = MutableStateFlow<List<io.github.whywood.superpi.data.relay.RemoteBot>>(emptyList())
    private val cachedRelayDeviceId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    override val relayMemorySync: io.github.whywood.superpi.data.relay.RelayMemorySync by lazy {
        val client = io.github.whywood.superpi.data.relay.RelayMemoryClient(
            tokenProvider = dataGraph.relayStore::token,
        )
        io.github.whywood.superpi.data.relay.RelayMemorySync(
            scope = scope,
            accounts = dataGraph.relayStore,
            memory = memory,
            client = client,
            relayCapabilities = { relayLive.relayCapabilities.value.toSet() },
            onConsolidationTrigger = { memoryAgent.launchConsolidationIfDue() },
        )
    }

    override val relayLive: io.github.whywood.superpi.data.relay.RelayLive by lazy {
        io.github.whywood.superpi.data.relay.RelayLive(
            scope = scope,
            accounts = dataGraph.relayStore,
            host = host,
            appVersion = BuildConfig.VERSION_NAME,
            questionCenter = dataGraph.questionCenter,
            avatarStorageDir = File(appContext.filesDir, "bot-avatars"),
            providerCatalog = { dataGraph.providerCatalog },
            configuredProviderIds = ::configuredProviderIds,
            agentDocStore = { dataGraph.agentDocStore },
            workspaceToolPolicies = { dataGraph.workspaceToolPolicies },
            installedCapabilities = { _ ->
                val skills = dataGraph.skillRepository.list().getOrNull().orEmpty().map { it.name }.sorted()
                val plugins = pluginManager.state.value.plugins
                    .filter { it.enabled }
                    .map {
                        io.github.whywood.superpi.data.relay.RemoteCapabilityPluginInfo(
                            id = it.id,
                            name = it.name.ifBlank { it.id },
                        )
                    }
                val mcpServers = runCatching { dataGraph.mcpConfigStore.load().servers.keys.sorted() }
                    .getOrDefault(emptyList())
                val screenReady = hostAccess.state.value.effective.let { it == HostAccess.ADB || it == HostAccess.ROOT }
                io.github.whywood.superpi.data.relay.RemoteCapabilitiesInventory(
                    skills = skills,
                    plugins = plugins,
                    mcpServers = mcpServers,
                    screenToolsReady = screenReady,
                )
            },
            slashCommands = { cwd ->
                val resolvedCwd = cwd.ifBlank { dataGraph.homeDir.absolutePath }
                val enabledSkills = enabledSkillNamesForCwd(resolvedCwd)
                val skills = runCatching { dataGraph.skillCatalog.list(resolvedCwd) }
                    .getOrDefault(emptyList())
                    .filter { it.name in enabledSkills }
                buildList {
                    add(io.github.whywood.superpi.data.relay.RemoteCommandSpec("compact", "Compact conversation", "builtin"))
                    for (skill in skills) {
                        add(
                            io.github.whywood.superpi.data.relay.RemoteCommandSpec(
                                name = "skill:${skill.name}",
                                description = skill.description,
                                source = "skill",
                            ),
                        )
                    }
                }
            },
            expandSkillPrompt = ::expandRemotePromptSkills,
            localWorkerPool = runtimeGraph.localWorkerPool,
            onRemoteMemoryChanged = { version -> relayMemorySync.onRemoteVersionHint(version) },
        ).also { live ->
            scope.launch {
                live.remoteBots.collect { bots ->
                    remoteBots.value = bots
                    runCatching { superRuntime.refreshBlueprints() }
                }
            }
            scope.launch {
                dataGraph.workspaceStore.workspaces.drop(1).collect {
                    live.refreshBots()
                    runCatching { superRuntime.refreshBlueprints() }
                }
            }
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            scope.launch {
                merge(
                    dataGraph.workspaceToolPolicies.changes.drop(1).map { Unit },
                    dataGraph.skillRepository.changes.drop(1).map { Unit },
                    dataGraph.mcpConfigStore.changes.drop(1).map { Unit },
                    pluginManager.state
                        .map { state -> state.plugins.filter { it.enabled }.map { it.id to it.name } }
                        .distinctUntilChanged()
                        .drop(1)
                        .map { Unit },
                    hostAccess.state
                        .map { it.effective }
                        .distinctUntilChanged()
                        .drop(1)
                        .map { Unit },
                ).debounce(350).collect {
                    live.refreshBots()
                    host.workspaces()
                        .filter { it.remote == null }
                        .forEach { ws ->
                            live.emitSyncEvent(
                                io.github.whywood.superpi.data.relay.RelaySyncEvent(
                                    kind = "capabilities_changed",
                                    botId = ws.id,
                                ),
                            )
                        }
                    runCatching { superRuntime.refreshBlueprints() }
                }
            }
        }
    }

    private val remoteWorkspaceRoot: File
        get() = io.github.whywood.superpi.data.relay.RemoteWorkspaces.remoteRoot(appContext.filesDir)

    private suspend fun enabledSkillNamesForCwd(cwd: String): Set<String> {
        val homePath = runCatching { dataGraph.homeDir.canonicalPath }.getOrElse { dataGraph.homeDir.absolutePath }
        val targetPath = runCatching { File(cwd).canonicalPath }.getOrElse { File(cwd).absolutePath }
        val stored = dataGraph.workspaceToolPolicies.policyForCwd(cwd)
        val policy = if (targetPath == homePath) stored.copy(allCapabilities = true) else stored
        val installedPlugins = pluginManager.state.value.plugins.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
        val excludedPlugins = installedPlugins - policy.enabledPlugins(installedPlugins)
        val skills = dataGraph.skillRepository.list().getOrNull().orEmpty()
        val allSkillNames = skills.mapTo(mutableSetOf()) { it.name }
        val nameById = skills.associate { it.id to it.name }
        val excludedPluginSkills = excludedPlugins.flatMapTo(mutableSetOf()) { pluginId ->
            pluginManager.skillIds(pluginId).mapTo(mutableSetOf()) { id -> nameById[id] ?: id }
        }
        val screenReady = policy.screenTools && hostEnvironmentProvider.current().capabilities.hasScreenControl
        val excludedScreenSkill = if (screenReady) emptySet() else setOf(BundledSkills.SCREEN_OPS_SKILL)
        val excludedMiniappSkill = if (policy.miniappTools) emptySet() else setOf(BundledSkills.MINIAPP_SKILL)
        return policy.enabledSkills(allSkillNames) - excludedPluginSkills - excludedScreenSkill - excludedMiniappSkill
    }

    private suspend fun expandRemotePromptSkills(cwd: String, rawText: String): String {
        if (!rawText.contains("/skill:") || rawText.contains("<skill ")) return rawText
        val resolvedCwd = cwd.ifBlank { dataGraph.homeDir.absolutePath }
        val attachmentTagIndex = rawText.indexOf("\n<attachments>\n")
        val mainPart = if (attachmentTagIndex >= 0) rawText.substring(0, attachmentTagIndex).trimEnd() else rawText
        val attachmentSuffix = if (attachmentTagIndex >= 0) "\n\n" + rawText.substring(attachmentTagIndex + 1) else ""
        val lines = mainPart.lines()
        val quoteLines = lines.takeWhile { it.startsWith(">") }
        val quotePrefix = if (quoteLines.isNotEmpty()) quoteLines.joinToString("\n") + "\n\n" else ""
        val body = if (quoteLines.isNotEmpty()) {
            lines.drop(quoteLines.size).joinToString("\n").trimStart()
        } else {
            mainPart.trim()
        }
        val enabled = enabledSkillNamesForCwd(resolvedCwd)
        when (val parsed = io.github.whywood.superpi.ui.common.chat.parseChatCommand(body)) {
            is io.github.whywood.superpi.ui.common.chat.ParsedCommand.Skill -> {
                if (parsed.name !in enabled) {
                    throw IllegalArgumentException("Skill not found: ${parsed.name}")
                }
                val expansion = dataGraph.skillCatalog.expand(resolvedCwd, parsed.name, parsed.args)
                    ?: throw IllegalArgumentException("Skill not found: ${parsed.name}")
                return quotePrefix + expansion.prompt + attachmentSuffix
            }
            else -> Unit
        }
        val mentions = io.github.whywood.superpi.ui.common.chat.extractSkillMentions(body) ?: return rawText
        val expansions = mentions.names.map { name ->
            if (name !in enabled) {
                throw IllegalArgumentException("Skill not found: $name")
            }
            dataGraph.skillCatalog.expand(resolvedCwd, name, "")
                ?: throw IllegalArgumentException("Skill not found: $name")
        }
        val expandedBody = buildString {
            expansions.forEachIndexed { index, expansion ->
                if (index > 0) append("\n\n")
                append(expansion.prompt)
            }
            val remainder = mentions.text.trim()
            if (remainder.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(remainder)
            }
        }
        return quotePrefix + expandedBody + attachmentSuffix
    }

    private fun remoteTargetFor(cwd: String): io.github.whywood.superpi.remote.RemoteSessionTarget? {
        val bot = remoteBots.value.firstOrNull { candidate ->
            io.github.whywood.superpi.data.relay.RemoteWorkspaces.directory(remoteWorkspaceRoot, candidate).absolutePath == cwd
        } ?: return null
        return io.github.whywood.superpi.remote.RemoteSessionTarget(
            deviceId = bot.deviceId,
            deviceName = bot.deviceName,
            botId = bot.botId,
            botName = bot.botName,
            isOnline = {
                remoteBots.value.any { current ->
                    current.deviceId == bot.deviceId && current.botId == bot.botId && current.online
                }
            },
        )
    }

    private val relayTransport: io.github.whywood.superpi.remote.RemoteAgentTransport by lazy {
        io.github.whywood.superpi.data.relay.RelayAgentTransport(relayLive, scope)
    }

    override val remoteTransport: io.github.whywood.superpi.remote.RemoteAgentTransport =
        object : io.github.whywood.superpi.remote.RemoteAgentTransport {
            override val remoteEvents
                get() = relayTransport.remoteEvents

            override val streamClosures
                get() = relayTransport.streamClosures

            override val onlineDevices
                get() = relayTransport.onlineDevices

            override val syncEvents
                get() = relayTransport.syncEvents

            override val devices
                get() = relayTransport.devices

            override val isRelayOnline
                get() = relayTransport.isRelayOnline

            override fun lastCallError() = relayTransport.lastCallError()

            override fun supportsPeerCapability(deviceId: String, capability: String): Boolean =
                relayTransport.supportsPeerCapability(deviceId, capability)

            override fun emitSyncEvent(
                kind: String,
                botId: String,
                sessionId: String?,
                busy: Boolean?,
                pendingApprovals: Int?,
                provider: String?,
                modelId: String?,
                thinkingLevel: String?,
            ) = relayTransport.emitSyncEvent(
                kind = kind,
                botId = botId,
                sessionId = sessionId,
                busy = busy,
                pendingApprovals = pendingApprovals,
                provider = provider,
                modelId = modelId,
                thinkingLevel = thinkingLevel,
            )

            override suspend fun call(
                to: String,
                method: String,
                params: kotlinx.serialization.json.JsonObject,
                timeoutMillis: Long,
                onRouteId: (String) -> Unit,
            ): kotlinx.serialization.json.JsonObject? =
                relayTransport.call(to, method, params, timeoutMillis, onRouteId)

            override suspend fun callWithOutcome(
                to: String,
                method: String,
                params: kotlinx.serialization.json.JsonObject,
                timeoutMillis: Long,
                onRouteId: (String) -> Unit,
            ) = relayTransport.callWithOutcome(to, method, params, timeoutMillis, onRouteId)
        }

    override val remoteBotAdminClient: io.github.whywood.superpi.remote.RemoteBotAdminClient by lazy {
        io.github.whywood.superpi.remote.RemoteBotAdminClient(remoteTransport)
    }

    override val remoteBotControlClient: io.github.whywood.superpi.remote.RemoteBotControlClient by lazy {
        io.github.whywood.superpi.remote.RemoteBotControlClient(remoteTransport)
    }

    override val remoteFileClient: io.github.whywood.superpi.remote.RemoteFileClient by lazy {
        io.github.whywood.superpi.remote.RemoteFileClient(remoteTransport)
    }

    override val remoteArtifactClient: io.github.whywood.superpi.remote.RemoteArtifactClient by lazy {
        io.github.whywood.superpi.remote.RemoteArtifactClient(
            transport = remoteTransport,
            cacheRootForEviction = remoteWorkspaceRoot,
        )
    }

    private val remoteWorkerClient: io.github.whywood.superpi.remote.RemoteWorkerClient by lazy {
        io.github.whywood.superpi.remote.RemoteWorkerClient(
            transport = remoteTransport,
            taskStore = runtimeGraph.superTaskStore,
            artifactClient = remoteArtifactClient,
            remoteCacheRoot = File(agentDir, "super/remote-cache"),
            scope = scope,
            settingsProvider = { runtimeGraph.currentSuperSettings() },
        )
    }

    override val superRuntime: SuperRuntime get() = runtimeGraph.superRuntime

    val workerJobs: kotlinx.coroutines.flow.StateFlow<List<io.github.whywood.superpi.runtime.JobSnapshot>>
        get() = runtimeGraph.workerJobs

    val hasAnyRunningWorkerJobs: Boolean
        get() = runtimeGraph.hasAnyRunningWorkerJobs()

    private val updateInstaller: AndroidUpdateInstaller by lazy { AndroidUpdateInstaller(appContext) }

    override val updateManager: UpdateManager by lazy {
        UpdateManager(
            transport = dataGraph.httpTransport,
            store = DataStoreUpdateStore(appContext),
            installer = updateInstaller,
            updatesDir = File(appContext.filesDir, "updates"),
            scope = scope,
            currentVersionName = BuildConfig.VERSION_NAME,
            onFailure = { message, error -> Diagnostics.log(appContext, message, error) },
        )
    }

    override val hostAccess: AndroidHostAccessController get() = platformGraph.hostAccess
    override val hostEnvironmentProvider: HostEnvironmentProvider get() = platformGraph.hostEnvironmentProvider
    override val rootfsBootstrap: RootfsBootstrap get() = platformGraph.rootfsBootstrap
    val adbBridge get() = platformGraph.adbBridge
    override val linuxEnvironment: LinuxEnvironment get() = platformGraph.linuxEnvironment

    override val host: AgentRuntimeHost get() = runtimeGraph.host
    override val conversationStore: ConversationStore get() = runtimeGraph.conversationStore
    override val memory: MemoryStore get() = runtimeGraph.memory
    override val memoryAgent: MemoryAgentService get() = runtimeGraph.memoryAgent
    override val memoryPreferences: MemoryPreferencesStore get() = runtimeGraph.memoryPreferences
    override val memoryModelRouter: io.github.whywood.superpi.runtime.memory.MemoryModelRouter
        get() = runtimeGraph.memoryRouter
    override val miniappAgentService: MiniappAgentService get() = runtimeGraph.miniappAgentService

    val notificationCenter: NotificationCenter get() = serviceGraph.notificationCenter
    override val timerService: TimerService get() = serviceGraph.timerService
    override val jobRegistry: JobRegistry get() = serviceGraph.jobRegistry
    override val devPackageManager: DevPackageManager get() = serviceGraph.devPackageManager
    override val processRuntime: ProcessRuntime get() = serviceGraph.processRuntime

    private val pluginGraph: PluginGraph by lazy { PluginGraph(pluginGraphDeps()) }

    override val pluginManager: PluginHostManager get() = pluginGraph.manager

    private val themeCatalog by lazy { ThemeCatalog() }

    override val themeController: ThemeController by lazy {
        ThemeController(
            context = uiContext,
            preferences = themePreferences,
            catalog = themeCatalog,
            pluginSources = { pluginManager.installedThemes() },
            language = {
                val preference = LanguagePreferences(appContext).read()
                if (preference == AppLanguage.SYSTEM) {
                    appContext.resources.configuration.locales[0].language
                } else {
                    preference.tag
                }
            },
        )
    }

    override val marketRepository: MarketRepository by lazy {
        MarketRepositoryImpl(
            context = appContext,
            scope = scope,
            skillRepository = skillRepository,
            pluginManager = pluginManager,
            mcpConfigStore = mcpConfigStore,
        )
    }

    init {
        scope.launch {
            dataGraph.relayStore.account.collect { account ->
                cachedRelayDeviceId.set(account?.deviceId)
            }
        }
        scope.launch {
            dataGraph.credentials.authKinds.collect {
                runCatching { reconcileModelSelection() }
            }
        }
        scope.launch {
            merge(
                dataGraph.workspaceToolPolicies.changes.drop(1),
                dataGraph.skillRepository.changes.drop(1),
            ).debounce(300).collect {
                runCatching { host.reconfigure() }
            }
        }
        scope.launch {
            val environment = linuxEnvironment
            if (!shouldAutoInstallLinux(environment.isSupported, environment.state.value)) return@launch
            runCatching { environment.install() }.onFailure {
                Diagnostics.log(appContext, "app: linux environment preparation failed", it)
            }
        }
        scope.launch {
            combine(processRuntime.state, pluginManager.state) { process, _ -> process }.collect { state ->
                if (state == ProcessState.STARTED) {
                    runCatching { themeController.refresh() }.onFailure {
                        Diagnostics.log(appContext, "app: theme refresh failed", it)
                    }
                }
            }
        }
        scope.launch {
            runCatching { marketRepository.refresh() }.onFailure {
                Diagnostics.log(appContext, "app: market refresh failed", it)
            }
        }
        scope.launch {
            runCatching { hostAccess.refresh() }.onFailure {
                Diagnostics.log(appContext, "app: host access refresh failed", it)
            }
        }
        OAuthRuntime.callbackServerFactory = SuperPIOAuthCallbackServerFactory()
        scope.launch {
            host.sdkEvents.collect { event ->
                pluginManager.onSessionSdkEvent(event.sessionId, event.event)
            }
        }
        scope.launch {
            host.events.collect { event ->
                pluginManager.onRuntimeEvent(event)
                when (event.event) {
                    is RuntimeEvent.RunFinished, is RuntimeEvent.RunFailed -> {
                        runCatching { skillRepository.syncIfChanged() }.onFailure {
                            Diagnostics.log(appContext, "app: skill sync failed", it)
                        }
                        runCatching { pluginManager.refreshIfStale(ProcessRuntime.PLUGIN_REFRESH_MIN_INTERVAL_MS) }.onFailure {
                            Diagnostics.log(appContext, "app: plugin refresh failed", it)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun platformGraphDeps(): PlatformGraphDeps = PlatformGraphDeps(
        appContext = appContext,
        uiContext = uiContext,
        scope = scope,
        homeDir = homeDir,
        sharedStorage = dataGraph.sharedStorage,
        linuxSettings = dataGraph.linuxSettings,
        storageMountStore = dataGraph.storageMountStore,
        httpTransport = dataGraph.httpTransport,
        mcpTokenStore = dataGraph.mcpTokenStore,
        pluginManager = { pluginManager },
        host = { host },
        syncBundledSkills = { syncBundledSkills() },
    )

    private fun runtimeGraphDeps(): RuntimeGraphDeps = RuntimeGraphDeps(
        appContext = appContext,
        scope = scope,
        homeDir = homeDir,
        workspacesDir = workspacesDir,
        agentDir = agentDir,
        modelConfigStore = dataGraph.modelConfigStore,
        sessionCatalog = dataGraph.sessionCatalog,
        workspaceStore = dataGraph.workspaceStore,
        providerCatalog = dataGraph.providerCatalog,
        credentials = dataGraph.credentials,
        configuredProviderIds = ::configuredProviderIds,
        httpTransport = dataGraph.httpTransport,
        personalizationStore = dataGraph.personalizationStore,
        linuxSettings = dataGraph.linuxSettings,
        storageMountStore = dataGraph.storageMountStore,
        workspaceToolPolicies = dataGraph.workspaceToolPolicies,
        skillRepository = dataGraph.skillRepository,
        mcpConfigStore = dataGraph.mcpConfigStore,
        questionCenter = dataGraph.questionCenter,
        goalService = dataGraph.goalService,
        miniappStore = dataGraph.miniappStore,
        pluginManager = { pluginManager },
        hostAccess = hostAccess,
        hostEnvironment = hostEnvironmentProvider,
        decorateInterrupted = ::decorateInterrupted,
        timerService = { serviceGraph.timerService },
        jobRegistry = { serviceGraph.jobRegistry },
        appPreferences = dataGraph.appPreferences,
        remoteWorkspaces = {
            io.github.whywood.superpi.data.relay.RemoteWorkspaces.workspaces(
                root = remoteWorkspaceRoot,
                bots = remoteBots.value,
            )
        },
        remoteTarget = ::remoteTargetFor,
        remoteTransport = remoteTransport,
        remoteRoot = remoteWorkspaceRoot,
        apiKeyProvider = {
            dataGraph.credentials.cachedApiKey(ProviderCatalog.DEFAULT_PROVIDER_ID) ?: run {
                dataGraph.credentials.preload(ProviderCatalog.DEFAULT_PROVIDER_ID)
                dataGraph.credentials.cachedApiKey(ProviderCatalog.DEFAULT_PROVIDER_ID)
            }
        },
        webSearchKeyProvider = { providerId -> dataGraph.webSearchSettings.key(providerId) },
        postCustomMessage = { sessionId, customType, payload, text ->
            host.postPluginMessage(sessionId, customType, payload, text)
        },
        screenTools = { platformGraph.screenToolset.tools() },
        virtualScreenTools = { platformGraph.virtualScreenFeature.tools() },
        remoteWorkerGateway = { remoteWorkerClient },
        remoteBotsProvider = ::remoteBotBlueprints,
        localDeviceNameProvider = ::localDeviceDisplayName,
        onSuperSettingsChanged = { scope.launch { remoteWorkerClient.onSettingsChanged() } },
        memoryDeviceIdProvider = { cachedRelayDeviceId.get() },
        memoryConsolidationCoordinator = { relayMemorySync },
    )

    private fun localDeviceDisplayName(): String =
        android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "This phone"

    private fun remoteBotBlueprints(): List<BotBlueprint> = remoteBots.value.map { bot ->
        val caps = bot.botCapabilities?.let { c ->
            BotCapabilitiesSummary(
                skills = c.skills,
                plugins = c.plugins,
                mcpServers = c.mcpServers,
                screenTools = c.screenTools,
                miniappTools = c.miniappTools,
                memoryDispatch = c.memoryDispatch ?: false,
                memoryScope = MemoryAccessScope.fromWire(c.memoryScope),
            )
        } ?: BotCapabilitiesSummary()
        BotBlueprint(
            ref = SuperRuntime.remoteBotRef(bot.deviceId, bot.botId),
            botId = bot.botId,
            name = bot.botName,
            avatar = bot.avatar,
            intro = bot.intro,
            deviceId = bot.deviceId,
            deviceName = bot.deviceName,
            online = bot.online,
            capabilities = caps,
            supportsSuperWorker = remoteTransport.supportsPeerCapability(bot.deviceId, "super-worker"),
            defaultModel = null,
        )
    }

    private fun serviceGraphDeps(): ServiceGraphDeps = ServiceGraphDeps(
        appContext = appContext,
        uiContext = uiContext,
        scope = scope,
        host = { host },
        conversationStore = { conversationStore },
        superRuntime = { superRuntime },
        questionCenter = dataGraph.questionCenter,
        timerStore = dataGraph.timerStore,
        pluginManager = { pluginManager },
        skillRepository = dataGraph.skillRepository,
        installBundledSkills = { syncBundledSkills() },
        prepare = ::prepare,
        jobWake = dataGraph.jobWake,
        hostEnvironment = platformGraph.hostEnvironmentProvider,
        devPackagesCwd = { homeDir.absolutePath },
        devPackagesReady = {
            platformGraph.hostEnvironmentProvider.current().capabilities.bashAvailable
        },
    )

    private fun pluginGraphDeps(): PluginGraphDeps = PluginGraphDeps(
        appContext = appContext,
        uiContext = uiContext,
        scope = scope,
        agentDir = agentDir,
        homeDir = homeDir,
        pluginStrings = dataGraph.pluginStrings,
        host = host,
        installationId = dataGraph.installationId,
        linuxEnvironment = platformGraph.linuxEnvironment,
        hostEnvironment = platformGraph.hostEnvironmentProvider,
        secretStore = dataGraph.secretStore,
        httpTransport = dataGraph.httpTransport,
        activeSessionId = activeSessionId,
        pluginBus = dataGraph.pluginBus,
        mcpConfigStore = dataGraph.mcpConfigStore,
        mcpServerProbe = dataGraph.mcpServerProbe,
        miniappStore = dataGraph.miniappStore,
        miniappAgentService = { runtimeGraph.miniappAgentService },
        jobRegistry = { serviceGraph.jobRegistry },
        timerService = { serviceGraph.timerService },
        goalService = { dataGraph.goalService },
        virtualScreenFeature = { platformGraph.virtualScreenFeature },
        pluginScreenController = { platformGraph.pluginScreenController },
        pluginMainScreenController = { platformGraph.pluginMainScreenController },
        mcpPluginBridge = { platformGraph.mcpPluginBridge },
        hostFiles = dataGraph.hostFiles,
        arrangeCalendar = runtimeGraph.pluginArrangeCalendar,
        webProvider = runtimeGraph.pluginWebProvider,
        credentials = dataGraph.credentials,
        customProviderStore = dataGraph.customProviderStore,
        providerCatalog = dataGraph.providerCatalog,
        mcpTokenStore = dataGraph.mcpTokenStore,
        rootShell = platformGraph.rootShell,
        adbBridge = platformGraph.adbBridge,
        hostAccess = platformGraph.hostAccess,
        updateInstaller = updateInstaller,
    )

    override suspend fun prepare() {
        prepareMutex.withLock {
            if (!questionRecoveryLoaded) {
                withContext(Dispatchers.IO) { questionCenter.loadPersisted() }
                host.start()
                notificationCenter.notifyInterruptedWork(questionCenter.interrupted.value, host.interruptedRuns.value)
                questionRecoveryLoaded = true
            }
        }
        withContext(Dispatchers.IO) {
            importDebugKey()
        }
        credentials.preloadAll()
        reconcileModelSelection()
    }

    private suspend fun reconcileModelSelection() {
        dataGraph.modelSelection.ensure(configuredProviderIds())
    }

    private suspend fun syncBundledSkills() {
        runCatching {
            dataGraph.bundledSkills.sync()
        }.onFailure {
            Diagnostics.log(appContext, "app: bundled skill sync failed", it)
        }
    }

    private suspend fun decorateInterrupted(state: ConversationState, sessionId: String): ConversationState {
        var next = state
        val questions = questionCenter.interrupted.value.count { it.sessionId == sessionId }
        if (questions > 0 && next.messages.none { it.notice is TurnNotice.InterruptedQuestions }) {
            next = next.copy(
                messages = next.messages + noticeMessage(next.messages, TurnNotice.InterruptedQuestions(questions)),
            )
        }
        val runs = host.interruptedRuns.value.count { it.sessionId == sessionId }
        if (runs > 0 && next.messages.none { it.notice is TurnNotice.InterruptedRun }) {
            next = next.copy(
                messages = next.messages + noticeMessage(next.messages, TurnNotice.InterruptedRun(runs)),
            )
        }
        return next
    }

    private fun noticeMessage(messages: List<AssistantMessage>, notice: TurnNotice): AssistantMessage {
        val now = System.currentTimeMillis()
        return AssistantMessage(
            id = nextMessageId(messages),
            role = AssistantRole.ASSISTANT,
            text = "",
            status = MessageStatus.DONE,
            startedAt = now,
            finishedAt = now,
            notice = notice,
        )
    }

    private suspend fun importDebugKey() {
        if (!BuildConfig.DEBUG) return
        val directory = File(appContext.filesDir, "import")
        val keyFile = File(directory, "deepseek.key")
        if (!keyFile.isFile) return
        val key = keyFile.readText().trim()
        if (key.isNotEmpty()) {
            credentials.writeApiKey(ProviderCatalog.DEFAULT_PROVIDER_ID, key, null)
        }
        keyFile.delete()
        directory.delete()
    }
}
