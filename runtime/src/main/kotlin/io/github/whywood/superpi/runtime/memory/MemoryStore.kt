package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.DreamRunSummary
import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryExportLabels
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemoryOverview
import io.github.whywood.superpi.runtime.MemoryRecord
import io.github.whywood.superpi.runtime.MemorySearchHit
import io.github.whywood.superpi.runtime.MemoryTagCount
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class CapturedTranscript(
    val sessionId: String,
    val sessionFile: String?,
    val entries: List<SessionEntry>,
)

internal data class IngestionItemMutation(
    val draft: MemoryDraft,
    val supersedesId: String? = null,
)

internal data class DreamContextSnapshot(
    val lastDreamAt: Long?,
    val newSinceLastDream: Int,
    val coreRecords: List<MemoryRecord>,
    val recentEpisodic: List<MemoryRecord>,
    val entities: List<MemoryEntity>,
)

internal data class DreamEntityMergeOp(
    val keepName: String,
    val mergeNames: List<String>,
    val summary: String? = null,
)

internal data class DreamMutationBatch(
    val promotions: List<MemoryDraft> = emptyList(),
    val supersessions: List<Pair<String, MemoryDraft>> = emptyList(),
    val expirations: List<String> = emptyList(),
    val archives: List<String> = emptyList(),
    val entityMerges: List<DreamEntityMergeOp> = emptyList(),
)

internal data class PauseWindow(
    val startMs: Long,
    val endMs: Long? = null,
)

