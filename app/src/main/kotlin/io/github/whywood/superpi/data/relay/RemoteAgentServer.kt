package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SessionRuntimeEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class RemoteAgentServer(
    private val host: AgentRuntime,
    private val bots: suspend () -> List<RelayBot>,
    private val send: (JsonObject) -> Boolean,
    scope: CoroutineScope,
    private val maxBufferedEvents: Int = MAX_BUFFERED_EVENTS,
) {
    private val feeds = ConcurrentHashMap<String, Feed>()

    init {
        scope.launch {
            host.events.collect { event -> onRuntimeEvent(event) }
        }
    }

    fun reset() {
        feeds.clear()
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
        if (botId.isBlank()) {
            reject(frame, "bot_not_found", "botId is required")
            return
        }
        val text = frame.params.string("text")
        if (text.isBlank()) {
            reject(frame, "bad_request", "text is required")
            return
        }
        val existing = frame.params.string("sessionId").ifBlank { null }
        val sessionId = existing ?: run {
            if (bots().none { bot -> bot.id == botId }) {
                reject(frame, "bot_not_found", "bot $botId is not available")
                return
            }
            host.newSession(botId).sessionId
        }
        val feed = Feed(routeId = frame.id, seq = AtomicLong(0), updatedAt = System.currentTimeMillis())
        feeds[sessionId] = feed
        pruneFinishedFeeds()
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        host.send(sessionId, text)
    }

    private suspend fun steer(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        val text = frame.params.string("text")
        if (sessionId.isBlank() || text.isBlank()) {
            reject(frame, "bad_request", "sessionId and text are required")
            return
        }
        feeds.putIfAbsent(sessionId, Feed(frame.id, AtomicLong(0), System.currentTimeMillis()))
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        host.steer(sessionId, text)
    }

    private suspend fun followUp(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        val text = frame.params.string("text")
        if (sessionId.isBlank() || text.isBlank()) {
            reject(frame, "bad_request", "sessionId and text are required")
            return
        }
        feeds.putIfAbsent(sessionId, Feed(frame.id, AtomicLong(0), System.currentTimeMillis()))
        respondOk(frame.id, buildJsonObject { put("sessionId", sessionId) })
        host.followUp(sessionId, text)
    }

    private fun abort(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        val stopped = host.stop(sessionId)
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("stopped", stopped.size)
            },
        )
    }

    private fun state(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
        respondOk(
            frame.id,
            buildJsonObject {
                put("sessionId", sessionId)
                put("busy", host.busySessionIds.value.contains(sessionId))
            },
        )
    }

    private fun resume(frame: RelayServerFrame.Route) {
        val sessionId = frame.params.string("sessionId")
        if (sessionId.isBlank()) {
            reject(frame, "bad_request", "sessionId is required")
            return
        }
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

    private fun onRuntimeEvent(session: SessionRuntimeEvent) {
        val feed = feeds[session.sessionId] ?: return
        val events = RelayEventMapper.map(session.event)
        if (events.isEmpty()) return
        val terminal = session.event is RuntimeEvent.RunFinished || session.event is RuntimeEvent.RunFailed
        val toSend = mutableListOf<Pair<String, BufferedEvent>>()
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
    )

    private companion object {
        private const val MAX_BUFFERED_EVENTS = 4_000
        private const val MAX_RETAINED_FINISHED_FEEDS = 16
    }
}
