package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.CapabilityDelta
import io.github.whywood.superpi.runtime.CapabilityNotices
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.MessageSummary
import io.github.whywood.superpi.runtime.MessageSummaryKind
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolResultPayload
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.TurnNotice
import io.github.whywood.superpi.runtime.TurnSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object WorkerHistoryWireCodec {
    const val DEFAULT_WIRE_BUDGET_CHARS: Int = 2_621_440
    private const val MAX_BUDGETED_FIELD_CHARS: Int = 131_072

    data class BudgetedMessages(
        val messages: JsonArray,
        val truncated: Boolean,
    )

    fun encodeMessages(
        messages: List<AssistantMessage>,
        enrichToolOutput: (name: String, output: String) -> String = { _, out -> out },
    ): JsonArray = buildJsonArray {
        messages.forEach { message ->
            add(encodeMessage(message, enrichToolOutput, capFields = false, onTrimmed = {}))
        }
    }

    fun encodeMessagesBudgeted(
        messages: List<AssistantMessage>,
        maxChars: Int = DEFAULT_WIRE_BUDGET_CHARS,
        enrichToolOutput: (name: String, output: String) -> String = { _, out -> out },
    ): BudgetedMessages {
        if (messages.isEmpty()) return BudgetedMessages(buildJsonArray {}, truncated = false)
        var fieldTrimmed = false
        val encoded = messages.map { message ->
            encodeMessage(message, enrichToolOutput, capFields = true) { fieldTrimmed = true }
        }
        var remaining = maxChars
        var firstKept = encoded.size
        for (index in encoded.indices.reversed()) {
            val size = encoded[index].toString().length + 1
            if (index == encoded.lastIndex || size <= remaining) {
                remaining = (remaining - size).coerceAtLeast(0)
                firstKept = index
            } else {
                break
            }
        }
        val droppedOldest = firstKept > 0
        val kept = if (droppedOldest) encoded.subList(firstKept, encoded.size) else encoded
        return BudgetedMessages(
            messages = JsonArray(kept.toList()),
            truncated = fieldTrimmed || droppedOldest,
        )
    }

    fun decodeMessages(array: JsonArray?): List<AssistantMessage> {
        if (array == null || array.isEmpty()) return emptyList()
        return array.mapNotNull { element ->
            runCatching { decodeMessage(element.jsonObject) }.getOrNull()
        }
    }

    private fun capWireText(
        text: String,
        capFields: Boolean,
        onTrimmed: () -> Unit,
    ): String {
        if (!capFields || text.length <= MAX_BUDGETED_FIELD_CHARS) return text
        onTrimmed()
        return "${text.take(MAX_BUDGETED_FIELD_CHARS)}\n...[truncated]"
    }

    private fun encodeMessage(
        message: AssistantMessage,
        enrichToolOutput: (name: String, output: String) -> String,
        capFields: Boolean,
        onTrimmed: () -> Unit,
    ): JsonObject = buildJsonObject {
        put("id", message.id)
        put("role", message.role.name)
        put("text", capWireText(message.text, capFields, onTrimmed))
        put("streaming", message.streaming)
        put("status", message.status.name)
        put("startedAt", message.startedAt)
        message.finishedAt?.let { put("finishedAt", it) }
        if (message.runId.isNotEmpty()) put("runId", message.runId)
        message.error?.let { put("error", capWireText(it, capFields, onTrimmed)) }
        message.rawError?.let { put("rawError", capWireText(it, capFields, onTrimmed)) }
        message.reminder?.let { put("reminder", capWireText(it, capFields, onTrimmed)) }
        if (message.retrying) put("retrying", true)
        message.notice?.let { put("notice", encodeNotice(it)) }
        message.summary?.let { put("summary", encodeSummary(it)) }
        message.customType?.let { put("customType", it) }
        message.customPayload?.let { put("customPayload", capWireText(it, capFields, onTrimmed)) }
        message.injectedSource?.let { put("injectedSource", it.name) }
        if (message.steps.isNotEmpty()) {
            put(
                "steps",
                buildJsonArray {
                    message.steps.forEach { step ->
                        add(encodeStep(step, enrichToolOutput, capFields, onTrimmed))
                    }
                },
            )
        }
    }

    private fun decodeMessage(obj: JsonObject): AssistantMessage {
        val role = enumOrNull<AssistantRole>(obj.str("role")) ?: AssistantRole.ASSISTANT
        val status = enumOrNull<MessageStatus>(obj.str("status")) ?: MessageStatus.DONE
        val injectedSource = obj.str("injectedSource")?.let { enumOrNull<TurnSource>(it) }
        val steps = (obj["steps"] as? JsonArray)
            ?.mapNotNull { el -> runCatching { decodeStep(el.jsonObject) }.getOrNull() }
            .orEmpty()
        return AssistantMessage(
            id = obj.long("id") ?: 0L,
            role = role,
            text = obj.str("text").orEmpty(),
            streaming = obj.bool("streaming") ?: false,
            steps = steps,
            status = status,
            startedAt = obj.long("startedAt") ?: 0L,
            finishedAt = obj.long("finishedAt"),
            runId = obj.str("runId").orEmpty(),
            error = obj.str("error"),
            rawError = obj.str("rawError"),
            reminder = obj.str("reminder"),
            retrying = obj.bool("retrying") ?: false,
            notice = (obj["notice"] as? JsonObject)?.let(::decodeNotice),
            summary = (obj["summary"] as? JsonObject)?.let(::decodeSummary),
            customType = obj.str("customType"),
            customPayload = obj.str("customPayload"),
            injectedSource = injectedSource,
        )
    }

    private fun encodeStep(
        step: AssistantStep,
        enrichToolOutput: (name: String, output: String) -> String,
        capFields: Boolean,
        onTrimmed: () -> Unit,
    ): JsonObject = when (step) {
        is AssistantStep.Reasoning -> buildJsonObject {
            put("type", "reasoning")
            put("id", step.id)
            put("text", capWireText(step.text, capFields, onTrimmed))
            put("expanded", step.expanded)
        }
        is AssistantStep.Tool -> buildJsonObject {
            put("type", "tool")
            put("trace", encodeTrace(step.trace, enrichToolOutput, capFields, onTrimmed))
        }
        is AssistantStep.Text -> buildJsonObject {
            put("type", "text")
            put("id", step.id)
            put("text", capWireText(step.text, capFields, onTrimmed))
        }
        is AssistantStep.Notice -> buildJsonObject {
            put("type", "notice")
            put("id", step.id)
            put("notice", encodeNotice(step.notice))
        }
        is AssistantStep.CustomCard -> buildJsonObject {
            put("type", "custom_card")
            put("id", step.id)
            put("customType", step.customType)
            put("payload", capWireText(step.payload, capFields, onTrimmed))
            put("text", capWireText(step.text, capFields, onTrimmed))
        }
    }

    private fun decodeStep(obj: JsonObject): AssistantStep? = when (obj.str("type")) {
        "reasoning" -> AssistantStep.Reasoning(
            id = obj.long("id") ?: 0L,
            text = obj.str("text").orEmpty(),
            expanded = obj.bool("expanded") ?: false,
        )
        "tool" -> (obj["trace"] as? JsonObject)?.let { AssistantStep.Tool(decodeTrace(it)) }
        "text" -> AssistantStep.Text(
            id = obj.long("id") ?: 0L,
            text = obj.str("text").orEmpty(),
        )
        "notice" -> (obj["notice"] as? JsonObject)?.let(::decodeNotice)?.let {
            AssistantStep.Notice(id = obj.long("id") ?: 0L, notice = it)
        }
        "custom_card" -> AssistantStep.CustomCard(
            id = obj.long("id") ?: 0L,
            customType = obj.str("customType").orEmpty(),
            payload = obj.str("payload").orEmpty(),
            text = obj.str("text").orEmpty(),
        )
        else -> null
    }

    private fun encodeTrace(
        trace: ToolTrace,
        enrichToolOutput: (name: String, output: String) -> String,
        capFields: Boolean,
        onTrimmed: () -> Unit,
    ): JsonObject = buildJsonObject {
        put("id", trace.id)
        put("name", trace.name)
        put("status", trace.status.name)
        if (trace.arguments.isNotEmpty()) {
            put(
                "arguments",
                buildJsonObject {
                    trace.arguments.forEach { (k, v) -> put(k, capWireText(v, capFields, onTrimmed)) }
                },
            )
        }
        trace.output?.let { put("output", capWireText(enrichToolOutput(trace.name, it), capFields, onTrimmed)) }
        trace.error?.let { put("error", capWireText(it, capFields, onTrimmed)) }
        if (trace.expanded) put("expanded", true)
        if (trace.toolCallId.isNotEmpty()) put("toolCallId", trace.toolCallId)
        put("startedAt", trace.startedAt)
        trace.durationMs?.let { put("durationMs", it) }
        trace.rawArguments?.let { raw ->
            if (!capFields || raw.toString().length <= MAX_BUDGETED_FIELD_CHARS) {
                put("rawArguments", raw)
            } else {
                onTrimmed()
            }
        }
        trace.resultPayload?.let { payload ->
            val adjusted = if (payload is ToolResultPayload.Text) {
                ToolResultPayload.Text(capWireText(enrichToolOutput(trace.name, payload.text), capFields, onTrimmed))
            } else {
                payload
            }
            put("resultPayload", encodePayload(adjusted, capFields, onTrimmed))
        }
        trace.resultDetails?.let { details ->
            if (!capFields || details.toString().length <= MAX_BUDGETED_FIELD_CHARS) {
                put("resultDetails", details)
            } else {
                onTrimmed()
            }
        }
    }

    private fun decodeTrace(obj: JsonObject): ToolTrace {
        val args = (obj["arguments"] as? JsonObject)
            ?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }
            ?.toMap()
            .orEmpty()
        return ToolTrace(
            id = obj.long("id") ?: 0L,
            name = obj.str("name").orEmpty(),
            arguments = args,
            status = enumOrNull<ToolActivityStatus>(obj.str("status")) ?: ToolActivityStatus.DONE,
            output = obj.str("output"),
            error = obj.str("error"),
            expanded = obj.bool("expanded") ?: false,
            toolCallId = obj.str("toolCallId").orEmpty(),
            startedAt = obj.long("startedAt") ?: 0L,
            durationMs = obj.long("durationMs"),
            rawArguments = obj["rawArguments"] as? JsonObject,
            resultPayload = (obj["resultPayload"] as? JsonObject)?.let(::decodePayload),
            resultDetails = obj["resultDetails"] as? JsonObject,
        )
    }

    private fun encodePayload(
        payload: ToolResultPayload,
        capFields: Boolean,
        onTrimmed: () -> Unit,
    ): JsonObject = when (payload) {
        is ToolResultPayload.Text -> buildJsonObject {
            put("type", "text")
            put("text", capWireText(payload.text, capFields, onTrimmed))
        }
        is ToolResultPayload.Blocks -> buildJsonObject {
            put("type", "blocks")
            put(
                "blocks",
                buildJsonArray {
                    payload.blocks.forEach { block ->
                        add(
                            when (block) {
                                is ToolResultPayload.Block.Text -> buildJsonObject {
                                    put("type", "text")
                                    put("text", capWireText(block.text, capFields, onTrimmed))
                                }
                                is ToolResultPayload.Block.Image -> if (capFields) {
                                    onTrimmed()
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "[Image omitted]")
                                    }
                                } else {
                                    buildJsonObject {
                                        put("type", "image")
                                        put("data", block.data)
                                        put("mimeType", block.mimeType)
                                    }
                                }
                                is ToolResultPayload.Block.FileRef -> buildJsonObject {
                                    put("type", "file_ref")
                                    put("path", block.path)
                                    block.mimeType?.let { put("mimeType", it) }
                                }
                            },
                        )
                    }
                },
            )
        }
        ToolResultPayload.Empty -> buildJsonObject { put("type", "empty") }
    }

    private fun decodePayload(obj: JsonObject): ToolResultPayload? = when (obj.str("type")) {
        "text" -> ToolResultPayload.Text(obj.str("text").orEmpty())
        "blocks" -> {
            val blocks = (obj["blocks"] as? JsonArray).orEmpty().mapNotNull { el ->
                val b = el as? JsonObject ?: return@mapNotNull null
                when (b.str("type")) {
                    "text" -> ToolResultPayload.Block.Text(b.str("text").orEmpty())
                    "image" -> ToolResultPayload.Block.Image(
                        data = b.str("data").orEmpty(),
                        mimeType = b.str("mimeType").orEmpty(),
                    )
                    "file_ref" -> ToolResultPayload.Block.FileRef(
                        path = b.str("path").orEmpty(),
                        mimeType = b.str("mimeType"),
                    )
                    else -> null
                }
            }
            ToolResultPayload.Blocks(blocks)
        }
        "empty" -> ToolResultPayload.Empty
        else -> null
    }

    private fun encodeSummary(summary: MessageSummary): JsonObject = buildJsonObject {
        put("kind", summary.kind.name)
        put("text", summary.text)
        summary.tokensBefore?.let { put("tokensBefore", it) }
        summary.tokensAfter?.let { put("tokensAfter", it) }
        summary.fromId?.let { put("fromId", it) }
    }

    private fun decodeSummary(obj: JsonObject): MessageSummary? {
        val kind = enumOrNull<MessageSummaryKind>(obj.str("kind")) ?: return null
        return MessageSummary(
            kind = kind,
            text = obj.str("text").orEmpty(),
            tokensBefore = obj.long("tokensBefore"),
            tokensAfter = obj.long("tokensAfter"),
            fromId = obj.str("fromId"),
        )
    }

    private fun encodeNotice(notice: TurnNotice): JsonObject = when (notice) {
        is TurnNotice.Compacted -> buildJsonObject {
            put("type", "compacted")
            put("tokensBefore", notice.tokensBefore)
            notice.tokensAfter?.let { put("tokensAfter", it) }
        }
        is TurnNotice.GoalRound -> buildJsonObject {
            put("type", "goal_round")
            put("round", notice.round)
            put("maxRounds", notice.maxRounds)
            put("objective", notice.objective)
        }
        is TurnNotice.GoalFinished -> buildJsonObject {
            put("type", "goal_finished")
            put("completed", notice.completed)
        }
        is TurnNotice.JobFinished -> buildJsonObject {
            put("type", "job_finished")
            put("label", notice.label)
            put("failed", notice.failed)
        }
        is TurnNotice.InterruptedQuestions -> buildJsonObject {
            put("type", "interrupted_questions")
            put("count", notice.count)
        }
        is TurnNotice.InterruptedRun -> buildJsonObject {
            put("type", "interrupted_run")
            put("count", notice.count)
        }
        is TurnNotice.CapabilitiesChanged -> buildJsonObject {
            put("type", "capabilities_changed")
            put("delta", CapabilityNotices.encodeDelta(notice.delta))
        }
    }

    private fun decodeNotice(obj: JsonObject): TurnNotice? = when (obj.str("type")) {
        "compacted" -> TurnNotice.Compacted(
            tokensBefore = obj.long("tokensBefore") ?: 0L,
            tokensAfter = obj.long("tokensAfter"),
        )
        "goal_round" -> TurnNotice.GoalRound(
            round = (obj.long("round") ?: 0L).toInt(),
            maxRounds = (obj.long("maxRounds") ?: 0L).toInt(),
            objective = obj.str("objective").orEmpty(),
        )
        "goal_finished" -> TurnNotice.GoalFinished(completed = obj.bool("completed") ?: false)
        "job_finished" -> TurnNotice.JobFinished(
            label = obj.str("label").orEmpty(),
            failed = obj.bool("failed") ?: false,
        )
        "interrupted_questions" -> TurnNotice.InterruptedQuestions(
            count = (obj.long("count") ?: 0L).toInt(),
        )
        "interrupted_run" -> TurnNotice.InterruptedRun(
            count = (obj.long("count") ?: 0L).toInt(),
        )
        "capabilities_changed" -> CapabilityNotices.decodeDelta(obj["delta"])?.let {
            TurnNotice.CapabilitiesChanged(it)
        }
        else -> null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        if (name.isNullOrBlank()) null else runCatching { enumValueOf<T>(name) }.getOrNull()
}
