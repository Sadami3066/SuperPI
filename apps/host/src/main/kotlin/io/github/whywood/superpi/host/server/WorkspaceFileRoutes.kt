package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.remote.RemoteFileResult
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantStep
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object WorkspacePathGuard {
    const val MAX_PREVIEW_BYTES = 512 * 1024
    const val MAX_DIFF_CHARS = 256 * 1024
    private const val GIT_TIMEOUT_MS = 3_000L

    sealed interface Resolution {
        data class Allowed(val rootReal: Path, val targetReal: Path, val relativePath: String) : Resolution
        data class Forbidden(val reason: String) : Resolution
        data class NotFound(val reason: String) : Resolution
    }

    fun isDotGitSegment(segment: String): Boolean = segment.equals(".git", ignoreCase = true)

    fun containsForbiddenSegment(relPath: String): Boolean =
        relPath.replace('\\', '/').split('/').any { seg ->
            seg == ".." || isDotGitSegment(seg) || seg.contains('\u0000')
        }

    fun resolveSafe(rootDir: File, requestedRelPath: String?): Resolution {
        val rootReal = runCatching { rootDir.toPath().toRealPath() }.getOrNull()
            ?: return Resolution.NotFound("Bot directory does not exist")
        val cleaned = requestedRelPath.orEmpty().trim().replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty() || cleaned == ".") {
            return Resolution.Allowed(rootReal, rootReal, "")
        }
        if (containsForbiddenSegment(cleaned)) {
            return Resolution.Forbidden("Access to '$cleaned' is forbidden")
        }
        val candidate = try {
            rootReal.resolve(cleaned).normalize()
        } catch (_: InvalidPathException) {
            return Resolution.Forbidden("Invalid path")
        }
        if (!candidate.startsWith(rootReal)) {
            return Resolution.Forbidden("Path escapes workspace root")
        }
        if (!Files.exists(candidate)) {
            return Resolution.NotFound("Path does not exist: $cleaned")
        }
        val targetReal = runCatching { candidate.toRealPath() }.getOrNull()
            ?: return Resolution.Forbidden("Unable to resolve real path")
        if (!targetReal.startsWith(rootReal)) {
            return Resolution.Forbidden("Symlink escapes workspace root")
        }
        val relPath = rootReal.relativize(targetReal).toString().replace('\\', '/')
        if (containsForbiddenSegment(relPath)) {
            return Resolution.Forbidden("Access to .git is forbidden")
        }
        return Resolution.Allowed(rootReal, targetReal, relPath)
    }

    fun resolveSafeWriteTarget(rootDir: File, requestedRelPath: String?): Resolution {
        val rootReal = runCatching { rootDir.toPath().toRealPath() }.getOrNull()
            ?: return Resolution.NotFound("Bot directory does not exist")
        val cleaned = requestedRelPath.orEmpty().trim().replace('\\', '/').trimStart('/').trimEnd('/')
        if (cleaned.isEmpty() || cleaned == ".") {
            return Resolution.Forbidden("Cannot target workspace root directly")
        }
        if (containsForbiddenSegment(cleaned)) {
            return Resolution.Forbidden("Access to '$cleaned' is forbidden")
        }
        val candidate = try {
            rootReal.resolve(cleaned).normalize()
        } catch (_: InvalidPathException) {
            return Resolution.Forbidden("Invalid path")
        }
        if (!candidate.startsWith(rootReal) || candidate == rootReal) {
            return Resolution.Forbidden("Path escapes workspace root")
        }
        // Walk components from rootReal using NOFOLLOW_LINKS to block dangling symlink escapes & .git symlink writes
        var cursor = rootReal
        val relCandidate = rootReal.relativize(candidate)
        val count = relCandidate.nameCount
        for (i in 0 until count) {
            val seg = relCandidate.getName(i)
            val step = cursor.resolve(seg)
            if (Files.isSymbolicLink(step)) {
                return Resolution.Forbidden("Refusing to write through symbolic link")
            }
            if (Files.exists(step, LinkOption.NOFOLLOW_LINKS)) {
                val stepReal = runCatching { step.toRealPath() }.getOrNull()
                    ?: return Resolution.Forbidden("Unable to verify path segment")
                if (!stepReal.startsWith(rootReal)) {
                    return Resolution.Forbidden("Path escapes workspace root")
                }
                val stepRel = rootReal.relativize(stepReal).toString().replace('\\', '/')
                if (containsForbiddenSegment(stepRel)) {
                    return Resolution.Forbidden("Access to .git is forbidden")
                }
            }
            cursor = step
        }
        val relPath = rootReal.relativize(candidate).toString().replace('\\', '/')
        if (containsForbiddenSegment(relPath)) {
            return Resolution.Forbidden("Access to .git is forbidden")
        }
        return Resolution.Allowed(rootReal, candidate, relPath)
    }

    /**
     * Resolves an existing entry WITHOUT dereferencing the final segment (safe for delete, rename source,
     * and untracked git revert so symlinks themselves are unlinked/moved rather than their targets).
     */
    fun resolveSafeNoFollow(rootDir: File, requestedRelPath: String?): Resolution {
        val rootReal = runCatching { rootDir.toPath().toRealPath() }.getOrNull()
            ?: return Resolution.NotFound("Bot directory does not exist")
        val cleaned = requestedRelPath.orEmpty().trim().replace('\\', '/').trimStart('/').trimEnd('/')
        if (cleaned.isEmpty() || cleaned == ".") {
            return Resolution.Forbidden("Cannot target workspace root directly")
        }
        if (containsForbiddenSegment(cleaned)) {
            return Resolution.Forbidden("Access to '$cleaned' is forbidden")
        }
        val candidate = try {
            rootReal.resolve(cleaned).normalize()
        } catch (_: InvalidPathException) {
            return Resolution.Forbidden("Invalid path")
        }
        if (!candidate.startsWith(rootReal) || candidate == rootReal) {
            return Resolution.Forbidden("Path escapes workspace root")
        }
        val parent = candidate.parent ?: return Resolution.Forbidden("Invalid parent directory")
        val parentReal = runCatching { parent.toRealPath() }.getOrNull()
            ?: return Resolution.NotFound("Parent directory does not exist")
        if (!parentReal.startsWith(rootReal)) {
            return Resolution.Forbidden("Parent symlink escapes workspace root")
        }
        val parentRel = rootReal.relativize(parentReal).toString().replace('\\', '/')
        if (parentRel.isNotEmpty() && containsForbiddenSegment(parentRel)) {
            return Resolution.Forbidden("Access to .git is forbidden")
        }
        val nonDereferenced = parentReal.resolve(candidate.fileName)
        if (!Files.exists(nonDereferenced, LinkOption.NOFOLLOW_LINKS)) {
            return Resolution.NotFound("Path does not exist: $cleaned")
        }
        val relPath = rootReal.relativize(nonDereferenced).toString().replace('\\', '/')
        if (containsForbiddenSegment(relPath)) {
            return Resolution.Forbidden("Access to .git is forbidden")
        }
        return Resolution.Allowed(rootReal, nonDereferenced, relPath)
    }

    fun runGit(rootDir: File, args: List<String>, maxChars: Int = MAX_DIFF_CHARS + 1): Pair<Int, String> {
        val safeArgs = listOf("git", "--literal-pathspecs", "-c", "core.fsmonitor=false", "-c", "core.quotePath=false") + args
        val proc = try {
            ProcessBuilder(safeArgs)
                .directory(rootDir)
                .redirectErrorStream(true)
                .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        } catch (_: IOException) {
            return -1 to ""
        }
        runCatching { proc.outputStream.close() }
        val buffer = StringBuilder()
        val reader = thread(isDaemon = true, name = "superpi-git-reader") {
            try {
                proc.inputStream.reader(Charsets.UTF_8).use { r ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n < 0) break
                        val remaining = maxChars - buffer.length
                        if (remaining > 0) {
                            buffer.append(chunk, 0, minOf(n, remaining))
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }
        return try {
            val finished = proc.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                reader.join(500)
                -1 to ""
            } else {
                reader.join(1_000)
                proc.exitValue() to buffer.toString()
            }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            runCatching { proc.inputStream.close() }
        }
    }
}

