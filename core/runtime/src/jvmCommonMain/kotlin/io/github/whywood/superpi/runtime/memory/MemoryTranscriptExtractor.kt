package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.sdk.ai.AssistantMessage as SdkAssistantMessage
import io.github.whywood.superpi.sdk.ai.BlockContent
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import java.security.MessageDigest
import java.time.Instant

internal data class ExtractedTranscript(
    val text: String,
    val hash: String,
    val lastEntryId: String?,
    val messageCount: Int,
)

internal object MemoryTranscriptExtractor {
    private const val FALLBACK_TAIL_ENTRIES = 24

    fun deltaEntries(
        entries: List<SessionEntry>,
        watermark: IngestionWatermark?,
    ): List<SessionEntry> {
        if (entries.isEmpty()) return emptyList()
        val afterEntryId = watermark?.lastEntryId
        if (afterEntryId.isNullOrBlank()) return entries
        val index = entries.indexOfFirst { it.id == afterEntryId }
        if (index >= 0) return entries.subList(index + 1, entries.size)
        val watermarkTs = watermark.updatedAt
        val messageEntries = entries.filterIsInstance<SessionMessageEntry>()
        val byTimestamp = messageEntries.filter { entry ->
            val ts = entryTimestampMs(entry)
            ts != null && ts > watermarkTs
        }
        return when {
            byTimestamp.isNotEmpty() -> byTimestamp
            messageEntries.any { entryTimestampMs(it) == null } -> entries.takeLast(FALLBACK_TAIL_ENTRIES)
            else -> emptyList()
        }
    }

    fun filterOutPausedEntries(entries: List<SessionEntry>, windows: List<PauseWindow>): List<SessionEntry> {
        if (windows.isEmpty()) return entries
        return entries.filter { entry ->
            if (entry !is SessionMessageEntry) {
                true
            } else {
                val ts = entryTimestampMs(entry) ?: return@filter true
                windows.none { window ->
                    ts >= window.startMs && (window.endMs == null || ts <= window.endMs)
                }
            }
        }
    }

    fun extract(
        entries: List<SessionEntry>,
        minMessages: Int,
        maxTotalChars: Int = Int.MAX_VALUE,
    ): ExtractedTranscript? {
        if (entries.isEmpty()) return null
        val bounded = maxTotalChars in 1 until Int.MAX_VALUE
        val budget = if (bounded) maxTotalChars.coerceAtLeast(1_000) else Int.MAX_VALUE
        val lines = ArrayList<String>()
        var lastMessageId: String? = null
        for (entry in entries) {
            if (entry !is SessionMessageEntry) continue
            lastMessageId = entry.id
            when (val message = entry.message) {
                is UserMessage -> {
                    val text = when (val content = message.content) {
                        is PlainTextContent -> content.text
                        is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    }.trim()
                    if (text.isNotEmpty() &&
                        !text.startsWith("[Worker update]") &&
                        !text.startsWith("[Worker waiting-input timeout]") &&
                        !text.startsWith("[Reminder]")
                    ) {
                        lines += "User: $text"
                    }
                }

                is SdkAssistantMessage -> {
                    val rawAssistant = message.content
                        .filterIsInstance<TextContent>()
                        .joinToString("\n") { it.text }
                        .trim()
                    if (rawAssistant.isNotEmpty()) {
                        lines += "Assistant: $rawAssistant"
                    }
                }

                else -> Unit
            }
        }
        if (lines.size < minMessages) return null
        val text = if (!bounded) {
            lines.joinToString("\n\n")
        } else {
            val kept = ArrayList<String>()
            var total = 0
            for (idx in lines.indices.reversed()) {
                val line = lines[idx]
                val addCost = if (kept.isEmpty()) line.length else line.length + 2
                if (kept.isNotEmpty() && total + addCost > budget) {
                    break
                }
                kept.add(0, line)
                total += addCost
            }
            val joined = if (kept.size < lines.size) {
                "...(earlier turns omitted)\n\n" + kept.joinToString("\n\n")
            } else {
                kept.joinToString("\n\n")
            }
            if (joined.length <= budget + 32) {
                joined
            } else {
                "...(truncated)\n\n" + joined.takeLast(budget).trimStart()
            }
        }
        return ExtractedTranscript(
            text = text,
            hash = sha256(text),
            lastEntryId = lastMessageId,
            messageCount = lines.size,
        )
    }

    fun entryTimestampMs(entry: SessionMessageEntry): Long? {
        val msgTs = when (val msg = entry.message) {
            is UserMessage -> msg.timestamp
            is SdkAssistantMessage -> msg.timestamp
            is ToolResultMessage -> msg.timestamp
            else -> 0L
        }
        if (msgTs > 0L) return msgTs
        return runCatching { Instant.parse(entry.timestamp.trim()).toEpochMilli() }.getOrNull()
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
