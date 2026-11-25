package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerConflictException
import io.github.whywood.superpi.runtime.WorkerInspectResult
import io.github.whywood.superpi.runtime.WorkerNotFoundException
import io.github.whywood.superpi.runtime.WorkerPoolFullException
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerStepSnapshot
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.RequestAbortedException
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.textResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

interface WorkerOrchestrationGateway {
    suspend fun listWorkers(botQuery: String? = null): List<WorkerRunRecord> = emptyList()
    suspend fun startWorker(
        botQuery: String,
        task: String,
        attachmentPaths: List<String> = emptyList(),
    ): WorkerRunRecord
    suspend fun startWorker(
        botQuery: String,
        task: String,
        attachmentPaths: List<String>,
        modelOverride: ModelConfig?,
    ): WorkerRunRecord = startWorker(botQuery, task, attachmentPaths)
    suspend fun startWorker(
        botQuery: String,
        task: String,
        attachmentPaths: List<String>,
        modelOverride: ModelConfig?,
        name: String?,
    ): WorkerRunRecord = startWorker(botQuery, task, attachmentPaths, modelOverride)
    suspend fun continueWorker(
        workerId: String,
        message: String,
        attachmentPaths: List<String> = emptyList(),
    ): WorkerRunRecord
    suspend fun steerWorker(workerId: String, message: String): WorkerRunRecord
    suspend fun inspectWorker(workerId: String, tail: Int = 30): WorkerInspectResult
    suspend fun stopWorker(workerId: String): WorkerRunRecord
    suspend fun deleteWorker(workerId: String)
    suspend fun compactWorker(workerId: String): WorkerRunRecord {
        throw UnsupportedOperationException("Compacting workers is not supported.")
    }
    suspend fun setWorkerModel(
        workerId: String,
        providerId: String,
        modelId: String,
        thinkingLevel: ThinkingLevel,
    ): WorkerRunRecord {
        throw UnsupportedOperationException("Switching worker model is not supported.")
    }
    suspend fun answerWorkerQuestion(
        workerId: String,
        requestId: String,
        answers: List<QuestionAnswer>,
    ): WorkerRunRecord {
        throw UnsupportedOperationException("Answering worker questions is not supported.")
    }
    suspend fun dismissWorkerQuestion(
        workerId: String,
        requestId: String,
    ): WorkerRunRecord {
        throw UnsupportedOperationException("Dismissing worker questions is not supported.")
    }
}

private const val WORKER_ACTION_LIST = "list"
private const val WORKER_ACTION_START = "start"
private const val WORKER_ACTION_CONTINUE = "continue"
private const val WORKER_ACTION_STEER = "steer"
private const val WORKER_ACTION_INSPECT = "inspect"
private const val WORKER_ACTION_STOP = "stop"
private const val WORKER_ACTION_DELETE = "delete"
private const val WORKER_ACTION_COMPACT = "compact"
private const val WORKER_ACTION_SET_MODEL = "set_model"
private const val WORKER_ACTION_ANSWER = "answer"
private const val WORKER_ACTION_DISMISS = "dismiss"

private const val DEFAULT_INSPECT_TAIL = 30
private const val MAX_INSPECT_TAIL = 200
private const val MAX_TOOL_OUTPUT_PREVIEW = 320
private const val MAX_TOOL_ARGS_PREVIEW = 180

private val ISO_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private val WORKER_ACTION_ENUM = buildJsonArray {
    add(JsonPrimitive(WORKER_ACTION_LIST))
    add(JsonPrimitive(WORKER_ACTION_START))
    add(JsonPrimitive(WORKER_ACTION_CONTINUE))
    add(JsonPrimitive(WORKER_ACTION_STEER))
    add(JsonPrimitive(WORKER_ACTION_INSPECT))
    add(JsonPrimitive(WORKER_ACTION_STOP))
    add(JsonPrimitive(WORKER_ACTION_DELETE))
    add(JsonPrimitive(WORKER_ACTION_COMPACT))
    add(JsonPrimitive(WORKER_ACTION_SET_MODEL))
    add(JsonPrimitive(WORKER_ACTION_ANSWER))
    add(JsonPrimitive(WORKER_ACTION_DISMISS))
}

private val THINKING_LEVEL_ENUM = buildJsonArray {
    ThinkingLevel.entries.forEach { add(JsonPrimitive(it.name.lowercase(Locale.ROOT))) }
}

