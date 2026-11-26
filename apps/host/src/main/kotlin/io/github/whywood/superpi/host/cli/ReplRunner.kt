package io.github.whywood.superpi.host.cli

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.host.config.ResolvedBotContext
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import java.io.File
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder

/**
 * Interactive JLine REPL (`super-pi`) supporting live streaming deltas, concise raw tool cards,
 * double Ctrl+C safety, Super mode switching, and Bot-aware slash commands.
 */
object ReplRunner {
    suspend fun run(
        controller: SuperPiController,
        options: SessionLaunchOptions,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int = coroutineScope {
        controller.bootstrap()

        var resolvedBot = if (!options.botSelector.isNullOrBlank()) {
            val found = controller.botRegistry.findBot(options.botSelector)
                ?: throw IllegalArgumentException("Bot not found: ${options.botSelector}")
            controller.botRegistry.resolveForCwd(File(found.cwd))
        } else {
            controller.botRegistry.resolveForCwd(options.cwd)
        }
        if (options.noSession && (options.continueLast || !options.resumeSessionId.isNullOrBlank())) {
            throw IllegalArgumentException("--no-session cannot be combined with --continue or --resume.")
        }
        var workDir = File(resolvedBot.effectiveCwd)
        var superMode = options.superMode
        var opened = if (!superMode) {
            HeadlessRunner.openTargetSession(controller, workDir, options).also { session ->
                HeadlessRunner.applyModelOverride(
                    controller = controller,
                    sessionId = session.sessionId,
                    modelSpec = options.modelSpec,
                    thinkingOverride = options.thinking,
                    botDefault = resolvedBot.workspace?.defaultModel,
                )
            }
        } else {
            controller.superOrchestrator.ensureStarted()
            applySuperModelOptions(controller, options.modelSpec, options.thinking)
            null
        }
        val initialEphemeralPath = if (!superMode && options.noSession) opened?.filePath else null

        val terminal = buildTerminal()
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .variable(LineReader.HISTORY_FILE, controller.paths.replHistoryFile.toPath())
            .build()

        val activeTurnDone = AtomicReference<CompletableDeferred<Unit>?>(null)
        val turnRunning = AtomicBoolean(false)
        val activeSessionId = AtomicReference(opened?.sessionId)
        val isSuperActive = AtomicBoolean(superMode)

        terminal.handle(Terminal.Signal.INT) {
            if (turnRunning.get()) {
                if (isSuperActive.get()) {
                    controller.superOrchestrator.abortSuper()
                } else {
                    activeSessionId.get()?.let { controller.runtimeHost.stop(it) }
                }
            }
        }

        val answerCardUnsub = controller.extensionRegistry.onAnswerCard { card ->
            val label = card.title?.takeIf { it.isNotBlank() } ?: card.type
            out.println("\n[card] $label")
            out.flush()
        }

        val eventPrinter = EventStreamPrinter(out, err)
        val botCollector: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.runtimeHost.events
                .filter { wrapped -> !isSuperActive.get() && wrapped.sessionId == activeSessionId.get() }
                .collect { wrapped ->
                    if (eventPrinter.handle(wrapped.event)) {
                        turnRunning.set(false)
                        activeTurnDone.getAndSet(null)?.complete(Unit)
                    }
                }
        }
        var superCollector: Job? = null
        fun ensureSuperCollector() {
            if (superCollector?.isActive == true) return
            superCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                controller.superOrchestrator.superThreadEvents
                    .filter { isSuperActive.get() }
                    .collect { event ->
                        if (eventPrinter.handle(event)) {
                            turnRunning.set(false)
                            activeTurnDone.getAndSet(null)?.complete(Unit)
                        }
                    }
            }
        }
        if (superMode) ensureSuperCollector()

