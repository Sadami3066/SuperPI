package io.github.whywood.superpi.host.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.whywood.superpi.data.relay.RelayError
import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.host.config.HostSettings
import io.github.whywood.superpi.host.core.CheckSeverity
import io.github.whywood.superpi.host.core.DoctorReport
import io.github.whywood.superpi.host.core.DoctorService
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.platform.HostPlatform
import io.github.whywood.superpi.host.platform.HostPlatforms
import io.github.whywood.superpi.host.platform.HostServiceManagers
import io.github.whywood.superpi.host.platform.OsKind
import io.github.whywood.superpi.host.server.LocalControlClient
import io.github.whywood.superpi.host.server.LocalControlServer
import io.github.whywood.superpi.runtime.McpProbeState
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

internal class CliRuntimeEnv(
    val homeOverride: File? = null,
    val out: PrintStream = System.out,
    val err: PrintStream = System.err,
    val platformFactory: (File?) -> HostPlatform = { HostPlatforms.detect(it) },
)

internal class ControllerHolder(
    val env: CliRuntimeEnv,
    private val cliHomeDir: File? = null,
) {
    fun withController(homeArg: File? = null, block: suspend (SuperPiController) -> Int): Int {
        val platform = env.platformFactory(homeArg ?: cliHomeDir ?: env.homeOverride)
        val controller = SuperPiController(platform)
        return runBlocking {
            try {
                block(controller)
            } finally {
                runCatching { controller.shutdown() }
            }
        }
    }
}

class SuperPiRootCommand internal constructor(
    private val env: CliRuntimeEnv = CliRuntimeEnv(),
) : CliktCommand(name = AppBrand.CLI_NAME) {
    override val invokeWithoutSubcommand: Boolean = true

    private val homeDir by option("--home", help = "Override data directory (default: ~/.super-pi or \$${AppBrand.ENV_HOME})")
        .file(canBeFile = false, mustExist = false)
    private val cwdOption by option("--cwd", help = "Working directory for session/bot resolution")
        .file(canBeFile = false, mustExist = false)
    private val printFlag by option("-p", "--print", help = "Run a single non-interactive turn and exit")
        .flag(default = false)
    private val modeOption by option("--mode", help = "Output mode: interactive, text, json, or rpc")
    private val continueFlag by option("-c", "--continue", help = "Continue the most recent session in cwd")
        .flag(default = false)
    private val resumeId by option("-r", "--resume", help = "Resume a specific session by ID prefix")
    private val modelSpec by option("--model", help = "Override model as provider/model")
    private val thinkingRaw by option("--thinking", help = "Thinking level: off, low, medium, high, xhigh, max")
    private val superFlag by option("--super", help = "Use the Super coordinator thread instead of a Bot session")
        .flag(default = false)
    private val botSelector by option("--bot", help = "Target a registered Bot by id, name, or directory")
    private val noSession by option("--no-session", help = "Delete the one-shot session file after completion")
        .flag(default = false)
    private val versionFlag by option("--version", "-V", help = "Print version and exit")
        .flag(default = false)
    private val promptParts by argument("prompt", help = "Optional prompt text for one-shot -p mode").multiple()

    init {
        subcommands(
            ServeCommand(),
            WebCommand(),
            StatusCommand(),
            InitCommand(),
            DoctorCommand(),
            BotCommand(),
            ProviderCommand(),
            SessionCommand(),
            ConfigCommand(),
            RelayCommand(),
            McpCommand(),
            MemoryCommand(),
            ServiceCommand(),
        )
    }

    override fun help(context: Context): String =
        "${AppBrand.DISPLAY_NAME} Host CLI — local coding & automation agent with Bots, Memory v3, and Relay sync."

    override fun run() {
        currentContext.findOrSetObject { ControllerHolder(env, cliHomeDir = homeDir) }
        if (versionFlag) {
            env.out.println("${AppBrand.CLI_NAME} ${AppBrand.VERSION}")
            throw ProgramResult(0)
        }
        if (currentContext.invokedSubcommand != null) return

        val mode = parseOutputMode(modeOption, printFlag, env.err)
        val thinking = parseThinking(thinkingRaw, env.err)
        if (mode == CliOutputMode.INTERACTIVE && promptParts.isNotEmpty()) {
            env.err.println(
                "Unknown command or unexpected arguments: ${promptParts.joinToString(" ")}. " +
                    "Use -p \"<prompt>\" for one-shot headless prompts or --help for commands.",
            )
            throw ProgramResult(2)
        }
        val workDir = (cwdOption ?: File(System.getProperty("user.dir").orEmpty().ifBlank { "." })).absoluteFile
        val launchOptions = SessionLaunchOptions(
            cwd = workDir,
            botSelector = botSelector,
            continueLast = continueFlag,
            resumeSessionId = resumeId,
            modelSpec = modelSpec,
            thinking = thinking,
            superMode = superFlag,
            noSession = noSession,
        )

        val exitCode = ControllerHolder(env, cliHomeDir = homeDir).withController(homeDir) { controller ->
            when (mode) {
                CliOutputMode.RPC -> RpcRunner.run(controller, workDir, out = env.out)
                CliOutputMode.TEXT, CliOutputMode.JSON -> {
                    val prompt = readHeadlessPrompt(promptParts)
                    if (prompt.isBlank()) {
                        env.err.println("Error: no prompt provided for headless mode. Pass a prompt or pipe stdin.")
                        return@withController 2
                    }
                    try {
                        HeadlessRunner.runSingleTurn(controller, launchOptions, prompt, mode, out = env.out, err = env.err)
                    } catch (err: IllegalArgumentException) {
                        env.err.println("Error: ${err.message}")
                        2
                    }
                }
                CliOutputMode.INTERACTIVE -> {
                    try {
                        ReplRunner.run(controller, launchOptions, out = env.out, err = env.err)
                    } catch (err: IllegalArgumentException) {
                        env.err.println("Error: ${err.message}")
                        2
                    }
                }
            }
        }
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    private fun parseOutputMode(raw: String?, isPrint: Boolean, err: PrintStream): CliOutputMode {
        val normalized = raw?.trim()?.lowercase()
        if (normalized == null) {
            return if (isPrint) CliOutputMode.TEXT else CliOutputMode.INTERACTIVE
        }
        return when (normalized) {
            "interactive" -> if (isPrint) CliOutputMode.TEXT else CliOutputMode.INTERACTIVE
            "text" -> CliOutputMode.TEXT
            "json" -> CliOutputMode.JSON
            "rpc" -> CliOutputMode.RPC
            else -> {
                err.println("Invalid --mode '$raw'. Expected interactive, text, json, or rpc.")
                throw ProgramResult(2)
            }
        }
    }

    private fun parseThinking(raw: String?, err: PrintStream): ThinkingLevel? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ThinkingLevel.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: run {
            err.println("Invalid --thinking '$raw'. Expected off, low, medium, high, xhigh, or max.")
            throw ProgramResult(2)
        }
    }

    private fun readHeadlessPrompt(args: List<String>): String {
        val argText = args.joinToString(" ").trim()
        val stdinText = if (shouldReadPipedStdin(hasArgs = argText.isNotEmpty())) {
            BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)).readText().trim()
        } else {
            ""
        }
        return listOf(argText, stdinText).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun shouldReadPipedStdin(hasArgs: Boolean): Boolean {
        if (System.console() != null) return false
        val fd0 = Path.of("/proc/self/fd/0")
        if (Files.exists(fd0)) {
            val target = runCatching { Files.readSymbolicLink(fd0).toString() }.getOrDefault("")
            if (target == "/dev/null") return false
            if (target.startsWith("pipe:")) return true
            val attrs = runCatching { Files.readAttributes(fd0, BasicFileAttributes::class.java) }.getOrNull()
            if (attrs != null && attrs.isRegularFile) return true
            if (target.startsWith("/dev/pts/") || target.startsWith("/dev/tty")) return false
        }
        return !hasArgs || runCatching { System.`in`.available() > 0 }.getOrDefault(false)
    }
}