private val workerToolParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            put("enum", WORKER_ACTION_ENUM)
            put("description", "list, start, continue, steer, inspect, stop, delete, compact, set_model, answer or dismiss")
        }
        putJsonObject("bot") {
            put("type", "string")
            put("description", "Whitelisted bot ref, botId or name from <available_bots>; required for start and optional filter for list")
        }
        putJsonObject("name") {
            put("type", "string")
            put("description", "Short descriptive label for this worker shown to the user and in worker lists; required for action=start")
        }
        putJsonObject("task") {
            put("type", "string")
            put("description", "Complete self-contained task instruction for action=start")
        }
        putJsonObject("workerId") {
            put("type", "string")
            put("description", "Persistent worker id (e.g. wrk-...) for continue, steer, inspect, stop, delete, compact, set_model, answer or dismiss")
        }
        putJsonObject("message") {
            put("type", "string")
            put("description", "Instruction sent to the worker for action=continue or action=steer")
        }
        putJsonObject("attachments") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
            }
            put("description", "Optional local file paths or worker:// artifact URIs to forward to the worker on action=start or action=continue")
        }
        putJsonObject("providerId") {
            put("type", "string")
            put("description", "Model provider id; optional on start, required on set_model")
        }
        putJsonObject("modelId") {
            put("type", "string")
            put("description", "Model id; optional on start, required on set_model")
        }
        putJsonObject("thinkingLevel") {
            put("type", "string")
            put("enum", THINKING_LEVEL_ENUM)
            put("description", "Reasoning level (off, low, medium, high, max); optional on start or set_model")
        }
        putJsonObject("requestId") {
            put("type", "string")
            put("description", "Pending approval requestId from waiting-input timeout or inspect, required on answer or dismiss")
        }
        putJsonObject("answers") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("questionId") { put("type", "string") }
                    putJsonObject("selected") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("custom") { put("type", "string") }
                    putJsonObject("skipped") { put("type", "boolean") }
                }
            }
            put("description", "Structured answers for action=answer after human confirmation times out")
        }
        putJsonObject("tail") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", MAX_INSPECT_TAIL)
            put("default", DEFAULT_INSPECT_TAIL)
            put("description", "Recent step count returned by action=inspect")
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("action")) })
}

internal val WORKER_TOOL_GUIDELINES: List<String> = listOf(
    "Call worker action=list when you need to inspect existing persistent workers for all bots or a specific bot.",
    "Always provide a short descriptive name parameter on worker action=start so the user and list outputs clearly identify each worker.",
    "worker start and continue return immediately while the worker runs asynchronously; report dispatch to the user instead of polling inspect in a loop.",
    "Pass user images, files or worker:// artifact URIs in attachments on worker start or continue when the worker needs those inputs.",
    "Only call bots listed in <available_bots>; when a bot's persistent worker slots are full, either continue a relevant slot or delete an idle slot before starting a new one.",
    "Never pre-empt user confirmation: wait until a WaitingInputTimeout notification arrives before calling worker action=answer or action=dismiss.",
)

