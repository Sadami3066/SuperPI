package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.remote.RemoteControlResult
import io.github.whywood.superpi.remote.RemoteSessionRegistry
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.BackgroundTurnStarted
import io.github.whywood.superpi.runtime.CompactionOutcome
import io.github.whywood.superpi.runtime.GoalView
import io.github.whywood.superpi.runtime.InputQueueKind
import io.github.whywood.superpi.runtime.JobSnapshot
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.NoticeEvent
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolApprovalDecision
import io.github.whywood.superpi.runtime.UsageChanged
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
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
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

internal class SseTicketStore(
    private val ttlMs: Long = 30_000L,
) {
    private val tickets = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()

    fun issue(): String {
        pruneExpired()
        if (tickets.size >= 256) {
            tickets.entries.minByOrNull { it.value }?.let { tickets.remove(it.key) }
        }
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[ticket] = System.currentTimeMillis() + ttlMs
        return ticket
    }

    fun consume(ticket: String?): Boolean {
        if (ticket.isNullOrBlank()) return false
        pruneExpired()
        val expiresAt = tickets.remove(ticket) ?: return false
        return expiresAt >= System.currentTimeMillis()
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value < now }
    }
}

private val LOW_PRIORITY_SSE_EVENTS = setOf(
    "text_delta",
    "reasoning_delta",
    "activity_changed",
    "usage_changed",
    "worker_progress",
    "super_text_delta",
    "super_reasoning_delta",
    "super_activity_changed",
    "super_usage_changed",
)

internal fun Route.installSseEventRoute(
    controller: SuperPiController,
    json: Json,
    serverScope: CoroutineScope,
) {
    get {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.header(HttpHeaders.Connection, "keep-alive")
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val controlOutbound = Channel<String>(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
            val deltaOutbound = Channel<String>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            fun closeChannels() {
                controlOutbound.close()
                deltaOutbound.close()
            }
            suspend fun enqueue(eventName: String, payload: SseEventPayload) {
                val encoded = json.encodeToString(SseEventPayload.serializer(), payload)
                val frame = "event: $eventName\ndata: $encoded\n\n"
                if (eventName in LOW_PRIORITY_SSE_EVENTS) {
                    deltaOutbound.trySend(frame)
                } else {
                    controlOutbound.send(frame)
                }
            }
            try {
                coroutineScope {
                    val parentJob = coroutineContext[Job]
                    val shutdownWatcher = serverScope.coroutineContext[Job]?.invokeOnCompletion {
                        closeChannels()
                        parentJob?.cancel()
                    }
                    val writerJob = launch {
                        while (true) {
                            val next = controlOutbound.tryReceive().getOrNull()
                                ?: select<String?> {
                                    controlOutbound.onReceiveCatching { it.getOrNull() }
                                    deltaOutbound.onReceiveCatching { it.getOrNull() }
                                }
                                ?: break
                            writeStringUtf8(next)
                            flush()
                        }
                    }
                    val runtimeJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.runtimeHost.events.collect { sessionEvt ->
                            val triggerPct = controller.settingsStore.current().compactionTriggerPercent
                            val mapped = mapRuntimeEvent(sessionEvt.sessionId, sessionEvt.event, triggerPct)
                            if (mapped != null) {
                                enqueue(mapped.type, mapped)
                            }
                        }
                    }
                    val passiveRemoteJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.passiveRemoteEventsFlow.collect { sessionEvt ->
                            val triggerPct = controller.settingsStore.current().compactionTriggerPercent
                            val mapped = mapRuntimeEvent(sessionEvt.sessionId, sessionEvt.event, triggerPct)
                            if (mapped != null) {
                                enqueue(mapped.type, mapped)
                            }
                        }
                    }
                    val superThreadJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.superThreadEventsFlow.collect { evt ->
                            val triggerPct = controller.settingsStore.current().compactionTriggerPercent
                            val mapped = mapRuntimeEvent(
                                SuperRuntime.SUPER_THREAD_SESSION_ID,
                                evt,
                                triggerPct,
                                prefix = "super_",
                            )
                            if (mapped != null) {
                                enqueue(mapped.type, mapped)
                            }
                        }
                    }
                    val superQuestionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.superQuestionChangedFlow.collect { tag ->
                            enqueue(
                                "questions_changed",
                                SseEventPayload(type = "questions_changed", status = "super:$tag"),
                            )
                        }
                    }
                    val workerProgressJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.workerProgressFlow.collect { evt ->
                            val mapped = mapWorkerProgressEvent(evt)
                            enqueue(mapped.type, mapped)
                        }
                    }
                    val questionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.questionCenter.pending.drop(1).collect { list ->
                            enqueue(
                                "questions_changed",
                                SseEventPayload(
                                    type = "questions_changed",
                                    status = list.size.toString(),
                                ),
                            )
                        }
                    }
                    val approvalJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.toolApprovalCenter.pending.drop(1).collect { list ->
                            enqueue(
                                "approvals_changed",
                                SseEventPayload(
                                    type = "approvals_changed",
                                    status = list.size.toString(),
                                ),
                            )
                        }
                    }
                    val relayJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.relayDevicesChangedFlow.collect { tag ->
                            enqueue(
                                "devices_changed",
                                SseEventPayload(
                                    type = "devices_changed",
                                    status = tag,
                                ),
                            )
                        }
                    }
                    val goalJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.goalService.goals.drop(1).collect { goalsMap ->
                            enqueue(
                                "goal_changed",
                                SseEventPayload(
                                    type = "goal_changed",
                                    status = goalsMap.size.toString(),
                                ),
                            )
                        }
                    }
                    val jobsJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.jobRegistry.jobs.drop(1).collect { list ->
                            enqueue(
                                "jobs_changed",
                                SseEventPayload(
                                    type = "jobs_changed",
                                    status = list.size.toString(),
                                ),
                            )
                        }
                    }
                    val inboxJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        controller.inboxStore.items.drop(1).collect { list ->
                            enqueue(
                                "inbox_changed",
                                SseEventPayload(
                                    type = "inbox_changed",
                                    status = list.count { it.status == "unread" }.toString(),
                                ),
                            )
                        }
                    }
                    val heartbeatJob = launch {
                        while (isActive) {
                            delay(15_000L)
                            deltaOutbound.trySend(": heartbeat\n\n")
                        }
                    }
                    // Emit ready ONLY after all collectors are subscribed.
                    enqueue("ready", SseEventPayload(type = "ready"))
                    try {
                        writerJob.join()
                    } finally {
                        shutdownWatcher?.dispose()
                        closeChannels()
                        heartbeatJob.cancel()
                        runtimeJob.cancel()
                        passiveRemoteJob.cancel()
                        superThreadJob.cancel()
                        superQuestionJob.cancel()
                        workerProgressJob.cancel()
                        questionJob.cancel()
                        approvalJob.cancel()
                        relayJob.cancel()
                        goalJob.cancel()
                        jobsJob.cancel()
                        inboxJob.cancel()
                        writerJob.cancel()
                    }
                }
            } catch (_: ClosedWriteChannelException) {
                // Normal client disconnect
            } catch (_: IOException) {
                // Normal client disconnect
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }
}