internal fun Route.installWorkspaceFileRoutes(controller: SuperPiController) {
    get("/v1/bots/{id}/files") {
        val botId = call.parameters["id"].orEmpty()
        val reqPath = call.request.queryParameters["path"]
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            if (!reqPath.isNullOrBlank() && WorkspacePathGuard.containsForbiddenSegment(reqPath)) {
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden path"))
                return@get
            }
            when (val res = controller.remoteFileClient.list(remote.deviceId, remote.botId, reqPath.orEmpty())) {
                is RemoteFileResult.Ok -> {
                    val entries = res.value.entries
                        .filterNot { WorkspacePathGuard.isDotGitSegment(it.name) || WorkspacePathGuard.containsForbiddenSegment(it.path) }
                        .map { item ->
                            WorkspaceFileEntryDto(
                                name = item.name,
                                path = item.path,
                                isDirectory = item.isDirectory,
                                sizeBytes = item.size,
                                modifiedAt = item.modifiedAt,
                            )
                        }
                        .sortedWith(compareByDescending<WorkspaceFileEntryDto> { it.isDirectory }.thenBy { it.name.lowercase() })
                    call.respond(
                        WorkspaceFileListResponse(
                            botId = botId,
                            root = "${remote.deviceName}:${remote.botName}",
                            path = res.value.path,
                            entries = entries,
                        ),
                    )
                }
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        val resolved = withContext(Dispatchers.IO) {
            WorkspacePathGuard.resolveSafe(File(bot.cwd), reqPath)
        }
        when (resolved) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                val dir = resolved.targetReal.toFile()
                if (!dir.isDirectory) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Path is not a directory"))
                    return@get
                }
                val entries = withContext(Dispatchers.IO) {
                    dir.listFiles().orEmpty()
                        .mapNotNull { child ->
                            if (WorkspacePathGuard.isDotGitSegment(child.name)) return@mapNotNull null
                            val childReal = runCatching { child.toPath().toRealPath() }.getOrNull()
                                ?: return@mapNotNull null
                            if (!childReal.startsWith(resolved.rootReal)) return@mapNotNull null
                            val rel = resolved.rootReal.relativize(childReal).toString().replace('\\', '/')
                            if (WorkspacePathGuard.containsForbiddenSegment(rel)) return@mapNotNull null
                            WorkspaceFileEntryDto(
                                name = child.name,
                                path = rel,
                                isDirectory = child.isDirectory,
                                sizeBytes = if (child.isFile) child.length() else 0L,
                                modifiedAt = child.lastModified(),
                            )
                        }
                        .sortedWith(compareByDescending<WorkspaceFileEntryDto> { it.isDirectory }.thenBy { it.name.lowercase() })
                }
                call.respond(
                    WorkspaceFileListResponse(
                        botId = bot.id,
                        root = resolved.rootReal.toString(),
                        path = resolved.relativePath,
                        entries = entries,
                    ),
                )
            }
        }
    }

    get("/v1/bots/{id}/files/read") {
        val botId = call.parameters["id"].orEmpty()
        val reqPath = call.request.queryParameters["path"].orEmpty()
        if (reqPath.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing path parameter"))
            return@get
        }
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            if (WorkspacePathGuard.containsForbiddenSegment(reqPath)) {
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden path"))
                return@get
            }
            val stat = controller.remoteFileClient.stat(remote.deviceId, remote.botId, reqPath)
            val totalSize = (stat as? RemoteFileResult.Ok)?.value?.size ?: 0L
            if ((stat as? RemoteFileResult.Ok)?.value?.isDirectory == true) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Path is not a file"))
                return@get
            }
            val buffer = ByteArrayOutputStream()
            val capped = object : OutputStream() {
                private var written = 0
                var truncated = false
                override fun write(b: Int) {
                    if (written < WorkspacePathGuard.MAX_PREVIEW_BYTES) {
                        buffer.write(b)
                        written++
                    } else {
                        truncated = true
                    }
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val remain = WorkspacePathGuard.MAX_PREVIEW_BYTES - written
                    if (remain <= 0) {
                        truncated = true
                        return
                    }
                    val toWrite = minOf(len, remain)
                    buffer.write(b, off, toWrite)
                    written += toWrite
                    if (toWrite < len) truncated = true
                }
            }
            when (
                val dl = controller.remoteFileClient.downloadToStream(
                    deviceId = remote.deviceId,
                    botId = remote.botId,
                    remotePath = reqPath,
                    outputStream = capped,
                    maxBytes = (WorkspacePathGuard.MAX_PREVIEW_BYTES + 1).toLong(),
                )
            ) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(dl.message))
                is RemoteFileResult.Ok -> {
                    val bytes = buffer.toByteArray()
                    val binary = bytes.take(8192).any { it == 0.toByte() }
                    val size = maxOf(totalSize, dl.value)
                    call.respond(
                        WorkspaceFileReadResponse(
                            botId = botId,
                            path = reqPath,
                            content = if (binary) "" else String(bytes, Charsets.UTF_8),
                            sizeBytes = size,
                            truncated = capped.truncated || size > WorkspacePathGuard.MAX_PREVIEW_BYTES,
                            isBinary = binary,
                        ),
                    )
                }
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        val resolved = withContext(Dispatchers.IO) {
            WorkspacePathGuard.resolveSafe(File(bot.cwd), reqPath)
        }
        when (resolved) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                val file = resolved.targetReal.toFile()
                if (!file.isFile) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Path is not a file"))
                    return@get
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val totalSize = file.length()
                        val toRead = totalSize.coerceAtMost(WorkspacePathGuard.MAX_PREVIEW_BYTES.toLong()).toInt()
                        val bytes = ByteArray(toRead)
                        var readTotal = 0
                        file.inputStream().use { input ->
                            while (readTotal < toRead) {
                                val r = input.read(bytes, readTotal, toRead - readTotal)
                                if (r < 0) break
                                readTotal += r
                            }
                        }
                        val slice = if (readTotal == bytes.size) bytes else bytes.copyOf(readTotal)
                        val mtimeMs = file.lastModified()
                        val binary = slice.take(8192).any { it == 0.toByte() }
                        if (binary) {
                            WorkspaceFileReadResponse(
                                botId = bot.id,
                                path = resolved.relativePath,
                                content = "",
                                sizeBytes = totalSize,
                                truncated = false,
                                isBinary = true,
                                mtimeMs = mtimeMs,
                            )
                        } else {
                            WorkspaceFileReadResponse(
                                botId = bot.id,
                                path = resolved.relativePath,
                                content = String(slice, Charsets.UTF_8),
                                sizeBytes = totalSize,
                                truncated = totalSize > WorkspacePathGuard.MAX_PREVIEW_BYTES,
                                isBinary = false,
                                mtimeMs = mtimeMs,
                            )
                        }
                    }
                }
                result.fold(
                    onSuccess = { call.respond(it) },
                    onFailure = { err ->
                        call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(err.message ?: "Unable to read file"))
                    },
                )
            }
        }
    }

    get("/v1/bots/{id}/files/raw") {
        val botId = call.parameters["id"].orEmpty()
        val reqPath = call.request.queryParameters["path"].orEmpty()
        val download = call.request.queryParameters["download"] == "1" ||
            call.request.queryParameters["download"].equals("true", ignoreCase = true)
        if (reqPath.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing path parameter"))
            return@get
        }
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            if (WorkspacePathGuard.containsForbiddenSegment(reqPath)) {
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden path"))
                return@get
            }
            val stat = controller.remoteFileClient.stat(remote.deviceId, remote.botId, reqPath)
            val entry = (stat as? RemoteFileResult.Ok)?.value
            if (entry?.isDirectory == true) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Path is not a file"))
                return@get
            }
            if (entry != null && entry.size > MAX_RAW_SERVE_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("File exceeds 50 MB limit"))
                return@get
            }
            val buffer = ByteArrayOutputStream()
            when (
                val dl = controller.remoteFileClient.downloadToStream(
                    deviceId = remote.deviceId,
                    botId = remote.botId,
                    remotePath = reqPath,
                    outputStream = buffer,
                    maxBytes = MAX_RAW_SERVE_BYTES + 1L,
                )
            ) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(dl.message))
                is RemoteFileResult.Ok -> {
                    val bytes = buffer.toByteArray()
                    if (bytes.size.toLong() > MAX_RAW_SERVE_BYTES) {
                        call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("File exceeds 50 MB limit"))
                        return@get
                    }
                    val fileName = reqPath.substringAfterLast('/').ifBlank { "file" }
                    respondSafeRawBytes(call, bytes, fileName, download)
                }
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        val resolved = withContext(Dispatchers.IO) {
            WorkspacePathGuard.resolveSafe(File(bot.cwd), reqPath)
        }
        when (resolved) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                val file = resolved.targetReal.toFile()
                if (!file.isFile) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Path is not a file"))
                    return@get
                }
                if (file.length() > MAX_RAW_SERVE_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("File exceeds 50 MB limit"))
                    return@get
                }
                val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() } }
                bytes.fold(
                    onSuccess = { data -> respondSafeRawBytes(call, data, file.name, download) },
                    onFailure = { err ->
                        call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(err.message ?: "Unable to read file"))
                    },
                )
            }
        }
    }

    get("/v1/sessions/{sessionId}/artifact") {
        val sessionId = call.parameters["sessionId"].orEmpty().trim()
        val rawPath = call.request.queryParameters["path"].orEmpty().trim()
        val download = call.request.queryParameters["download"] == "1" ||
            call.request.queryParameters["download"].equals("true", ignoreCase = true)
        if (sessionId.isEmpty() || rawPath.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Missing sessionId or path"))
            return@get
        }
        if (rawPath.contains('\u0000') || WorkspacePathGuard.containsForbiddenSegment(rawPath)) {
            call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden artifact path"))
            return@get
        }
        val resolved = withContext(Dispatchers.IO) {
            resolveAuthorizedArtifactFile(controller, sessionId, rawPath)
        }
        if (resolved == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Artifact not found or access denied"))
            return@get
        }
        if (!resolved.isFile || !resolved.canRead()) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Artifact file is not readable"))
            return@get
        }
        if (resolved.length() > MAX_RAW_SERVE_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("Artifact exceeds 50 MB limit"))
            return@get
        }
        val bytes = withContext(Dispatchers.IO) { runCatching { resolved.readBytes() } }
        bytes.fold(
            onSuccess = { data -> respondSafeRawBytes(call, data, resolved.name, download) },
            onFailure = { err ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(err.message ?: "Unable to read artifact"))
            },
        )
    }

    post("/v1/bots/{id}/attachments") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<StageAttachmentRequest>()
        val cleanName = sanitizeAttachmentFileName(req.name)
        val rawB64 = req.dataBase64.substringAfter("base64,", req.dataBase64).trim()
        val maxEncodedLen = ((MAX_ATTACHMENT_BYTES + 2) / 3) * 4
        if (rawB64.length > maxEncodedLen) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("Attachment exceeds 25 MB limit"))
            return@post
        }
        val decoded = try {
            if (rawB64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(rawB64)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid base64 payload"))
            return@post
        }
        if (decoded.size > MAX_ATTACHMENT_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("Attachment exceeds 25 MB limit"))
            return@post
        }
        val mime = req.mimeType.trim().ifBlank { guessMimeForFileName(cleanName) }
        val kind = classifyAttachmentKind(mime, cleanName)
        val uniqueName = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}-$cleanName"
        val relativePath = "$ATTACHMENTS_DIR_NAME/$uniqueName"

        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            val uploadRes = controller.remoteFileClient.uploadFromStream(
                deviceId = remote.deviceId,
                botId = remote.botId,
                remotePath = relativePath,
                inputStream = ByteArrayInputStream(decoded),
                totalBytes = decoded.size.toLong(),
                overwrite = true,
            )
            when (uploadRes) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(uploadRes.message))
                is RemoteFileResult.Ok -> {
                    call.respond(
                        HttpStatusCode.Created,
                        StagedAttachmentDto(
                            id = uniqueName,
                            name = cleanName,
                            mime = mime,
                            size = decoded.size.toLong(),
                            path = relativePath,
                            relativePath = relativePath,
                            kind = kind,
                        ),
                    )
                }
            }
            return@post
        }

        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        val staged = withContext(Dispatchers.IO) {
            runCatching {
                val rootDir = File(bot.cwd)
                val rootReal = rootDir.toPath().toRealPath()
                val attachDir = rootReal.resolve(ATTACHMENTS_DIR_NAME).toFile()
                if (!attachDir.exists()) attachDir.mkdirs()
                val attachDirReal = attachDir.toPath().toRealPath()
                if (!attachDirReal.startsWith(rootReal)) {
                    throw SecurityException("Attachments directory escapes workspace root")
                }
                val targetPath = attachDirReal.resolve(uniqueName).normalize()
                if (!targetPath.startsWith(attachDirReal)) {
                    throw SecurityException("Attachment path escapes attachments directory")
                }
                val targetFile = targetPath.toFile()
                writeBytesAtomically(targetFile, decoded)
                val finalReal = targetFile.toPath().toRealPath()
                if (!finalReal.startsWith(rootReal)) {
                    runCatching { targetFile.delete() }
                    throw SecurityException("Attachment target escapes workspace root")
                }
                StagedAttachmentDto(
                    id = uniqueName,
                    name = cleanName,
                    mime = mime,
                    size = decoded.size.toLong(),
                    path = finalReal.toString(),
                    relativePath = relativePath,
                    kind = kind,
                )
            }
        }
        staged.fold(
            onSuccess = { call.respond(HttpStatusCode.Created, it) },
            onFailure = { err ->
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Failed to stage attachment"))
            },
        )
    }

    post("/v1/bots/{id}/attachments/folder") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<StageFolderAttachmentRequest>()
        val rawPath = req.path.trim()
        if (rawPath.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Folder path is required"))
            return@post
        }
        val dirFile = File(rawPath)
        val canonicalDir = runCatching { dirFile.toPath().toRealPath().toFile() }.getOrNull()
        if (canonicalDir == null || !canonicalDir.isDirectory) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Folder not found: $rawPath"))
            return@post
        }
        val folderName = sanitizeAttachmentFileName(canonicalDir.name.ifBlank { "folder" })
        val uniqueId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}-$folderName"

        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            val zipResult = withContext(Dispatchers.IO) {
                runCatching { zipFolderForRemote(canonicalDir, MAX_ATTACHMENT_BYTES) }
            }
            val zipBytes = zipResult.getOrElse { err ->
                val status = if (err is IllegalStateException && err.message?.contains("25 MB") == true) {
                    HttpStatusCode.PayloadTooLarge
                } else {
                    HttpStatusCode.BadRequest
                }
                call.respond(status, ApiErrorResponse(err.message ?: "Failed to package folder"))
                return@post
            }
            val zipName = "$folderName.zip"
            val relativePath = "$ATTACHMENTS_DIR_NAME/$uniqueId.zip"
            val uploadRes = controller.remoteFileClient.uploadFromStream(
                deviceId = remote.deviceId,
                botId = remote.botId,
                remotePath = relativePath,
                inputStream = ByteArrayInputStream(zipBytes),
                totalBytes = zipBytes.size.toLong(),
                overwrite = true,
            )
            when (uploadRes) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(uploadRes.message))
                is RemoteFileResult.Ok -> {
                    call.respond(
                        HttpStatusCode.Created,
                        StagedAttachmentDto(
                            id = "$uniqueId.zip",
                            name = zipName,
                            mime = "application/zip",
                            size = zipBytes.size.toLong(),
                            path = relativePath,
                            relativePath = relativePath,
                            kind = "folder",
                        ),
                    )
                }
            }
            return@post
        }

        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        call.respond(
            HttpStatusCode.Created,
            StagedAttachmentDto(
                id = uniqueId,
                name = canonicalDir.name.ifBlank { folderName },
                mime = "inode/directory",
                size = 0L,
                path = canonicalDir.absolutePath,
                relativePath = canonicalDir.absolutePath,
                kind = "folder",
            ),
        )
    }

    post("/v1/bots/{id}/files/upload") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<UploadWorkspaceFileRequest>()
        val relTarget = req.path.trim().replace('\\', '/').trimStart('/')
        if (relTarget.isEmpty() || WorkspacePathGuard.containsForbiddenSegment(relTarget)) {
            call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden or empty target path"))
            return@post
        }
        val rawB64 = req.dataBase64.substringAfter("base64,", req.dataBase64).trim()
        val maxEncodedLen = ((MAX_RAW_SERVE_BYTES + 2L) / 3L) * 4L
        if (rawB64.length.toLong() > maxEncodedLen) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("File exceeds 50 MB limit"))
            return@post
        }
        val bytes = try {
            if (rawB64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(rawB64)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid base64 payload"))
            return@post
        }
        if (bytes.size.toLong() > MAX_RAW_SERVE_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("File exceeds 50 MB limit"))
            return@post
        }
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            when (
                val up = controller.remoteFileClient.uploadFromStream(
                    deviceId = remote.deviceId,
                    botId = remote.botId,
                    remotePath = relTarget,
                    inputStream = ByteArrayInputStream(bytes),
                    totalBytes = bytes.size.toLong(),
                    overwrite = req.overwrite,
                )
            ) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(up.message))
                is RemoteFileResult.Ok -> call.respond(HttpStatusCode.Created, HealthResponse(ok = true))
            }
            return@post
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        when (val resolved = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafeWriteTarget(File(bot.cwd), relTarget) }) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                val targetFile = resolved.targetReal.toFile()
                if (targetFile.exists() && !req.overwrite) {
                    call.respond(HttpStatusCode.Conflict, ApiErrorResponse("File already exists"))
                    return@post
                }
                if (req.expectedMtimeMs != null && targetFile.exists() && targetFile.lastModified() != req.expectedMtimeMs) {
                    call.respond(HttpStatusCode.Conflict, ApiErrorResponse("File was modified externally"))
                    return@post
                }
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        writeBytesAtomically(targetFile, bytes)
                        targetFile.lastModified()
                    }
                }
                res.fold(
                    onSuccess = { newMtime ->
                        call.respond(HttpStatusCode.Created, UploadWorkspaceFileResponse(ok = true, mtimeMs = newMtime))
                    },
                    onFailure = { err ->
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Upload failed"))
                    },
                )
            }
        }
    }

    post("/v1/bots/{id}/files/mkdir") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<WorkspaceMkdirRequest>()
        val relTarget = req.path.trim().replace('\\', '/').trimStart('/')
        if (relTarget.isEmpty() || WorkspacePathGuard.containsForbiddenSegment(relTarget)) {
            call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden or empty directory path"))
            return@post
        }
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            when (val res = controller.remoteFileClient.mkdir(remote.deviceId, remote.botId, relTarget)) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
                is RemoteFileResult.Ok -> call.respond(HttpStatusCode.Created, HealthResponse(ok = true))
            }
            return@post
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        when (val resolved = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafeWriteTarget(File(bot.cwd), relTarget) }) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                val created = withContext(Dispatchers.IO) {
                    runCatching {
                        Files.createDirectories(resolved.targetReal)
                    }
                }
                created.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, HealthResponse(ok = true)) },
                    onFailure = { err ->
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Failed to create folder"))
                    },
                )
            }
        }
    }

    post("/v1/bots/{id}/files/rename") {
        val botId = call.parameters["id"].orEmpty()
        val req = call.receive<WorkspaceRenameRequest>()
        val fromRel = req.from.trim().replace('\\', '/').trimStart('/')
        val toRel = req.to.trim().replace('\\', '/').trimStart('/')
        if (fromRel.isEmpty() || toRel.isEmpty() ||
            WorkspacePathGuard.containsForbiddenSegment(fromRel) ||
            WorkspacePathGuard.containsForbiddenSegment(toRel)
        ) {
            call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden or empty path"))
            return@post
        }
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            when (val res = controller.remoteFileClient.rename(remote.deviceId, remote.botId, fromRel, toRel)) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
                is RemoteFileResult.Ok -> call.respond(HealthResponse(ok = true))
            }
            return@post
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        val rootDir = File(bot.cwd)
        val fromRes = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafeNoFollow(rootDir, fromRel) }
        val toRes = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafeWriteTarget(rootDir, toRel) }
        when {
            fromRes is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(fromRes.reason))
            fromRes is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(fromRes.reason))
            toRes is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(toRes.reason))
            toRes is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(toRes.reason))
            fromRes is WorkspacePathGuard.Resolution.Allowed && toRes is WorkspacePathGuard.Resolution.Allowed -> {
                if (fromRes.relativePath.isEmpty()) {
                    call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Cannot rename workspace root"))
                    return@post
                }
                val moved = withContext(Dispatchers.IO) {
                    runCatching {
                        toRes.targetReal.parent?.let { Files.createDirectories(it) }
                        Files.move(fromRes.targetReal, toRes.targetReal)
                    }
                }
                moved.fold(
                    onSuccess = { call.respond(HealthResponse(ok = true)) },
                    onFailure = { err ->
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Rename failed"))
                    },
                )
            }
        }
    }

    delete("/v1/bots/{id}/files") {
        val botId = call.parameters["id"].orEmpty()
        val reqPath = call.request.queryParameters["path"].orEmpty().trim().replace('\\', '/').trimStart('/')
        val recursive = call.request.queryParameters["recursive"] == "true" ||
            call.request.queryParameters["recursive"] == "1"
        if (reqPath.isEmpty() || reqPath == "." || WorkspacePathGuard.containsForbiddenSegment(reqPath)) {
            call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden or empty delete path"))
            return@delete
        }
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            when (val res = controller.remoteFileClient.delete(remote.deviceId, remote.botId, reqPath, recursive)) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
                is RemoteFileResult.Ok -> call.respond(HealthResponse(ok = true))
            }
            return@delete
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@delete
        }
        when (val resolved = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafeNoFollow(File(bot.cwd), reqPath) }) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                if (resolved.relativePath.isEmpty() || resolved.targetReal == resolved.rootReal) {
                    call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Cannot delete workspace root"))
                    return@delete
                }
                val isSym = Files.isSymbolicLink(resolved.targetReal)
                val file = resolved.targetReal.toFile()
                val deleted = withContext(Dispatchers.IO) {
                    runCatching {
                        if (!isSym && Files.isDirectory(resolved.targetReal, LinkOption.NOFOLLOW_LINKS)) {
                            if (!recursive && file.listFiles().orEmpty().isNotEmpty()) {
                                throw IllegalStateException("Directory is not empty")
                            }
                            deleteSafeRecursively(resolved.targetReal, resolved.rootReal)
                        } else {
                            Files.deleteIfExists(resolved.targetReal)
                        }
                    }
                }
                deleted.fold(
                    onSuccess = { call.respond(HealthResponse(ok = true)) },
                    onFailure = { err ->
                        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Delete failed"))
                    },
                )
            }
        }
    }

    get("/v1/bots/{id}/files/search") {
        val botId = call.parameters["id"].orEmpty()
        val query = call.request.queryParameters["q"].orEmpty().trim()
        val basePath = call.request.queryParameters["path"].orEmpty().trim()
        if (query.isEmpty()) {
            call.respond(WorkspaceSearchResponse(botId = botId, query = "", entries = emptyList()))
            return@get
        }
        val remote = controller.findRemoteBotByWorkspaceId(botId)
        if (remote != null) {
            if (basePath.isNotEmpty() && WorkspacePathGuard.containsForbiddenSegment(basePath)) {
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Forbidden path"))
                return@get
            }
            when (val res = controller.remoteFileClient.list(remote.deviceId, remote.botId, basePath, query = query, limit = 200)) {
                is RemoteFileResult.Error -> call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(res.message))
                is RemoteFileResult.Ok -> {
                    val items = res.value.entries
                        .filterNot { WorkspacePathGuard.isDotGitSegment(it.name) || WorkspacePathGuard.containsForbiddenSegment(it.path) }
                        .map { item ->
                            WorkspaceFileEntryDto(
                                name = item.name,
                                path = item.path,
                                isDirectory = item.isDirectory,
                                sizeBytes = item.size,
                                modifiedAt = item.modifiedAt,
                            )
                        }
                    call.respond(WorkspaceSearchResponse(botId = botId, query = query, entries = items))
                }
            }
            return@get
        }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        when (val resolved = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafe(File(bot.cwd), basePath) }) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                val results = withContext(Dispatchers.IO) {
                    searchLocalWorkspace(resolved.rootReal, resolved.targetReal, query)
                }
                call.respond(WorkspaceSearchResponse(botId = bot.id, query = query, entries = results))
            }
        }
    }

    get("/v1/bots/{id}/git/status") {
        val botId = call.parameters["id"].orEmpty()
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        val status = withContext(Dispatchers.IO) {
            val rootDir = File(bot.cwd)
            if (!rootDir.isDirectory) return@withContext GitStatusResponse(isGitRepo = false)
            val (code, out) = WorkspacePathGuard.runGit(rootDir, listOf("status", "--porcelain=v1", "-b", "--", "."))
            if (code != 0) return@withContext GitStatusResponse(isGitRepo = false)
            val lines = out.lines().filter { it.isNotEmpty() }
            val branch = lines.firstOrNull()?.takeIf { it.startsWith("## ") }
                ?.removePrefix("## ")
                ?.substringBefore("...")
                ?.trim()
            val files = lines.dropWhile { it.startsWith("## ") }.mapNotNull { line ->
                if (line.length < 4) return@mapNotNull null
                val x = line[0].toString().trim()
                val y = line[1].toString().trim()
                val rawPath = line.substring(3).trim()
                val cleanPath = rawPath.substringAfter(" -> ", rawPath).trim('"')
                if (WorkspacePathGuard.containsForbiddenSegment(cleanPath)) return@mapNotNull null
                GitChangedFileDto(path = cleanPath, indexStatus = x, workTreeStatus = y)
            }
            GitStatusResponse(isGitRepo = true, branch = branch, files = files)
        }
        call.respond(status)
    }

    get("/v1/bots/{id}/git/diff") {
        val botId = call.parameters["id"].orEmpty()
        val wtId = call.request.queryParameters["worktreeId"]?.trim()?.takeIf { it.isNotEmpty() }
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        val wtDir = if (wtId != null) {
            val wt = controller.worktreeManager.findById(wtId)
            if (wt == null || wt.botId != botId) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Worktree not found: $wtId"))
                return@get
            }
            File(wt.worktreePath)
        } else {
            File(bot.cwd)
        }
        val reqPath = call.request.queryParameters["path"]?.trim()?.takeIf { it.isNotEmpty() }
        val rootDir = wtDir
        val safeRelPath = if (reqPath != null) {
            when (val res = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafe(rootDir, reqPath) }) {
                is WorkspacePathGuard.Resolution.Forbidden -> {
                    call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(res.reason))
                    return@get
                }
                is WorkspacePathGuard.Resolution.NotFound -> {
                    val cleaned = reqPath.replace('\\', '/').trimStart('/')
                    if (cleaned.startsWith("-") ||
                        cleaned.startsWith(":") ||
                        WorkspacePathGuard.containsForbiddenSegment(cleaned)
                    ) {
                        call.respond(HttpStatusCode.Forbidden, ApiErrorResponse("Invalid diff path"))
                        return@get
                    }
                    cleaned
                }
                is WorkspacePathGuard.Resolution.Allowed -> res.relativePath
            }
        } else {
            null
        }
        val diffResp = withContext(Dispatchers.IO) {
            val targetSpec = safeRelPath ?: "."
            val args = listOf("diff", "--no-ext-diff", "--no-textconv", "HEAD", "--", targetSpec)
            var (code, out) = WorkspacePathGuard.runGit(rootDir, args)
            if (code != 0) {
                val fallbackArgs = listOf("diff", "--no-ext-diff", "--no-textconv", "--", targetSpec)
                val fallback = WorkspacePathGuard.runGit(rootDir, fallbackArgs)
                out = fallback.second
            }
            val truncated = out.length > WorkspacePathGuard.MAX_DIFF_CHARS
            val text = if (truncated) out.substring(0, WorkspacePathGuard.MAX_DIFF_CHARS) else out
            GitDiffResponse(path = safeRelPath, diff = text, truncated = truncated)
        }
        call.respond(diffResp)
    }
}

