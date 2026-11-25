package io.github.whywood.superpi.screen

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.textResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MAX_BATCH_STEPS = 10
private const val BATCH_QUIET_MS = 250L
private const val BATCH_SETTLE_MAX_MS = 600L
private const val BATCH_OBSERVE_TIMEOUT_MS = 500L
private const val BATCH_FRAME_TIMEOUT_MS = 150L

private sealed interface ParsedBatchStep {
    val action: String
    val stopOnPackageChange: Boolean

    data class Tap(
        val selector: GuiSelector,
        val durationMs: Int,
        override val stopOnPackageChange: Boolean,
    ) : ParsedBatchStep {
        override val action: String get() = "tap"
    }

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
        override val stopOnPackageChange: Boolean,
    ) : ParsedBatchStep {
        override val action: String get() = "swipe"
    }

    data class Scroll(
        val direction: GuiScrollDirection,
        val amount: Int,
        val selector: GuiSelector?,
        override val stopOnPackageChange: Boolean,
    ) : ParsedBatchStep {
        override val action: String get() = "scroll"
    }

    data class Type(
        val selector: GuiSelector?,
        val text: String,
        override val stopOnPackageChange: Boolean,
    ) : ParsedBatchStep {
        override val action: String get() = "type"
    }

    data class Key(
        val key: String,
        override val stopOnPackageChange: Boolean,
    ) : ParsedBatchStep {
        override val action: String get() = "key"
    }

    data class Wait(
        val packageName: String?,
        val text: String?,
        val textGone: String?,
        val timeoutMs: Long,
        override val stopOnPackageChange: Boolean,
    ) : ParsedBatchStep {
        override val action: String get() = "wait"
    }

    data class Launch(
        val packageName: String,
        override val stopOnPackageChange: Boolean,
    ) : ParsedBatchStep {
        override val action: String get() = "launch"
    }
}

internal fun batchTool(
    controller: ScreenController,
    tracker: ScreenDiffTracker,
): CodingTool = CodingTool(
    name = "screen_batch",
    label = "screen_batch",
    description = "Run 1-$MAX_BATCH_STEPS screen actions sequentially on the selected screen and return step effects plus final screen diff; stops on failure or unexpected package change.",
    parameters = batchParameters,
    execute = { _, args, signal, _ ->
        signal?.throwIfAborted()
        runBatch(controller, tracker, args, signal)
    },
)

