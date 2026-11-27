package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.data.AGENT_DOC_MAX_BYTES
import io.github.whywood.superpi.host.core.DoctorService
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.platform.HostSecretEnv
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.WorkerNotFoundException
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.writeStringUtf8
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

private val workerStreamJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun Route.installSuperMemoryAndSettingsRoutes(
    controller: SuperPiController,
    serverScope: CoroutineScope,
) {
    // ── Super PI Orchestrator ────────────────────────────────────────────────
    get("/v1/super/state") {
        controller.superOrchestrator.ensureStarted()
        val state = controller.superOrchestrator.threadState.value
        val workers = controller.superOrchestrator.workers.value
        val approvalsByWorker = controller.toolApprovalCenter.pending.value
            .mapNotNull { approval -> approval.workerId?.let { it to approval } }
            .toMap()
        val triggerPct = controller.settingsStore.current().compactionTriggerPercent
        call.respond(
            SuperStateResponse(
                busy = state.busy,
                ready = state.ready,
                providerId = state.providerId,
                modelId = state.modelId,
                modelLabel = state.modelLabel,
                thinkingLevel = state.thinkingLevel.name,
                thinkingLevels = state.thinkingLevels.map { it.name },
                usage = state.usage?.toDto(triggerPct),
                archivedCount = state.archivedCount,
                activeToolText = state.activeToolText,
                error = state.error,
                pendingQuestion = state.pendingQuestion?.toDto(),
                messages = state.messages.map { it.toDto() },
                workers = workers.map { it.toDto(approvalsByWorker[it.workerId]) },
            ),
        )
    }

    put("/v1/super/model") {
        val req = call.receive<SetModelRequest>()
        val thinking = ThinkingLevel.entries.firstOrNull {
            it.name.equals(req.thinkingLevel.trim(), ignoreCase = true)
        } ?: ThinkingLevel.OFF
        controller.superOrchestrator.ensureStarted()
        try {
            controller.superOrchestrator.changeSuperModel(
                providerId = req.providerId.trim(),
                modelId = req.modelId.trim(),
                thinkingLevel = thinking,
            )
            call.respond(HealthResponse(ok = true))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid model"))
        } catch (err: IllegalStateException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Model not found"))
        }
    }

    post("/v1/super/compact") {
        controller.superOrchestrator.ensureStarted()
        try {
            controller.superOrchestrator.compactSuper()
            call.respond(CompactResponse(ok = true, status = "done"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                CompactResponse(ok = false, status = "failed", message = err.message ?: "Super compaction failed"),
            )
        }
    }

    post("/v1/super/reset") {
        controller.superOrchestrator.ensureStarted()
        try {
            controller.superOrchestrator.archiveAndResetSuper()
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse(err.message ?: "Failed to reset Super thread"))
        }
    }

    post("/v1/super/prompt") {
        val req = call.receive<PromptRequest>()
        val text = req.text.trim()
        val images = try {
            req.images.toImageParts()
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid image attachment"))
            return@post
        }
        if (text.isEmpty() && images.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Prompt text or image is required"))
            return@post
        }
        controller.superOrchestrator.ensureStarted()
        if (req.steer && controller.superOrchestrator.threadState.value.busy) {
            controller.superOrchestrator.steer(text, images)
        } else {
            controller.scope.launch {
                runCatching { controller.superOrchestrator.send(text, images) }
            }
        }
        call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
    }

    post("/v1/super/abort") {
        controller.superOrchestrator.abortSuper()
        call.respond(HealthResponse(ok = true))
    }

    post("/v1/super/question/{requestId}/answer") {
        val requestId = call.parameters["requestId"].orEmpty().trim()
        val req = call.receive<AnswerQuestionRequest>()
        if (requestId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing requestId"))
            return@post
        }
        controller.superOrchestrator.ensureStarted()
        controller.superOrchestrator.answerSuperQuestion(requestId, req.answers.map { it.toDomain() })
        call.respond(HealthResponse(ok = true))
    }

    post("/v1/super/question/{requestId}/dismiss") {
        val requestId = call.parameters["requestId"].orEmpty().trim()
        if (requestId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing requestId"))
            return@post
        }
        controller.superOrchestrator.ensureStarted()
        controller.superOrchestrator.cancelSuperQuestion(requestId)
        call.respond(HealthResponse(ok = true))
    }

    get("/v1/super/team") {
        controller.superOrchestrator.ensureStarted()
        controller.superOrchestrator.refreshBlueprints()
        call.respond(controller.superOrchestrator.blueprints.value.map { it.toTeamDto() })
    }

    put("/v1/super/team") {
        val req = call.receive<SetSuperTeamRequest>()
        val ref = req.botRef.trim()
        if (ref.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("botRef is required"))
            return@put
        }
        controller.superOrchestrator.ensureStarted()
        controller.superOrchestrator.setBotEnabledInSuper(ref, req.enabled)
        call.respond(controller.superOrchestrator.blueprints.value.map { it.toTeamDto() })
    }

    get("/v1/super/settings") {
        call.respond(controller.settingsStore.current().superMode.toDto())
    }

    put("/v1/super/settings") {
        val req = call.receive<UpdateSuperSettingsRequest>()
        val updated = controller.settingsStore.update { cur ->
            val sm = cur.superMode
            cur.copy(
                superMode = sm.copy(
                    maxConcurrentWorkers = req.maxConcurrentWorkers?.coerceIn(1, 10) ?: sm.maxConcurrentWorkers,
                    workerMaxSlotsPerBot = req.workerMaxSlotsPerBot?.coerceIn(1, 16) ?: sm.workerMaxSlotsPerBot,
                    workerIdleCleanupMinutes = req.workerIdleCleanupMinutes?.coerceIn(5, 1440) ?: sm.workerIdleCleanupMinutes,
                    workerStallTimeoutMinutes = req.workerStallTimeoutMinutes?.coerceIn(0, 60) ?: sm.workerStallTimeoutMinutes,
                    questionTimeoutMinutes = req.questionTimeoutMinutes?.coerceIn(0, 60) ?: sm.questionTimeoutMinutes,
                    coalesceWindowMs = req.coalesceWindowMs?.coerceIn(0L, 10_000L) ?: sm.coalesceWindowMs,
                    receiptMaxChars = req.receiptMaxChars?.coerceIn(500, 10_000) ?: sm.receiptMaxChars,
                    workerJobWake = req.workerJobWake ?: sm.workerJobWake,
                    workerCompactionOverrideEnabled = req.workerCompactionOverrideEnabled ?: sm.workerCompactionOverrideEnabled,
                    workerCompactionTriggerPercent = req.workerCompactionTriggerPercent?.coerceIn(50, 95) ?: sm.workerCompactionTriggerPercent,
                ),
            )
        }
        if (controller.isRemoteWorkerClientInitialized) {
            runCatching { controller.remoteWorkerClient.onSettingsChanged() }
        }
        if (controller.isSuperOrchestratorInitialized) {
            runCatching { controller.superOrchestrator.refreshBlueprints() }
        }
        call.respond(updated.superMode.toDto())
    }

    get("/v1/super/agents-doc") {
        val dir = controller.superRouterWorkspaceDir()
        val doc = withContext(Dispatchers.IO) { controller.botRegistry.agentDocStore.read(dir.absolutePath) }
        if (doc == null) {
            call.respond(AgentDocDto(exists = false))
        } else {
            call.respond(
                AgentDocDto(
                    exists = true,
                    fileName = doc.fileName,
                    content = doc.content,
                    isOverride = doc.isOverride,
                ),
            )
        }
    }

    put("/v1/super/agents-doc") {
        val req = call.receive<SaveAgentDocRequest>()
        if (req.content.toByteArray(Charsets.UTF_8).size > AGENT_DOC_MAX_BYTES) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("Instructions exceed maximum size of ${AGENT_DOC_MAX_BYTES / 1024} KB."),
            )
            return@put
        }
        val dir = controller.superRouterWorkspaceDir()
        val saved = withContext(Dispatchers.IO) {
            dir.mkdirs()
            controller.botRegistry.agentDocStore.save(dir.absolutePath, req.content)
            controller.botRegistry.agentDocStore.read(dir.absolutePath)
        }
        call.respond(
            AgentDocDto(
                exists = saved != null,
                fileName = saved?.fileName ?: "AGENTS.md",
                content = saved?.content ?: req.content,
                isOverride = saved?.isOverride ?: false,
            ),
        )
    }

    delete("/v1/super/agents-doc") {
        val dir = controller.superRouterWorkspaceDir()
        withContext(Dispatchers.IO) {
            controller.botRegistry.agentDocStore.delete(dir.absolutePath)
        }
        call.respond(HealthResponse(ok = true))
    }

    get("/v1/super/workers/{workerId}/stream") {
        val workerId = call.parameters["workerId"].orEmpty().trim()
        controller.superOrchestrator.ensureStarted()
        val existingWorker = controller.superOrchestrator.workers.value.firstOrNull { it.workerId == workerId }
        if (existingWorker == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Worker not found: $workerId"))
            return@get
        }
        runCatching { controller.superOrchestrator.loadWorkerSession(workerId) }
        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.header(HttpHeaders.Connection, "keep-alive")
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val outbound = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            try {
                coroutineScope {
                    val parentJob = coroutineContext[Job]
                    val shutdownWatcher = serverScope.coroutineContext[Job]?.invokeOnCompletion {
                        outbound.close()
                        parentJob?.cancel()
                    }
                    try {
                        val writerJob = launch {
                            for (frame in outbound) {
                                writeStringUtf8(frame)
                                flush()
                            }
                        }
                        val heartbeatJob = launch {
                            while (isActive) {
                                delay(15_000L)
                                outbound.trySend(": heartbeat\n\n")
                            }
                        }
                        val collectorJob = launch {
                            var lastEncoded: String? = null
                            val workerFlow = controller.superOrchestrator.workers.map { list ->
                                list.firstOrNull { it.workerId == workerId }
                            }
                            val workerApprovalFlow = controller.toolApprovalCenter.pending.map { list ->
                                list.firstOrNull { it.workerId == workerId }
                            }
                            combine(
                                controller.superOrchestrator.observeWorkerSession(workerId),
                                workerFlow,
                                workerApprovalFlow,
                            ) { snapshot, currentWorker, pendingApproval ->
                                Triple(snapshot, currentWorker, pendingApproval)
                            }.collect { (snapshot, currentWorker, pendingApproval) ->
                                if (currentWorker == null) {
                                    outbound.trySend("event: worker_deleted\ndata: {}\n\n")
                                    heartbeatJob.cancel()
                                    outbound.close()
                                    throw CancellationException("Worker deleted")
                                }
                                val triggerPct = controller.settingsStore.current().let {
                                    if (it.superMode.workerCompactionOverrideEnabled) it.superMode.workerCompactionTriggerPercent
                                    else it.compactionTriggerPercent
                                }
                                val dto = WorkerDetailDto(
                                    worker = currentWorker.toDto(pendingApproval),
                                    messages = snapshot.messages.map { it.toDto() },
                                    streaming = snapshot.streaming,
                                    activeToolText = snapshot.activeToolText,
                                    providerId = snapshot.providerId,
                                    modelId = snapshot.modelId,
                                    modelLabel = snapshot.modelLabel,
                                    thinkingLevel = snapshot.thinkingLevel.name,
                                    thinkingLevels = snapshot.thinkingLevels.map { it.name },
                                    usage = snapshot.usage?.toDto(triggerPct),
                                    loaded = snapshot.loaded,
                                    error = snapshot.error,
                                )
                                val encoded = workerStreamJson.encodeToString(WorkerDetailDto.serializer(), dto)
                                if (encoded != lastEncoded) {
                                    lastEncoded = encoded
                                    outbound.trySend("event: worker_snapshot\ndata: $encoded\n\n")
                                }
                                if (snapshot.streaming) {
                                    delay(110L)
                                }
                            }
                        }
                        writerJob.join()
                        collectorJob.cancel()
                        heartbeatJob.cancel()
                    } finally {
                        shutdownWatcher?.dispose()
                        outbound.close()
                    }
                }
            } catch (_: ClosedWriteChannelException) {
                // Normal client disconnect
            } catch (_: IOException) {
                // Normal client disconnect
            } catch (cancelled: CancellationException) {
                if (cancelled.message != "Worker deleted") throw cancelled
            }
        }
    }

    get("/v1/super/workers/{workerId}") {
        val workerId = call.parameters["workerId"].orEmpty().trim()
        controller.superOrchestrator.ensureStarted()
        val worker = controller.superOrchestrator.workers.value.firstOrNull { it.workerId == workerId }
        if (worker == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Worker not found: $workerId"))
            return@get
        }
        runCatching { controller.superOrchestrator.loadWorkerSession(workerId) }
        val snapshot = controller.superOrchestrator.observeWorkerSession(workerId).value
        val triggerPct = controller.settingsStore.current().let {
            if (it.superMode.workerCompactionOverrideEnabled) it.superMode.workerCompactionTriggerPercent
            else it.compactionTriggerPercent
        }
        val pendingApproval = controller.toolApprovalCenter.pending.value.firstOrNull { it.workerId == workerId }
        call.respond(
            WorkerDetailDto(
                worker = worker.toDto(pendingApproval),
                messages = snapshot.messages.map { it.toDto() },
                streaming = snapshot.streaming,
                activeToolText = snapshot.activeToolText,
                providerId = snapshot.providerId,
                modelId = snapshot.modelId,
                modelLabel = snapshot.modelLabel,
                thinkingLevel = snapshot.thinkingLevel.name,
                thinkingLevels = snapshot.thinkingLevels.map { it.name },
                usage = snapshot.usage?.toDto(triggerPct),
                loaded = snapshot.loaded,
                error = snapshot.error,
            ),
        )
    }

    post("/v1/super/workers/{workerId}/prompt") {
        val workerId = call.parameters["workerId"].orEmpty()
        val req = call.receive<PromptRequest>()
        val images = try {
            req.images.toImageParts()
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid image attachment"))
            return@post
        }
        if (req.text.isBlank() && images.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Message or image is required"))
            return@post
        }
        try {
            controller.superOrchestrator.sendToWorker(workerId, req.text.trim(), images)
            call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: WorkerNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worker not found"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Failed to message worker"))
        }
    }

    put("/v1/super/workers/{workerId}/model") {
        val workerId = call.parameters["workerId"].orEmpty()
        val req = call.receive<SetModelRequest>()
        val thinking = ThinkingLevel.entries.firstOrNull {
            it.name.equals(req.thinkingLevel.trim(), ignoreCase = true)
        } ?: ThinkingLevel.OFF
        try {
            controller.superOrchestrator.changeWorkerModel(
                workerId = workerId,
                providerId = req.providerId.trim(),
                modelId = req.modelId.trim(),
                thinkingLevel = thinking,
            )
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: WorkerNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worker not found"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Failed to update worker model"))
        }
    }

    post("/v1/super/workers/{workerId}/compact") {
        val workerId = call.parameters["workerId"].orEmpty()
        try {
            controller.superOrchestrator.compactWorker(workerId)
            call.respond(CompactResponse(ok = true, status = "done"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: WorkerNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worker not found"))
        } catch (err: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                CompactResponse(ok = false, status = "failed", message = err.message ?: "Worker compaction failed"),
            )
        }
    }

    post("/v1/super/workers/{workerId}/question/{requestId}/answer") {
        val workerId = call.parameters["workerId"].orEmpty()
        val requestId = call.parameters["requestId"].orEmpty()
        val req = call.receive<AnswerQuestionRequest>()
        try {
            controller.superOrchestrator.answerWorkerQuestion(workerId, requestId, req.answers.map { it.toDomain() })
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: WorkerNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worker not found"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Failed to answer worker question"))
        }
    }

    post("/v1/super/workers/{workerId}/question/{requestId}/dismiss") {
        val workerId = call.parameters["workerId"].orEmpty()
        val requestId = call.parameters["requestId"].orEmpty()
        try {
            controller.superOrchestrator.dismissWorkerQuestion(workerId, requestId)
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: WorkerNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worker not found"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Failed to dismiss worker question"))
        }
    }

    post("/v1/super/workers/{workerId}/stop") {
        val workerId = call.parameters["workerId"].orEmpty()
        try {
            controller.superOrchestrator.stopWorker(workerId)
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: WorkerNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worker not found"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(err.message ?: "Failed to stop worker"))
        }
    }

    delete("/v1/super/workers/{workerId}") {
        val workerId = call.parameters["workerId"].orEmpty()
        try {
            controller.superOrchestrator.deleteWorker(workerId)
            call.respond(HealthResponse(ok = true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: WorkerNotFoundException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worker not found"))
        } catch (err: Exception) {
            call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(err.message ?: "Failed to delete worker"))
        }
    }

    // ── Settings & Providers ─────────────────────────────────────────────────
    get("/v1/settings") {
        val s = controller.settingsStore.current()
        call.respond(
            SettingsDto(
                deviceName = s.deviceName,
                defaultProvider = s.defaultProvider,
                defaultModel = s.defaultModel,
                defaultThinkingLevel = s.defaultThinkingLevel,
                compactionTriggerPercent = s.compactionTriggerPercent,
                servePort = s.servePort,
                language = s.language,
                sendKey = s.sendKey,
                sendWhileRunning = s.sendWhileRunning,
                chatFontScale = s.chatFontScale,
                processDetail = s.processDetail,
                autoRetry = s.autoRetry,
                backgroundJobWake = s.backgroundJobWake,
                userNickname = s.userNickname,
                userAvatar = s.userAvatar,
                customSystemInstruction = s.customSystemInstruction,
                osName = controller.platform.profile.osName,
                arch = controller.platform.profile.arch,
                shellName = controller.platform.profile.shellName,
                homeDir = controller.paths.homeDir.absolutePath,
            ),
        )
    }

    patch("/v1/settings") {
        val req = call.receive<PatchSettingsRequest>()
        val before = controller.settingsStore.current()
        val updated = controller.settingsStore.update { cur ->
            val nextThinking = req.defaultThinkingLevel?.trim()?.let { raw ->
                ThinkingLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.name
            } ?: cur.defaultThinkingLevel
            cur.copy(
                deviceName = req.deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: cur.deviceName,
                defaultProvider = req.defaultProvider?.trim()?.takeIf { it.isNotEmpty() } ?: cur.defaultProvider,
                defaultModel = req.defaultModel?.trim()?.takeIf { it.isNotEmpty() } ?: cur.defaultModel,
                defaultThinkingLevel = nextThinking,
                compactionTriggerPercent = req.compactionTriggerPercent?.coerceIn(50, 95) ?: cur.compactionTriggerPercent,
                language = req.language?.trim()?.takeIf { it.isNotEmpty() } ?: cur.language,
                sendKey = req.sendKey?.trim()?.takeIf { it.isNotEmpty() } ?: cur.sendKey,
                sendWhileRunning = req.sendWhileRunning?.trim()?.takeIf { it.isNotEmpty() } ?: cur.sendWhileRunning,
                chatFontScale = req.chatFontScale?.coerceIn(85, 140) ?: cur.chatFontScale,
                processDetail = req.processDetail?.trim()?.takeIf { it.isNotEmpty() } ?: cur.processDetail,
                autoRetry = req.autoRetry ?: cur.autoRetry,
                backgroundJobWake = req.backgroundJobWake ?: cur.backgroundJobWake,
                userNickname = req.userNickname?.trim()?.take(64) ?: cur.userNickname,
                userAvatar = when {
                    req.clearUserAvatar -> ""
                    req.userAvatar != null -> req.userAvatar.trim().take(262_144)
                    else -> cur.userAvatar
                },
                customSystemInstruction = req.customSystemInstruction?.trim()?.take(4_000) ?: cur.customSystemInstruction,
            )
        }
        val needsReload =
            before.deviceName != updated.deviceName ||
                before.defaultProvider != updated.defaultProvider ||
                before.defaultModel != updated.defaultModel ||
                before.defaultThinkingLevel != updated.defaultThinkingLevel ||
                before.compactionTriggerPercent != updated.compactionTriggerPercent ||
                before.userNickname != updated.userNickname ||
                before.customSystemInstruction != updated.customSystemInstruction
        if (needsReload) {
            controller.reloadAll()
        }
        call.respond(
            SettingsDto(
                deviceName = updated.deviceName,
                defaultProvider = updated.defaultProvider,
                defaultModel = updated.defaultModel,
                defaultThinkingLevel = updated.defaultThinkingLevel,
                compactionTriggerPercent = updated.compactionTriggerPercent,
                servePort = updated.servePort,
                language = updated.language,
                sendKey = updated.sendKey,
                sendWhileRunning = updated.sendWhileRunning,
                chatFontScale = updated.chatFontScale,
                processDetail = updated.processDetail,
                autoRetry = updated.autoRetry,
                backgroundJobWake = updated.backgroundJobWake,
                userNickname = updated.userNickname,
                userAvatar = updated.userAvatar,
                customSystemInstruction = updated.customSystemInstruction,
                osName = controller.platform.profile.osName,
                arch = controller.platform.profile.arch,
                shellName = controller.platform.profile.shellName,
                homeDir = controller.paths.homeDir.absolutePath,
            ),
        )
    }

    get("/v1/providers") {
        val configuredIds = controller.configuredProviderIds()
        val customIds = controller.customProviderStore.entries.value.map { it.id }.toSet()
        val storedKeys = runCatching { controller.apiKeyStore.readAll() }.getOrDefault(emptyMap())
        val list = controller.providerCatalog.summaries.map { summary ->
            val resolvedKey = storedKeys[summary.id]
            val envVal = HostSecretEnv.envSecretFallback("api_key_", summary.id)
            val keyToMask = resolvedKey ?: envVal
            val fromEnv = !envVal.isNullOrBlank() && (resolvedKey.isNullOrBlank() || resolvedKey == envVal)
            ProviderDto(
                id = summary.id,
                name = summary.name,
                baseUrl = summary.baseUrl,
                supportsApiKey = summary.supportsApiKey,
                hasOAuth = summary.hasOAuth,
                isCustom = summary.id in customIds,
                configured = summary.id in configuredIds,
                keyHint = maskApiKey(keyToMask),
                hasEnvKey = fromEnv,
                models = controller.providerCatalog.models(summary.id).map { m ->
                    ModelOptionDto(
                        id = m.id,
                        displayName = m.displayName,
                        thinkingLevels = m.thinkingLevels.map { it.name },
                    )
                },
            )
        }
        call.respond(list)
    }

    put("/v1/providers/{providerId}/key") {
        val providerId = call.parameters["providerId"].orEmpty().trim()
        val req = call.receive<SaveProviderKeyRequest>()
        if (providerId.isEmpty() || req.apiKey.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Provider ID and API key are required"))
            return@put
        }
        controller.apiKeyStore.save(providerId, req.apiKey.trim())
        controller.reloadAll()
        controller.ensureConfiguredDefaultModel(preferredProviderId = providerId)
        call.respond(HealthResponse(ok = true))
    }

    delete("/v1/providers/{providerId}/key") {
        val providerId = call.parameters["providerId"].orEmpty().trim()
        if (providerId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Provider ID is required"))
            return@delete
        }
        controller.apiKeyStore.save(providerId, null)
        controller.reloadAll()
        controller.ensureConfiguredDefaultModel()
        call.respond(HealthResponse(ok = true))
    }

    // ── Relay & Doctor ───────────────────────────────────────────────────────
    val relayAuthMutex = kotlinx.coroutines.sync.Mutex()

    post("/v1/relay/pair") {
        val req = call.receive<RelayPairRequest>()
        val link = req.link.trim()
        if (link.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Pairing link is required"))
            return@post
        }
        val deviceName = req.deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: controller.settingsStore.current().deviceName
        if (!relayAuthMutex.tryLock()) {
            call.respond(HttpStatusCode.Conflict, ApiErrorResponse("Relay pairing is already in progress"))
            return@post
        }
        try {
            withTimeoutOrNull(1_000L) { controller.relayController.state.first { !it.signingIn } }
            controller.relayController.signIn(link, req.loginSecret?.trim()?.takeIf { it.isNotEmpty() }, deviceName)
            val settled = withTimeoutOrNull(20_000L) {
                controller.relayController.state.first { !it.signingIn && (it.error != null || it.account != null) }
            }
            val account = settled?.account
            if (settled != null && settled.error == null && account != null) {
                controller.reloadAll()
                call.respond(
                    RelayStatusDto(
                        enrolled = true,
                        state = if (controller.isRelayLiveInitialized) controller.relayLive.state.value.name else "Disabled",
                        origin = account.origin,
                        deviceId = account.deviceId,
                        deviceName = account.deviceName,
                        allowRemote = controller.relayStore.readAllowRemote(),
                    ),
                )
            } else {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse("Relay pairing failed: ${settled?.error?.name ?: "Timed out"}"),
                )
            }
        } finally {
            relayAuthMutex.unlock()
        }
    }

    post("/v1/relay/logout") {
        relayAuthMutex.lock()
        try {
            val account = controller.relayStore.read()
            if (account != null) {
                val token = runCatching { controller.relayStore.token() }.getOrNull()
                if (!token.isNullOrBlank()) {
                    runCatching { controller.relayClient.revokeDevice(account, token, account.deviceId) }
                }
                controller.relayStore.clear()
                controller.reloadAll()
            }
            call.respond(HealthResponse(ok = true))
        } finally {
            relayAuthMutex.unlock()
        }
    }

    get("/v1/doctor") {
        val probeMcp = call.request.queryParameters["probeMcp"]?.equals("true", ignoreCase = true) ?: false
        val report = DoctorService(controller, controller.platform, controller.extensionRegistry)
            .run(probeMcpServers = probeMcp)
        call.respond(report)
    }

    get("/v1/doctor/export") {
        val probeMcp = call.request.queryParameters["probeMcp"]?.equals("true", ignoreCase = true) ?: true
        val report = DoctorService(controller, controller.platform, controller.extensionRegistry)
            .run(probeMcpServers = probeMcp)
        val configured = controller.configuredProviderIds()
        val settings = controller.settingsStore.current()
        val bots = controller.botRegistry.listAllBots()
        val worktrees = controller.worktreeManager.listAll()
        val mcpCfg = runCatching { controller.mcpConfigStore.load() }.getOrNull()
        val timers = runCatching { controller.timerService.timers.value }.getOrDefault(emptyList())
        val relayEnrolled = controller.relayStore.read() != null
        val relayState = if (controller.isRelayLiveInitialized) controller.relayLive.state.value.name else "Disabled"

        val md = buildString {
            appendLine("# Super PI Diagnostics Report (Redacted)")
            appendLine()
            appendLine("- Generated At: ${java.time.Instant.now()}")
            appendLine("- Version: ${io.github.whywood.superpi.host.brand.AppBrand.VERSION}")
            appendLine("- Platform: ${controller.platform.profile.osName} (${controller.platform.profile.arch}), shell=${controller.platform.profile.shellName}")
            appendLine("- Default Model: `${settings.defaultProvider}/${settings.defaultModel}` (thinking=${settings.defaultThinkingLevel})")
            appendLine("- Bots: ${bots.size}, Active Worktrees: ${worktrees.size}, Timers: ${timers.size} (${timers.count { it.enabled }} enabled)")
            appendLine("- Relay: enrolled=$relayEnrolled, state=$relayState")
            appendLine("- Configured Providers (IDs only, secrets omitted): ${configured.sorted().joinToString(", ").ifEmpty { "(none)" }}")
            appendLine("- MCP Servers (metadata only, headers/env omitted):")
            if (mcpCfg == null || mcpCfg.servers.isEmpty()) {
                appendLine("  - (none)")
            } else {
                mcpCfg.servers.entries.sortedBy { it.key }.forEach { (name, cfg) ->
                    val kind = when (cfg) {
                        is io.github.whywood.superpi.sdk.mcp.config.McpServerConfig.Http -> "http"
                        is io.github.whywood.superpi.sdk.mcp.config.McpServerConfig.Stdio -> "stdio"
                    }
                    appendLine("  - `$name` ($kind, enabled=${cfg.enabled})")
                }
            }
            appendLine()
            appendLine("## Health Checks (Redacted)")
            val homePath = controller.paths.homeDir.absolutePath
            val userHome = System.getProperty("user.home").orEmpty()
            report.checks.forEach { row ->
                val safeSummary = if (row.category.equals("relay", ignoreCase = true)) {
                    "enrolled=$relayEnrolled, state=$relayState"
                } else {
                    redactDiagnosticsSummary(row.summary, homePath, userHome)
                }
                appendLine("- [${row.status}] **${row.category} / ${row.name}**: $safeSummary")
            }
        }
        call.response.header(
            io.ktor.http.HttpHeaders.ContentDisposition,
            io.ktor.http.ContentDisposition.Attachment.withParameter(
                io.ktor.http.ContentDisposition.Parameters.FileName,
                "superpi-diagnostics.md",
            ).toString(),
        )
        call.respondText(md, ContentType.Text.Plain)
    }
}