internal fun Route.installSessionRoutes(
    controller: SuperPiController,
    sseTickets: SseTicketStore,
) {
    post("/v1/auth/sse-ticket") {
        val ticket = sseTickets.issue()
        call.respond(SseTicketResponse(ticket = ticket, expiresInSeconds = 30))
    }

    get("/v1/bots/{id}/sessions") {
        val botId = call.parameters["id"].orEmpty()
        val bot = findLocalOrRemoteBot(controller, botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        if (bot.remote != null) {
            call.respond(controller.listRemoteBotSessions(bot))
            return@get
        }
        val busyIds = controller.runtimeHost.busySessionIds.value
        val botWorktrees = controller.worktreeManager.listForBot(bot.id)
        val wtBySessionId = botWorktrees.mapNotNull { wt -> wt.sessionId?.let { it to wt } }.toMap()
        val wtByPath = botWorktrees.associateBy { controller.paths.normalizePath(it.worktreePath) }
        val baseSessions = controller.runtimeHost.sessions(bot.id)
        val seenIds = baseSessions.mapTo(HashSet()) { it.id }
        val wtExtraSessions = if (botWorktrees.isNotEmpty()) {
            controller.runtimeHost.allSessions().filter { s ->
                s.id !in seenIds &&
                    (s.id in wtBySessionId || runCatching { controller.paths.normalizePath(s.cwd) }.getOrNull() in wtByPath)
            }
        } else {
            emptyList()
        }
        val list = (baseSessions + wtExtraSessions)
            .sortedByDescending { it.updatedAt }
            .map { s ->
                val wt = wtBySessionId[s.id]
                    ?: runCatching { controller.paths.normalizePath(s.cwd) }.getOrNull()?.let { wtByPath[it] }
                SessionSummaryDto(
                    id = s.id,
                    title = s.title,
                    updatedAt = s.updatedAt,
                    filePath = s.filePath,
                    cwd = s.cwd.ifBlank { bot.cwd },
                    pinned = s.pinned,
                    archived = s.archived,
                    memoryExcluded = s.memoryExcluded,
                    busy = s.id in busyIds,
                    worktreeId = wt?.id,
                    worktreeBranch = wt?.branch,
                )
            }
        call.respond(list)
    }

    post("/v1/bots/{id}/sessions") {
        val botId = call.parameters["id"].orEmpty()
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            val opened = controller.runtimeHost.newSession(botId)
            call.respond(
                HttpStatusCode.Created,
                CreateSessionResponse(
                    sessionId = opened.sessionId,
                    filePath = opened.filePath,
                    cwd = opened.cwd,
                ),
            )
            return@post
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        val req = runCatching { call.receive<CreateSessionRequest>() }.getOrNull()
        controller.ensureConfiguredDefaultModel()
        if (req?.useWorktree == true) {
            var createdWtId: String? = null
            try {
                val wt = controller.worktreeManager.create(
                    botId = bot.id,
                    repoDir = File(bot.cwd),
                    label = req.label?.trim()?.takeIf { it.isNotEmpty() } ?: "${bot.name}-chat",
                )
                createdWtId = wt.id
                val opened = controller.runtimeHost.newSessionInDirectory(wt.worktreePath)
                controller.worktreeManager.bindSession(wt.id, opened.sessionId)
                call.respond(
                    HttpStatusCode.Created,
                    CreateSessionResponse(
                        sessionId = opened.sessionId,
                        filePath = opened.filePath,
                        cwd = opened.cwd,
                        worktreeId = wt.id,
                        worktreeBranch = wt.branch,
                    ),
                )
                return@post
            } catch (err: Exception) {
                if (createdWtId != null) {
                    runCatching { controller.worktreeManager.remove(createdWtId, deleteBranch = true) }
                }
                if (err is CancellationException) throw err
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse(err.message ?: "Failed to create worktree session"),
                )
                return@post
            }
        }
        val opened = controller.runtimeHost.newSessionInDirectory(bot.cwd)
        call.respond(
            HttpStatusCode.Created,
            CreateSessionResponse(
                sessionId = opened.sessionId,
                filePath = opened.filePath,
                cwd = opened.cwd,
            ),
        )
    }

    get("/v1/sessions/archived") {
        val busyIds = controller.runtimeHost.busySessionIds.value
        val archived = controller.runtimeHost.archivedSessions()
            .filterNot { controller.isRemoteCwd(it.cwd) }
            .map { s ->
                SessionSummaryDto(
                    id = s.id,
                    title = s.title,
                    updatedAt = s.updatedAt,
                    filePath = s.filePath,
                    cwd = s.cwd,
                    pinned = s.pinned,
                    archived = true,
                    memoryExcluded = s.memoryExcluded,
                    busy = s.id in busyIds,
                )
            }
        call.respond(archived)
    }

    get("/v1/sessions/{sessionId}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        if (sessionId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing sessionId"))
            return@get
        }
        if (controller.syncRemoteSessionHistoryIfNeeded(sessionId) == SuperPiController.RemoteSessionSyncResult.Deleted) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Remote session was deleted: $sessionId"))
            return@get
        }
        val opened = ensureSessionOpen(controller, sessionId)
        if (!opened) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@get
        }
        val history = runCatching { controller.runtimeHost.historySnapshot(sessionId) }.getOrDefault(emptyList())
        val limitParam = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500)
        val beforeParam = call.request.queryParameters["before"]?.toIntOrNull()
        val total = history.size
        val sliceEnd = if (limitParam == null && beforeParam == null) total else (beforeParam ?: total).coerceIn(0, total)
        val sliceStart = if (limitParam == null && beforeParam == null) {
            0
        } else {
            (sliceEnd - (limitParam ?: 40)).coerceAtLeast(0)
        }
        val page = history.subList(sliceStart, sliceEnd)
        val userTurnOffset = history.subList(0, sliceStart).count {
            it.role == AssistantRole.USER && it.summary == null
        }
        val model = runCatching { controller.runtimeHost.sessionModel(sessionId) }.getOrNull()
        val usage = runCatching { controller.runtimeHost.usage(sessionId) }.getOrNull()
        val title = runCatching { controller.runtimeHost.sessionTitle(sessionId) }.getOrNull()
        val cwd = runCatching { controller.runtimeHost.sessionCwd(sessionId) }.getOrNull()
        val triggerPct = controller.settingsStore.current().compactionTriggerPercent
        val queued = runCatching { controller.runtimeHost.queuedInputs(sessionId).map { it.toDto() } }
            .getOrDefault(emptyList())
        val pendingQuestion = controller.questionCenter.pending.value
            .firstOrNull { it.sessionId == sessionId }
            ?.toDto()
        val pendingApprovals = controller.toolApprovalCenter.pending.value
            .filter { it.sessionId == sessionId }
            .map { it.toDto() }
        val boundWorktree = controller.worktreeManager.findBySessionId(sessionId)
        call.respond(
            SessionDetailDto(
                sessionId = sessionId,
                title = title,
                cwd = cwd,
                busy = sessionId in controller.runtimeHost.busySessionIds.value,
                model = model?.let {
                    SessionModelDto(it.providerId, it.modelId, it.thinkingLevel.name)
                },
                usage = usage?.toDto(triggerPct),
                messages = page.map { it.toDto() },
                totalMessages = total,
                hasMore = sliceStart > 0,
                nextBefore = if (sliceStart > 0) sliceStart else null,
                userTurnOffset = userTurnOffset,
                queuedInputs = queued,
                pendingQuestion = pendingQuestion,
                pendingApprovals = pendingApprovals,
                worktreeId = boundWorktree?.id,
                worktreeBranch = boundWorktree?.branch,
                worktreeBaseBranch = boundWorktree?.baseBranch,
            ),
        )
    }

    patch("/v1/sessions/{sessionId}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val req = call.receive<UpdateSessionRequest>()
        val remoteRef = controller.resolveRemoteMirrorRef(sessionId)
        val remote = remoteRef?.workspace?.remote
        req.title?.trim()?.takeIf { it.isNotEmpty() }?.let { newTitle ->
            if (remote != null) {
                runCatching {
                    controller.remoteBotAdminClient.renameSession(
                        remote.deviceId,
                        remote.botId,
                        remoteRef.peerSessionId,
                        newTitle,
                    )
                }
            }
            controller.runtimeHost.renameSession(sessionId, newTitle)
        }
        if (remote != null && (req.pinned != null || req.archived != null)) {
            runCatching {
                controller.remoteBotAdminClient.updateSessionMeta(
                    deviceId = remote.deviceId,
                    botId = remote.botId,
                    sessionId = remoteRef.peerSessionId,
                    pinned = req.pinned,
                    archived = req.archived,
                )
            }
        }
        req.pinned?.let { controller.runtimeHost.setSessionPinned(sessionId, it) }
        req.archived?.let { archived ->
            if (remote != null && archived) {
                controller.forgetRemoteMirrorRef(sessionId)
                controller.runtimeHost.deleteSession(sessionId)
            } else {
                controller.runtimeHost.setSessionArchived(sessionId, archived)
            }
        }
        req.memoryExcluded?.let { controller.runtimeHost.setSessionMemoryExcluded(sessionId, it) }
        call.respond(HealthResponse(ok = true))
    }

    delete("/v1/sessions/{sessionId}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val remoteRef = controller.resolveRemoteMirrorRef(sessionId)
        val remote = remoteRef?.workspace?.remote
        if (remote != null) {
            runCatching {
                controller.remoteBotAdminClient.deleteSession(
                    remote.deviceId,
                    remote.botId,
                    remoteRef.peerSessionId,
                )
            }
        }
        controller.forgetRemoteMirrorRef(sessionId)
        controller.autoRetryController.cancel(sessionId)
        controller.toolApprovalCenter.cancelForSession(sessionId)
        controller.toolApprovalCenter.clearSessionGrants(sessionId)
        controller.runtimeHost.deleteSession(sessionId)
        controller.scope.launch { runCatching { controller.checkpointStore.deleteSession(sessionId) } }
        call.respond(HealthResponse(ok = true))
    }

    get("/v1/sessions/{sessionId}/export") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        if (sessionId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing sessionId"))
            return@get
        }
        val summary = controller.runtimeHost.allSessions().firstOrNull { it.id == sessionId }
            ?: controller.runtimeHost.archivedSessions().firstOrNull { it.id == sessionId }
        if (summary == null || summary.filePath.isBlank()) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session file not found: $sessionId"))
            return@get
        }
        val file = File(summary.filePath)
        val homeReal = runCatching { controller.paths.homeDir.toPath().toRealPath() }.getOrNull()
        val fileReal = runCatching { file.toPath().toRealPath() }.getOrNull()
        if (homeReal == null || fileReal == null || !fileReal.startsWith(homeReal) || !file.isFile) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session file is not exportable locally"))
            return@get
        }
        val content = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
        val safeName = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                "session-$safeName.jsonl",
            ).toString(),
        )
        call.respondText(content, ContentType.Text.Plain)
    }

    post("/v1/sessions/{sessionId}/prompt") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val req = call.receive<PromptRequest>()
        val rawText = req.text.trim()
        val images = try {
            req.images.toImageParts()
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid image attachment"))
            return@post
        }
        if (rawText.isEmpty() && images.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Prompt text or image is required"))
            return@post
        }
        if (!ensureSessionOpen(controller, sessionId)) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@post
        }
        controller.autoRetryController.cancel(sessionId)
        val cwd = controller.runtimeHost.sessionCwd(sessionId).orEmpty()
        val expanded = if (rawText.isNotEmpty()) controller.expandPromptCommand(cwd, rawText) else ""
        if (req.steer && sessionId in controller.runtimeHost.busySessionIds.value) {
            controller.scope.launch {
                runCatching { controller.runtimeHost.steer(sessionId, expanded, images) }
                    .onFailure { err ->
                        if (err !is CancellationException) {
                            controller.emitSessionError(sessionId, err.message ?: "Failed to steer turn")
                        }
                    }
            }
        } else {
            if (sessionId in controller.runtimeHost.busySessionIds.value) {
                call.respond(HttpStatusCode.Conflict, ApiErrorResponse("Agent session is busy"))
                return@post
            }
            val preHistory = runCatching { controller.runtimeHost.historySnapshot(sessionId) }.getOrDefault(emptyList())
            val userTurnIndex = preHistory.count { it.role.name.equals("USER", ignoreCase = true) }
            val preMessageCount = preHistory.size
            controller.scope.launch {
                if (cwd.isNotBlank() && !controller.isRemoteCwd(cwd)) {
                    withTimeoutOrNull(4_000L) {
                        try {
                            controller.checkpointStore.capture(
                                sessionId = sessionId,
                                workspaceDir = File(cwd),
                                turnIndex = userTurnIndex,
                                label = rawText.take(60),
                                messageCount = preMessageCount,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // Best-effort checkpoint capture
                        }
                    }
                }
                try {
                    controller.runtimeHost.send(sessionId, expanded, images)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Throwable) {
                    controller.emitSessionError(sessionId, err.message ?: "Failed to send message")
                }
            }
        }
        call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
    }

    post("/v1/sessions/{sessionId}/steer") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val req = call.receive<PromptRequest>()
        val images = try {
            req.images.toImageParts()
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid image attachment"))
            return@post
        }
        if (req.text.isBlank() && images.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Text or image is required"))
            return@post
        }
        if (!ensureSessionOpen(controller, sessionId)) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@post
        }
        controller.scope.launch {
            runCatching { controller.runtimeHost.steer(sessionId, req.text.trim(), images) }
        }
        call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
    }

    post("/v1/sessions/{sessionId}/followup") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val req = call.receive<PromptRequest>()
        val images = try {
            req.images.toImageParts()
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid image attachment"))
            return@post
        }
        if (req.text.isBlank() && images.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Text or image is required"))
            return@post
        }
        if (!ensureSessionOpen(controller, sessionId)) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@post
        }
        controller.scope.launch {
            runCatching { controller.runtimeHost.followUp(sessionId, req.text.trim(), images) }
        }
        call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
    }

    delete("/v1/sessions/{sessionId}/queue") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val req = runCatching { call.receive<RemoveQueueItemRequest>() }.getOrNull()
        if (req == null || req.clearAll || req.text.isNullOrBlank()) {
            controller.runtimeHost.clearQueuedInputs(sessionId)
        } else {
            val kind = when (req.kind?.trim()?.lowercase()) {
                "steer" -> InputQueueKind.STEER
                else -> InputQueueKind.FOLLOW_UP
            }
            controller.runtimeHost.removeQueuedInput(sessionId, kind, req.text)
        }
        call.respond(controller.runtimeHost.queuedInputs(sessionId).map { it.toDto() })
    }

    post("/v1/sessions/{sessionId}/queue/promote") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val req = call.receive<PromoteQueueItemRequest>()
        val text = req.text.trim()
        if (text.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Text is required"))
            return@post
        }
        if (!ensureSessionOpen(controller, sessionId)) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@post
        }
        val removed = controller.runtimeHost.removeQueuedInput(sessionId, InputQueueKind.FOLLOW_UP, req.text)
            || controller.runtimeHost.removeQueuedInput(sessionId, InputQueueKind.FOLLOW_UP, text)
        if (removed) {
            controller.scope.launch {
                runCatching { controller.runtimeHost.steer(sessionId, text, emptyList()) }
            }
        }
        call.respond(controller.runtimeHost.queuedInputs(sessionId).map { it.toDto() })
    }

    post("/v1/sessions/{sessionId}/retry") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        if (!ensureSessionOpen(controller, sessionId)) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@post
        }
        controller.scope.launch {
            runCatching { controller.runtimeHost.retryLastTurn(sessionId) }
                .onFailure { err ->
                    if (err !is CancellationException) {
                        controller.emitSessionError(sessionId, err.message ?: "Failed to retry turn")
                    }
                }
        }
        call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
    }

    post("/v1/sessions/{sessionId}/abort") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        controller.autoRetryController.cancel(sessionId)
        controller.toolApprovalCenter.cancelForSession(sessionId)
        controller.runtimeHost.stop(sessionId)
        call.respond(HealthResponse(ok = true))
    }

    // ── Tool Approvals ───────────────────────────────────────────────────────
    get("/v1/approvals") {
        val sessionFilter = call.request.queryParameters["sessionId"]?.trim()?.takeIf { it.isNotEmpty() }
        val workerFilter = call.request.queryParameters["workerId"]?.trim()?.takeIf { it.isNotEmpty() }
        val list = controller.toolApprovalCenter.pending.value.filter { item ->
            (sessionFilter == null || item.sessionId == sessionFilter) &&
                (workerFilter == null || item.workerId == workerFilter)
        }
        call.respond(list.map { it.toDto() })
    }

    post("/v1/approvals/{approvalId}/resolve") {
        val approvalId = call.parameters["approvalId"].orEmpty().trim()
        if (approvalId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing approvalId"))
            return@post
        }
        val req = call.receive<ResolveToolApprovalRequest>()
        val decision = ToolApprovalDecision.parseWireOrNull(req.decision)
        if (decision == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid decision: ${req.decision}"))
            return@post
        }
        val resolved = controller.toolApprovalCenter.resolve(approvalId, decision, req.note)
        if (!resolved) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Pending tool approval not found: $approvalId"))
            return@post
        }
        call.respond(HealthResponse(ok = true))
    }

    // ── Goal Mode ────────────────────────────────────────────────────────────
    get("/v1/sessions/{sessionId}/goal") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val view = controller.goalService.goals.value[sessionId]
        call.respond(GoalResponse(goal = view?.toDto()))
    }

    post("/v1/sessions/{sessionId}/goal") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val req = call.receive<CreateGoalRequest>()
        val objective = req.objective.trim()
        if (sessionId.isEmpty() || objective.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Goal objective is required"))
            return@post
        }
        val created = controller.goalService.create(sessionId, objective, req.maxRounds)
        if (created == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid goal objective"))
            return@post
        }
        call.respond(HttpStatusCode.Created, GoalResponse(goal = created.toDto()))
    }

    post("/v1/sessions/{sessionId}/goal/pause") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val updated = controller.goalService.pause(sessionId)
        call.respond(GoalResponse(goal = updated?.toDto()))
    }

    post("/v1/sessions/{sessionId}/goal/resume") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val updated = controller.goalService.resume(sessionId)
        call.respond(GoalResponse(goal = updated?.toDto()))
    }

    delete("/v1/sessions/{sessionId}/goal") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        controller.goalService.clear(sessionId)
        call.respond(GoalResponse(goal = null))
    }

    // ── Background Jobs ──────────────────────────────────────────────────────
    get("/v1/sessions/{sessionId}/jobs") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val items = controller.jobRegistry.jobs.value
            .filter { it.sessionId == sessionId }
            .map { it.toDto() }
        call.respond(items)
    }

    get("/v1/sessions/{sessionId}/jobs/{jobId}/output") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val jobId = call.parameters["jobId"].orEmpty().trim()
        val exists = controller.jobRegistry.jobs.value.any { it.id == jobId && it.sessionId == sessionId }
        if (!exists) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Job not found: $jobId"))
            return@get
        }
        try {
            val read = controller.jobRegistry.read(jobId)
            call.respond(
                JobOutputDto(
                    jobId = jobId,
                    output = read.output,
                    status = read.status.name.lowercase(),
                    exitCode = read.exitCode,
                    truncated = read.dropped,
                ),
            )
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Job not found"))
        }
    }

    post("/v1/sessions/{sessionId}/jobs/{jobId}/kill") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val jobId = call.parameters["jobId"].orEmpty().trim()
        val exists = controller.jobRegistry.jobs.value.any { it.id == jobId && it.sessionId == sessionId }
        if (!exists) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Job not found: $jobId"))
            return@post
        }
        val ok = controller.jobRegistry.kill(jobId, "Stopped by user")
        call.respond(HealthResponse(ok = ok))
    }

    delete("/v1/sessions/{sessionId}/jobs/settled") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val settledIds = controller.jobRegistry.jobs.value
            .filter { it.sessionId == sessionId && it.status != JobStatus.RUNNING }
            .map { it.id }
        settledIds.forEach { id -> controller.jobRegistry.remove(id) }
        call.respond(HealthResponse(ok = true))
    }

    post("/v1/sessions/{sessionId}/compact") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        if (!ensureSessionOpen(controller, sessionId)) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@post
        }
        when (val outcome = controller.runtimeHost.compact(sessionId)) {
            is CompactionOutcome.Done -> call.respond(
                CompactResponse(
                    ok = true,
                    status = "done",
                    tokensBefore = outcome.tokensBefore,
                    tokensAfter = outcome.tokensAfter,
                ),
            )
            CompactionOutcome.Empty -> call.respond(
                CompactResponse(ok = false, status = "empty", message = "Nothing to compact yet"),
            )
            CompactionOutcome.Busy -> call.respond(
                HttpStatusCode.Conflict,
                CompactResponse(ok = false, status = "busy", message = "Session is currently running"),
            )
            CompactionOutcome.Unavailable -> call.respond(
                HttpStatusCode.BadRequest,
                CompactResponse(ok = false, status = "unavailable", message = "Compaction unavailable for this session"),
            )
            is CompactionOutcome.Failed -> call.respond(
                HttpStatusCode.InternalServerError,
                CompactResponse(ok = false, status = "failed", message = outcome.message ?: "Compaction failed"),
            )
        }
    }

    put("/v1/sessions/{sessionId}/model") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val req = call.receive<SetModelRequest>()
        val providerId = req.providerId.trim()
        val modelId = req.modelId.trim()
        val thinking = ThinkingLevel.entries.firstOrNull {
            it.name.equals(req.thinkingLevel.trim(), ignoreCase = true)
        } ?: ThinkingLevel.OFF
        if (!ensureSessionOpen(controller, sessionId)) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@put
        }
        val cwd = controller.runtimeHost.sessionCwd(sessionId).orEmpty()
        if (cwd.isNotEmpty() && controller.isRemoteCwd(cwd)) {
            val target = controller.resolveRemoteTargetForCwd(cwd)
            if (target == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ApiErrorResponse("Remote bot is currently offline"))
                return@put
            }
            val peerSessionId = RemoteSessionRegistry.peerSessionId(sessionId)
            if (peerSessionId != null) {
                when (
                    val res = controller.remoteBotControlClient.setModel(
                        deviceId = target.deviceId,
                        botId = target.botId,
                        providerId = providerId,
                        modelId = modelId,
                        thinkingLevel = thinking,
                        sessionId = peerSessionId,
                    )
                ) {
                    is RemoteControlResult.Ok -> call.respond(HealthResponse(ok = true))
                    is RemoteControlResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
                }
            } else {
                RemoteSessionRegistry.setPendingModel(sessionId, providerId, modelId, thinking)
                call.respond(HealthResponse(ok = true))
            }
            return@put
        }
        try {
            controller.runtimeHost.setSessionModel(
                sessionId = sessionId,
                providerId = providerId,
                modelId = modelId,
                thinkingLevel = thinking,
            )
            val configured = controller.configuredProviderIds()
            val curSettings = controller.settingsStore.current()
            if (providerId in configured && curSettings.defaultProvider !in configured) {
                controller.settingsStore.update { cur ->
                    cur.copy(
                        defaultProvider = providerId,
                        defaultModel = modelId,
                        defaultThinkingLevel = thinking.name,
                    )
                }
            }
            call.respond(HealthResponse(ok = true))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid model"))
        } catch (err: IllegalStateException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Model not found"))
        }
    }
}

