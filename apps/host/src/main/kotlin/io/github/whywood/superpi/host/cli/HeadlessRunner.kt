package io.github.whywood.superpi.host.cli

import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import java.io.File
import java.io.PrintStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CliOutputMode {
    INTERACTIVE,
    TEXT,
    JSON,
    RPC,
}

data class SessionLaunchOptions(
    val cwd: File,
    val botSelector: String? = null,
    val continueLast: Boolean = false,
    val resumeSessionId: String? = null,
    val modelSpec: String? = null,
    val thinking: ThinkingLevel? = null,
    val superMode: Boolean = false,
    val noSession: Boolean = false,
)

/**
 * Runs one-shot `-p` prompts in either clean plain-text output or structured JSONL (`--mode json`).
 */
object HeadlessRunner {
    suspend fun runSingleTurn(
        controller: SuperPiController,
        options: SessionLaunchOptions,
        prompt: String,
        outputMode: CliOutputMode,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
    ): Int = coroutineScope {
        controller.bootstrap()
        val resolvedBot = if (!options.botSelector.isNullOrBlank()) {
            val found = controller.botRegistry.findBot(options.botSelector)
                ?: throw IllegalArgumentException("Bot not found: ${options.botSelector}")
            controller.botRegistry.resolveForCwd(File(found.cwd))
        } else {
            controller.botRegistry.resolveForCwd(options.cwd)
        }
        val workDir = File(resolvedBot.effectiveCwd)
        val expandedPrompt = controller.expandPromptCommand(workDir.absolutePath, prompt)

        if (options.noSession && (options.continueLast || !options.resumeSessionId.isNullOrBlank())) {
            throw IllegalArgumentException("--no-session cannot be combined with --continue or --resume.")
        }

        if (options.superMode) {
            return@coroutineScope runSuperSingleTurn(
                controller = controller,
                workDir = workDir,
                options = options,
                expandedPrompt = expandedPrompt,
                outputMode = outputMode,
                out = out,
                err = err,
            )
        }

        val opened = openTargetSession(controller, workDir, options)
        val sessionId = opened.sessionId

        return@coroutineScope try {
            applyModelOverride(controller, sessionId, options.modelSpec, options.thinking, resolvedBot.workspace?.defaultModel)

            if (outputMode == CliOutputMode.JSON) {
                emitJson(
                    out,
                    buildJsonObject {
                        put("type", "session_started")
                        put("sessionId", sessionId)
                        put("mode", "bot")
                        put("cwd", opened.cwd)
                        resolvedBot.workspace?.let { put("botId", it.id); put("botName", it.name) }
                    },
                )
            }

            val outcome = collectTurnEvents(
                events = controller.runtimeHost.events
                    .filter { it.sessionId == sessionId }
                    .map { it.event },
                outputMode = outputMode,
                out = out,
                err = err,
            ) {
                controller.runtimeHost.send(sessionId, expandedPrompt)
            }

            val usage = controller.runtimeHost.usage(sessionId)
            if (outputMode == CliOutputMode.JSON && usage != null) {
                emitJson(
                    out,
                    buildJsonObject {
                        put("type", "usage")
                        put("sessionId", sessionId)
                        put("inputTokens", usage.inputTokens)
                        put("outputTokens", usage.outputTokens)
                        put("contextTokens", usage.contextTokens)
                    },
                )
            }
            if (outcome.ok) 0 else 1
        } finally {
            if (options.noSession) {
                runCatching { controller.runtimeHost.closeSession(sessionId) }
                opened.filePath?.let { path -> runCatching { controller.sessionCatalog.delete(path) } }
            }
        }
    }

    private suspend fun runSuperSingleTurn(
        controller: SuperPiController,
        workDir: File,
        options: SessionLaunchOptions,
        expandedPrompt: String,
        outputMode: CliOutputMode,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        controller.superOrchestrator.ensureStarted()
        val rawModel = options.modelSpec
        if (rawModel != null) {
            val parsed = parseModelSpec(rawModel)
                ?: throw IllegalArgumentException("Invalid model format '$rawModel'. Expected provider/model (e.g. openai/gpt-5).")
            val thinking = options.thinking ?: controller.settingsStore.read().thinkingLevel
            controller.superOrchestrator.changeSuperModel(parsed.providerId, parsed.modelId, thinking)
        } else if (options.thinking != null) {
            controller.superOrchestrator.changeSuperThinking(options.thinking)
        }

        if (outputMode == CliOutputMode.JSON) {
            emitJson(
                out,
                buildJsonObject {
                    put("type", "session_started")
                    put("sessionId", SuperRuntime.SUPER_THREAD_SESSION_ID)
                    put("mode", "super")
                    put("cwd", workDir.absolutePath)
                },
            )
        }

        val outcome = collectTurnEvents(
            events = controller.superOrchestrator.superThreadEvents,
            outputMode = outputMode,
            out = out,
            err = err,
        ) {
            controller.superOrchestrator.send(expandedPrompt, emptyList())
        }
        return if (outcome.ok) 0 else 1
    }

