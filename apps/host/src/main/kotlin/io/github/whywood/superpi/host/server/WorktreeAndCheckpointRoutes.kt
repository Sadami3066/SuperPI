package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.CheckpointRecord
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.core.WorktreeRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
data class WorktreeDto(
    val id: String,
    val botId: String,
    val repoPath: String,
    val worktreePath: String,
    val branch: String,
    val baseBranch: String,
    val baseCommit: String,
    val label: String,
    val sessionId: String? = null,
    val workerId: String? = null,
    val createdAt: Long,
)

@Serializable
data class CreateWorktreeRequest(
    val label: String = "",
    val createSession: Boolean = true,
)

@Serializable
data class CreateWorktreeResponse(
    val worktree: WorktreeDto,
    val sessionId: String? = null,
)

@Serializable
data class GitFileSummaryDto(
    val path: String,
    val indexStatus: String = "",
    val workTreeStatus: String = "",
    val staged: Boolean = false,
    val additions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
data class GitSummaryResponse(
    val isGitRepo: Boolean,
    val branch: String? = null,
    val worktreeId: String? = null,
    val totalAdditions: Int = 0,
    val totalDeletions: Int = 0,
    val files: List<GitFileSummaryDto> = emptyList(),
)

@Serializable
data class GitPathActionRequest(
    val path: String? = null,
    val all: Boolean = false,
    val worktreeId: String? = null,
)

@Serializable
data class GitCommitRequest(
    val message: String,
    val stageAll: Boolean = false,
    val worktreeId: String? = null,
)

@Serializable
data class WorktreeCommitRequest(
    val message: String = "",
)

@Serializable
data class WorktreeOpResponse(
    val ok: Boolean,
    val message: String,
    val conflicts: List<String> = emptyList(),
)

@Serializable
data class WorktreeDiffResponse(
    val worktree: WorktreeDto,
    val files: List<GitFileSummaryDto>,
    val diff: String,
    val truncated: Boolean,
    val totalAdditions: Int,
    val totalDeletions: Int,
    val aheadCommits: Int,
)

@Serializable
data class OpenEditorRequest(
    val editor: String? = null,
    val worktreeId: String? = null,
)

@Serializable
data class OpenEditorResponse(
    val ok: Boolean,
    val editor: String,
    val message: String,
)

@Serializable
data class CheckpointDto(
    val id: String,
    val sessionId: String,
    val turnIndex: Int,
    val label: String,
    val messageCount: Int,
    val createdAt: Long,
)

@Serializable
data class RestoreCheckpointRequest(
    val mode: String = "files_only",
)

@Serializable
data class RestoreCheckpointResponse(
    val ok: Boolean,
    val message: String,
    val backupCheckpointId: String? = null,
    val restoredFiles: Int = 0,
)

@Serializable
data class ForkSessionRequest(
    val upToUserTurn: Int? = null,
    val title: String? = null,
)

@Serializable
data class RewindSessionRequest(
    val turnIndex: Int,
    val mode: String = "chat_only", // "chat_only" | "files_only" | "files_and_chat"
    val checkpointId: String? = null,
)

private val sessionLineJson = Json { ignoreUnknownKeys = true }

internal fun Route.installWorktreeAndCheckpointRoutes(controller: SuperPiController) {
    // ── Git Review Queue Operations (Stage / Unstage / Revert / Commit / Summary) ──
    get("/v1/bots/{id}/git/summary") {
        val botId = call.parameters["id"].orEmpty()
        val wtId = call.request.queryParameters["worktreeId"]?.trim()?.takeIf { it.isNotEmpty() }
        val rootDir = resolveBotOrWorktreeDir(controller, botId, wtId)
        if (rootDir == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot or worktree not found"))
            return@get
        }
        val summary = withContext(Dispatchers.IO) {
            buildGitSummary(rootDir, wtId)
        }
        call.respond(summary)
    }

    post("/v1/bots/{id}/git/stage") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<GitPathActionRequest>()
        val rootDir = resolveBotOrWorktreeDir(controller, botId, req.worktreeId)
        if (rootDir == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot or worktree not found"))
            return@post
        }
        val args = if (req.all || req.path.isNullOrBlank()) {
            listOf("add", "-A", "--", ".")
        } else {
            val clean = sanitizeGitRelPath(req.path) ?: run {
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Invalid path"))
                return@post
            }
            listOf("add", "--", clean)
        }
        val (code, out) = withContext(Dispatchers.IO) { WorkspacePathGuard.runGit(rootDir, args) }
        if (code != 0) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(out.trim().ifEmpty { "git stage failed" }))
        } else {
            call.respond(withContext(Dispatchers.IO) { buildGitSummary(rootDir, req.worktreeId) })
        }
    }

    post("/v1/bots/{id}/git/unstage") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<GitPathActionRequest>()
        val rootDir = resolveBotOrWorktreeDir(controller, botId, req.worktreeId)
        if (rootDir == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot or worktree not found"))
            return@post
        }
        val args = if (req.all || req.path.isNullOrBlank()) {
            listOf("reset", "HEAD", "--", ".")
        } else {
            val clean = sanitizeGitRelPath(req.path) ?: run {
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Invalid path"))
                return@post
            }
            listOf("reset", "HEAD", "--", clean)
        }
        val (code, out) = withContext(Dispatchers.IO) { WorkspacePathGuard.runGit(rootDir, args) }
        if (code != 0) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(out.trim().ifEmpty { "git unstage failed" }))
        } else {
            call.respond(withContext(Dispatchers.IO) { buildGitSummary(rootDir, req.worktreeId) })
        }
    }

    post("/v1/bots/{id}/git/revert") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<GitPathActionRequest>()
        val rawPath = req.path?.trim().orEmpty()
        val clean = sanitizeGitRelPath(rawPath)
        if (clean == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Valid file path is required"))
            return@post
        }
        val rootDir = resolveBotOrWorktreeDir(controller, botId, req.worktreeId)
        if (rootDir == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot or worktree not found"))
            return@post
        }
        val err = withContext(Dispatchers.IO) {
            val (headCode, _) = WorkspacePathGuard.runGit(rootDir, listOf("cat-file", "-e", "HEAD:$clean"))
            if (headCode == 0) {
                WorkspacePathGuard.runGit(rootDir, listOf("reset", "HEAD", "--", clean))
                val (coCode, coOut) = WorkspacePathGuard.runGit(rootDir, listOf("checkout", "HEAD", "--", clean))
                if (coCode != 0) coOut.trim().ifEmpty { "Failed to revert file" } else null
            } else {
                // Untracked or newly staged file (not in HEAD): unstage if needed, then safely unlink without following symlinks
                WorkspacePathGuard.runGit(rootDir, listOf("rm", "--cached", "--ignore-unmatch", "--", clean))
                when (val resolved = WorkspacePathGuard.resolveSafeNoFollow(rootDir, clean)) {
                    is WorkspacePathGuard.Resolution.Allowed -> {
                        val target = resolved.targetReal
                        val isSym = Files.isSymbolicLink(target)
                        val isReg = Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        if (target != resolved.rootReal && (isSym || isReg)) {
                            Files.deleteIfExists(target)
                            null
                        } else {
                            "Cannot revert non-regular untracked path"
                        }
                    }
                    is WorkspacePathGuard.Resolution.Forbidden -> resolved.reason
                    is WorkspacePathGuard.Resolution.NotFound -> null
                }
            }
        }
        if (err != null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err))
        } else {
            call.respond(withContext(Dispatchers.IO) { buildGitSummary(rootDir, req.worktreeId) })
        }
    }

    post("/v1/bots/{id}/git/commit") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<GitCommitRequest>()
        val msg = req.message.trim()
        if (msg.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Commit message is required"))
            return@post
        }
        val rootDir = resolveBotOrWorktreeDir(controller, botId, req.worktreeId)
        if (rootDir == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot or worktree not found"))
            return@post
        }
        val result = withContext(Dispatchers.IO) {
            if (req.stageAll) {
                val (aCode, aOut) = WorkspacePathGuard.runGit(rootDir, listOf("add", "-A"))
                if (aCode != 0) return@withContext WorktreeOpResponse(false, aOut.trim().ifEmpty { "git add failed" })
            }
            val (cCode, cOut) = WorkspacePathGuard.runGit(
                rootDir,
                listOf("commit", "-m", msg),
            )
            if (cCode != 0) {
                WorktreeOpResponse(false, cOut.trim().ifEmpty { "Nothing staged to commit" })
            } else {
                WorktreeOpResponse(true, cOut.lines().firstOrNull().orEmpty().ifBlank { "Committed" })
            }
        }
        if (!result.ok) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(result.message))
        } else {
            call.respond(result)
        }
    }

    post("/v1/bots/{id}/open-editor") {
        val botId = call.parameters["id"].orEmpty()
        val req = runCatching { call.receive<OpenEditorRequest>() }.getOrDefault(OpenEditorRequest())
        val rootDir = resolveBotOrWorktreeDir(controller, botId, req.worktreeId)
        if (rootDir == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot or worktree not found"))
            return@post
        }
        val res = withContext(Dispatchers.IO) {
            controller.worktreeManager.openInExternalEditor(rootDir, req.editor)
        }
        if (!res.ok) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(res.message))
        } else {
            call.respond(OpenEditorResponse(ok = res.ok, editor = res.editor, message = res.message))
        }
    }

    // ── Git Worktrees ────────────────────────────────────────────────────────
    get("/v1/bots/{id}/worktrees") {
        val botId = call.parameters["id"].orEmpty()
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        val items = controller.worktreeManager.listForBot(botId).map { it.toDto() }
        call.respond(items)
    }

    post("/v1/bots/{id}/worktrees") {
        val botId = call.parameters["id"].orEmpty()
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        val req = runCatching { call.receive<CreateWorktreeRequest>() }.getOrDefault(CreateWorktreeRequest())
        var createdWtId: String? = null
        try {
            val wt = controller.worktreeManager.create(
                botId = bot.id,
                repoDir = File(bot.cwd),
                label = req.label.ifBlank { "${bot.name}-wt" },
            )
            createdWtId = wt.id
            val sessionId = if (req.createSession) {
                controller.ensureConfiguredDefaultModel()
                val opened = controller.runtimeHost.newSessionInDirectory(wt.worktreePath)
                controller.worktreeManager.bindSession(wt.id, opened.sessionId)
                opened.sessionId
            } else {
                null
            }
            val finalWt = if (sessionId != null) wt.copy(sessionId = sessionId) else wt
            call.respond(
                HttpStatusCode.Created,
                CreateWorktreeResponse(worktree = finalWt.toDto(), sessionId = sessionId),
            )
        } catch (err: Exception) {
            if (createdWtId != null) {
                runCatching { controller.worktreeManager.remove(createdWtId, deleteBranch = true) }
            }
            if (err is kotlinx.coroutines.CancellationException) throw err
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Cannot create worktree"))
        }
    }

    get("/v1/worktrees/{wtId}/diff") {
        val wtId = call.parameters["wtId"].orEmpty().trim()
        try {
            val res = controller.worktreeManager.diff(wtId)
            call.respond(
                WorktreeDiffResponse(
                    worktree = res.worktree.toDto(),
                    files = res.files.map { f ->
                        GitFileSummaryDto(
                            path = f.path,
                            workTreeStatus = f.status,
                            additions = f.additions,
                            deletions = f.deletions,
                        )
                    },
                    diff = res.diff,
                    truncated = res.truncated,
                    totalAdditions = res.totalAdditions,
                    totalDeletions = res.totalDeletions,
                    aheadCommits = res.aheadCommits,
                ),
            )
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse(err.message ?: "Worktree not found"))
        }
    }

    post("/v1/worktrees/{wtId}/commit") {
        val wtId = call.parameters["wtId"].orEmpty().trim()
        val req = runCatching { call.receive<WorktreeCommitRequest>() }.getOrDefault(WorktreeCommitRequest())
        val res = controller.worktreeManager.commit(wtId, req.message)
        if (!res.ok) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(res.message))
        } else {
            call.respond(WorktreeOpResponse(ok = res.ok, message = res.message, conflicts = res.conflicts))
        }
    }

    post("/v1/worktrees/{wtId}/apply") {
        val wtId = call.parameters["wtId"].orEmpty().trim()
        val res = controller.worktreeManager.applyToBase(wtId)
        if (!res.ok) {
            call.respond(
                HttpStatusCode.Conflict,
                WorktreeOpResponse(ok = false, message = res.message, conflicts = res.conflicts),
            )
        } else {
            call.respond(WorktreeOpResponse(ok = true, message = res.message))
        }
    }

    delete("/v1/worktrees/{wtId}") {
        val wtId = call.parameters["wtId"].orEmpty().trim()
        val deleteBranch = call.request.queryParameters["deleteBranch"]?.equals("false", ignoreCase = true) != true
        val force = call.request.queryParameters["force"]?.equals("true", ignoreCase = true) == true
        val existing = controller.worktreeManager.findById(wtId)
        if (existing != null) {
            val wtDir = File(existing.worktreePath)
            val isDirty = withContext(Dispatchers.IO) { controller.worktreeManager.hasUncommittedChanges(wtDir) }
            if (isDirty && !force) {
                call.respond(HttpStatusCode.Conflict, ApiErrorResponse("Worktree has uncommitted changes"))
                return@delete
            }
            var capturedSafetyBackup = false
            if (isDirty && force) {
                val backupSessionId = existing.sessionId ?: "worktree-${existing.id}"
                val snap = runCatching {
                    controller.checkpointStore.capture(
                        sessionId = backupSessionId,
                        workspaceDir = wtDir,
                        turnIndex = 0,
                        label = "Safety snapshot before removing worktree ${existing.branch}",
                        messageCount = 0,
                    )
                }.getOrNull()
                capturedSafetyBackup = snap != null
            }
            controller.ptySessionManager.killByCwd(wtDir)
            existing.sessionId?.let { sid ->
                controller.autoRetryController.cancel(sid)
                runCatching { controller.runtimeHost.stop(sid) }
                runCatching { controller.runtimeHost.closeSession(sid) }
                if (!capturedSafetyBackup) {
                    runCatching { controller.checkpointStore.deleteSession(sid) }
                }
            }
        }
        val ok = controller.worktreeManager.remove(wtId, deleteBranch = deleteBranch)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Worktree not found: $wtId"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    // ── Turn Checkpoints & Session Fork ──────────────────────────────────────
    get("/v1/sessions/{sessionId}/checkpoints") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val list = controller.checkpointStore.list(sessionId).map { it.toDto() }
        call.respond(list)
    }

    post("/v1/sessions/{sessionId}/checkpoints/{cpId}/restore") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val cpId = call.parameters["cpId"].orEmpty().trim()
        val req = runCatching { call.receive<RestoreCheckpointRequest>() }.getOrDefault(RestoreCheckpointRequest())
        val cp = controller.checkpointStore.list(sessionId).firstOrNull { it.id == cpId }
        if (cp == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Checkpoint not found: $cpId"))
            return@post
        }
        if (sessionId in controller.runtimeHost.busySessionIds.value) {
            call.respond(HttpStatusCode.Conflict, ApiErrorResponse("Cannot restore checkpoint while session is running; stop generation first."))
            return@post
        }
        val res = controller.checkpointStore.restoreFiles(sessionId, cpId)
        if (!res.ok) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(res.message))
            return@post
        }
        if (req.mode.equals("files_and_chat", ignoreCase = true)) {
            val summary = controller.runtimeHost.allSessions().firstOrNull { it.id == sessionId }
                ?: controller.runtimeHost.archivedSessions().firstOrNull { it.id == sessionId }
            if (summary != null && summary.filePath.isNotBlank()) {
                val file = File(summary.filePath)
                val homeReal = runCatching { controller.paths.homeDir.toPath().toRealPath() }.getOrNull()
                val fileReal = runCatching { file.toPath().toRealPath() }.getOrNull()
                if (homeReal != null && fileReal != null && fileReal.startsWith(homeReal) && file.isFile) {
                    runCatching { controller.runtimeHost.stop(sessionId) }
                    runCatching { controller.runtimeHost.closeSession(sessionId) }
                    withContext(Dispatchers.IO) {
                        truncateSessionJsonlBeforeUserTurn(file, cp.turnIndex)
                    }
                    runCatching { controller.runtimeHost.openSession(file.absolutePath, summary.cwd) }
                }
            }
        }
        call.respond(
            RestoreCheckpointResponse(
                ok = true,
                message = res.message,
                backupCheckpointId = res.backupCheckpointId,
                restoredFiles = res.restoredFiles,
            ),
        )
    }

    post("/v1/sessions/{sessionId}/rewind") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val req = call.receive<RewindSessionRequest>()
        if (req.turnIndex < 0) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid turnIndex"))
            return@post
        }
        if (sessionId in controller.runtimeHost.busySessionIds.value) {
            call.respond(HttpStatusCode.Conflict, ApiErrorResponse("Cannot rewind while session is running; stop generation first."))
            return@post
        }
        val mode = req.mode.trim().lowercase()
        var restoredFiles = 0
        var backupCheckpointId: String? = null
        if (mode == "files_only" || mode == "files_and_chat") {
            val checkpoints = controller.checkpointStore.list(sessionId)
            val cp = if (!req.checkpointId.isNullOrBlank()) {
                checkpoints.firstOrNull { it.id == req.checkpointId }
            } else {
                checkpoints.firstOrNull { it.turnIndex == req.turnIndex }
            }
            if (cp == null) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Checkpoint not found for turn ${req.turnIndex}"))
                return@post
            }
            val res = controller.checkpointStore.restoreFiles(sessionId, cp.id)
            if (!res.ok) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(res.message))
                return@post
            }
            restoredFiles = res.restoredFiles
            backupCheckpointId = res.backupCheckpointId
        }
        if (mode == "chat_only" || mode == "files_and_chat") {
            val summary = controller.runtimeHost.allSessions().firstOrNull { it.id == sessionId }
                ?: controller.runtimeHost.archivedSessions().firstOrNull { it.id == sessionId }
            if (summary == null || summary.filePath.isBlank()) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
                return@post
            }
            val file = File(summary.filePath)
            val homeReal = runCatching { controller.paths.homeDir.toPath().toRealPath() }.getOrNull()
            val fileReal = runCatching { file.toPath().toRealPath() }.getOrNull()
            if (homeReal == null || fileReal == null || !fileReal.startsWith(homeReal) || !file.isFile) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Remote sessions cannot be rewound locally"))
                return@post
            }
            val turnExists = withContext(Dispatchers.IO) { hasUserTurnAtIndex(file, req.turnIndex) }
            if (!turnExists) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Rewind target turn index not found: ${req.turnIndex}"))
                return@post
            }
            runCatching { controller.runtimeHost.stop(sessionId) }
            runCatching { controller.runtimeHost.closeSession(sessionId) }
            val truncated = withContext(Dispatchers.IO) {
                truncateSessionJsonlBeforeUserTurn(file, req.turnIndex)
            }
            runCatching { controller.runtimeHost.openSession(file.absolutePath, summary.cwd) }
            if (!truncated) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Rewind target turn index not found: ${req.turnIndex}"))
                return@post
            }
        }
        call.respond(
            RestoreCheckpointResponse(
                ok = true,
                message = "Rewound session",
                backupCheckpointId = backupCheckpointId,
                restoredFiles = restoredFiles,
            ),
        )
    }

    post("/v1/sessions/{sessionId}/fork") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val req = runCatching { call.receive<ForkSessionRequest>() }.getOrDefault(ForkSessionRequest())
        val summary = controller.runtimeHost.allSessions().firstOrNull { it.id == sessionId }
            ?: controller.runtimeHost.archivedSessions().firstOrNull { it.id == sessionId }
        if (summary == null || summary.filePath.isBlank()) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Session not found: $sessionId"))
            return@post
        }
        val sourceFile = File(summary.filePath)
        val homeReal = runCatching { controller.paths.homeDir.toPath().toRealPath() }.getOrNull()
        val srcReal = runCatching { sourceFile.toPath().toRealPath() }.getOrNull()
        if (homeReal == null || srcReal == null || !srcReal.startsWith(homeReal) || !sourceFile.isFile) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Remote sessions cannot be forked locally"))
            return@post
        }
        val forkId = UUID.randomUUID().toString()
        val forkFile = File(sourceFile.parentFile, "$forkId.jsonl")
        withContext(Dispatchers.IO) {
            val sourceLines = sourceFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            val kept = sliceSessionJsonlLines(sourceLines, req.upToUserTurn, forkId, summary.cwd)
            forkFile.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        val opened = controller.runtimeHost.openSession(forkFile.absolutePath, summary.cwd)
        val forkTitle = req.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${summary.title.ifBlank { "Chat" }} (Fork)"
        runCatching { controller.runtimeHost.renameSession(opened.sessionId, forkTitle) }
        call.respond(
            HttpStatusCode.Created,
            CreateSessionResponse(
                sessionId = opened.sessionId,
                filePath = opened.filePath,
                cwd = opened.cwd,
            ),
        )
    }
}

