package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.runtime.DetailedShellExecutor
import io.github.whywood.superpi.runtime.host.ShellToolProfile
import io.github.whywood.superpi.runtime.host.StreamingShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutionException
import java.io.File
import java.util.concurrent.TimeUnit

internal class WindowsProcessTreeKiller : ProcessTreeKiller {
    override fun kill(process: Process, ownProcessGroup: Boolean) {
        val pid = runCatching { process.pid() }.getOrNull()
        val descendants = runCatching {
            process.toHandle().descendants().toList()
        }.getOrDefault(emptyList())
        if (pid != null && pid > 0L) {
            runCatching {
                val killer = ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .start()
                runCatching { killer.outputStream.close() }
                runCatching { killer.inputStream.close() }
                killer.waitFor(2_000, TimeUnit.MILLISECONDS)
                if (killer.isAlive) killer.destroyForcibly()
            }
        }
        descendants.asReversed().forEach { handle ->
            if (handle.isAlive) runCatching { handle.destroyForcibly() }
        }
        if (process.isAlive) {
            runCatching { process.destroyForcibly() }
        }
    }
}

internal class WindowsShellBackend(
    logsDir: File,
    processKiller: ProcessTreeKiller,
    onRegisterProcess: (Process, Boolean) -> Unit,
    onUnregisterProcess: (Process) -> Unit,
    private val customShellPath: String? = null,
    maxOutputLines: Int = ShellProcessRunner.DEFAULT_MAX_LINES,
    maxOutputBytes: Int = ShellProcessRunner.DEFAULT_MAX_BYTES,
) : ShellBackend {
    private val runner = ShellProcessRunner(
        logsDir = logsDir,
        processKiller = processKiller,
        onRegisterProcess = onRegisterProcess,
        onUnregisterProcess = onUnregisterProcess,
        maxOutputLines = maxOutputLines,
        maxOutputBytes = maxOutputBytes,
    ) { command ->
        val shell = resolveShellPath()
            ?: throw ShellExecutionException("Shell unavailable: no pwsh.exe, powershell.exe, or cmd.exe found")
        buildWindowsPlan(shell, command)
    }

    fun resolveShellPath(): String? {
        customShellPath?.let { path ->
            val file = File(path)
            if (file.isFile) return path
            return null
        }
        for (name in listOf("pwsh.exe", "pwsh", "powershell.exe")) {
            executableOnWindowsPath(name)?.let { return it }
        }
        val systemRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
        val programFiles = System.getenv("ProgramFiles")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files"
        val fixedCandidates = listOf(
            "$programFiles\\PowerShell\\7\\pwsh.exe",
            "$systemRoot\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            "$systemRoot\\System32\\cmd.exe",
        )
        for (candidate in fixedCandidates) {
            if (File(candidate).isFile) return candidate
        }
        return executableOnWindowsPath("cmd.exe")
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
                shellName = "pwsh.exe",
                message = "No pwsh.exe, powershell.exe, or cmd.exe found in System32, Program Files, or PATH",
            )
        }
    }

    override fun executor(cwd: String): DetailedShellExecutor = runner.detailedExecutor(cwd)

    override fun streamingExecutor(cwd: String): StreamingShellExecutor = runner.streamingExecutor(cwd)

    internal fun buildWindowsPlan(shellPath: String, command: String): ShellCommandPlan {
        val exeName = File(shellPath).name.lowercase()
        val argv = if (exeName == "cmd.exe" || exeName == "cmd") {
            listOf(shellPath, "/d", "/s", "/c", "chcp 65001>nul 2>&1 & $command")
        } else {
            // Use -EncodedCommand (UTF-16LE Base64) to avoid Win32 quote mangling, wrap [Console]
            // calls in try/catch for headless Task Scheduler / GUI runs, and preserve $LASTEXITCODE.
            val psScript = buildString {
                appendLine("try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch {}")
                appendLine("try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}")
                appendLine("\$OutputEncoding = [System.Text.Encoding]::UTF8")
                appendLine("\$LASTEXITCODE = 0")
                appendLine(command)
                appendLine("if (\$null -ne \$LASTEXITCODE -and \$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }")
            }
            val encoded = java.util.Base64.getEncoder().encodeToString(psScript.toByteArray(Charsets.UTF_16LE))
            listOf(shellPath, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
        }
        return ShellCommandPlan(
            argv = argv,
            ownProcessGroup = false,
            envOverrides = mapOf("PYTHONIOENCODING" to "utf-8"),
        )
    }

    private fun executableOnWindowsPath(name: String): String? {
        val pathVar = System.getenv("PATH") ?: return null
        for (rawEntry in pathVar.split(File.pathSeparatorChar)) {
            val entry = rawEntry.trim().trim('"')
            if (entry.isBlank()) continue
            val candidate = File(entry, name)
            if (candidate.isFile) return candidate.absolutePath
        }
        return null
    }
}