fun workerOrchestrationTool(gateway: WorkerOrchestrationGateway): CodingTool = CodingTool(
    name = "worker",
    description = "List, dispatch, continue, steer, inspect, compact, change model, answer timed-out approvals, stop or delete isolated persistent bot workers across this phone and connected remote devices. Only bots listed in <available_bots> can be used.",
    parameters = workerToolParameters,
    label = "worker",
    promptGuidelines = WORKER_TOOL_GUIDELINES,
    execute = { _, args, signal, _ ->
        signal?.throwIfAborted()
        workerToolResult {
            when (args.requiredString("action").lowercase(Locale.ROOT)) {
                WORKER_ACTION_LIST -> {
                    val bot = args.optionalString("bot")
                    val workers = gateway.listWorkers(bot)
                    textResult(encodeListResult(workers).toString())
                }

                WORKER_ACTION_START -> {
                    val bot = args.requiredString("bot")
                    val name = args.requiredString("name").replace(Regex("\\s+"), " ").trim().take(60)
                    val task = args.requiredString("task")
                    val attachments = args.optionalStringList("attachments")
                    val modelOverride = parseOptionalModelOverride(args)
                    val record = gateway.startWorker(
                        botQuery = bot,
                        task = task,
                        attachmentPaths = attachments,
                        modelOverride = modelOverride,
                        name = name,
                    )
                    textResult(encodeDispatchResult("started", record).toString())
                }

                WORKER_ACTION_CONTINUE -> {
                    val workerId = args.requiredWorkerId()
                    val message = args.requiredString("message")
                    val attachments = args.optionalStringList("attachments")
                    val record = gateway.continueWorker(workerId = workerId, message = message, attachmentPaths = attachments)
                    textResult(encodeDispatchResult("continued", record).toString())
                }

                WORKER_ACTION_STEER -> {
                    val workerId = args.requiredWorkerId()
                    val message = args.requiredString("message")
                    val record = gateway.steerWorker(workerId = workerId, message = message)
                    textResult(encodeDispatchResult("steered", record).toString())
                }

                WORKER_ACTION_INSPECT -> {
                    val workerId = args.requiredWorkerId()
                    val tail = (args.intOrNull("tail") ?: DEFAULT_INSPECT_TAIL).coerceIn(1, MAX_INSPECT_TAIL)
                    val inspected = gateway.inspectWorker(workerId = workerId, tail = tail)
                    textResult(encodeInspectResult(inspected).toString())
                }

                WORKER_ACTION_STOP -> {
                    val workerId = args.requiredWorkerId()
                    val record = gateway.stopWorker(workerId = workerId)
                    textResult(encodeDispatchResult("stopped", record).toString())
                }

                WORKER_ACTION_DELETE -> {
                    val workerId = args.requiredWorkerId()
                    gateway.deleteWorker(workerId = workerId)
                    textResult(
                        buildJsonObject {
                            put("action", WORKER_ACTION_DELETE)
                            put("workerId", workerId)
                            put("worker_id", workerId)
                            put("deleted", true)
                        }.toString(),
                    )
                }

                WORKER_ACTION_COMPACT -> {
                    val workerId = args.requiredWorkerId()
                    val record = gateway.compactWorker(workerId = workerId)
                    textResult(encodeDispatchResult("compacted", record).toString())
                }

                WORKER_ACTION_SET_MODEL -> {
                    val workerId = args.requiredWorkerId()
                    val providerId = args.requiredString("providerId")
                    val modelId = args.requiredString("modelId")
                    val thinking = parseThinkingLevel(args.optionalString("thinkingLevel")) ?: ThinkingLevel.OFF
                    val record = gateway.setWorkerModel(
                        workerId = workerId,
                        providerId = providerId,
                        modelId = modelId,
                        thinkingLevel = thinking,
                    )
                    textResult(encodeDispatchResult("model_updated", record).toString())
                }

                WORKER_ACTION_ANSWER -> {
                    val workerId = args.requiredWorkerId()
                    val requestId = args.optionalString("requestId")
                        ?: args.optionalString("request_id")
                        ?: throw IllegalArgumentException("Missing required argument: requestId")
                    val answers = parseQuestionAnswers(args)
                    val record = gateway.answerWorkerQuestion(workerId, requestId, answers)
                    textResult(encodeDispatchResult("answered", record).toString())
                }

                WORKER_ACTION_DISMISS -> {
                    val workerId = args.requiredWorkerId()
                    val requestId = args.optionalString("requestId")
                        ?: args.optionalString("request_id")
                        ?: throw IllegalArgumentException("Missing required argument: requestId")
                    val record = gateway.dismissWorkerQuestion(workerId, requestId)
                    textResult(encodeDispatchResult("dismissed", record).toString())
                }

                else -> textResult(
                    "action must be list, start, continue, steer, inspect, stop, delete, compact, set_model, answer or dismiss.",
                    isError = true,
                )
            }
        }
    },
)

private suspend fun workerToolResult(block: suspend () -> AgentToolResult): AgentToolResult = try {
    block()
} catch (error: RequestAbortedException) {
    throw error
} catch (error: CancellationException) {
    throw error
} catch (full: WorkerPoolFullException) {
    val payload = buildJsonObject {
        put("error", "worker_pool_full")
        put("bot_id", full.botId)
        put("max_slots", full.maxSlots)
        put(
            "message",
            "All ${full.maxSlots} persistent worker slots for bot ${full.botId} are occupied. Either continue an existing slot with action=continue or free an idle slot with action=delete.",
        )
        putJsonArray("existing_workers") {
            full.existing.forEach { slot ->
                add(
                    buildJsonObject {
                        put("worker_id", slot.workerId)
                        put("name", slot.title)
                        put("title", slot.title)
                        put("status", slot.status.name.lowercase(Locale.ROOT))
                        put("updated_at", formatTimestamp(slot.updatedAt))
                    },
                )
            }
        }
    }
    textResult(payload.toString(), isError = true)
} catch (conflict: WorkerConflictException) {
    textResult(
        buildJsonObject {
            put("error", "worker_conflict")
            put("worker_id", conflict.workerId)
            put("message", conflict.message)
        }.toString(),
        isError = true,
    )
} catch (notFound: WorkerNotFoundException) {
    textResult(
        buildJsonObject {
            put("error", "worker_not_found")
            put("worker_id", notFound.workerId)
            put("message", "Worker ${notFound.workerId} does not exist.")
        }.toString(),
        isError = true,
    )
} catch (error: Exception) {
    textResult(error.message ?: "Worker orchestration failed", isError = true)
}