private const val MAX_RAW_SERVE_BYTES = 50L * 1024L * 1024L
private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
private const val ATTACHMENTS_DIR_NAME = ".superpi-attachments"
private val ALLOWED_HOME_ARTIFACT_SUBDIRS = setOf("remote-artifacts", "artifacts", "attachments")

internal suspend fun respondSafeRawBytes(
    call: ApplicationCall,
    bytes: ByteArray,
    fileName: String,
    download: Boolean,
) {
    val mime = guessMimeForFileName(fileName)
    call.response.header("X-Content-Type-Options", "nosniff")
    if (mime == "text/html") {
        call.response.header(
            "Content-Security-Policy",
            "sandbox allow-scripts; default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; form-action 'none';",
        )
    } else if (mime == "image/svg+xml" || mime == "application/xml" || mime == "text/xml") {
        call.response.header(
            "Content-Security-Policy",
            "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline';",
        )
    }
    if (download) {
        val safeName = sanitizeAttachmentFileName(fileName)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                safeName,
            ).toString(),
        )
    }
    val contentType = runCatching { ContentType.parse(mime) }.getOrDefault(ContentType.Application.OctetStream)
    call.respondBytes(bytes = bytes, contentType = contentType)
}

private suspend fun resolveAuthorizedArtifactFile(
    controller: SuperPiController,
    sessionId: String,
    rawPath: String,
): File? {
    val candidateFile = File(rawPath)
    val sessionCwd = when {
        sessionId == "super-main" -> controller.botRegistry.homeBot().cwd
        sessionId.startsWith("worker:") -> {
            val workerId = sessionId.removePrefix("worker:")
            val worker = if (controller.isSuperOrchestratorInitialized) {
                controller.superOrchestrator.workers.value.firstOrNull { it.workerId == workerId }
            } else null
            worker?.let { controller.botRegistry.findBotById(it.botId)?.cwd }
        }
        else -> runCatching { controller.runtimeHost.sessionCwd(sessionId) }.getOrNull()
    }

    val resolvedFile = if (candidateFile.isAbsolute) {
        candidateFile
    } else if (!sessionCwd.isNullOrBlank() && !controller.isRemoteCwd(sessionCwd)) {
        File(sessionCwd, rawPath)
    } else {
        return null
    }
    if (!resolvedFile.exists() || !resolvedFile.isFile) return null
    val targetReal = runCatching { resolvedFile.toPath().toRealPath() }.getOrNull() ?: return null
    if (WorkspacePathGuard.containsForbiddenSegment(targetReal.toString())) return null

    // 1. Inside session's local workspace root
    if (!sessionCwd.isNullOrBlank() && !controller.isRemoteCwd(sessionCwd)) {
        val cwdReal = runCatching { File(sessionCwd).toPath().toRealPath() }.getOrNull()
        if (cwdReal != null && targetReal.startsWith(cwdReal)) {
            return targetReal.toFile()
        }
    }

    // 2. Inside allowed artifact subdirectories of ~/.super-pi (never exposing miniapps/userdata/*.db!)
    val homeReal = runCatching { controller.paths.homeDir.toPath().toRealPath() }.getOrNull()
    if (homeReal != null && targetReal.startsWith(homeReal)) {
        val rel = homeReal.relativize(targetReal).toString().replace('\\', '/')
        val firstSeg = rel.substringBefore('/')
        if (firstSeg in ALLOWED_HOME_ARTIFACT_SUBDIRS || rel.startsWith("miniapps/apps/")) {
            return targetReal.toFile()
        }
    }

    // 3. Explicitly referenced by a completed `present` tool result in this session/super/worker history
    val messages: List<AssistantMessage> = when {
        sessionId == "super-main" && controller.isSuperOrchestratorInitialized ->
            controller.superOrchestrator.threadState.value.messages
        sessionId.startsWith("worker:") && controller.isSuperOrchestratorInitialized -> {
            val workerId = sessionId.removePrefix("worker:")
            controller.superOrchestrator.observeWorkerSession(workerId).value.messages
        }
        else -> runCatching { controller.runtimeHost.history(sessionId) }.getOrDefault(emptyList())
    }
    val presentedPaths = extractPresentedPaths(messages)
    val allowedByPresent = presentedPaths.any { presented ->
        val pReal = runCatching { File(presented).toPath().toRealPath() }.getOrNull()
        pReal != null && pReal == targetReal
    }
    return if (allowedByPresent) targetReal.toFile() else null
}

