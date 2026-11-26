package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.AgentMessage
import io.github.whywood.superpi.sdk.ai.Model
import io.github.whywood.superpi.sdk.ai.Provider
import io.github.whywood.superpi.sdk.ai.auth.CredentialStore
import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.coding.compaction.buildSessionProjection
import io.github.whywood.superpi.sdk.coding.compaction.estimateProjectedContextTokens
import io.github.whywood.superpi.sdk.coding.AgentSessionOptions
import io.github.whywood.superpi.sdk.coding.createAgentSession
import io.github.whywood.superpi.sdk.coding.mcp.McpConfigLoader
import io.github.whywood.superpi.sdk.coding.mcp.McpOptions
import io.github.whywood.superpi.sdk.coding.paths.AgentPaths
import io.github.whywood.superpi.sdk.coding.prompt.loadPromptResources
import io.github.whywood.superpi.sdk.coding.settings.CompactionSettings
import io.github.whywood.superpi.sdk.coding.settings.SettingsManager
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import io.github.whywood.superpi.sdk.coding.session.AgentSession
import io.github.whywood.superpi.sdk.coding.session.CodingHooks
import io.github.whywood.superpi.sdk.coding.session.CustomEntry
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.coding.session.SessionInfo
import io.github.whywood.superpi.sdk.coding.session.SessionManager
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.FileMutationQueue
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.defaultCodingTools
import io.github.whywood.superpi.runtime.memory.MemoryStore
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.time.OffsetDateTime
import io.github.whywood.superpi.runtime.host.DynamicFileSystem
import io.github.whywood.superpi.runtime.host.DynamicShellExecutor
import io.github.whywood.superpi.runtime.host.HostAccess
import io.github.whywood.superpi.runtime.host.HostAccessUnavailableException
import io.github.whywood.superpi.runtime.host.HostEnvironmentProvider
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class PiSessionEngine(
    workspace: File,
    agentDir: File,
    private val credentials: CredentialStore,
    private val transport: HttpTransport,
    private val shellExecutor: ShellExecutor? = null,
    private val hostEnvironment: HostEnvironmentProvider? = null,
    private val providers: ProviderCatalog,
    private val personalization: suspend () -> Personalization = { Personalization() },
    private val sharedStorage: suspend () -> Boolean = { false },
    private val storageMounts: suspend () -> List<StorageMountInfo> = { emptyList() },
    private val pluginPromptBody: suspend () -> String? = { null },
    private val pluginPromptOverrides: suspend () -> PluginPromptOverrides = { PluginPromptOverrides.None },
    private val excludedSkills: Set<String> = emptySet(),
    private val excludedMcpServers: Set<String> = emptySet(),
    private val toolContext: AppToolContext? = null,
    private val toolFs: FileSystem = FileSystem.SYSTEM,
    private val fs: FileSystem = toolFs,
    private val supportsChatDelivery: Boolean = true,
    private val memory: MemoryStore? = null,
    internal val hooks: RuntimeHooks = RuntimeHooks.None,
    private val promptAppDataDir: File? = null,
    private val compactionSettingsProvider: (() -> CompactionSettings)? = null,
    private val promptResourceCwd: Path? = null,
    private val extraSystemPromptSectionsProvider: (suspend () -> Map<String, String>)? = null,
    private val defaultShellEnabled: Boolean = true,
    private val defaultMcpEnabled: Boolean = true,
    private val defaultToolsFilter: ((CodingTool) -> Boolean)? = null,
    private val captureMemoryOnClose: Boolean = true,
    private val includePromptResources: Boolean = true,
    private val readOnlyPathsPrompt: Boolean = false,
    private val environmentLinesOverride: (suspend () -> List<String>)? = null,
    private val enableReadOnlyQueryTools: Boolean = false,
    private val memoryScope: () -> MemoryAccessScope = { MemoryAccessScope.PERSONAL },
    private val awaitMemoryReady: (suspend () -> Unit)? = null,
    private val memoryColdRefreshIdleMs: Long = DEFAULT_MEMORY_COLD_REFRESH_IDLE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val extraSkillDirs: List<SkillSourceDir> = emptyList(),
    private val toolInterceptor: ((CodingTool, SessionBinding) -> CodingTool)? = null,
) : SessionEngine {
    val cwd: Path = workspace.absolutePath.toPath()
    val agentDirectory: Path = agentDir.absolutePath.toPath()
    val paths = AgentPaths(cwd, agentDirectory)
    private val sessionBindings = ConcurrentHashMap<String, SessionBinding>()
    private val sessionCleanups = ConcurrentHashMap<String, () -> Unit>()
    private val sessionMemoryRefreshes = ConcurrentHashMap<String, suspend () -> Unit>()

    suspend fun createSession(
        file: Path?,
        config: ModelConfig?,
        shellEnabled: Boolean = true,
        toolsFilter: ((CodingTool) -> Boolean)? = null,
        mcpEnabled: Boolean = true,
        extraSystemPromptSections: Map<String, String> = emptyMap(),
    ): AgentSession {
        runCatching { java.io.File(agentDirectory.toFile(), "SYSTEM.md").delete() }
        if (shellEnabled) {
            hostEnvironment?.let { provider ->
                val environment = provider.current()
                if (environment.access == HostAccess.ROOT && !environment.capabilities.rootLinuxReady) {
                    throw HostAccessUnavailableException("Root Linux is not ready; retry after preparation finishes")
                }
            }
        }
        try {
            memory?.ensure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("Memory", "memory startup failed", error)
        }
        val fallback = providers.resolveConfig(config ?: ModelConfig())
        val restored = if (file == null || !fs.exists(file)) {
            null
        } else {
            runCatching { SessionManager.open(file, fs, cwd).buildSessionContext() }.getOrNull()
        }
        val restoredProviderId = restored?.provider?.takeIf { it.isNotBlank() }
        val restoredModelId = restored?.modelId?.takeIf { it.isNotBlank() }
        val restoredModel = restoredProviderId != null &&
            restoredModelId != null &&
            providers.sdkModel(restoredProviderId, restoredModelId) != null
        val explicitModel = if (restoredModel) null else fallback
        val providerId = if (restoredModel) restoredProviderId else fallback.providerId
        val modelId = if (restoredModel) restoredModelId else fallback.modelId
        val environmentProvider = hostEnvironment
        val activeShell = when {
            !shellEnabled -> null
            environmentProvider != null -> DynamicShellExecutor { environmentProvider.current().bash(cwd.toString()) }
            else -> shellExecutor
        }
        val binding = SessionBinding(supportsChatDelivery)
        binding.bind(BoundSession(sessionId = "", sessionFile = null, cwd = cwd.toString()))
        val hasDeferredPlugins = toolContext?.pluginDeferredTools?.invoke(binding)?.isNotEmpty() == true
        val hasEnabledMcpServers = mcpEnabled && runCatching {
            loadMcpServers().values.any { it.enabled }
        }.getOrDefault(false)
        var created: AgentSession? = null
        val allTools = buildTools(
            activeShell = activeShell,
            binding = binding,
            includeDeferred = hasDeferredPlugins || hasEnabledMcpServers,
        ) { created }
        val tools = if (toolsFilter == null) allTools else allTools.filter(toolsFilter)
        val pinnedMemory = AtomicReference<String?>(null)
        val pinnedMemoryScope = AtomicReference<MemoryAccessScope?>(null)
        val memoryLoaded = AtomicBoolean(false)
        val lastSeenCompactionId = AtomicReference<String?>(null)
        val lastTurnEndedAtMs = AtomicLong(0L)
        fun latestCompactionId(): String? = runCatching {
            created?.sessionManager?.getBranch()?.lastOrNull { it.type == "compaction" }?.id
        }.getOrNull()
        suspend fun loadMemorySection(activeScope: MemoryAccessScope): String? = try {
            if (activeScope == MemoryAccessScope.OFF) {
                null
            } else {
                memory?.section(activeScope)?.takeIf { it.isNotBlank() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("Memory", "memory section failed", error)
            null
        }
        suspend fun refreshPinnedMemoryNow() {
            val activeScope = memoryScope()
            val compactionId = latestCompactionId()
            val reloaded = loadMemorySection(activeScope)
            pinnedMemory.set(reloaded)
            pinnedMemoryScope.set(activeScope)
            lastSeenCompactionId.set(compactionId)
            lastTurnEndedAtMs.set(0L)
            memoryLoaded.set(true)
        }
        suspend fun pluginContextSection(): Map<String, String> {
            val context = hooks.systemContext?.invoke()?.takeIf { it.isNotBlank() } ?: return emptyMap()
            return mapOf("plugin_context" to formatPluginContextSection(context))
        }
        suspend fun memoryContextSections(): Map<String, String> {
            val activeScope = memoryScope()
            if (activeScope == MemoryAccessScope.OFF) {
                pinnedMemory.set(null)
                pinnedMemoryScope.set(null)
                memoryLoaded.set(false)
                return emptyMap()
            }
            if (!memoryLoaded.get()) {
                runCatching { awaitMemoryReady?.invoke() }
                    .onFailure { if (it is CancellationException) throw it }
                val loaded = loadMemorySection(activeScope)
                if (memoryLoaded.compareAndSet(false, true)) {
                    lastSeenCompactionId.set(latestCompactionId())
                    pinnedMemory.set(loaded)
                    pinnedMemoryScope.set(activeScope)
                }
            } else {
                val scopeChanged = pinnedMemoryScope.get() != activeScope
                val currentCompactionId = latestCompactionId()
                val compactionChanged = currentCompactionId != null && currentCompactionId != lastSeenCompactionId.get()
                val lastEnded = lastTurnEndedAtMs.get()
                val now = clock()
                val coldIdleExpired = lastEnded > 0L &&
                    memoryColdRefreshIdleMs > 0L &&
                    now - lastEnded >= memoryColdRefreshIdleMs
                if (scopeChanged || compactionChanged || coldIdleExpired) {
                    val reloaded = loadMemorySection(activeScope)
                    pinnedMemory.set(reloaded)
                    pinnedMemoryScope.set(activeScope)
                    lastSeenCompactionId.set(currentCompactionId)
                    lastTurnEndedAtMs.compareAndSet(lastEnded, 0L)
                }
            }
            val text = pinnedMemory.get() ?: return emptyMap()
            return mapOf("memory" to wrapPromptSection("memory", text))
        }
        val suppressedPromptSections = if (includePromptResources) {
            emptyMap()
        } else {
            mapOf(
                "skills" to "",
                "project_context" to "",
                "addendum" to "",
            )
        }
        val promptResources = if (includePromptResources) {
            PromptResourceSections(
                cwd = promptResourceCwd ?: cwd,
                agentDir = agentDirectory,
                fs = fs,
                excludedSkills = excludedSkills,
                selectedTools = tools.map { it.name },
                extraSkillDirs = extraSkillDirs,
            )
        } else {
            null
        }
        val currentSections: suspend () -> Map<String, String> = {
            val session = created
            val sharedStorageValue = runCatching { sharedStorage() }.getOrDefault(false)
            val mounts = runCatching { storageMounts() }.getOrDefault(emptyList())
            val promptMerge = mergePluginPromptOverrides(
                overrides = runCatching { pluginPromptOverrides() }.getOrDefault(PluginPromptOverrides.None),
                legacyBody = runCatching { pluginPromptBody() }.getOrNull(),
                warn = { message -> logWarn("PluginPrompt", message) },
            )
            val deferredSection = if (tools.any { it.name == "tool_search" }) {
                toolContext?.pluginDeferredTools
                    ?.invoke(binding)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::buildDeferredToolsSection)
                    ?.let { mapOf("deferred_tools" to it) }
                    .orEmpty()
            } else {
                emptyMap()
            }
            buildSessionSystemPromptSections(
                personalization = runCatching { personalization() }.getOrDefault(Personalization()),
                providerId = session?.model?.provider ?: providerId,
                modelId = session?.model?.id ?: modelId,
                now = OffsetDateTime.now(),
                pluginBody = promptMerge.pluginsBody,
                promptPreamble = promptMerge.preamble,
                promptRules = promptMerge.rules,
                environmentLines = environmentLinesOverride?.invoke() ?: hostEnvironment?.current()?.promptLines,
                extra = mapOf(
                    "paths" to wrapPromptSection(
                        "paths",
                        buildPathsSection(
                            skillsDir = (agentDirectory / "skills").toString(),
                            appDataDir = promptAppDataDir?.absolutePath
                                ?: (agentDirectory.parent?.parent ?: agentDirectory).toString(),
                            sharedStorage = sharedStorageValue,
                            mounts = mounts,
                            readOnly = readOnlyPathsPrompt,
                        ),
                    ),
                ) + suppressedPromptSections + promptResources?.current().orEmpty() + deferredSection +
                    memoryContextSections() + pluginContextSection() +
                    extraSystemPromptSectionsProvider?.invoke().orEmpty() + extraSystemPromptSections,
            )
        }
        val session = createAgentSession(
            AgentSessionOptions(
                cwd = cwd,
                agentDir = agentDirectory,
                transport = transport,
                fs = fs,
                model = explicitModel?.let { providers.modelReference(it.providerId, it.modelId) },
                thinkingLevel = explicitModel?.thinkingLevel?.runtimeValue(),
                sessionFile = file,
                tools = tools,
                shellExecutor = activeShell,
                hooks = CodingHooks(extraSystemPromptSections = { currentSections() })
                    .withPluginHooks(hooks.promptTransform),
                credentials = credentials,
                excludedSkills = excludedSkills,
                initialSystemPromptSections = currentSections(),
                projectTrusted = true,
                mcp = McpOptions(
                    enabled = mcpEnabled,
                    loadConfig = true,
                    samplingEnabled = false,
                    rootsEnabled = true,
                    autoEnableToolSearch = false,
                    excludedServers = excludedMcpServers,
                ),
                compactionSettingsProvider = compactionSettingsProvider,
            ),
        )
        created = session
        val memoryUnsubscribe = session.agent.subscribe { event, _ ->
            if (event is AgentEvent.AgentEnd) {
                lastTurnEndedAtMs.set(clock())
            }
        }
        sessionMemoryRefreshes[session.sessionId] = ::refreshPinnedMemoryNow
        sessionCleanups.put(session.sessionId) {
            sessionMemoryRefreshes.remove(session.sessionId)
            memoryUnsubscribe()
        }?.invoke()
        val toolHooks = hooks.toolHooks
        if (toolHooks == null) {
            session.agent.afterToolCall = bridgeAfterToolCall(
                ToolResultPostProcessor(null),
                session.agent.afterToolCall,
            )
        } else {
            session.agent.installToolExecutionHooks(toolHooks.withPostProcessing())
        }
        binding.attach(session)
        sessionBindings[session.sessionId] = binding
        hooks.onSessionBound?.invoke(binding)
        return session
    }

    internal suspend fun capabilitySnapshot(session: AgentSession): CapabilitySnapshot {
        val binding = sessionBindings[session.sessionId]
        val direct = session.activeToolNames
        val mcpDeferred = runCatching { session.mcp.deferredCatalog().map { it.name } }.getOrDefault(emptyList())
        val pluginDeferred = runCatching {
            if (binding == null) emptyList() else toolContext?.pluginDeferredTools?.invoke(binding).orEmpty().map { it.name }
        }.getOrDefault(emptyList())
        val skills = if (!includePromptResources) {
            emptyList()
        } else {
            runCatching {
                val base = loadPromptResources(
                    cwd = promptResourceCwd ?: cwd,
                    agentDir = agentDirectory,
                    fs = fs,
                    projectTrusted = true,
                    excludedSkills = excludedSkills,
                ).skills.map { it.name }
                val extra = if (fs === FileSystem.SYSTEM) {
                    discoverMergedSkills(
                        cwd = (promptResourceCwd ?: cwd).toFile(),
                        agentDir = agentDirectory.toFile(),
                        extraGlobalDirs = extraSkillDirs,
                    ).map { it.skill.name }.filterNot { it in excludedSkills }
                } else {
                    emptyList()
                }
                (base + extra).distinct()
            }.getOrDefault(emptyList())
        }
        return CapabilitySnapshot.of(direct + mcpDeferred + pluginDeferred, skills)
    }

    internal fun capabilityBaseline(session: AgentSession): CapabilitySnapshot? {
        val entries = runCatching { session.sessionManager.getBranch() }.getOrNull() ?: return null
        val baseline = entries.asReversed()
            .filterIsInstance<CustomEntry>()
            .firstOrNull { it.customType == CAPABILITIES_BASELINE_CUSTOM_TYPE }
            ?: return null
        return CapabilityNotices.decodeSnapshot(baseline.data)
    }

    internal suspend fun refreshMemoryAfterCompaction(sessionId: String) {
        sessionMemoryRefreshes[sessionId]?.invoke()
    }

    internal fun releaseSession(sessionId: String) {
        sessionBindings.remove(sessionId)
        sessionCleanups.remove(sessionId)?.invoke()
    }

    internal fun captureMemory(sessionId: String, sessionFile: String?, entries: List<io.github.whywood.superpi.sdk.coding.session.SessionEntry>?) {
        if (!captureMemoryOnClose) return
        memory?.capture(sessionId, sessionFile, entries)
    }

    internal suspend fun prepareInput(text: String, images: List<ImagePart>): PreparedPrompt {
        val handler = hooks.inputHandler ?: return PreparedPrompt(text, images)
        return when (val decision = handler(InputRequest(text, images))) {
            is InputDecision.Continue -> PreparedPrompt(decision.text, decision.images)
            InputDecision.Handled -> PreparedPrompt.Handled
        }
    }

    override suspend fun create(file: String?, config: ModelConfig): SessionHandle =
        PiSessionHandle(
            this,
            createSession(
                file = file?.toPath(),
                config = config,
                shellEnabled = defaultShellEnabled,
                toolsFilter = defaultToolsFilter,
                mcpEnabled = defaultMcpEnabled,
            ),
        )

    fun availableTools(): List<CodingTool> {
        val environmentProvider = hostEnvironment
        val shell = when {
            !defaultShellEnabled -> null
            environmentProvider != null -> DynamicShellExecutor { environmentProvider.current().bash(cwd.toString()) }
            else -> shellExecutor
        }
        val binding = SessionBinding(supportsChatDelivery).also { candidate ->
            candidate.bind(BoundSession(sessionId = "", sessionFile = null, cwd = cwd.toString()))
        }
        val hasDeferredPlugins = toolContext?.pluginDeferredTools?.invoke(binding)?.isNotEmpty() == true
        val built = buildTools(shell, binding, includeDeferred = hasDeferredPlugins) { null }
        val filter = defaultToolsFilter
        return if (filter == null) built else built.filter(filter)
    }

    private fun buildTools(
        activeShell: ShellExecutor?,
        binding: SessionBinding,
        includeDeferred: Boolean = true,
        session: () -> AgentSession?,
    ): List<CodingTool> {
        val rawTools = buildList {
            val environmentProvider = hostEnvironment
            val fileSystem = if (environmentProvider != null) {
                DynamicFileSystem { environmentProvider.current().files }
            } else {
                toolFs
            }
            val defaults = defaultCodingTools(
                cwd = cwd,
                fs = fileSystem,
                mutationQueue = FileMutationQueue(),
                shellExecutor = null,
            )
            defaults.forEach { tool ->
                when (tool.name) {
                    "bash" -> activeShell?.let {
                        add(superPiBashTool(it, environmentProvider?.current()?.shellToolProfile))
                    }
                    "ls" -> if (enableReadOnlyQueryTools) add(superPiLsTool(fileSystem))
                    "grep" -> if (enableReadOnlyQueryTools) add(superPiGrepTool(fileSystem))
                    "find" -> if (enableReadOnlyQueryTools) add(superPiFindTool(fileSystem))
                    else -> add(tool.withAbsolutePaths(fileSystem))
                }
            }
            toolContext?.let { context ->
                addAll(appTools(cwd, context, binding, session, includeDeferred))
            }
        }
        val interceptor = toolInterceptor ?: return rawTools
        return rawTools.map { interceptor(it, binding) }
    }

    private fun formatPluginContextSection(raw: String): String {
        val trimmed = raw.trim()
        val alreadyWrapped = trimmed.startsWith("<plugin_context") && trimmed.endsWith("</plugin_context>")
        if (!alreadyWrapped) {
            val limited = if (trimmed.length <= MAX_PLUGIN_CONTEXT_CHARS) {
                trimmed
            } else {
                val suffix = "\n...(truncated)"
                trimmed.take((MAX_PLUGIN_CONTEXT_CHARS - suffix.length).coerceAtLeast(0)).trimEnd() + suffix
            }
            return wrapPromptSection("plugin_context", limited)
        }
        if (trimmed.length <= MAX_PLUGIN_CONTEXT_CHARS) return trimmed
        val suffix = "\n...(truncated)\n</plugin_context>"
        val cutoff = (MAX_PLUGIN_CONTEXT_CHARS - suffix.length).coerceAtLeast(0)
        return trimmed.take(cutoff).trimEnd() + suffix
    }

    private companion object {
        private const val MAX_PLUGIN_CONTEXT_CHARS = 8_000
        private const val DEFAULT_MEMORY_COLD_REFRESH_IDLE_MS = 45 * 60_000L
    }

    override suspend fun loadMcpServers(): Map<String, McpServerConfig> {
        val settings = SettingsManager.forPaths(paths, fs)
        val loaded = McpConfigLoader(paths, fs, settings).load()
        return loaded.servers.filter { (name, _) -> name !in excludedMcpServers }.toMap()
    }

    suspend fun listSessions(): List<ConversationSummary> = SessionManager.list(paths, fs).map { info ->
        info.toSummary()
    }

    fun history(session: AgentSession): List<AssistantMessage> =
        HistoryMapper.mapEntries(session.sessionManager.getBranch())

    suspend fun deleteSessionFile(filePath: String) {
        runCatching { fs.delete(filePath.toPath()) }
    }

    override fun titleFor(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return "New chat"
        return if (normalized.length <= 28) normalized else normalized.take(28) + "…"
    }

    private fun SessionInfo.toSummary(): ConversationSummary = ConversationSummary(
        id = sessionId,
        title = name?.takeIf { it.isNotBlank() } ?: "New chat",
        updatedAt = parseTimestamp(timestamp),
        filePath = sessionFile.toString(),
    )

    private fun parseTimestamp(value: String): Long = runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}

fun ThinkingLevel.runtimeValue(): String = when (this) {
    ThinkingLevel.OFF -> "off"
    ThinkingLevel.LOW -> "low"
    ThinkingLevel.MEDIUM -> "medium"
    ThinkingLevel.HIGH -> "high"
    ThinkingLevel.XHIGH -> "xhigh"
    ThinkingLevel.MAX -> "max"
}

internal fun thinkingLevelOf(value: String?): ThinkingLevel = when (value?.trim()?.lowercase()) {
    "minimal", "low" -> ThinkingLevel.LOW
    "medium" -> ThinkingLevel.MEDIUM
    "high" -> ThinkingLevel.HIGH
    "xhigh" -> ThinkingLevel.XHIGH
    "max" -> ThinkingLevel.MAX
    else -> ThinkingLevel.OFF
}

internal sealed interface PreparedPrompt {
    data class Ready(
        val text: String,
        val images: List<ImagePart>,
    ) : PreparedPrompt

    data object Handled : PreparedPrompt

    companion object {
        operator fun invoke(text: String, images: List<ImagePart>): PreparedPrompt = Ready(text, images)
    }
}

internal class PiSessionHandle(
    private val engine: PiSessionEngine,
    private val session: AgentSession,
) : SessionHandle {
    override val sessionId: String get() = session.sessionId

    override val sessionFile: String? get() = session.sessionFile?.toString()

    override val isBusy: Boolean get() = session.isBusy

    override val isStreaming: Boolean get() = session.isStreaming

    override val messages: List<AgentMessage> get() = session.messages

    override val activeToolNames: List<String> get() = session.activeToolNames

    override val model: Model get() = session.model

    override val thinkingLevel: String get() = session.thinkingLevel

    override suspend fun prompt(
        text: String,
        images: List<ImagePart>?,
        behavior: QueueBehavior?,
    ) {
        when (val prepared = engine.prepareInput(text, images.orEmpty())) {
            is PreparedPrompt.Ready -> session.prompt(prepared.text, prepared.images.toImageContents(), behavior)
            PreparedPrompt.Handled -> Unit
        }
    }

    override fun abort() {
        session.abort()
    }

    override suspend fun close() {
        val sessionId = session.sessionId
        val entries = try {
            session.sessionManager.getBranch()
        } catch (error: Exception) {
            null
        }
        val sessionFile = session.sessionFile?.toString()
        session.close()
        engine.captureMemory(sessionId, sessionFile, entries)
        engine.releaseSession(sessionId)
        engine.hooks.onSessionClosed?.let { it(sessionId) }
    }

    override fun subscribe(listener: suspend (SessionEvent) -> Unit): () -> Unit =
        session.subscribe(listener)

    override fun clearQueues() {
        session.agent.clearAllQueues()
    }

    override fun removeQueuedInput(text: String, behavior: QueueBehavior): Boolean =
        session.removeQueuedInput(text, behavior)

    override fun canRetryLastAssistant(): Boolean = session.canRetryLastAssistant()

    override suspend fun retryLastAssistant() {
        session.retryLastAssistant()
    }

    override fun lastAssistantText(): String? = session.getLastAssistantText()

    override suspend fun setModel(model: Model, thinkingLevel: String?) {
        session.setModel(model, thinkingLevel)
    }

    override suspend fun setThinkingLevel(level: String) {
        session.setThinkingLevel(level)
    }

    override fun registerProvider(provider: Provider) {
        session.modelRuntime.registerProvider(provider)
    }

    override suspend fun compact(): SessionCompaction? = session.compact()?.let { result ->
        engine.refreshMemoryAfterCompaction(session.sessionId)
        SessionCompaction(result.tokensBefore, result.estimatedTokensAfter)
    }

    override fun history(): List<AssistantMessage> = engine.history(session)

    override fun branchEntries(): List<SessionEntry> = runCatching {
        session.sessionManager.getBranch()
    }.getOrDefault(emptyList())

    override fun usage(): SessionUsage? = runCatching {
        val manager = session.sessionManager
        val branch = manager.getBranch()
        val stats = manager.getSessionStats()
        SessionUsage(
            contextTokens = estimateProjectedContextTokens(buildSessionProjection(branch), branch).tokens,
            contextWindow = session.model.contextWindow,
            inputTokens = stats.inputTokens,
            outputTokens = stats.outputTokens,
            cacheReadTokens = stats.cacheReadTokens,
            cacheWriteTokens = stats.cacheWriteTokens,
            cost = stats.totalCost,
        )
    }.getOrNull()

    override fun sessionName(): String? = session.sessionManager.getSessionName()

    override suspend fun appendTitle(title: String) {
        session.sessionManager.appendSessionInfo(title)
    }

    override suspend fun appendCustomMessage(customType: String, payloadJson: String, text: String, display: Boolean) {
        val details = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payloadJson) }.getOrNull()
        session.appendCustomMessage(customType, io.github.whywood.superpi.sdk.ai.PlainTextContent(text), display, details)
    }

    override suspend fun appendCustomEntry(customType: String, dataJson: String) {
        val data = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(dataJson) }.getOrNull()
        session.sessionManager.appendCustomEntry(customType, data)
    }

    override suspend fun capabilitySnapshot(): CapabilitySnapshot = engine.capabilitySnapshot(session)

    override fun capabilityBaseline(): CapabilitySnapshot? = engine.capabilityBaseline(session)

    override suspend fun reloadMcp(servers: Map<String, McpServerConfig>) {
        session.mcp.reload(servers)
    }

    override suspend fun reconnectMcp(server: String) {
        session.mcp.reconnect(server)
    }

    override fun mcpServers(): List<SessionMcpServerStatus> = session.mcp.servers.map { status ->
        SessionMcpServerStatus(
            name = status.name,
            state = status.state.name,
            toolCount = status.toolCount,
            lastError = status.lastError,
        )
    }

    override fun deferredMcpToolCount(): Int = session.mcp.deferredCatalog().size
}
