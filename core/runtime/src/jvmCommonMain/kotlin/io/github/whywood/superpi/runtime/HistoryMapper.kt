package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.ai.AssistantMessage as PiAssistantMessage
import io.github.whywood.superpi.sdk.ai.BlockContent
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.ThinkingContent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.BranchSummaryEntry
import io.github.whywood.superpi.sdk.coding.session.CompactionEntry
import io.github.whywood.superpi.sdk.coding.session.ContextEditEntry
import io.github.whywood.superpi.sdk.coding.session.CustomMessageEntry
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import java.time.Instant
import kotlinx.serialization.json.JsonObject

private const val REMINDER_PREFIX = "[Reminder]"
private const val WORKER_UPDATE_PREFIX = "[Worker update]"

object HistoryMapper {
    fun mapEntries(
        entries: List<SessionEntry>,
        settleUnfinishedTools: Boolean = false,
    ): List<AssistantMessage> {
        val omittedEntryIds = entries.asSequence()
            .filterIsInstance<ContextEditEntry>()
            .filter { it.replacement == null }
            .mapTo(HashSet()) { it.targetId }
        val result = mutableListOf<AssistantMessage>()
        val traces = mutableMapOf<String, ToolTrace>()
        val toolOwners = mutableMapOf<String, Int>()
        val hiddenToolResults = mutableMapOf<String, ToolActivityStatus>()
        var current: AssistantMessage? = null
        var messageId = 0L
        var stepId = 0L

        fun flush() {
            val message = current ?: return
            if (message.text.isNotBlank() || message.steps.isNotEmpty() || message.status == MessageStatus.FAILED) {
                val index = result.size
                result += message
                message.steps.forEach { step ->
                    if (step is AssistantStep.Tool) toolOwners[step.trace.toolCallId] = index
                }
            }
            current = null
        }

        for ((entryIndex, entry) in entries.withIndex()) {
            if (entry.id in omittedEntryIds) {
                val toolResult = (entry as? SessionMessageEntry)?.message as? ToolResultMessage
                if (toolResult != null) {
                    hiddenToolResults[toolResult.toolCallId] =
                        if (toolResult.isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE
                }
                continue
            }
            when (entry) {
                is CustomMessageEntry -> {
                    if (!entry.display) continue
                    val text = when (val content = entry.content) {
                        is PlainTextContent -> content.text
                        is BlockContent -> content.asText()
                    }
                    if (entry.customType == CAPABILITIES_NOTICE_CUSTOM_TYPE) {
                        flush()
                        val delta = CapabilityNotices.decodeDelta(entry.details)
                        result += AssistantMessage(
                            id = messageId++,
                            role = AssistantRole.ASSISTANT,
                            text = text,
                            status = MessageStatus.DONE,
                            notice = TurnNotice.CapabilitiesChanged(delta ?: CapabilityDelta()),
                        )
                        continue
                    }
                    val payload = entry.details?.toString().orEmpty()
                    val card = AssistantStep.CustomCard(
                        id = stepId++,
                        customType = entry.customType,
                        payload = payload,
                        text = text,
                    )
                    val belongsToTurn = customMessageBelongsToTurn(entries, entryIndex) ?: (current != null)
                    if (belongsToTurn) {
                        val base = current ?: emptyAssistant(messageId++, 0L)
                        current = base.copy(steps = base.steps + card)
                    } else {
                        flush()
                        result += AssistantMessage(
                            id = messageId++,
                            role = AssistantRole.ASSISTANT,
                            text = text,
                            status = MessageStatus.DONE,
                            customType = entry.customType,
                            customPayload = payload,
                        )
                    }
                }

                is SessionMessageEntry -> when (val message = entry.message) {
                    is UserMessage -> {
                        flush()
                        val text = message.content.asText()
                        if (text.trimStart().startsWith(WORKER_UPDATE_PREFIX)) {
                            continue
                        }
                        val hasNonTextBlocks = (message.content as? BlockContent)?.blocks?.isNotEmpty() == true
                        if (text.isNotBlank() || hasNonTextBlocks) {
                            val timestamp = entryTimestamp(entry, message.timestamp)
                            val reminder = if (text.isNotBlank()) parseReminder(text) else null
                            if (reminder == null) {
                                result += AssistantMessage(
                                    id = messageId++,
                                    role = AssistantRole.USER,
                                    text = text,
                                    startedAt = timestamp,
                                )
                            } else {
                                result += AssistantMessage(
                                    id = messageId++,
                                    role = AssistantRole.USER,
                                    text = reminder.body,
                                    startedAt = timestamp,
                                    reminder = reminder.name,
                                )
                            }
                        }
                    }

                    is PiAssistantMessage -> {
                        val messageTimestamp = entryTimestamp(entry, message.timestamp)
                        val base = current ?: AssistantMessage(
                            id = messageId++,
                            role = AssistantRole.ASSISTANT,
                            text = "",
                            status = MessageStatus.DONE,
                            startedAt = messageTimestamp,
                        )
                        val newSteps = mutableListOf<AssistantStep>()
                        for (content in message.content) {
                            when (content) {
                                is ThinkingContent -> {
                                    if (content.thinking.isNotBlank()) {
                                        newSteps += AssistantStep.Reasoning(stepId++, content.thinking)
                                    }
                                }

                                is TextContent -> {
                                    if (content.text.isNotBlank()) {
                                        newSteps += AssistantStep.Text(stepId++, content.text)
                                    }
                                }

                                is ToolCall -> {
                                    val trace = ToolTrace(
                                        id = stepId++,
                                        name = content.name,
                                        arguments = content.arguments.toDisplayArguments(),
                                        startedAt = messageTimestamp,
                                        toolCallId = content.id,
                                        rawArguments = content.arguments,
                                    )
                                    traces[content.id] = trace
                                    newSteps += AssistantStep.Tool(trace)
                                }

                                else -> Unit
                            }
                        }
                        val steps = base.steps + newSteps
                        val finalText = trailingText(steps)
                        current = base.copy(
                            text = finalText.ifBlank { base.text },
                            steps = steps,
                            startedAt = base.startedAt.takeIf { it > 0 } ?: messageTimestamp,
                            finishedAt = messageTimestamp.takeIf { it > 0 } ?: base.finishedAt,
                            status = turnStatus(base.status, message.stopReason),
                            rawError = message.errorMessage?.takeIf { it.isNotBlank() } ?: base.rawError,
                        )
                    }

                    is ToolResultMessage -> {
                        val messageTimestamp = entryTimestamp(entry, message.timestamp)
                        val payload = message.toToolResultPayload()
                        val status = if (message.isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE
                        val existing = traces[message.toolCallId]
                        if (existing != null) {
                            val updated = existing.copy(
                                status = status,
                                output = payload.text().takeIf { it.isNotBlank() && !message.isError },
                                error = payload.text().takeIf { it.isNotBlank() && message.isError },
                                resultPayload = payload,
                                resultDetails = message.details as? JsonObject,
                                durationMs = message.durationMs ?: existing.durationMs,
                            )
                            traces[message.toolCallId] = updated
                            val messageWithStep = current
                            val ownerIndex = toolOwners[message.toolCallId]
                            when {
                                messageWithStep != null && messageWithStep.hasToolCall(message.toolCallId) ->
                                    current = messageWithStep.replaceTool(updated, messageTimestamp)

                                ownerIndex != null -> result[ownerIndex] =
                                    result[ownerIndex].replaceTool(updated, messageTimestamp)

                                else -> current = (messageWithStep ?: emptyAssistant(messageId++, messageTimestamp))
                                    .appendTool(updated, messageTimestamp)
                            }
                        } else {
                            val trace = ToolTrace(
                                id = stepId++,
                                name = message.toolName,
                                arguments = emptyMap(),
                                status = status,
                                output = payload.text().takeIf { it.isNotBlank() && !message.isError },
                                error = payload.text().takeIf { it.isNotBlank() && message.isError },
                                toolCallId = message.toolCallId,
                                resultPayload = payload,
                                resultDetails = message.details as? JsonObject,
                                durationMs = message.durationMs,
                            )
                            traces[message.toolCallId] = trace
                            current = (current ?: emptyAssistant(messageId++, messageTimestamp))
                                .appendTool(trace, messageTimestamp)
                        }
                    }

                    else -> Unit
                }

                is CompactionEntry -> {
                    flush()
                    result += AssistantMessage(
                        id = messageId++,
                        role = AssistantRole.ASSISTANT,
                        text = "",
                        summary = MessageSummary(
                            kind = MessageSummaryKind.COMPACTION,
                            text = entry.summary,
                            tokensBefore = entry.tokensBefore,
                        ),
                    )
                }

                is BranchSummaryEntry -> {
                    flush()
                    result += AssistantMessage(
                        id = messageId++,
                        role = AssistantRole.ASSISTANT,
                        text = "",
                        summary = MessageSummary(
                            kind = MessageSummaryKind.BRANCH,
                            text = entry.summary,
                            fromId = entry.fromId,
                        ),
                    )
                }

                else -> Unit
            }
        }
        flush()
        if (settleUnfinishedTools) {
            // A saved tool call can survive a process restart without its result.
            for ((callId, trace) in traces) {
                if (trace.status != ToolActivityStatus.RUNNING) continue
                val ownerIndex = toolOwners[callId] ?: continue
                val status = hiddenToolResults[callId] ?: ToolActivityStatus.FAILED
                val message = result[ownerIndex]
                result[ownerIndex] = message.replaceTool(trace.copy(status = status), message.finishedAt ?: 0L)
            }
        }
        return result
    }

    private fun emptyAssistant(id: Long, timestamp: Long): AssistantMessage = AssistantMessage(
        id = id,
        role = AssistantRole.ASSISTANT,
        text = "",
        status = MessageStatus.DONE,
        startedAt = timestamp,
    )

    private fun customMessageBelongsToTurn(entries: List<SessionEntry>, index: Int): Boolean? {
        for (next in index + 1 until entries.size) {
            when (val entry = entries[next]) {
                is CustomMessageEntry -> Unit
                is SessionMessageEntry -> return entry.message !is UserMessage
                is CompactionEntry, is BranchSummaryEntry -> return false
                else -> Unit
            }
        }
        return null
    }

    private fun trailingText(steps: List<AssistantStep>): String =
        steps.takeLastWhile { it is AssistantStep.Text }.joinToString("") { (it as AssistantStep.Text).text }

    private fun AssistantMessage.hasToolCall(toolCallId: String): Boolean =
        steps.any { step -> step is AssistantStep.Tool && step.trace.toolCallId == toolCallId }

    private fun AssistantMessage.replaceTool(trace: ToolTrace, timestamp: Long): AssistantMessage = copy(
        steps = steps.map { step ->
            if (step is AssistantStep.Tool && step.trace.toolCallId == trace.toolCallId) {
                AssistantStep.Tool(trace)
            } else {
                step
            }
        },
        finishedAt = maxOf(finishedAt ?: 0L, timestamp).takeIf { it > 0L },
    )

    private fun AssistantMessage.appendTool(trace: ToolTrace, timestamp: Long): AssistantMessage = copy(
        steps = steps + AssistantStep.Tool(trace),
        finishedAt = maxOf(finishedAt ?: 0L, timestamp).takeIf { it > 0L },
    )

    private data class ReminderMessage(
        val name: String,
        val body: String,
    )

    private fun parseReminder(text: String): ReminderMessage? {
        if (!text.startsWith(REMINDER_PREFIX)) return null
        val firstLineEnd = text.indexOf('\n')
        if (firstLineEnd < 0) {
            val name = text.removePrefix(REMINDER_PREFIX).trim()
            return if (name.isEmpty()) null else ReminderMessage(name, "")
        }
        val name = text.substring(0, firstLineEnd).removePrefix(REMINDER_PREFIX).trim()
        if (name.isEmpty()) return null
        val rest = text.substring(firstLineEnd + 1)
        val separator = rest.indexOf("\n\n")
        val body = when {
            separator >= 0 -> rest.substring(separator + 2)
            rest.startsWith("\n") -> rest.trimStart('\n')
            else -> rest
        }
        return ReminderMessage(name, body.trim())
    }

    private fun turnStatus(base: MessageStatus, reason: StopReason): MessageStatus = when {
        base == MessageStatus.FAILED || reason == StopReason.Error -> MessageStatus.FAILED
        base == MessageStatus.STOPPED || reason == StopReason.Aborted -> MessageStatus.STOPPED
        else -> MessageStatus.DONE
    }

    private fun io.github.whywood.superpi.sdk.ai.MessageContent.asText(): String = when (this) {
        is PlainTextContent -> text
        is BlockContent -> blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
    }

    private fun entryTimestamp(entry: SessionEntry, messageTimestamp: Long): Long {
        if (messageTimestamp > 0L) return messageTimestamp
        val raw = entry.timestamp.trim()
        if (raw.isEmpty()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.takeIf { it > 0L } ?: 0L
    }
}