    private suspend fun collectTurnEvents(
        events: Flow<RuntimeEvent>,
        outputMode: CliOutputMode,
        out: PrintStream,
        err: PrintStream,
        sendAction: suspend () -> Unit,
    ): RunOutcome = coroutineScope {
        val done = CompletableDeferred<RunOutcome>()
        val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
            var printedAnyText = false
            events.collect { event ->
                when (event) {
                    is RuntimeEvent.ReasoningDelta -> if (outputMode == CliOutputMode.JSON) {
                        emitJson(
                            out,
                            buildJsonObject {
                                put("type", "thinking_delta")
                                put("runId", event.runId)
                                put("text", event.text)
                            },
                        )
                    }

                    is RuntimeEvent.TextDelta -> {
                        printedAnyText = true
                        if (outputMode == CliOutputMode.JSON) {
                            emitJson(
                                out,
                                buildJsonObject {
                                    put("type", "assistant_delta")
                                    put("runId", event.runId)
                                    put("text", event.text)
                                },
                            )
                        } else {
                            out.print(event.text)
                            out.flush()
                        }
                    }

                    is RuntimeEvent.ToolStarted -> {
                        if (outputMode == CliOutputMode.JSON) {
                            emitJson(
                                out,
                                buildJsonObject {
                                    put("type", "tool_start")
                                    put("runId", event.runId)
                                    put("callId", event.callId)
                                    put("name", event.name)
                                    event.rawArguments?.let { put("arguments", it) }
                                },
                            )
                        } else {
                            val desc = event.arguments["description"]?.takeIf { it.isNotBlank() }
                            err.println(if (desc != null) "[tool] ${event.name}: $desc" else "[tool] ${event.name}")
                        }
                    }

                    is RuntimeEvent.ToolFinished -> {
                        if (outputMode == CliOutputMode.JSON) {
                            emitJson(
                                out,
                                buildJsonObject {
                                    put("type", "tool_end")
                                    put("runId", event.runId)
                                    put("callId", event.callId)
                                    put("name", event.name)
                                    put("isError", event.isError)
                                    event.durationMs?.let { put("durationMs", it) }
                                    put("output", event.result)
                                },
                            )
                        } else if (event.isError) {
                            err.println("[tool error] ${event.name}: ${event.result.lineSequence().firstOrNull().orEmpty()}")
                        }
                    }

                    is RuntimeEvent.RunFinished -> {
                        if (outputMode == CliOutputMode.TEXT) {
                            if (!printedAnyText && event.text.isNotBlank()) {
                                out.println(event.text)
                            } else if (printedAnyText) {
                                out.println()
                            }
                        } else {
                            emitJson(
                                out,
                                buildJsonObject {
                                    put("type", "turn_done")
                                    put("runId", event.runId)
                                    put("status", event.status.name)
                                    put("text", event.text)
                                },
                            )
                        }
                        done.complete(RunOutcome(ok = event.status == RunStatus.DONE, error = null))
                    }

                    is RuntimeEvent.RunFailed -> {
                        if (outputMode == CliOutputMode.JSON) {
                            emitJson(
                                out,
                                buildJsonObject {
                                    put("type", "error")
                                    put("runId", event.runId)
                                    put("message", event.message)
                                },
                            )
                        } else {
                            err.println("Error: ${event.message}")
                        }
                        done.complete(RunOutcome(ok = false, error = event.message))
                    }

                    else -> Unit
                }
            }
        }
        try {
            sendAction()
            done.await()
        } finally {
            collectorJob.cancel()
        }
    }

    internal suspend fun openTargetSession(
        controller: SuperPiController,
        workDir: File,
        options: SessionLaunchOptions,
    ) = when {
        !options.resumeSessionId.isNullOrBlank() -> {
            val all = controller.sessionCatalog.listAllExisting()
            val target = all.firstOrNull { it.id == options.resumeSessionId || it.id.startsWith(options.resumeSessionId) }
                ?: throw IllegalArgumentException("Session not found: ${options.resumeSessionId}")
            controller.runtimeHost.openSession(target.filePath, target.cwd.ifBlank { workDir.absolutePath })
        }

        options.continueLast -> {
            val match = controller.sessionCatalog.list(workDir.absolutePath)
                .filter { !it.archived }
                .maxByOrNull { it.updatedAt }
            if (match != null) {
                controller.runtimeHost.openSession(match.filePath, workDir.absolutePath)
            } else {
                controller.runtimeHost.newSessionInDirectory(workDir.absolutePath)
            }
        }

        else -> controller.runtimeHost.newSessionInDirectory(workDir.absolutePath)
    }

    internal suspend fun applyModelOverride(
        controller: SuperPiController,
        sessionId: String,
        modelSpec: String?,
        thinkingOverride: ThinkingLevel?,
        botDefault: ModelConfig?,
    ) {
        val base = botDefault ?: controller.settingsStore.read()
        val parsed = if (modelSpec != null) {
            parseModelSpec(modelSpec)
                ?: throw IllegalArgumentException("Invalid model format '$modelSpec'. Expected provider/model (e.g. openai/gpt-5).")
        } else {
            null
        }
        val targetProvider = parsed?.providerId ?: base.providerId
        val targetModel = parsed?.modelId ?: base.modelId
        val targetThinking = thinkingOverride ?: base.thinkingLevel
        if (modelSpec != null || thinkingOverride != null || botDefault != null) {
            controller.runtimeHost.setSessionModel(sessionId, targetProvider, targetModel, targetThinking)
        }
    }

    internal fun parseModelSpec(raw: String): ModelConfig? {
        val trimmed = raw.trim()
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash >= trimmed.lastIndex) return null
        return ModelConfig(
            providerId = trimmed.substring(0, slash).trim(),
            modelId = trimmed.substring(slash + 1).trim(),
            thinkingLevel = ThinkingLevel.OFF,
        )
    }

    private fun emitJson(out: PrintStream, obj: JsonObject) {
        synchronized(out) {
            out.println(obj.toString())
            out.flush()
        }
    }

    private data class RunOutcome(val ok: Boolean, val error: String?)
}
