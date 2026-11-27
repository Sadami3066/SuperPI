package io.github.whywood.superpi.host.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class TerminalEvent(
    val type: String, // "chunk" | "exit"
    val data: String = "",
    val exitCode: Int? = null,
)

data class TerminalInfo(
    val id: String,
    val botId: String,
    val cwd: String,
    val shell: String,
    val alive: Boolean,
    val exitCode: Int? = null,
    val createdAt: Long,
    val history: String,
)

/**
 * Manages interactive shell sessions scoped to Bot / Worktree directories.
 * Uses `ProcessBuilder` argument arrays (zero shell string concatenation), keeps a 128 KB ring buffer
 * for reconnect continuity, streams output over bounded SharedFlow, and reaps entire process trees cleanly.
 */
class PtySessionManager(
    private val maxSessions: Int = 6,
) {
    private class ManagedTerminal(
        val id: String,
        val botId: String,
        val cwd: String,
        val shell: String,
        val process: Process,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        val lastActiveAt = AtomicLong(createdAt)
        val closed = AtomicBoolean(false)
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val activeSubscribers = AtomicInteger(0)
        val bufferLock = Any()
        val historyBuf = StringBuilder()
        val events = MutableSharedFlow<TerminalEvent>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        fun appendHistory(chunk: String) {
            synchronized(bufferLock) {
                historyBuf.append(chunk)
                if (historyBuf.length > MAX_HISTORY_CHARS) {
                    historyBuf.delete(0, historyBuf.length - MAX_HISTORY_CHARS)
                }
            }
            lastActiveAt.set(System.currentTimeMillis())
        }

        fun snapshotHistory(): String = synchronized(bufferLock) { historyBuf.toString() }

        fun resolvedExitCode(): Int? = exitCode.get().takeIf { it != Int.MIN_VALUE }

        fun toInfo(): TerminalInfo = TerminalInfo(
            id = id,
            botId = botId,
            cwd = cwd,
            shell = shell,
            alive = process.isAlive && !closed.get(),
            exitCode = resolvedExitCode(),
            createdAt = createdAt,
            history = snapshotHistory(),
        )
    }

    private val sessions = ConcurrentHashMap<String, ManagedTerminal>()
    private val createLock = Any()

    fun listForBot(botId: String): List<TerminalInfo> {
        return sessions.values
            .filter { it.botId == botId }
            .sortedByDescending { it.createdAt }
            .map { it.toInfo() }
    }

    fun getInfo(termId: String): TerminalInfo? = sessions[termId]?.toInfo()

    fun eventsFlow(termId: String): SharedFlow<TerminalEvent>? = sessions[termId]?.events?.asSharedFlow()

    fun attachSubscriber(termId: String) {
        val managed = sessions[termId] ?: return
        managed.activeSubscribers.incrementAndGet()
        managed.lastActiveAt.set(System.currentTimeMillis())
    }

    fun detachSubscriber(termId: String) {
        val managed = sessions[termId] ?: return
        managed.activeSubscribers.updateAndGet { cur -> maxOf(0, cur - 1) }
        managed.lastActiveAt.set(System.currentTimeMillis())
    }

    fun resize(termId: String, cols: Int, rows: Int): Boolean {
        val managed = sessions[termId] ?: return false
        if (managed.closed.get() || !managed.process.isAlive) return false
        val safeCols = cols.coerceIn(MIN_COLS, MAX_COLS)
        val safeRows = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        managed.lastActiveAt.set(System.currentTimeMillis())
        applyPtyWindowSize(managed.process, safeCols, safeRows)
        return true
    }

    fun createOrReuse(botId: String, cwd: File, fresh: Boolean = false): TerminalInfo = synchronized(createLock) {
        require(cwd.isDirectory) { "Workspace directory does not exist: ${cwd.absolutePath}" }
        pruneIdleAndDead()
        val canonicalCwd = cwd.toPath().toRealPath().toString()
        if (!fresh) {
            val existing = sessions.values.firstOrNull {
                it.botId == botId && it.cwd == canonicalCwd && it.process.isAlive && !it.closed.get()
            }
            if (existing != null) return existing.toInfo()
        }
        if (sessions.size >= maxSessions) {
            val oldest = sessions.values.minByOrNull { it.lastActiveAt.get() }
            if (oldest != null) kill(oldest.id)
        }

        val (shellLabel, userShellPath, cmd) = buildShellCommand()
        val pb = ProcessBuilder(cmd)
            .directory(File(canonicalCwd))
            .redirectErrorStream(true)
        val env = pb.environment()
        if (userShellPath != null) env["SHELL"] = userShellPath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["COLUMNS"] = "120"
        env["LINES"] = "32"

        val proc = try {
            pb.start()
        } catch (err: IOException) {
            throw IllegalStateException("Failed to start terminal shell ($shellLabel): ${err.message}")
        }

        val termId = "term-${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val managed = ManagedTerminal(
            id = termId,
            botId = botId,
            cwd = canonicalCwd,
            shell = shellLabel,
            process = proc,
        )
        sessions[termId] = managed

        thread(isDaemon = true, name = "superpi-pty-$termId") {
            try {
                proc.inputStream.reader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    while (!managed.closed.get()) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        val chunk = String(buf, 0, n)
                        managed.appendHistory(chunk)
                        managed.events.tryEmit(TerminalEvent(type = "chunk", data = chunk))
                    }
                }
            } catch (_: IOException) {
            } finally {
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                managed.exitCode.compareAndSet(Int.MIN_VALUE, code)
                managed.closed.set(true)
                managed.events.tryEmit(TerminalEvent(type = "exit", exitCode = managed.resolvedExitCode()))
            }
        }
        managed.toInfo()
    }

    fun writeInput(termId: String, data: String): Boolean {
        val managed = sessions[termId] ?: return false
        if (managed.closed.get() || !managed.process.isAlive) return false
        return try {
            val out: OutputStream = managed.process.outputStream
            synchronized(out) {
                out.write(data.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            managed.lastActiveAt.set(System.currentTimeMillis())
            true
        } catch (_: IOException) {
            false
        }
    }

    fun kill(termId: String): Boolean {
        val managed = sessions.remove(termId) ?: return false
        managed.closed.set(true)
        managed.exitCode.compareAndSet(Int.MIN_VALUE, -1)
        runCatching { managed.process.outputStream.close() }
        terminateProcessTree(managed.process)
        runCatching { managed.process.inputStream.close() }
        managed.events.tryEmit(TerminalEvent(type = "exit", exitCode = managed.resolvedExitCode()))
        return true
    }

    fun killByCwd(cwdDir: File) {
        val target = runCatching { cwdDir.toPath().toRealPath().toString() }.getOrElse { cwdDir.absolutePath }
        sessions.values.filter { it.cwd == target }.forEach { kill(it.id) }
    }

    fun shutdownAll() {
        sessions.keys.toList().forEach { kill(it) }
    }

    fun pruneIdleAndDead() {
        val now = System.currentTimeMillis()
        sessions.values.toList().forEach { term ->
            val alive = term.process.isAlive
            if (alive && term.activeSubscribers.get() > 0) return@forEach
            val idleTooLong = now - term.lastActiveAt.get() > IDLE_TIMEOUT_MS
            val deadTooLong = !alive && now - term.lastActiveAt.get() > 60_000L
            if (idleTooLong || deadTooLong) {
                kill(term.id)
            }
        }
    }

    private fun applyPtyWindowSize(proc: Process, cols: Int, rows: Int) {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.US)
        if ("win" in os) return
        val ttyPath = resolvePtySlavePath(proc, os) ?: return
        val flag = if ("mac" in os || "darwin" in os) "-f" else "-F"
        runCatching {
            val stty = ProcessBuilder("stty", flag, ttyPath, "rows", rows.toString(), "cols", cols.toString())
                .redirectErrorStream(true)
                .start()
            stty.waitFor(1_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun resolvePtySlavePath(proc: Process, os: String): String? {
        val pids = buildList {
            runCatching { addAll(proc.toHandle().descendants().map { it.pid() }.toList()) }
            add(proc.pid())
        }
        if ("mac" in os || "darwin" in os) {
            for (pid in pids) {
                val ttyName = runCatching {
                    val ps = ProcessBuilder("ps", "-o", "tty=", "-p", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                    val out = ps.inputStream.bufferedReader().readText().trim()
                    ps.waitFor(1_000, TimeUnit.MILLISECONDS)
                    out.takeIf { it.isNotBlank() && it != "??" && !it.startsWith("?") }
                }.getOrNull() ?: continue
                val dev = if (ttyName.startsWith("/dev/")) ttyName else "/dev/$ttyName"
                if (File(dev).exists()) return dev
            }
            return null
        }
        for (pid in pids) {
            for (fd in listOf("0", "1")) {
                val target = runCatching {
                    Files.readSymbolicLink(Path.of("/proc/$pid/fd/$fd")).toString()
                }.getOrNull() ?: continue
                if (target.startsWith("/dev/pts/") || target.startsWith("/dev/tty")) {
                    return target
                }
            }
        }
        return null
    }

    private fun terminateProcessTree(proc: Process) {
        val descendants = runCatching {
            proc.toHandle().descendants().use { s -> s.toList() }
        }.getOrDefault(emptyList())
        // Graceful SIGHUP/SIGTERM first so shells & dev servers clean up cleanly
        descendants.forEach { runCatching { it.destroy() } }
        runCatching { proc.destroy() }
        val exited = runCatching { proc.waitFor(200, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) {
            descendants.forEach { if (it.isAlive) runCatching { it.destroyForcibly() } }
            runCatching { proc.destroyForcibly() }
        }
    }

    private fun buildShellCommand(): Triple<String, String?, List<String>> {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.US)
        if ("win" in os) {
            val pwsh = findExecutable("pwsh.exe") ?: findExecutable("powershell.exe")
            if (pwsh != null) return Triple("PowerShell", null, listOf(pwsh, "-NoLogo"))
            return Triple("cmd", null, listOf("cmd.exe"))
        }
        val userShell = System.getenv("SHELL")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains('\u0000') && File(it).canExecute() }
            ?: listOf("/bin/bash", "/bin/zsh", "/bin/sh").firstOrNull { File(it).canExecute() }
            ?: "/bin/sh"
        val shellName = File(userShell).name
        val scriptExe = findExecutable("script")
        if (scriptExe != null) {
            return if ("mac" in os || "darwin" in os) {
                Triple(shellName, userShell, listOf(scriptExe, "-q", "/dev/null", userShell, "-i"))
            } else {
                // On Linux util-linux, `script -qef /dev/null` execs $SHELL directly with ZERO /bin/sh -c interpolation!
                Triple(shellName, userShell, listOf(scriptExe, "-qef", "/dev/null"))
            }
        }
        return Triple(shellName, userShell, listOf(userShell, "-i"))
    }

    private fun findExecutable(name: String): String? {
        val pathEnv = System.getenv("PATH").orEmpty()
        for (dir in pathEnv.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val f = File(dir, name)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }

    companion object {
        private const val MAX_HISTORY_CHARS = 128 * 1024
        private const val IDLE_TIMEOUT_MS = 30 * 60_000L
        const val MIN_COLS = 20
        const val MAX_COLS = 500
        const val MIN_ROWS = 5
        const val MAX_ROWS = 200
    }
}