        try {
            printBanner(controller, resolvedBot, opened?.sessionId, superMode, out)
            var lastIdleInterruptAt = 0L

            while (true) {
                val promptLabel = buildPrompt(resolvedBot, superMode)
                val rawLine = try {
                    withContext(Dispatchers.IO) { reader.readLine(promptLabel) }
                } catch (_: UserInterruptException) {
                    if (turnRunning.get()) {
                        if (superMode) controller.superOrchestrator.abortSuper()
                        else opened?.sessionId?.let { controller.runtimeHost.stop(it) }
                        err.println("\n[aborted]")
                        continue
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastIdleInterruptAt <= 3_000L) {
                        out.println("\nBye.")
                        break
                    }
                    lastIdleInterruptAt = now
                    out.println("\nPress Ctrl+C again or type /exit to quit.")
                    continue
                } catch (_: EndOfFileException) {
                    out.println()
                    break
                }

                val line = rawLine.trim()
                if (line.isEmpty()) continue
                lastIdleInterruptAt = 0L

                if (line.startsWith("/")) {
                    val outcome = try {
                        handleSlashCommand(
                            line = line,
                            controller = controller,
                            workDir = workDir,
                            resolvedBot = resolvedBot,
                            sessionId = opened?.sessionId,
                            superMode = superMode,
                            out = out,
                            err = err,
                            onSwitchSession = { newOpened, newBot ->
                                resolvedBot = newBot
                                workDir = File(newBot.effectiveCwd)
                                opened = newOpened
                                activeSessionId.set(newOpened.sessionId)
                            },
                            onSwitchSuper = { nextSuper ->
                                if (nextSuper) ensureSuperCollector()
                                superMode = nextSuper
                                isSuperActive.set(nextSuper)
                            },
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        err.println("Command failed: ${e.message ?: e::class.simpleName}")
                        SlashResult.HANDLED
                    }
                    when (outcome) {
                        SlashResult.EXIT -> break
                        SlashResult.HANDLED -> continue
                        is SlashResult.SEND_EXPANDED -> {
                            runTurnSafely(
                                controller = controller,
                                superMode = superMode,
                                sessionId = opened?.sessionId,
                                prompt = outcome.prompt,
                                turnRunning = turnRunning,
                                activeTurnDone = activeTurnDone,
                                eventPrinter = eventPrinter,
                                err = err,
                            )
                        }
                    }
                    continue
                }

                runTurnSafely(
                    controller = controller,
                    superMode = superMode,
                    sessionId = opened?.sessionId,
                    prompt = line,
                    turnRunning = turnRunning,
                    activeTurnDone = activeTurnDone,
                    eventPrinter = eventPrinter,
                    err = err,
                )
            }
            0
        } finally {
            answerCardUnsub()
            botCollector.cancel()
            superCollector?.cancel()
            if (initialEphemeralPath != null) {
                opened?.let { runCatching { controller.runtimeHost.closeSession(it.sessionId) } }
                runCatching { controller.sessionCatalog.delete(initialEphemeralPath) }
            }
            runCatching { terminal.close() }
        }
    }

    private suspend fun runTurnSafely(
        controller: SuperPiController,
        superMode: Boolean,
        sessionId: String?,
        prompt: String,
        turnRunning: AtomicBoolean,
        activeTurnDone: AtomicReference<CompletableDeferred<Unit>?>,
        eventPrinter: EventStreamPrinter,
        err: PrintStream,
    ) {
        try {
            runTurn(controller, superMode, sessionId, prompt, turnRunning, activeTurnDone, eventPrinter)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            err.println("Error: ${error.message ?: error::class.java.simpleName}\n")
        }
    }

    private suspend fun runTurn(
        controller: SuperPiController,
        superMode: Boolean,
        sessionId: String?,
        prompt: String,
        turnRunning: AtomicBoolean,
        activeTurnDone: AtomicReference<CompletableDeferred<Unit>?>,
        eventPrinter: EventStreamPrinter,
    ) {
        if (!superMode && sessionId == null) {
            throw IllegalStateException("No active Bot session. Type /bot or /new first.")
        }
        val deferred = CompletableDeferred<Unit>()
        eventPrinter.resetTurn()
        turnRunning.set(true)
        activeTurnDone.set(deferred)
        try {
            if (superMode) {
                controller.superOrchestrator.send(prompt, emptyList())
            } else {
                controller.runtimeHost.send(sessionId!!, prompt)
            }
            deferred.await()
        } finally {
            turnRunning.set(false)
            activeTurnDone.set(null)
        }
    }