private suspend fun resolveBotOrWorktreeDir(
    controller: SuperPiController,
    botId: String,
    worktreeId: String?,
): File? {
    val cleanWt = worktreeId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanWt != null) {
        val wt = controller.worktreeManager.findById(cleanWt) ?: return null
        if (wt.botId != botId) return null
        val dir = File(wt.worktreePath)
        return dir.takeIf { it.isDirectory }
    }
    val bot = controller.botRegistry.findBotById(botId) ?: return null
    val dir = File(bot.cwd)
    return dir.takeIf { it.isDirectory }
}

private fun sanitizeGitRelPath(raw: String): String? {
    val cleaned = raw.trim().replace('\\', '/').trimStart('/')
    if (cleaned.isEmpty() || cleaned == "." || cleaned.startsWith("-") || cleaned.startsWith(":")) return null
    if (WorkspacePathGuard.containsForbiddenSegment(cleaned)) return null
    return cleaned
}

private fun buildGitSummary(rootDir: File, worktreeId: String?): GitSummaryResponse {
    val (code, statusOut) = WorkspacePathGuard.runGit(rootDir, listOf("status", "--porcelain=v1", "-b", "--", "."))
    if (code != 0) return GitSummaryResponse(isGitRepo = false, worktreeId = worktreeId)
    val lines = statusOut.lines().filter { it.isNotEmpty() }
    val branch = lines.firstOrNull()?.takeIf { it.startsWith("## ") }
        ?.removePrefix("## ")
        ?.substringBefore("...")
        ?.trim()

    val statByPath = LinkedHashMap<String, Pair<Int, Int>>()
    val (nsCode, nsOut) = WorkspacePathGuard.runGit(rootDir, listOf("diff", "--numstat", "--no-ext-diff", "HEAD", "--", "."))
    val numstatSource = if (nsCode == 0) nsOut else WorkspacePathGuard.runGit(rootDir, listOf("diff", "--numstat", "--no-ext-diff", "--", ".")).second
    for (line in numstatSource.lines()) {
        val parts = line.split('\t')
        if (parts.size < 3) continue
        val add = parts[0].toIntOrNull() ?: 0
        val del = parts[1].toIntOrNull() ?: 0
        val p = parts[2].trim()
        if (!WorkspacePathGuard.containsForbiddenSegment(p)) {
            statByPath[p] = add to del
        }
    }

    val files = lines.dropWhile { it.startsWith("## ") }.mapNotNull { line ->
        if (line.length < 4) return@mapNotNull null
        val x = line[0].toString().trim()
        val y = line[1].toString().trim()
        val rawPath = line.substring(3).trim()
        val cleanPath = rawPath.substringAfter(" -> ", rawPath).trim('"')
        if (WorkspacePathGuard.containsForbiddenSegment(cleanPath)) return@mapNotNull null
        val staged = x.isNotEmpty() && x != "?"
        val (add, del) = statByPath[cleanPath] ?: run {
            if (x == "?" || y == "?") {
                val f = File(rootDir, cleanPath)
                val cnt = if (f.isFile && f.length() <= 256 * 1024) {
                    runCatching { f.readLines(Charsets.UTF_8).size }.getOrDefault(0)
                } else 0
                cnt to 0
            } else {
                0 to 0
            }
        }
        GitFileSummaryDto(
            path = cleanPath,
            indexStatus = x,
            workTreeStatus = y,
            staged = staged,
            additions = add,
            deletions = del,
        )
    }
    return GitSummaryResponse(
        isGitRepo = true,
        branch = branch,
        worktreeId = worktreeId,
        totalAdditions = files.sumOf { it.additions },
        totalDeletions = files.sumOf { it.deletions },
        files = files,
    )
}

