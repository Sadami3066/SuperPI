package io.github.whywood.superpi.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Platform-agnostic payload for rich structured cards rendered exclusively in the answer area.
 * Tool execution traces always stay on the raw tool-card pipeline.
 */
@Serializable
data class AnswerCardPayload(
    val cardId: String,
    val type: String,
    val title: String? = null,
    val data: JsonObject,
    val sourceExtensionId: String? = null,
)