private suspend fun runBatch(
    controller: ScreenController,
    tracker: ScreenDiffTracker,
    args: JsonObject,
    signal: RunSignal?,
): AgentToolResult {
    val parsedSteps = parseBatchSteps(args)
    val stopOnNoChange = optionalBoolean(args, "stopOnNoChange") ?: false

    return controller.withExecutionLock {
        signal?.throwIfAborted()
        val displayBounds = readBatchFact(BATCH_FRAME_TIMEOUT_MS) {
            controller.effectDisplayInfo()?.let { GuiBounds(0, 0, it.width, it.height) }
        }
        val scopeKey = controller.screenScopeKey()
        val previousBaseline = tracker.lastBaseline(scopeKey)
        val initialObserved = readBatchFact(BATCH_OBSERVE_TIMEOUT_MS) {
            controller.observe(SCREEN_DEFAULT_MAX_DEPTH, GUI_MAX_NODES, null)
        }
        if (initialObserved == null) {
            val body = buildJsonObject {
                put("ok", false)
                put("completedSteps", 0)
                put("totalSteps", parsedSteps.size)
                put("stoppedReason", "observe_failed")
                put("hint", "Could not observe the current screen before running batch; no steps were executed")
                put("steps", buildJsonArray {})
            }
            return@withExecutionLock textResult(body.toString(), isError = true)
        }
        if (
            parsedSteps.first().stopOnPackageChange &&
            previousBaseline?.packageName != null &&
            initialObserved.snapshot.packageName != null &&
            initialObserved.snapshot.packageName != previousBaseline.packageName
        ) {
            val initialScreen = tracker.formatObserve(
                observed = initialObserved,
                scopeKey = scopeKey,
                scanLimits = SCREEN_DEFAULT_MAX_DEPTH to GUI_MAX_NODES,
                displayBounds = displayBounds,
            )
            val body = buildJsonObject {
                put("ok", false)
                put("completedSteps", 0)
                put("totalSteps", parsedSteps.size)
                put("stoppedReason", "package_changed")
                put("hint", "Foreground app changed since the last observation; no steps were executed")
                put("steps", buildJsonArray {})
                put("screen", initialScreen)
            }
            return@withExecutionLock textResult(body.toString(), isError = false)
        }

        var latestObserved: ScreenObserveResult? = initialObserved
        var currentSnapshot = initialObserved.snapshot
        var currentFrame = readBatchFact(BATCH_FRAME_TIMEOUT_MS) { controller.frameGrid() }

        val stepResults = mutableListOf<JsonObject>()
        var completedCount = 0
        var stoppedReason: String? = null

        for ((index, step) in parsedSteps.withIndex()) {
            signal?.throwIfAborted()

            val expectedPkgBefore = currentSnapshot.packageName
            val outcome = executeBatchStep(controller, step)
            if (outcome.failure != null) {
                stepResults += buildJsonObject {
                    put("step", index + 1)
                    put("action", step.action)
                    put("ok", false)
                    put("error", outcome.failure)
                }
                stoppedReason = "step_failed"
                break
            }

            completedCount++
            if (step !is ParsedBatchStep.Wait) {
                runCatching { controller.awaitQuiet(BATCH_QUIET_MS, BATCH_SETTLE_MAX_MS) }
                    .onFailure { if (it is CancellationException) throw it }
            }

            val afterObserved = readBatchFact(BATCH_OBSERVE_TIMEOUT_MS) {
                controller.observe(SCREEN_DEFAULT_MAX_DEPTH, GUI_MAX_NODES, null)
            }
            latestObserved = afterObserved
            val afterSnapshot = afterObserved?.snapshot
            val afterFrame = readBatchFact(BATCH_FRAME_TIMEOUT_MS) { controller.frameGrid() }
            val effect = outcome.verb?.let { verb ->
                evaluateScreenEffect(
                    verb = verb,
                    before = currentSnapshot,
                    after = afterSnapshot,
                    beforeFrame = currentFrame,
                    afterFrame = afterFrame,
                    displayBounds = displayBounds,
                )
            }

            stepResults += buildJsonObject {
                put("step", index + 1)
                put("action", step.action)
                put("ok", true)
                outcome.message?.let { put("message", it) }
                outcome.typeResult?.let { typed ->
                    putTypeResultFields(
                        selector = (step as? ParsedBatchStep.Type)?.selector,
                        outcome = typed,
                        includeHint = false,
                    )
                }
                effect?.let { put("effect", it.toJson()) }
            }

            if (afterFrame != null) currentFrame = afterFrame
            signal?.throwIfAborted()

            if (afterSnapshot == null) {
                stoppedReason = "observe_failed"
                break
            }
            currentSnapshot = afterSnapshot

            if (effect?.atEnd == true) {
                stoppedReason = "at_end"
                break
            }
            val pkgAfter = afterSnapshot.packageName
            if (step.stopOnPackageChange &&
                expectedPkgBefore != null &&
                pkgAfter != null &&
                pkgAfter != expectedPkgBefore
            ) {
                stoppedReason = "package_changed"
                break
            }
            if (stopOnNoChange && effect != null && !effect.changed && effect.kind == ScreenEffectKind.NO_CHANGE) {
                stoppedReason = "no_change"
                break
            }
        }

        val finalObserved = when (stoppedReason) {
            "step_failed" -> readBatchFact(BATCH_OBSERVE_TIMEOUT_MS) {
                controller.observe(SCREEN_DEFAULT_MAX_DEPTH, GUI_MAX_NODES, null)
            }
            "observe_failed" -> null
            else -> latestObserved
        }
        val finalScreen = finalObserved?.let {
            tracker.formatObserve(
                observed = it,
                scopeKey = scopeKey,
                scanLimits = SCREEN_DEFAULT_MAX_DEPTH to GUI_MAX_NODES,
                displayBounds = displayBounds,
            )
        }

        val failed = stoppedReason == "step_failed"
        val body = buildJsonObject {
            put("ok", stoppedReason == null)
            put("completedSteps", completedCount)
            put("totalSteps", parsedSteps.size)
            stoppedReason?.let { put("stoppedReason", it) }
            if (stoppedReason == "observe_failed") {
                put("hint", "Post-step screen observation failed; call screen_observe before continuing")
            }
            put("steps", buildJsonArray { stepResults.forEach { add(it) } })
            finalScreen?.let { put("screen", it) }
        }
        textResult(body.toString(), isError = failed)
    }
}