class WindowsHostPlatform(
    paths: PathLayout,
    customShellPath: String? = null,
) : BaseHostPlatform(paths, WindowsProcessTreeKiller()) {
    private val windowsShell = WindowsShellBackend(
        logsDir = paths.shellLogsDir,
        processKiller = processKiller,
        onRegisterProcess = { proc, ownGroup -> trackedProcesses[proc] = ownGroup },
        onUnregisterProcess = { proc -> trackedProcesses.remove(proc) },
        customShellPath = customShellPath,
    )

    override val shell: ShellBackend get() = windowsShell

    override val profile: OsProfile by lazy {
        val resolved = windowsShell.resolveShellPath() ?: "powershell.exe"
        val shellName = File(resolved).name
        val arch = System.getProperty("os.arch").orEmpty().ifBlank { "amd64" }
        val user = System.getProperty("user.name").orEmpty().ifBlank { "user" }
        val userHome = System.getProperty("user.home").orEmpty()
        OsProfile(
            os = OsKind.WINDOWS,
            osName = System.getProperty("os.name").orEmpty().ifBlank { "Windows" },
            arch = arch,
            shellName = shellName,
            shellPath = resolved,
            promptLines = listOf(
                "Host OS: Windows ($arch)",
                "Shell: $resolved ($shellName, UTF-8 console)",
                "User: $user (home: $userHome)",
                "SuperPI Data Home: ${paths.homeDir.absolutePath}",
                "Commands run natively on the host Windows environment in the active bot working directory.",
            ),
            shellToolProfile = ShellToolProfile(
                toolDescription = "Execute a command in the host Windows $shellName shell for the current working directory. Returns merged stdout and stderr, ending with [exit: N]. Output is truncated to the last 2000 lines or 50KB (with complete log path saved when exceeded). Optionally provide timeout in seconds.",
                guidelines = listOf(
                    "Check the [exit: N] marker on every command result; investigate failures before moving on.",
                    "Commands run directly in host Windows $shellName with UTF-8 encoding; quote paths containing spaces.",
                    "Always provide a short plain-language description argument so the user sees what is happening.",
                ),
            ),
        )
    }
}

internal object WindowsServiceManager : HostServiceManager {
    const val TASK_NAME = "SuperPI-Serve"

    override val label: String = "Windows Scheduled Task ($TASK_NAME)"

    fun taskXmlFile(paths: PathLayout): File = File(paths.cacheDir, "superpi-task.xml")