private fun redactDiagnosticsSummary(raw: String, homePath: String, userHome: String): String {
    var out = raw
    if (homePath.isNotBlank()) out = out.replace(homePath, "<SUPERPI_HOME>")
    if (userHome.isNotBlank()) out = out.replace(userHome, "<USER_HOME>")
    // Strip URLs (including query strings / basic auth)
    out = out.replace(Regex("""https?://[^\s"'<>]+"""), "<URL_REDACTED>")
    // Strip common bearer / API key / secret token shapes
    out = out.replace(Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{8,}"""), "$1<REDACTED>")
    out = out.replace(Regex("""\b(?:sk|oc_sk|pk|ghp|gho|xox[baprs])_[A-Za-z0-9_-]{8,}\b"""), "<KEY_REDACTED>")
    out = out.replace(Regex("""(?i)(api[_-]?key|token|secret|password|authorization)\s*[:=]\s*[^\s,;}\]]+"""), "$1=<REDACTED>")
    // Strip Unix/Windows absolute filesystem paths that might leak usernames or private directories
    out = out.replace(Regex("""/(?:Users|home|var|tmp|opt|mnt)/[^\s,;:)]+"""), "<PATH_REDACTED>")
    out = out.replace(Regex("""[A-Za-z]:\\[^\s,;:)]+"""), "<PATH_REDACTED>")
    return out.take(280)
}

internal fun maskApiKey(raw: String?): String? {
    val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        clean.length < 9 -> "••••"
        clean.length < 16 -> "••••${clean.takeLast(4)}"
        else -> "${clean.take(4)}••••${clean.takeLast(4)}"
    }
}