private class ServeCommand : CliktCommand(name = "serve") {
    private val holder by requireObject<ControllerHolder>()
    private val portOption by option("--port", help = "Localhost control port (0 picks an available port)")
        .int()

    override fun help(context: Context): String = "Run the background host daemon (Relay, timers, Memory sync, localhost control API)."

    override fun run() {
        val shutdownDone = CountDownLatch(1)
        val code = try {
            holder.withController { controller ->
                controller.startDaemonServices()
                val requestedPort = portOption ?: controller.settingsStore.current().servePort
                val stopSignal = CompletableDeferred<Unit>()
                val server = LocalControlServer(
                    controller = controller,
                    requestedPort = requestedPort,
                    onShutdownRequested = { stopSignal.complete(Unit) },
                )
                val boundPort = server.start()
                val hook = Thread {
                    stopSignal.complete(Unit)
                    runCatching { shutdownDone.await(10, TimeUnit.SECONDS) }
                }
                Runtime.getRuntime().addShutdownHook(hook)
                holder.env.out.println("${AppBrand.CLI_NAME} serve listening on http://127.0.0.1:$boundPort (PID ${ProcessHandle.current().pid()})")
                holder.env.out.println("Press Ctrl+C to stop.")
                try {
                    stopSignal.await()
                } finally {
                    runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
                    server.stop()
                }
                0
            }
        } finally {
            shutdownDone.countDown()
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class WebCommand : CliktCommand(name = "web") {
    private val holder by requireObject<ControllerHolder>()
    private val portOption by option("--port", help = "Localhost port (0 picks an available port)")
        .int()
    private val noOpen by option("--no-open", help = "Do not automatically open the system browser")
        .flag(default = false)

    override fun help(context: Context): String = "Open the Super PI Web UI backed by the local control server."

    override fun run() {
        val shutdownDone = CountDownLatch(1)
        val code = try {
            holder.withController { controller ->
                val controlClient = LocalControlClient(controller.paths.serveLockFile)
                val (existingLock, liveStatus) = try {
                    val lock = controlClient.activeLock()
                    val status = if (lock != null) controlClient.fetchStatus() else null
                    lock to status
                } finally {
                    controlClient.close()
                }
                if (existingLock != null && liveStatus != null) {
                    val url = "http://127.0.0.1:${existingLock.port}/#token=${existingLock.token}"
                    holder.env.out.println("${AppBrand.DISPLAY_NAME} Web UI (attached to PID ${liveStatus.pid}): $url")
                    if (!noOpen) openSystemBrowser(url, controller.platform.profile.os, controller.paths.homeDir)
                    return@withController 0
                }

                controller.startDaemonServices()
                val requestedPort = portOption ?: controller.settingsStore.current().servePort
                val stopSignal = CompletableDeferred<Unit>()
                val server = LocalControlServer(
                    controller = controller,
                    requestedPort = requestedPort,
                    onShutdownRequested = { stopSignal.complete(Unit) },
                )
                val boundPort = server.start()
                val url = "http://127.0.0.1:$boundPort/#token=${server.bearerToken}"
                val hook = Thread {
                    stopSignal.complete(Unit)
                    runCatching { shutdownDone.await(10, TimeUnit.SECONDS) }
                }
                Runtime.getRuntime().addShutdownHook(hook)
                holder.env.out.println("${AppBrand.DISPLAY_NAME} Web UI listening on $url (PID ${ProcessHandle.current().pid()})")
                holder.env.out.println("Press Ctrl+C to stop.")
                val launchFile = if (!noOpen) openSystemBrowser(url, controller.platform.profile.os, controller.paths.homeDir) else null
                try {
                    stopSignal.await()
                } finally {
                    runCatching { launchFile?.delete() }
                    runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
                    server.stop()
                }
                0
            }
        } finally {
            shutdownDone.countDown()
        }
        if (code != 0) throw ProgramResult(code)
    }

    private fun openSystemBrowser(url: String, os: OsKind, homeDir: File): File? {
        // Avoid passing the bearer token directly on the command line (/proc/<pid>/cmdline)
        // by opening a 0600 local HTML redirector that immediately replaces location.
        val redirectFile = runCatching {
            homeDir.mkdirs()
            val file = File(homeDir, ".web-launch-${ProcessHandle.current().pid()}.html")
            val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
            val html = """<!doctype html><meta charset="utf-8"><script>window.location.replace("$safeUrl");</script>"""
            file.writeText(html, Charsets.UTF_8)
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(
                    file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
            file.deleteOnExit()
            file
        }.getOrNull()
        val target = redirectFile?.toURI()?.toString() ?: url
        val cmd = when (os) {
            OsKind.MACOS -> listOf("open", target)
            OsKind.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
            OsKind.LINUX -> listOf("xdg-open", target)
        }
        runCatching {
            ProcessBuilder(cmd)
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        return redirectFile
    }
}

private class StatusCommand : CliktCommand(name = "status") {
    private val holder by requireObject<ControllerHolder>()

    override fun help(context: Context): String = "Show running daemon status or local configuration summary."

    override fun run() {
        val code = holder.withController { controller ->
            val live = LocalControlClient(controller.paths.serveLockFile).use { it.fetchStatus() }
            if (live != null) {
                holder.env.out.println("${AppBrand.CLI_NAME} ${live.version} · daemon RUNNING (PID ${live.pid}, http://127.0.0.1:${live.port})")
                holder.env.out.println("  Home:   ${live.homeDir}")
                holder.env.out.println("  Relay:  ${live.relayState} (${live.relayOrigin ?: "not paired"})")
                holder.env.out.println("  Bots:   ${live.botsCount} · Active sessions: ${live.busySessionsCount} · Timers: ${live.activeTimersCount}")
                holder.env.out.println("  MCP:    ${live.mcpServersCount} server(s) · Memory: ${live.memoryEntities} entities, ${live.memoryEvents} events")
            } else {
                controller.bootstrap()
                val relay = controller.relayStore.read()
                val bots = controller.botRegistry.listAllBots()
                val mcpCount = controller.mcpConfigStore.load().entries.size
                val mem = runCatching { controller.memoryStore.overview() }.getOrNull()
                holder.env.out.println("${AppBrand.CLI_NAME} ${AppBrand.VERSION} · daemon STOPPED")
                holder.env.out.println("  Home:   ${controller.paths.homeDir.absolutePath}")
                holder.env.out.println("  Relay:  ${relay?.origin ?: "not paired"}")
                holder.env.out.println("  Bots:   ${bots.size} · MCP: $mcpCount server(s)")
                if (mem != null) {
                    holder.env.out.println("  Memory: ${mem.entityCount} entities, ${mem.activeEvents} events, ${mem.activeBlocks} blocks")
                }
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class InitCommand : CliktCommand(name = "init") {
    private val holder by requireObject<ControllerHolder>()
    private val providerOption by option("--provider", help = "Default provider ID (e.g. openai, anthropic, deepseek)")
    private val apiKeyOption by option("--api-key", help = "API key to store encrypted in secrets.enc")
    private val modelOption by option("--model", help = "Default model ID for the chosen provider")
    private val deviceNameOption by option("--device-name", help = "Device display name")
    private val relayLinkOption by option("--relay-link", help = "Optional Relay pairing URL to enroll immediately")

    override fun help(context: Context): String = "Initialize ~/.super-pi, configure a model provider API key, and optionally pair Relay."

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val console = System.console()
            val stdinReader = if (console == null) BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)) else null
            val currentSettings = controller.settingsStore.current()
            val interactiveConsole = if (providerOption == null) console else null

            val deviceName = deviceNameOption?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (interactiveConsole == null) currentSettings.deviceName else promptConsoleLine(interactiveConsole, "Device name", currentSettings.deviceName)

            val summaries = controller.providerCatalog.summaries
            val providers = summaries.map { it.id }
            val defaultProvider = providerOption?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (interactiveConsole == null) {
                    currentSettings.defaultProvider
                } else {
                    promptConsoleLine(
                        interactiveConsole,
                        "Default provider (${providers.take(5).joinToString("/")})",
                        currentSettings.defaultProvider,
                    )
                }

            val suggestedModel = if (defaultProvider != currentSettings.defaultProvider) {
                controller.providerCatalog.models(defaultProvider).firstOrNull()?.id
                    ?: currentSettings.defaultModel.ifBlank { ProviderCatalog.DEFAULT_MODEL_ID }
            } else {
                currentSettings.defaultModel.ifBlank { ProviderCatalog.DEFAULT_MODEL_ID }
            }
            val defaultModelId = modelOption?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (interactiveConsole == null) suggestedModel else promptConsoleLine(interactiveConsole, "Default model ID", suggestedModel)

            val existingKey = controller.credentials.readApiKey(defaultProvider)
            val apiKey = apiKeyOption?.trim()?.takeIf { it.isNotEmpty() } ?: when {
                console != null -> {
                    val hint = if (!existingKey.isNullOrBlank()) "keep existing" else "optional, press Enter to skip"
                    holder.env.out.print("API key for $defaultProvider [$hint]: ")
                    holder.env.out.flush()
                    String(console.readPassword() ?: CharArray(0)).trim().takeIf { it.isNotEmpty() }
                }
                stdinReader != null && runCatching { System.`in`.available() > 0 }.getOrDefault(false) -> {
                    runCatching { stdinReader.readLine() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                else -> null
            }
            if (!apiKey.isNullOrBlank()) {
                controller.apiKeyStore.save(defaultProvider, apiKey)
            }

            controller.settingsStore.update {
                it.copy(
                    deviceName = deviceName,
                    defaultProvider = defaultProvider,
                    defaultModel = defaultModelId,
                )
            }
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Saved settings to ${controller.paths.settingsFile.absolutePath}")

            val link = relayLinkOption?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (interactiveConsole != null) {
                    promptConsoleLine(interactiveConsole, "Relay pairing link (optional, Enter to skip)", "")
                } else {
                    ""
                }

            if (link.isNotBlank()) {
                val ok = enrollRelay(controller, link, null, deviceName, holder.env.out, holder.env.err)
                if (!ok) return@withController 1
                LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }

    private fun promptConsoleLine(console: java.io.Console, label: String, default: String): String {
        val suffix = if (default.isNotBlank()) " [$default]" else ""
        holder.env.out.print("$label$suffix: ")
        holder.env.out.flush()
        val line = runCatching { console.readLine() }.getOrNull()?.trim().orEmpty()
        return line.ifEmpty { default }
    }
}

private class DoctorCommand : CliktCommand(name = "doctor") {
    private val holder by requireObject<ControllerHolder>()
    private val jsonFlag by option("--json", help = "Emit report as JSON").flag(default = false)
    private val noMcpProbe by option("--no-mcp-probe", help = "Skip live MCP server handshakes").flag(default = false)

    override fun help(context: Context): String = "Run environment, credentials, Memory v3, Bot, Relay, and MCP diagnostics."

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val report = DoctorService(controller, controller.platform, controller.extensionRegistry)
                .run(probeMcpServers = !noMcpProbe)
            if (jsonFlag) {
                val json = Json { prettyPrint = true; encodeDefaults = true }
                holder.env.out.println(json.encodeToString(DoctorReport.serializer(), report))
            } else {
                holder.env.out.println("${AppBrand.CLI_NAME} doctor · ${report.version} · ${report.os}")
                holder.env.out.println("Home: ${report.homeDir}\n")
                report.checks.forEach { item ->
                    val badge = when (item.status) {
                        CheckSeverity.PASS -> "[OK]  "
                        CheckSeverity.WARN -> "[WARN]"
                        CheckSeverity.FAIL -> "[FAIL]"
                    }
                    holder.env.out.println("$badge ${item.name}: ${item.summary}")
                    item.fixHint?.let { holder.env.out.println("       -> $it") }
                }
            }
            if (report.hasFailures) 1 else 0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class BotCommand : CliktCommand(name = "bot") {
    override fun help(context: Context): String = "Manage Bot workspaces (home + mounted local project directories)."

    init {
        subcommands(BotAddCommand(), BotListCommand(), BotRmCommand(), BotConfigCommand())
    }

    override fun run() = Unit
}

private class BotAddCommand : CliktCommand(name = "add") {
    private val holder by requireObject<ControllerHolder>()
    private val dir by argument("dir", help = "Local project directory to mount as a Bot")
        .file(canBeFile = false, mustExist = false)
    private val name by option("--name", help = "Display name for the Bot")
    private val modelSpec by option("--model", help = "Default model (provider/model)")
    private val intro by option("--intro", help = "Short description of this Bot's role")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val model = modelSpec?.let { raw ->
                HeadlessRunner.parseModelSpec(raw) ?: run {
                    holder.env.err.println("Invalid --model '$raw'. Expected provider/model.")
                    return@withController 2
                }
            }
            try {
                val added = controller.botRegistry.addBot(dir, name = name, defaultModel = model, intro = intro)
                controller.superTaskStore.ensure()
                controller.superTaskStore.setBotEnabled(SuperRuntime.localBotRef(added.id), true)
                LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
                holder.env.out.println("Added bot '${added.name}' (${added.id}) -> ${added.cwd}")
                0
            } catch (err: IllegalArgumentException) {
                holder.env.err.println("Error: ${err.message}")
                2
            }
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class BotListCommand : CliktCommand(name = "list") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val bots = controller.botRegistry.listAllBots()
            bots.forEach { ws ->
                val policy = controller.botRegistry.policyForCwd(ws.cwd)
                val scope = policy.effectiveMemoryScope().wire
                val model = ws.defaultModel?.let { "${it.providerId}/${it.modelId}" } ?: "global default"
                val hasDoc = controller.botRegistry.agentDocStore.read(ws.cwd) != null
                val homeTag = if (ws.isHome) " [home]" else ""
                holder.env.out.println("${ws.id}$homeTag · ${ws.name}")
                holder.env.out.println("  cwd: ${ws.cwd} | model: $model | memory: $scope | AGENTS.md: ${if (hasDoc) "yes" else "no"}")
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class BotRmCommand : CliktCommand(name = "rm") {
    private val holder by requireObject<ControllerHolder>()
    private val target by argument("target", help = "Bot ID, name, or directory path")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            try {
                val removed = controller.botRegistry.removeBot(target)
                if (removed == null) {
                    holder.env.err.println("Bot not found: $target")
                    return@withController 1
                }
                LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
                holder.env.out.println("Removed bot '${removed.name}' (${removed.id}).")
                0
            } catch (err: IllegalArgumentException) {
                holder.env.err.println("Error: ${err.message}")
                2
            }
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class BotConfigCommand : CliktCommand(name = "config") {
    private val holder by requireObject<ControllerHolder>()
    private val target by argument("target", help = "Bot ID, name, or directory path")
    private val modelOpt by option("--model", help = "Set provider/model, or 'default' to clear override")
    private val memoryScopeOpt by option("--memory-scope", help = "personal, work_only, or off")
    private val memoryDispatchOpt by option("--memory-dispatch", help = "true or false")
    private val mcpDeltas by option("--mcp", help = "Toggle MCP access (+server or -server)").multiple()
    private val skillDeltas by option("--skill", help = "Toggle Skill access (+skill or -skill)").multiple()
    private val superEnabledOpt by option("--super-enabled", help = "Allow Super coordinator to delegate to this bot (true or false)")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val ws = controller.botRegistry.findBot(target)
            if (ws == null) {
                holder.env.err.println("Bot not found: $target")
                return@withController 1
            }

            if (modelOpt != null) {
                val parsed: ModelConfig? = if (modelOpt.equals("default", ignoreCase = true) || modelOpt.equals("clear", ignoreCase = true)) {
                    null
                } else {
                    HeadlessRunner.parseModelSpec(modelOpt!!) ?: run {
                        holder.env.err.println("Invalid --model '$modelOpt'. Expected provider/model or 'default'.")
                        return@withController 2
                    }
                }
                controller.botRegistry.setBotDefaultModel(ws.id, parsed)
            }

            val currentPolicy = controller.botRegistry.policyForCwd(ws.cwd)
            val canonical = controller.paths.normalizePath(ws.cwd)
            var nextPolicy = if (ws.isHome) currentPolicy.copy(allCapabilities = true) else currentPolicy

            if (memoryScopeOpt != null) {
                val scope = MemoryAccessScope.entries.firstOrNull {
                    it.wire.equals(memoryScopeOpt!!.trim(), ignoreCase = true) ||
                        it.name.equals(memoryScopeOpt!!.trim(), ignoreCase = true)
                } ?: run {
                    holder.env.err.println("Invalid --memory-scope '$memoryScopeOpt'. Use personal, work_only, or off.")
                    return@withController 2
                }
                nextPolicy = nextPolicy.copy(memoryScope = scope)
            }
            if (memoryDispatchOpt != null) {
                val dispatch = memoryDispatchOpt!!.trim().lowercase().toBooleanStrictOrNull() ?: run {
                    holder.env.err.println("Invalid --memory-dispatch '$memoryDispatchOpt'. Use true or false.")
                    return@withController 2
                }
                nextPolicy = nextPolicy.copy(memoryDispatch = dispatch)
            }
            if (mcpDeltas.isNotEmpty()) {
                nextPolicy = nextPolicy.copy(
                    mcpServers = applyToggleSet(nextPolicy.allCapabilities, nextPolicy.mcpServers, mcpDeltas),
                )
            }
            if (skillDeltas.isNotEmpty()) {
                nextPolicy = nextPolicy.copy(
                    skills = applyToggleSet(nextPolicy.allCapabilities, nextPolicy.skills, skillDeltas),
                )
            }
            if (superEnabledOpt != null) {
                val enabled = superEnabledOpt!!.trim().lowercase().toBooleanStrictOrNull() ?: run {
                    holder.env.err.println("Invalid --super-enabled '$superEnabledOpt'. Use true or false.")
                    return@withController 2
                }
                controller.superTaskStore.ensure()
                controller.superTaskStore.setBotEnabled(SuperRuntime.localBotRef(ws.id), enabled)
            }
            controller.botRegistry.policyStore.update(canonical, nextPolicy)
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Updated bot '${ws.name}' (${ws.id}).")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }

    private fun applyToggleSet(allCapabilities: Boolean, currentSet: Set<String>, tokens: List<String>): Set<String> {
        val next = currentSet.toMutableSet()
        tokens.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            val enable = !token.startsWith("-")
            val name = token.removePrefix("+").removePrefix("-").trim()
            if (name.isEmpty()) return@forEach
            if (allCapabilities) {
                // In allCapabilities=true mode, the set lists EXCLUDED items.
                if (enable) next.remove(name) else next.add(name)
            } else {
                // In allowlist mode, the set lists ALLOWED items.
                if (enable) next.add(name) else next.remove(name)
            }
        }
        return next
    }
}

private class RelayCommand : CliktCommand(name = "relay") {
    override fun help(context: Context): String = "Manage Relay pairing and cross-device connection."

    init {
        subcommands(
            RelayPairCommand(),
            RelayLoginCommand(),
            RelayStatusCommand(),
            RelayDevicesCommand(),
            RelayLogoutCommand(),
        )
    }

    override fun run() = Unit
}

private class RelayPairCommand : CliktCommand(name = "pair") {
    private val holder by requireObject<ControllerHolder>()
    private val link by argument("link", help = "Pairing URL from another device; omit to generate a QR code for your phone")
        .optional()
    private val deviceName by option("--name", help = "Device display name on Relay")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val rawLink = link?.trim()?.takeIf { it.isNotEmpty() }
            if (rawLink != null) {
                val resolvedName = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: controller.settingsStore.current().deviceName
                val ok = enrollRelay(controller, rawLink, null, resolvedName, holder.env.out, holder.env.err)
                if (!ok) return@withController 1
                LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
                return@withController 0
            }

            // Generate a pairing code + terminal QR for mobile scanning.
            val viaDaemon = LocalControlClient(controller.paths.serveLockFile).use { it.requestPairCode() }
            if (viaDaemon != null) {
                viaDaemon.fold(
                    onSuccess = { resp ->
                        holder.env.out.println("Scan with SuperPI Android or paste this link:\n${resp.link}\n")
                        holder.env.out.println(TerminalQr.render(resp.link))
                        0
                    },
                    onFailure = { err ->
                        holder.env.err.println("Error: ${err.message}")
                        1
                    },
                )
            } else {
                if (controller.relayStore.read() == null) {
                    holder.env.err.println("Not paired with a Relay server yet. Pass a pairing link or run `${AppBrand.CLI_NAME} relay login`.")
                    return@withController 1
                }
                withTimeoutOrNull(2_000L) { controller.relayController.state.first { it.account != null } }
                controller.relayController.dismissPairCode()
                controller.relayController.generatePairCode()
                val settled = withTimeoutOrNull(15_000L) {
                    controller.relayController.state.first { !it.generatingPairCode }
                }
                val generatedLink = settled?.pairLink
                if (generatedLink == null) {
                    holder.env.err.println("Failed to generate pair code (${settled?.error ?: RelayError.Unreachable}).")
                    1
                } else {
                    holder.env.out.println("Scan with SuperPI Android or paste this link:\n$generatedLink\n")
                    holder.env.out.println(TerminalQr.render(generatedLink))
                    0
                }
            }
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class RelayLoginCommand : CliktCommand(name = "login") {
    private val holder by requireObject<ControllerHolder>()
    private val url by option("--url", help = "Relay server origin URL (e.g. https://relay.example.com)")
    private val secret by option("--secret", help = "Relay login secret")
    private val fingerprint by option("--fingerprint", help = "Optional SHA-256 TLS CA fingerprint")
    private val deviceName by option("--name", help = "Device display name on Relay")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val origin = url?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                holder.env.err.println("--url is required.")
                return@withController 2
            }
            val loginSecret = secret?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                holder.env.err.println("--secret is required.")
                return@withController 2
            }
            val fullLink = if (!fingerprint.isNullOrBlank()) {
                val fp = fingerprint!!.trim()
                when {
                    '#' in origin -> "$origin&fp=$fp"
                    "/pair" in origin -> "${origin.trimEnd('/')}#fp=$fp"
                    else -> "${origin.trimEnd('/')}/pair#fp=$fp"
                }
            } else {
                origin
            }
            val resolvedName = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: controller.settingsStore.current().deviceName
            val ok = enrollRelay(controller, fullLink, loginSecret, resolvedName, holder.env.out, holder.env.err)
            if (ok) LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            if (ok) 0 else 1
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class RelayStatusCommand : CliktCommand(name = "status") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            val live = LocalControlClient(controller.paths.serveLockFile).use { it.fetchStatus() }
            val account = controller.relayStore.read()
            if (account == null) {
                holder.env.out.println("Relay: not paired")
            } else {
                holder.env.out.println("Relay origin: ${account.origin}")
                holder.env.out.println("Device:       ${account.deviceName} (${account.deviceId})")
                holder.env.out.println("Account ID:   ${account.accountId}")
                if (live != null) {
                    holder.env.out.println("Live state:   ${live.relayState} (daemon PID ${live.pid})")
                } else {
                    holder.env.out.println("Live state:   daemon stopped (run `${AppBrand.CLI_NAME} serve` to go online)")
                }
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class RelayLogoutCommand : CliktCommand(name = "logout") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val account = controller.relayStore.read()
            if (account == null) {
                holder.env.out.println("Not paired to any Relay server.")
                return@withController 0
            }
            val token = runCatching { controller.relayStore.token() }.getOrNull()
            if (!token.isNullOrBlank()) {
                runCatching { controller.relayClient.revokeDevice(account, token, account.deviceId) }
            }
            controller.relayStore.clear()
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Signed out of Relay.")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class McpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context): String = "Manage stdio and HTTP MCP servers in ~/.super-pi/mcp.json."

    init {
        subcommands(McpListCommand(), McpAddCommand(), McpRmCommand(), McpTestCommand())
    }

    override fun run() = Unit
}

private class McpListCommand : CliktCommand(name = "list") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            val snap = controller.mcpConfigStore.load()
            if (snap.entries.isEmpty()) {
                holder.env.out.println("No MCP servers configured.")
            } else {
                snap.entries.keys.sorted().forEach { name ->
                    val cfg = snap.servers[name]
                    val summary = when (cfg) {
                        is McpServerConfig.Http -> "http: ${cfg.url} (enabled=${cfg.enabled})"
                        is McpServerConfig.Stdio -> "stdio: ${cfg.command} ${cfg.args.joinToString(" ")} (enabled=${cfg.enabled})".trim()
                        null -> "invalid config"
                    }
                    holder.env.out.println("$name -> $summary")
                }
            }
            if (snap.errors.isNotEmpty()) {
                snap.errors.forEach { holder.env.err.println("Warning: $it") }
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class McpAddCommand : CliktCommand(name = "add") {
    private val holder by requireObject<ControllerHolder>()
    private val name by argument("name", help = "MCP server name")
    private val url by option("--url", help = "HTTP MCP endpoint URL")
    private val stdio by option("--stdio", help = "Executable command for stdio MCP server")
    private val args by option("--arg", help = "Command argument (repeatable)").multiple()
    private val envPairs by option("--env", help = "Environment variable KEY=VALUE (repeatable)").multiple()
    private val headerPairs by option("--header", help = "HTTP header Name:Value (repeatable)").multiple()
    private val cwd by option("--cwd", help = "Working directory for stdio MCP process")

    override fun run() {
        val code = holder.withController { controller ->
            if ((url == null) == (stdio == null)) {
                holder.env.err.println("Specify exactly one of --url or --stdio.")
                return@withController 2
            }
            val cfg = try {
                if (url != null) {
                    val headers = parseKeyValues(headerPairs, ':', "Header")
                    McpServerConfig.Http(url = url!!.trim(), headers = headers)
                } else {
                    val envMap = parseKeyValues(envPairs, '=', "Env pair")
                    McpServerConfig.Stdio(
                        command = stdio!!.trim(),
                        args = args,
                        env = envMap,
                        cwd = cwd?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
            } catch (err: IllegalArgumentException) {
                holder.env.err.println("Error: ${err.message}")
                return@withController 2
            }
            try {
                controller.mcpConfigStore.upsert(name.trim(), cfg)
            } catch (err: IllegalArgumentException) {
                holder.env.err.println("Error: ${err.message}")
                return@withController 2
            }
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Saved MCP server '$name'.")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }

    private fun parseKeyValues(items: List<String>, delimiter: Char, label: String): Map<String, String> =
        buildMap {
            items.forEach { item ->
                val idx = item.indexOf(delimiter)
                if (idx <= 0) {
                    throw IllegalArgumentException("$label must be formatted as Key${delimiter}Value: '$item'")
                }
                put(item.substring(0, idx).trim(), item.substring(idx + 1).trim())
            }
        }
}

private class McpRmCommand : CliktCommand(name = "rm") {
    private val holder by requireObject<ControllerHolder>()
    private val name by argument("name", help = "MCP server name")

    override fun run() {
        val code = holder.withController { controller ->
            val key = name.trim()
            val snap = controller.mcpConfigStore.load()
            if (key !in snap.entries) {
                holder.env.err.println("MCP server not found: $key")
                return@withController 1
            }
            controller.mcpConfigStore.remove(key)
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Removed MCP server '$key'.")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class McpTestCommand : CliktCommand(name = "test") {
    private val holder by requireObject<ControllerHolder>()
    private val name by argument("name", help = "Optional MCP server name to test").optional()

    override fun run() {
        val code = holder.withController { controller ->
            val snap = controller.mcpConfigStore.load()
            if (snap.errors.isNotEmpty()) {
                snap.errors.forEach { holder.env.err.println("Config error: $it") }
                return@withController 1
            }
            val servers = snap.servers
            val targetName = name?.trim()?.takeIf { it.isNotEmpty() }
            val targets = if (targetName != null) {
                val cfg = servers[targetName] ?: run {
                    holder.env.err.println("MCP server not found: $targetName")
                    return@withController 1
                }
                mapOf(targetName to cfg)
            } else {
                servers
            }
            if (targets.isEmpty()) {
                holder.env.out.println("No MCP servers configured.")
                return@withController 0
            }
            var anyFailed = false
            targets.entries.sortedBy { it.key }.forEach { (serverName, cfg) ->
                if (!cfg.enabled) {
                    holder.env.out.println("[SKIP] $serverName (disabled in mcp.json)")
                    return@forEach
                }
                val result = controller.extensionRegistry.probeMcp(serverName, cfg)
                when (result.state) {
                    McpProbeState.OK -> holder.env.out.println("[OK]   $serverName (${result.toolCount} tools, ${result.serverName ?: "ready"})")
                    McpProbeState.AUTH_REQUIRED -> holder.env.out.println("[AUTH] $serverName requires OAuth authorization")
                    McpProbeState.FAILED, McpProbeState.UNSUPPORTED -> {
                        anyFailed = true
                        holder.env.out.println("[FAIL] $serverName: ${result.error ?: "connection failed"}")
                    }
                }
            }
            if (anyFailed) 1 else 0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class MemoryCommand : CliktCommand(name = "memory") {
    override fun help(context: Context): String = "Inspect or sync Memory v3 state."

    init {
        subcommands(MemoryStatusCommand(), MemorySyncCommand())
    }

    override fun run() = Unit
}

private class MemoryStatusCommand : CliktCommand(name = "status") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            controller.memoryStore.ensure()
            val ov = controller.memoryStore.overview()
            holder.env.out.println("Memory v3 · ${controller.paths.memoryDir.absolutePath}")
            holder.env.out.println("  Blocks:    ${ov.activeBlocks} active, ${ov.archivedBlocks} archived")
            holder.env.out.println("  Events:    ${ov.activeEvents} active")
            holder.env.out.println("  Graph:     ${ov.entityCount} entities, ${ov.relationCount} relations")
            holder.env.out.println("  Pending:   ${ov.pendingProposals} proposals, ${ov.pendingDispatchJobs} dispatch jobs")
            holder.env.out.println("  Consolidation: ${ov.lastConsolidationAt ?: "never"}")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class MemorySyncCommand : CliktCommand(name = "sync") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            if (controller.relayStore.read() == null) {
                holder.env.err.println("Not paired to a Relay server. Run `${AppBrand.CLI_NAME} relay pair <link>` first.")
                return@withController 1
            }
            val ok = controller.relayMemorySync.syncNow(manual = true)
            val state = controller.relayMemorySync.state.value
            if (ok) {
                holder.env.out.println("Memory v3 synced successfully (${state.status.name.lowercase()}).")
                0
            } else {
                holder.env.err.println("Memory sync did not complete (status=${state.status}, error=${state.lastErrorCode ?: "none"}). Check `memory status` or account switch state.")
                1
            }
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ServiceCommand : CliktCommand(name = "service") {
    override fun help(context: Context): String =
        "Manage the OS background service (systemd / LaunchAgent / Task Scheduler) for `${AppBrand.CLI_NAME} serve`."

    init {
        subcommands(
            ServiceInstallCommand(),
            ServiceUninstallCommand(),
            ServiceStartCommand(),
            ServiceStopCommand(),
            ServiceStatusCommand(),
            ServiceLogsCommand(),
        )
    }

    override fun run() = Unit
}

private class ServiceInstallCommand : CliktCommand(name = "install") {
    private val holder by requireObject<ControllerHolder>()
    private val port by option("--port", help = "Fixed localhost control port (default: auto)")
        .int()
        .default(0)

    override fun run() {
        val code = holder.withController { controller ->
            val mgr = HostServiceManagers.forPlatform(controller.platform)
            val res = mgr.install(controller.paths, port)
            res.fold(
                onSuccess = { file ->
                    holder.env.out.println("Installed and enabled ${mgr.label}: ${file.absolutePath}")
                    holder.env.out.println("Start it with: ${AppBrand.CLI_NAME} service start")
                    0
                },
                onFailure = { err ->
                    holder.env.err.println("Failed to install service: ${err.message}")
                    1
                },
            )
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ServiceUninstallCommand : CliktCommand(name = "uninstall") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            val mgr = HostServiceManagers.forPlatform(controller.platform)
            mgr.uninstall(controller.paths).fold(
                onSuccess = {
                    holder.env.out.println("Removed ${mgr.label}.")
                    0
                },
                onFailure = { err ->
                    holder.env.err.println("Failed to uninstall service: ${err.message}")
                    1
                },
            )
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ServiceStartCommand : CliktCommand(name = "start") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            val mgr = HostServiceManagers.forPlatform(controller.platform)
            val res = mgr.start(controller.paths)
            if (res.output.isNotBlank()) holder.env.out.println(res.output)
            res.exitCode
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ServiceStopCommand : CliktCommand(name = "stop") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            val mgr = HostServiceManagers.forPlatform(controller.platform)
            val res = mgr.stop(controller.paths)
            if (res.output.isNotBlank()) holder.env.out.println(res.output)
            res.exitCode
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ServiceStatusCommand : CliktCommand(name = "status") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            val mgr = HostServiceManagers.forPlatform(controller.platform)
            val res = mgr.status(controller.paths)
            if (res.output.isNotBlank()) holder.env.out.println(res.output)
            res.exitCode
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ServiceLogsCommand : CliktCommand(name = "logs") {
    private val holder by requireObject<ControllerHolder>()
    private val follow by option("-f", "--follow", help = "Stream live service logs").flag(default = false)

    override fun run() {
        val code = holder.withController { controller ->
            val mgr = HostServiceManagers.forPlatform(controller.platform)
            mgr.logs(controller.paths, follow = follow)
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private suspend fun enrollRelay(
    controller: SuperPiController,
    link: String,
    loginSecret: String?,
    deviceName: String,
    out: PrintStream,
    err: PrintStream,
): Boolean {
    withTimeoutOrNull(1_000L) { controller.relayController.state.first { !it.signingIn } }
    controller.relayController.signIn(link, loginSecret, deviceName)
    val started = withTimeoutOrNull(2_000L) {
        controller.relayController.state.first { it.signingIn || it.error != null }
    }
    if (started != null && !started.signingIn && started.error != null) {
        err.println("Relay pairing failed: ${started.error!!.name}")
        return false
    }
    val settled = withTimeoutOrNull(20_000L) {
        controller.relayController.state.first { !it.signingIn && (it.error != null || it.account != null) }
    }
    val account = settled?.account
    return if (settled != null && settled.error == null && account != null) {
        out.println("Paired with Relay ${account.origin} as '${account.deviceName}' (${account.deviceId}).")
        true
    } else {
        err.println("Relay pairing failed: ${settled?.error?.name ?: "Timed out"}")
        false
    }
}
