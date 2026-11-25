package io.github.whywood.superpi.runtime

data class QuestionOption(
    val label: String,
    val description: String? = null,
)

data class UserQuestion(
    val id: String,
    val header: String? = null,
    val question: String,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

data class QuestionAnswer(
    val questionId: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
    val skipped: Boolean = false,
)

data class PendingQuestion(
    val requestId: String,
    val sessionId: String,
    val toolCallId: String,
    val questions: List<UserQuestion>,
)
