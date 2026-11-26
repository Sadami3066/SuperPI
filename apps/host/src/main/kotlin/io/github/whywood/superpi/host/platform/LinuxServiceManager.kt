package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.host.brand.AppBrand
import java.io.File

internal data class CommandExecOutput(
    val exitCode: Int,
    val output: String,
)

/**
 * Manages Linux `systemd --user` background service registration for `super-pi serve`.
 */
internal object LinuxServiceManager : HostServiceManager {
    private const val SERVICE_FILE_NAME = "${AppBrand.CLI_NAME}.service"

    override val label: String = "Linux systemd --user ($SERVICE_FILE_NAME)"

    fun unitFile(): File {
        val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
        return File(home, ".config/systemd/user/$SERVICE_FILE_NAME")
    }

    fun resolveExecutable(): List<String> = LauncherLocator.resolveExecutable(windows = false)

    fun renderUnitContent(
        homeDir: File,
        port: Int,
        execPrefix: List<String> = resolveExecutable(),
    ): String {
        val execStart = (execPrefix + listOf("--home", homeDir.absolutePath, "serve") +
            if (port > 0) listOf("--port", port.toString()) else emptyList())
            .joinToString(" ") { systemdExecArg(it) }
        val homeEnv = systemdEnvPair(AppBrand.ENV_HOME, homeDir.absolutePath)
        return """
            [Unit]
            Description=${AppBrand.DISPLAY_NAME} Host Agent Service
            After=network-online.target
            Wants=network-online.target

            [Service]
            Type=simple
            ExecStart=$execStart
            Restart=on-failure
            RestartSec=3
            Environment=$homeEnv

            [Install]
            WantedBy=default.target
        """.trimIndent() + "\n"
    }

    override fun install(paths: PathLayout, port: Int): Result<File> = install(paths.homeDir, port)

    fun install(homeDir: File, port: Int): Result<File> = runCatching {
        val unit = unitFile()
        unit.parentFile?.mkdirs()
        unit.writeText(renderUnitContent(homeDir, port), Charsets.UTF_8)
        runSystemctl("daemon-reload")
        val enable = runSystemctl("enable", SERVICE_FILE_NAME)
        if (enable.exitCode != 0) {
            error(enable.output.ifBlank { "systemctl --user enable failed" })
        }
        unit
    }

    override fun uninstall(paths: PathLayout): Result<Unit> = uninstall()

    fun uninstall(): Result<Unit> = runCatching {
        runSystemctl("stop", SERVICE_FILE_NAME)
        runSystemctl("disable", SERVICE_FILE_NAME)
        unitFile().delete()
        runSystemctl("daemon-reload")
    }

    override fun start(paths: PathLayout): CommandExecOutput = start()

    fun start(): CommandExecOutput = runSystemctl("start", SERVICE_FILE_NAME)

    override fun stop(paths: PathLayout): CommandExecOutput = stop()

    fun stop(): CommandExecOutput = runSystemctl("stop", SERVICE_FILE_NAME)

    override fun status(paths: PathLayout): CommandExecOutput = status()

    fun status(): CommandExecOutput = runSystemctl("status", SERVICE_FILE_NAME, "--no-pager")

    override fun logs(paths: PathLayout, follow: Boolean, lines: Int): Int = logs(follow = follow, lines = lines)

    fun logs(follow: Boolean, lines: Int = 80): Int {
        val args = mutableListOf("journalctl", "--user", "-u", SERVICE_FILE_NAME, "-n", lines.toString())
        if (follow) args += "-f"
        val proc = ProcessBuilder(args).inheritIO().start()
        return proc.waitFor()
    }

    private fun runSystemctl(vararg args: String): CommandExecOutput {
        val proc = ProcessBuilder(listOf("systemctl", "--user") + args)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return CommandExecOutput(code, output.trim())
    }

    private fun systemdExecArg(arg: String): String {
        require(!arg.contains('\n') && !arg.contains('\r')) { "Service argument must not contain newlines" }
        val escaped = arg
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("%", "%%")
            .replace("$", "$$")
        if (escaped.all { it.isLetterOrDigit() || it in "-_./:=@" }) return escaped
        return "\"$escaped\""
    }

    private fun systemdEnvPair(key: String, value: String): String {
        require(!value.contains('\n') && !value.contains('\r')) { "Environment value must not contain newlines" }
        val escaped = "$key=$value"
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("%", "%%")
        return "\"$escaped\""
    }
}