private fun isUserMessageEntry(line: String): Boolean {
    val obj = runCatching { sessionLineJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return false
    if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "message") return false
    val msg = obj["message"] as? JsonObject ?: return false
    return (msg["role"] as? JsonPrimitive)?.contentOrNull == "user"
}

private fun hasUserTurnAtIndex(file: File, targetTurnIndex: Int): Boolean {
    if (!file.isFile || targetTurnIndex < 0) return false
    var seenUsers = 0
    file.forEachLine(Charsets.UTF_8) { line ->
        if (line.isNotBlank() && isUserMessageEntry(line)) {
            if (seenUsers == targetTurnIndex) return true
            seenUsers++
        }
    }
    return false
}

private fun truncateSessionJsonlBeforeUserTurn(file: File, targetTurnIndex: Int): Boolean {
    val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    var seenUsers = 0
    var foundTarget = false
    val kept = ArrayList<String>(lines.size)
    for (line in lines) {
        if (isUserMessageEntry(line)) {
            if (seenUsers >= targetTurnIndex) {
                foundTarget = true
                break
            }
            seenUsers++
        }
        kept.add(line)
    }
    if (!foundTarget) return false
    if (kept.isNotEmpty()) {
        // Save a reversible pre-truncate backup alongside the session file and swap atomically.
        runCatching {
            val bak = File(file.parentFile, "${file.name}.pre-rewind.bak")
            Files.copy(file.toPath(), bak.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val tmp = Files.createTempFile(file.parentFile.toPath(), ".truncate.", ".tmp")
        try {
            tmp.toFile().writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
            try {
                Files.move(
                    tmp,
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }
    return true
}

private fun sliceSessionJsonlLines(
    lines: List<String>,
    upToUserTurn: Int?,
    newSessionId: String,
    cwd: String,
): List<String> {
    val out = ArrayList<String>(lines.size + 1)
    var seenUsers = 0
    var wroteHeader = false
    for (line in lines) {
        val obj = runCatching { sessionLineJson.parseToJsonElement(line).jsonObject }.getOrNull()
        if (obj != null && (obj["type"] as? JsonPrimitive)?.contentOrNull == "session") {
            val rewritten = buildJsonObject {
                for ((k, v) in obj) {
                    if (k == "id") put("id", newSessionId) else put(k, v)
                }
            }
            out.add(rewritten.toString())
            wroteHeader = true
            continue
        }
        if (upToUserTurn != null && isUserMessageEntry(line)) {
            if (seenUsers > upToUserTurn) break
            seenUsers++
        }
        out.add(line)
    }
    if (!wroteHeader) {
        val headerObj = buildJsonObject {
            put("type", "session")
            put("version", 3)
            put("id", newSessionId)
            put("timestamp", java.time.Instant.now().toString())
            put("cwd", cwd)
        }
        out.add(0, headerObj.toString())
    }
    return out
}

private fun WorktreeRecord.toDto() = WorktreeDto(
    id = id,
    botId = botId,
    repoPath = repoPath,
    worktreePath = worktreePath,
    branch = branch,
    baseBranch = baseBranch,
    baseCommit = baseCommit,
    label = label,
    sessionId = sessionId,
    workerId = workerId,
    createdAt = createdAt,
)

private fun CheckpointRecord.toDto() = CheckpointDto(
    id = id,
    sessionId = sessionId,
    turnIndex = turnIndex,
    label = label,
    messageCount = messageCount,
    createdAt = createdAt,
)
