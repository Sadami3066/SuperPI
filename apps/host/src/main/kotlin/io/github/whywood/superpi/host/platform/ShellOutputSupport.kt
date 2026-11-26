package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.runtime.CommandResult
import io.github.whywood.superpi.runtime.DetailedShellExecutor
import io.github.whywood.superpi.runtime.EncryptedFileSecretStore
import io.github.whywood.superpi.runtime.SecretStore
import io.github.whywood.superpi.runtime.host.HostAccess
import io.github.whywood.superpi.runtime.host.HostCapabilities
import io.github.whywood.superpi.runtime.host.HostEnvironment
import io.github.whywood.superpi.runtime.host.StreamingShellExecutor
import io.github.whywood.superpi.runtime.host.StreamingShellResult
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutionException
import io.github.whywood.superpi.sdk.coding.tools.truncateTail
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.FileSystem

internal object HostSecretEnv {
    private val PROVIDER_ENV_VARS = mapOf(
        "openai" to "OPENAI_API_KEY",
        "anthropic" to "ANTHROPIC_API_KEY",
        "google" to "GEMINI_API_KEY",
        "gemini" to "GEMINI_API_KEY",
        "deepseek" to "DEEPSEEK_API_KEY",
        "openrouter" to "OPENROUTER_API_KEY",
        "xai" to "XAI_API_KEY",
        "groq" to "GROQ_API_KEY",
        "mistral" to "MISTRAL_API_KEY",
        "opencode" to "OPENCODE_API_KEY",
        "opencode-go" to "OPENCODE_GO_API_KEY",
    )

    private val PROVIDER_FALLBACK_ENV_VARS = mapOf(
        "opencode-go" to listOf("OPENCODE_API_KEY"),
    )

    private val WEB_SEARCH_ENV_VARS = mapOf(
        "exa" to "EXA_API_KEY",
        "tavily" to "TAVILY_API_KEY",
        "brave" to "BRAVE_API_KEY",
        "serper" to "SERPER_API_KEY",
    )

