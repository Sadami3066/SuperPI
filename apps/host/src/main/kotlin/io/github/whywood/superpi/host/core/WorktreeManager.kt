package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.host.server.WorkspacePathGuard
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class WorktreeRecord(
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
    val createdAt: Long = System.currentTimeMillis(),
)

data class WorktreeFileStat(
    val path: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
)

data class WorktreeDiffResult(
    val worktree: WorktreeRecord,
    val files: List<WorktreeFileStat>,
    val diff: String,
    val truncated: Boolean,
    val totalAdditions: Int,
    val totalDeletions: Int,
    val aheadCommits: Int,
)

data class WorktreeOpResult(
    val ok: Boolean,
    val message: String,
    val conflicts: List<String> = emptyList(),
)

data class OpenEditorResult(
    val ok: Boolean,
    val editor: String,
    val message: String,
)

/**
 * Manages isolated Git worktrees under `<homeDir>/worktrees/<id>/` so parallel bots,
 * Super PI workers, or exploratory chat threads never stomp on the user's active checkout.
 * All git and editor invocations use strict `ProcessBuilder` argument arrays (zero shell interpolation).
 */
class WorktreeManager(
    private val worktreesRoot: File,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val indexFile = File(worktreesRoot, "index.json")
    private val mutex = Mutex()

    suspend fun listAll(): List<WorktreeRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            pruneMissingLocked()
        }
    }

    suspend fun listForBot(botId: String): List<WorktreeRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            pruneMissingLocked().filter { it.botId == botId }
        }
    }

    suspend fun findById(id: String): WorktreeRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            pruneMissingLocked().firstOrNull { it.id == id }
        }
    }

    suspend fun findBySessionId(sessionId: String): WorktreeRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            pruneMissingLocked().firstOrNull { it.sessionId == sessionId }
        }
    }

    suspend fun bindSession(wtId: String, sessionId: String): WorktreeRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readIndexLocked().toMutableList()
            val idx = all.indexOfFirst { it.id == wtId }
            if (idx < 0) return@withLock null
            val updated = all[idx].copy(sessionId = sessionId)
            all[idx] = updated
            writeIndexLocked(all)
            updated
        }
    }

    fun isGitRepo(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val (code, out) = runGit(dir, listOf("rev-parse", "--is-inside-work-tree"))
        return code == 0 && out.trim() == "true"
    }

    suspend fun create(
        botId: String,
        repoDir: File,
        label: String,
        sessionId: String? = null,
        workerId: String? = null,
    ): WorktreeRecord = withContext(Dispatchers.IO) {
        require(repoDir.isDirectory) { "Repository directory does not exist." }
        val (topCode, topOut) = runGit(repoDir, listOf("rev-parse", "--show-toplevel"))
        require(topCode == 0 && topOut.isNotBlank()) { "Directory is not a Git repository." }
        val repoTop = File(topOut.trim()).toPath().toRealPath().toFile()

        val (headCode, headSha) = runGit(repoTop, listOf("rev-parse", "--verify", "HEAD"))
        require(headCode == 0 && headSha.isNotBlank()) {
            "Git repository has no commits yet. Make an initial commit before creating a worktree."
        }
        val (_, baseBranchRaw) = runGit(repoTop, listOf("rev-parse", "--abbrev-ref", "HEAD"))
        val baseBranch = baseBranchRaw.trim().ifBlank { "HEAD" }

        val shortId = UUID.randomUUID().toString().replace("-", "").take(8)
        val slug = sanitizeBranchSlug(label)
        val wtId = "wt-$shortId"
        val branch = "superpi/$slug-$shortId"
        worktreesRoot.mkdirs()
        val wtDir = File(worktreesRoot, wtId)

        val (addCode, addOut) = runGit(
            repoTop,
            listOf("worktree", "add", "-b", branch, wtDir.absolutePath, "HEAD"),
            timeoutMs = 15_000L,
        )
        if (addCode != 0) {
            throw IllegalStateException("Failed to create worktree: ${addOut.trim().ifEmpty { "git exit $addCode" }}")
        }

        val record = WorktreeRecord(
            id = wtId,
            botId = botId,
            repoPath = repoTop.absolutePath,
            worktreePath = wtDir.toPath().toRealPath().toString(),
            branch = branch,
            baseBranch = baseBranch,
            baseCommit = headSha.trim(),
            label = label.trim().ifBlank { slug },
            sessionId = sessionId,
            workerId = workerId,
            createdAt = System.currentTimeMillis(),
        )
        mutex.withLock {
            val all = readIndexLocked().toMutableList()
            all.add(0, record)
            writeIndexLocked(all)
        }
        record
    }

    suspend fun diff(wtId: String): WorktreeDiffResult = withContext(Dispatchers.IO) {
        val record = findById(wtId) ?: throw IllegalArgumentException("Worktree not found: $wtId")
        val wtDir = File(record.worktreePath)
        require(wtDir.isDirectory) { "Worktree directory no longer exists." }

        val (_, aheadOut) = runGit(wtDir, listOf("rev-list", "--count", "${record.baseCommit}..HEAD"))
        val aheadCommits = aheadOut.trim().toIntOrNull() ?: 0

        val (_, statusOut) = runGit(wtDir, listOf("status", "--porcelain=v1", "--", "."))
        val statusMap = LinkedHashMap<String, String>()
        for (line in statusOut.lines()) {
            if (line.length < 4) continue
            val xy = line.substring(0, 2).trim().ifEmpty { "M" }
            val rawPath = line.substring(3).trim()
            val clean = rawPath.substringAfter(" -> ", rawPath).trim('"')
            if (!WorkspacePathGuard.containsForbiddenSegment(clean)) {
                statusMap[clean] = xy
            }
        }

        val (_, numstatOut) = runGit(wtDir, listOf("diff", "--numstat", "--no-ext-diff", record.baseCommit, "--", "."))
        val statsMap = LinkedHashMap<String, Pair<Int, Int>>()
        for (line in numstatOut.lines()) {
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val add = parts[0].toIntOrNull() ?: 0
            val del = parts[1].toIntOrNull() ?: 0
            val path = parts[2].trim()
            if (!WorkspacePathGuard.containsForbiddenSegment(path)) {
                statsMap[path] = add to del
                statusMap.putIfAbsent(path, "M")
            }
        }

        val files = statusMap.entries.map { (path, st) ->
            val (add, del) = statsMap[path] ?: run {
                if (st.contains("?")) {
                    val f = File(wtDir, path)
                    val isSafeRegular = !java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isFile && f.length() <= 256 * 1024
                    val lines = if (isSafeRegular) {
                        runCatching { f.readLines(Charsets.UTF_8).size }.getOrDefault(0)
                    } else 0
                    lines to 0
                } else 0 to 0
            }
            WorktreeFileStat(path = path, status = st, additions = add, deletions = del)
        }

        val (_, diffOut) = runGit(
            wtDir,
            listOf("diff", "--no-ext-diff", "--no-textconv", record.baseCommit, "--", "."),
            maxChars = WorkspacePathGuard.MAX_DIFF_CHARS + 1,
            timeoutMs = 10_000L,
        )
        val truncated = diffOut.length > WorkspacePathGuard.MAX_DIFF_CHARS
        val diffText = if (truncated) diffOut.substring(0, WorkspacePathGuard.MAX_DIFF_CHARS) else diffOut
        WorktreeDiffResult(
            worktree = record,
            files = files,
            diff = diffText,
            truncated = truncated,
            totalAdditions = files.sumOf { it.additions },
            totalDeletions = files.sumOf { it.deletions },
            aheadCommits = aheadCommits,
        )
    }

    suspend fun commit(wtId: String, message: String): WorktreeOpResult = withContext(Dispatchers.IO) {
        val record = findById(wtId) ?: return@withContext WorktreeOpResult(false, "Worktree not found")
        val wtDir = File(record.worktreePath)
        val cleanMsg = message.trim().ifEmpty { "superpi: update ${record.label}" }
        val (addCode, addOut) = runGit(wtDir, listOf("add", "-A"), timeoutMs = 10_000L)
        if (addCode != 0) return@withContext WorktreeOpResult(false, addOut.trim().ifEmpty { "git add failed" })
        val (_, st) = runGit(wtDir, listOf("status", "--porcelain=v1"))
        if (st.isBlank()) return@withContext WorktreeOpResult(true, "Nothing to commit")
        val (cCode, cOut) = runGit(
            wtDir,
            listOf(
                "-c", "user.name=SuperPI",
                "-c", "user.email=superpi@local",
                "commit", "-m", cleanMsg,
            ),
            timeoutMs = 10_000L,
        )
        if (cCode != 0) WorktreeOpResult(false, cOut.trim().ifEmpty { "git commit failed" })
        else WorktreeOpResult(true, cOut.lines().firstOrNull().orEmpty().ifBlank { "Committed" })
    }

    private val repoLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun applyToBase(wtId: String): WorktreeOpResult = withContext(Dispatchers.IO) {
        val record = findById(wtId) ?: return@withContext WorktreeOpResult(false, "Worktree not found")
        val wtDir = File(record.worktreePath)
        val repoDir = File(record.repoPath)
        if (!wtDir.isDirectory || !repoDir.isDirectory) {
            return@withContext WorktreeOpResult(false, "Repository or worktree directory missing")
        }
        val repoLock = repoLocks.computeIfAbsent(repoDir.absolutePath) { Mutex() }
        repoLock.withLock {
            // Check base workspace cleanliness & branch BEFORE mutating/committing inside the worktree!
            val (_, baseStatus) = runGit(repoDir, listOf("status", "--porcelain=v1"))
            if (baseStatus.isNotBlank()) {
                return@withLock WorktreeOpResult(
                    ok = false,
                    message = "Main workspace has uncommitted changes. Commit or stash them before applying this worktree.",
                )
            }
            val (_, currentBaseBranch) = runGit(repoDir, listOf("rev-parse", "--abbrev-ref", "HEAD"))
            val cleanBaseBranch = currentBaseBranch.trim()
            if (record.baseBranch.isNotBlank() && record.baseBranch != "HEAD" && cleanBaseBranch != record.baseBranch) {
                return@withLock WorktreeOpResult(
                    ok = false,
                    message = "Main workspace is on branch '$cleanBaseBranch', expected '${record.baseBranch}'. Switch back before applying.",
                )
            }

            // Snapshot any uncommitted changes inside the worktree now that base checks passed.
            val (_, wtStatus) = runGit(wtDir, listOf("status", "--porcelain=v1"))
            if (wtStatus.isNotBlank()) {
                val snap = commit(wtId, "superpi: snapshot ${record.label}")
                if (!snap.ok) return@withLock snap
            }
            val (_, countOut) = runGit(wtDir, listOf("rev-list", "--count", "${record.baseCommit}..HEAD"))
            if ((countOut.trim().toIntOrNull() ?: 0) == 0) {
                return@withLock WorktreeOpResult(true, "No new changes in worktree to apply.")
            }

            val (mergeCode, mergeOut) = runGit(
                repoDir,
                listOf(
                    "-c", "user.name=SuperPI",
                    "-c", "user.email=superpi@local",
                    "merge", "--no-ff", "--no-edit", record.branch,
                ),
                timeoutMs = 15_000L,
            )
            if (mergeCode != 0) {
                val (_, conflictOut) = runGit(repoDir, listOf("diff", "--name-only", "--diff-filter=U"))
                val conflicts = conflictOut.lines().map { it.trim() }.filter { it.isNotEmpty() }
                runGit(repoDir, listOf("merge", "--abort"))
                return@withLock WorktreeOpResult(
                    ok = false,
                    message = "Merge conflicts detected; aborted cleanly. Resolve conflicts or apply manually.",
                    conflicts = conflicts,
                )
            }
            WorktreeOpResult(
                true,
                mergeOut.lines().firstOrNull().orEmpty().ifBlank { "Applied worktree changes to ${record.baseBranch}" },
            )
        }
    }

    suspend fun remove(wtId: String, deleteBranch: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val record = mutex.withLock {
            val all = readIndexLocked().toMutableList()
            val idx = all.indexOfFirst { it.id == wtId }
            if (idx < 0) return@withLock null
            val removed = all.removeAt(idx)
            writeIndexLocked(all)
            removed
        } ?: return@withContext false

        val repoDir = File(record.repoPath)
        val wtDir = File(record.worktreePath)
        if (repoDir.isDirectory) {
            runGit(repoDir, listOf("worktree", "remove", "--force", wtDir.absolutePath), timeoutMs = 10_000L)
            runGit(repoDir, listOf("worktree", "prune"), timeoutMs = 5_000L)
            if (deleteBranch && record.branch.startsWith("superpi/")) {
                runGit(repoDir, listOf("branch", "-D", record.branch), timeoutMs = 5_000L)
            }
        }
        if (wtDir.exists()) {
            val rootReal = runCatching { worktreesRoot.toPath().toRealPath() }.getOrNull()
            val wtReal = runCatching { wtDir.toPath().toRealPath() }.getOrNull()
            if (rootReal != null && wtReal != null && wtReal.startsWith(rootReal) && wtReal != rootReal) {
                runCatching { wtDir.deleteRecursively() }
            }
        }
        true
    }

    fun hasUncommittedChanges(wtDir: File): Boolean {
        if (!wtDir.isDirectory) return false
        val (code, out) = runGit(wtDir, listOf("status", "--porcelain"), timeoutMs = 5_000L)
        return code == 0 && out.isNotBlank()
    }

    fun openInExternalEditor(dir: File, requestedEditor: String? = null): OpenEditorResult {
        if (!dir.isDirectory) {
            return OpenEditorResult(false, "", "Directory does not exist")
        }
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.US)
        val allowed = linkedMapOf(
            "vscode" to listOf("code"),
            "cursor" to listOf("cursor"),
            "zed" to listOf("zed"),
            "codium" to listOf("codium"),
            "idea" to listOf("idea"),
            "folder" to when {
                "mac" in os || "darwin" in os -> listOf("open")
                "win" in os -> listOf("explorer.exe")
                else -> listOf("xdg-open")
            },
        )
        val candidates = if (!requestedEditor.isNullOrBlank()) {
            val key = requestedEditor.trim().lowercase(Locale.US)
            val cmds = allowed[key] ?: return OpenEditorResult(false, key, "Unsupported editor: $key")
            listOf(key to cmds)
        } else {
            listOf("cursor", "vscode", "zed", "codium", "folder").mapNotNull { k -> allowed[k]?.let { k to it } }
        }
        for ((key, cmds) in candidates) {
            val exe = cmds.firstNotNullOfOrNull { resolveExecutableOnPath(it) } ?: continue
            val proc = runCatching {
                ProcessBuilder(listOf(exe, dir.absolutePath))
                    .redirectInput(ProcessBuilder.Redirect.from(File(if ("win" in os) "NUL" else "/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull()
            if (proc != null) {
                proc.onExit() // register async reap handle
                return OpenEditorResult(true, key, "Opened in $key")
            }
        }
        return OpenEditorResult(false, requestedEditor.orEmpty(), "No supported editor or file manager found on PATH")
    }

    private fun resolveExecutableOnPath(cmd: String): String? {
        val pathEnv = System.getenv("PATH").orEmpty()
        val sep = File.pathSeparatorChar
        val exts = if (System.getProperty("os.name").orEmpty().lowercase(Locale.US).contains("win")) {
            listOf("", ".exe", ".cmd", ".bat")
        } else {
            listOf("")
        }
        for (dir in pathEnv.split(sep)) {
            if (dir.isBlank()) continue
            for (ext in exts) {
                val f = File(dir, cmd + ext)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    private fun pruneMissingLocked(): List<WorktreeRecord> {
        val current = readIndexLocked()
        val kept = current.filter { File(it.worktreePath).isDirectory }
        if (kept.size != current.size) {
            writeIndexLocked(kept)
        }
        return kept
    }

    private fun readIndexLocked(): List<WorktreeRecord> {
        if (!indexFile.isFile) return emptyList()
        val raw = runCatching { indexFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(WorktreeRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeIndexLocked(records: List<WorktreeRecord>) {
        worktreesRoot.mkdirs()
        val tmp = Files.createTempFile(worktreesRoot.toPath(), ".index.", ".tmp")
        try {
            tmp.toFile().writeText(
                json.encodeToString(ListSerializer(WorktreeRecord.serializer()), records) + "\n",
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

    private fun sanitizeBranchSlug(raw: String): String {
        val slug = raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(28)
        return slug.ifEmpty { "task" }
    }

    private fun runGit(
        dir: File,
        args: List<String>,
        maxChars: Int = 65_536,
        timeoutMs: Long = 5_000L,
    ): Pair<Int, String> {
        val safeArgs = listOf("git", "--literal-pathspecs", "-c", "core.fsmonitor=false", "-c", "core.quotePath=false") + args
        val proc = try {
            ProcessBuilder(safeArgs)
                .directory(dir)
                .redirectErrorStream(true)
                .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        } catch (_: IOException) {
            return -1 to ""
        }
        runCatching { proc.outputStream.close() }
        val buffer = StringBuilder()
        val reader = thread(isDaemon = true, name = "superpi-wt-git") {
            try {
                proc.inputStream.reader(Charsets.UTF_8).use { r ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n < 0) break
                        val rem = maxChars - buffer.length
                        if (rem > 0) buffer.append(chunk, 0, minOf(n, rem))
                    }
                }
            } catch (_: IOException) {
            }
        }
        return try {
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                reader.join(500)
                -1 to "git command timed out"
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
