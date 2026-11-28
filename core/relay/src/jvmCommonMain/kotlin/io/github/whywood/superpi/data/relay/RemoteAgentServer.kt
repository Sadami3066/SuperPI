package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.data.AGENT_DOC_MAX_BYTES
import io.github.whywood.superpi.data.AgentDocStore
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.WorkspaceToolPolicyStore
import io.github.whywood.superpi.remote.RemoteMirrorRegistry
import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.CompactionOutcome
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SessionRuntimeEvent
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.invocationKey
import io.github.whywood.superpi.runtime.resolveThinkingLevel
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RemoteCommandSpec(
    val name: String,
    val description: String,
    val source: String,
)

data class RemoteCapabilityPluginInfo(
    val id: String,
    val name: String,
)

data class RemoteCapabilitiesInventory(
    val skills: List<String> = emptyList(),
    val plugins: List<RemoteCapabilityPluginInfo> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val screenToolsReady: Boolean = false,
)

class RemoteAgentServer(
    private val host: AgentRuntime,
    private val bots: suspend () -> List<RelayBot>,
    private val send: (JsonObject) -> Boolean,
    private val scope: CoroutineScope,
    private val maxBufferedEvents: Int = MAX_BUFFERED_EVENTS,
    private val questionCenter: QuestionCenter? = null,
    private val onBotsChanged: () -> Unit = {},
    private val onSyncEvent: (RelaySyncEvent) -> Unit = {},
    private val fileHandler: RemoteWorkspaceFileHandler = RemoteWorkspaceFileHandler(),
    private val avatarStorageDir: File? = null,
    private val providerCatalog: () -> ProviderDirectory? = { null },
    private val configuredProviderIds: () -> Set<String> = { emptySet() },
    private val agentDocStore: () -> AgentDocStore? = { null },
    private val slashCommands: suspend (String) -> List<RemoteCommandSpec> = { emptyList() },
    private val workspaceToolPolicies: () -> WorkspaceToolPolicyStore? = { null },
    private val installedCapabilities: suspend (String) -> RemoteCapabilitiesInventory = { RemoteCapabilitiesInventory() },
    private val expandSkillPrompt: suspend (String, String) -> String = { _, text -> text },
    localWorkerPool: LocalWorkerPool? = null,
) {
    private val feeds = ConcurrentHashMap<String, Feed>()
    private val botWatchers = ConcurrentHashMap<String, BotWatcher>()
    private val pendingOriginRouteId = ConcurrentHashMap<String, String>()
    private val pendingUserMessages = ConcurrentHashMap<String, JsonObject>()
    private val sessionBots = ConcurrentHashMap<String, String>()
    private val activeQuestions = ConcurrentHashMap<String, PendingQuestion>()
    private val suppressedSessionSync = ConcurrentHashMap<String, Long>()
    private val presentedArtifacts = PresentedArtifactRegistry()
    private val questionMutex = Mutex()
    private val workerServer: RemoteWorkerServer? = localWorkerPool?.let { pool ->
        RemoteWorkerServer(
            pool = pool,
            bots = bots,
            send = send,
            scope = scope,
            presentedArtifacts = presentedArtifacts,
            resolveBotCwd = { id -> localWorkspaceById(id)?.cwd },
        )
    }

    init {
        scope.launch {
            host.events.collect { event -> onRuntimeEvent(event) }
        }
        if (questionCenter != null) {
            scope.launch {
                questionCenter.pending.collect { current -> onPendingQuestionsChanged(current) }
            }
        }
    }

    fun reset() {
        fileHandler.dispose()
        presentedArtifacts.clear()
        workerServer?.reset()
        for ((_, feed) in feeds) {
            synchronized(feed) {
                feed.active = false
                feed.routeIds.clear()
            }
        }
        feeds.clear()
        botWatchers.clear()
        pendingOriginRouteId.clear()
        pendingUserMessages.clear()
        sessionBots.clear()
        activeQuestions.clear()
        suppressedSessionSync.clear()
    }

    fun onPeerOffline(deviceId: String) {
        if (deviceId.isBlank()) return
        workerServer?.onPeerOffline(deviceId)
        botWatchers.entries.removeIf { it.value.deviceId == deviceId }
        for (feed in feeds.values) {
            synchronized(feed) {
                feed.routeIds.entries.removeIf { it.value == deviceId }
            }
        }
        pruneFinishedFeeds()
    }

    fun onRouteRejected(routeId: String) {
        if (routeId.isBlank()) return
        workerServer?.onRouteRejected(routeId)
        botWatchers.remove(routeId)
        for (feed in feeds.values) {
            synchronized(feed) {
                feed.routeIds.remove(routeId)
            }
        }
        pruneFinishedFeeds()
    }

    suspend fun handle(frame: RelayServerFrame.Route) {
        if (frame.method.startsWith("worker.")) {
            val workerHandler = workerServer
            if (workerHandler == null) {
                reject(frame, "bad_request", "worker routes are not enabled")
            } else {
                workerHandler.handle(frame)
            }
            return
        }
        try {
            when (frame.method) {
                "bot.prompt" -> prompt(frame)
                "bot.steer" -> steer(frame)
                "bot.follow_up" -> followUp(frame)
                "bot.abort" -> abort(frame)
                "bot.state" -> state(frame)
                "bot.models" -> models(frame)
                "bot.set_model" -> setModel(frame)
                "bot.set_thinking" -> setThinking(frame)
                "bot.commands" -> commands(frame)
                "bot.instructions.get" -> getInstructions(frame)
                "bot.instructions.set" -> setInstructions(frame)
                "bot.capabilities.get" -> getCapabilities(frame)
                "bot.capabilities.set" -> setCapabilities(frame)
                "bot.reload" -> reloadBot(frame)
                "bot.resume" -> resume(frame)
                "bot.sessions" -> listSessions(frame)
                "bot.history" -> history(frame)
                "bot.approval" -> approval(frame)
                "bot.create" -> createBot(frame)
                "bot.update" -> updateBot(frame)
                "bot.delete" -> deleteBot(frame)
                "session.create" -> createSession(frame)
                "session.rename" -> renameSession(frame)
                "session.delete" -> deleteSession(frame)
                "session.update_meta" -> updateSessionMeta(frame)
                "session.compact" -> compactSession(frame)
                "session.retry" -> retrySession(frame)
                "file.list" -> fileList(frame)
                "file.stat" -> fileStat(frame)
                "file.read_chunk" -> fileReadChunk(frame)
                "file.write_chunk" -> fileWriteChunk(frame)
                "file.mkdir" -> fileMkdir(frame)
                "file.rename" -> fileRename(frame)
                "file.delete" -> fileDelete(frame)
                "artifact.read_chunk" -> readArtifactChunk(frame)
                else -> reject(frame, "bad_request", "unknown method ${frame.method}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RemoteFileException) {
            respondError(frame.id, error.code, error.message)
        } catch (error: IllegalArgumentException) {
            respondError(frame.id, "bad_request", error.message ?: "invalid request")
        } catch (error: Exception) {
            respondError(frame.id, "internal", error.message ?: "the bot failed")
        }
    }

    private suspend fun prompt(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId")
        if (botId.isBlank() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isBlank()) "botId is required" else "bot $botId is not available")
            return
        }
        val rawText = frame.params.string("text")
        val images = parseImageParts(frame.params)
        if (rawText.isBlank() && images.isEmpty()) {
            reject(frame, "bad_request", "text or images is required")
            return
        }
        val text = if (rawText.isNotBlank()) {
            try {
                expandSkillPrompt(localWorkspaceById(botId)?.cwd.orEmpty(), rawText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                reject(frame, "bad_request", error.message ?: "invalid skill command")
                return
            }
        } else {
            rawText
        }
        val existing = frame.params.string("sessionId").ifBlank { null }
        val requestedProvider = frame.params.optionalTrimmedString("provider")
        val requestedModelId = frame.params.optionalTrimmedString("modelId")
        val requestedThinkingRaw = frame.params.optionalTrimmedString("thinkingLevel")
        var validatedModel: Pair<String, String>? = null
        var validatedThinking: ThinkingLevel? = null
        if (existing == null && (requestedProvider != null || requestedModelId != null || requestedThinkingRaw != null)) {
            if ((requestedProvider != null) != (requestedModelId != null)) {
                reject(frame, "bad_request", "provider and modelId must both be provided")
                return
            }
            if (requestedThinkingRaw != null) {
                validatedThinking = parseThinkingLevel(requestedThinkingRaw) ?: run {
                    reject(frame, "bad_request", "Invalid thinkingLevel: $requestedThinkingRaw")
                    return
                }
            }
            if (requestedProvider != null && requestedModelId != null) {
                val configured = configuredProviderIds()
                if (configured.isNotEmpty() && requestedProvider !in configured) {
                    reject(frame, "bad_request", "Provider is not configured: $requestedProvider")
                    return
                }
                val catalog = providerCatalog()
                val option = catalog?.models(requestedProvider)?.firstOrNull { it.id == requestedModelId }
                if (catalog != null && catalog.summaries.isNotEmpty() && option == null) {
                    reject(frame, "bad_request", "Unknown model: $requestedProvider/$requestedModelId")
                    return
                }
                validatedModel = requestedProvider to requestedModelId
            }
        }
        val sessionId = if (existing != null) {
            resolveExistingSession(botId, existing) ?: run {
                reject(frame, "session_not_found", "session $existing was not found")
                return
            }
        } else {
            host.newSession(botId).sessionId.also { created -> sessionBots[created] = botId }
        }
        if (existing == null && (validatedModel != null || validatedThinking != null)) {
            val defaultCfg = effectiveDefaultConfig(botId)
            val targetProvider = validatedModel?.first ?: defaultCfg.providerId
            val targetModelId = validatedModel?.second ?: defaultCfg.modelId
            val option = if (targetProvider.isNotBlank() && targetModelId.isNotBlank()) {
                providerCatalog()?.models(targetProvider)?.firstOrNull { it.id == targetModelId }
            } else {
                null
            }
            val baseThinking = validatedThinking ?: defaultCfg.thinkingLevel
            val resolvedThinking = if (option != null && option.thinkingLevels.isNotEmpty()) {
                option.thinkingLevels.resolveThinkingLevel(baseThinking)
            } else {
                baseThinking
            }
            if (validatedModel != null) {
                host.setSessionModel(sessionId, targetProvider, targetModelId, resolvedThinking)
            } else {
                host.setSessionThinkingLevel(sessionId, resolvedThinking)
            }
        }
        val alreadyBusy = host.busySessionIds.value.contains(sessionId) || feeds[sessionId]?.active == true
        val feed = ensureActiveFeed(sessionId, frame.id, frame.from).also {
            if (!alreadyBusy) pruneFinishedFeeds()
        }
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        if (alreadyBusy) {
            if (text.isNotBlank() || images.isNotEmpty()) {
                emitFeedEvents(
                    feed,
                    listOf(queueUpdateUserMessageEvent(sessionId, text, images)),
                    terminal = false,
                    excludeRouteId = frame.id,
                    excludeDeviceId = frame.from,
                )
            }
            try {
                host.followUp(sessionId, text, images)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emitFailureEvents(sessionId, feed, error.message ?: "the bot failed")
            }
            return
        }
        pendingOriginRouteId[sessionId] = frame.id
        pendingUserMessages[sessionId] = userMessagePayload(text, images)
        try {
            host.send(sessionId, text, images)
        } catch (cancelled: CancellationException) {
            pendingOriginRouteId.remove(sessionId, frame.id)
            pendingUserMessages.remove(sessionId)
            throw cancelled
        } catch (error: Exception) {
            pendingOriginRouteId.remove(sessionId, frame.id)
            pendingUserMessages.remove(sessionId)
            emitFailureEvents(sessionId, feed, error.message ?: "the bot failed")
        }
    }

    private suspend fun resolveExistingSession(botId: String, existing: String): String? {
        val owner = sessionBots[existing]
        if (owner != null) {
            if (owner != botId) return null
        } else if (host.sessions(botId).none { !it.archived && it.id == existing }) {
            return null
        }
        sessionBots[existing] = botId
        if (host.history(existing).isEmpty() && !feeds.containsKey(existing) && !host.busySessionIds.value.contains(existing)) {
            val summary = host.sessions(botId).firstOrNull { !it.archived && it.id == existing }
            if (summary != null) {
                val reopened = host.openSession(summary.filePath, summary.cwd).sessionId
                sessionBots[reopened] = botId
                return reopened
            }
        }
        return existing
    }

    private suspend fun steer(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        val text = frame.params.string("text")
        val images = parseImageParts(frame.params)
        if (sessionId.isBlank() || (text.isBlank() && images.isEmpty())) {
            reject(frame, "bad_request", "sessionId and text or images are required")
            return
        }
        val botId = frame.params.string("botId")
        if (!verifySessionAccess(botId, sessionId, frame)) return
        val feed = feeds[sessionId] ?: if (host.busySessionIds.value.contains(sessionId)) {
            ensureActiveFeed(sessionId, null)
        } else {
            null
        }
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        if (feed != null && (text.isNotBlank() || images.isNotEmpty())) {
            emitFeedEvents(
                feed,
                listOf(queueUpdateUserMessageEvent(sessionId, text, images)),
                terminal = false,
                excludeRouteId = frame.id,
                excludeDeviceId = frame.from,
            )
        }
        try {
            host.steer(sessionId, text, images)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (feed != null) emitFailureEvents(sessionId, feed, error.message ?: "the bot failed")
        }
    }

    private suspend fun followUp(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        val text = frame.params.string("text")
        val images = parseImageParts(frame.params)
        if (sessionId.isBlank() || (text.isBlank() && images.isEmpty())) {
            reject(frame, "bad_request", "sessionId and text or images are required")
            return
        }
        val botId = frame.params.string("botId")
        if (!verifySessionAccess(botId, sessionId, frame)) return
        val feed = feeds[sessionId] ?: if (host.busySessionIds.value.contains(sessionId)) {
            ensureActiveFeed(sessionId, null)
        } else {
            null
        }
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        if (feed != null && (text.isNotBlank() || images.isNotEmpty())) {
            emitFeedEvents(
                feed,
                listOf(queueUpdateUserMessageEvent(sessionId, text, images)),
                terminal = false,
                excludeRouteId = frame.id,
                excludeDeviceId = frame.from,
            )
        }
        try {
            host.followUp(sessionId, text, images)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (feed != null) emitFailureEvents(sessionId, feed, error.message ?: "the bot failed")
        }
    }

    private fun ensureActiveFeed(
        sessionId: String,
        routeId: String?,
        fromDeviceId: String = "",
    ): Feed {
        return feeds.compute(sessionId) { _, existing ->
            val feed = existing ?: Feed(
                seq = AtomicLong(0),
                updatedAt = System.currentTimeMillis(),
                active = false,
            )
            synchronized(feed) {
                val wasActive = feed.active
                if (!wasActive) {
                    feed.turnStartSeq = feed.seq.get()
                }
                feed.active = true
                bindWatchersLocked(feed, sessionId, replaceExistingDeviceRoute = !wasActive)
                if (routeId != null) {
                    if (fromDeviceId.isNotBlank()) {
                        feed.routeIds.entries.removeIf { it.value == fromDeviceId }
                    }
                    feed.routeIds[routeId] = fromDeviceId
                }
            }
            feed
        }!!
    }

    private fun ensureWatchFeed(sessionId: String): Feed =
        feeds.computeIfAbsent(sessionId) {
            Feed(
                seq = AtomicLong(0),
                updatedAt = System.currentTimeMillis(),
                active = false,
            )
        }

    private fun registerWatcher(
        routeId: String,
        deviceId: String,
        botId: String?,
        boundSessionId: String?,
    ) {
        if (deviceId.isNotBlank()) {
            botWatchers.entries.removeIf { (_, existing) ->
                existing.deviceId == deviceId &&
                    existing.botId == botId &&
                    existing.boundSessionId == boundSessionId
            }
        }
        botWatchers[routeId] = BotWatcher(
            routeId = routeId,
            deviceId = deviceId,
            botId = botId,
            boundSessionId = boundSessionId,
        )
    }

    private fun bindWatchersLocked(
        feed: Feed,
        sessionId: String,
        replaceExistingDeviceRoute: Boolean,
    ) {
        val ownerBotId = sessionBots[sessionId]
        val matching = botWatchers.values.filter { watcher ->
            if (watcher.boundSessionId != null) {
                watcher.boundSessionId == sessionId
            } else {
                watcher.botId == null || ownerBotId == null || watcher.botId == ownerBotId
            }
        }.sortedBy { if (it.boundSessionId == null) 0 else 1 }
        for (watcher in matching) {
            if (watcher.deviceId.isNotBlank()) {
                if (!replaceExistingDeviceRoute && feed.routeIds.values.any { it == watcher.deviceId }) {
                    continue
                }
                feed.routeIds.entries.removeIf { it.value == watcher.deviceId }
            }
            feed.routeIds[watcher.routeId] = watcher.deviceId
        }
    }

    private suspend fun resolveSessionBotId(sessionId: String): String? {
        sessionBots[sessionId]?.let { return it }
        for (bot in bots()) {
            if (host.sessions(bot.id).any { !it.archived && it.id == sessionId }) {
                return sessionBots.putIfAbsent(sessionId, bot.id) ?: bot.id
            }
        }
        return null
    }

    private suspend fun findActiveSessionId(botId: String?): String? {
        val activeFeedId = feeds.entries
            .asSequence()
            .filter { (id, feed) ->
                feed.active && (botId == null || sessionBots[id] == null || sessionBots[id] == botId)
            }
            .sortedByDescending { it.value.updatedAt }
            .map { it.key }
            .firstOrNull { !isRemoteMirrorSession(it) }
        if (activeFeedId != null) return activeFeedId
        for (id in host.busySessionIds.value) {
            if (isRemoteMirrorSession(id)) continue
            if (botId == null || sessionBots[id] == botId) return id
            if (host.sessions(botId).any { !it.archived && it.id == id }) {
                sessionBots.putIfAbsent(id, botId)
                return id
            }
        }
        return null
    }

    private suspend fun verifySessionAccess(
        botId: String,
        sessionId: String,
        frame: RelayServerFrame.Route,
    ): Boolean {
        if (isRemoteMirrorSession(sessionId)) {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return false
        }
        if (botId.isNotBlank()) {
            if (bots().none { it.id == botId }) {
                reject(frame, "bot_not_found", "bot $botId is not available")
                return false
            }
            val owner = sessionBots[sessionId]
            if (owner != null && owner != botId) {
                reject(frame, "session_not_found", "session $sessionId was not found")
                return false
            }
            if (owner == null && host.sessions(botId).none { !it.archived && it.id == sessionId }) {
                reject(frame, "session_not_found", "session $sessionId was not found")
                return false
            }
            sessionBots.putIfAbsent(sessionId, botId)
            return true
        }
        if (!isKnownLocalSession(sessionId)) {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return false
        }
        return true
    }

    private suspend fun isKnownLocalSession(sessionId: String): Boolean {
        if (isRemoteMirrorSession(sessionId)) return false
        if (sessionBots.containsKey(sessionId)) return true
        return bots().any { bot ->
            host.sessions(bot.id).any { !it.archived && it.id == sessionId }.also { found ->
                if (found) sessionBots.putIfAbsent(sessionId, bot.id)
            }
        }
    }

    private fun parseImageParts(params: JsonObject): List<ImagePart> =
        RelayFrames.parseImages(params).map { ImagePart(data = it.data, mimeType = it.mimeType) }

    private fun emitFailureEvents(sessionId: String, feed: Feed, message: String) {
        val events = listOf(
            buildJsonObject {
                put("type", "error")
                put("message", message)
            },
            buildJsonObject { put("type", "agent_settled") },
        )
        emitFeedEvents(feed, events, terminal = true)
        if (feeds[sessionId] === feed) pruneFinishedFeeds()
    }

    private suspend fun abort(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val botId = frame.params.string("botId")
        if (!verifySessionAccess(botId, sessionId, frame)) return
        val stopped = host.stop(sessionId)
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("stopped", stopped.size)
            },
        )
    }

    private suspend fun state(frame: RelayServerFrame.Route) {
        val requestedSessionId = frame.params.string("sessionId").trim()
        if (requestedSessionId.isEmpty()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val botId = frame.params.string("botId").trim()
        val resolvedSessionId = if (requestedSessionId == ACTIVE_SESSION_SENTINEL) {
            if (botId.isNotEmpty() && bots().none { it.id == botId }) {
                reject(frame, "bot_not_found", "bot $botId is not available")
                return
            }
            findActiveSessionId(botId.ifEmpty { null }).orEmpty()
        } else {
            if (!verifySessionAccess(botId, requestedSessionId, frame)) return
            requestedSessionId
        }
        if (questionCenter != null) {
            onPendingQuestionsChanged(questionCenter.pending.value)
        }
        val busy = resolvedSessionId.isNotEmpty() &&
            (host.busySessionIds.value.contains(resolvedSessionId) || feeds[resolvedSessionId]?.active == true)
        val modelSnap = if (resolvedSessionId.isNotEmpty()) host.sessionModel(resolvedSessionId) else null
        val fallbackConfig = if (botId.isNotEmpty()) effectiveDefaultConfig(botId) else host.currentConfig()
        val providerId = modelSnap?.providerId ?: fallbackConfig.providerId
        val modelId = modelSnap?.modelId ?: fallbackConfig.modelId
        val thinkingLevel = (modelSnap?.thinkingLevel ?: fallbackConfig.thinkingLevel).name.lowercase()
        val catalog = providerCatalog()
        val displayName = catalog?.models(providerId)?.firstOrNull { it.id == modelId }?.displayName ?: modelId
        val usage = if (resolvedSessionId.isNotEmpty()) host.usage(resolvedSessionId) else null
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", resolvedSessionId)
                put("busy", busy)
                if (providerId.isNotBlank() && modelId.isNotBlank()) {
                    put("model", serializeModelRef(providerId, modelId, displayName))
                }
                put("thinkingLevel", thinkingLevel)
                usage?.let { put("usage", serializeUsage(it)) }
            },
        )
    }

    private suspend fun models(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val catalog = providerCatalog() ?: run {
            reject(frame, "permission_denied", "Model selection is not available on this device")
            return
        }
        val configured = configuredProviderIds()
        val summaries = catalog.summaries.filter { configured.isEmpty() || it.id in configured }
        val requestedSessionId = frame.params.optionalTrimmedString("sessionId")
        val resolvedSessionId = if (requestedSessionId != null && requestedSessionId != ACTIVE_SESSION_SENTINEL) {
            resolveExistingSession(botId, requestedSessionId)
        } else {
            findActiveSessionId(botId) ?: host.sessions(botId).firstOrNull { !it.archived }?.id
        }
        val snap = resolvedSessionId?.let { host.sessionModel(it) }
        val fallback = effectiveDefaultConfig(botId)
        val currentProviderId = snap?.providerId ?: fallback.providerId
        val currentModelId = snap?.modelId ?: fallback.modelId
        val currentThinking = snap?.thinkingLevel ?: fallback.thinkingLevel
        val allModels = summaries.flatMap { summary -> catalog.models(summary.id) }
        val currentModelOption = allModels.firstOrNull { it.providerId == currentProviderId && it.id == currentModelId }
        val currentLevels = (currentModelOption?.thinkingLevels?.takeIf { it.isNotEmpty() } ?: ThinkingLevel.entries)
        respondOk(
            frame.id,
            buildJsonObject {
                put(
                    "models",
                    buildJsonArray {
                        for (opt in allModels) {
                            addJsonObject {
                                put("provider", opt.providerId)
                                put("id", opt.id)
                                put("name", opt.displayName)
                                put("reasoning", opt.thinkingLevels.any { it != ThinkingLevel.OFF })
                                put(
                                    "thinkingLevels",
                                    buildJsonArray {
                                        for (level in opt.thinkingLevels) {
                                            add(JsonPrimitive(level.name.lowercase()))
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
                put(
                    "providers",
                    buildJsonArray {
                        for (summary in summaries) {
                            addJsonObject {
                                put("id", summary.id)
                                put("name", summary.name)
                                put("modelCount", summary.modelCount)
                                summary.baseUrl?.let { put("baseUrl", it) }
                                put("hasOAuth", summary.hasOAuth)
                                put("supportsApiKey", summary.supportsApiKey)
                            }
                        }
                    },
                )
                put(
                    "thinkingLevels",
                    buildJsonArray {
                        for (level in currentLevels) {
                            add(JsonPrimitive(level.name.lowercase()))
                        }
                    },
                )
                if (currentProviderId.isNotBlank() && currentModelId.isNotBlank()) {
                    put(
                        "model",
                        serializeModelRef(
                            currentProviderId,
                            currentModelId,
                            currentModelOption?.displayName ?: currentModelId,
                        ),
                    )
                }
                put("thinkingLevel", currentThinking.name.lowercase())
            },
        )
    }

    private suspend fun setModel(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val provider = frame.params.string("provider").trim()
        val modelId = frame.params.string("modelId").trim()
        if (provider.isEmpty() || modelId.isEmpty()) {
            reject(frame, "bad_request", "provider and modelId are required")
            return
        }
        val configured = configuredProviderIds()
        if (configured.isNotEmpty() && provider !in configured) {
            reject(frame, "bad_request", "Provider is not configured: $provider")
            return
        }
        val catalog = providerCatalog()
        val option = catalog?.models(provider)?.firstOrNull { it.id == modelId }
        if (catalog != null && catalog.summaries.isNotEmpty() && option == null) {
            reject(frame, "bad_request", "Unknown model: $provider/$modelId")
            return
        }
        val requestedThinking = frame.params.optionalTrimmedString("thinkingLevel")?.let { raw ->
            parseThinkingLevel(raw) ?: run {
                reject(frame, "bad_request", "Invalid thinkingLevel: $raw")
                return
            }
        }
        val requestedSessionId = frame.params.optionalTrimmedString("sessionId")
        val explicitSession = requestedSessionId != null && requestedSessionId != ACTIVE_SESSION_SENTINEL
        if (explicitSession) {
            val resolved = resolveExistingSession(botId, requestedSessionId) ?: run {
                reject(frame, "session_not_found", "session $requestedSessionId was not found")
                return
            }
            if (host.busySessionIds.value.contains(resolved) || feeds[resolved]?.active == true) {
                reject(frame, "conflict", "Cannot change model while a turn is running")
                return
            }
        } else if (isBotBusy(botId)) {
            reject(frame, "conflict", "Cannot change model while a turn is running")
            return
        }
        val sessionId = resolveControlSessionId(frame, botId, allowCreate = true, failIfMissing = true) ?: return
        val baseThinking = requestedThinking ?: host.sessionModel(sessionId)?.thinkingLevel ?: effectiveDefaultConfig(botId).thinkingLevel
        val resolvedThinking = if (option != null && option.thinkingLevels.isNotEmpty()) {
            option.thinkingLevels.resolveThinkingLevel(baseThinking)
        } else {
            baseThinking
        }
        host.setSessionModel(sessionId, provider, modelId, resolvedThinking)
        val thinkingStr = resolvedThinking.name.lowercase()
        onSyncEvent(
            RelaySyncEvent(
                kind = "model_changed",
                botId = botId,
                sessionId = sessionId,
                provider = provider,
                modelId = modelId,
                thinkingLevel = thinkingStr,
            ),
        )
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("model", serializeModelRef(provider, modelId, option?.displayName ?: modelId))
                put("thinkingLevel", thinkingStr)
            },
        )
    }

    private suspend fun setThinking(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val rawLevel = frame.params.string("thinkingLevel").trim()
        val parsed = parseThinkingLevel(rawLevel) ?: run {
            reject(frame, "bad_request", "Invalid thinkingLevel: $rawLevel")
            return
        }
        val requestedSessionId = frame.params.optionalTrimmedString("sessionId")
        val explicitSession = requestedSessionId != null && requestedSessionId != ACTIVE_SESSION_SENTINEL
        if (explicitSession) {
            val resolved = resolveExistingSession(botId, requestedSessionId) ?: run {
                reject(frame, "session_not_found", "session $requestedSessionId was not found")
                return
            }
            if (host.busySessionIds.value.contains(resolved) || feeds[resolved]?.active == true) {
                reject(frame, "conflict", "Cannot change thinking level while a turn is running")
                return
            }
        } else if (isBotBusy(botId)) {
            reject(frame, "conflict", "Cannot change thinking level while a turn is running")
            return
        }
        val sessionId = resolveControlSessionId(frame, botId, allowCreate = true, failIfMissing = true) ?: return
        val snap = host.sessionModel(sessionId)
        val fallback = effectiveDefaultConfig(botId)
        val providerId = snap?.providerId ?: fallback.providerId
        val modelId = snap?.modelId ?: fallback.modelId
        val option = if (providerId.isNotBlank() && modelId.isNotBlank()) {
            providerCatalog()?.models(providerId)?.firstOrNull { it.id == modelId }
        } else {
            null
        }
        val resolvedThinking = if (option != null && option.thinkingLevels.isNotEmpty()) {
            option.thinkingLevels.resolveThinkingLevel(parsed)
        } else {
            parsed
        }
        host.setSessionThinkingLevel(sessionId, resolvedThinking)
        val thinkingStr = resolvedThinking.name.lowercase()
        onSyncEvent(
            RelaySyncEvent(
                kind = "model_changed",
                botId = botId,
                sessionId = sessionId,
                provider = providerId.takeIf { it.isNotBlank() },
                modelId = modelId.takeIf { it.isNotBlank() },
                thinkingLevel = thinkingStr,
            ),
        )
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("thinkingLevel", thinkingStr)
            },
        )
    }

    private suspend fun commands(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val cwd = localWorkspaceById(botId)?.cwd.orEmpty()
        val items = slashCommands(cwd)
        respondOk(
            frame.id,
            buildJsonObject {
                put(
                    "commands",
                    buildJsonArray {
                        for (item in items) {
                            addJsonObject {
                                put("name", item.name)
                                put("description", item.description)
                                put("source", item.source)
                            }
                        }
                    },
                )
            },
        )
    }

    private suspend fun getInstructions(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val store = agentDocStore() ?: run {
            reject(frame, "permission_denied", "Bot instructions are not available on this device")
            return
        }
        val doc = store.read(cwd)
        respondOk(
            frame.id,
            buildJsonObject {
                put("content", doc?.content.orEmpty())
            },
        )
    }

    private suspend fun setInstructions(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val store = agentDocStore() ?: run {
            reject(frame, "permission_denied", "Bot instructions are not available on this device")
            return
        }
        val rawContent = frame.params["content"] as? JsonPrimitive
        if (rawContent == null || !rawContent.isString) {
            reject(frame, "bad_request", "content must be a string")
            return
        }
        val content = rawContent.content
        if (content.toByteArray(Charsets.UTF_8).size > AGENT_DOC_MAX_BYTES) {
            reject(frame, "payload_too_large", "instructions exceed 64 KiB limit")
            return
        }
        val saved = store.save(cwd, content)
        if (!saved) {
            reject(frame, "payload_too_large", "instructions exceed 64 KiB limit")
            return
        }
        respondOk(
            frame.id,
            buildJsonObject {
                put("content", content)
                put("saved", true)
            },
        )
    }

    private suspend fun getCapabilities(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val workspace = localWorkspaceById(botId) ?: run {
            reject(frame, "bot_not_found", "bot $botId is not available")
            return
        }
        val store = workspaceToolPolicies() ?: run {
            reject(frame, "permission_denied", "Bot capabilities are not available on this device")
            return
        }
        val stored = store.policyForCwd(workspace.cwd)
        val policy = if (workspace.isHome) stored.copy(allCapabilities = true) else stored
        val inventory = installedCapabilities(workspace.cwd)
        respondOk(frame.id, serializeCapabilitiesSnapshot(workspace, policy, inventory))
    }

    private suspend fun setCapabilities(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val workspace = localWorkspaceById(botId) ?: run {
            reject(frame, "bot_not_found", "bot $botId is not available")
            return
        }
        val store = workspaceToolPolicies() ?: run {
            reject(frame, "permission_denied", "Bot capabilities are not available on this device")
            return
        }
        val policyObj = frame.params["policy"] as? JsonObject ?: run {
            reject(frame, "bad_request", "policy must be an object")
            return
        }
        val currentStored = store.policyForCwd(workspace.cwd)
        val current = if (workspace.isHome) currentStored.copy(allCapabilities = true) else currentStored
        val next = parseCapabilityPolicy(policyObj, current, isHome = workspace.isHome) ?: run {
            reject(frame, "bad_request", "invalid capability policy")
            return
        }
        store.update(workspace.cwd, next)
        if (!isBotBusy(botId)) {
            val activeId = findActiveSessionId(botId) ?: host.sessions(botId).firstOrNull { !it.archived }?.let {
                resolveExistingSession(botId, it.id)
            }
            if (activeId != null) {
                runCatching { host.reloadSession(activeId).also { sessionBots[it] = botId } }
            }
        }
        onSyncEvent(RelaySyncEvent(kind = "capabilities_changed", botId = workspace.id))
        val inventory = installedCapabilities(workspace.cwd)
        respondOk(frame.id, serializeCapabilitiesSnapshot(workspace, next, inventory))
    }

    private fun parseCapabilityPolicy(
        json: JsonObject,
        fallback: WorkspaceToolPolicy,
        isHome: Boolean,
    ): WorkspaceToolPolicy? {
        fun parseBool(key: String, default: Boolean): Boolean? {
            val elem = json[key] ?: return default
            val prim = elem as? JsonPrimitive ?: return null
            return prim.booleanOrNull
        }
        fun parseStringSet(key: String, default: Set<String>): Set<String>? {
            val elem = json[key] ?: return default
            val arr = elem as? JsonArray ?: return null
            if (arr.size > 500) return null
            val out = LinkedHashSet<String>(arr.size)
            for (item in arr) {
                val prim = item as? JsonPrimitive ?: return null
                if (!prim.isString) return null
                val s = prim.content.trim()
                if (s.length > 256) return null
                if (s.isNotEmpty()) out += s
            }
            return out
        }
        val requestedAll = parseBool("allCapabilities", fallback.allCapabilities) ?: return null
        val skills = parseStringSet("skills", fallback.skills) ?: return null
        val plugins = parseStringSet("plugins", fallback.plugins) ?: return null
        val mcpServers = parseStringSet("mcpServers", fallback.mcpServers) ?: return null
        val screenTools = parseBool("screenTools", fallback.screenTools) ?: return null
        val miniappTools = parseBool("miniappTools", fallback.miniappTools) ?: return null
        val memoryScope = when (val elem = json["memoryScope"]) {
            null -> fallback.memoryScope
            JsonNull -> null
            is JsonPrimitive -> {
                if (!elem.isString) return null
                parseMemoryAccessScopeStrict(elem.content) ?: return null
            }
            else -> return null
        }
        val rawMemoryDispatch = when (val elem = json["memoryDispatch"]) {
            null -> fallback.memoryDispatch
            JsonNull -> null
            is JsonPrimitive -> elem.booleanOrNull ?: return null
            else -> return null
        }
        val memoryDispatch = if (memoryScope == MemoryAccessScope.OFF) false else rawMemoryDispatch
        return WorkspaceToolPolicy(
            allCapabilities = if (isHome) true else requestedAll,
            skills = skills,
            plugins = plugins,
            mcpServers = mcpServers,
            screenTools = screenTools,
            miniappTools = miniappTools,
            memoryScope = memoryScope,
            memoryDispatch = memoryDispatch,
        )
    }

    private fun serializeCapabilitiesSnapshot(
        workspace: RuntimeWorkspace,
        policy: WorkspaceToolPolicy,
        inventory: RemoteCapabilitiesInventory,
    ): JsonObject = buildJsonObject {
        put("botId", workspace.id)
        put("isHome", workspace.isHome)
        put(
            "policy",
            buildJsonObject {
                put("allCapabilities", policy.allCapabilities)
                put("skills", buildJsonArray { policy.skills.sorted().forEach { add(JsonPrimitive(it)) } })
                put("plugins", buildJsonArray { policy.plugins.sorted().forEach { add(JsonPrimitive(it)) } })
                put("mcpServers", buildJsonArray { policy.mcpServers.sorted().forEach { add(JsonPrimitive(it)) } })
                put("screenTools", policy.screenTools)
                put("miniappTools", policy.miniappTools)
                policy.memoryScope?.let { put("memoryScope", it.wire) }
                policy.memoryDispatch?.let { put("memoryDispatch", it) }
            },
        )
        put(
            "available",
            buildJsonObject {
                put("skills", buildJsonArray { inventory.skills.forEach { add(JsonPrimitive(it)) } })
                put(
                    "plugins",
                    buildJsonArray {
                        inventory.plugins.forEach { plugin ->
                            addJsonObject {
                                put("id", plugin.id)
                                put("name", plugin.name)
                            }
                        }
                    },
                )
                put("mcpServers", buildJsonArray { inventory.mcpServers.forEach { add(JsonPrimitive(it)) } })
                put("screenToolsReady", inventory.screenToolsReady)
            },
        )
    }

    private suspend fun reloadBot(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        if (isBotBusy(botId)) {
            reject(frame, "conflict", "Cannot reload bot while a turn is running")
            return
        }
        val requestedSessionId = frame.params.optionalTrimmedString("sessionId")
        val targetSessionId = if (requestedSessionId != null && requestedSessionId != ACTIVE_SESSION_SENTINEL) {
            resolveExistingSession(botId, requestedSessionId) ?: run {
                reject(frame, "session_not_found", "session $requestedSessionId was not found")
                return
            }
        } else {
            findActiveSessionId(botId) ?: host.sessions(botId).firstOrNull { !it.archived }?.let {
                resolveExistingSession(botId, it.id)
            }
        }
        val reloadedId = targetSessionId?.let { id ->
            host.reloadSession(id).also { sessionBots[it] = botId }
        }
        onSyncEvent(
            RelaySyncEvent(
                kind = "sessions_changed",
                botId = botId,
                sessionId = reloadedId,
            ),
        )
        respondOk(
            frame.id,
            buildJsonObject {
                put("reloaded", true)
                put("botId", botId)
                reloadedId?.let { put("sessionId", it) }
            },
        )
    }

    private suspend fun compactSession(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        if (isBotBusy(botId)) {
            reject(frame, "conflict", "Cannot compact while a turn is running")
            return
        }
        val sessionId = resolveControlSessionId(frame, botId, allowCreate = false, failIfMissing = true) ?: return
        when (val outcome = host.compact(sessionId)) {
            is CompactionOutcome.Done -> {
                val usage = host.usage(sessionId)
                onSyncEvent(RelaySyncEvent(kind = "sessions_changed", botId = botId, sessionId = sessionId))
                respondOk(
                    frame.id,
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("compacted", true)
                        put("tokensBefore", outcome.tokensBefore)
                        outcome.tokensAfter?.let { put("tokensAfter", it) }
                        usage?.let { put("usage", serializeUsage(it)) }
                    },
                )
            }

            CompactionOutcome.Empty -> {
                respondOk(
                    frame.id,
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("compacted", false)
                    },
                )
            }

            CompactionOutcome.Busy -> reject(frame, "conflict", "Cannot compact while a turn is running")
            CompactionOutcome.Unavailable -> reject(frame, "permission_denied", "Session compaction is not available")
            is CompactionOutcome.Failed -> reject(frame, "internal", outcome.message ?: "Compaction failed")
        }
    }

    private suspend fun retrySession(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val sessionId = frame.params.string("sessionId").trim()
        if (sessionId.isEmpty()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        if (isBotBusy(botId)) {
            reject(frame, "conflict", "Cannot retry while a turn is running")
            return
        }
        val resolved = resolveExistingSession(botId, sessionId) ?: run {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return
        }
        val feed = ensureActiveFeed(resolved, frame.id, frame.from).also { pruneFinishedFeeds() }
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", resolved)
                put("retried", true)
            },
        )
        try {
            host.retryLastTurn(resolved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitFailureEvents(resolved, feed, error.message ?: "the bot failed")
        }
    }

    private suspend fun requireAvailableBotId(frame: RelayServerFrame.Route): String? {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return null
        }
        return botId
    }

    private suspend fun isBotBusy(botId: String): Boolean =
        collectBotSessionIds(botId).any { id -> host.busySessionIds.value.contains(id) || feeds[id]?.active == true }

    private suspend fun resolveControlSessionId(
        frame: RelayServerFrame.Route,
        botId: String,
        allowCreate: Boolean,
        failIfMissing: Boolean,
    ): String? {
        val requested = frame.params.optionalTrimmedString("sessionId")
        if (requested != null && requested != ACTIVE_SESSION_SENTINEL) {
            val resolved = resolveExistingSession(botId, requested)
            if (resolved == null && failIfMissing) {
                reject(frame, "session_not_found", "session $requested was not found")
            }
            return resolved
        }
        val active = findActiveSessionId(botId)
        if (active != null) return active
        val latest = host.sessions(botId).firstOrNull { !it.archived }?.let { resolveExistingSession(botId, it.id) }
        if (latest != null) return latest
        if (allowCreate) {
            return host.newSession(botId).sessionId.also { created -> sessionBots[created] = botId }
        }
        if (failIfMissing) {
            reject(frame, "session_not_found", "no session available for bot $botId")
        }
        return null
    }

    private fun parseThinkingLevel(raw: String): ThinkingLevel? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        if (clean.equals("minimal", ignoreCase = true)) return ThinkingLevel.LOW
        return ThinkingLevel.entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    private fun serializeModelRef(provider: String, modelId: String, name: String): JsonObject = buildJsonObject {
        put("provider", provider)
        put("id", modelId)
        put("name", name)
    }

    private fun serializeUsage(usage: SessionUsage): JsonObject = buildJsonObject {
        put("contextTokens", usage.contextTokens)
        put("contextWindow", usage.contextWindow)
        put("inputTokens", usage.inputTokens)
        put("outputTokens", usage.outputTokens)
        put("cacheReadTokens", usage.cacheReadTokens)
        put("cacheWriteTokens", usage.cacheWriteTokens)
        put("cost", usage.cost)
    }

    private suspend fun resume(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val botId = frame.params.string("botId")
        if (sessionId == ACTIVE_SESSION_SENTINEL) {
            if (botId.isNotBlank() && bots().none { it.id == botId }) {
                reject(frame, "bot_not_found", "bot $botId is not available")
                return
            }
            if (questionCenter != null) {
                onPendingQuestionsChanged(questionCenter.pending.value)
            }
            val scopedBotId = botId.ifBlank { null }
            registerWatcher(
                routeId = frame.id,
                deviceId = frame.from,
                botId = scopedBotId,
                boundSessionId = null,
            )
            val activeSessionId = findActiveSessionId(scopedBotId)
            if (activeSessionId == null) {
                respondOk(
                    frame.id,
                    buildJsonObject {
                        put("sessionId", ACTIVE_SESSION_SENTINEL)
                        put("busy", false)
                        put("gap", false)
                    },
                )
                return
            }
            val feed = ensureActiveFeed(activeSessionId, frame.id, frame.from)
            respondResumeSnapshot(frame, activeSessionId, feed)
            return
        }
        if (!verifySessionAccess(botId, sessionId, frame)) return
        if (questionCenter != null) {
            onPendingQuestionsChanged(questionCenter.pending.value)
        }
        registerWatcher(
            routeId = frame.id,
            deviceId = frame.from,
            botId = botId.ifBlank { null },
            boundSessionId = sessionId,
        )
        val feed = feeds[sessionId] ?: ensureWatchFeed(sessionId)
        respondResumeSnapshot(frame, sessionId, feed)
    }

    private fun respondResumeSnapshot(
        frame: RelayServerFrame.Route,
        sessionId: String,
        feed: Feed,
    ) {
        val tail = (frame.params["tail"] as? JsonPrimitive)?.booleanOrNull == true
        val requestedAfterSeq = frame.params.long("afterSeq") ?: -1L
        synchronized(feed.sendLock) {
            val currentPending = questionCenter?.pending?.value.orEmpty().filter { it.sessionId == sessionId }
            val snapshot: List<BufferedEvent>
            val gap: Boolean
            val busy: Boolean
            val latestSeq: Long
            val seqReset: Boolean
            synchronized(feed) {
                if (frame.from.isNotBlank()) {
                    feed.routeIds.entries.removeIf { it.value == frame.from }
                }
                busy = feed.active || host.busySessionIds.value.contains(sessionId)
                feed.routeIds[frame.id] = frame.from
                feed.updatedAt = System.currentTimeMillis()
                latestSeq = feed.seq.get() - 1L
                seqReset = !tail && requestedAfterSeq > latestSeq
                val afterSeq = if (tail || seqReset) {
                    if (busy) feed.turnStartSeq - 1L else latestSeq
                } else {
                    requestedAfterSeq
                }
                gap = seqReset || (afterSeq >= 0L && feed.evictedThroughSeq > afterSeq)
                snapshot = feed.buffer.filter { entry -> entry.seq > afterSeq }
            }
            respondOk(
                frame.id,
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("busy", busy)
                    put("gap", gap)
                    put("seq", latestSeq)
                    if (seqReset) put("reset", true)
                },
            )
            for (entry in snapshot) {
                if (!send(RelayFrames.routeEvent(frame.id, entry.seq, entry.event))) break
            }
            val alreadyReplayed = snapshot.mapNotNull { entry ->
                val type = (entry.event["type"] as? JsonPrimitive)?.contentOrNull
                val resolved = (entry.event["resolved"] as? JsonPrimitive)?.booleanOrNull == true
                if (type == "approval_request" && !resolved) {
                    (entry.event["approvalId"] as? JsonPrimitive)?.contentOrNull
                } else {
                    null
                }
            }.toSet()
            for (pq in currentPending) {
                if (pq.requestId !in alreadyReplayed) {
                    val replaySeq = synchronized(feed) { feed.seq.getAndIncrement() }
                    send(RelayFrames.routeEvent(frame.id, replaySeq, approvalRequestEvent(pq)))
                }
            }
        }
    }

    private suspend fun approval(frame: RelayServerFrame.Route) {
        val center = questionCenter ?: run {
            reject(frame, "permission_denied", "interactive approvals are not enabled on this host")
            return
        }
        val sessionId = frame.params.string("sessionId")
        val approvalId = frame.params.string("approvalId").ifBlank { frame.params.string("requestId") }
        if (approvalId.isBlank()) {
            reject(frame, "bad_request", "approvalId is required")
            return
        }
        val cancelled = (frame.params["cancelled"] as? JsonPrimitive)?.booleanOrNull == true
        val confirmed = (frame.params["confirmed"] as? JsonPrimitive)?.booleanOrNull
        val parsedAnswers = RelayFrames.parseQuestionAnswers(frame.params["answers"])
        val hasValue = (frame.params["value"] as? JsonPrimitive)?.contentOrNull != null
        if (!cancelled && confirmed == null && parsedAnswers.isEmpty() && !hasValue) {
            reject(frame, "bad_request", "approval response payload is required")
            return
        }
        val pending = center.pending.value.firstOrNull { pq ->
            pq.requestId == approvalId && (sessionId.isBlank() || pq.sessionId == sessionId)
        }
        if (pending == null) {
            reject(frame, "not_found", "approval $approvalId is not pending")
            return
        }
        val botId = frame.params.string("botId")
        if (!verifySessionAccess(botId, pending.sessionId, frame)) return
        if (cancelled || confirmed == false) {
            center.cancel(approvalId)
            respondOk(frame.id, buildJsonObject { put("approvalId", approvalId) })
            onPendingQuestionsChanged(center.pending.value)
            return
        }
        val answers = if (parsedAnswers.isNotEmpty()) {
            parsedAnswers
        } else {
            val firstQ = pending.questions.firstOrNull()
            if (firstQ == null) {
                emptyList()
            } else if (confirmed == true) {
                val label = firstQ.options.firstOrNull()?.label
                listOf(
                    QuestionAnswer(
                        questionId = firstQ.id,
                        selected = listOfNotNull(label),
                        custom = if (label == null) "yes" else null,
                    ),
                )
            } else {
                val value = frame.params.string("value")
                listOf(QuestionAnswer(questionId = firstQ.id, custom = value))
            }
        }
        center.answer(approvalId, answers)
        respondOk(frame.id, buildJsonObject { put("approvalId", approvalId) })
        onPendingQuestionsChanged(center.pending.value)
    }

    private suspend fun listSessions(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId")
        if (botId.isBlank() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isBlank()) "botId is required" else "bot $botId is not available")
            return
        }
        if (questionCenter != null) {
            onPendingQuestionsChanged(questionCenter.pending.value)
        }
        val includeArchived = frame.params.booleanOrNull("includeArchived") == true
        val ordered = allSessionsForBot(botId, includeArchived)
            .sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
        for (session in ordered) {
            sessionBots[session.id] = botId
        }
        val botCount = bots().size
        val listedIds = ordered.map { it.id }.toSet()
        val extraActive = mutableListOf<Pair<String, Long>>()
        for ((id, feed) in feeds) {
            if (id in listedIds) continue
            val isBusy = host.busySessionIds.value.contains(id) || feed.active
            if (!isBusy) continue
            val owner = sessionBots[id]
            if (owner != botId && !(owner == null && botCount <= 1)) continue
            if (!isRemoteMirrorSession(id)) {
                extraActive += id to feed.updatedAt
            }
        }
        respondOk(
            frame.id,
            buildJsonObject {
                put(
                    "sessions",
                    buildJsonArray {
                        for ((id, updatedAt) in extraActive.sortedByDescending { it.second }) {
                            addJsonObject {
                                put("id", id)
                                put("title", "Untitled")
                                put("updatedAt", updatedAt)
                                put("busy", true)
                            }
                        }
                        for (session in ordered) {
                            addJsonObject {
                                put("id", session.id)
                                put("title", session.title.ifBlank { "Untitled" })
                                put("updatedAt", session.updatedAt)
                                if (session.pinned) put("pinned", true)
                                if (session.archived) put("archived", true)
                                if (host.busySessionIds.value.contains(session.id) || feeds[session.id]?.active == true) {
                                    put("busy", true)
                                }
                            }
                        }
                    },
                )
            },
        )
    }

    private suspend fun createBot(frame: RelayServerFrame.Route) {
        val name = frame.params.string("name").trim()
        if (name.isEmpty()) {
            reject(frame, "bad_request", "name is required")
            return
        }
        val avatar = parseOptionalAvatarForWrite(frame.params)
        val intro = frame.params.optionalNullableString("intro")
        val created = host.createWorkspace(
            name = name,
            directory = null,
            avatar = avatar,
            intro = intro,
        )
        onBotsChanged()
        respondOk(
            frame.id,
            buildJsonObject {
                put("bot", serializeBot(created.id, created.name, frameAvatarOrStored(created.avatar), created.intro))
            },
        )
    }

    private suspend fun updateBot(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return
        }
        val existingWorkspace = localWorkspaceById(botId)
        val hasName = frame.params.containsKey("name")
        val nextName = if (hasName) {
            frame.params.string("name").trim().also {
                if (it.isEmpty()) {
                    reject(frame, "bad_request", "name must not be empty")
                    return
                }
            }
        } else {
            existingWorkspace?.name ?: botId
        }
        if (hasName) {
            host.renameWorkspace(botId, nextName)
        }
        val hasAvatar = frame.params.containsKey("avatar")
        val hasIntro = frame.params.containsKey("intro")
        val previousAvatar = existingWorkspace?.avatar
        val nextAvatar = if (hasAvatar) parseOptionalAvatarForWrite(frame.params) else previousAvatar
        val nextIntro = if (hasIntro) frame.params.optionalNullableString("intro") else existingWorkspace?.intro
        if (hasAvatar || hasIntro) {
            host.updateWorkspaceProfile(botId, nextAvatar, nextIntro)
            if (hasAvatar && previousAvatar != null && previousAvatar != nextAvatar) {
                deleteManagedAvatar(previousAvatar)
            }
        }
        val refreshed = localWorkspaceById(botId)
        val finalName = refreshed?.name ?: nextName
        val finalAvatar = refreshed?.avatar ?: nextAvatar
        onBotsChanged()
        respondOk(
            frame.id,
            buildJsonObject {
                put("bot", serializeBot(botId, finalName, frameAvatarOrStored(finalAvatar), refreshed?.intro ?: nextIntro))
            },
        )
    }

    private suspend fun deleteBot(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return
        }
        if (botId == HOME_BOT_ID) {
            reject(frame, "permission_denied", "Default bot cannot be deleted")
            return
        }
        val botSessionIds = collectBotSessionIds(botId)
        val hasBusy = botSessionIds.any { id ->
            host.busySessionIds.value.contains(id) || feeds[id]?.active == true
        }
        if (hasBusy) {
            reject(frame, "conflict", "Cannot delete a bot while a turn is running")
            return
        }
        val existingAvatar = localWorkspaceById(botId)?.avatar
        for (sessionId in botSessionIds) {
            cleanupFeedAndQuestions(sessionId)
            sessionBots.remove(sessionId)
        }
        botWatchers.entries.removeIf { it.value.botId == botId }
        host.deleteWorkspace(botId)
        deleteManagedAvatar(existingAvatar)
        onBotsChanged()
        respondOk(
            frame.id,
            buildJsonObject {
                put("deleted", true)
                put("botId", botId)
            },
        )
    }

    private suspend fun createSession(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return
        }
        val cleanTitle = frame.params.optionalTrimmedString("title")?.take(MAX_SESSION_TITLE_LENGTH)
        val opened = host.newSession(botId)
        sessionBots[opened.sessionId] = botId
        if (!cleanTitle.isNullOrEmpty()) {
            host.renameSession(opened.sessionId, cleanTitle)
        }
        val now = System.currentTimeMillis()
        val summary = allSessionsForBot(botId, includeArchived = true).firstOrNull { it.id == opened.sessionId }
        val resolvedTitle = cleanTitle ?: summary?.title?.takeIf { it.isNotBlank() } ?: "Untitled"
        val updatedAt = summary?.updatedAt ?: now
        onSyncEvent(RelaySyncEvent(kind = "sessions_changed", botId = botId, sessionId = opened.sessionId, ts = now))
        respondOk(
            frame.id,
            buildJsonObject {
                put(
                    "session",
                    buildJsonObject {
                        put("id", opened.sessionId)
                        put("title", resolvedTitle)
                        put("updatedAt", updatedAt)
                        put("messageCount", 0)
                    },
                )
            },
        )
    }

    private suspend fun renameSession(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return
        }
        val sessionId = frame.params.string("sessionId").trim()
        val cleanTitle = frame.params.string("title").trim().take(MAX_SESSION_TITLE_LENGTH)
        if (sessionId.isEmpty() || cleanTitle.isEmpty()) {
            reject(frame, "bad_request", "sessionId and title are required")
            return
        }
        if (!ownsSession(botId, sessionId)) {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return
        }
        markSuppressedSync(sessionId)
        host.renameSession(sessionId, cleanTitle)
        onSyncEvent(RelaySyncEvent(kind = "sessions_changed", botId = botId, sessionId = sessionId))
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("title", cleanTitle)
            },
        )
    }

    private suspend fun deleteSession(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return
        }
        val sessionId = frame.params.string("sessionId").trim()
        if (sessionId.isEmpty()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        if (!ownsSession(botId, sessionId)) {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return
        }
        if (host.busySessionIds.value.contains(sessionId) || feeds[sessionId]?.active == true) {
            reject(frame, "conflict", "Cannot delete an active busy session")
            return
        }
        cleanupFeedAndQuestions(sessionId)
        sessionBots.remove(sessionId)
        markSuppressedSync(sessionId)
        host.deleteSession(sessionId)
        onSyncEvent(RelaySyncEvent(kind = "sessions_changed", botId = botId, sessionId = sessionId))
        respondOk(
            frame.id,
            buildJsonObject {
                put("deleted", true)
                put("sessionId", sessionId)
            },
        )
    }

    private suspend fun updateSessionMeta(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return
        }
        val sessionId = frame.params.string("sessionId").trim()
        if (sessionId.isEmpty()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val pinnedPatch = frame.params.booleanOrNull("pinned")
        val archivedPatch = frame.params.booleanOrNull("archived")
        if (pinnedPatch == null && archivedPatch == null) {
            reject(frame, "bad_request", "pinned or archived is required")
            return
        }
        val existing = allSessionsForBot(botId, includeArchived = true).firstOrNull { it.id == sessionId }
        if (existing == null && sessionBots[sessionId] != botId) {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return
        }
        markSuppressedSync(sessionId)
        if (pinnedPatch != null) {
            host.setSessionPinned(sessionId, pinnedPatch)
        }
        if (archivedPatch != null) {
            host.setSessionArchived(sessionId, archivedPatch)
        }
        val finalPinned = pinnedPatch ?: (existing?.pinned == true)
        val finalArchived = archivedPatch ?: (existing?.archived == true)
        onSyncEvent(RelaySyncEvent(kind = "sessions_changed", botId = botId, sessionId = sessionId))
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("pinned", finalPinned)
                put("archived", finalArchived)
            },
        )
    }

    private suspend fun fileList(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val relPath = (frame.params["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val query = (frame.params["query"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val limit = frame.params.intOrNull("limit") ?: DEFAULT_FILE_LIST_LIMIT
        val (resolvedPath, entries) = fileHandler.list(cwd, relPath, query, limit)
        respondOk(
            frame.id,
            buildJsonObject {
                put("path", resolvedPath)
                put(
                    "entries",
                    buildJsonArray {
                        for (entry in entries) {
                            add(serializeFileEntry(entry))
                        }
                    },
                )
            },
        )
    }

    private suspend fun fileStat(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val relPath = (frame.params["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val entry = fileHandler.stat(cwd, relPath)
        respondOk(
            frame.id,
            buildJsonObject {
                put("entry", serializeFileEntry(entry))
            },
        )
    }

    private suspend fun fileReadChunk(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val relPath = (frame.params["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val offset = frame.params.long("offset")
        val length = frame.params.intOrNull("length")
        if (offset == null || length == null) {
            reject(frame, "bad_request", "offset and length are required")
            return
        }
        val result = fileHandler.readChunk(cwd, relPath, offset, length)
        respondOk(
            frame.id,
            buildJsonObject {
                put("path", result.path)
                put("offset", result.offset)
                put("data", result.data)
                put("size", result.size)
                put("eof", result.eof)
                result.mimeType?.let { put("mimeType", it) }
                put("binary", result.binary)
            },
        )
    }

    private suspend fun readArtifactChunk(frame: RelayServerFrame.Route) {
        val botId = requireAvailableBotId(frame) ?: return
        val artifactId = (frame.params["artifactId"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        val offset = frame.params.long("offset")
        val length = frame.params.intOrNull("length")
        if (artifactId.isEmpty() || offset == null || length == null) {
            reject(frame, "bad_request", "artifactId, offset, and length are required")
            return
        }
        when (val chunk = presentedArtifacts.readChunk(botId, artifactId, offset, length)) {
            is ArtifactChunkOutcome.Ok -> respondOk(
                frame.id,
                buildJsonObject {
                    put("dataBase64", chunk.dataBase64)
                    put("size", chunk.size)
                    put("eof", chunk.eof)
                    chunk.mime?.let { put("mime", it) }
                    put("name", chunk.name)
                },
            )
            is ArtifactChunkOutcome.Error -> reject(frame, chunk.code, chunk.message)
        }
    }

    private suspend fun fileWriteChunk(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val relPath = (frame.params["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val offset = frame.params.long("offset")
        val data = (frame.params["data"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val eof = frame.params.booleanOrNull("eof")
        if (offset == null || data == null || eof == null) {
            reject(frame, "bad_request", "path, offset, data, and eof are required")
            return
        }
        val result = fileHandler.writeChunk(
            cwd,
            RemoteFileWriteChunkInput(
                path = relPath,
                transferId = frame.params.optionalTrimmedString("transferId"),
                offset = offset,
                data = data,
                eof = eof,
                overwrite = frame.params.booleanOrNull("overwrite"),
                expectedSha256 = frame.params.optionalTrimmedString("expectedSha256"),
            ),
        )
        respondOk(
            frame.id,
            buildJsonObject {
                put("transferId", result.transferId)
                put("written", result.written)
                put("eof", result.eof)
                result.size?.let { put("size", it) }
                result.sha256?.let { put("sha256", it) }
            },
        )
    }

    private suspend fun fileMkdir(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val relPath = (frame.params["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val (resolvedPath, created) = fileHandler.mkdir(cwd, relPath)
        respondOk(
            frame.id,
            buildJsonObject {
                put("path", resolvedPath)
                put("created", created)
            },
        )
    }

    private suspend fun fileRename(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val from = (frame.params["from"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val to = (frame.params["to"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val (resolvedFrom, resolvedTo) = fileHandler.rename(cwd, from, to)
        respondOk(
            frame.id,
            buildJsonObject {
                put("from", resolvedFrom)
                put("to", resolvedTo)
            },
        )
    }

    private suspend fun fileDelete(frame: RelayServerFrame.Route) {
        val cwd = requireWorkspaceCwd(frame) ?: return
        val relPath = (frame.params["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val recursive = frame.params.booleanOrNull("recursive") == true
        val (deleted, resolvedPath) = fileHandler.delete(cwd, relPath, recursive)
        respondOk(
            frame.id,
            buildJsonObject {
                put("deleted", deleted)
                put("path", resolvedPath)
            },
        )
    }

    private suspend fun history(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId")
        if (botId.isBlank() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isBlank()) "botId is required" else "bot $botId is not available")
            return
        }
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        if (questionCenter != null) {
            onPendingQuestionsChanged(questionCenter.pending.value)
        }
        val owner = sessionBots[sessionId]
        if (owner != null && owner != botId) {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return
        }
        val summary = host.sessions(botId).firstOrNull { !it.archived && it.id == sessionId }
        var resolvedId = sessionId
        var messages = if (summary != null || owner == botId) host.history(sessionId) else emptyList()
        if (messages.isEmpty() && summary != null) {
            val opened = host.openSession(summary.filePath, summary.cwd)
            resolvedId = opened.sessionId
            messages = host.history(resolvedId)
        }
        val busy = (summary != null || owner == botId) &&
            (host.busySessionIds.value.contains(resolvedId) || feeds[resolvedId]?.active == true)
        if (summary == null && owner != botId) {
            reject(frame, "session_not_found", "session $sessionId was not found")
            return
        }
        sessionBots.putIfAbsent(resolvedId, botId)
        val liveFeed = feeds[resolvedId]
        val (bufferedEvents, snapSeq) = if (liveFeed != null) {
            synchronized(liveFeed) {
                val tailEvents = if (busy) liveFeed.buffer.toList() else emptyList()
                tailEvents to maxOf(-1L, liveFeed.seq.get() - 1L)
            }
        } else {
            emptyList<BufferedEvent>() to -1L
        }
        val serialized = serializeHistory(
            botId = botId,
            cwd = localWorkspaceById(botId)?.cwd,
            messages = messages,
            bufferedEvents = bufferedEvents,
        )
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", resolvedId)
                summary?.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                put("busy", busy)
                if (liveFeed != null) {
                    put("seq", snapSeq)
                }
                put("messages", serialized.messages)
                if (serialized.truncated) {
                    put("truncated", true)
                }
            },
        )
    }

    private data class BoundedHistory(
        val messages: JsonArray,
        val truncated: Boolean,
    )

    private fun capHistoryText(text: String, limit: Int): Pair<String, Boolean> {
        if (text.length <= limit) return text to false
        return "${text.take(limit)}\n...[truncated]" to true
    }

    private fun serializeHistory(
        botId: String,
        cwd: String?,
        messages: List<AssistantMessage>,
        bufferedEvents: List<BufferedEvent> = emptyList(),
    ): BoundedHistory {
        val entries = mutableListOf<JsonObject>()
        var truncated = false
        val committedToolCallIds = HashSet<String>()
        val committedToolResultIds = HashSet<String>()
        val committedToolNames = HashMap<String, String>()
        for (message in messages) {
            when (message.role) {
                AssistantRole.USER -> {
                    if (message.text.isNotBlank()) {
                        val (cappedText, wasCapped) = capHistoryText(message.text, MAX_HISTORY_BLOCK_CHARS)
                        if (wasCapped) truncated = true
                        entries += buildJsonObject {
                            put("role", "user")
                            if (message.startedAt > 0L) put("timestamp", message.startedAt)
                            put(
                                "content",
                                buildJsonArray {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", cappedText)
                                    }
                                },
                            )
                        }
                    }
                }

                AssistantRole.ASSISTANT -> {
                    val contentBlocks = mutableListOf<JsonObject>()
                    val toolResults = mutableListOf<JsonObject>()
                    if (message.steps.isNotEmpty()) {
                        for (step in message.steps) {
                            when (step) {
                                is AssistantStep.Reasoning -> {
                                    if (step.text.isNotBlank()) {
                                        val (capped, wasCapped) = capHistoryText(step.text, MAX_HISTORY_BLOCK_CHARS)
                                        if (wasCapped) truncated = true
                                        contentBlocks += buildJsonObject {
                                            put("type", "thinking")
                                            put("thinking", capped)
                                        }
                                    }
                                }

                                is AssistantStep.Text -> {
                                    if (step.text.isNotBlank()) {
                                        val (capped, wasCapped) = capHistoryText(step.text, MAX_HISTORY_BLOCK_CHARS)
                                        if (wasCapped) truncated = true
                                        contentBlocks += buildJsonObject {
                                            put("type", "text")
                                            put("text", capped)
                                        }
                                    }
                                }

                                is AssistantStep.Tool -> {
                                    val trace = step.trace
                                    val callId = trace.invocationKey
                                    committedToolCallIds += callId
                                    committedToolNames[callId] = trace.name
                                    val rawArgs = trace.rawArguments?.takeIf {
                                        it.toString().length <= MAX_HISTORY_TOOL_RESULT_CHARS
                                    }
                                    if (trace.rawArguments != null && rawArgs == null) truncated = true
                                    val args = rawArgs ?: JsonObject(
                                        trace.arguments.mapValues { (_, v) ->
                                            val (cappedVal, valCapped) = capHistoryText(v, MAX_HISTORY_TOOL_RESULT_CHARS)
                                            if (valCapped) truncated = true
                                            JsonPrimitive(cappedVal)
                                        },
                                    )
                                    contentBlocks += buildJsonObject {
                                        put("type", "toolCall")
                                        put("id", callId)
                                        put("name", trace.name)
                                        put("arguments", args)
                                    }
                                    if (trace.status != ToolActivityStatus.RUNNING || trace.output != null || trace.error != null) {
                                        committedToolResultIds += callId
                                        val rawResultText = if (
                                            trace.error == null &&
                                            trace.name == "present" &&
                                            trace.status != ToolActivityStatus.FAILED &&
                                            !trace.output.isNullOrBlank()
                                        ) {
                                            enrichPresentedOutput(botId, cwd, trace.output.orEmpty())
                                        } else {
                                            trace.error ?: trace.output.orEmpty()
                                        }
                                        val (resultText, resultCapped) = capHistoryText(
                                            rawResultText,
                                            MAX_HISTORY_TOOL_RESULT_CHARS,
                                        )
                                        if (resultCapped) truncated = true
                                        val toolTimestamp = (
                                            if (trace.startedAt > 0L && trace.durationMs != null) {
                                                trace.startedAt + trace.durationMs!!
                                            } else {
                                                null
                                            }
                                            ) ?: trace.startedAt.takeIf { it > 0L }
                                            ?: message.finishedAt?.takeIf { it > 0L }
                                            ?: message.startedAt.takeIf { it > 0L }
                                        toolResults += buildJsonObject {
                                            put("role", "toolResult")
                                            put("toolCallId", callId)
                                            put("toolName", trace.name)
                                            put("isError", trace.status == ToolActivityStatus.FAILED)
                                            toolTimestamp?.let { put("timestamp", it) }
                                            trace.durationMs?.let { put("durationMs", it) }
                                            put(
                                                "content",
                                                buildJsonArray {
                                                    addJsonObject {
                                                        put("type", "text")
                                                        put("text", resultText)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                else -> Unit
                            }
                        }
                    } else if (message.text.isNotBlank()) {
                        val (capped, wasCapped) = capHistoryText(message.text, MAX_HISTORY_BLOCK_CHARS)
                        if (wasCapped) truncated = true
                        contentBlocks += buildJsonObject {
                            put("type", "text")
                            put("text", capped)
                        }
                    }
                    if (contentBlocks.isNotEmpty() || message.error != null || message.status == MessageStatus.FAILED) {
                        val assistantTimestamp = message.startedAt.takeIf { it > 0L }
                            ?: message.finishedAt?.takeIf { it > 0L }
                        entries += buildJsonObject {
                            put("role", "assistant")
                            assistantTimestamp?.let { put("timestamp", it) }
                            put("content", JsonArray(contentBlocks))
                            message.error?.takeIf { it.isNotBlank() }?.let { err ->
                                val (cappedErr, errCapped) = capHistoryText(err, MAX_HISTORY_TOOL_RESULT_CHARS)
                                if (errCapped) truncated = true
                                put("errorMessage", cappedErr)
                            }
                        }
                    }
                    for (result in toolResults) {
                        entries += result
                    }
                }
            }
        }
        val lastAssistant = messages.lastOrNull()?.takeIf { it.role == AssistantRole.ASSISTANT }
        val lastCommittedThinking = lastAssistant?.steps
            ?.filterIsInstance<AssistantStep.Reasoning>()
            ?.joinToString("") { it.text }
            .orEmpty()
        val lastCommittedText = if (lastAssistant != null) {
            if (lastAssistant.steps.isNotEmpty()) {
                lastAssistant.steps.filterIsInstance<AssistantStep.Text>().joinToString("") { it.text }
            } else {
                lastAssistant.text
            }
        } else {
            ""
        }
        var dedupeAgainstLastCommitted = lastAssistant != null
        if (bufferedEvents.isNotEmpty()) {
            val liveThinking = StringBuilder()
            val liveText = StringBuilder()
            val liveBlocks = mutableListOf<JsonObject>()
            var liveAssistantTimestamp: Long? = null
            fun stripCommittedPrefix(live: String, committed: String): String {
                if (!dedupeAgainstLastCommitted || committed.isEmpty() || live.isEmpty()) return live
                if (committed.startsWith(live)) return ""
                if (live.startsWith(committed)) return live.substring(committed.length)
                return live
            }
            fun flushLiveAssistant() {
                val rawThinking = liveThinking.toString()
                val rawText = liveText.toString()
                liveThinking.setLength(0)
                liveText.setLength(0)
                val effectiveThinking = stripCommittedPrefix(rawThinking, lastCommittedThinking)
                val effectiveText = stripCommittedPrefix(rawText, lastCommittedText)
                if (effectiveThinking.isNotEmpty()) {
                    val (cappedThinking, wasCapped) = capHistoryText(effectiveThinking, MAX_HISTORY_BLOCK_CHARS)
                    if (wasCapped) truncated = true
                    liveBlocks.add(
                        0,
                        buildJsonObject {
                            put("type", "thinking")
                            put("thinking", cappedThinking)
                        },
                    )
                }
                if (effectiveText.isNotEmpty()) {
                    val (cappedLiveText, wasCapped) = capHistoryText(effectiveText, MAX_HISTORY_BLOCK_CHARS)
                    if (wasCapped) truncated = true
                    liveBlocks += buildJsonObject {
                        put("type", "text")
                        put("text", cappedLiveText)
                    }
                }
                if (liveBlocks.isNotEmpty()) {
                    val ts = liveAssistantTimestamp
                    entries += buildJsonObject {
                        put("role", "assistant")
                        ts?.takeIf { it > 0L }?.let { put("timestamp", it) }
                        put("content", JsonArray(liveBlocks.toList()))
                    }
                    liveBlocks.clear()
                    liveAssistantTimestamp = null
                    dedupeAgainstLastCommitted = false
                }
            }
            val bufferedToolNames = HashMap<String, String>()
            for (entry in bufferedEvents) {
                val ev = entry.event
                when ((ev["type"] as? JsonPrimitive)?.contentOrNull) {
                    "message_update" -> {
                        if (liveAssistantTimestamp == null && entry.timestamp > 0L) {
                            liveAssistantTimestamp = entry.timestamp
                        }
                        val sub = ev["assistantMessageEvent"] as? JsonObject ?: continue
                        val delta = (sub["delta"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        when ((sub["type"] as? JsonPrimitive)?.contentOrNull) {
                            "reasoning_delta" -> liveThinking.append(delta)
                            "text_delta" -> liveText.append(delta)
                        }
                    }

                    "tool_call" -> {
                        if (liveAssistantTimestamp == null && entry.timestamp > 0L) {
                            liveAssistantTimestamp = entry.timestamp
                        }
                        val callId = (ev["callId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val toolName = (ev["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (callId.isNotBlank() && toolName.isNotBlank()) {
                            bufferedToolNames[callId] = toolName
                            val rawArgsObj = (ev["args"] as? JsonObject) ?: buildJsonObject {}
                            val argsObj = if (rawArgsObj.toString().length <= MAX_HISTORY_TOOL_RESULT_CHARS) {
                                rawArgsObj
                            } else {
                                truncated = true
                                buildJsonObject {}
                            }
                            if (callId !in committedToolCallIds) {
                                committedToolCallIds += callId
                                liveBlocks += buildJsonObject {
                                    put("type", "toolCall")
                                    put("id", callId)
                                    put("name", toolName)
                                    put("arguments", argsObj)
                                }
                            } else if ((ev["args"] as? JsonObject) != null) {
                                val existingIndex = liveBlocks.indexOfFirst { block ->
                                    (block["type"] as? JsonPrimitive)?.contentOrNull == "toolCall" &&
                                        (block["id"] as? JsonPrimitive)?.contentOrNull == callId
                                }
                                if (existingIndex >= 0) {
                                    liveBlocks[existingIndex] = buildJsonObject {
                                        put("type", "toolCall")
                                        put("id", callId)
                                        put("name", toolName)
                                        put("arguments", argsObj)
                                    }
                                }
                            }
                        }
                    }

                    "tool_result" -> {
                        val callId = (ev["callId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val toolName = (ev["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: bufferedToolNames[callId]
                            ?: committedToolNames[callId].orEmpty()
                        if (callId.isNotBlank() && callId !in committedToolResultIds) {
                            committedToolResultIds += callId
                            flushLiveAssistant()
                            dedupeAgainstLastCommitted = false
                            val rawText = (ev["result"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            val (cappedRawText, rawCapped) = capHistoryText(rawText, MAX_HISTORY_TOOL_RESULT_CHARS)
                            if (rawCapped) truncated = true
                            val content = buildJsonArray {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", cappedRawText)
                                }
                            }
                            val isError = (ev["isError"] as? JsonPrimitive)?.booleanOrNull == true
                            val rawDetails = ev["details"]?.takeUnless { it is JsonNull }
                            val details = rawDetails?.takeIf { it.toString().length <= MAX_HISTORY_TOOL_RESULT_CHARS }
                            if (rawDetails != null && details == null) truncated = true
                            entries += buildJsonObject {
                                put("role", "toolResult")
                                put("toolCallId", callId)
                                put("toolName", toolName)
                                if (isError) put("isError", true)
                                if (entry.timestamp > 0L) put("timestamp", entry.timestamp)
                                put("content", content)
                                details?.let { put("details", it) }
                            }
                        } else if (callId.isNotBlank()) {
                            flushLiveAssistant()
                            dedupeAgainstLastCommitted = false
                        }
                    }
                }
            }
            flushLiveAssistant()
        }
        var remainingBudget = MAX_HISTORY_WIRE_CHARS
        var firstKeptIndex = entries.size
        for (index in entries.indices.reversed()) {
            val itemSize = entries[index].toString().length + 1
            if (index == entries.lastIndex || itemSize <= remainingBudget) {
                remainingBudget = (remainingBudget - itemSize).coerceAtLeast(0)
                firstKeptIndex = index
            } else {
                break
            }
        }
        while (firstKeptIndex < entries.lastIndex &&
            (entries[firstKeptIndex]["role"] as? JsonPrimitive)?.contentOrNull == "toolResult"
        ) {
            firstKeptIndex += 1
        }
        val droppedPrefix = firstKeptIndex > 0
        val kept = if (droppedPrefix) entries.subList(firstKeptIndex, entries.size) else entries
        return BoundedHistory(
            messages = JsonArray(kept.toList()),
            truncated = truncated || droppedPrefix,
        )
    }

    private suspend fun onPendingQuestionsChanged(current: List<PendingQuestion>) {
        questionMutex.withLock {
            val toEmit = mutableListOf<Pair<String, JsonObject>>()
            val currentById = current.associateBy { it.requestId }
            val removedIds = activeQuestions.keys.filter { it !in currentById }
            for (oldId in removedIds) {
                val removed = activeQuestions.remove(oldId) ?: continue
                toEmit += removed.sessionId to buildJsonObject {
                    put("type", "approval_request")
                    put("approvalId", removed.requestId)
                    put("kind", "resolved")
                    put("summary", "resolved")
                    put("resolved", true)
                }
            }
            for (pq in current) {
                if (isRemoteMirrorSession(pq.sessionId)) continue
                if (!activeQuestions.containsKey(pq.requestId)) {
                    activeQuestions[pq.requestId] = pq
                    toEmit += pq.sessionId to approvalRequestEvent(pq)
                }
            }
            val touchedSessions = linkedSetOf<String>()
            for ((sessionId, event) in toEmit) {
                if (isRemoteMirrorSession(sessionId)) continue
                touchedSessions += sessionId
                val isResolved = (event["resolved"] as? JsonPrimitive)?.booleanOrNull == true
                val feed = if (isResolved) {
                    feeds[sessionId]
                } else {
                    ensureActiveFeed(sessionId, null)
                }
                if (feed != null) {
                    emitFeedEvents(feed, listOf(event), terminal = false)
                }
            }
            if (touchedSessions.isNotEmpty()) {
                val remaining = activeQuestions.values.toList()
                for (sessionId in touchedSessions) {
                    val botId = resolveSessionBotId(sessionId) ?: continue
                    val count = remaining.count { q -> q.sessionId == sessionId }
                    onSyncEvent(
                        RelaySyncEvent(
                            kind = "approval_changed",
                            botId = botId,
                            sessionId = sessionId,
                            pendingApprovals = count,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun isRemoteMirrorSession(sessionId: String): Boolean {
        if (RemoteMirrorRegistry.contains(sessionId)) return true
        if (sessionBots.containsKey(sessionId)) return false
        return host.workspaces().any { workspace ->
            workspace.remote != null && host.sessions(workspace.id).any { it.id == sessionId }
        }
    }

    private suspend fun onRuntimeEvent(session: SessionRuntimeEvent) {
        val runStarted = session.event as? RuntimeEvent.RunStarted
        if (runStarted != null) {
            if (isRemoteMirrorSession(session.sessionId)) return
            val ownerBotId = resolveSessionBotId(session.sessionId)
            val feed = ensureActiveFeed(session.sessionId, null)
            pruneFinishedFeeds()
            val originRouteId = pendingOriginRouteId.remove(session.sessionId)
            val pendingMessage = pendingUserMessages.remove(session.sessionId)
            val userMessage = pendingMessage ?: runStarted.prompt.takeIf { it.isNotBlank() }?.let {
                userMessagePayload(it, emptyList())
            }
            val title = ownerBotId?.let { botId ->
                host.sessions(botId).firstOrNull { !it.archived && it.id == session.sessionId }?.title?.takeIf { it.isNotBlank() }
            }
            val event = buildJsonObject {
                put("type", "agent_start")
                put("sessionId", session.sessionId)
                title?.let { put("title", it) }
                originRouteId?.let { put("originRouteId", it) }
                userMessage?.let { put("userMessage", it) }
            }
            emitFeedEvents(feed, listOf(event), terminal = false)
            if (!ownerBotId.isNullOrBlank()) {
                onSyncEvent(
                    RelaySyncEvent(
                        kind = "bot_activity",
                        botId = ownerBotId,
                        sessionId = session.sessionId,
                        busy = true,
                    ),
                )
            }
            return
        }
        if (session.event is RuntimeEvent.SessionRemoved || session.event is RuntimeEvent.SessionArchived) {
            if (session.sessionId.isNotBlank() && !isRemoteMirrorSession(session.sessionId) && !consumeSuppressedSync(session.sessionId)) {
                val isRemoved = session.event is RuntimeEvent.SessionRemoved
                if (isRemoved) {
                    cleanupFeedAndQuestions(session.sessionId)
                }
                val botId = if (isRemoved) {
                    sessionBots.remove(session.sessionId) ?: resolveSessionBotId(session.sessionId)
                } else {
                    sessionBots[session.sessionId] ?: resolveSessionBotId(session.sessionId)
                }
                if (!botId.isNullOrBlank()) {
                    onSyncEvent(RelaySyncEvent(kind = "sessions_changed", botId = botId, sessionId = session.sessionId))
                } else {
                    // File was already removed from disk before SessionRemoved fired and sessionBots
                    // did not cache this local session yet; notify peers across all local bots so
                    // stale remote mirrors are pruned immediately.
                    bots().forEach { localBot ->
                        onSyncEvent(RelaySyncEvent(kind = "sessions_changed", botId = localBot.id, sessionId = session.sessionId))
                    }
                }
            }
            return
        }
        val feed = feeds[session.sessionId] ?: return
        val rawEvents = RelayEventMapper.map(session.event)
        if (rawEvents.isEmpty()) return
        val events = enrichLiveToolEvents(session.sessionId, session.event, rawEvents)
        val terminal = session.event is RuntimeEvent.RunFinished || session.event is RuntimeEvent.RunFailed
        emitFeedEvents(feed, events, terminal = terminal)
        if (terminal) {
            pruneFinishedFeeds()
            val ownerBotId = sessionBots[session.sessionId] ?: resolveSessionBotId(session.sessionId)
            if (!ownerBotId.isNullOrBlank()) {
                onSyncEvent(
                    RelaySyncEvent(
                        kind = "bot_activity",
                        botId = ownerBotId,
                        sessionId = session.sessionId,
                        busy = false,
                    ),
                )
                onSyncEvent(
                    RelaySyncEvent(
                        kind = "sessions_changed",
                        botId = ownerBotId,
                        sessionId = session.sessionId,
                    ),
                )
            }
        }
    }

    private suspend fun enrichLiveToolEvents(
        sessionId: String,
        runtimeEvent: RuntimeEvent,
        events: List<JsonObject>,
    ): List<JsonObject> {
        val toolFinished = runtimeEvent as? RuntimeEvent.ToolFinished ?: return events
        if (toolFinished.name != "present" || toolFinished.isError || toolFinished.result.isBlank()) {
            return events
        }
        val ownerBotId = sessionBots[sessionId] ?: resolveSessionBotId(sessionId) ?: return events
        val cwd = localWorkspaceById(ownerBotId)?.cwd
        val enriched = enrichPresentedOutput(ownerBotId, cwd, toolFinished.result)
        if (enriched == toolFinished.result) return events
        return events.map { event ->
            val type = (event["type"] as? JsonPrimitive)?.contentOrNull
            if (type == "tool_result") {
                buildJsonObject {
                    for ((k, v) in event) {
                        if (k == "result") put("result", enriched) else put(k, v)
                    }
                }
            } else {
                event
            }
        }
    }

    private fun enrichPresentedOutput(
        botId: String,
        cwd: String?,
        rawOutput: String,
    ): String {
        val root = runCatching { Json.parseToJsonElement(rawOutput).jsonObject }.getOrNull() ?: return rawOutput
        val items = root["items"] as? JsonArray ?: return rawOutput
        var changed = false
        val updatedItems = buildJsonArray {
            for (element in items) {
                val obj = element as? JsonObject
                if (obj == null) {
                    add(element)
                    continue
                }
                val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.lowercase()
                val rawPath = (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
                if (type !in PRESENT_ARTIFACT_TYPES || rawPath.isNullOrEmpty()) {
                    add(obj)
                    continue
                }
                val mime = (obj["mime"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val file = resolvePresentedLocalFile(rawPath, cwd)
                val registered = file?.let { presentedArtifacts.register(botId, it, mime, name) }
                if (registered == null) {
                    add(obj)
                    continue
                }
                changed = true
                add(
                    buildJsonObject {
                        for ((k, v) in obj) put(k, v)
                        put("artifactId", registered.artifactId)
                        put("artifactSize", registered.size)
                    },
                )
            }
        }
        if (!changed) return rawOutput
        return buildJsonObject {
            for ((k, v) in root) {
                if (k == "items") put("items", updatedItems) else put(k, v)
            }
        }.toString()
    }

    private fun resolvePresentedLocalFile(rawPath: String, cwd: String?): File? {
        val clean = if (rawPath.startsWith("file:", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(rawPath) }.getOrNull() ?: return null
            if (!uri.scheme.equals("file", ignoreCase = true)) return null
            val authority = uri.authority
            if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
            uri.path?.takeIf { it.isNotBlank() } ?: return null
        } else {
            rawPath
        }
        return when {
            clean.startsWith("/workspace/") && !cwd.isNullOrBlank() ->
                File(cwd, clean.removePrefix("/workspace/"))
            clean.startsWith("/") -> File(clean)
            !cwd.isNullOrBlank() -> File(cwd, clean.removePrefix("workspace/"))
            else -> File(clean)
        }
    }

    private fun emitFeedEvents(
        feed: Feed,
        events: List<JsonObject>,
        terminal: Boolean,
        excludeRouteId: String? = null,
        excludeDeviceId: String? = null,
    ) {
        if (events.isEmpty()) return
        val toSend = mutableListOf<Pair<String, BufferedEvent>>()
        synchronized(feed.sendLock) {
            synchronized(feed) {
                feed.updatedAt = System.currentTimeMillis()
                val targets = feed.routeIds.entries
                    .filter { (routeId, deviceId) ->
                        routeId != excludeRouteId &&
                            (excludeDeviceId.isNullOrBlank() || deviceId != excludeDeviceId)
                    }
                    .map { it.key }
                for (event in events) {
                    val entry = BufferedEvent(feed.seq.getAndIncrement(), event)
                    feed.buffer.addLast(entry)
                    while (feed.buffer.size > maxBufferedEvents) {
                        val removed = feed.buffer.removeFirst()
                        feed.evictedThroughSeq = removed.seq
                    }
                    for (routeId in targets) {
                        toSend += routeId to entry
                    }
                }
                if (terminal) feed.active = false
            }
            for ((routeId, entry) in toSend) {
                send(RelayFrames.routeEvent(routeId, entry.seq, entry.event))
            }
        }
    }

    private fun userMessagePayload(text: String, images: List<ImagePart>): JsonObject = buildJsonObject {
        put("text", text)
        if (images.isNotEmpty()) {
            put("images", RelayFrames.imagesArray(images.map { RouteImagePart(it.data, it.mimeType) }))
        }
    }

    private fun queueUpdateUserMessageEvent(
        sessionId: String,
        text: String,
        images: List<ImagePart>,
    ): JsonObject = buildJsonObject {
        put("type", "queue_update")
        put("sessionId", sessionId)
        put("steering", buildJsonArray {})
        put("followUp", buildJsonArray {})
        put("userMessage", userMessagePayload(text, images))
    }

    private fun approvalRequestEvent(pq: PendingQuestion): JsonObject {
        val firstQuestion = pq.questions.firstOrNull()?.question?.takeIf { it.isNotBlank() } ?: "Question"
        return buildJsonObject {
            put("type", "approval_request")
            put("approvalId", pq.requestId)
            put("kind", "question")
            if (pq.toolCallId.isNotBlank()) put("toolCallId", pq.toolCallId)
            put("summary", firstQuestion.take(512))
            put("title", firstQuestion.take(512))
            put("questions", RelayFrames.questionsArray(pq.questions))
        }
    }

    private fun pruneFinishedFeeds() {
        val finished = feeds.entries
            .filter { (_, feed) -> !feed.active && synchronized(feed) { feed.routeIds.isEmpty() } }
            .sortedBy { (_, feed) -> feed.updatedAt }
        val excess = finished.size - MAX_RETAINED_FINISHED_FEEDS
        if (excess <= 0) return
        finished.take(excess).forEach { (id, feed) -> feeds.remove(id, feed) }
    }

    private fun respondOk(id: String, result: JsonObject) {
        send(RelayFrames.routeOk(id, result))
    }

    private fun respondError(id: String, code: String, message: String) {
        send(RelayFrames.routeError(id, code, message))
    }

    private fun reject(frame: RelayServerFrame.Route, code: String, message: String) {
        respondError(frame.id, code, message)
    }

    private suspend fun localWorkspaceById(botId: String): RuntimeWorkspace? =
        host.workspaces().firstOrNull { workspace -> workspace.remote == null && workspace.id == botId }

    private suspend fun allSessionsForBot(botId: String, includeArchived: Boolean): List<ConversationSummary> {
        val active = host.sessions(botId)
        if (!includeArchived) {
            return active.filter { !it.archived }
        }
        val cwd = localWorkspaceById(botId)?.cwd
        val archived = if (cwd != null) {
            host.archivedSessions().filter { it.cwd == cwd }
        } else {
            host.archivedSessions()
        }
        return (active + archived).distinctBy { it.id }
    }

    private suspend fun ownsSession(botId: String, sessionId: String): Boolean {
        val owner = sessionBots[sessionId]
        if (owner != null) return owner == botId
        return allSessionsForBot(botId, includeArchived = true).any { it.id == sessionId }
    }

    private suspend fun collectBotSessionIds(botId: String): Set<String> {
        val ids = linkedSetOf<String>()
        allSessionsForBot(botId, includeArchived = true).forEach { ids += it.id }
        sessionBots.forEach { (sessionId, owner) ->
            if (owner == botId) ids += sessionId
        }
        return ids
    }

    private fun cleanupFeedAndQuestions(sessionId: String) {
        val feed = feeds.remove(sessionId)
        if (feed != null) {
            synchronized(feed) {
                feed.active = false
                feed.routeIds.clear()
            }
        }
        pendingOriginRouteId.remove(sessionId)
        pendingUserMessages.remove(sessionId)
        activeQuestions.entries.removeIf { it.value.sessionId == sessionId }
    }

    private suspend fun requireWorkspaceCwd(frame: RelayServerFrame.Route): String? {
        val botId = frame.params.string("botId").trim()
        if (botId.isEmpty() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isEmpty()) "botId is required" else "bot $botId is not available")
            return null
        }
        val workspace = localWorkspaceById(botId)
        val cwd = workspace?.cwd?.trim().orEmpty()
        if (cwd.isEmpty()) {
            reject(frame, "not_found", "Workspace directory is unavailable")
            return null
        }
        return cwd
    }

    private fun parseOptionalAvatarForWrite(params: JsonObject): String? {
        if (!params.containsKey("avatar") || params["avatar"] == JsonNull) {
            return null
        }
        val raw = (params["avatar"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.length > RelayFrames.MAX_AVATAR_DATA_URL_LENGTH) {
            throw RemoteFileException("payload_too_large", "Avatar image exceeds size limit")
        }
        val match = DATA_IMAGE_URL_REGEX.matchEntire(raw)
            ?: throw RemoteFileException("bad_request", "Avatar must be a PNG, JPEG, or WebP data URL")
        val declaredSubtype = match.groupValues[1].lowercase().let { if (it == "jpg") "jpeg" else it }
        val encoded = match.groupValues[2]
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw RemoteFileException("bad_request", "Avatar contains invalid base64 data")
        }
        if (bytes.isEmpty() || bytes.size > MAX_DECODED_AVATAR_BYTES) {
            throw RemoteFileException("payload_too_large", "Avatar image exceeds size limit")
        }
        val actualMime = RelayFrames.detectImageMime(bytes)
            ?: throw RemoteFileException("bad_request", "Unsupported avatar image format")
        if (actualMime != "image/$declaredSubtype") {
            throw RemoteFileException("bad_request", "Avatar image bytes do not match declared MIME type")
        }
        val dir = avatarStorageDir ?: return raw
        dir.mkdirs()
        val ext = when (actualMime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val target = File(dir, UUID.randomUUID().toString().replace("-", "") + "." + ext)
        FileOutputStream(target).use { output ->
            output.write(bytes)
        }
        return target.absolutePath
    }

    private fun frameAvatarOrStored(avatar: String?): String? {
        val raw = avatar?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("data:image/")) {
            return raw.takeIf { it.length <= RelayFrames.MAX_AVATAR_DATA_URL_LENGTH }
        }
        val file = File(raw)
        if (!file.isFile || file.length() !in 1L..MAX_DECODED_AVATAR_BYTES.toLong()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mime = RelayFrames.detectImageMime(bytes) ?: return null
        val encoded = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
        return encoded.takeIf { it.length <= RelayFrames.MAX_AVATAR_DATA_URL_LENGTH }
    }

    private fun deleteManagedAvatar(path: String?) {
        val dir = avatarStorageDir ?: return
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (file.parentFile?.absolutePath == dir.absolutePath) {
            runCatching { file.delete() }
        }
    }

    private fun serializeBot(id: String, name: String, avatar: String?, intro: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        avatar?.takeIf { it.isNotBlank() }?.let { put("avatar", it) }
        intro?.trim()?.take(RelayFrames.MAX_BOT_INTRO_LENGTH)?.takeIf { it.isNotEmpty() }?.let { put("intro", it) }
    }

    private suspend fun effectiveDefaultConfig(botId: String): ModelConfig =
        localWorkspaceById(botId)?.defaultModel ?: host.currentConfig()

    private fun parseMemoryAccessScopeStrict(raw: String): MemoryAccessScope? =
        when (raw.trim().uppercase()) {
            "OFF" -> MemoryAccessScope.OFF
            "WORK_ONLY" -> MemoryAccessScope.WORK_ONLY
            "PERSONAL" -> MemoryAccessScope.PERSONAL
            else -> null
        }

    private fun serializeFileEntry(entry: RemoteFileEntryDto): JsonObject = buildJsonObject {
        put("name", entry.name)
        put("path", entry.path)
        put("type", entry.type)
        put("size", entry.size)
        put("modifiedAt", entry.modifiedAt)
        entry.mimeType?.let { put("mimeType", it) }
    }

    private fun markSuppressedSync(sessionId: String) {
        suppressedSessionSync[sessionId] = System.currentTimeMillis() + SUPPRESS_SYNC_WINDOW_MS
    }

    private fun consumeSuppressedSync(sessionId: String): Boolean {
        val until = suppressedSessionSync.remove(sessionId) ?: return false
        return System.currentTimeMillis() <= until
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    private fun JsonObject.optionalTrimmedString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.optionalNullableString(key: String): String? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private data class BotWatcher(
        val routeId: String,
        val deviceId: String,
        val botId: String?,
        val boundSessionId: String?,
    )

    private data class BufferedEvent(
        val seq: Long,
        val event: JsonObject,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private class Feed(
        initialRoutes: Map<String, String> = emptyMap(),
        val seq: AtomicLong,
        @Volatile var updatedAt: Long,
        @Volatile var active: Boolean = true,
        var evictedThroughSeq: Long = -1L,
        @Volatile var turnStartSeq: Long = 0L,
        val buffer: ArrayDeque<BufferedEvent> = ArrayDeque(),
        val routeIds: MutableMap<String, String> = LinkedHashMap(initialRoutes),
        val sendLock: Any = Any(),
    )

    private companion object {
        private const val ACTIVE_SESSION_SENTINEL = "__active__"
        private const val HOME_BOT_ID = "home"
        private const val MAX_BUFFERED_EVENTS = 4_000
        private const val MAX_RETAINED_FINISHED_FEEDS = 16
        private const val DEFAULT_FILE_LIST_LIMIT = 500
        private const val MAX_SESSION_TITLE_LENGTH = 128
        private const val MAX_DECODED_AVATAR_BYTES = 50_000
        private const val MAX_HISTORY_WIRE_CHARS = 2_621_440
        private const val MAX_HISTORY_BLOCK_CHARS = 262_144
        private const val MAX_HISTORY_TOOL_RESULT_CHARS = 131_072
        private const val SUPPRESS_SYNC_WINDOW_MS = 2_000L
        private val PRESENT_ARTIFACT_TYPES = setOf("file", "image", "video", "html")
        private val DATA_IMAGE_URL_REGEX = Regex("^data:image/(png|jpeg|jpg|webp);base64,([A-Za-z0-9+/=]+)$", RegexOption.IGNORE_CASE)
    }
}
