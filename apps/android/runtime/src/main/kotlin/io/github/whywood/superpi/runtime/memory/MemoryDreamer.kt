package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.DreamRunSummary
import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.logWarn
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

sealed interface DreamOutcome {
    data class Done(val summary: DreamRunSummary) : DreamOutcome

    data class Skipped(val reason: String) : DreamOutcome

    data class Failed(val message: String) : DreamOutcome

    data object Busy : DreamOutcome
}

data class ManualDreamState(
    val running: Boolean = false,
    val lastOutcome: DreamOutcome? = null,
    val outcomeSeq: Long = 0L,
)

class MemoryDreamer(
    private val store: MemoryStore,
    private val completionGateway: MemoryCompletionGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val coordinator: () -> MemoryDreamCoordinator? = { null },
) {
    private val running = AtomicBoolean(false)
    private val _manualState = MutableStateFlow(ManualDreamState())
    val manualState: StateFlow<ManualDreamState> = _manualState.asStateFlow()

    fun launchIfDue() {
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                execute(
                    minHoursSinceLastDream = OPPORTUNISTIC_COOLDOWN_HOURS,
                    minNewMemories = OPPORTUNISTIC_MIN_NEW_MEMORIES,
                    forceLlm = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "opportunistic dream failed", error)
            } finally {
                running.set(false)
            }
        }
    }

    fun launchPostMergeConsolidation() {
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                execute(
                    minHoursSinceLastDream = 0L,
                    minNewMemories = 0,
                    forceLlm = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "post-merge dream failed", error)
            } finally {
                running.set(false)
            }
        }
    }

    fun launchManual() {
        if (!running.compareAndSet(false, true)) {
            if (!_manualState.value.running) {
                _manualState.update {
                    it.copy(
                        lastOutcome = DreamOutcome.Busy,
                        outcomeSeq = it.outcomeSeq + 1L,
                    )
                }
            }
            return
        }
        _manualState.update { it.copy(running = true) }
        scope.launch(Dispatchers.IO) {
            var outcome: DreamOutcome = DreamOutcome.Failed("dream failed")
            try {
                outcome = execute(
                    minHoursSinceLastDream = 0L,
                    minNewMemories = 0,
                    forceLlm = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "manual dream run failed", error)
                outcome = DreamOutcome.Failed(error.message ?: "dream failed")
            } finally {
                running.set(false)
                _manualState.update {
                    it.copy(
                        running = false,
                        lastOutcome = outcome,
                        outcomeSeq = it.outcomeSeq + 1L,
                    )
                }
            }
        }
    }

    suspend fun runNow(): DreamOutcome {
        if (!running.compareAndSet(false, true)) return DreamOutcome.Busy
        return try {
            execute(
                minHoursSinceLastDream = 0L,
                minNewMemories = 0,
                forceLlm = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn(TAG, "manual dream run failed", error)
            DreamOutcome.Failed(error.message ?: "dream failed")
        } finally {
            running.set(false)
        }
    }

    suspend fun runFromWorker(): DreamOutcome {
        if (!running.compareAndSet(false, true)) return DreamOutcome.Busy
        return try {
            execute(
                minHoursSinceLastDream = 12L,
                minNewMemories = 1,
                forceLlm = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn(TAG, "worker dream run failed", error)
            DreamOutcome.Failed(error.message ?: "dream failed")
        } finally {
            running.set(false)
        }
    }

    private suspend fun execute(
        minHoursSinceLastDream: Long,
        minNewMemories: Int,
        forceLlm: Boolean,
    ): DreamOutcome = withContext(Dispatchers.IO) {
        val activeCoordinator = coordinator()
        var leaseId: String? = null
        if (activeCoordinator != null) {
            when (val lease = activeCoordinator.prepareForDream(forceLlm, minHoursSinceLastDream * MS_PER_HOUR)) {
                is DreamLeaseResult.Acquired -> leaseId = lease.leaseId
                DreamLeaseResult.Offline -> {
                    return@withContext if (forceLlm) {
                        DreamOutcome.Failed("sync_offline")
                    } else {
                        DreamOutcome.Skipped("sync_offline")
                    }
                }
                DreamLeaseResult.Unauthorized -> {
                    return@withContext if (forceLlm) {
                        DreamOutcome.Failed("sync_unauthorized")
                    } else {
                        DreamOutcome.Skipped("sync_unauthorized")
                    }
                }
                DreamLeaseResult.Rejected -> {
                    return@withContext if (forceLlm) {
                        DreamOutcome.Failed("sync_rejected")
                    } else {
                        DreamOutcome.Skipped("sync_rejected")
                    }
                }
                is DreamLeaseResult.Blocked -> {
                    return@withContext when {
                        forceLlm && lease.reason == "busy" -> DreamOutcome.Busy
                        forceLlm && lease.reason == "account_switch_pending" -> DreamOutcome.Failed("account_switch_pending")
                        else -> DreamOutcome.Skipped(lease.reason)
                    }
                }
            }
        }

        try {
            val runId = UUID.randomUUID().toString()
            val startedAt = clock()
            store.ensure()
            val (deterministicExpired, deterministicArchived) = store.runDeterministicCleanup()
            val context = store.loadDreamContext()

            val lastDreamAt = context.lastDreamAt
            val dueByTime = lastDreamAt == null || (startedAt - lastDreamAt) >= minHoursSinceLastDream * MS_PER_HOUR
            val enoughNew = context.newSinceLastDream >= minNewMemories
            val hasAnyMemory = context.coreRecords.isNotEmpty() || context.recentEpisodic.isNotEmpty()

            if (!hasAnyMemory) {
                return@withContext DreamOutcome.Skipped("empty")
            }
            if (!forceLlm && (!dueByTime || !enoughNew)) {
                return@withContext DreamOutcome.Skipped("not_due")
            }

            val prompt = buildDreamPrompt(
                today = MemorySnapshotRenderer.todayKey(startedAt),
                context = context,
            )
            val response = when (val completion = completionGateway.complete(MemoryModelTier.DREAM, prompt)) {
                is MemoryCompletionResult.Success -> completion.response
                MemoryCompletionResult.Paused -> return@withContext DreamOutcome.Skipped("paused")
                MemoryCompletionResult.NoModel -> {
                    val summary = DreamRunSummary(
                        id = runId,
                        startedAt = startedAt,
                        finishedAt = clock(),
                        status = "NO_MODEL",
                        modelRef = null,
                        promotedCount = 0,
                        supersededCount = 0,
                        expiredCount = deterministicExpired + deterministicArchived,
                        mergedEntities = 0,
                        error = null,
                    )
                    store.recordDreamRun(summary)
                    return@withContext DreamOutcome.Skipped("no_model")
                }
                is MemoryCompletionResult.Failed -> {
                    val failure = DreamRunSummary(
                        id = runId,
                        startedAt = startedAt,
                        finishedAt = clock(),
                        status = "FAILED",
                        modelRef = null,
                        promotedCount = 0,
                        supersededCount = 0,
                        expiredCount = deterministicExpired + deterministicArchived,
                        mergedEntities = 0,
                        error = completion.reason,
                    )
                    store.recordDreamRun(failure)
                    return@withContext DreamOutcome.Failed(completion.reason)
                }
            }

            val parsed = parseDreamResponse(response.text, startedAt)
            if (parsed == null) {
                val failure = DreamRunSummary(
                    id = runId,
                    startedAt = startedAt,
                    finishedAt = clock(),
                    status = "PARSE_ERROR",
                    modelRef = response.modelRef,
                    promotedCount = 0,
                    supersededCount = 0,
                    expiredCount = deterministicExpired + deterministicArchived,
                    mergedEntities = 0,
                    error = "invalid json response",
                )
                store.recordDreamRun(failure)
                return@withContext DreamOutcome.Failed("dream response parse error")
            }

            val summary = store.applyDreamBatch(
                batch = parsed,
                runId = runId,
                startedAt = startedAt,
                modelRef = response.modelRef,
            )
            val finishedAt = summary.finishedAt ?: clock()
            activeCoordinator?.onDreamCompleted(leaseId, finishedAt)
            DreamOutcome.Done(
                summary.copy(expiredCount = summary.expiredCount + deterministicExpired + deterministicArchived),
            )
        } finally {
            withContext(NonCancellable) {
                activeCoordinator?.onDreamFinished(leaseId)
            }
        }
    }

    internal fun buildDreamPrompt(today: String, context: DreamContextSnapshot): String = buildString {
        appendLine("You perform periodic sleep-time consolidation for a personal AI assistant's long-term memory.")
        appendLine("Today's date: $today")
        appendLine()
        appendLine("Goals:")
        appendLine("1. Promote repeated patterns or enduring takeaways from recent episodes into IDENTITY, ACTIVE, or HABIT items.")
        appendLine("2. Resolve contradictions: if a newer episode or active note invalidates an older ACTIVE/IDENTITY/HABIT entry, output a supersession (`old_id` -> new concise statement).")
        appendLine("3. Mark completed or stale `ACTIVE` items as expired (`expirations`), and redundant low-value items as archived (`archives`).")
        appendLine("4. Merge duplicate entities / aliases (e.g. nickname vs full name) into a clean canonical entity (`entity_merges`).")
        appendLine()
        appendLine("Hard Safety Rules:")
        appendLine("- NEVER modify, supersede, expire, or archive any entry marked `[PINNED]`.")
        appendLine("- NEVER expire or archive `[CONFIRMED]` entries unless directly superseded with a replacement.")
        appendLine("- Keep the user's original language and exact proper nouns/names.")
        appendLine("- Output ONLY strict JSON:")
        appendLine("{\"promotions\":[{\"kind\":\"IDENTITY|ACTIVE|HABIT\",\"content\":\"...\",\"tags\":[],\"entities\":[],\"confidence\":\"OBSERVED|INFERRED\",\"importance\":4,\"valid_until_days\":null}],\"supersessions\":[{\"old_id\":\"...\",\"kind\":\"IDENTITY|ACTIVE|HABIT\",\"content\":\"...\",\"tags\":[],\"entities\":[],\"confidence\":\"OBSERVED|INFERRED\",\"importance\":4,\"valid_until_days\":null}],\"expirations\":[\"id\"],\"archives\":[\"id\"],\"entity_merges\":[{\"keep_name\":\"...\",\"merge_names\":[\"...\"],\"summary\":\"...\"}]}")
        appendLine()
        appendLine("<active_core_memories>")
        context.coreRecords.forEach { item ->
            val pinFlag = if (item.pinned) " [PINNED]" else ""
            val date = MemorySnapshotRenderer.formatDate(item.validFrom)
            appendLine("- [id=${item.id}] [${item.kind.wire}] [${item.confidence.wire}]$pinFlag ($date) ${item.content}")
        }
        appendLine("</active_core_memories>")
        appendLine()
        appendLine("<recent_episodes>")
        context.recentEpisodic.forEach { item ->
            val date = MemorySnapshotRenderer.formatDate(item.validFrom)
            appendLine("- [id=${item.id}] ($date) ${item.content}")
        }
        appendLine("</recent_episodes>")
        appendLine()
        if (context.entities.isNotEmpty()) {
            appendLine("<entities>")
            context.entities.forEach { entity ->
                val aliases = if (entity.aliases.isEmpty()) "" else " aliases=[${entity.aliases.joinToString(", ")}]"
                val sum = entity.summary?.let { " summary=$it" }.orEmpty()
                appendLine("- ${entity.canonicalName} (${entity.kind.wire})$aliases$sum")
            }
            appendLine("</entities>")
        }
    }

    internal fun parseDreamResponse(raw: String, nowMs: Long): DreamMutationBatch? {
        val root = MemoryJsonSupport.parseObject(raw) ?: return null

        val promotions = ArrayList<MemoryDraft>()
        (root["promotions"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val draft = obj.toDraft(nowMs, MemorySourceKind.DREAM_SYNTH) ?: return@forEach
            promotions += draft
        }

        val supersessions = ArrayList<Pair<String, MemoryDraft>>()
        (root["supersessions"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val oldId = obj.str("old_id")
            if (oldId.isEmpty()) return@forEach
            val draft = obj.toDraft(nowMs, MemorySourceKind.DREAM_SYNTH) ?: return@forEach
            supersessions += oldId to draft
        }

        val expirations = root.stringList("expirations")
        val archives = root.stringList("archives")

        val merges = ArrayList<DreamEntityMergeOp>()
        (root["entity_merges"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val keep = obj.str("keep_name")
            val mergeNames = obj.stringList("merge_names")
            val summary = obj.str("summary").takeIf { it.isNotBlank() }
            if (keep.isNotEmpty() && mergeNames.isNotEmpty()) {
                merges += DreamEntityMergeOp(keep, mergeNames, summary)
            }
        }

        return DreamMutationBatch(
            promotions = promotions,
            supersessions = supersessions,
            expirations = expirations,
            archives = archives,
            entityMerges = merges,
        )
    }

    private fun JsonObject.toDraft(nowMs: Long, sourceKind: MemorySourceKind): MemoryDraft? {
        val content = str("content")
        if (content.isEmpty()) return null
        val kind = MemoryKind.fromWire(str("kind")) ?: MemoryKind.ACTIVE
        val confidence = when (str("confidence").uppercase()) {
            "INFERRED" -> MemoryConfidence.INFERRED
            else -> MemoryConfidence.OBSERVED
        }
        val importance = (int("importance") ?: 4).coerceIn(1, 5)
        val validUntilDays = int("valid_until_days")?.takeIf { it > 0 && it <= 365 }
        val entities = stringList("entities").map { MemoryEntityRef(name = it, kind = EntityKind.TOPIC) }
        return MemoryDraft(
            kind = kind,
            content = content,
            tags = stringList("tags").take(6),
            entities = entities,
            confidence = confidence,
            importance = importance,
            validFrom = nowMs,
            validUntil = validUntilDays?.let { nowMs + it * MS_PER_DAY },
            sourceKind = sourceKind,
        )
    }

    private companion object {
        private const val TAG = "MemoryDreamer"
        private const val OPPORTUNISTIC_COOLDOWN_HOURS = 24L
        private const val OPPORTUNISTIC_MIN_NEW_MEMORIES = 5
        private const val MS_PER_HOUR = 3_600_000L
        private const val MS_PER_DAY = 86_400_000L
    }
}
