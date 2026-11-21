package io.github.whywood.superpi.data.relay

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class RemoteFileException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

data class RemoteFileChunkReadResult(
    val path: String,
    val offset: Long,
    val data: String,
    val size: Long,
    val eof: Boolean,
    val mimeType: String? = null,
    val binary: Boolean,
)

data class RemoteFileWriteChunkInput(
    val path: String,
    val transferId: String? = null,
    val offset: Long,
    val data: String,
    val eof: Boolean,
    val overwrite: Boolean? = null,
    val expectedSha256: String? = null,
)

data class RemoteFileWriteChunkResult(
    val transferId: String,
    val written: Long,
    val eof: Boolean,
    val size: Long? = null,
    val sha256: String? = null,
)

class RemoteWorkspaceFileHandler(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val uploads = LinkedHashMap<String, ActiveUpload>()

    private data class ActiveUpload(
        val id: String,
        val cwdReal: File,
        val relPath: String,
        val tmpFile: File,
        val targetFile: File,
        var written: Long,
        val overwrite: Boolean,
        val digest: MessageDigest,
        var updatedAt: Long,
    )

    fun list(
        cwd: String,
        relPath: String = "",
        query: String? = null,
        limit: Int = DEFAULT_LIST_LIMIT,
    ): Pair<String, List<RemoteFileEntryDto>> {
        val rootReal = requireWorkspaceRoot(cwd)
        val safeRel = parseRelPath(relPath, allowEmpty = true)
        val dirFile = resolveExistingInsideRoot(rootReal, safeRel)
        if (!dirFile.exists()) {
            throw RemoteFileException("not_found", "Directory not found: ${safeRel.ifEmpty { "." }}")
        }
        if (!dirFile.isDirectory) {
            throw RemoteFileException("bad_request", "Not a directory: ${safeRel.ifEmpty { "." }}")
        }
        val cleanQuery = query?.trim()?.lowercase().orEmpty()
        val effectiveLimit = limit.coerceIn(1, DEFAULT_LIST_LIMIT)
        if (cleanQuery.isNotEmpty()) {
            return safeRel to searchEntriesInsideRoot(rootReal, dirFile, cleanQuery, effectiveLimit)
        }
        val children = dirFile.listFiles()
            ?: throw RemoteFileException("permission_denied", "Cannot read workspace directory")
        val entries = children.asSequence()
            .filter { file ->
                val name = file.name
                name != ".git" && name != ".DS_Store" && !isReservedTempName(name)
            }
            .mapNotNull { file -> toRemoteEntry(rootReal, file, file.name) }
            .sortedWith(compareBy<RemoteFileEntryDto> { if (it.type == "directory") 0 else 1 }.thenBy { it.name })
            .take(effectiveLimit)
            .toList()
        return safeRel to entries
    }

    fun stat(cwd: String, relPath: String): RemoteFileEntryDto {
        val rootReal = requireWorkspaceRoot(cwd)
        val safeRel = parseRelPath(relPath, allowEmpty = false)
        assertNotReservedTemp(safeRel)
        val target = resolveExistingInsideRoot(rootReal, safeRel)
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) {
            throw RemoteFileException("not_found", "Path not found: $safeRel")
        }
        return toRemoteEntry(rootReal, target, safeRel.substringAfterLast('/'))
            ?: throw RemoteFileException("bad_request", "Path points outside workspace boundary")
    }

    fun readChunk(cwd: String, relPath: String, offset: Long, length: Int): RemoteFileChunkReadResult {
        val rootReal = requireWorkspaceRoot(cwd)
        val safeRel = parseRelPath(relPath, allowEmpty = false)
        assertNotReservedTemp(safeRel)
        if (offset < 0L) {
            throw RemoteFileException("bad_request", "offset must be a non-negative integer")
        }
        if (length < 1 || length > RelayFrames.MAX_FILE_CHUNK_BYTES) {
            val code = if (length > RelayFrames.MAX_FILE_CHUNK_BYTES) "payload_too_large" else "bad_request"
            throw RemoteFileException(code, "length must be between 1 and ${RelayFrames.MAX_FILE_CHUNK_BYTES}")
        }
        val target = resolveExistingInsideRoot(rootReal, safeRel)
        if (!target.exists()) {
            throw RemoteFileException("not_found", "File not found: $safeRel")
        }
        if (!target.isFile) {
            throw RemoteFileException("bad_request", "Path is not a file: $safeRel")
        }
        val size = target.length()
        if (offset > size) {
            throw RemoteFileException("bad_request", "offset exceeds file size")
        }
        val toRead = minOf(length.toLong(), maxOf(0L, size - offset)).toInt()
        val slice = if (toRead > 0) {
            RandomAccessFile(target, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(toRead)
                var readTotal = 0
                while (readTotal < toRead) {
                    val count = raf.read(buf, readTotal, toRead - readTotal)
                    if (count <= 0) break
                    readTotal += count
                }
                if (readTotal == toRead) buf else buf.copyOf(readTotal)
            }
        } else {
            ByteArray(0)
        }
        val binary = slice.any { it == 0.toByte() }
        val eof = offset + slice.size >= size
        return RemoteFileChunkReadResult(
            path = safeRel,
            offset = offset,
            data = Base64.getEncoder().encodeToString(slice),
            size = size,
            eof = eof,
            mimeType = guessMimeType(safeRel),
            binary = binary,
        )
    }

    @Synchronized
    fun writeChunk(cwd: String, input: RemoteFileWriteChunkInput): RemoteFileWriteChunkResult {
        pruneStaleUploadsLocked()
        val rootReal = requireWorkspaceRoot(cwd)
        val safeRel = parseRelPath(input.path, allowEmpty = false)
        assertNotReservedTemp(safeRel)
        if (input.offset < 0L) {
            throw RemoteFileException("bad_request", "offset must be a non-negative integer")
        }
        val chunk = decodeBase64Chunk(input.data, input.eof)
        if (chunk.size > RelayFrames.MAX_FILE_CHUNK_BYTES) {
            throw RemoteFileException("payload_too_large", "chunk exceeds maximum byte size")
        }

        val upload = if (input.offset == 0L) {
            val parentRel = if ('/' in safeRel) safeRel.substringBeforeLast('/') else ""
            val parentReal = ensureDirectoryInsideRoot(rootReal, parentRel)
            val baseName = safeRel.substringAfterLast('/')
            val targetFile = File(parentReal, baseName)
            if (Files.isSymbolicLink(targetFile.toPath())) {
                val targetReal = canonicalOrNull(targetFile)
                if (targetReal == null || !isInsideRoot(rootReal, targetReal)) {
                    throw RemoteFileException("bad_request", "Symlink target is outside workspace boundary")
                }
            }
            if (targetFile.isDirectory) {
                throw RemoteFileException("bad_request", "Target path is a directory")
            }
            if (input.overwrite == false && (targetFile.exists() || Files.isSymbolicLink(targetFile.toPath()))) {
                throw RemoteFileException("already_exists", "File already exists: $safeRel")
            }
            val transferId = input.transferId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
            val existing = uploads[transferId]
            if (existing != null) {
                if (existing.cwdReal != rootReal || existing.relPath != safeRel) {
                    throw RemoteFileException("conflict", "transferId is already in use for another file")
                }
                cleanupUpload(existing)
                uploads.remove(transferId)
            }
            val safeToken = transferId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty {
                UUID.randomUUID().toString()
            }
            val tmpFile = File(parentReal, "$UPLOAD_TMP_PREFIX$safeToken$UPLOAD_TMP_SUFFIX")
            FileOutputStream(tmpFile, false).close()
            ActiveUpload(
                id = transferId,
                cwdReal = rootReal,
                relPath = safeRel,
                tmpFile = tmpFile,
                targetFile = targetFile,
                written = 0L,
                overwrite = input.overwrite != false,
                digest = MessageDigest.getInstance("SHA-256"),
                updatedAt = clock(),
            ).also { uploads[transferId] = it }
        } else {
            val transferId = input.transferId?.trim().orEmpty()
            if (transferId.isEmpty()) {
                throw RemoteFileException("bad_request", "transferId is required for non-zero offset")
            }
            val existing = uploads[transferId]
            if (existing == null || existing.cwdReal != rootReal || existing.relPath != safeRel) {
                throw RemoteFileException("not_found", "Upload session not found: $transferId")
            }
            if (input.offset != existing.written) {
                throw RemoteFileException(
                    "conflict",
                    "Unexpected chunk offset ${input.offset}; expected ${existing.written}",
                )
            }
            existing
        }

        if (upload.written + chunk.size > RelayFrames.MAX_FILE_TRANSFER_BYTES) {
            uploads.remove(upload.id)
            cleanupUpload(upload)
            throw RemoteFileException("payload_too_large", "file exceeds maximum transfer size")
        }

        try {
            if (chunk.isNotEmpty()) {
                FileOutputStream(upload.tmpFile, true).use { output ->
                    output.write(chunk)
                }
                upload.digest.update(chunk)
                upload.written += chunk.size
            }
            upload.updatedAt = clock()
        } catch (failure: Exception) {
            uploads.remove(upload.id)
            cleanupUpload(upload)
            throw failure
        }

        if (!input.eof) {
            return RemoteFileWriteChunkResult(
                transferId = upload.id,
                written = upload.written,
                eof = false,
            )
        }

        uploads.remove(upload.id)
        val sha256 = upload.digest.digest().joinToString("") { "%02x".format(it) }
        val expected = input.expectedSha256?.trim()?.lowercase()
        if (!expected.isNullOrEmpty() && sha256 != expected) {
            cleanupUpload(upload)
            throw RemoteFileException("bad_request", "Uploaded file SHA-256 verification failed")
        }

        val parentReal = upload.targetFile.parentFile?.let(::canonicalOrNull)
        if (parentReal == null || !isInsideRootOrEqual(rootReal, parentReal)) {
            cleanupUpload(upload)
            throw RemoteFileException("bad_request", "Target directory escaped workspace boundary")
        }
        if (!upload.overwrite && (upload.targetFile.exists() || Files.isSymbolicLink(upload.targetFile.toPath()))) {
            cleanupUpload(upload)
            throw RemoteFileException("already_exists", "File already exists: $safeRel")
        }

        try {
            moveReplacing(upload.tmpFile, upload.targetFile)
            return RemoteFileWriteChunkResult(
                transferId = upload.id,
                written = upload.written,
                eof = true,
                size = upload.targetFile.length(),
                sha256 = sha256,
            )
        } catch (failure: Exception) {
            cleanupUpload(upload)
            throw failure
        }
    }

    fun mkdir(cwd: String, relPath: String): Pair<String, Boolean> {
        val rootReal = requireWorkspaceRoot(cwd)
        val safeRel = parseRelPath(relPath, allowEmpty = false)
        assertNotReservedTemp(safeRel)
        val candidate = File(rootReal, safeRel.replace('/', File.separatorChar))
        if (Files.isSymbolicLink(candidate.toPath())) {
            val real = canonicalOrNull(candidate)
            if (real == null || !isInsideRoot(rootReal, real)) {
                throw RemoteFileException("bad_request", "Symlink target is outside workspace boundary")
            }
        }
        if (candidate.isFile) {
            throw RemoteFileException("already_exists", "A file already exists at $safeRel")
        }
        if (candidate.isDirectory) {
            return safeRel to false
        }
        ensureDirectoryInsideRoot(rootReal, safeRel)
        return safeRel to true
    }

    fun rename(cwd: String, fromRel: String, toRel: String): Pair<String, String> {
        val rootReal = requireWorkspaceRoot(cwd)
        val safeFrom = parseRelPath(fromRel, allowEmpty = false)
        val safeTo = parseRelPath(toRel, allowEmpty = false)
        assertNotReservedTemp(safeFrom)
        assertNotReservedTemp(safeTo)
        val sourceFile = resolveExistingInsideRoot(rootReal, safeFrom)
        if (!sourceFile.exists() && !Files.isSymbolicLink(sourceFile.toPath())) {
            throw RemoteFileException("not_found", "Source path not found: $safeFrom")
        }
        if (safeFrom == safeTo) {
            return safeFrom to safeTo
        }
        if (safeTo.startsWith("$safeFrom/")) {
            throw RemoteFileException("bad_request", "Cannot move a directory into itself")
        }
        val destParentRel = if ('/' in safeTo) safeTo.substringBeforeLast('/') else ""
        val destParentReal = ensureDirectoryInsideRoot(rootReal, destParentRel)
        val destFile = File(destParentReal, safeTo.substringAfterLast('/'))
        if (destFile.exists() || Files.isSymbolicLink(destFile.toPath())) {
            throw RemoteFileException("already_exists", "Destination already exists: $safeTo")
        }
        Files.move(sourceFile.toPath(), destFile.toPath())
        return safeFrom to safeTo
    }

    fun delete(cwd: String, relPath: String, recursive: Boolean = false): Pair<Boolean, String> {
        val rootReal = requireWorkspaceRoot(cwd)
        val safeRel = parseRelPath(relPath, allowEmpty = false)
        assertNotReservedTemp(safeRel)
        val parentRel = if ('/' in safeRel) safeRel.substringBeforeLast('/') else ""
        val leafName = safeRel.substringAfterLast('/')
        val parentReal = resolveExistingInsideRoot(rootReal, parentRel)
        if (!parentReal.isDirectory) {
            throw RemoteFileException("not_found", "Path not found: $safeRel")
        }
        val target = File(parentReal, leafName)
        if (Files.isSymbolicLink(target.toPath())) {
            Files.deleteIfExists(target.toPath())
            return true to safeRel
        }
        if (!target.exists()) {
            throw RemoteFileException("not_found", "Path not found: $safeRel")
        }
        val targetReal = canonicalOrNull(target)
        if (targetReal == null || !isInsideRoot(rootReal, targetReal)) {
            throw RemoteFileException("bad_request", "Path is outside the workspace boundary")
        }
        if (target.isDirectory) {
            val children = target.list()
            if (!recursive && children != null && children.isNotEmpty()) {
                throw RemoteFileException("bad_request", "Directory is not empty")
            }
            if (recursive) {
                if (!target.deleteRecursively()) {
                    throw RemoteFileException("internal", "Could not delete directory")
                }
            } else if (!target.delete()) {
                throw RemoteFileException("internal", "Could not delete directory")
            }
            return true to safeRel
        }
        if (!target.delete()) {
            throw RemoteFileException("internal", "Could not delete file")
        }
        return true to safeRel
    }

    @Synchronized
    fun dispose() {
        uploads.values.forEach(::cleanupUpload)
        uploads.clear()
    }

    private fun pruneStaleUploadsLocked() {
        val cutoff = clock() - UPLOAD_STALE_MS
        val iterator = uploads.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.updatedAt >= cutoff) continue
            cleanupUpload(entry.value)
            iterator.remove()
        }
    }

    private fun cleanupUpload(upload: ActiveUpload) {
        runCatching {
            if (upload.tmpFile.exists()) upload.tmpFile.delete()
        }
    }

    private fun parseRelPath(raw: String, allowEmpty: Boolean): String = try {
        RelayFrames.validateRelativeWorkspacePath(raw, allowEmpty)
    } catch (failure: IllegalArgumentException) {
        throw RemoteFileException("bad_request", failure.message ?: "Invalid workspace path")
    }

    private fun requireWorkspaceRoot(cwd: String): File {
        val dir = File(cwd)
        if (!dir.isDirectory) {
            throw RemoteFileException("not_found", "Workspace directory is unavailable")
        }
        return canonicalOrNull(dir) ?: throw RemoteFileException("not_found", "Workspace directory is unavailable")
    }

    private fun resolveExistingInsideRoot(rootReal: File, relPath: String): File {
        if (relPath.isEmpty()) return rootReal
        var current = rootReal
        for (segment in relPath.split('/')) {
            val next = File(current, segment)
            if (!next.exists() && !Files.isSymbolicLink(next.toPath())) {
                throw RemoteFileException("not_found", "Path not found: $relPath")
            }
            val real = canonicalOrNull(next)
            if (real == null || !isInsideRoot(rootReal, real)) {
                throw RemoteFileException("bad_request", "Path is outside the workspace boundary")
            }
            current = real
        }
        return current
    }

    private fun ensureDirectoryInsideRoot(rootReal: File, relDir: String): File {
        if (relDir.isEmpty()) return rootReal
        var current = rootReal
        for (segment in relDir.split('/')) {
            val next = File(current, segment)
            if (next.exists() || Files.isSymbolicLink(next.toPath())) {
                val resolved = if (Files.isSymbolicLink(next.toPath())) {
                    val real = canonicalOrNull(next)
                    if (real == null || !isInsideRoot(rootReal, real)) {
                        throw RemoteFileException("bad_request", "Symlink target is outside the workspace boundary")
                    }
                    real
                } else {
                    next
                }
                if (!resolved.isDirectory) {
                    throw RemoteFileException("bad_request", "Expected directory at segment $segment")
                }
                current = resolved
            } else {
                if (!next.mkdir()) {
                    throw RemoteFileException("internal", "Cannot create directory segment $segment")
                }
                val real = canonicalOrNull(next)
                if (real == null || !isInsideRoot(rootReal, real)) {
                    throw RemoteFileException("bad_request", "Created directory escaped workspace boundary")
                }
                current = real
            }
        }
        return current
    }

    private fun searchEntriesInsideRoot(
        rootReal: File,
        startDir: File,
        needle: String,
        limit: Int,
    ): List<RemoteFileEntryDto> {
        val results = ArrayList<RemoteFileEntryDto>()
        val queue = ArrayDeque<File>()
        queue.addLast(startDir)
        var scannedDirs = 0
        val rootPrefix = rootReal.path + File.separator
        while (queue.isNotEmpty() && results.size < limit && scannedDirs < 350) {
            val currentDir = queue.removeFirst()
            scannedDirs += 1
            val children = currentDir.listFiles() ?: continue
            for (child in children) {
                val name = child.name
                if (name in IGNORED_DIRS || name == ".DS_Store" || isReservedTempName(name)) continue
                val real = canonicalOrNull(child) ?: continue
                if (!isInsideRoot(rootReal, real)) continue
                val rel = child.path.removePrefix(rootPrefix).replace(File.separatorChar, '/')
                if (real.isDirectory) {
                    queue.addLast(real)
                } else if (real.isFile && rel.lowercase().contains(needle)) {
                    results += RemoteFileEntryDto(
                        name = name,
                        path = rel,
                        type = "file",
                        size = real.length(),
                        modifiedAt = real.lastModified(),
                        mimeType = guessMimeType(name),
                    )
                    if (results.size >= limit) break
                }
            }
        }
        return results
    }

    private fun toRemoteEntry(rootReal: File, candidate: File, name: String): RemoteFileEntryDto? {
        val real = canonicalOrNull(candidate) ?: return null
        if (!isInsideRoot(rootReal, real)) return null
        val isDir = real.isDirectory
        if (!isDir && !real.isFile) return null
        val rel = candidate.path.removePrefix(rootReal.path + File.separator).replace(File.separatorChar, '/')
        return RemoteFileEntryDto(
            name = name,
            path = rel,
            type = if (isDir) "directory" else "file",
            size = if (isDir) 0L else real.length(),
            modifiedAt = real.lastModified(),
            mimeType = if (isDir) null else guessMimeType(name),
        )
    }

    private fun isInsideRoot(rootReal: File, targetReal: File): Boolean =
        targetReal.path.startsWith(rootReal.path + File.separator)

    private fun isInsideRootOrEqual(rootReal: File, targetReal: File): Boolean =
        targetReal == rootReal || isInsideRoot(rootReal, targetReal)

    private fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun isReservedTempName(name: String): Boolean =
        name.startsWith(UPLOAD_TMP_PREFIX) && name.endsWith(UPLOAD_TMP_SUFFIX)

    private fun assertNotReservedTemp(relPath: String) {
        if (relPath.split('/').any(::isReservedTempName)) {
            throw RemoteFileException("permission_denied", "Reserved temporary upload name is not accessible")
        }
    }

    private fun decodeBase64Chunk(raw: String, eof: Boolean): ByteArray {
        val normalized = raw.replace(Regex("\\s+"), "")
        if (normalized.isEmpty()) {
            if (!eof) throw RemoteFileException("bad_request", "data must not be empty before eof")
            return ByteArray(0)
        }
        if (normalized.length > RelayFrames.MAX_FILE_CHUNK_BASE64_CHARS) {
            throw RemoteFileException("payload_too_large", "chunk exceeds maximum base64 size")
        }
        if (normalized.length % 4 != 0) {
            throw RemoteFileException("bad_request", "data must be valid base64")
        }
        return try {
            Base64.getDecoder().decode(normalized)
        } catch (_: IllegalArgumentException) {
            throw RemoteFileException("bad_request", "data must be valid base64")
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            if (target.exists() && !target.delete()) {
                throw RemoteFileException("conflict", "Cannot overwrite existing destination file")
            }
            if (!source.renameTo(target)) {
                throw RemoteFileException("internal", "Failed to finalize uploaded file")
            }
        }
    }

    private fun guessMimeType(filePath: String): String? =
        when (filePath.substringAfterLast('.', "").lowercase()) {
            "txt", "log" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json", "jsonl" -> "application/json"
            "ts", "tsx" -> "text/typescript"
            "js", "jsx", "mjs", "cjs" -> "text/javascript"
            "kt", "kts" -> "text/x-kotlin"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "xml" -> "application/xml"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> null
        }

    private companion object {
        const val UPLOAD_TMP_PREFIX = ".superpi-upload-"
        const val UPLOAD_TMP_SUFFIX = ".tmp"
        const val UPLOAD_STALE_MS = 15L * 60L * 1000L
        const val DEFAULT_LIST_LIMIT = 500
        val IGNORED_DIRS = setOf(".git", "node_modules", ".gradle", ".kotlin", "build", "dist", ".next", "target", "__pycache__")
    }
}