    fun envSecretFallback(prefix: String, id: String): String? {
        if (prefix == "api_key_") {
            val lower = id.lowercase()
            PROVIDER_ENV_VARS[lower]?.let { varName ->
                System.getenv(varName)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val normalized = id.uppercase().replace(Regex("[^A-Z0-9]+"), "_")
            System.getenv("${AppBrand.ENV_PREFIX}_API_KEY_$normalized")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            PROVIDER_FALLBACK_ENV_VARS[lower].orEmpty().forEach { fallbackVar ->
                System.getenv(fallbackVar)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return null
        }
        if (prefix == "web_search_key_") {
            WEB_SEARCH_ENV_VARS[id.lowercase()]?.let { varName ->
                System.getenv(varName)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    fun envSecretEntries(prefix: String): Map<String, String> = buildMap {
        if (prefix == "api_key_") {
            PROVIDER_ENV_VARS.forEach { (id, varName) ->
                System.getenv(varName)?.trim()?.takeIf { it.isNotEmpty() }?.let { put(id, it) }
            }
            val dynamicPrefix = "${AppBrand.ENV_PREFIX}_API_KEY_"
            System.getenv().forEach { (envName, value) ->
                if (envName.startsWith(dynamicPrefix)) {
                    val trimmed = value.trim()
                    val suffix = envName.removePrefix(dynamicPrefix).lowercase()
                    val providerId = suffix.replace('_', '-')
                    if (providerId.isNotEmpty() && trimmed.isNotEmpty()) {
                        putIfAbsent(providerId, trimmed)
                        putIfAbsent(suffix, trimmed)
                    }
                }
            }
        } else if (prefix == "web_search_key_") {
            WEB_SEARCH_ENV_VARS.forEach { (id, varName) ->
                System.getenv(varName)?.trim()?.takeIf { it.isNotEmpty() }?.let { put(id, it) }
            }
        }
    }
}

internal object LauncherLocator {
    fun resolveExecutable(windows: Boolean): List<String> {
        val explicitLauncher = System.getProperty("superpi.launcher")?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitLauncher != null) {
            val file = File(explicitLauncher)
            if (file.isFile && (windows || file.canExecute())) {
                return listOf(file.absolutePath)
            }
        }
        val selfCmd = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull()
        val launcherNames = if (windows) {
            listOf("${AppBrand.CLI_NAME}.bat", "${AppBrand.CLI_NAME}.cmd", "${AppBrand.CLI_NAME}.exe")
        } else {
            listOf(AppBrand.CLI_NAME)
        }
        if (!selfCmd.isNullOrBlank()) {
            val cmdFile = File(selfCmd).absoluteFile
            val appHomes = listOfNotNull(
                cmdFile.parentFile?.parentFile,
                cmdFile.parentFile?.parentFile?.parentFile,
            )
            for (home in appHomes) {
                for (name in launcherNames) {
                    val launcher = File(home, "bin/$name")
                    if (launcher.isFile && (windows || launcher.canExecute())) {
                        return listOf(launcher.absolutePath)
                    }
                }
            }
        }
        val classpath = System.getProperty("java.class.path")
        val javaExe = if (windows) "bin/java.exe" else "bin/java"
        val javaBin = selfCmd ?: File(System.getProperty("java.home"), javaExe).absolutePath
        return listOf(javaBin, "-cp", classpath, "io.github.whywood.superpi.host.MainKt")
    }

    fun executableOnPath(name: String, extraDirs: List<String> = emptyList()): String? {
        val dirs = buildList {
            System.getenv("PATH")
                ?.split(File.pathSeparatorChar)
                ?.filter { it.isNotBlank() }
                ?.let { addAll(it) }
            addAll(extraDirs)
        }
        for (entry in dirs) {
            val candidate = File(entry, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }
}

internal class PosixProcessTreeKiller : ProcessTreeKiller {
    override fun kill(process: Process, ownProcessGroup: Boolean) {
        val descendants = runCatching {
            process.toHandle().descendants().toList()
        }.getOrDefault(emptyList())
        descendants.asReversed().forEach { handle ->
            runCatching { handle.destroy() }
        }
        val pid = runCatching { process.pid() }.getOrNull()
        val confirmedPgid = if (ownProcessGroup && pid != null && pid > 1L && isConfirmedOwnProcessGroup(pid)) {
            pid
        } else {
            null
        }
        if (confirmedPgid != null) {
            runKill("TERM", confirmedPgid)
        }
        runCatching { process.destroy() }
        if (process.isAlive || descendants.any { it.isAlive }) {
            runCatching {
                if (process.isAlive) {
                    process.waitFor(250, TimeUnit.MILLISECONDS)
                } else {
                    Thread.sleep(150)
                }
            }
        }
        descendants.asReversed().forEach { handle ->
            if (handle.isAlive) {
                runCatching { handle.destroyForcibly() }
            }
        }
        if (confirmedPgid != null) {
            runKill("KILL", confirmedPgid)
        }
        if (process.isAlive) {
            runCatching { process.destroyForcibly() }
        }
    }

    private fun isConfirmedOwnProcessGroup(pid: Long): Boolean {
        if (pid <= 1L || pid == ProcessHandle.current().pid()) return false
        if (File("/proc").isDirectory) {
            val statFile = File("/proc/$pid/stat")
            if (!statFile.isFile) return false
            val raw = runCatching { statFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
            val closeParen = raw.lastIndexOf(')')
            if (closeParen < 0 || closeParen + 2 >= raw.length) return false
            val fields = raw.substring(closeParen + 2).trim().split(Regex("\\s+"))
            // After "(comm) ": [0]=state, [1]=ppid, [2]=pgrp, [3]=session
            val pgrp = fields.getOrNull(2)?.toLongOrNull() ?: return false
            return pgrp == pid && pgrp > 1L
        }
        // macOS / BSD POSIX: confirm PGID via `ps -o pgid= -p <pid>`
        val pgrp = runCatching {
            val proc = ProcessBuilder("ps", "-o", "pgid=", "-p", pid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            runCatching { proc.outputStream.close() }
            val text = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            proc.waitFor(300, TimeUnit.MILLISECONDS)
            if (proc.isAlive) proc.destroyForcibly()
            text.toLongOrNull()
        }.getOrNull() ?: return false
        return pgrp == pid && pgrp > 1L
    }

    private fun runKill(signalName: String, pgid: Long) {
        if (pgid <= 1L) return
        val isLinuxProc = File("/proc").isDirectory
        // Linux procps-ng 4.0.4 requires `--` before negative PGID so `-1xxxxxx` isn't truncated to `-1`.
        // macOS BSD /bin/kill rejects `--` after `-s <sig>` and parses `-$pgid` directly.
        val argv = if (isLinuxProc) {
            listOf("kill", "-s", signalName, "--", "-$pgid")
        } else {
            listOf("/bin/kill", "-s", signalName, "-$pgid")
        }
        runCatching {
            val proc = ProcessBuilder(argv)
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            proc.waitFor(300, TimeUnit.MILLISECONDS)
            if (proc.isAlive) proc.destroyForcibly()
        }
    }
}

internal data class ShellCommandPlan(
    val argv: List<String>,
    val ownProcessGroup: Boolean = false,
    val envOverrides: Map<String, String> = emptyMap(),
)

internal class ShellProcessRunner(
    private val logsDir: File,
    private val processKiller: ProcessTreeKiller,
    private val onRegisterProcess: (Process, Boolean) -> Unit,
    private val onUnregisterProcess: (Process) -> Unit,
    private val maxOutputLines: Int = DEFAULT_MAX_LINES,
    private val maxOutputBytes: Int = DEFAULT_MAX_BYTES,
    private val buildPlan: (command: String) -> ShellCommandPlan,
) {
    fun detailedExecutor(cwd: String): DetailedShellExecutor = object : DetailedShellExecutor {
        override suspend fun runDetailed(command: String, timeoutSeconds: Long?, signal: RunSignal?): CommandResult {
            val timeoutMs = timeoutSeconds?.coerceIn(1L, MAX_TIMEOUT_SECONDS)?.times(1000L) ?: DEFAULT_TIMEOUT_MS
            val result = executeStreaming(
                cwd = cwd,
                command = command,
                timeoutMillis = timeoutMs,
                signal = signal,
                onOutput = null,
            )
            return CommandResult(
                output = result.output,
                exitCode = if (result.timedOut) null else result.exitCode,
                truncated = result.truncated,
                timedOut = result.timedOut,
                fullOutputPath = result.fullOutputPath,
            )
        }
    }

    fun streamingExecutor(cwd: String): StreamingShellExecutor =
        StreamingShellExecutor { command, timeoutMillis, onOutput ->
            executeStreaming(
                cwd = cwd,
                command = command,
                timeoutMillis = timeoutMillis.coerceAtLeast(1_000L),
                signal = null,
                onOutput = onOutput,
            )
        }

    private suspend fun executeStreaming(
        cwd: String,
        command: String,
        timeoutMillis: Long,
        signal: RunSignal?,
        onOutput: ((String) -> Unit)?,
    ): StreamingShellResult = withContext(Dispatchers.IO) {
        signal?.throwIfAborted()
        val plan = buildPlan(command)
        val workDir = if (cwd.isNotBlank()) {
            val explicit = File(cwd)
            if (!explicit.isDirectory) {
                throw ShellExecutionException("Working directory does not exist: ${explicit.absolutePath}")
            }
            explicit
        } else {
            File(System.getProperty("user.home").orEmpty().ifBlank { "." })
        }

        val builder = ProcessBuilder(plan.argv).apply {
            directory(workDir)
            redirectErrorStream(true)
            SESSION_ENV_KEYS.forEach { environment().remove(it) }
            environment().putIfAbsent("TERM", "dumb")
            plan.envOverrides.forEach { (k, v) -> environment()[k] = v }
        }

        val startedAt = System.currentTimeMillis()
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw ShellExecutionException(error.message ?: "Failed to start shell")
        }
        onRegisterProcess(process, plan.ownProcessGroup)
        runCatching { process.outputStream.close() }

        val collector = ShellOutputCollector(
            logsDir = logsDir,
            maxLines = maxOutputLines,
            maxBytes = maxOutputBytes,
            onLine = onOutput,
        )
        val reader = thread(isDaemon = true, name = "superpi-shell-reader") {
            readStream(process.inputStream, collector)
        }
        val unsubscribe = signal?.onAbort {
            processKiller.kill(process, ownProcessGroup = plan.ownProcessGroup)
        }

        try {
            val finished = try {
                runInterruptible(Dispatchers.IO) {
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                }
            } catch (cancelled: CancellationException) {
                processKiller.kill(process, ownProcessGroup = plan.ownProcessGroup)
                finishReader(process, reader)
                collector.closeLog()
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                processKiller.kill(process, ownProcessGroup = plan.ownProcessGroup)
                finishReader(process, reader)
                val snap = collector.snapshot()
                throw ShellExecutionException(statusText(snap.text, "Command aborted"))
            }

            if (!finished) {
                processKiller.kill(process, ownProcessGroup = plan.ownProcessGroup)
                runCatching { process.waitFor(300, TimeUnit.MILLISECONDS) }
                finishReader(process, reader)
                val snap = collector.snapshot()
                return@withContext StreamingShellResult(
                    exitCode = -1,
                    output = snap.text,
                    truncated = snap.truncated,
                    timedOut = true,
                    durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                    fullOutputPath = snap.fullOutputPath,
                )
            }

            finishReader(process, reader)
            val snap = collector.snapshot()
            if (signal?.isAborted == true) {
                throw ShellExecutionException(statusText(snap.text, "Command aborted"))
            }
            val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            StreamingShellResult(
                exitCode = exitCode,
                output = snap.text,
                truncated = snap.truncated,
                timedOut = false,
                durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                fullOutputPath = snap.fullOutputPath,
            )
        } finally {
            unsubscribe?.invoke()
            if (process.isAlive) {
                processKiller.kill(process, ownProcessGroup = plan.ownProcessGroup)
            }
            onUnregisterProcess(process)
            collector.closeLog()
        }
    }

    private fun readStream(stream: InputStream, collector: ShellOutputCollector) {
        val reader = stream.reader(Charsets.UTF_8)
        val buffer = CharArray(8192)
        try {
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                collector.append(buffer, read)
            }
        } catch (_: IOException) {
        } finally {
            collector.flushLineBuffer()
            runCatching { stream.close() }
        }
    }

    private fun finishReader(process: Process, reader: Thread) {
        reader.join(READER_JOIN_MS)
        if (reader.isAlive) {
            runCatching { process.inputStream.close() }
            reader.join(READER_CLOSE_JOIN_MS)
        }
    }

    private fun statusText(output: String, status: String): String {
        val text = if (output.isEmpty()) "(no output)" else output
        return "$text\n\n$status"
    }

    companion object {
        const val DEFAULT_MAX_LINES = 2000
        const val DEFAULT_MAX_BYTES = 51_200
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_SECONDS = 86_400L
        private const val READER_JOIN_MS = 3_000L
        private const val READER_CLOSE_JOIN_MS = 1_000L

        private val SESSION_ENV_KEYS = listOf(
            "PI_SESSION_ID",
            "PI_SESSION_FILE",
            "PI_PROVIDER",
            "PI_MODEL",
            "PI_REASONING_LEVEL",
        )
    }
}

private data class CollectedShellOutput(
    val text: String,
    val truncated: Boolean,
    val fullOutputPath: String?,
)

private class ShellOutputCollector(
    private val logsDir: File,
    private val maxLines: Int,
    private val maxBytes: Int,
    private val onLine: ((String) -> Unit)?,
) {
    private val lock = Any()
    private val tailBuilder = StringBuilder()
    private val lineBuffer = StringBuilder()
    private var totalBytes = 0L
    private var totalLines = 0
    private var dropped = false
    private var spillFile: File? = null
    private var spillWriter: java.io.BufferedWriter? = null

    fun append(chars: CharArray, length: Int) {
        if (length <= 0) return
        val chunk = String(chars, 0, length)
        val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
        val chunkLines = chunk.count { it == '\n' }
        val linesToEmit: List<String>? = synchronized(lock) {
            totalBytes += chunkBytes
            totalLines += chunkLines
            val shouldSpill = spillWriter != null || totalBytes > maxBytes || totalLines > maxLines
            if (shouldSpill) {
                val writer = ensureSpillWriter()
                if (writer != null) {
                    runCatching {
                        writer.write(chunk)
                        writer.flush()
                    }
                }
            }
            tailBuilder.append(chunk)
            trimTail()
            if (onLine != null) {
                val list = mutableListOf<String>()
                lineBuffer.append(chunk)
                while (true) {
                    val idx = lineBuffer.indexOf("\n")
                    if (idx < 0) break
                    list += lineBuffer.substring(0, idx)
                    lineBuffer.delete(0, idx + 1)
                }
                if (lineBuffer.length > MAX_LINE_BUFFER_CHARS) {
                    list += lineBuffer.toString()
                    lineBuffer.clear()
                }
                list
            } else {
                null
            }
        }
        val callback = onLine
        if (callback != null && !linesToEmit.isNullOrEmpty()) {
            for (line in linesToEmit) {
                runCatching { callback.invoke(line) }
            }
        }
    }

    fun flushLineBuffer() {
        val remaining = synchronized(lock) {
            if (onLine != null && lineBuffer.isNotEmpty()) {
                lineBuffer.toString().also { lineBuffer.clear() }
            } else {
                null
            }
        }
        val callback = onLine
        if (callback != null && remaining != null) {
            runCatching { callback.invoke(remaining) }
        }
    }

    fun snapshot(): CollectedShellOutput = synchronized(lock) {
        val raw = tailBuilder.toString()
        val truncation = truncateTail(raw, maxLines = maxLines, maxBytes = maxBytes)
        CollectedShellOutput(
            text = truncation.text,
            truncated = truncation.truncated || dropped,
            fullOutputPath = spillFile?.absolutePath,
        )
    }

    fun closeLog() {
        synchronized(lock) {
            runCatching { spillWriter?.close() }
            spillWriter = null
        }
    }

    private fun trimTail() {
        val limit = maxBytes * 4
        if (tailBuilder.length <= limit) return
        var cut = tailBuilder.length - maxBytes * 2
        if (cut <= 0) return
        val newline = tailBuilder.indexOf("\n", cut)
        if (newline >= 0 && newline + 1 < tailBuilder.length) {
            cut = newline + 1
        }
        tailBuilder.delete(0, cut)
        dropped = true
    }

    private fun ensureSpillWriter(): java.io.BufferedWriter? {
        if (spillWriter != null) return spillWriter
        return runCatching {
            if (!logsDir.exists()) logsDir.mkdirs()
            val file = File(logsDir, "shell-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.log")
            val writer = file.bufferedWriter(Charsets.UTF_8)
            val bufferedTail = tailBuilder.toString()
            if (bufferedTail.isNotEmpty()) {
                writer.write(bufferedTail)
                writer.flush()
            }
            spillFile = file
            spillWriter = writer
            writer
        }.getOrNull()
    }

    private companion object {
        const val MAX_LINE_BUFFER_CHARS = 65_536
    }
}

abstract class BaseHostPlatform(
    final override val paths: PathLayout,
    final override val processKiller: ProcessTreeKiller,
) : HostPlatform {
    protected val trackedProcesses = ConcurrentHashMap<Process, Boolean>()

    override fun createSecretStore(): SecretStore = EncryptedFileSecretStore(
        secretsFile = paths.secretsFile,
        keyFile = paths.secretKeyFile,
        envFallback = HostSecretEnv::envSecretFallback,
        envEntries = HostSecretEnv::envSecretEntries,
    )

    override fun createHostEnvironment(): HostEnvironment {
        val currentProfile = profile
        return object : HostEnvironment {
            override val access: HostAccess = HostAccess.LOCAL
            override val capabilities: HostCapabilities = HostCapabilities(
                isRoot = false,
                hasSystemShell = false,
                canReadSystemData = false,
                bashAvailable = shell.probe().available,
                hasScreenControl = false,
                rootLinuxReady = false,
            )
            override val sharedStorageEnabled: Boolean = true
            override fun bash(cwd: String): DetailedShellExecutor = shell.executor(cwd)
            override fun bashStreaming(cwd: String): StreamingShellExecutor = shell.streamingExecutor(cwd)
            override val shellToolProfile = currentProfile.shellToolProfile
            override val files: FileSystem = FileSystem.SYSTEM
            override val system: DetailedShellExecutor? = null
            override val promptLines: List<String> = currentProfile.promptLines
        }
    }

    override fun registerChildProcess(process: Process, ownProcessGroup: Boolean) {
        trackedProcesses[process] = ownProcessGroup
    }

    override fun unregisterChildProcess(process: Process) {
        trackedProcesses.remove(process)
    }

    override fun cleanupAllChildProcesses() {
        for ((process, ownGroup) in trackedProcesses.toMap()) {
            if (process.isAlive) {
                processKiller.kill(process, ownProcessGroup = ownGroup)
            }
            trackedProcesses.remove(process)
        }
    }
}
