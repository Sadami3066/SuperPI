package io.github.whywood.superpi.ui.common.chat

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.ToolActivityStatus

internal const val TodoWriteCustomType = "todo_write"

private val HiddenCustomCardTypes = setOf(TodoWriteCustomType)

internal sealed interface AnswerCardSource {
    data class Custom(val step: AssistantStep.CustomCard) : AnswerCardSource

    data class Host(val card: HostAnswerCard) : AnswerCardSource
}

internal sealed interface HostAnswerCard {
    data class Present(val traceId: Long, val items: List<PresentedItem>) : HostAnswerCard

    data class Miniapp(val traceId: Long, val app: MiniappCardApp) : HostAnswerCard

    data class AnsweredQuestion(val toolCallId: String, val record: QuestionRecord) : HostAnswerCard
}

internal sealed interface TurnBlock {
    val key: String

    data class Activity(val steps: List<AssistantStep>) : TurnBlock {
        override val key: String get() = "activity-${steps.firstOrNull()?.id ?: LIVE_KEY}"
    }

    data class Text(val step: AssistantStep.Text) : TurnBlock {
        override val key: String get() = "text-${step.id}"
    }

    data class Card(val source: AnswerCardSource) : TurnBlock {
        override val key: String
            get() = when (val source = source) {
                is AnswerCardSource.Custom -> "card-${source.step.id}"
                is AnswerCardSource.Host -> when (val card = source.card) {
                    is HostAnswerCard.Present -> "present-${card.traceId}"
                    is HostAnswerCard.Miniapp -> "miniapp-${card.traceId}"
                    is HostAnswerCard.AnsweredQuestion -> "answered-${card.toolCallId}"
                }
            }
    }

    data class LiveQuestion(val question: PendingQuestion) : TurnBlock {
        override val key: String get() = "live-${question.requestId}"
    }

    data class TodoCard(val messageId: Long, val payload: String, val todos: List<TodoItem>) : TurnBlock {
        override val key: String get() = "todo-$messageId"
    }
}

internal fun turnBlocks(
    message: AssistantMessage,
    pendingQuestions: List<PendingQuestion> = emptyList(),
): List<TurnBlock> {
    val blocks = mutableListOf<TurnBlock>()
    val latestTodoStep = message.steps.lastOrNull { step ->
        step is AssistantStep.CustomCard && step.customType == TodoWriteCustomType
    } as? AssistantStep.CustomCard
    val steps = message.steps.filterNot { step ->
        step is AssistantStep.CustomCard && step.customType in HiddenCustomCardTypes
    }
    val activity = mutableListOf<AssistantStep>()

    fun flushActivity() {
        if (activity.isEmpty()) return
        blocks += TurnBlock.Activity(activity.toList())
        activity.clear()
    }

    fun addContent(step: AssistantStep, block: TurnBlock) {
        activity += step
        flushActivity()
        blocks += block
    }

    steps.forEach { step ->
        when (step) {
            is AssistantStep.Text -> {
                if (step.text.isNotBlank()) {
                    flushActivity()
                    blocks += TurnBlock.Text(step)
                }
            }

            is AssistantStep.CustomCard -> {
                flushActivity()
                blocks += TurnBlock.Card(AnswerCardSource.Custom(step))
            }

            else -> {
                val artifact = toolBlock(step)
                if (artifact == null) {
                    activity += step
                } else {
                    addContent(step, artifact)
                }
            }
        }
    }
    flushActivity()
    if (latestTodoStep != null) {
        val todos = parseTodos(latestTodoStep.payload)
        if (todos.isNotEmpty()) {
            val todoBlock = TurnBlock.TodoCard(message.id, latestTodoStep.payload, todos)
            val firstTextIndex = blocks.indexOfFirst { it is TurnBlock.Text }
            if (firstTextIndex >= 0) {
                blocks.add(firstTextIndex, todoBlock)
            } else {
                blocks += todoBlock
            }
        }
    }
    if (blocks.isEmpty() && message.streaming) {
        blocks += TurnBlock.Activity(emptyList())
    }
    liveQuestion(message, pendingQuestions)?.let { question ->
        val owner = blocks.indexOfLast { block ->
            block is TurnBlock.Activity && block.steps.any { step ->
                step is AssistantStep.Tool && step.trace.toolCallId == question.toolCallId
            }
        }
        blocks.add(if (owner >= 0) owner + 1 else blocks.size, TurnBlock.LiveQuestion(question))
    }
    return blocks
}

internal fun answerText(message: AssistantMessage, blocks: List<TurnBlock>): String =
    blocks.filterIsInstance<TurnBlock.Text>()
        .map { it.step.text.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
        .ifBlank { message.text.trim() }

internal fun latestTodoPayload(messages: List<AssistantMessage>): String? {
    messages.asReversed().forEach { message ->
        message.steps.asReversed().forEach { step ->
            if (step is AssistantStep.CustomCard && step.customType == TodoWriteCustomType) {
                return step.payload
            }
        }
        if (message.customType == TodoWriteCustomType) return message.customPayload
    }
    return null
}

private fun toolBlock(step: AssistantStep): TurnBlock? {
    val trace = (step as? AssistantStep.Tool)?.trace ?: return null
    if (trace.name == "present") {
        val items = parsePresentedItems(trace) ?: return null
        return TurnBlock.Card(AnswerCardSource.Host(HostAnswerCard.Present(trace.id, items)))
    }
    if (trace.name == "miniapp_load" || trace.name == "miniapp_register") {
        val app = parseRegisteredMiniapp(trace) ?: return null
        return TurnBlock.Card(AnswerCardSource.Host(HostAnswerCard.Miniapp(trace.id, app)))
    }
    if (trace.name == "ask_user_question" && trace.status == ToolActivityStatus.DONE) {
        val record = parseQuestionRecord(trace) ?: return null
        return TurnBlock.Card(AnswerCardSource.Host(HostAnswerCard.AnsweredQuestion(trace.toolCallId, record)))
    }
    return null
}

private fun liveQuestion(
    message: AssistantMessage,
    pendingQuestions: List<PendingQuestion>,
): PendingQuestion? = pendingQuestions.firstOrNull { pending ->
    message.steps.any { step ->
        step is AssistantStep.Tool &&
            step.trace.name == "ask_user_question" &&
            step.trace.toolCallId == pending.toolCallId
    }
}

private const val LIVE_KEY = "live"