private data class StepExecutionOutcome(
    val verb: ScreenActionVerb? = null,
    val message: String? = null,
    val typeResult: ScreenTextResult? = null,
    val failure: String? = null,
)

private suspend fun executeBatchStep(
    controller: ScreenController,
    step: ParsedBatchStep,
): StepExecutionOutcome = try {
    when (step) {
        is ParsedBatchStep.Tap -> StepExecutionOutcome(
            verb = ScreenActionVerb.TAP,
            message = controller.tap(step.selector, step.durationMs),
        )
        is ParsedBatchStep.Swipe -> StepExecutionOutcome(
            verb = ScreenActionVerb.SWIPE,
            message = controller.swipe(step.x1, step.y1, step.x2, step.y2, step.durationMs),
        )
        is ParsedBatchStep.Scroll -> StepExecutionOutcome(
            verb = ScreenActionVerb.SCROLL,
            message = controller.scroll(step.direction, step.amount, step.selector),
        )
        is ParsedBatchStep.Type -> {
            val typed = controller.typeText(step.selector, step.text)
            StepExecutionOutcome(verb = ScreenActionVerb.TYPE, typeResult = typed)
        }
        is ParsedBatchStep.Key -> StepExecutionOutcome(
            verb = ScreenActionVerb.KEY,
            message = controller.key(step.key),
        )
        is ParsedBatchStep.Wait -> {
            val outcome = controller.waitFor(step.packageName, step.text, step.textGone, step.timeoutMs)
            if (outcome.matched) {
                StepExecutionOutcome(message = "Wait condition matched")
            } else {
                StepExecutionOutcome(failure = "Wait timed out")
            }
        }
        is ParsedBatchStep.Launch -> {
            val report = controller.launch(step.packageName)
            if (report.ok && report.landedOnExpectedDisplay) {
                StepExecutionOutcome(verb = ScreenActionVerb.LAUNCH, message = report.message)
            } else {
                StepExecutionOutcome(
                    failure = report.message.ifBlank { "App launch did not land on the expected screen" },
                )
            }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    StepExecutionOutcome(failure = error.message ?: "Step failed")
}

private fun parseBatchSteps(args: JsonObject): List<ParsedBatchStep> {
    val rawSteps = args["steps"] as? JsonArray
        ?: throw IllegalArgumentException("Missing required parameter: steps")
    if (rawSteps.isEmpty() || rawSteps.size > MAX_BATCH_STEPS) {
        throw IllegalArgumentException("steps must contain 1..$MAX_BATCH_STEPS items")
    }
    return rawSteps.mapIndexed { index, element ->
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Step ${index + 1} must be an object")
        val action = (obj["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: throw IllegalArgumentException("Step ${index + 1}: missing required parameter: action")
        val stepArgs = obj["args"]?.let { it as? JsonObject }
            ?: throw IllegalArgumentException("Step ${index + 1}: args must be an object")
        require("target" !in stepArgs) {
            "Step ${index + 1}: pass target on screen_batch rather than inside step args"
        }
        val defaultStopOnPkg = action != "launch" && action != "wait"
        val stopOnPkg = try {
            optionalBoolean(obj, "stopOnPackageChange") ?: defaultStopOnPkg
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Step ${index + 1}: ${error.message}")
        }
        try {
            when (action) {
                "tap" -> ParsedBatchStep.Tap(
                    selector = parseScreenTapSelector(stepArgs),
                    durationMs = tapDurationOf(stepArgs),
                    stopOnPackageChange = stopOnPkg,
                )
                "swipe" -> {
                    val x1 = stepArgs.batchInt("x1")
                    val y1 = stepArgs.batchInt("y1")
                    val x2 = stepArgs.batchInt("x2")
                    val y2 = stepArgs.batchInt("y2")
                    if (listOf(x1, y1, x2, y2).any { it < 0 }) {
                        throw IllegalArgumentException("Coordinates must not be negative")
                    }
                    ParsedBatchStep.Swipe(x1, y1, x2, y2, swipeDurationOf(stepArgs), stopOnPkg)
                }
                "scroll" -> ParsedBatchStep.Scroll(
                    direction = parseScrollDirection(stepArgs),
                    amount = scrollAmountOf(stepArgs),
                    selector = stepArgs["ref"]?.let {
                        val ref = (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }
                            ?: throw IllegalArgumentException("ref must not be empty")
                        GuiSelector.Ref(ref)
                    },
                    stopOnPackageChange = stopOnPkg,
                )
                "type" -> ParsedBatchStep.Type(
                    selector = parseScreenTextSelector(stepArgs),
                    text = parseScreenTypeText(stepArgs),
                    stopOnPackageChange = stopOnPkg,
                )
                "key" -> ParsedBatchStep.Key(
                    key = parseScreenKey(stepArgs),
                    stopOnPackageChange = stopOnPkg,
                )
                "wait" -> {
                    val pkg = (stepArgs["forPackage"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    val txt = (stepArgs["forText"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    val gone = (stepArgs["forTextGone"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    if (pkg == null && txt == null && gone == null) {
                        throw IllegalArgumentException("Provide at least one of forPackage, forText or forTextGone")
                    }
                    ParsedBatchStep.Wait(pkg, txt, gone, waitTimeoutOf(stepArgs), stopOnPkg)
                }
                "launch" -> ParsedBatchStep.Launch(
                    packageName = parseScreenLaunchPackage(stepArgs),
                    stopOnPackageChange = stopOnPkg,
                )
                else -> throw IllegalArgumentException(
                    "Unsupported action '$action'; available: tap, swipe, scroll, type, key, wait, launch",
                )
            }
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Step ${index + 1} ($action): ${error.message}")
        }
    }
}

private fun JsonObject.batchInt(name: String): Int =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
        ?: throw IllegalArgumentException("Missing or invalid required parameter: $name")

private fun optionalBoolean(obj: JsonObject, key: String): Boolean? {
    val element = obj[key] ?: return null
    val primitive = element as? JsonPrimitive
    val parsed = primitive?.booleanOrNull
    return parsed ?: throw IllegalArgumentException("$key must be a boolean")
}

private suspend fun <V> readBatchFact(timeoutMs: Long, block: suspend () -> V): V? = try {
    withTimeoutOrNull(timeoutMs) { block() }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private val batchParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("steps") {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", MAX_BATCH_STEPS)
            put("description", "1-$MAX_BATCH_STEPS steps to execute in sequence.")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                listOf("tap", "swipe", "scroll", "type", "key", "wait", "launch").forEach {
                                    add(JsonPrimitive(it))
                                }
                            },
                        )
                        put("description", "Screen action name without the screen_ prefix.")
                    }
                    putJsonObject("args") {
                        put("type", "object")
                        put("description", "Arguments object for that action.")
                    }
                    putJsonObject("stopOnPackageChange") {
                        put("type", "boolean")
                        put("description", "Stop batch if foreground package changes; defaults true except launch and wait.")
                    }
                }
                put(
                    "required",
                    buildJsonArray {
                        add(JsonPrimitive("action"))
                        add(JsonPrimitive("args"))
                    },
                )
            }
        }
        putJsonObject("stopOnNoChange") {
            put("type", "boolean")
            put("description", "Stop batch immediately after an action with no observable effect.")
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("steps")) })
}