    fun renderTaskXml(
        paths: PathLayout,
        port: Int,
        execPrefix: List<String> = LauncherLocator.resolveExecutable(windows = true),
    ): String {
        val executable = execPrefix.firstOrNull().orEmpty()
        val trailingArgs = (execPrefix.drop(1) +
            listOf("--home", paths.homeDir.absolutePath, "serve") +
            if (port > 0) listOf("--port", port.toString()) else emptyList())
        val comspec = System.getenv("ComSpec")?.takeIf { it.isNotBlank() } ?: "cmd.exe"
        val innerCmd = (listOf(executable) + trailingArgs).joinToString(" ") { quoteWinArg(it) }
        val logPath = HostServiceManagers.defaultLogFile(paths).absolutePath
        val command = comspec
        val arguments = """/d /s /c "$innerCmd >> ${quoteWinArg(logPath)} 2>&1""""
        return """<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>${HostServiceManagers.renderXmlText("${AppBrand.DISPLAY_NAME} Background Control Server")}</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>5</Count>
    </RestartOnFailure>
    <Hidden>true</Hidden>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>${HostServiceManagers.renderXmlText(command)}</Command>
      <Arguments>${HostServiceManagers.renderXmlText(arguments)}</Arguments>
      <WorkingDirectory>${HostServiceManagers.renderXmlText(paths.homeDir.absolutePath)}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""
    }

    override fun install(paths: PathLayout, port: Int): Result<File> = runCatching {
        paths.cacheDir.mkdirs()
        paths.logsDir.mkdirs()
        val xmlFile = taskXmlFile(paths)
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        xmlFile.writeBytes(bom + renderTaskXml(paths, port).toByteArray(Charsets.UTF_16LE))
        val create = runSchtasks("/Create", "/TN", TASK_NAME, "/XML", xmlFile.absolutePath, "/F")
        if (create.exitCode != 0) {
            error(create.output.ifBlank { "schtasks /Create failed" })
        }
        xmlFile
    }

    override fun uninstall(paths: PathLayout): Result<Unit> = runCatching {
        runSchtasks("/End", "/TN", TASK_NAME)
        val existsBefore = runSchtasks("/Query", "/TN", TASK_NAME).exitCode == 0
        val del = runSchtasks("/Delete", "/TN", TASK_NAME, "/F")
        taskXmlFile(paths).delete()
        if (existsBefore && del.exitCode != 0) {
            error(del.output.ifBlank { "schtasks /Delete failed" })
        }
    }

    override fun start(paths: PathLayout): CommandExecOutput =
        runSchtasks("/Run", "/TN", TASK_NAME)

    override fun stop(paths: PathLayout): CommandExecOutput =
        runSchtasks("/End", "/TN", TASK_NAME)

    override fun status(paths: PathLayout): CommandExecOutput =
        runSchtasks("/Query", "/TN", TASK_NAME, "/FO", "LIST", "/V")

    override fun logs(paths: PathLayout, follow: Boolean, lines: Int): Int =
        HostServiceManagers.tailLogFile(HostServiceManagers.defaultLogFile(paths), lines = lines, follow = follow)

    private fun runSchtasks(vararg args: String): CommandExecOutput {
        val proc = ProcessBuilder(listOf("schtasks.exe") + args)
            .redirectErrorStream(true)
            .start()
        val nativeCharset = runCatching {
            val name = System.getProperty("native.encoding")?.takeIf { it.isNotBlank() }
            if (name != null) java.nio.charset.Charset.forName(name) else Charsets.UTF_8
        }.getOrDefault(Charsets.UTF_8)
        val output = proc.inputStream.bufferedReader(nativeCharset).readText()
        val code = proc.waitFor()
        return CommandExecOutput(code, output.trim())
    }

    private fun quoteWinArg(arg: String): String {
        val needsQuotes = arg.isEmpty() || arg.any {
            it.isWhitespace() || it in "\"&|<>^()%!"
        }
        if (!needsQuotes) return arg
        val escaped = StringBuilder("\"")
        var backslashes = 0
        for (ch in arg) {
            when (ch) {
                '\\' -> backslashes++
                '"' -> {
                    repeat(backslashes * 2 + 1) { escaped.append('\\') }
                    escaped.append('"')
                    backslashes = 0
                }
                else -> {
                    repeat(backslashes) { escaped.append('\\') }
                    backslashes = 0
                    escaped.append(ch)
                }
            }
        }
        repeat(backslashes * 2) { escaped.append('\\') }
        escaped.append('"')
        return escaped.toString()
    }
}
