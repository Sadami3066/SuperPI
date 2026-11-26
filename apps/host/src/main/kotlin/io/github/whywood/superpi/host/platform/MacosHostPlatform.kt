package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.runtime.DetailedShellExecutor
import io.github.whywood.superpi.runtime.host.ShellToolProfile
import io.github.whywood.superpi.runtime.host.StreamingShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutionException
import java.io.File

internal class MacosShellBackend(
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
            ?: throw ShellExecutionException("Shell unavailable: no zsh, bash, or sh executable found")
        val enrichedPath = enrichedMacosPath()
        ShellCommandPlan(
            argv = listOf(shell, "-c", command),
            ownProcessGroup = false,
            envOverrides = if (enrichedPath != null) mapOf("PATH" to enrichedPath) else emptyMap(),
        )
    }

    fun resolveShellPath(): String? {
        customShellPath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canExecute()) return path
            return null
        }
        val candidates = listOf(
            "/bin/zsh",
            "/usr/bin/zsh",
            "/opt/homebrew/bin/zsh",
            "/usr/local/bin/zsh",
            "/bin/bash",
            "/usr/bin/bash",
            "/opt/homebrew/bin/bash",
            "/bin/sh",
        )
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile && file.canExecute()) return candidate
        }
        return LauncherLocator.executableOnPath("zsh", EXTRA_BIN_DIRS)
            ?: LauncherLocator.executableOnPath("bash", EXTRA_BIN_DIRS)
            ?: LauncherLocator.executableOnPath("sh", EXTRA_BIN_DIRS)
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
                shellName = "zsh",
                message = "No executable zsh, bash, or sh found in /bin, Homebrew, or PATH",
            )
        }
    }

    override fun executor(cwd: String): DetailedShellExecutor = runner.detailedExecutor(cwd)

    override fun streamingExecutor(cwd: String): StreamingShellExecutor = runner.streamingExecutor(cwd)

    private fun enrichedMacosPath(): String? {
        val current = System.getenv("PATH").orEmpty()
        val existing = current.split(File.pathSeparatorChar).filter { it.isNotBlank() }
        val prepended = EXTRA_BIN_DIRS.filter { it !in existing && File(it).isDirectory }
        if (prepended.isEmpty()) return null
        return (prepended + existing).joinToString(File.pathSeparator)
    }

    private companion object {
        val EXTRA_BIN_DIRS = listOf("/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin")
    }
}

class MacosHostPlatform(
    paths: PathLayout,
    customShellPath: String? = null,
) : BaseHostPlatform(paths, PosixProcessTreeKiller()) {
    private val macosShell = MacosShellBackend(
        logsDir = paths.shellLogsDir,
        processKiller = processKiller,
        onRegisterProcess = { proc, ownGroup -> trackedProcesses[proc] = ownGroup },
        onUnregisterProcess = { proc -> trackedProcesses.remove(proc) },
        customShellPath = customShellPath,
    )

    override val shell: ShellBackend get() = macosShell

    override val profile: OsProfile by lazy {
        val resolved = macosShell.resolveShellPath() ?: "/bin/zsh"
        val shellName = File(resolved).name
        val arch = System.getProperty("os.arch").orEmpty().ifBlank { "aarch64" }
        val user = System.getProperty("user.name").orEmpty().ifBlank { "user" }
        val userHome = System.getProperty("user.home").orEmpty()
        OsProfile(
            os = OsKind.MACOS,
            osName = System.getProperty("os.name").orEmpty().ifBlank { "macOS" },
            arch = arch,
            shellName = shellName,
            shellPath = resolved,
            promptLines = listOf(
                "Host OS: macOS ($arch)",
                "Shell: $resolved ($shellName, BSD/Darwin userland)",
                "User: $user (home: $userHome)",
                "SuperPI Data Home: ${paths.homeDir.absolutePath}",
                "Commands run natively on the host macOS environment in the active bot working directory.",
            ),
            shellToolProfile = ShellToolProfile(
                toolDescription = "Execute a command in the host macOS $shellName shell for the current working directory. Returns merged stdout and stderr, ending with [exit: N]. Output is truncated to the last 2000 lines or 50KB (with complete log path saved when exceeded). Optionally provide timeout in seconds.",
                guidelines = listOf(
                    "Check the [exit: N] marker on every shell result; investigate failures before moving on.",
                    "Commands run directly in the host macOS $shellName environment (note BSD flags vs GNU, e.g., sed -i '' or Homebrew tools).",
                    "Always provide a short plain-language description argument so the user sees what is happening.",
                ),
            ),
        )
    }
}

internal object MacosServiceManager : HostServiceManager {
    const val LABEL = "io.github.whywood.${AppBrand.CLI_NAME}"

    override val label: String = "macOS LaunchAgent ($LABEL)"

    fun plistFile(): File {
        val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
        return File(home, "Library/LaunchAgents/$LABEL.plist")
    }