private fun extractPresentedPaths(messages: List<AssistantMessage>): Set<String> {
    val result = LinkedHashSet<String>()
    for (msg in messages) {
        for (step in msg.steps) {
            val trace = (step as? AssistantStep.Tool)?.trace ?: continue
            if (trace.name != "present") continue
            val output = trace.output?.takeIf { it.isNotBlank() } ?: continue
            val root = runCatching { Json.parseToJsonElement(output) as? JsonObject }.getOrNull() ?: continue
            val items = root["items"] as? JsonArray ?: continue
            for (el in items) {
                val obj = el as? JsonObject ?: continue
                val p = (obj["path"] as? JsonPrimitive)?.contentOrNull?.trim()
                if (!p.isNullOrEmpty()) result += p
            }
        }
    }
    return result
}

internal fun sanitizeAttachmentFileName(raw: String): String {
    val normalized = raw.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_', '.')
    return normalized.take(96).ifBlank { "attachment" }
}

private fun deleteSafeRecursively(target: Path, rootReal: Path) {
    if (Files.isSymbolicLink(target)) {
        Files.deleteIfExists(target)
        return
    }
    val real = target.toRealPath()
    if (!real.startsWith(rootReal) || real == rootReal) {
        throw SecurityException("Delete target escapes workspace root")
    }
    if (Files.isDirectory(real)) {
        Files.list(real).use { stream ->
            for (child in stream) {
                deleteSafeRecursively(child, rootReal)
            }
        }
    }
    Files.deleteIfExists(real)
}