private fun encodeListResult(workers: List<WorkerRunRecord>): JsonObject = buildJsonObject {
    put("action", WORKER_ACTION_LIST)
    put("count", workers.size)
    putJsonArray("workers") {
        workers.forEach { record ->
            add(
                buildJsonObject {
                    put("workerId", record.workerId)
                    put("worker_id", record.workerId)
                    put("bot_ref", record.botRef)
                    put("bot_id", record.botId)
                    put("bot_name", record.botName)
                    put("device", record.deviceName)
                    put("remote", record.isRemote)
                    put("name", record.title)
                    put("title", record.title)
                    put("status", record.status.name.lowercase(Locale.ROOT))
                    put("turn_count", record.turnCount)
                    put("updated_at", formatTimestamp(record.updatedAt))
                    record.lastSummary?.takeIf { it.isNotBlank() }?.let {
                        put("summary", it.take(MAX_TOOL_OUTPUT_PREVIEW))
                    }
                },
            )
        }
    }
}

private fun encodeDispatchResult(action: String, record: WorkerRunRecord): JsonObject = buildJsonObject {
    put("action", action)
    put("workerId", record.workerId)
    put("worker_id", record.workerId)
    put("bot_ref", record.botRef)
    put("bot_name", record.botName)
    put("device", record.deviceName)
    put("remote", record.isRemote)
    put("name", record.title)
    put("title", record.title)
    put("status", record.status.name.lowercase(Locale.ROOT))
    put("turn_count", record.turnCount)
    record.activeStepText?.let { put("active_step", it) }
    record.lastOutcome.name.lowercase(Locale.ROOT).let { put("last_outcome", it) }
}