class MemoryStore(
    rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val db = MemoryDatabase(rootDir)
    private val retriever = FtsEntityRetriever(db = db, clock = clock)
    private val captured = ConcurrentHashMap<String, CapturedTranscript>()
    private val mutableChanges = MutableStateFlow(0L)

    val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    suspend fun ensure() = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.ensure()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.close()
        }
    }

    suspend fun section(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                db.ensure()
                val cached = db.read { conn -> MemoryStorage.readSnapshot(conn) }
                if (cached != null) {
                    cached.renderedText.takeIf { it.isNotBlank() }
                } else {
                    rebuildSnapshotLocked("session_start").takeIf { it.isNotBlank() }
                }
            }.onFailure { logWarn(TAG, "read memory section failed", it) }.getOrNull()
        }
    }

    suspend fun refresh(reason: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                db.ensure()
                rebuildSnapshotLocked(reason).takeIf { it.isNotBlank() }
            }.onFailure { logWarn(TAG, "refresh memory snapshot failed", it) }.getOrNull()
        }
    }

    internal fun capture(sessionId: String, sessionFile: String?, entries: List<SessionEntry>?) {
        if (entries.isNullOrEmpty()) return
        captured[sessionId] = CapturedTranscript(sessionId, sessionFile, entries)
    }

    internal fun takeCaptured(sessionId: String): CapturedTranscript? = captured.remove(sessionId)

    suspend fun save(draft: MemoryDraft, supersedesId: String? = null): MemoryRecord = withContext(Dispatchers.IO) {
        val saved = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                val targetSupersedes = supersedesId?.trim()?.takeIf { it.isNotEmpty() }
                    ?.takeIf { MemoryStorage.findById(conn, it) != null }
                val created = MemoryStorage.insertMemory(conn, draft, nowMs, targetSupersedes)
                rebuildSnapshotInConn(conn, "save", nowMs)
                created
            }
        }
        notifyChanged()
        saved
    }

    suspend fun update(
        id: String,
        content: String,
        kind: MemoryKind,
        tags: List<String>,
        confidence: MemoryConfidence,
        pinned: Boolean,
        validUntil: Long?,
    ): MemoryRecord? = withContext(Dispatchers.IO) {
        val updated = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                val result = MemoryStorage.updateMemory(
                    conn = conn,
                    id = id,
                    content = content,
                    kind = kind,
                    tags = tags,
                    confidence = confidence,
                    pinned = pinned,
                    validUntil = validUntil,
                    nowMs = nowMs,
                )
                if (result != null) rebuildSnapshotInConn(conn, "edit", nowMs)
                result
            }
        }
        if (updated != null) notifyChanged()
        updated
    }

    suspend fun confirm(id: String): MemoryRecord? = withContext(Dispatchers.IO) {
        val confirmed = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                val result = MemoryStorage.confirmMemory(conn, id, nowMs)
                if (result != null) rebuildSnapshotInConn(conn, "confirm", nowMs)
                result
            }
        }
        if (confirmed != null) notifyChanged()
        confirmed
    }

    suspend fun softDelete(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                val removed = MemoryStorage.softDelete(conn, id, nowMs)
                if (removed) rebuildSnapshotInConn(conn, "delete", nowMs)
                removed
            }
        }
        if (deleted) notifyChanged()
        deleted
    }

    suspend fun restore(id: String): MemoryRecord? = withContext(Dispatchers.IO) {
        val restored = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                val record = MemoryStorage.restore(conn, id, nowMs)
                if (record != null) rebuildSnapshotInConn(conn, "restore", nowMs)
                record
            }
        }
        if (restored != null) notifyChanged()
        restored
    }

    suspend fun deleteByTag(tag: String): Int = withContext(Dispatchers.IO) {
        val removed = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                val count = MemoryStorage.softDeleteByTag(conn, tag, nowMs)
                if (count > 0) rebuildSnapshotInConn(conn, "delete_tag", nowMs)
                count
            }
        }
        if (removed > 0) notifyChanged()
        removed
    }

    suspend fun deleteBySession(sessionId: String): Int = withContext(Dispatchers.IO) {
        val removed = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                val count = MemoryStorage.softDeleteBySession(conn, sessionId, nowMs)
                if (count > 0) rebuildSnapshotInConn(conn, "delete_session", nowMs)
                count
            }
        }
        if (removed > 0) notifyChanged()
        removed
    }

    suspend fun countBySession(sessionId: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.countBySession(conn, sessionId) }
        }
    }

    suspend fun get(id: String): MemoryRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.findById(conn, id) }
        }
    }

    suspend fun list(filter: MemoryFilter = MemoryFilter()): List<MemoryRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.queryRecords(conn, filter) }
        }
    }

    suspend fun search(
        queries: List<String>,
        filter: MemoryFilter = MemoryFilter(),
    ): List<MemorySearchHit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            retriever.search(queries, filter)
        }
    }

    suspend fun listTags(): List<MemoryTagCount> = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.listTags(conn) }
        }
    }

    suspend fun listEntities(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.listActiveEntities(conn) }
        }
    }

    suspend fun overview(): MemoryOverview = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.overview(conn) }
        }
    }

    suspend fun exportMarkdown(labels: MemoryExportLabels = MemoryExportLabels.ENGLISH): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val nowMs = clock()
                db.read { conn ->
                    val all = MemoryStorage.queryRecords(
                        conn,
                        MemoryFilter(includeHistory = true, limit = 10_000),
                    )
                    val entities = MemoryStorage.listActiveEntities(conn)
                    MemorySnapshotRenderer.exportMarkdown(all, entities, nowMs, labels)
                }
            }
        }

    internal suspend fun readWatermark(sessionId: String): IngestionWatermark? = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.readWatermark(conn, sessionId) }
        }
    }

    internal suspend fun listPendingIngestions(maxAttempts: Int = 5): List<PendingIngestionRecord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                db.read { conn -> MemoryStorage.listPendingIngestions(conn, maxAttempts) }
            }
        }

    internal suspend fun savePendingIngestion(pending: PendingIngestionRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn -> MemoryStorage.savePendingIngestion(conn, pending) }
        }
    }

    internal suspend fun hasPendingIngestion(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> MemoryStorage.hasPendingIngestion(conn, sessionId) }
        }
    }

    internal suspend fun deletePendingIngestion(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn -> MemoryStorage.deletePendingIngestion(conn, sessionId) }
        }
    }

    internal suspend fun pruneExhaustedPendingIngestions(maxAttempts: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn -> MemoryStorage.pruneExhaustedPendingIngestions(conn, maxAttempts) }
        }
    }

    internal suspend fun activeCoreSummary(limit: Int = 60): List<MemoryRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn ->
                MemoryStorage.queryRecords(
                    conn,
                    MemoryFilter(
                        kinds = setOf(MemoryKind.IDENTITY, MemoryKind.ACTIVE, MemoryKind.HABIT),
                        includeHistory = false,
                        limit = limit,
                    ),
                )
            }
        }
    }

    internal suspend fun applyIngestionBatch(
        mutations: List<IngestionItemMutation>,
        watermark: IngestionWatermark,
    ): Int = withContext(Dispatchers.IO) {
        val written = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                var inserted = 0
                for (mutation in mutations) {
                    val existingSupersede = mutation.supersedesId?.trim()?.takeIf { it.isNotEmpty() }
                        ?.takeIf { MemoryStorage.findById(conn, it)?.pinned == false }
                    if (existingSupersede == null && isDuplicateInStore(conn, mutation.draft)) {
                        continue
                    }
                    MemoryStorage.insertMemory(conn, mutation.draft, nowMs, existingSupersede)
                    inserted++
                }
                MemoryStorage.saveWatermark(conn, watermark)
                if (inserted > 0) rebuildSnapshotInConn(conn, "ingest", nowMs)
                inserted
            }
        }
        if (written > 0) notifyChanged()
        written
    }

    internal suspend fun markWatermarkOnly(watermark: IngestionWatermark) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn -> MemoryStorage.saveWatermark(conn, watermark) }
        }
    }

    internal suspend fun readPauseWindows(): List<PauseWindow> = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn -> parsePauseWindows(MemoryStorage.readSyncState(conn, SYNC_KEY_PAUSE_WINDOWS)) }
        }
    }

    internal suspend fun openPauseWindow(startMs: Long): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn ->
                val current = parsePauseWindows(MemoryStorage.readSyncState(conn, SYNC_KEY_PAUSE_WINDOWS))
                if (current.any { it.endMs == null }) return@writeTransaction false
                val updated = prunePauseWindows(current + PauseWindow(startMs = startMs, endMs = null), startMs)
                MemoryStorage.writeSyncState(conn, SYNC_KEY_PAUSE_WINDOWS, encodePauseWindows(updated))
                true
            }
        }
    }

    internal suspend fun closePauseWindow(endMs: Long): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn ->
                val current = parsePauseWindows(MemoryStorage.readSyncState(conn, SYNC_KEY_PAUSE_WINDOWS))
                if (current.none { it.endMs == null }) return@writeTransaction false
                val closed = current.map { window ->
                    if (window.endMs == null) window.copy(endMs = maxOf(window.startMs, endMs)) else window
                }
                val updated = prunePauseWindows(closed, endMs)
                MemoryStorage.writeSyncState(conn, SYNC_KEY_PAUSE_WINDOWS, encodePauseWindows(updated))
                true
            }
        }
    }

    internal suspend fun runDeterministicCleanup(staleInferredDays: Long = 45L): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val counts = mutex.withLock {
                db.writeTransaction { conn ->
                    val nowMs = nextWriteTimestampLocked(conn)
                    val staleCutoff = nowMs - staleInferredDays * MS_PER_DAY
                    val expired = MemoryStorage.expireOutdatedActive(conn, nowMs)
                    val archived = MemoryStorage.archiveStaleInferred(conn, staleCutoff, nowMs) +
                        MemoryStorage.deduplicateExactActiveMemories(conn, nowMs)
                    if (expired > 0 || archived > 0) rebuildSnapshotInConn(conn, "cleanup", nowMs)
                    MemoryStorage.pruneDreamRuns(conn)
                    val linkedAccount = MemoryStorage.readSyncState(conn, SYNC_KEY_ACCOUNT)
                    if (linkedAccount == null) {
                        MemoryStorage.pruneSyncOps(conn, maxSeqToPrune = Long.MAX_VALUE)
                    } else {
                        val pushedSeq = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ)?.toLongOrNull() ?: 0L
                        if (pushedSeq > 0L) {
                            MemoryStorage.pruneSyncOps(conn, maxSeqToPrune = pushedSeq)
                        }
                    }
                    expired to archived
                }
            }
            if (counts.first > 0 || counts.second > 0) notifyChanged()
            counts
        }

    internal suspend fun loadDreamContext(): DreamContextSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.read { conn ->
                val localDream = MemoryStorage.lastSuccessfulDreamAt(conn)
                val remoteDream = MemoryStorage.readSyncState(conn, SYNC_KEY_REMOTE_LAST_DREAM_AT)?.toLongOrNull()
                val lastDream = listOfNotNull(localDream, remoteDream).maxOrNull()
                val newCount = MemoryStorage.countMemoriesCreatedAfter(conn, lastDream ?: 0L)
                val core = MemoryStorage.queryRecords(
                    conn,
                    MemoryFilter(
                        kinds = setOf(MemoryKind.IDENTITY, MemoryKind.ACTIVE, MemoryKind.HABIT),
                        includeHistory = false,
                        limit = 120,
                    ),
                )
                val episodicSince = lastDream ?: (clock() - 30L * MS_PER_DAY)
                val episodic = MemoryStorage.queryRecords(
                    conn,
                    MemoryFilter(
                        kinds = setOf(MemoryKind.EPISODIC),
                        sinceEpochMs = episodicSince,
                        includeHistory = false,
                        limit = 120,
                    ),
                )
                val entities = MemoryStorage.listActiveEntities(conn)
                DreamContextSnapshot(
                    lastDreamAt = lastDream,
                    newSinceLastDream = newCount,
                    coreRecords = core,
                    recentEpisodic = episodic,
                    entities = entities,
                )
            }
        }
    }

    internal suspend fun applyDreamBatch(
        batch: DreamMutationBatch,
        runId: String,
        startedAt: Long,
        modelRef: String?,
    ): DreamRunSummary = withContext(Dispatchers.IO) {
        val summary = mutex.withLock {
            db.writeTransaction { conn ->
                val nowMs = nextWriteTimestampLocked(conn)
                var promoted = 0
                var superseded = 0
                var expired = 0
                var mergedEntities = 0

                for (draft in batch.promotions) {
                    if (!isDuplicateInStore(conn, draft)) {
                        MemoryStorage.insertMemory(conn, draft, nowMs)
                        promoted++
                    }
                }

                for ((oldId, replacement) in batch.supersessions) {
                    val existing = MemoryStorage.findById(conn, oldId) ?: continue
                    if (existing.pinned || existing.status != MemoryItemStatus.ACTIVE) continue
                    MemoryStorage.insertMemory(conn, replacement, nowMs, existing.id)
                    superseded++
                }

                for (id in batch.expirations) {
                    val existing = MemoryStorage.findById(conn, id) ?: continue
                    if (existing.pinned || existing.confidence == MemoryConfidence.CONFIRMED) continue
                    if (existing.status == MemoryItemStatus.ACTIVE) {
                        if (MemoryStorage.markStatus(conn, id, MemoryItemStatus.EXPIRED, null, nowMs)) expired++
                    }
                }

                for (id in batch.archives) {
                    val existing = MemoryStorage.findById(conn, id) ?: continue
                    if (existing.pinned || existing.confidence == MemoryConfidence.CONFIRMED) continue
                    if (existing.status == MemoryItemStatus.ACTIVE) {
                        if (MemoryStorage.markStatus(conn, id, MemoryItemStatus.ARCHIVED, null, nowMs)) expired++
                    }
                }

                for (merge in batch.entityMerges) {
                    if (merge.keepName.isNotBlank() && merge.mergeNames.isNotEmpty()) {
                        mergedEntities += MemoryStorage.mergeEntities(
                            conn = conn,
                            keepName = merge.keepName,
                            mergeNames = merge.mergeNames,
                            summary = merge.summary,
                            nowMs = nowMs,
                        )
                    }
                }

                rebuildSnapshotInConn(conn, "dream", nowMs)
                val recorded = DreamRunSummary(
                    id = runId,
                    startedAt = startedAt,
                    finishedAt = nowMs,
                    status = "DONE",
                    modelRef = modelRef,
                    promotedCount = promoted,
                    supersededCount = superseded,
                    expiredCount = expired,
                    mergedEntities = mergedEntities,
                    error = null,
                )
                MemoryStorage.recordDreamRun(conn, recorded)
                recorded
            }
        }
        notifyChanged()
        summary
    }

    internal suspend fun recordDreamRun(summary: DreamRunSummary) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn -> MemoryStorage.recordDreamRun(conn, summary) }
        }
    }

    suspend fun readSyncCursor(accountKey: String): MemorySyncCursor = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn ->
                ensureAccountCursorLocked(conn, accountKey)
                readCursorLocked(conn, accountKey)
            }
        }
    }

    suspend fun collectPendingPushBatch(
        accountKey: String,
        deviceId: String,
        limit: Int = 100,
    ): MemoryPendingPushBatch = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn ->
                ensureAccountCursorLocked(conn, accountKey)
                val initialPushDone = MemoryStorage.readSyncState(conn, SYNC_KEY_INITIAL_PUSH_DONE) == "1"
                val cutoffSeq = MemoryStorage.readSyncState(conn, SYNC_KEY_BASELINE_CUTOFF_SEQ)?.toLongOrNull() ?: 0L
                if (!initialPushDone) {
                    val stage = MemoryStorage.readSyncState(conn, SYNC_KEY_BASELINE_STAGE) ?: STAGE_ENTITY
                    val cursorId = MemoryStorage.readSyncState(conn, SYNC_KEY_BASELINE_CURSOR_ID).orEmpty()
                    if (stage == STAGE_ENTITY) {
                        val rawEntities = MemoryStorage.exportBaselineEntities(conn, cursorId, deviceId, limit + 1)
                        val entityHasMore = rawEntities.size > limit
                        val entities = if (entityHasMore) rawEntities.take(limit) else rawEntities
                        if (entities.isNotEmpty()) {
                            val nextCursor = entities.last().entity?.id ?: cursorId
                            return@writeTransaction MemoryPendingPushBatch(
                                ops = entities,
                                isBaseline = true,
                                nextBaselineStage = if (entityHasMore) STAGE_ENTITY else STAGE_MEMORY,
                                nextBaselineCursorId = if (entityHasMore) nextCursor else "",
                                nextInitialPushDone = false,
                                maxLocalSyncSeq = 0L,
                                baselineCutoffSeq = cutoffSeq,
                            )
                        }
                    }
                    val memoryCursor = if (stage == STAGE_ENTITY) "" else cursorId
                    val rawMemories = MemoryStorage.exportBaselineMemories(conn, memoryCursor, deviceId, limit + 1)
                    val memoryHasMore = rawMemories.size > limit
                    val memories = if (memoryHasMore) rawMemories.take(limit) else rawMemories
                    val memoryDone = !memoryHasMore
                    val nextMemoryCursor = memories.lastOrNull()?.memory?.id ?: memoryCursor
                    if (memories.isEmpty()) {
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_STAGE, STAGE_DONE)
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_CURSOR_ID, "")
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_INITIAL_PUSH_DONE, "1")
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ, cutoffSeq.toString())
                    }
                    return@writeTransaction MemoryPendingPushBatch(
                        ops = memories,
                        isBaseline = true,
                        nextBaselineStage = if (memoryDone) STAGE_DONE else STAGE_MEMORY,
                        nextBaselineCursorId = if (memoryDone) "" else nextMemoryCursor,
                        nextInitialPushDone = memoryDone,
                        maxLocalSyncSeq = 0L,
                        baselineCutoffSeq = cutoffSeq,
                    )
                }

                val lastPushedSeq = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ)?.toLongOrNull() ?: 0L
                val (maxSeq, ops) = MemoryStorage.exportIncrementalOps(conn, lastPushedSeq, deviceId, limit)
                if (ops.isEmpty() && maxSeq > lastPushedSeq) {
                    MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ, maxSeq.toString())
                }
                MemoryPendingPushBatch(
                    ops = ops,
                    isBaseline = false,
                    nextBaselineStage = STAGE_DONE,
                    nextBaselineCursorId = "",
                    nextInitialPushDone = true,
                    maxLocalSyncSeq = maxSeq,
                    baselineCutoffSeq = cutoffSeq,
                )
            }
        }
    }

    suspend fun markPushAcked(
        accountKey: String,
        batch: MemoryPendingPushBatch,
        serverSeq: Long,
        remoteLastDreamAt: Long?,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn ->
                if (MemoryStorage.readSyncState(conn, SYNC_KEY_ACCOUNT) != accountKey) return@writeTransaction
                if (batch.isBaseline) {
                    MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_STAGE, batch.nextBaselineStage)
                    MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_CURSOR_ID, batch.nextBaselineCursorId)
                    if (batch.nextInitialPushDone) {
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_INITIAL_PUSH_DONE, "1")
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ, batch.baselineCutoffSeq.toString())
                        MemoryStorage.pruneSyncOps(conn, maxSeqToPrune = batch.baselineCutoffSeq)
                    }
                } else if (batch.maxLocalSyncSeq > 0L) {
                    val existingSeq = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ)?.toLongOrNull() ?: 0L
                    val ackedSeq = maxOf(existingSeq, batch.maxLocalSyncSeq)
                    MemoryStorage.writeSyncState(
                        conn,
                        SYNC_KEY_LAST_PUSHED_SEQ,
                        ackedSeq.toString(),
                    )
                    MemoryStorage.pruneSyncOps(conn, maxSeqToPrune = ackedSeq)
                }
                if (remoteLastDreamAt != null) {
                    updateRemoteLastDreamLocked(conn, remoteLastDreamAt)
                }
                val currentPulled = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_PULLED_SEQ)?.toLongOrNull() ?: 0L
                if (batch.ops.isEmpty() && serverSeq > currentPulled) {
                    val maxRemoteTs = MemoryStorage.readSyncState(conn, SYNC_KEY_MAX_REMOTE_TS)?.toLongOrNull() ?: 0L
                    if (remoteLastDreamAt != null && remoteLastDreamAt > maxRemoteTs) {
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_MAX_REMOTE_TS, remoteLastDreamAt.toString())
                    }
                }
            }
        }
    }

    suspend fun inspectAccountSwitch(accountIdentity: String): MemoryAccountSwitchCheck =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                db.writeTransaction { conn ->
                    val previousIdentity = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY)
                        ?: MemoryStorage.readSyncState(conn, SYNC_KEY_ACCOUNT)
                            ?.let(::extractAccountIdentity)
                            ?.also { MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY, it) }
                    if (previousIdentity == null || previousIdentity == accountIdentity) {
                        return@writeTransaction MemoryAccountSwitchCheck.Ready
                    }
                    val localCount = MemoryStorage.countLocalMemories(conn)
                    if (localCount == 0) {
                        MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY, accountIdentity)
                        MemoryStorage.deleteSyncState(conn, SYNC_KEY_ACCOUNT)
                        MemoryAccountSwitchCheck.Ready
                    } else {
                        MemoryAccountSwitchCheck.NeedsDecision(localCount)
                    }
                }
            }
        }

    suspend fun resolveAccountSwitch(
        accountKey: String,
        accountIdentity: String,
        mode: MemoryAccountSwitchMode,
    ) {
        var wiped = false
        withContext(Dispatchers.IO) {
            mutex.withLock {
                db.writeTransaction { conn ->
                    when (mode) {
                        MemoryAccountSwitchMode.MERGE_ALL -> {
                            MemoryStorage.markAllLocalDataOwnedForMerge(conn)
                            val cutoffSeq = MemoryStorage.currentMaxSyncSeq(conn)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY, accountIdentity)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_ACCOUNT, accountKey)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PULLED_SEQ, "0")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ, "0")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_INITIAL_PUSH_DONE, "0")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_STAGE, STAGE_ENTITY)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_CURSOR_ID, "")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_CUTOFF_SEQ, cutoffSeq.toString())
                            MemoryStorage.deleteSyncState(conn, SYNC_KEY_REMOTE_LAST_DREAM_AT)
                            MemoryStorage.writeSyncState(
                                conn,
                                SYNC_KEY_HAD_LOCAL_PRE_MERGE,
                                if (MemoryStorage.countLocalMemories(conn) > 0) "1" else "0",
                            )
                            MemoryStorage.deleteSyncState(conn, SYNC_KEY_POST_MERGE_CONSOLIDATED)
                        }

                        MemoryAccountSwitchMode.REPLACE_WITH_REMOTE -> {
                            MemoryStorage.wipeAllMemoryDataForAccountSwitch(conn)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY, accountIdentity)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_ACCOUNT, accountKey)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PULLED_SEQ, "0")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ, "0")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_INITIAL_PUSH_DONE, "1")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_STAGE, STAGE_DONE)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_CURSOR_ID, "")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_CUTOFF_SEQ, "0")
                            MemoryStorage.deleteSyncState(conn, SYNC_KEY_REMOTE_LAST_DREAM_AT)
                            MemoryStorage.deleteSyncState(conn, SYNC_KEY_MAX_REMOTE_TS)
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_HAD_LOCAL_PRE_MERGE, "0")
                            MemoryStorage.writeSyncState(conn, SYNC_KEY_POST_MERGE_CONSOLIDATED, "1")
                            rebuildSnapshotInConn(conn, "account_switch", clock())
                            wiped = true
                        }
                    }
                }
            }
        }
        if (wiped) notifyChanged()
    }

    suspend fun applyRemotePullBatch(
        accountKey: String,
        selfDeviceId: String,
        entries: List<MemoryRemotePullEntry>,
        nextSince: Long,
        serverSeq: Long,
        remoteLastDreamAt: Long?,
    ): MemoryRemoteMergeResult = withContext(Dispatchers.IO) {
        val result = mutex.withLock {
            val nowMs = clock()
            db.writeTransaction { conn ->
                ensureAccountCursorLocked(conn, accountKey)
                var applied = 0
                var maxRemoteTs = MemoryStorage.readSyncState(conn, SYNC_KEY_MAX_REMOTE_TS)?.toLongOrNull() ?: 0L
                var receivedPeerMemory = false

                for (entry in entries) {
                    if (entry.kind == MemorySyncOpKind.ENTITY && entry.entity != null) {
                        maxRemoteTs = maxOf(maxRemoteTs, entry.entity.updatedAt)
                        if (MemoryStorage.applyRemoteEntity(conn, entry.entity, nowMs)) {
                            applied++
                        }
                    }
                }
                for (entry in entries) {
                    if (entry.kind == MemorySyncOpKind.MEMORY && entry.memory != null) {
                        maxRemoteTs = maxOf(maxRemoteTs, entry.memory.updatedAt)
                        if (MemoryStorage.applyRemoteMemory(conn, entry.memory, nowMs)) {
                            applied++
                        }
                        if (entry.deviceId != selfDeviceId && entry.memory.deletedAt == null) {
                            receivedPeerMemory = true
                        }
                    }
                }

                if (maxRemoteTs > 0L) {
                    MemoryStorage.writeSyncState(conn, SYNC_KEY_MAX_REMOTE_TS, maxRemoteTs.toString())
                }
                val currentPulled = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_PULLED_SEQ)?.toLongOrNull() ?: 0L
                MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PULLED_SEQ, maxOf(currentPulled, nextSince).toString())
                if (remoteLastDreamAt != null) {
                    updateRemoteLastDreamLocked(conn, remoteLastDreamAt)
                }

                val hadLocalPreMerge = MemoryStorage.readSyncState(conn, SYNC_KEY_HAD_LOCAL_PRE_MERGE) == "1"
                val consolidated = MemoryStorage.readSyncState(conn, SYNC_KEY_POST_MERGE_CONSOLIDATED) == "1"
                val overlap = hadLocalPreMerge && receivedPeerMemory && !consolidated
                if (overlap) {
                    MemoryStorage.writeSyncState(conn, SYNC_KEY_POST_MERGE_CONSOLIDATED, "1")
                }
                if (applied > 0) {
                    rebuildSnapshotInConn(conn, "sync_pull", nowMs)
                }
                MemoryRemoteMergeResult(
                    appliedCount = applied,
                    hadLocalAndRemoteOverlap = overlap,
                    serverSeq = serverSeq,
                )
            }
        }
        if (result.appliedCount > 0) notifyChanged()
        result
    }

    suspend fun updateRemoteLastDreamAt(accountKey: String, remoteLastDreamAt: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.writeTransaction { conn ->
                if (MemoryStorage.readSyncState(conn, SYNC_KEY_ACCOUNT) != accountKey) return@writeTransaction
                updateRemoteLastDreamLocked(conn, remoteLastDreamAt)
            }
        }
    }

    private fun nextWriteTimestampLocked(conn: androidx.sqlite.SQLiteConnection): Long {
        val maxRemote = MemoryStorage.readSyncState(conn, SYNC_KEY_MAX_REMOTE_TS)?.toLongOrNull() ?: 0L
        val localClock = clock()
        if (maxRemote > 0L && localClock <= maxRemote) {
            val next = maxRemote + 1L
            MemoryStorage.writeSyncState(conn, SYNC_KEY_MAX_REMOTE_TS, next.toString())
            return next
        }
        return localClock
    }

    private fun ensureAccountCursorLocked(conn: androidx.sqlite.SQLiteConnection, accountKey: String) {
        val current = MemoryStorage.readSyncState(conn, SYNC_KEY_ACCOUNT)
        val identity = extractAccountIdentity(accountKey)
        if (current == accountKey) {
            if (MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY) == null) {
                MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY, identity)
            }
            return
        }
        val previousIdentity = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY)
            ?: current?.let(::extractAccountIdentity)
        val sameAccountReEnroll = previousIdentity == identity
        MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_ACCOUNT_IDENTITY, identity)
        MemoryStorage.writeSyncState(conn, SYNC_KEY_ACCOUNT, accountKey)
        MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PULLED_SEQ, "0")
        MemoryStorage.writeSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ, "0")
        MemoryStorage.writeSyncState(conn, SYNC_KEY_INITIAL_PUSH_DONE, "0")
        MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_STAGE, STAGE_ENTITY)
        MemoryStorage.writeSyncState(conn, SYNC_KEY_BASELINE_CURSOR_ID, "")
        MemoryStorage.writeSyncState(
            conn,
            SYNC_KEY_BASELINE_CUTOFF_SEQ,
            MemoryStorage.currentMaxSyncSeq(conn).toString(),
        )
        MemoryStorage.deleteSyncState(conn, SYNC_KEY_REMOTE_LAST_DREAM_AT)
        if (sameAccountReEnroll) {
            MemoryStorage.writeSyncState(conn, SYNC_KEY_HAD_LOCAL_PRE_MERGE, "0")
        } else {
            MemoryStorage.writeSyncState(
                conn,
                SYNC_KEY_HAD_LOCAL_PRE_MERGE,
                if (MemoryStorage.countLocalMemories(conn) > 0) "1" else "0",
            )
            MemoryStorage.deleteSyncState(conn, SYNC_KEY_POST_MERGE_CONSOLIDATED)
        }
    }

    private fun extractAccountIdentity(accountKey: String): String {
        val parts = accountKey.split('|')
        return if (parts.size >= 2) "${parts[0]}|${parts[1]}" else accountKey
    }

    private fun parsePauseWindows(raw: String?): List<PauseWindow> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val startMs = (obj["startMs"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val endMs = (obj["endMs"] as? JsonPrimitive)?.longOrNull
                PauseWindow(startMs = startMs, endMs = endMs)
            }
        }.getOrDefault(emptyList())
    }

    private fun prunePauseWindows(windows: List<PauseWindow>, nowMs: Long): List<PauseWindow> {
        val cutoff = nowMs - PAUSE_WINDOW_RETENTION_DAYS * MS_PER_DAY
        val open = windows.filter { it.endMs == null }
        val closed = windows
            .filter { it.endMs != null && it.endMs >= cutoff }
            .takeLast(MAX_CLOSED_PAUSE_WINDOWS)
        return closed + open
    }

    private fun encodePauseWindows(windows: List<PauseWindow>): String = buildJsonArray {
        for (window in windows) {
            add(
                buildJsonObject {
                    put("startMs", window.startMs)
                    window.endMs?.let { put("endMs", it) }
                },
            )
        }
    }.toString()

    private fun readCursorLocked(conn: androidx.sqlite.SQLiteConnection, accountKey: String): MemorySyncCursor =
        MemorySyncCursor(
            accountKey = accountKey,
            lastPulledServerSeq = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_PULLED_SEQ)?.toLongOrNull() ?: 0L,
            lastPushedLocalSeq = MemoryStorage.readSyncState(conn, SYNC_KEY_LAST_PUSHED_SEQ)?.toLongOrNull() ?: 0L,
            initialPushDone = MemoryStorage.readSyncState(conn, SYNC_KEY_INITIAL_PUSH_DONE) == "1",
            baselineStage = MemoryStorage.readSyncState(conn, SYNC_KEY_BASELINE_STAGE) ?: STAGE_ENTITY,
            baselineCursorId = MemoryStorage.readSyncState(conn, SYNC_KEY_BASELINE_CURSOR_ID).orEmpty(),
            remoteLastDreamAt = MemoryStorage.readSyncState(conn, SYNC_KEY_REMOTE_LAST_DREAM_AT)?.toLongOrNull(),
            postMergeConsolidated = MemoryStorage.readSyncState(conn, SYNC_KEY_POST_MERGE_CONSOLIDATED) == "1",
        )

    private fun updateRemoteLastDreamLocked(conn: androidx.sqlite.SQLiteConnection, remoteLastDreamAt: Long) {
        if (remoteLastDreamAt <= 0L) return
        val existing = MemoryStorage.readSyncState(conn, SYNC_KEY_REMOTE_LAST_DREAM_AT)?.toLongOrNull() ?: 0L
        if (remoteLastDreamAt > existing) {
            MemoryStorage.writeSyncState(conn, SYNC_KEY_REMOTE_LAST_DREAM_AT, remoteLastDreamAt.toString())
        }
    }

    private fun isDuplicateInStore(conn: androidx.sqlite.SQLiteConnection, draft: MemoryDraft): Boolean {
        val candidates = MemoryStorage.queryRecords(
            conn,
            MemoryFilter(kinds = setOf(draft.kind), includeHistory = false, limit = 60),
        )
        return candidates.any { existing ->
            MemoryTokenizer.bigramJaccard(existing.content, draft.content) > INGEST_DUPLICATE_THRESHOLD
        }
    }

    private fun rebuildSnapshotLocked(reason: String): String {
        val nowMs = clock()
        return db.writeTransaction { conn -> rebuildSnapshotInConn(conn, reason, nowMs) }
    }

    private fun rebuildSnapshotInConn(
        conn: androidx.sqlite.SQLiteConnection,
        reason: String,
        nowMs: Long,
    ): String {
        val identity = MemoryStorage.queryRecords(
            conn,
            MemoryFilter(kinds = setOf(MemoryKind.IDENTITY), includeHistory = false, limit = 120),
        )
        val habit = MemoryStorage.queryRecords(
            conn,
            MemoryFilter(kinds = setOf(MemoryKind.HABIT), includeHistory = false, limit = 100),
        )
        val active = MemoryStorage.queryRecords(
            conn,
            MemoryFilter(kinds = setOf(MemoryKind.ACTIVE), includeHistory = false, limit = 120),
        )
        val episodic = MemoryStorage.queryRecords(
            conn,
            MemoryFilter(
                kinds = setOf(MemoryKind.EPISODIC),
                sinceEpochMs = nowMs - 14L * MS_PER_DAY,
                includeHistory = false,
                limit = 80,
            ),
        )
        val combined = identity + habit + active + episodic
        val entities = MemoryStorage.listActiveEntities(conn)
        val rendered = MemorySnapshotRenderer.render(combined, entities, nowMs)
        val revision = MemoryStorage.currentMaxRevision(conn)
        MemoryStorage.saveSnapshot(conn, rendered, revision, nowMs, reason)
        return rendered
    }

    private fun notifyChanged() {
        mutableChanges.update { it + 1L }
    }

    private companion object {
        private const val TAG = "SuperPIMemory"
        private const val MS_PER_DAY = 86_400_000L
        private const val INGEST_DUPLICATE_THRESHOLD = 0.85
        private const val SYNC_KEY_ACCOUNT = "account_key"
        private const val SYNC_KEY_LAST_PULLED_SEQ = "last_pulled_server_seq"
        private const val SYNC_KEY_LAST_PUSHED_SEQ = "last_pushed_local_seq"
        private const val SYNC_KEY_INITIAL_PUSH_DONE = "initial_push_done"
        private const val SYNC_KEY_BASELINE_STAGE = "baseline_stage"
        private const val SYNC_KEY_BASELINE_CURSOR_ID = "baseline_cursor_id"
        private const val SYNC_KEY_BASELINE_CUTOFF_SEQ = "baseline_cutoff_seq"
        private const val SYNC_KEY_MAX_REMOTE_TS = "max_seen_timestamp"
        private const val SYNC_KEY_REMOTE_LAST_DREAM_AT = "remote_last_dream_at"
        private const val SYNC_KEY_HAD_LOCAL_PRE_MERGE = "had_local_pre_merge"
        private const val SYNC_KEY_POST_MERGE_CONSOLIDATED = "post_merge_consolidated"
        private const val SYNC_KEY_LAST_ACCOUNT_IDENTITY = "last_account_identity"
        private const val SYNC_KEY_PAUSE_WINDOWS = "pause_windows"
        private const val MAX_CLOSED_PAUSE_WINDOWS = 50
        private const val PAUSE_WINDOW_RETENTION_DAYS = 90L
        private const val STAGE_ENTITY = "ENTITY"
        private const val STAGE_MEMORY = "MEMORY"
        private const val STAGE_DONE = "DONE"
    }
}