private val SEARCH_SKIP_DIRS = setOf(".git", "node_modules", ".superpi-attachments", ".gradle", "build", "target", "dist")

private fun searchLocalWorkspace(rootReal: Path, startDir: Path, query: String): List<WorkspaceFileEntryDto> {
    val needle = query.lowercase(Locale.US)
    val matches = ArrayList<WorkspaceFileEntryDto>()
    val queue = ArrayDeque<Path>()
    queue.add(startDir)
    var visitedDirs = 0
    while (queue.isNotEmpty() && matches.size < 200 && visitedDirs < 2000) {
        val dir = queue.removeFirst()
        visitedDirs++
        val children = dir.toFile().listFiles() ?: continue
        for (child in children) {
            val name = child.name
            if (WorkspacePathGuard.isDotGitSegment(name)) continue
            if (Files.isSymbolicLink(child.toPath())) continue
            val childReal = runCatching { child.toPath().toRealPath() }.getOrNull() ?: continue
            if (!childReal.startsWith(rootReal)) continue
            val rel = rootReal.relativize(childReal).toString().replace('\\', '/')
            if (WorkspacePathGuard.containsForbiddenSegment(rel)) continue
            if (name.lowercase(Locale.US).contains(needle) || rel.lowercase(Locale.US).contains(needle)) {
                matches.add(
                    WorkspaceFileEntryDto(
                        name = name,
                        path = rel,
                        isDirectory = child.isDirectory,
                        sizeBytes = if (child.isFile) child.length() else 0L,
                        modifiedAt = child.lastModified(),
                    ),
                )
                if (matches.size >= 200) break
            }
            if (child.isDirectory && name !in SEARCH_SKIP_DIRS) {
                queue.add(childReal)
            }
        }
    }
    return matches.sortedWith(compareByDescending<WorkspaceFileEntryDto> { it.isDirectory }.thenBy { it.path.lowercase() })
}