private fun encodeInspectResult(result: WorkerInspectResult): JsonObject {
    val record = result.record
    return buildJsonObject {
        put("workerId", record.workerId)
        put("worker_id", record.workerId)
        put("bot_ref", record.botRef)
        put("bot_name", record.botName)
        put("device", record.deviceName)
        put("remote", record.isRemote)
        put("name", record.title)
        put("title", record.title)
        put("status", record.status.name.lowercase(Locale.ROOT))
        put("last_outcome", record.lastOutcome.name.lowercase(Locale.ROOT))
        put("turn_count", record.turnCount)
        put("created_at", formatTimestamp(record.createdAt))
        put("updated_at", formatTimestamp(record.updatedAt))
        record.activeStepText?.let { put("active_step", it) }
        record.lastSummary?.let { put("summary", it) }
        record.lastError?.let { put("error", it) }
        record.pendingQuestion?.let { pq ->
            putJsonObject("pending_question") {
                put("request_id", pq.requestId)
                put("question_count", pq.questions.size)
                put("escalated_to_super", result.questionEscalatedToSuper)
                pq.questions.firstOrNull()?.question?.let { put("prompt", it) }
                putJsonArray("questions") {
                    pq.questions.forEach { q -> add(encodeUserQuestion(q)) }
                }
            }
        }
        result.usage?.let { usage ->
            val percent = if (usage.contextWindow > 0L) {
                ((usage.contextTokens.toDouble() / usage.contextWindow.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            } else {
                0
            }
            putJsonObject("usage") {
                put("context_tokens", usage.contextTokens)
                put("context_window", usage.contextWindow)
                put("percent", percent)
                put("input_tokens", usage.inputTokens)
                put("output_tokens", usage.outputTokens)
                put("cost", usage.cost)
            }
        }
        putJsonArray("active_timers") {
            result.activeTimers.forEach { timer ->
                add(
                    buildJsonObject {
                        put("id", timer.id)
                        put("name", timer.name)
                        put("enabled", timer.enabled)
                        timer.nextRunAt?.let { put("next_run_at", formatTimestamp(it)) }
                    },
                )
            }
        }
        putJsonArray("running_jobs") {
            result.runningJobs.forEach { job ->
                add(
                    buildJsonObject {
                        put("job_id", job.jobId)
                        put("command", job.command)
                        put("status", job.status.name.lowercase(Locale.ROOT))
                    },
                )
            }
        }
        putJsonArray("recent_steps") {
            result.recentSteps.forEach { step -> add(encodeStep(step)) }
        }
        putJsonArray("artifacts") {
            record.artifacts.forEach { artifact -> add(encodeArtifact(artifact)) }
        }
        putJsonArray("cards") {
            record.cards.forEach { card -> add(encodeCard(card)) }
        }
    }
}

private fun encodeStep(step: WorkerStepSnapshot): JsonObject = buildJsonObject {
    put("tool", step.toolName)
    put("status", step.status.name.lowercase(Locale.ROOT))
    if (step.argsJson.isNotBlank() && step.argsJson != "{}") {
        put("args", step.argsJson.take(MAX_TOOL_ARGS_PREVIEW))
    }
    step.outputPreview?.takeIf { it.isNotBlank() }?.let { put("output", it.take(MAX_TOOL_OUTPUT_PREVIEW)) }
    if (step.isError) put("is_error", true)
}

private fun encodeArtifact(artifact: WorkerArtifactRef): JsonObject = buildJsonObject {
    put("artifact_id", artifact.artifactId)
    put("uri", "worker://${artifact.workerId}/${artifact.artifactId}")
    put("type", artifact.kind)
    put("name", artifact.name)
    artifact.title?.let { put("title", it) }
    artifact.mimeType?.let { put("mime_type", it) }
    artifact.sizeBytes?.let { put("size_bytes", it) }
}

private fun encodeCard(card: WorkerCardRef): JsonObject = buildJsonObject {
    put("card_id", card.cardId)
    put("tool", card.toolName)
    put("title", card.title)
    if (card.outputText.isNotBlank()) {
        put("preview", card.outputText.take(MAX_TOOL_OUTPUT_PREVIEW))
    }
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return ISO_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

private fun JsonObject.requiredWorkerId(): String =
    (this["workerId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: (this["worker_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required argument: workerId")

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required argument: $name")

private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.optionalStringList(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }

private fun JsonObject.intOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun encodeUserQuestion(q: UserQuestion): JsonObject = buildJsonObject {
    put("id", q.id)
    q.header?.let { put("header", it) }
    put("question", q.question)
    put("multi_select", q.multiSelect)
    putJsonArray("options") {
        q.options.forEach { opt ->
            add(
                buildJsonObject {
                    put("label", opt.label)
                    opt.description?.let { put("description", it) }
                },
            )
        }
    }
}

private fun parseOptionalModelOverride(args: JsonObject): ModelConfig? {
    val providerId = args.optionalString("providerId")
    val modelId = args.optionalString("modelId")
    if (providerId == null && modelId == null) return null
    if (providerId == null || modelId == null) {
        throw IllegalArgumentException("Both providerId and modelId are required when specifying a model")
    }
    val thinking = parseThinkingLevel(args.optionalString("thinkingLevel")) ?: ThinkingLevel.OFF
    return ModelConfig(providerId = providerId, modelId = modelId, thinkingLevel = thinking)
}

private fun parseThinkingLevel(raw: String?): ThinkingLevel? {
    val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ThinkingLevel.entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        ?: throw IllegalArgumentException("Invalid thinkingLevel: $raw")
}

private fun parseQuestionAnswers(args: JsonObject): List<QuestionAnswer> {
    val array = args["answers"] as? JsonArray
        ?: throw IllegalArgumentException("Missing required argument: answers")
    return array.mapNotNull { elem ->
        val obj = elem as? JsonObject ?: return@mapNotNull null
        val qId = obj.optionalString("questionId")
            ?: obj.optionalString("question_id")
            ?: obj.optionalString("id")
            ?: return@mapNotNull null
        val selected = (obj["selected"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val custom = obj.optionalString("custom")
        val skipped = (obj["skipped"] as? JsonPrimitive)?.booleanOrNull ?: false
        QuestionAnswer(
            questionId = qId,
            selected = selected,
            custom = custom,
            skipped = skipped,
        )
    }.ifEmpty {
        throw IllegalArgumentException("answers must contain at least one valid answer item")
    }
}