    private sealed interface SlashResult {
        data object HANDLED : SlashResult
        data object EXIT : SlashResult
        data class SEND_EXPANDED(val prompt: String) : SlashResult
    }

    private suspend fun handleSlashCommand(
        line: String,
        controller: SuperPiController,
        workDir: File,
        resolvedBot: ResolvedBotContext,
        sessionId: String?,
        superMode: Boolean,
        out: PrintStream,
        err: PrintStream,
        onSwitchSession: (io.github.whywood.superpi.runtime.OpenedSession, ResolvedBotContext) -> Unit,
        onSwitchSuper: (Boolean) -> Unit,
    ): SlashResult {
        val body = line.removePrefix("/").trim()
        val space = body.indexOf(' ')
        val cmd = (if (space < 0) body else body.substring(0, space)).lowercase()
        val arg = if (space < 0) "" else body.substring(space + 1).trim()

        return when (cmd) {
            "exit", "quit", "q" -> SlashResult.EXIT
            "help", "?" -> {
                printHelp(out)
                SlashResult.HANDLED
            }

            "model" -> {
                if (arg.isBlank()) {
                    if (superMode) {
                        val t = controller.superOrchestrator.threadState.value
                        out.println("Super model: ${t.providerId}/${t.modelId} (thinking=${t.thinkingLevel.name.lowercase()})")
                    } else if (sessionId != null) {
                        val snap = controller.runtimeHost.sessionModel(sessionId)
                        if (snap != null) {
                            out.println("Session model: ${snap.providerId}/${snap.modelId} (thinking=${snap.thinkingLevel.name.lowercase()})")
                        }
                    }
                    val configured = controller.configuredProviderIds().sorted()
                    out.println("Configured providers: ${if (configured.isEmpty()) "none" else configured.joinToString(", ")}")
                } else {
                    val parsed = HeadlessRunner.parseModelSpec(arg)
                    if (parsed == null) {
                        err.println("Usage: /model <provider/model> (example: openai/gpt-5)")
                    } else if (superMode) {
                        val currentThinking = controller.superOrchestrator.threadState.value.thinkingLevel
                        controller.superOrchestrator.changeSuperModel(parsed.providerId, parsed.modelId, currentThinking)
                        out.println("Switched Super model to ${parsed.providerId}/${parsed.modelId}.")
                    } else if (sessionId != null) {
                        val currentThinking = controller.runtimeHost.sessionModel(sessionId)?.thinkingLevel ?: ThinkingLevel.OFF
                        controller.runtimeHost.setSessionModel(sessionId, parsed.providerId, parsed.modelId, currentThinking)
                        out.println("Switched session model to ${parsed.providerId}/${parsed.modelId}.")
                    }
                }
                SlashResult.HANDLED
            }

            "think" -> {
                if (arg.isBlank()) {
                    out.println("Usage: /think <off|low|medium|high|xhigh|max>")
                } else {
                    val level = ThinkingLevel.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }
                    if (level == null) {
                        err.println("Unknown thinking level '$arg'. Choose off, low, medium, high, xhigh, or max.")
                    } else if (superMode) {
                        controller.superOrchestrator.changeSuperThinking(level)
                        out.println("Super thinking level set to ${level.name.lowercase()}.")
                    } else if (sessionId != null) {
                        controller.runtimeHost.setSessionThinkingLevel(sessionId, level)
                        out.println("Thinking level set to ${level.name.lowercase()}.")
                    }
                }
                SlashResult.HANDLED
            }

            "super" -> {
                val target = when (arg.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    "" -> !superMode
                    else -> {
                        err.println("Usage: /super [on|off]")
                        return@handleSlashCommand SlashResult.HANDLED
                    }
                }
                if (target) {
                    controller.superOrchestrator.ensureStarted()
                } else if (sessionId == null) {
                    val fresh = controller.runtimeHost.newSessionInDirectory(workDir.absolutePath)
                    onSwitchSession(fresh, resolvedBot)
                }
                onSwitchSuper(target)
                out.println(if (target) "Switched to Super coordinator mode." else "Switched to Bot mode (${resolvedBot.workspace?.name ?: workDir.name}).")
                SlashResult.HANDLED
            }

            "bot" -> {
                if (arg.isBlank()) {
                    val bots = controller.botRegistry.listAllBots()
                    out.println("Current: ${resolvedBot.workspace?.let { "${it.name} (${it.id})" } ?: "ephemeral (${workDir.absolutePath})"}")
                    bots.forEach { ws ->
                        val marker = if (ws.id == resolvedBot.workspace?.id) "*" else " "
                        out.println("  $marker ${ws.id} · ${ws.name} -> ${ws.cwd}")
                    }
                } else {
                    val found = controller.botRegistry.findBot(arg)
                    if (found == null) {
                        err.println("Bot not found: $arg")
                    } else {
                        val nextResolved = controller.botRegistry.resolveForCwd(File(found.cwd))
                        val nextSession = controller.runtimeHost.newSessionInDirectory(nextResolved.effectiveCwd)
                        onSwitchSuper(false)
                        onSwitchSession(nextSession, nextResolved)
                        out.println("Switched to bot '${found.name}' (${found.cwd}), session ${nextSession.sessionId.take(8)}.")
                    }
                }
                SlashResult.HANDLED
            }

            "new" -> {
                if (superMode) {
                    controller.superOrchestrator.archiveAndResetSuper()
                    out.println("Archived and started a fresh Super thread.")
                } else {
                    val nextSession = controller.runtimeHost.newSessionInDirectory(workDir.absolutePath)
                    resolvedBot.workspace?.defaultModel?.let { m ->
                        controller.runtimeHost.setSessionModel(nextSession.sessionId, m.providerId, m.modelId, m.thinkingLevel)
                    }
                    onSwitchSession(nextSession, resolvedBot)
                    out.println("New session: ${nextSession.sessionId.take(8)} (${workDir.absolutePath})")
                }
                SlashResult.HANDLED
            }

            "sessions" -> {
                val list = controller.sessionCatalog.list(workDir.absolutePath)
                    .filter { !it.archived }
                    .sortedByDescending { it.updatedAt }
                    .take(12)
                if (list.isEmpty()) {
                    out.println("No saved sessions for ${workDir.absolutePath}.")
                } else {
                    list.forEach { s ->
                        val active = if (s.id == sessionId) "*" else " "
                        out.println("  $active ${s.id.take(8)} · ${s.title.ifBlank { "(untitled)" }}")
                    }
                }
                SlashResult.HANDLED
            }

            "resume" -> {
                if (arg.isBlank()) {
                    err.println("Usage: /resume <session-id-prefix>")
                } else {
                    val match = controller.sessionCatalog.listAllExisting()
                        .firstOrNull { it.id == arg || it.id.startsWith(arg) }
                    if (match == null) {
                        err.println("Session not found: $arg")
                    } else {
                        val openedSession = controller.runtimeHost.openSession(match.filePath, match.cwd.ifBlank { workDir.absolutePath })
                        val nextBot = controller.botRegistry.resolveForCwd(File(openedSession.cwd))
                        onSwitchSuper(false)
                        onSwitchSession(openedSession, nextBot)
                        out.println("Resumed session ${openedSession.sessionId.take(8)} (${openedSession.cwd}).")
                    }
                }
                SlashResult.HANDLED
            }

            "steer" -> {
                if (arg.isBlank()) {
                    err.println("Usage: /steer <message>")
                } else {
                    err.println("No active turn in progress — send your message directly instead of /steer.")
                }
                SlashResult.HANDLED
            }

            "followup" -> {
                if (arg.isBlank()) {
                    err.println("Usage: /followup <message>")
                } else {
                    err.println("No active turn in progress — send your message directly instead of /followup.")
                }
                SlashResult.HANDLED
            }

            "compact" -> {
                if (superMode) {
                    controller.superOrchestrator.compactSuper()
                    out.println("Compacted Super conversation.")
                } else if (sessionId != null) {
                    controller.runtimeHost.compact(sessionId)
                    out.println("Compacted current session.")
                }
                SlashResult.HANDLED
            }

            "mcp" -> {
                val snap = controller.mcpConfigStore.load()
                if (snap.entries.isEmpty()) {
                    out.println("No MCP servers configured. Add one with `${AppBrand.CLI_NAME} mcp add`.")
                } else {
                    val disabled = controller.settingsStore.current().disabledExtensions.toSet()
                    snap.entries.keys.sorted().forEach { name ->
                        val cfg = snap.servers[name]
                        val enabled = (cfg?.enabled != false) && ("mcp:$name" !in disabled)
                        out.println("  - $name [${if (enabled) "enabled" else "disabled"}]")
                    }
                }
                SlashResult.HANDLED
            }

            "memory" -> {
                val ov = controller.memoryStore.overview()
                val syncState = controller.relayMemorySync.state.value
                out.println("Memory v3: ${ov.entityCount} entities, ${ov.relationCount} relations, ${ov.activeEvents} events, ${ov.activeBlocks} blocks")
                out.println("Relay sync: ${syncState.status.name.lowercase()}")
                SlashResult.HANDLED
            }

            "reload" -> {
                controller.reloadAll()
                val snap = controller.extensionRegistry.snapshot(workDir.absolutePath)
                out.println("Reloaded: ${snap.skills.size} skill(s), ${snap.prompts.size} prompt(s), ${snap.mcp.entries.size} MCP server(s).")
                SlashResult.HANDLED
            }

            else -> {
                val expanded = controller.expandPromptCommand(workDir.absolutePath, line)
                if (expanded != line) {
                    SlashResult.SEND_EXPANDED(expanded)
                } else {
                    err.println("Unknown command '/$cmd'. Type /help for commands, or send text directly.")
                    SlashResult.HANDLED
                }
            }
        }
    }

    private suspend fun applySuperModelOptions(
        controller: SuperPiController,
        modelSpec: String?,
        thinking: ThinkingLevel?,
    ) {
        if (modelSpec != null) {
            val parsed = HeadlessRunner.parseModelSpec(modelSpec)
                ?: throw IllegalArgumentException("Invalid model format '$modelSpec'. Expected provider/model (e.g. openai/gpt-5).")
            val effectiveThinking = thinking ?: controller.settingsStore.read().thinkingLevel
            controller.superOrchestrator.changeSuperModel(parsed.providerId, parsed.modelId, effectiveThinking)
        } else if (thinking != null) {
            controller.superOrchestrator.changeSuperThinking(thinking)
        }
    }

    private suspend fun printBanner(
        controller: SuperPiController,
        resolved: ResolvedBotContext,
        sessionId: String?,
        superMode: Boolean,
        out: PrintStream,
    ) {
        val model = if (superMode) {
            val t = controller.superOrchestrator.threadState.value
            ModelConfig(t.providerId, t.modelId, t.thinkingLevel)
        } else if (sessionId != null) {
            val s = controller.runtimeHost.sessionModel(sessionId)
            if (s != null) ModelConfig(s.providerId, s.modelId, s.thinkingLevel) else controller.settingsStore.read()
        } else {
            controller.settingsStore.read()
        }
        val relay = controller.relayStore.read()
        val location = resolved.workspace?.let { "Bot: ${it.name} (${it.cwd})" }
            ?: "Directory: ${resolved.effectiveCwd} (temporary; run `${AppBrand.CLI_NAME} bot add .` to register)"
        out.println("${AppBrand.CLI_NAME} ${AppBrand.VERSION} · ${if (superMode) "Super" else "Bot"} mode")
        out.println("  $location")
        out.println("  Model: ${model.providerId}/${model.modelId} · Thinking: ${model.thinkingLevel.name.lowercase()}")
        out.println("  Relay: ${relay?.let { "${it.origin} (${it.deviceName})" } ?: "not paired"}")
        out.println("Type a message, or /help for commands. Ctrl+C aborts; /exit or Ctrl+D quits.\n")
    }

    private fun buildPrompt(resolved: ResolvedBotContext, superMode: Boolean): String =
        if (superMode) {
            "super> "
        } else {
            val label = resolved.workspace?.name ?: File(resolved.effectiveCwd).name.ifBlank { AppBrand.CLI_NAME }
            "$label> "
        }

    private fun printHelp(out: PrintStream) {
        out.println(
            """
            Commands:
              /help                  Show this command reference
              /model [provider/id]   Show or change current model
              /think <level>         Set thinking level (off|low|medium|high|xhigh|max)
              /super [on|off]        Toggle Super coordinator mode
              /bot [name|id|dir]     Show or switch registered Bot workspace
              /new                   Start a fresh session
              /sessions              List recent sessions in current directory
              /resume <id-prefix>    Resume a previous session
              /steer <message>       Redirect an active turn
              /followup <message>    Queue a follow-up prompt after the active turn
              /compact               Compact current conversation context
              /mcp                   Show configured MCP servers
              /memory                Show Memory v3 summary
              /reload                Reload settings, skills, prompts, and MCP
              /skill:<name> [args]   Run an installed skill directly
              /exit                  Exit ${AppBrand.CLI_NAME}
            """.trimIndent(),
        )
    }

    private fun buildTerminal(): Terminal =
        runCatching { TerminalBuilder.builder().system(true).build() }
            .getOrElse { TerminalBuilder.builder().dumb(true).build() }

    private class EventStreamPrinter(
        private val out: PrintStream,
        private val err: PrintStream,
    ) {
        @Volatile
        private var printedAnyText = false

        @Volatile
        private var atLineStart = true

        fun resetTurn() {
            printedAnyText = false
            atLineStart = true
        }

        fun handle(event: RuntimeEvent): Boolean {
            return when (event) {
                is RuntimeEvent.TextDelta -> {
                    printedAnyText = true
                    atLineStart = event.text.endsWith("\n")
                    out.print(event.text)
                    out.flush()
                    false
                }

                is RuntimeEvent.ToolStarted -> {
                    if (!atLineStart) {
                        out.println()
                        atLineStart = true
                    }
                    val desc = event.arguments["description"]?.takeIf { it.isNotBlank() }
                    val summary = desc ?: event.arguments.entries
                        .firstOrNull { it.key != "description" }
                        ?.let { "${it.key}=${it.value.take(60)}" }
                    err.println(if (summary != null) "[tool] ${event.name}: $summary" else "[tool] ${event.name}")
                    false
                }

                is RuntimeEvent.ToolFinished -> {
                    val status = if (event.isError) "err" else "done"
                    val duration = event.durationMs?.let { " (${it}ms)" }.orEmpty()
                    val preview = event.result.lineSequence().firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
                    err.println(
                        if (event.isError && preview.isNotBlank()) {
                            "[$status] ${event.name}$duration: $preview"
                        } else {
                            "[$status] ${event.name}$duration"
                        },
                    )
                    false
                }

                is RuntimeEvent.RunFinished -> {
                    if (!printedAnyText && event.text.isNotBlank()) {
                        out.println(event.text)
                    } else if (!atLineStart) {
                        out.println()
                    }
                    if (event.status == RunStatus.STOPPED) {
                        err.println("[turn aborted]")
                    }
                    out.println()
                    out.flush()
                    atLineStart = true
                    true
                }

                is RuntimeEvent.RunFailed -> {
                    if (!atLineStart) out.println()
                    err.println("Error: ${event.message}\n")
                    atLineStart = true
                    true
                }

                else -> false
            }
        }
    }
}