internal fun classifyAttachmentKind(mime: String, name: String): String {
    if (mime.startsWith("image/")) return "image"
    if (mime.startsWith("text/")) return "text"
    if (mime in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")) {
        return "text"
    }
    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "avif" -> "image"
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "kt", "kts", "java", "py", "js",
        "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "css", "html", "htm", "sh", "bash", "sql", "rs",
        "go", "swift", "rb", "log", "csv", "ini", "conf" -> "text"
        else -> "other"
    }
}

internal fun guessMimeForFileName(name: String): String =
    when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        "mp4" -> "video/mp4"
        "m4v" -> "video/x-m4v"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "yaml", "yml" -> "application/x-yaml"
        "toml" -> "application/toml"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

private val REMOTE_FOLDER_IGNORED_DIRS = setOf(
    ".git",
    "node_modules",
    "build",
    "dist",
    ".gradle",
    ".idea",
    ".venv",
    "__pycache__",
    "target",
    ".next",
    ".nuxt",
)

private const val MAX_REMOTE_FOLDER_FILES = 2000

private fun zipFolderForRemote(rootDir: File, maxCompressedBytes: Int): ByteArray {
    val baos = ByteArrayOutputStream()
    var fileCount = 0
    val rootPath = rootDir.toPath().toRealPath()
    ZipOutputStream(baos).use { zos ->
        fun walk(dir: File) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    if (child.name in REMOTE_FOLDER_IGNORED_DIRS) continue
                    walk(child)
                } else if (child.isFile) {
                    fileCount++
                    if (fileCount > MAX_REMOTE_FOLDER_FILES) {
                        throw IllegalStateException("Folder contains more than $MAX_REMOTE_FOLDER_FILES files; select a smaller subfolder for remote upload")
                    }
                    val rel = rootPath.relativize(child.toPath().toRealPath()).toString().replace('\\', '/')
                    zos.putNextEntry(ZipEntry(rel))
                    child.inputStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            zos.write(buf, 0, read)
                            if (baos.size() > maxCompressedBytes) {
                                throw IllegalStateException("Packaged folder exceeds 25 MB limit for remote transfer")
                            }
                        }
                    }
                    zos.closeEntry()
                    if (baos.size() > maxCompressedBytes) {
                        throw IllegalStateException("Packaged folder exceeds 25 MB limit for remote transfer")
                    }
                }
            }
        }
        walk(rootDir)
    }
    if (fileCount == 0) {
        throw IllegalStateException("Folder has no files to transfer")
    }
    return baos.toByteArray()
}

internal fun writeBytesAtomically(targetFile: File, bytes: ByteArray) {
    val parent = targetFile.parentFile ?: error("Target file has no parent: ${targetFile.path}")
    parent.mkdirs()
    val tmpPath = Files.createTempFile(parent.toPath(), ".tmp-", ".part")
    try {
        tmpPath.toFile().writeBytes(bytes)
        try {
            Files.move(
                tmpPath,
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpPath, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        runCatching { Files.deleteIfExists(tmpPath) }
    }
}
