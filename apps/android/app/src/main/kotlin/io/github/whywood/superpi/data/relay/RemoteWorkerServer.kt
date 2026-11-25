package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.WorkerConflictException
import io.github.whywood.superpi.runtime.WorkerNotFoundException
import io.github.whywood.superpi.runtime.WorkerPoolFullException
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.superagent.LocalWorkerEvent
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import io.github.whywood.superpi.runtime.superagent.WorkerHistoryWireCodec
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class RemoteWorkerServer(
    private val pool: LocalWorkerPool,
    private val bots: suspend () -> List<RelayBot>,
    private val send: (JsonObject) -> Boolean,
    private val scope: CoroutineScope,
    private val presentedArtifacts: PresentedArtifactRegistry,
    private val resolveBotCwd: suspend (String) -> String? = { null },
) {
    private val streamLock = Any()
    private val routesByWorker = ConcurrentHashMap<String, MutableMap<String, StreamSubscriber>>()
    private val routeToWorker = ConcurrentHashMap<String, String>()
    private val botCwds = ConcurrentHashMap<String, String>()

    private data class StreamSubscriber(
        val routeId: String,
        val deviceId: String,
        val persistent: Boolean,
        var ready: Boolean = false,
        var lastSentSeq: Long = 0L,
        val pendingEvents: ArrayDeque<LocalWorkerEvent> = ArrayDeque(),
    )

    init {
        scope.launch {
            pool.wireEvents.collect { event ->
                onWorkerWireEvent(event)
            }
        }
    }

    fun reset() {
        synchronized(streamLock) {
            routesByWorker.clear()
            routeToWorker.clear()
        }
        botCwds.clear()
    }

    fun onPeerOffline(deviceId: String) {
        if (deviceId.isBlank()) return
        synchronized(streamLock) {
            val iterator = routesByWorker.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val routeIterator = entry.value.entries.iterator()
                while (routeIterator.hasNext()) {
                    val sub = routeIterator.next()
                    if (sub.value.deviceId == deviceId) {
                        routeToWorker.remove(sub.key)
                        routeIterator.remove()
                    }
                }
                if (entry.value.isEmpty()) iterator.remove()
            }
        }
    }

    fun onRouteRejected(routeId: String) {
        if (routeId.isBlank()) return
        synchronized(streamLock) {
            val workerId = routeToWorker.remove(routeId) ?: return
            val map = routesByWorker[workerId] ?: return
            map.remove(routeId)
            if (map.isEmpty()) routesByWorker.remove(workerId)
        }
    }

    suspend fun handle(frame: RelayServerFrame.Route) {
        try {
            when (frame.method) {
                "worker.list" -> handleList(frame)
                "worker.start" -> handleStart(frame)
                "worker.continue" -> handleContinue(frame)
                "worker.resume" -> handleResume(frame)
                "worker.steer" -> handleSteer(frame)
                "worker.abort" -> handleAbort(frame)
                "worker.approval" -> handleApproval(frame)
                "worker.inspect" -> handleInspect(frame)
                "worker.delete" -> handleDelete(frame)
                "worker.compact" -> handleCompact(frame)
                "worker.set_model" -> handleSetModel(frame)
                else -> reject(frame, "bad_request", "unknown method ${frame.method}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: WorkerPoolFullException) {
            reject(
                frame,
                "conflict",
                "Worker pool full for bot ${error.botId} (max ${error.maxSlots})",
            )
        } catch (error: WorkerConflictException) {
            reject(frame, "conflict", error.message)
        } catch (error: WorkerNotFoundException) {
            reject(frame, "not_found", error.message ?: "worker not found")
        } catch (error: IllegalArgumentException) {
            reject(frame, "bad_request", error.message ?: "invalid request")
        } catch (error: Exception) {
            reject(frame, "internal", error.message ?: "worker request failed")
        }
    }

    private suspend fun handleList(frame: RelayServerFrame.Route) {
        val botId = frame.params.optionalString("botId")
        if (botId != null && bots().none { it.id == botId }) {
            reject(frame, "bot_not_found", "bot $botId is not available")
            return
        }
        val listed = pool.list(botId)
        respondOk(
            frame.id,
            buildJsonObject {
                put("maxWorkersPerBot", pool.maxSlotsPerBot())
                put(
                    "workers",
                    buildJsonArray {
                        listed.forEach { add(serializeWorkerSummary(it)) }
                    },
                )
            },
        )
    }

    private suspend fun handleStart(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val task = frame.params.string("task").trim()
        val images = parseImageParts(frame.params)
        if (task.isEmpty() && images.isEmpty()) {
            reject(frame, "bad_request", "task is required")
            return
        }
        val workerId = frame.params.optionalString("workerId")
            ?: ("wrk-" + UUID.randomUUID().toString().replace("-", "").take(12))
        val callerDeviceId = frame.params.optionalString("callerDeviceId") ?: frame.from
        val title = frame.params.optionalString("title") ?: frame.params.optionalString("name")
        val providerId = frame.params.optionalString("providerId")
        val modelId = frame.params.optionalString("modelId")
        val modelOverride = if (providerId != null && modelId != null) {
            ModelConfig(providerId, modelId, parseThinkingLevel(frame.params.optionalString("thinkingLevel")))
        } else {
            null
        }

        registerSubscriber(workerId, frame.id, frame.from, persistent = false)
        val created = try {
            pool.start(
                botId = botId,
                task = task,
                requestedWorkerId = workerId,
                images = images,
                originCaller = callerDeviceId,
                modelOverride = modelOverride,
                title = title,
            )
        } catch (error: Throwable) {
            unregisterSubscriber(workerId, frame.id)
            throw error
        }

        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", created.workerId)
                put("botId", created.botId)
                put("status", created.status.name)
                put("usedSlots", pool.usedSlotsForBot(botId))
                put("maxSlots", pool.maxSlotsPerBot())
                put("worker", serializeWorkerSummary(created))
            },
        )
        markReadyAndFlush(created.workerId, frame.id, afterSeq = 0L)
    }

    private suspend fun handleContinue(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val message = frame.params.string("message").trim()
        val images = parseImageParts(frame.params)
        if (message.isEmpty() && images.isEmpty()) {
            reject(frame, "bad_request", "message is required")
            return
        }
        val baselineSeq = runCatching { pool.replay(workerId, null).seq }.getOrDefault(0L)
        registerSubscriber(workerId, frame.id, frame.from, persistent = false)
        val updated = try {
            pool.continueWorker(
                botId = botId,
                workerId = workerId,
                message = message,
                images = images,
                originCaller = frame.from,
            )
        } catch (error: Throwable) {
            unregisterSubscriber(workerId, frame.id)
            throw error
        }
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", updated.workerId)
                put("botId", updated.botId)
                put("status", updated.status.name)
                put("turnCount", updated.turnCount)
                put("seq", baselineSeq)
                put("worker", serializeWorkerSummary(updated))
            },
        )
        markReadyAndFlush(updated.workerId, frame.id, afterSeq = baselineSeq)
    }

    private suspend fun handleResume(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val afterSeq = frame.params["afterSeq"]?.let { it as? JsonPrimitive }?.longOrNull
        registerSubscriber(workerId, frame.id, frame.from, persistent = true)
        val replay = try {
            pool.replay(workerId, afterSeq)
        } catch (error: Throwable) {
            unregisterSubscriber(workerId, frame.id)
            throw error
        }
        if (replay.record.botId != botId) {
            unregisterSubscriber(workerId, frame.id)
            throw WorkerNotFoundException(workerId)
        }
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", replay.record.workerId)
                put("botId", replay.record.botId)
                put("status", replay.record.status.name)
                put("seq", replay.seq)
                put("evictedBefore", replay.evictedBefore)
                put("worker", serializeWorkerSummary(replay.record))
            },
        )
        val minAfterSeq = if (afterSeq != null && afterSeq > replay.seq) null else afterSeq
        flushSubscriberReplayAndPending(
            workerId = workerId,
            routeId = frame.id,
            replayEvents = replay.events,
            minAfterSeq = minAfterSeq,
        )
    }

    private suspend fun handleSteer(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val message = frame.params.string("message").trim()
        val images = parseImageParts(frame.params)
        if (message.isEmpty() && images.isEmpty()) {
            reject(frame, "bad_request", "message or images is required")
            return
        }
        val updated = pool.steer(botId, workerId, message, images)
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", updated.workerId)
                put("status", updated.status.name)
            },
        )
    }

    private suspend fun handleAbort(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val stopped = pool.stop(botId, workerId)
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", stopped.workerId)
                put("status", stopped.status.name)
            },
        )
    }

    private suspend fun handleApproval(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val requestId = frame.params.optionalString("requestId") ?: run {
            reject(frame, "bad_request", "requestId is required")
            return
        }
        val record = pool.inspect(workerId, tail = 1).record
        if (record.botId != botId) throw WorkerNotFoundException(workerId)

        val dismissed = (frame.params["dismissed"] as? JsonPrimitive)?.booleanOrNull == true ||
            (frame.params["cancelled"] as? JsonPrimitive)?.booleanOrNull == true
        if (dismissed) {
            pool.dismissQuestion(workerId, requestId)
        } else {
            val answers = RelayFrames.parseQuestionAnswers(frame.params["answers"])
            pool.answerQuestion(workerId, requestId, answers)
        }
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", workerId)
                put("requestId", requestId)
                put("resolved", true)
            },
        )
    }

    private suspend fun handleInspect(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val tail = (frame.params["tail"] as? JsonPrimitive)?.intOrNull ?: 30
        val result = pool.inspect(workerId, tail)
        if (result.record.botId != botId) throw WorkerNotFoundException(workerId)
        val history = try {
            pool.sessionHistory(workerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val sessionSnap = pool.observeSession(workerId).value
        respondOk(
            frame.id,
            buildJsonObject {
                put("worker", serializeWorkerSummary(result.record))
                if (sessionSnap.providerId.isNotBlank()) put("providerId", sessionSnap.providerId)
                if (sessionSnap.modelId.isNotBlank()) put("modelId", sessionSnap.modelId)
                put("thinkingLevel", sessionSnap.thinkingLevel.name)
                put(
                    "activeTimers",
                    buildJsonArray {
                        result.activeTimers.forEach { timer ->
                            add(
                                buildJsonObject {
                                    put("id", timer.id)
                                    put("name", timer.name)
                                    put("enabled", timer.enabled)
                                    timer.nextRunAt?.let { put("nextRunAt", it) }
                                },
                            )
                        }
                    },
                )
                put(
                    "runningJobs",
                    buildJsonArray {
                        result.runningJobs.forEach { job ->
                            add(
                                buildJsonObject {
                                    put("jobId", job.jobId)
                                    put("command", job.command)
                                    put("status", job.status.name)
                                },
                            )
                        }
                    },
                )
                put(
                    "steps",
                    buildJsonArray {
                        result.recentSteps.forEach { step ->
                            add(
                                buildJsonObject {
                                    put("callId", step.callId)
                                    put("toolName", step.toolName)
                                    put("argsJson", step.argsJson)
                                    put("status", step.status.name)
                                    step.outputPreview?.let { put("outputPreview", it) }
                                    put("isError", step.isError)
                                    put("updatedAt", step.updatedAt)
                                },
                            )
                        }
                    },
                )
                put(
                    "artifacts",
                    buildJsonArray {
                        result.record.artifacts.forEach { art ->
                            val file = File(art.path)
                            val registered = if (file.isFile) {
                                presentedArtifacts.register(botId, file, art.mimeType, art.name)
                            } else {
                                null
                            }
                            add(
                                buildJsonObject {
                                    put("artifactId", art.artifactId)
                                    put("kind", art.kind)
                                    put("name", art.name)
                                    art.title?.let { put("title", it) }
                                    art.mimeType?.let { put("mimeType", it) }
                                    art.sizeBytes?.let { put("sizeBytes", it) }
                                    registered?.let { put("remoteArtifactId", it.artifactId) }
                                },
                            )
                        }
                    },
                )
                put(
                    "cards",
                    buildJsonArray {
                        result.record.cards.forEach { card ->
                            add(
                                buildJsonObject {
                                    put("cardId", card.cardId)
                                    put("toolName", card.toolName)
                                    card.title?.let { put("title", it) }
                                    put("argsJson", card.argsJson)
                                    put("outputText", card.outputText)
                                    put("detailsJson", card.detailsJson)
                                    put("payloadJson", card.payloadJson)
                                    put("createdAt", card.createdAt)
                                },
                            )
                        }
                    },
                )
                val encodedHistory = WorkerHistoryWireCodec.encodeMessagesBudgeted(history) { name, output ->
                    if (name == "present") enrichPresentResult(botId, workerId, output) else output
                }
                put("messages", encodedHistory.messages)
                if (encodedHistory.truncated) {
                    put("truncated", true)
                }
                result.usage?.let { put("usage", encodeUsage(it)) }
            },
        )
    }

    private suspend fun handleDelete(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        pool.delete(botId, workerId)
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", workerId)
                put("deleted", true)
            },
        )
    }

    private suspend fun handleCompact(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val current = pool.inspect(workerId, tail = 1)
        if (current.record.botId != botId) throw WorkerNotFoundException(workerId)
        val updated = pool.compact(workerId)
        val snap = pool.observeSession(workerId).value
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", updated.workerId)
                put("status", updated.status.name)
                put("worker", serializeWorkerSummary(updated, snap.usage))
                snap.usage?.let { put("usage", encodeUsage(it)) }
            },
        )
    }

    private suspend fun handleSetModel(frame: RelayServerFrame.Route) {
        val botId = requireBotAvailable(frame) ?: return
        val workerId = frame.params.optionalString("workerId") ?: run {
            reject(frame, "bad_request", "workerId is required")
            return
        }
        val current = pool.inspect(workerId, tail = 1)
        if (current.record.botId != botId) throw WorkerNotFoundException(workerId)
        val providerId = frame.params.optionalString("providerId")
        val modelId = frame.params.optionalString("modelId")
        val rawThinking = frame.params.optionalString("thinkingLevel")
        val requestedThinking = if (rawThinking != null) {
            parseThinkingLevelStrict(rawThinking) ?: run {
                reject(frame, "bad_request", "Invalid thinkingLevel: $rawThinking")
                return
            }
        } else {
            null
        }
        if (providerId != null && modelId != null) {
            val thinkingLevel = requestedThinking ?: pool.observeSession(workerId).value.thinkingLevel
            pool.changeModel(workerId, providerId, modelId, thinkingLevel)
        } else if (requestedThinking != null) {
            pool.changeThinking(workerId, requestedThinking)
        } else {
            reject(frame, "bad_request", "providerId/modelId or thinkingLevel is required")
            return
        }
        val after = pool.inspect(workerId, tail = 1)
        val snap = pool.observeSession(workerId).value
        respondOk(
            frame.id,
            buildJsonObject {
                put("workerId", workerId)
                put("providerId", snap.providerId)
                put("modelId", snap.modelId)
                put("thinkingLevel", snap.thinkingLevel.name)
                put("worker", serializeWorkerSummary(after.record, after.usage))
            },
        )
    }

    private fun registerSubscriber(
        workerId: String,
        routeId: String,
        deviceId: String,
        persistent: Boolean,
    ) {
        synchronized(streamLock) {
            val map = routesByWorker.getOrPut(workerId) { LinkedHashMap() }
            if (deviceId.isNotBlank()) {
                val staleIds = map.values.filter { it.deviceId == deviceId }.map { it.routeId }
                staleIds.forEach { oldId ->
                    map.remove(oldId)
                    routeToWorker.remove(oldId)
                }
            }
            map[routeId] = StreamSubscriber(routeId = routeId, deviceId = deviceId, persistent = persistent)
            routeToWorker[routeId] = workerId
        }
    }

    private fun unregisterSubscriber(workerId: String, routeId: String) {
        synchronized(streamLock) {
            routeToWorker.remove(routeId)
            val map = routesByWorker[workerId] ?: return
            map.remove(routeId)
            if (map.isEmpty()) routesByWorker.remove(workerId)
        }
    }

    private suspend fun markReadyAndFlush(workerId: String, routeId: String, afterSeq: Long) {
        val minAfterSeq = afterSeq.takeIf { it > 0L }
        val replay = runCatching { pool.replay(workerId, minAfterSeq) }.getOrNull()
        flushSubscriberReplayAndPending(
            workerId = workerId,
            routeId = routeId,
            replayEvents = replay?.events.orEmpty(),
            minAfterSeq = minAfterSeq,
        )
    }

    private fun flushSubscriberReplayAndPending(
        workerId: String,
        routeId: String,
        replayEvents: List<LocalWorkerEvent>,
        minAfterSeq: Long?,
    ) {
        val sub = synchronized(streamLock) { routesByWorker[workerId]?.get(routeId) } ?: return
        var settledReached = false
        synchronized(sub) {
            if (minAfterSeq != null && sub.lastSentSeq < minAfterSeq) {
                sub.lastSentSeq = minAfterSeq
            }
            replayEvents.forEach { ev ->
                if (ev.seq > sub.lastSentSeq) {
                    send(RelayFrames.routeEvent(routeId, ev.seq, enrichWireEvent(ev)))
                    sub.lastSentSeq = ev.seq
                    if (ev.terminal) settledReached = true
                }
            }
            while (sub.pendingEvents.isNotEmpty()) {
                val ev = sub.pendingEvents.removeFirst()
                if (ev.seq > sub.lastSentSeq) {
                    send(RelayFrames.routeEvent(routeId, ev.seq, enrichWireEvent(ev)))
                    sub.lastSentSeq = ev.seq
                    if (ev.terminal) settledReached = true
                }
            }
            sub.ready = true
        }
        if (settledReached && !sub.persistent) {
            unregisterSubscriber(workerId, routeId)
        }
    }

    private fun onWorkerWireEvent(event: LocalWorkerEvent) {
        val subs = synchronized(streamLock) {
            routesByWorker[event.workerId]?.values?.toList().orEmpty()
        }
        if (subs.isEmpty()) return
        val enriched = enrichWireEvent(event)
        val toUnregister = mutableListOf<String>()
        for (sub in subs) {
            synchronized(sub) {
                if (!sub.ready) {
                    if (sub.pendingEvents.size >= MAX_PENDING_SUBSCRIBER_EVENTS) {
                        sub.pendingEvents.removeFirst()
                    }
                    sub.pendingEvents.addLast(event)
                    return@synchronized
                }
                if (event.seq <= sub.lastSentSeq) return@synchronized
                send(RelayFrames.routeEvent(sub.routeId, event.seq, enriched))
                sub.lastSentSeq = event.seq
                if (event.terminal && !sub.persistent) {
                    toUnregister += sub.routeId
                }
            }
        }
        toUnregister.forEach { unregisterSubscriber(event.workerId, it) }
    }

    private fun enrichWireEvent(event: LocalWorkerEvent): JsonObject {
        val json = event.event
        if ((json["type"] as? JsonPrimitive)?.contentOrNull != "tool_result") return json
        if ((json["name"] as? JsonPrimitive)?.contentOrNull != "present") return json
        if ((json["isError"] as? JsonPrimitive)?.booleanOrNull == true) return json
        val rawResult = (json["result"] as? JsonPrimitive)?.contentOrNull ?: return json
        val enriched = enrichPresentResult(event.botId, event.workerId, rawResult)
        if (enriched == rawResult) return json
        return buildJsonObject {
            for ((k, v) in json) {
                if (k == "result") put("result", enriched) else put(k, v)
            }
        }
    }

    private fun enrichPresentResult(botId: String, workerId: String, rawOutput: String): String {
        val root = runCatching { JSON.parseToJsonElement(rawOutput).jsonObject }.getOrNull() ?: return rawOutput
        val items = root["items"] as? JsonArray ?: return rawOutput
        val workerCwd = pool.workerWorkspaceDir(botId, workerId).absolutePath
        var changed = false
        val updatedItems = buildJsonArray {
            for (element in items) {
                val obj = element as? JsonObject
                if (obj == null) {
                    add(element)
                    continue
                }
                val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim()
                val rawPath = (obj["path"] as? JsonPrimitive)?.contentOrNull?.trim()
                if (type !in PRESENT_ARTIFACT_TYPES || rawPath.isNullOrEmpty()) {
                    add(obj)
                    continue
                }
                val file = resolvePresentedLocalFile(rawPath, workerCwd)?.takeIf { it.isFile }
                    ?: resolvePresentedLocalFile(rawPath, botCwds[botId])
                val mime = (obj["mime"] as? JsonPrimitive)?.contentOrNull
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                val registered = if (file != null && file.isFile) presentedArtifacts.register(botId, file, mime, name) else null
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

    private suspend fun requireBotAvailable(frame: RelayServerFrame.Route): String? {
        val botId = frame.params.optionalString("botId")
        if (botId == null || bots().none { it.id == botId }) {
            reject(
                frame,
                "bot_not_found",
                if (botId == null) "botId is required" else "bot $botId is not available",
            )
            return null
        }
        resolveBotCwd(botId)?.takeIf { it.isNotBlank() }?.let { botCwds[botId] = it }
        return botId
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

    private fun serializeWorkerSummary(record: WorkerRunRecord, usage: SessionUsage? = null): JsonObject = buildJsonObject {
        put("workerId", record.workerId)
        put("botId", record.botId)
        put("botName", record.botName)
        put("title", record.title)
        put("status", record.status.name)
        put("lastOutcome", record.lastOutcome.name)
        put("turnCount", record.turnCount)
        put("createdAt", record.createdAt)
        put("updatedAt", record.updatedAt)
        put("lastProgressAt", record.lastProgressAt)
        record.lastSummary?.let { put("lastSummary", it) }
        if (record.lastTurnSilent) put("lastTurnSilent", true)
        record.lastError?.let { put("lastError", it) }
        record.activeStepText?.let { put("activeStepText", it) }
        record.pendingQuestion?.let { pq ->
            put(
                "pendingQuestion",
                buildJsonObject {
                    put("requestId", pq.requestId)
                    put("toolCallId", pq.toolCallId)
                    put("questions", RelayFrames.questionsArray(pq.questions))
                },
            )
        }
        usage?.let { put("usage", encodeUsage(it)) }
    }

    private fun parseThinkingLevel(raw: String?): ThinkingLevel {
        val clean = raw?.trim().orEmpty()
        return parseThinkingLevelStrict(clean) ?: ThinkingLevel.OFF
    }

    private fun parseThinkingLevelStrict(raw: String): ThinkingLevel? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        if (clean.equals("minimal", ignoreCase = true)) return ThinkingLevel.LOW
        return ThinkingLevel.entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    private fun encodeUsage(usage: SessionUsage): JsonObject = buildJsonObject {
        put("contextTokens", usage.contextTokens)
        put("contextWindow", usage.contextWindow)
        put("inputTokens", usage.inputTokens)
        put("outputTokens", usage.outputTokens)
        put("cacheReadTokens", usage.cacheReadTokens)
        put("cacheWriteTokens", usage.cacheWriteTokens)
        put("cost", usage.cost)
    }

    private fun parseImageParts(params: JsonObject): List<ImagePart> =
        RelayFrames.parseImages(params).map { ImagePart(data = it.data, mimeType = it.mimeType) }

    private fun respondOk(id: String, result: JsonObject) {
        send(RelayFrames.routeOk(id, result))
    }

    private fun reject(frame: RelayServerFrame.Route, code: String, message: String) {
        send(RelayFrames.routeError(frame.id, code, message))
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val PRESENT_ARTIFACT_TYPES = setOf("file", "image", "video", "html")
        private const val MAX_PENDING_SUBSCRIBER_EVENTS = 512
    }
}
