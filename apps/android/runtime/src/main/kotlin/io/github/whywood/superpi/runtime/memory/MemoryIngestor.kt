package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.AssistantMessage as UiAssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemoryRecord
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.logInfo
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.sdk.ai.AssistantMessage as SdkAssistantMessage
import io.github.whywood.superpi.sdk.ai.BlockContent
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal enum class IngestOutcome {
    WRITTEN,
    SKIPPED,
    DUPLICATE,
    EXCLUDED,
    PAUSED,
    NO_MODEL,
    FAILED,
}

class MemoryIngestor(
    private val store: MemoryStore,
    private val completionGateway: MemoryCompletionGateway,
    private val scope: CoroutineScope,
    private val isSessionExcluded: suspend (String) -> Boolean = { false },
    private val isPaused: suspend () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    fun launchSessionClose(sessionId: String) {
        val captured = store.takeCaptured(sessionId) ?: return
        scope.launch(Dispatchers.IO) {
            try {
                ingestCaptured(captured, reason = REASON_SESSION_CLOSE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "session close ingestion failed", error)
            }
        }
    }

    fun launchCompactionFlush(sessionId: String, sessionFile: String?, entries: List<SessionEntry>?) {
        if (entries.isNullOrEmpty()) return
        val captured = CapturedTranscript(sessionId, sessionFile, entries)
        scope.launch(Dispatchers.IO) {
            try {
                ingestCaptured(captured, reason = REASON_COMPACTION_FLUSH, minIncrementalMessages = 2)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "compaction flush ingestion failed", error)
            }
        }
    }

    fun launchIdleDebounce(sessionId: String, sessionFile: String?, entries: List<SessionEntry>?) {
        if (entries.isNullOrEmpty()) return
        val captured = CapturedTranscript(sessionId, sessionFile, entries)
        scope.launch(Dispatchers.IO) {
            try {
                ingestCaptured(
                    captured = captured,
                    reason = REASON_IDLE_DEBOUNCE,
                    minInitialMessages = DEFAULT_MIN_INITIAL_MESSAGES,
                    minIncrementalMessages = DEFAULT_MIN_INCREMENTAL_MESSAGES,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "idle debounce ingestion failed", error)
            }
        }
    }

    suspend fun onSessionExclusionChanged(
        sessionId: String,
        excluded: Boolean,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        if (excluded) {
            store.takeCaptured(sessionId)
            store.deletePendingIngestion(sessionId)
        } else {
            advanceSessionWatermark(sessionId, sessionFile, entries)
        }
    }

    suspend fun advanceSessionWatermark(
        sessionId: String,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        lockFor(sessionId).withLock {
            store.takeCaptured(sessionId)
            store.deletePendingIngestion(sessionId)
            advanceTailWatermarkLocked(sessionId, sessionFile, entries)
        }
    }

    fun discard(sessionId: String) {
        store.takeCaptured(sessionId)
    }

    fun launchRetryPending() {
        scope.launch(Dispatchers.IO) {
            try {
                retryPending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "pending memory ingestion retry failed", error)
            }
        }
    }

    suspend fun onPauseModeChanged(paused: Boolean) {
        if (paused) {
            store.openPauseWindow(clock())
        } else {
            val closed = store.closePauseWindow(clock())
            if (closed) {
                launchRetryPending()
            }
        }
    }

    suspend fun ingestMessages(
        sessionId: String,
        messages: List<UiAssistantMessage>,
        reason: String = REASON_MANUAL,
    ): Int {
        if (isSessionExcluded(sessionId)) return 0
        val transcript = buildTranscriptFromUi(messages) ?: return 0
        val result = lockFor(sessionId).withLock {
            runIngestionLocked(
                sessionId = sessionId,
                sessionFile = null,
                transcript = transcript,
                lastEntryId = null,
                reason = reason,
                existingWatermark = store.readWatermark(sessionId),
                advanceWatermark = false,
            )
        }
        return result.second
    }

    internal suspend fun ingestCaptured(
        captured: CapturedTranscript,
        reason: String,
        minInitialMessages: Int = DEFAULT_MIN_INITIAL_MESSAGES,
        minIncrementalMessages: Int = DEFAULT_MIN_INCREMENTAL_MESSAGES,
    ): IngestOutcome = lockFor(captured.sessionId).withLock {
        if (isSessionExcluded(captured.sessionId)) {
            advanceTailWatermarkLocked(captured.sessionId, captured.sessionFile, captured.entries)
            store.deletePendingIngestion(captured.sessionId)
            return@withLock IngestOutcome.EXCLUDED
        }
        val windows = store.readPauseWindows()
        val watermark = store.readWatermark(captured.sessionId)
        val after = watermark?.lastEntryId
        val delta = deltaEntries(captured.entries, after)
        val minMessages = if (after == null && reason != REASON_COMPACTION_FLUSH) {
            minInitialMessages
        } else {
            minIncrementalMessages
        }
        if (isPaused()) {
            val hasOpenWindow = windows.any { it.endMs == null }
            if (!hasOpenWindow) {
                advanceTailWatermarkLocked(captured.sessionId, captured.sessionFile, captured.entries)
                return@withLock IngestOutcome.PAUSED
            }
            if (delta.isNotEmpty()) {
                val prePauseEntries = filterOutPausedEntries(delta, windows)
                val prePauseLastId = prePauseEntries.lastOrNull { it is SessionMessageEntry }?.id
                if (prePauseLastId != null) {
                    val prePauseTranscript = buildTranscript(prePauseEntries, minMessages)
                    if (prePauseTranscript != null) {
                        store.savePendingIngestion(
                            PendingIngestionRecord(
                                sessionId = captured.sessionId,
                                sessionFile = captured.sessionFile,
                                transcript = prePauseTranscript,
                                lastEntryId = prePauseLastId,
                                reason = reason,
                                attempts = 0,
                                createdAt = clock(),
                                lastError = PAUSED_PRE_WINDOW_REASON,
                            ),
                        )
                    }
                } else if (!store.hasPendingIngestion(captured.sessionId)) {
                    advanceTailWatermarkLocked(captured.sessionId, captured.sessionFile, captured.entries)
                }
            }
            return@withLock IngestOutcome.PAUSED
        }
        if (delta.isEmpty()) {
            store.deletePendingIngestion(captured.sessionId)
            return@withLock IngestOutcome.SKIPPED
        }
        val eligible = filterOutPausedEntries(delta, windows)
        val lastDeltaMessageId = delta.lastOrNull { it is SessionMessageEntry }?.id
        val transcript = buildTranscript(eligible, minMessages)
        if (transcript == null) {
            if (eligible.none { it is SessionMessageEntry } && lastDeltaMessageId != null) {
                store.markWatermarkOnly(
                    IngestionWatermark(
                        sessionId = captured.sessionId,
                        sessionFile = captured.sessionFile ?: watermark?.sessionFile,
                        lastEntryId = lastDeltaMessageId,
                        lastHash = watermark?.lastHash,
                        updatedAt = clock(),
                    ),
                )
                store.deletePendingIngestion(captured.sessionId)
            }
            return@withLock IngestOutcome.SKIPPED
        }
        val (outcome, _) = runIngestionLocked(
            sessionId = captured.sessionId,
            sessionFile = captured.sessionFile,
            transcript = transcript,
            lastEntryId = lastDeltaMessageId,
            reason = reason,
            existingWatermark = watermark,
            advanceWatermark = true,
        )
        when (outcome) {
            IngestOutcome.FAILED -> {
                store.savePendingIngestion(
                    PendingIngestionRecord(
                        sessionId = captured.sessionId,
                        sessionFile = captured.sessionFile,
                        transcript = transcript,
                        lastEntryId = lastDeltaMessageId,
                        reason = reason,
                        attempts = 1,
                        createdAt = clock(),
                        lastError = "ingestion model call failed",
                    ),
                )
            }
            IngestOutcome.WRITTEN,
            IngestOutcome.SKIPPED,
            IngestOutcome.DUPLICATE,
            IngestOutcome.EXCLUDED,
            -> store.deletePendingIngestion(captured.sessionId)
            IngestOutcome.PAUSED,
            IngestOutcome.NO_MODEL,
            -> Unit
        }
        outcome
    }

    private suspend fun advanceTailWatermarkLocked(
        sessionId: String,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        val lastMessageId = entries?.lastOrNull { it is SessionMessageEntry }?.id ?: return
        val existing = store.readWatermark(sessionId)
        store.markWatermarkOnly(
            IngestionWatermark(
                sessionId = sessionId,
                sessionFile = sessionFile ?: existing?.sessionFile,
                lastEntryId = lastMessageId,
                lastHash = existing?.lastHash,
                updatedAt = clock(),
            ),
        )
    }

    internal suspend fun retryPending() {
        if (isPaused()) return
        store.pruneExhaustedPendingIngestions(MAX_PENDING_ATTEMPTS)
        for (pending in store.listPendingIngestions(MAX_PENDING_ATTEMPTS)) {
            if (isPaused()) return
            if (isSessionExcluded(pending.sessionId)) {
                val existing = store.readWatermark(pending.sessionId)
                val targetId = pending.lastEntryId ?: existing?.lastEntryId
                if (targetId != null && (existing == null || existing.updatedAt <= pending.createdAt)) {
                    store.markWatermarkOnly(
                        IngestionWatermark(
                            sessionId = pending.sessionId,
                            sessionFile = pending.sessionFile ?: existing?.sessionFile,
                            lastEntryId = targetId,
                            lastHash = existing?.lastHash,
                            updatedAt = clock(),
                        ),
                    )
                }
                store.deletePendingIngestion(pending.sessionId)
                continue
            }
            val outcome = lockFor(pending.sessionId).withLock {
                if (isPaused()) return@withLock IngestOutcome.PAUSED
                val existingWatermark = store.readWatermark(pending.sessionId)
                val alreadyPastPending = existingWatermark != null &&
                    existingWatermark.lastEntryId != null &&
                    (existingWatermark.updatedAt > pending.createdAt ||
                        (pending.lastEntryId != null && existingWatermark.lastEntryId == pending.lastEntryId))
                if (alreadyPastPending) {
                    store.deletePendingIngestion(pending.sessionId)
                    return@withLock IngestOutcome.SKIPPED
                }
                val (runOutcome, _) = runIngestionLocked(
                    sessionId = pending.sessionId,
                    sessionFile = pending.sessionFile,
                    transcript = pending.transcript,
                    lastEntryId = pending.lastEntryId,
                    reason = pending.reason,
                    existingWatermark = existingWatermark,
                    advanceWatermark = pending.lastEntryId != null,
                )
                runOutcome
            }
            when (outcome) {
                IngestOutcome.PAUSED,
                IngestOutcome.NO_MODEL,
                -> return
                IngestOutcome.FAILED -> {
                    val nextAttempts = pending.attempts + 1
                    if (nextAttempts >= MAX_PENDING_ATTEMPTS) {
                        store.deletePendingIngestion(pending.sessionId)
                    } else {
                        store.savePendingIngestion(
                            pending.copy(attempts = nextAttempts, lastError = "retry failed"),
                        )
                    }
                    return
                }
                IngestOutcome.WRITTEN,
                IngestOutcome.SKIPPED,
                IngestOutcome.DUPLICATE,
                IngestOutcome.EXCLUDED,
                -> store.deletePendingIngestion(pending.sessionId)
            }
        }
    }

    private suspend fun runIngestionLocked(
        sessionId: String,
        sessionFile: String?,
        transcript: String,
        lastEntryId: String?,
        reason: String,
        existingWatermark: IngestionWatermark?,
        advanceWatermark: Boolean,
    ): Pair<IngestOutcome, Int> = withContext(Dispatchers.IO) {
        if (sessionFile != null && !File(sessionFile).isFile) {
            logInfo(TAG, "skip memory ingestion for deleted session $sessionId")
            return@withContext IngestOutcome.SKIPPED to 0
        }
        val hash = sha256(transcript)
        if (existingWatermark?.lastHash == hash) {
            return@withContext IngestOutcome.DUPLICATE to 0
        }
        val nowMs = clock()
        val existingCore = store.activeCoreSummary(limit = 50)
        val entities = store.listEntities().take(40)
        val prompt = buildFastPassPrompt(
            today = MemorySnapshotRenderer.todayKey(nowMs),
            existingCore = existingCore,
            entities = entities,
            transcript = transcript,
        )
        val nextWatermark = IngestionWatermark(
            sessionId = sessionId,
            sessionFile = sessionFile ?: existingWatermark?.sessionFile,
            lastEntryId = lastEntryId ?: existingWatermark?.lastEntryId,
            lastHash = hash,
            updatedAt = nowMs,
        )
        val response = when (val completion = completionGateway.complete(MemoryModelTier.FAST, prompt)) {
            is MemoryCompletionResult.Success -> completion.response
            MemoryCompletionResult.Paused -> return@withContext IngestOutcome.PAUSED to 0
            MemoryCompletionResult.NoModel -> return@withContext IngestOutcome.NO_MODEL to 0
            is MemoryCompletionResult.Failed -> return@withContext IngestOutcome.FAILED to 0
        }
        val parsed = parseFastPassResponse(
            raw = response.text,
            sessionId = sessionId,
            entryId = lastEntryId,
            nowMs = nowMs,
        ) ?: return@withContext IngestOutcome.FAILED to 0

        if (parsed.isEmpty()) {
            if (advanceWatermark) store.markWatermarkOnly(nextWatermark)
            return@withContext IngestOutcome.SKIPPED to 0
        }
        val written = if (advanceWatermark) {
            store.applyIngestionBatch(parsed, nextWatermark)
        } else {
            var count = 0
            for (mutation in parsed) {
                store.save(mutation.draft, mutation.supersedesId)
                count++
            }
            count
        }
        (if (written > 0) IngestOutcome.WRITTEN else IngestOutcome.SKIPPED) to written
    }

    private fun lockFor(sessionId: String): Mutex = sessionLocks.getOrPut(sessionId) { Mutex() }

    private fun deltaEntries(entries: List<SessionEntry>, afterEntryId: String?): List<SessionEntry> {
        if (afterEntryId == null) return entries
        val index = entries.indexOfFirst { it.id == afterEntryId }
        if (index < 0) return emptyList()
        return entries.subList(index + 1, entries.size)
    }

    private fun filterOutPausedEntries(entries: List<SessionEntry>, windows: List<PauseWindow>): List<SessionEntry> {
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

    private fun entryTimestampMs(entry: SessionMessageEntry): Long? {
        val msgTs = when (val msg = entry.message) {
            is UserMessage -> msg.timestamp
            is SdkAssistantMessage -> msg.timestamp
            else -> 0L
        }
        if (msgTs > 0L) return msgTs
        return runCatching { Instant.parse(entry.timestamp.trim()).toEpochMilli() }.getOrNull()
    }

    private fun buildTranscript(entries: List<SessionEntry>, minMessages: Int): String? {
        val lines = ArrayList<String>()
        for (entry in entries) {
            if (entry !is SessionMessageEntry) continue
            when (val message = entry.message) {
                is UserMessage -> {
                    val text = when (val content = message.content) {
                        is PlainTextContent -> content.text
                        is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    }.trim()
                    if (text.isNotEmpty()) lines += "User: $text"
                }

                is SdkAssistantMessage -> {
                    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.trim()
                    if (text.isNotEmpty()) lines += "Assistant: $text"
                }

                else -> Unit
            }
        }
        if (lines.size < minMessages) return null
        return truncateTranscript(lines.joinToString("\n\n"))
    }

    private fun buildTranscriptFromUi(messages: List<UiAssistantMessage>): String? {
        val lines = messages.mapNotNull { message ->
            val text = message.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            when (message.role) {
                AssistantRole.USER -> "User: $text"
                AssistantRole.ASSISTANT -> "Assistant: $text"
            }
        }
        if (lines.size < DEFAULT_MIN_INITIAL_MESSAGES) return null
        return truncateTranscript(lines.joinToString("\n\n"))
    }

    private fun truncateTranscript(raw: String): String {
        if (raw.length <= MAX_TRANSCRIPT_CHARS) return raw
        return "...(truncated)\n\n" + raw.takeLast(MAX_TRANSCRIPT_CHARS).trimStart()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    internal fun buildFastPassPrompt(
        today: String,
        existingCore: List<MemoryRecord>,
        entities: List<MemoryEntity>,
        transcript: String,
    ): String = buildString {
        appendLine("You extract structured personal memory candidates from a user-assistant conversation.")
        appendLine("Today's date: $today")
        appendLine()
        appendLine("Rules:")
        appendLine("1. Extract ONLY durable personal facts, active near-term commitments/plans, reusable collaboration habits, or noteworthy dated episodes.")
        appendLine("2. Ignore casual banter, greetings, sarcasm, jokes, hypothetical 'what-if' coding/writing examples, temporary debugging logs, and secrets/passwords/tokens.")
        appendLine("3. Write `content` cleanly in the user's primary language, keeping exact proper nouns, product names, and people names untouched.")
        appendLine("4. Every item MUST include a short verbatim `quote` grounded in what the user said or directly agreed to.")
        appendLine("5. If an item directly replaces/updates an existing memory in the list below, set `action` = \"UPDATE\" and `supersedes_id` to that exact existing ID. Otherwise use `action` = \"ADD\".")
        appendLine("6. Set `confidence` = \"OBSERVED\" for explicitly stated facts, or \"INFERRED\" for reasonable deductions.")
        appendLine("7. For `ACTIVE` items with a clear timeframe, set integer `valid_until_days` (e.g. 7 or 14); otherwise null.")
        appendLine("8. Merge closely related fragments on the same topic into one clear entry and return at most 12 high-value items per pass.")
        appendLine("9. Output ONLY valid JSON matching: {\"items\":[{\"action\":\"ADD|UPDATE\",\"supersedes_id\":null,\"kind\":\"IDENTITY|ACTIVE|HABIT|EPISODIC\",\"content\":\"...\",\"tags\":[\"...\"],\"entities\":[{\"name\":\"...\",\"kind\":\"PERSON|PROJECT|PLACE|TOPIC|OTHER\"}],\"confidence\":\"OBSERVED|INFERRED\",\"importance\":3,\"valid_until_days\":null,\"quote\":\"...\"}]}. Return {\"items\":[]} when nothing should be saved.")
        appendLine()
        if (existingCore.isNotEmpty()) {
            appendLine("<existing_memories>")
            existingCore.forEach { item ->
                appendLine("- [id=${item.id}] (${item.kind.wire}) ${item.content}")
            }
            appendLine("</existing_memories>")
            appendLine()
        }
        if (entities.isNotEmpty()) {
            appendLine("<known_entities>")
            entities.forEach { entity ->
                val aliases = if (entity.aliases.isEmpty()) "" else " aliases=${entity.aliases.joinToString(",")}"
                appendLine("- ${entity.canonicalName} (${entity.kind.wire})$aliases")
            }
            appendLine("</known_entities>")
            appendLine()
        }
        appendLine("<conversation>")
        appendLine(transcript)
        appendLine("</conversation>")
    }

    internal fun parseFastPassResponse(
        raw: String,
        sessionId: String?,
        entryId: String?,
        nowMs: Long,
    ): List<IngestionItemMutation>? {
        val root = MemoryJsonSupport.parseObject(raw) ?: return null
        val items = root["items"] as? JsonArray ?: return null
        val parsed = ArrayList<IngestionItemMutation>()
        for (element in items) {
            if (parsed.size >= MAX_ITEMS_PER_INGEST) break
            val obj = element as? JsonObject ?: continue
            val action = obj.str("action").uppercase()
            if (action == "SKIP") continue
            val content = obj.str("content").trim()
            if (content.isEmpty()) continue
            val kind = MemoryKind.fromWire(obj.str("kind")) ?: MemoryKind.EPISODIC
            val confidence = when (obj.str("confidence").uppercase()) {
                "INFERRED" -> MemoryConfidence.INFERRED
                else -> MemoryConfidence.OBSERVED
            }
            val importance = (obj.int("importance") ?: 3).coerceIn(1, 5)
            val tags = obj.stringList("tags").take(6)
            val entities = parseEntityRefs(obj["entities"])
            val validUntilDays = obj.int("valid_until_days")?.takeIf { it > 0 && it <= 365 }
            val validUntil = validUntilDays?.let { nowMs + it * MS_PER_DAY }
            val quote = obj.str("quote").takeIf { it.isNotBlank() }?.take(240)
            val supersedesId = if (action == "UPDATE") obj.str("supersedes_id").takeIf { it.isNotBlank() } else null
            parsed += IngestionItemMutation(
                draft = MemoryDraft(
                    kind = kind,
                    content = content,
                    tags = tags,
                    entities = entities,
                    confidence = confidence,
                    importance = importance,
                    validFrom = nowMs,
                    validUntil = validUntil,
                    sourceKind = MemorySourceKind.CHAT_AUTO,
                    sourceSessionId = sessionId,
                    sourceEntryId = entryId,
                    sourceQuote = quote,
                ),
                supersedesId = supersedesId,
            )
        }
        return parsed
    }

    private fun parseEntityRefs(raw: Any?): List<MemoryEntityRef> {
        val array = raw as? JsonArray ?: return emptyList()
        val result = ArrayList<MemoryEntityRef>()
        for (entry in array) {
            when (entry) {
                is JsonPrimitive -> {
                    val name = entry.contentOrNull?.trim().orEmpty()
                    if (name.isNotEmpty()) result += MemoryEntityRef(name = name, kind = EntityKind.TOPIC)
                }
                is JsonObject -> {
                    val name = entry.str("name").trim()
                    if (name.isNotEmpty()) {
                        result += MemoryEntityRef(
                            name = name,
                            kind = EntityKind.fromWire(entry.str("kind")),
                        )
                    }
                }
                else -> Unit
            }
        }
        return result.take(6)
    }

    private companion object {
        private const val TAG = "MemoryIngestor"
        private const val REASON_SESSION_CLOSE = "session_close"
        private const val REASON_COMPACTION_FLUSH = "compaction_flush"
        private const val REASON_IDLE_DEBOUNCE = "idle_debounce"
        private const val REASON_MANUAL = "manual"
        private const val DEFAULT_MIN_INITIAL_MESSAGES = 4
        private const val DEFAULT_MIN_INCREMENTAL_MESSAGES = 2
        private const val MAX_TRANSCRIPT_CHARS = 80_000
        private const val MAX_ITEMS_PER_INGEST = 20
        private const val MAX_PENDING_ATTEMPTS = 5
        private const val MS_PER_DAY = 86_400_000L
        private const val PAUSED_PRE_WINDOW_REASON = "paused_pre_window"
    }
}

internal object MemoryJsonSupport {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun parseObject(raw: String): JsonObject? {
        val cleaned = extractJsonObjectText(raw) ?: return null
        return runCatching { JSON.parseToJsonElement(cleaned) as? JsonObject }.getOrNull()
    }

    private fun extractJsonObjectText(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end < start) return null
        return trimmed.substring(start, end + 1)
    }
}

internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .orEmpty()
