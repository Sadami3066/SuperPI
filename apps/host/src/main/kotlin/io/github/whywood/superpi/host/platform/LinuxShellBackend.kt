package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.runtime.DetailedShellExecutor
import io.github.whywood.superpi.runtime.host.StreamingShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutionException
import java.io.File

internal class LinuxShellBackend(
    logsDir: File,
    processKiller: ProcessTreeKiller,
    onRegisterProcess: (Process, Boolean) -> Unit,
    onUnregisterProcess: (Process) -> Unit,
    private val customShellPath: String? = null,
    maxOutputLines: Int = ShellProcessRunner.DEFAULT_MAX_LINES,
    maxOutputBytes: Int = ShellProcessRunner.DEFAULT_MAX_BYTES,
) : ShellBackend {
    private val setsidPath: String? by lazy { LauncherLocator.executableOnPath("setsid") }

    private val runner = ShellProcessRunner(
        logsDir = logsDir,
        processKiller = processKiller,
        onRegisterProcess = onRegisterProcess,
        onUnregisterProcess = onUnregisterProcess,
        maxOutputLines = maxOutputLines,
        maxOutputBytes = maxOutputBytes,
    ) { command ->
        val shell = resolveShellPath()
            ?: throw ShellExecutionException("Shell unavailable: no bash or sh executable found")
        val useSetsid = setsidPath != null
        val argv = if (useSetsid) {
            listOf(setsidPath!!, shell, "-c", command)
        } else {
            listOf(shell, "-c", command)
        }
        ShellCommandPlan(argv = argv, ownProcessGroup = useSetsid)
    }

    fun resolveShellPath(): String? {
        customShellPath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canExecute()) return path
            return null
        }
        for (candidate in listOf("/bin/bash", "/usr/bin/bash")) {
            val file = File(candidate)
            if (file.isFile && file.canExecute()) return candidate
        }
        LauncherLocator.executableOnPath("bash")?.let { return it }
        for (candidate in listOf("/bin/sh", "/usr/bin/sh")) {
            val file = File(candidate)
            if (file.isFile && file.canExecute()) return candidate
        }
        return LauncherLocator.executableOnPath("sh")
    }

    override fun probe(): ShellProbeResult {
        val path = resolveShellPath()
        return if (path != null) {
            ShellProbeResult(
                available = true,
                shellPath = path,
                shellName = File(path).name,
                message = "Using $path",
            )
        } else {
            ShellProbeResult(
                available = false,
                shellPath = null,
                shellName = "bash",
                message = "No executable bash or sh found in /bin, /usr/bin, or PATH",
            )
        }
    }

    override fun executor(cwd: String): DetailedShellExecutor = runner.detailedExecutor(cwd)

    override fun streamingExecutor(cwd: String): StreamingShellExecutor = runner.streamingExecutor(cwd)
}

internal typealias LinuxProcessTreeKiller = PosixProcessTreeKiller