private fun GoalView.toDto(): GoalDto = GoalDto(
    id = goal.id,
    objective = goal.objective,
    phase = goal.phase.name.lowercase(),
    roundsStarted = goal.roundsStarted,
    maxRounds = goal.maxRounds,
    blockedReason = goal.blockedReason,
    armed = armed,
    createdAt = goal.createdAt,
    updatedAt = goal.updatedAt,
)

private fun JobSnapshot.toDto(): JobSnapshotDto = JobSnapshotDto(
    id = id,
    sessionId = sessionId,
    label = label,
    command = command,
    status = status.name.lowercase(),
    startedAt = startedAt,
    finishedAt = finishedAt,
    exitCode = exitCode,
    detail = detail,
    outputBytes = outputBytes,
)

private suspend fun findLocalOrRemoteBot(
    controller: SuperPiController,
    botId: String,
): io.github.whywood.superpi.runtime.RuntimeWorkspace? {
    controller.botRegistry.findBotById(botId)?.let { return it }
    return controller.listMergedBots().firstOrNull { it.id == botId }
}

private suspend fun ensureSessionOpen(controller: SuperPiController, sessionId: String): Boolean {
    if (runCatching { controller.runtimeHost.sessionModel(sessionId) }.getOrNull() != null) {
        return true
    }
    val bots = controller.listMergedBots()
    for (bot in bots) {
        val summary = runCatching { controller.runtimeHost.sessions(bot.id) }
            .getOrDefault(emptyList())
            .firstOrNull { it.id == sessionId }
        if (summary != null) {
            return runCatching {
                controller.runtimeHost.openSession(summary.filePath, summary.cwd.ifBlank { bot.cwd })
            }.isSuccess
        }
    }
    val archived = runCatching { controller.runtimeHost.archivedSessions() }
        .getOrDefault(emptyList())
        .firstOrNull { it.id == sessionId }
    if (archived != null) {
        return runCatching {
            controller.runtimeHost.openSession(archived.filePath, archived.cwd)
        }.isSuccess
    }
    return false
}

