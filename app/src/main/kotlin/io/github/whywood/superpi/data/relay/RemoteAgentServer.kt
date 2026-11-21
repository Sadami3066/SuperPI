package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SessionRuntimeEvent
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.invocationKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class RemoteAgentServer(
    private val host: AgentRuntime,
    private val bots: suspend () -> List<RelayBot>,
    private val send: (JsonObject) -> Boolean,
    private val scope: CoroutineScope,
    private val maxBufferedEvents: Int = MAX_BUFFERED_EVENTS,
) {
    private val feeds = ConcurrentHashMap<String, Feed>()
    private val sessionBots = ConcurrentHashMap<String, String>()

    init {
        scope.launch {
            host.events.collect { event -> onRuntimeEvent(event) }
        }
    }

    fun reset() {
        feeds.clear()
        sessionBots.clear()
    }

    suspend fun handle(frame: RelayServerFrame.Route) {
        try {
            when (frame.method) {
                "bot.prompt" -> prompt(frame)
                "bot.steer" -> steer(frame)
                "bot.follow_up" -> followUp(frame)
                "bot.abort" -> abort(frame)
                "bot.state" -> state(frame)
                "bot.resume" -> resume(frame)
                "bot.sessions" -> listSessions(frame)
                "bot.history" -> history(frame)
                "bot.approval" -> reject(frame, "unsupported", "interactive approvals are not enabled on this host")
                else -> reject(frame, "bad_request", "unknown method ${frame.method}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        val text = frame.params.string("text")
        val images = parseImageParts(frame.params)
        if (text.isBlank() && images.isEmpty()) {
            reject(frame, "bad_request", "text or images is required")
            return
        }
        val existing = frame.params.string("sessionId").ifBlank { null }
        val sessionId = if (existing != null) {
            resolveExistingSession(botId, existing) ?: run {
                reject(frame, "session_not_found", "session $existing was not found")
                return
            }
        } else {
            host.newSession(botId).sessionId.also { created -> sessionBots[created] = botId }
        }
        if (host.busySessionIds.value.contains(sessionId) || feeds[sessionId]?.active == true) {
            reject(frame, "bad_request", "session $sessionId is busy")
            return
        }
        val feed = Feed(routeId = frame.id, seq = AtomicLong(0), updatedAt = System.currentTimeMillis())
        feeds[sessionId] = feed
        pruneFinishedFeeds()
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        try {
            host.send(sessionId, text, images)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
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
        val feed = ensureActiveFeed(sessionId, frame.id)
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        try {
            host.steer(sessionId, text, images)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitFailureEvents(sessionId, feed, error.message ?: "the bot failed")
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
        val feed = ensureActiveFeed(sessionId, frame.id)
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        try {
            host.followUp(sessionId, text, images)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitFailureEvents(sessionId, feed, error.message ?: "the bot failed")
        }
    }

    private fun ensureActiveFeed(sessionId: String, routeId: String): Feed {
        return feeds.compute(sessionId) { _, existing ->
            if (existing != null && existing.active) {
                existing
            } else {
                Feed(routeId, AtomicLong(0), System.currentTimeMillis())
            }
        }!!
    }

    private suspend fun verifySessionAccess(
        botId: String,
        sessionId: String,
        frame: RelayServerFrame.Route,
    ): Boolean {
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
        }
        return true
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
        val toSend = mutableListOf<Pair<String, BufferedEvent>>()
        synchronized(feed.sendLock) {
            synchronized(feed) {
                feed.updatedAt = System.currentTimeMillis()
                for (event in events) {
                    val entry = BufferedEvent(feed.seq.getAndIncrement(), event)
                    feed.buffer.addLast(entry)
                    while (feed.buffer.size > maxBufferedEvents) {
                        val removed = feed.buffer.removeFirst()
                        feed.evictedThroughSeq = removed.seq
                    }
                    toSend += feed.routeId to entry
                }
                feed.active = false
            }
            for ((routeId, entry) in toSend) {
                if (!send(RelayFrames.routeEvent(routeId, entry.seq, entry.event))) break
            }
        }
        if (feeds[sessionId] === feed) pruneFinishedFeeds()
    }

    private suspend fun abort(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val botId = frame.params.string("botId")
        if (botId.isNotBlank() && !verifySessionAccess(botId, sessionId, frame)) return
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
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val botId = frame.params.string("botId")
        if (botId.isNotBlank() && !verifySessionAccess(botId, sessionId, frame)) return
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("busy", host.busySessionIds.value.contains(sessionId))
            },
        )
    }

    private suspend fun resume(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val botId = frame.params.string("botId")
        if (botId.isNotBlank() && !verifySessionAccess(botId, sessionId, frame)) return
        val feed = feeds[sessionId]
        if (feed == null) {
            if (!host.busySessionIds.value.contains(sessionId)) {
                reject(frame, "session_not_found", "session $sessionId is not active")
                return
            }
            val created = Feed(frame.id, AtomicLong(0), System.currentTimeMillis())
            feeds[sessionId] = created
            respondOk(
                frame.id,
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("busy", true)
                    put("gap", false)
                },
            )
            return
        }
        val afterSeq = frame.params.long("afterSeq") ?: -1L
        synchronized(feed.sendLock) {
            val snapshot: List<BufferedEvent>
            val gap: Boolean
            val busy: Boolean
            synchronized(feed) {
                feed.routeId = frame.id
                feed.updatedAt = System.currentTimeMillis()
                gap = afterSeq >= 0L && feed.evictedThroughSeq > afterSeq
                snapshot = feed.buffer.filter { entry -> entry.seq > afterSeq }
                busy = feed.active || host.busySessionIds.value.contains(sessionId)
            }
            respondOk(
                frame.id,
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("busy", busy)
                    put("gap", gap)
                },
            )
            for (entry in snapshot) {
                if (!send(RelayFrames.routeEvent(frame.id, entry.seq, entry.event))) break
            }
        }
    }

    private suspend fun listSessions(frame: RelayServerFrame.Route) {
        val botId = frame.params.string("botId")
        if (botId.isBlank() || bots().none { bot -> bot.id == botId }) {
            reject(frame, "bot_not_found", if (botId.isBlank()) "botId is required" else "bot $botId is not available")
            return
        }
        val ordered = host.sessions(botId)
            .filter { !it.archived }
            .sortedWith(compareByDescending<io.github.whywood.superpi.runtime.ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
        respondOk(
            frame.id,
            buildJsonObject {
                put(
                    "sessions",
                    buildJsonArray {
                        for (session in ordered) {
                            addJsonObject {
                                put("id", session.id)
                                put("title", session.title.ifBlank { "Untitled" })
                                put("updatedAt", session.updatedAt)
                                if (session.pinned) put("pinned", true)
                            }
                        }
                    },
                )
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
        val serialized = serializeHistory(messages)
        val activeFeed = if (busy) feeds[resolvedId] else null
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", resolvedId)
                summary?.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                put("busy", busy)
                if (activeFeed != null) {
                    put("seq", maxOf(-1L, activeFeed.seq.get() - 1L))
                }
                put("messages", serialized)
            },
        )
    }

    private fun serializeHistory(messages: List<AssistantMessage>): JsonArray = buildJsonArray {
        for (message in messages) {
            when (message.role) {
                AssistantRole.USER -> {
                    if (message.text.isNotBlank()) {
                        addJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", message.text)
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
                                        contentBlocks += buildJsonObject {
                                            put("type", "thinking")
                                            put("thinking", step.text)
                                        }
                                    }
                                }

                                is AssistantStep.Text -> {
                                    if (step.text.isNotBlank()) {
                                        contentBlocks += buildJsonObject {
                                            put("type", "text")
                                            put("text", step.text)
                                        }
                                    }
                                }

                                is AssistantStep.Tool -> {
                                    val trace = step.trace
                                    val callId = trace.invocationKey
                                    val args = trace.rawArguments ?: JsonObject(
                                        trace.arguments.mapValues { (_, v) -> JsonPrimitive(v) },
                                    )
                                    contentBlocks += buildJsonObject {
                                        put("type", "toolCall")
                                        put("id", callId)
                                        put("name", trace.name)
                                        put("arguments", args)
                                    }
                                    if (trace.status != ToolActivityStatus.RUNNING || trace.output != null || trace.error != null) {
                                        toolResults += buildJsonObject {
                                            put("role", "toolResult")
                                            put("toolCallId", callId)
                                            put("toolName", trace.name)
                                            put("isError", trace.status == ToolActivityStatus.FAILED)
                                            put(
                                                "content",
                                                buildJsonArray {
                                                    addJsonObject {
                                                        put("type", "text")
                                                        put("text", trace.error ?: trace.output.orEmpty())
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
                        contentBlocks += buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                    }
                    if (contentBlocks.isNotEmpty() || message.error != null || message.status == MessageStatus.FAILED) {
                        addJsonObject {
                            put("role", "assistant")
                            put("content", JsonArray(contentBlocks))
                            message.error?.takeIf { it.isNotBlank() }?.let { put("errorMessage", it) }
                        }
                    }
                    for (result in toolResults) {
                        add(result)
                    }
                }
            }
        }
    }

    private fun onRuntimeEvent(session: SessionRuntimeEvent) {
        val feed = feeds[session.sessionId] ?: return
        val events = RelayEventMapper.map(session.event)
        if (events.isEmpty()) return
        val terminal = session.event is RuntimeEvent.RunFinished || session.event is RuntimeEvent.RunFailed
        val toSend = mutableListOf<Pair<String, BufferedEvent>>()
        synchronized(feed.sendLock) {
            synchronized(feed) {
                feed.updatedAt = System.currentTimeMillis()
                for (event in events) {
                    val entry = BufferedEvent(feed.seq.getAndIncrement(), event)
                    feed.buffer.addLast(entry)
                    while (feed.buffer.size > maxBufferedEvents) {
                        val removed = feed.buffer.removeFirst()
                        feed.evictedThroughSeq = removed.seq
                    }
                    toSend += feed.routeId to entry
                }
                if (terminal) feed.active = false
            }
            for ((routeId, entry) in toSend) {
                if (!send(RelayFrames.routeEvent(routeId, entry.seq, entry.event))) break
            }
        }
        if (terminal) pruneFinishedFeeds()
    }

    private fun pruneFinishedFeeds() {
        val finished = feeds.entries
            .filter { (_, feed) -> !feed.active }
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

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private data class BufferedEvent(
        val seq: Long,
        val event: JsonObject,
    )

    private class Feed(
        @Volatile var routeId: String,
        val seq: AtomicLong,
        @Volatile var updatedAt: Long,
        @Volatile var active: Boolean = true,
        var evictedThroughSeq: Long = -1L,
        val buffer: ArrayDeque<BufferedEvent> = ArrayDeque(),
        val sendLock: Any = Any(),
    )

    private companion object {
        private const val MAX_BUFFERED_EVENTS = 4_000
        private const val MAX_RETAINED_FINISHED_FEEDS = 16
    }
}
