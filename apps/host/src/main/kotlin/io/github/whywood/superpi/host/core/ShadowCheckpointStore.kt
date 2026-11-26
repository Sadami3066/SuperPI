package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.host.server.WorkspacePathGuard
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CheckpointRecord(
    val id: String,
    val sessionId: String,
    val turnIndex: Int,
    val label: String,
    val treeSha: String,
    val messageCount: Int,
    val workspacePath: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class CheckpointRestoreResult(
    val ok: Boolean,
    val message: String,
    val backupCheckpointId: String? = null,
    val restoredFiles: Int = 0,
)

/**
 * Captures per-turn workspace snapshots in an isolated shadow bare Git repository under
 * `<homeDir>/checkpoints/<sessionId>/repo.git` using `GIT_DIR` + `GIT_WORK_TREE`.
 * Never touches or pollutes the user's real `.git` history or `git status`.
 */
class ShadowCheckpointStore(
    private val checkpointsRoot: File,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(sessionId: String): Mutex = sessionLocks.computeIfAbsent(sessionId) { Mutex() }

    private fun sessionDir(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "default" }
        return File(checkpointsRoot, safe)
    }

    suspend fun list(sessionId: String): List<CheckpointRecord> = withContext(Dispatchers.IO) {
        val sDir = sessionDir(sessionId)
        if (!sDir.isDirectory) return@withContext emptyList()
        lockFor(sessionId).withLock {
            readIndexLocked(sDir)
        }
    }

    suspend fun capture(
        sessionId: String,
        workspaceDir: File,
        turnIndex: Int,
        label: String,
        messageCount: Int,
    ): CheckpointRecord? = withContext(Dispatchers.IO) {
        if (!workspaceDir.isDirectory) return@withContext null
        val wsReal = runCatching { workspaceDir.toPath().toRealPath() }.getOrNull() ?: return@withContext null
        // Never snapshot a directory that contains checkpointsRoot (e.g. $HOME) to prevent recursive secret capture.
        val rootReal = runCatching {
            checkpointsRoot.mkdirs()
            checkpointsRoot.toPath().toRealPath()
        }.getOrNull()
        if (rootReal != null && rootReal.startsWith(wsReal)) {
            return@withContext null
        }
        lockFor(sessionId).withLock {
            captureLocked(sessionId, wsReal.toFile(), turnIndex, label, messageCount)
        }
    }

    suspend fun restoreFiles(
        sessionId: String,
        checkpointId: String,
    ): CheckpointRestoreResult = withContext(Dispatchers.IO) {
        lockFor(sessionId).withLock {
            val sDir = sessionDir(sessionId)
            val all = readIndexLocked(sDir)
            val target = all.firstOrNull { it.id == checkpointId }
                ?: return@withLock CheckpointRestoreResult(false, "Checkpoint not found: $checkpointId")
            val wsDir = File(target.workspacePath)
            if (!wsDir.isDirectory) {
                return@withLock CheckpointRestoreResult(false, "Workspace directory no longer exists")
            }
            val wsReal = wsDir.toPath().toRealPath()
            val repoDir = File(sDir, "repo.git")
            if (!repoDir.isDirectory) {
                return@withLock CheckpointRestoreResult(false, "Checkpoint repository missing")
            }

            // 1. Capture safety backup before touching any file — abort if backup cannot be created!
            val backup = captureLocked(
                sessionId = sessionId,
                wsReal = wsDir,
                turnIndex = (all.maxOfOrNull { it.turnIndex } ?: 0) + 1,
                label = "Safety backup before restore",
                messageCount = target.messageCount,
            ) ?: return@withLock CheckpointRestoreResult(
                ok = false,
                message = "Unable to create safety backup before restore; aborted to protect current files.",
            )

            // 2. Find files created after target checkpoint using NUL-delimited diff-tree without silent truncation.
            val currentTree = backup.treeSha
            var removedCount = 0
            if (currentTree != target.treeSha) {
                val (diffCode, diffOut, truncated) = runShadowGit(
                    repoDir = repoDir,
                    workTree = wsDir,
                    args = listOf("diff-tree", "-r", "-z", "--name-only", "--diff-filter=A", target.treeSha, currentTree),
                    maxChars = MAX_DIFF_TREE_CHARS,
                )
                if (diffCode != 0 || truncated) {
                    return@withLock CheckpointRestoreResult(
                        ok = false,
                        message = "Failed to enumerate post-checkpoint files safely; restore aborted.",
                        backupCheckpointId = backup.id,
                    )
                }
                val addedPaths = diffOut.split('\u0000').filter { it.isNotEmpty() }
                for (rel in addedPaths) {
                    if (WorkspacePathGuard.containsForbiddenSegment(rel)) continue
                    val candidate = wsReal.resolve(rel).normalize()
                    if (!candidate.startsWith(wsReal) || candidate == wsReal) continue
                    if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
                        val realCand = runCatching { candidate.toRealPath() }.getOrNull() ?: continue
                        if (realCand.startsWith(wsReal) && runCatching { Files.deleteIfExists(realCand) }.getOrDefault(false)) {
                            removedCount++
                            pruneEmptyParents(realCand.parent, wsReal)
                        }
                    }
                }
            }

            // 3. Reset shadow index to target tree and materialize files into workspace.
            clearStaleIndexLock(repoDir)
            val (rtCode, rtOut, _) = runShadowGit(
                repoDir = repoDir,
                workTree = wsDir,
                args = listOf("read-tree", "--reset", target.treeSha),
                timeoutMs = 15_000L,
            )
            if (rtCode != 0) {
                return@withLock CheckpointRestoreResult(
                    ok = false,
                    message = "Failed to read checkpoint tree: ${rtOut.trim()}",
                    backupCheckpointId = backup.id,
                )
            }
            val (coCode, coOut, _) = runShadowGit(
                repoDir = repoDir,
                workTree = wsDir,
                args = listOf("checkout-index", "-a", "-f"),
                timeoutMs = 15_000L,
            )
            if (coCode != 0) {
                return@withLock CheckpointRestoreResult(
                    ok = false,
                    message = "Failed to restore files: ${coOut.trim()}",
                    backupCheckpointId = backup.id,
                )
            }

            val (_, lsOut, _) = runShadowGit(repoDir, wsDir, listOf("ls-tree", "-r", "--name-only", target.treeSha))
            val restoredFiles = lsOut.lines().count { it.isNotBlank() } + removedCount
            CheckpointRestoreResult(
                ok = true,
                message = "Workspace files restored to checkpoint.",
                backupCheckpointId = backup.id,
                restoredFiles = restoredFiles,
            )
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val lock = sessionLocks[sessionId]
        val performDelete = {
            val sDir = sessionDir(sessionId)
            val rootReal = runCatching { checkpointsRoot.toPath().toRealPath() }.getOrNull()
            val dirReal = runCatching { sDir.toPath().toRealPath() }.getOrNull()
            if (rootReal != null && dirReal != null && dirReal.startsWith(rootReal) && dirReal != rootReal) {
                runCatching { sDir.deleteRecursively() }
            }
        }
        if (lock != null) {
            lock.withLock { performDelete() }
            sessionLocks.remove(sessionId, lock)
        } else {
            performDelete()
        }
    }

    private fun pruneEmptyParents(startDir: java.nio.file.Path?, wsReal: java.nio.file.Path) {
        var cur = startDir
        while (cur != null && cur != wsReal && cur.startsWith(wsReal)) {
            val empty = runCatching {
                Files.list(cur).use { s -> !s.findFirst().isPresent }
            }.getOrDefault(false)
            if (!empty) break
            if (!runCatching { Files.deleteIfExists(cur) }.getOrDefault(false)) break
            cur = cur.parent
        }
    }

    private fun clearStaleIndexLock(repoDir: File) {
        val lockFile = File(repoDir, "index.lock")
        if (lockFile.isFile) {
            runCatching { lockFile.delete() }
        }
    }

    private suspend fun captureLocked(
        sessionId: String,
        wsReal: File,
        turnIndex: Int,
        label: String,
        messageCount: Int,
    ): CheckpointRecord? {
        val sDir = sessionDir(sessionId)
        val repoDir = File(sDir, "repo.git")
        if (!ensureShadowRepo(repoDir)) return null
        clearStaleIndexLock(repoDir)

        val (addCode, _, _) = runShadowGit(
            repoDir = repoDir,
            workTree = wsReal,
            args = listOf("add", "-A", "--", "."),
            timeoutMs = 15_000L,
        )
        if (addCode != 0) return null

        val (treeCode, treeOut, _) = runShadowGit(
            repoDir = repoDir,
            workTree = wsReal,
            args = listOf("write-tree"),
        )
        val treeSha = treeOut.trim()
        if (treeCode != 0 || treeSha.length < 7) return null

        val record = CheckpointRecord(
            id = "cp-${UUID.randomUUID().toString().replace("-", "").take(10)}",
            sessionId = sessionId,
            turnIndex = turnIndex,
            label = label.trim().take(80).ifEmpty { "Turn $turnIndex" },
            treeSha = treeSha,
            messageCount = messageCount,
            workspacePath = wsReal.absolutePath,
            createdAt = System.currentTimeMillis(),
        )
        val all = readIndexLocked(sDir).toMutableList()
        all.add(record)
        val trimmed = if (all.size > MAX_CHECKPOINTS_PER_SESSION) {
            all.takeLast(MAX_CHECKPOINTS_PER_SESSION)
        } else {
            all
        }
        writeIndexLocked(sDir, trimmed)
        return record
    }

    private suspend fun ensureShadowRepo(repoDir: File): Boolean {
        if (!File(repoDir, "HEAD").isFile) {
            repoDir.mkdirs()
            val (code, _, _) = runPlainGit(listOf("init", "--bare", repoDir.absolutePath))
            if (code != 0) return false
        }
        val infoDir = File(repoDir, "info").apply { mkdirs() }
        val excludeFile = File(infoDir, "exclude")
        // Always ensure .super-pi/ is excluded even for pre-existing shadow repos
        if (!excludeFile.isFile || !excludeFile.readText(Charsets.UTF_8).contains(".super-pi/")) {
            excludeFile.writeText(DEFAULT_SHADOW_EXCLUDES, Charsets.UTF_8)
        }
        return true
    }

    private fun readIndexLocked(sDir: File): List<CheckpointRecord> {
        val indexFile = File(sDir, "index.json")
        if (!indexFile.isFile) return emptyList()
        val raw = runCatching { indexFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(CheckpointRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeIndexLocked(sDir: File, records: List<CheckpointRecord>) {
        sDir.mkdirs()
        val indexFile = File(sDir, "index.json")
        val tmp = Files.createTempFile(sDir.toPath(), ".cp-index.", ".tmp")
        try {
            tmp.toFile().writeText(
                json.encodeToString(ListSerializer(CheckpointRecord.serializer()), records) + "\n",
                Charsets.UTF_8,
            )
            try {
                Files.move(tmp, indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private suspend fun runPlainGit(args: List<String>, timeoutMs: Long = 5_000L): Triple<Int, String, Boolean> {
        val proc = try {
            ProcessBuilder(listOf("git") + args)
                .redirectErrorStream(true)
                .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        } catch (_: IOException) {
            return Triple(-1, "", false)
        }
        return collectProcess(proc, timeoutMs, maxChars = 131_072)
    }

    private suspend fun runShadowGit(
        repoDir: File,
        workTree: File,
        args: List<String>,
        timeoutMs: Long = 8_000L,
        maxChars: Int = 131_072,
    ): Triple<Int, String, Boolean> {
        val cmd = listOf(
            "git",
            "--literal-pathspecs",
            "-c", "core.fsmonitor=false",
            "-c", "core.autocrlf=false",
            "-c", "core.quotePath=false",
            "--git-dir=${repoDir.absolutePath}",
            "--work-tree=${workTree.absolutePath}",
        ) + args
        val proc = try {
            ProcessBuilder(cmd)
                .directory(workTree)
                .redirectErrorStream(true)
                .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        } catch (_: IOException) {
            return Triple(-1, "", false)
        }
        return collectProcess(proc, timeoutMs, maxChars = maxChars)
    }

    private suspend fun collectProcess(proc: Process, timeoutMs: Long, maxChars: Int): Triple<Int, String, Boolean> {
        runCatching { proc.outputStream.close() }
        val bufLock = Any()
        val buf = StringBuilder()
        var truncated = false
        val reader = thread(isDaemon = true, name = "superpi-cp-git") {
            try {
                proc.inputStream.reader(Charsets.UTF_8).use { r ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n < 0) break
                        synchronized(bufLock) {
                            val rem = maxChars - buf.length
                            if (rem > 0) {
                                buf.append(chunk, 0, minOf(n, rem))
                                if (n > rem) truncated = true
                            } else {
                                truncated = true
                            }
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }
        return try {
            val finished = runInterruptible(Dispatchers.IO) {
                proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!finished) {
                proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                reader.join(500)
                Triple(-1, "timeout", true)
            } else {
                reader.join(1_000)
                synchronized(bufLock) { Triple(proc.exitValue(), buf.toString(), truncated) }
            }
        } catch (cancelled: CancellationException) {
            proc.destroyForcibly()
            runCatching { proc.inputStream.close() }
            throw cancelled
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            runCatching { proc.inputStream.close() }
        }
    }

    companion object {
        private const val MAX_CHECKPOINTS_PER_SESSION = 50
        private const val MAX_DIFF_TREE_CHARS = 4 * 1024 * 1024
        private val DEFAULT_SHADOW_EXCLUDES = """
            .git/
            .git
            .super-pi/
            node_modules/
            build/
            target/
            .gradle/
            dist/
            .next/
            .superpi-attachments/
            *.hprof
        """.trimIndent() + "\n"
    }
}