    fun renderPlistContent(
        paths: PathLayout,
        port: Int,
        execPrefix: List<String> = LauncherLocator.resolveExecutable(windows = false),
    ): String {
        val args = execPrefix +
            listOf("--home", paths.homeDir.absolutePath, "serve") +
            if (port > 0) listOf("--port", port.toString()) else emptyList()
        val logFile = HostServiceManagers.defaultLogFile(paths).absolutePath
        val argsXml = args.joinToString("\n") { arg ->
            "      <string>${HostServiceManagers.renderXmlText(arg)}</string>"
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
            appendLine("""<plist version="1.0">""")
            appendLine("""  <dict>""")
            appendLine("""    <key>Label</key>""")
            appendLine("""    <string>${HostServiceManagers.renderXmlText(LABEL)}</string>""")
            appendLine("""    <key>ProgramArguments</key>""")
            appendLine("""    <array>""")
            appendLine(argsXml)
            appendLine("""    </array>""")
            appendLine("""    <key>EnvironmentVariables</key>""")
            appendLine("""    <dict>""")
            appendLine("""      <key>${HostServiceManagers.renderXmlText(AppBrand.ENV_HOME)}</key>""")
            appendLine("""      <string>${HostServiceManagers.renderXmlText(paths.homeDir.absolutePath)}</string>""")
            appendLine("""      <key>PATH</key>""")
            appendLine("""      <string>/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>""")
            appendLine("""    </dict>""")
            appendLine("""    <key>RunAtLoad</key>""")
            appendLine("""    <true/>""")
            appendLine("""    <key>KeepAlive</key>""")
            appendLine("""    <dict>""")
            appendLine("""      <key>SuccessfulExit</key>""")
            appendLine("""      <false/>""")
            appendLine("""    </dict>""")
            appendLine("""    <key>StandardOutPath</key>""")
            appendLine("""    <string>${HostServiceManagers.renderXmlText(logFile)}</string>""")
            appendLine("""    <key>StandardErrorPath</key>""")
            appendLine("""    <string>${HostServiceManagers.renderXmlText(logFile)}</string>""")
            appendLine("""  </dict>""")
            appendLine("""</plist>""")
        }
    }

    override fun install(paths: PathLayout, port: Int): Result<File> = runCatching {
        paths.logsDir.mkdirs()
        val plist = plistFile()
        plist.parentFile?.mkdirs()
        plist.writeText(renderPlistContent(paths, port), Charsets.UTF_8)
        val domain = guiDomain()
        runLaunchctl("bootout", "$domain/$LABEL")
        val bootstrap = runLaunchctl("bootstrap", domain, plist.absolutePath)
        if (bootstrap.exitCode != 0) {
            val legacy = runLaunchctl("load", "-w", plist.absolutePath)
            if (legacy.exitCode != 0) {
                error(bootstrap.output.ifBlank { legacy.output.ifBlank { "launchctl bootstrap failed" } })
            }
        }
        plist
    }

    override fun uninstall(paths: PathLayout): Result<Unit> = runCatching {
        val domain = guiDomain()
        val plist = plistFile()
        runLaunchctl("bootout", "$domain/$LABEL")
        if (plist.exists()) {
            runLaunchctl("unload", "-w", plist.absolutePath)
            plist.delete()
        }
    }

    override fun start(paths: PathLayout): CommandExecOutput {
        val domain = guiDomain()
        val plist = plistFile()
        if (runLaunchctl("print", "$domain/$LABEL").exitCode != 0 && plist.isFile) {
            runLaunchctl("bootstrap", domain, plist.absolutePath)
        }
        val res = runLaunchctl("kickstart", "-k", "$domain/$LABEL")
        return if (res.exitCode == 0) res else runLaunchctl("start", LABEL)
    }

    override fun stop(paths: PathLayout): CommandExecOutput {
        val domain = guiDomain()
        val res = runLaunchctl("bootout", "$domain/$LABEL")
        return if (res.exitCode == 0) res else runLaunchctl("stop", LABEL)
    }

    override fun status(paths: PathLayout): CommandExecOutput {
        val domain = guiDomain()
        val res = runLaunchctl("print", "$domain/$LABEL")
        return if (res.exitCode == 0) res else runLaunchctl("list", LABEL)
    }

    override fun logs(paths: PathLayout, follow: Boolean, lines: Int): Int =
        HostServiceManagers.tailLogFile(HostServiceManagers.defaultLogFile(paths), lines = lines, follow = follow)

    private fun guiDomain(): String {
        val uid = runCatching {
            val proc = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: "501"
        return "gui/$uid"
    }

    private fun runLaunchctl(vararg args: String): CommandExecOutput {
        val proc = ProcessBuilder(listOf("launchctl") + args)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return CommandExecOutput(code, output.trim())
    }
}