private fun mapWorkerProgressEvent(event: WorkerProgressEvent): SseEventPayload = when (event) {
    is WorkerProgressEvent.StatusChanged -> SseEventPayload(
        type = "worker_progress",
        workerId = event.workerId,
        status = event.status.name.lowercase(),
        text = event.activeStepText,
    )
    is WorkerProgressEvent.StepUpdated -> SseEventPayload(
        type = "worker_progress",
        workerId = event.workerId,
        callId = event.step.callId,
        toolName = event.step.toolName,
        status = event.step.status.name.lowercase(),
        output = event.step.outputPreview,
        isError = event.step.isError,
    )
    is WorkerProgressEvent.ApprovalRequested -> SseEventPayload(
        type = "worker_approval",
        workerId = event.workerId,
        sessionId = event.question.sessionId,
        status = "requested",
        text = event.question.requestId,
    )
    is WorkerProgressEvent.ApprovalResolved -> SseEventPayload(
        type = "worker_approval",
        workerId = event.workerId,
        status = "resolved",
        text = event.requestId,
    )
}

private fun mapRuntimeEvent(
    sessionId: String,
    event: RuntimeEvent,
    compactionTriggerPercent: Int = 80,
    prefix: String = "",
): SseEventPayload? = when (event) {
    is RuntimeEvent.RunStarted -> SseEventPayload(
        type = "${prefix}run_started",
        sessionId = sessionId,
        runId = event.runId,
        text = event.prompt,
    )
    is RuntimeEvent.ActivityChanged -> SseEventPayload(
        type = "${prefix}activity_changed",
        sessionId = sessionId,
        runId = event.runId,
        activity = when (val a = event.activity) {
            null -> null
            RuntimeActivity.Thinking -> "thinking"
            is RuntimeActivity.Retrying -> "retrying:${a.attempt}"
            RuntimeActivity.Compacting -> "compacting"
            RuntimeActivity.CompactionFailed -> "compaction_failed"
            is RuntimeActivity.RunningTool -> "tool:${a.name}"
        },
    )
    is RuntimeEvent.TextDelta -> SseEventPayload(
        type = "${prefix}text_delta",
        sessionId = sessionId,
        runId = event.runId,
        text = event.text,
    )
    is RuntimeEvent.ReasoningDelta -> SseEventPayload(
        type = "${prefix}reasoning_delta",
        sessionId = sessionId,
        runId = event.runId,
        text = event.text,
    )
    is RuntimeEvent.ToolCallStarted -> SseEventPayload(
        type = "${prefix}tool_call_started",
        sessionId = sessionId,
        runId = event.runId,
        callId = event.callId,
        toolName = event.name,
    )
    is RuntimeEvent.ToolStarted -> SseEventPayload(
        type = "${prefix}tool_started",
        sessionId = sessionId,
        runId = event.runId,
        callId = event.callId,
        toolName = event.name,
        arguments = event.arguments,
    )
    is RuntimeEvent.ToolFinished -> SseEventPayload(
        type = "${prefix}tool_finished",
        sessionId = sessionId,
        runId = event.runId,
        callId = event.callId,
        toolName = event.name,
        output = event.result,
        isError = event.isError,
        durationMs = event.durationMs,
    )
    is RuntimeEvent.RunFinished -> SseEventPayload(
        type = "${prefix}run_finished",
        sessionId = sessionId,
        runId = event.runId,
        text = event.text,
        status = event.status.name.lowercase(),
    )
    is RuntimeEvent.RunFailed -> SseEventPayload(
        type = "${prefix}run_failed",
        sessionId = sessionId,
        runId = event.runId,
        text = event.message,
    )
    is RuntimeEvent.PluginCustomMessage -> SseEventPayload(
        type = "${prefix}custom_card",
        sessionId = sessionId,
        runId = event.runId,
        customType = event.customType,
        payload = event.payload,
        text = event.text,
    )
    is RuntimeEvent.QueueChanged -> SseEventPayload(
        type = "${prefix}queue_changed",
        sessionId = sessionId,
        runId = event.runId,
        steering = event.steering,
        followUp = event.followUp,
    )
    is RuntimeEvent.QueuedInputsDropped -> SseEventPayload(
        type = "${prefix}queue_dropped",
        sessionId = sessionId,
        runId = event.runId,
        followUp = event.texts,
    )
    is RuntimeEvent.UserInputAdmitted -> SseEventPayload(
        type = "${prefix}user_input_admitted",
        sessionId = sessionId,
        runId = event.runId,
        text = event.text,
    )
    is RuntimeEvent.TurnInjected -> SseEventPayload(
        type = "${prefix}turn_injected",
        sessionId = sessionId,
        runId = event.runId,
        text = event.text,
    )
    is RuntimeEvent.SessionRemoved -> SseEventPayload(
        type = "${prefix}session_removed",
        sessionId = sessionId,
    )
    is RuntimeEvent.SessionArchived -> SseEventPayload(
        type = "${prefix}session_archived",
        sessionId = sessionId,
        archived = event.archived,
    )
    is RuntimeEvent.SessionListChanged -> SseEventPayload(
        type = "${prefix}session_list_changed",
        sessionId = sessionId,
    )
    is RuntimeEvent.CapabilitiesChanged -> SseEventPayload(
        type = "${prefix}capabilities_changed",
        sessionId = sessionId,
        runId = event.runId,
    )
    is UsageChanged -> SseEventPayload(
        type = "${prefix}usage_changed",
        sessionId = sessionId,
        runId = event.runId,
        usage = event.usage.toDto(compactionTriggerPercent),
    )
    is NoticeEvent -> SseEventPayload(
        type = "${prefix}notice",
        sessionId = sessionId,
        runId = event.runId,
    )
    is BackgroundTurnStarted -> SseEventPayload(
        type = "${prefix}run_started",
        sessionId = sessionId,
        runId = event.runId,
    )
}
