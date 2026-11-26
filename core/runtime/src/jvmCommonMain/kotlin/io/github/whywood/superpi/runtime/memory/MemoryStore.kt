package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryAgentRunSummary
import io.github.whywood.superpi.runtime.MemoryAuditEntry
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockProposal
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityDetail
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryExportLabels
import io.github.whywood.superpi.runtime.MemoryInjectionBudget
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryOverview
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemorySearchHit
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemoryTagCount
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import java.io.File
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MemoryStore(
    val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val vectorChannel: MemoryVectorChannel = MemoryVectorChannel.NOOP,
    private val injectionBudgetProvider: suspend () -> MemoryInjectionBudget = { MemoryInjectionBudget() },
    private val deviceIdProvider: () -> String? = { null },
) {
    private val database = MemoryDatabase(rootDir, clock)
    private val mutex = Mutex()
    private val captured = ConcurrentHashMap<String, CapturedTranscript>()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    suspend fun ensure() = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.ensure()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.close()
        }
    }

    suspend fun section(scope: MemoryAccessScope = MemoryAccessScope.PERSONAL): String =
        withContext(Dispatchers.IO) {
            if (scope == MemoryAccessScope.OFF) return@withContext ""
            mutex.withLock {
                val nowMs = clock()
                val cached = database.read { conn -> MemoryStorage.readCachedContext(conn, scope) }
                if (cached != null) return@withLock cached.renderedText
                val budget = injectionBudgetProvider()
                database.writeTransaction { conn ->
                    val snapshot = MemoryStorage.loadSnapshotData(conn, nowMs)
                    val rendered = MemoryContextRenderer.render(snapshot, scope, budget)
                    MemoryStorage.writeCachedContext(
                        conn,
                        CachedContextSection(
                            scopeWire = scope.wire,
                            renderedText = rendered,
                            charCount = rendered.length,
                            revision = _changes.value + 1L,
                            generatedAt = nowMs,
                            reason = "render",
                        ),
                    )
                    rendered
                }
            }
        }

    suspend fun invalidateContextCache() = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.invalidateContextCache(conn)
            }
        }
    }

    suspend fun listBlocks(includeArchived: Boolean = false): List<MemoryBlock> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryBlockStorage.listBlocks(conn, includeArchived) }
            }
        }

    suspend fun getBlockBySlug(slug: String): MemoryBlock? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryBlockStorage.getBlockBySlug(conn, slug) }
            }
        }

    suspend fun getBlockById(id: String): MemoryBlock? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryBlockStorage.getBlockById(conn, id) }
            }
        }

    suspend fun createBlock(
        draft: MemoryBlockDraft,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
    ): MemoryBlock = withContext(Dispatchers.IO) {
        val created = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryBlockStorage.createBlock(conn, draft, actor, clock(), deviceId = deviceIdProvider())
            }
        }
        notifyChanged()
        created
    }

    suspend fun updateBlock(
        id: String,
        title: String? = null,
        purpose: String? = null,
        schemaHint: String? = null,
        content: String? = null,
        domain: String? = null,
        sensitivity: MemorySensitivity? = null,
        injectionMode: MemoryBlockInjectionMode? = null,
        pinned: Boolean? = null,
        status: MemoryBlockStatus? = null,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
        runId: String? = null,
    ): MemoryBlock? = withContext(Dispatchers.IO) {
        val (updated, guard) = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryBlockStorage.updateBlock(
                    conn = conn,
                    id = id,
                    title = title,
                    purpose = purpose,
                    schemaHint = schemaHint,
                    content = content,
                    domain = domain,
                    sensitivity = sensitivity,
                    injectionMode = injectionMode,
                    pinned = pinned,
                    status = status,
                    actor = actor,
                    runId = runId,
                    nowMs = clock(),
                    deviceId = deviceIdProvider(),
                )
            }
        }
        if (guard == GuardDecision.Allowed && updated != null) {
            notifyChanged()
        }
        updated
    }

    suspend fun deleteCustomBlock(
        id: String,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
    ): Boolean = withContext(Dispatchers.IO) {
        val deleted = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryBlockStorage.deleteCustomBlock(conn, id, actor, clock(), deviceId = deviceIdProvider())
            }
        }
        if (deleted) notifyChanged()
        deleted
    }

    suspend fun listProposals(status: MemoryProposalStatus? = MemoryProposalStatus.PENDING): List<MemoryBlockProposal> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryBlockStorage.listProposals(conn, status) }
            }
        }

    suspend fun createProposal(
        slug: String,
        title: String,
        purpose: String,
        domain: String,
        sensitivity: MemorySensitivity,
        seedContent: String,
        supportingEventIds: List<String>,
        reason: String,
    ): MemoryBlockProposal? = withContext(Dispatchers.IO) {
        val created = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryBlockStorage.createProposal(
                    conn = conn,
                    slug = slug,
                    title = title,
                    purpose = purpose,
                    domain = domain,
                    sensitivity = sensitivity,
                    seedContent = seedContent,
                    supportingEventIds = supportingEventIds,
                    reason = reason,
                    nowMs = clock(),
                    deviceId = deviceIdProvider(),
                )
            }
        }
        if (created != null) notifyChanged()
        created
    }

    suspend fun acceptProposal(proposalId: String): MemoryBlock? = withContext(Dispatchers.IO) {
        val created = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryBlockStorage.acceptProposal(conn, proposalId, clock(), deviceId = deviceIdProvider())
            }
        }
        if (created != null) notifyChanged()
        created
    }

    suspend fun dismissProposal(proposalId: String): Boolean = withContext(Dispatchers.IO) {
        val dismissed = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryBlockStorage.dismissProposal(conn, proposalId, clock(), deviceId = deviceIdProvider())
            }
        }
        if (dismissed) notifyChanged()
        dismissed
    }

    suspend fun listEntities(
        entityType: String? = null,
        scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryGraphStorage.listEntities(conn, entityType, scope) }
        }
    }

    suspend fun listRelations(
        scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
        entityId: String? = null,
        includeInvalidated: Boolean = false,
    ): List<MemoryEntityRelation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryGraphStorage.listActiveRelations(conn, scope, entityId, includeInvalidated) }
        }
    }

    suspend fun upsertEntity(
        ref: MemoryEntityRef,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
    ): MemoryEntity = withContext(Dispatchers.IO) {
        val entity = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryGraphStorage.upsertEntity(conn, ref, actor, clock(), deviceId = deviceIdProvider()).first
            }
        }
        notifyChanged()
        entity
    }

    suspend fun updateEntity(
        id: String,
        canonicalName: String? = null,
        entityType: String? = null,
        aliases: List<String>? = null,
        attributes: Map<String, String>? = null,
        summary: String? = null,
        clearSummary: Boolean = false,
        domain: String? = null,
        sensitivity: MemorySensitivity? = null,
        pinned: Boolean? = null,
    ): MemoryEntity? = withContext(Dispatchers.IO) {
        val updated = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryGraphStorage.updateEntityDirect(
                    conn = conn,
                    id = id,
                    canonicalName = canonicalName,
                    entityType = entityType,
                    aliases = aliases,
                    attributes = attributes,
                    summary = summary,
                    clearSummary = clearSummary,
                    domain = domain,
                    sensitivity = sensitivity,
                    pinned = pinned,
                    nowMs = clock(),
                    deviceId = deviceIdProvider(),
                )
            }
        }
        if (updated != null) notifyChanged()
        updated
    }

    suspend fun invalidateRelation(
        relationId: String,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
    ): Boolean = withContext(Dispatchers.IO) {
        val guard = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryGraphStorage.invalidateRelation(
                    conn = conn,
                    op = InvalidateRelationOp(
                        relationId = relationId,
                        actingTier = MemoryEpistemicTier.USER_EXPLICIT,
                        reason = null,
                        userSummary = "移除了这条关系连线",
                    ),
                    actor = actor,
                    nowMs = clock(),
                    deviceId = deviceIdProvider(),
                )
            }
        }
        val ok = guard == GuardDecision.Allowed
        if (ok) notifyChanged()
        ok
    }

    suspend fun upsertRelation(
        fromEntityName: String,
        relationType: String,
        toEntityName: String,
        label: String? = null,
        attributes: Map<String, String> = emptyMap(),
        epistemicTier: MemoryEpistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
        domain: String = MemoryDomains.PERSONAL,
        sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    ): MemoryEntityRelation? = withContext(Dispatchers.IO) {
        val rel = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryGraphStorage.upsertRelation(
                    conn = conn,
                    op = UpsertRelationOp(
                        fromEntityName = fromEntityName,
                        relationType = relationType,
                        toEntityName = toEntityName,
                        label = label,
                        attributes = attributes,
                        epistemicTier = epistemicTier,
                        domain = domain,
                        sensitivity = sensitivity,
                        userSummary = "更新了「$fromEntityName」与「$toEntityName」的关系",
                    ),
                    actor = MemoryMutationActor.USER,
                    nowMs = clock(),
                    deviceId = deviceIdProvider(),
                ).first
            }
        }
        if (rel != null) notifyChanged()
        rel
    }

    suspend fun mergeEntities(
        keepId: String,
        mergeIds: List<String>,
        mergedSummary: String? = null,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
        runId: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        val merged = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryGraphStorage.mergeEntities(
                    conn = conn,
                    keepId = keepId,
                    mergeIds = mergeIds,
                    mergedSummary = mergedSummary,
                    actor = actor,
                    nowMs = clock(),
                    runId = runId,
                    deviceId = deviceIdProvider(),
                )
            }
        }
        if (merged > 0) notifyChanged()
        merged
    }

    suspend fun inspectEntity(
        nameOrId: String,
        scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
        includeHistory: Boolean = false,
    ): MemoryEntityDetail? = withContext(Dispatchers.IO) {
        if (scope == MemoryAccessScope.OFF) return@withContext null
        mutex.withLock {
            database.read { conn ->
                val entity = MemoryGraphStorage.findEntityById(conn, nameOrId.trim())
                    ?: MemoryGraphStorage.findEntityByNameOrAlias(conn, nameOrId)
                    ?: return@read null
                if (!scope.allows(entity.domain, entity.sensitivity)) return@read null
                val relations = MemoryGraphStorage.listActiveRelations(
                    conn = conn,
                    scope = scope,
                    entityId = entity.id,
                    includeInvalidated = includeHistory,
                )
                val outgoing = relations.filter { it.fromEntityId == entity.id }
                val incoming = relations.filter { it.toEntityId == entity.id }
                val events = MemoryStorage.listEventsForEntity(conn, entity.id, scope, includeHistory)
                MemoryEntityDetail(
                    entity = entity,
                    outgoingRelations = outgoing,
                    incomingRelations = incoming,
                    linkedEvents = events,
                )
            }
        }
    }

    suspend fun saveEvent(
        draft: MemoryEventDraft,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
        userSummary: String? = "新增了一条记忆",
    ): MemoryEvent = withContext(Dispatchers.IO) {
        val saved = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.insertEvent(conn, draft, clock(), actor, null, userSummary, deviceId = deviceIdProvider())
            }
        }
        notifyChanged()
        saved
    }

    suspend fun updateEvent(
        id: String,
        eventKind: MemoryEventKind? = null,
        content: String? = null,
        summary: String? = null,
        domain: String? = null,
        sensitivity: MemorySensitivity? = null,
        tags: List<String>? = null,
        epistemicTier: MemoryEpistemicTier? = null,
        pinned: Boolean? = null,
        eventTime: Long? = null,
        validUntil: Long? = null,
        clearValidUntil: Boolean = false,
        status: MemoryItemStatus? = null,
    ): MemoryEvent? = withContext(Dispatchers.IO) {
        val updated = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.updateEventDirect(
                    conn = conn,
                    id = id,
                    eventKind = eventKind,
                    content = content,
                    summary = summary,
                    domain = domain,
                    sensitivity = sensitivity,
                    tags = tags,
                    epistemicTier = epistemicTier,
                    pinned = pinned,
                    eventTime = eventTime,
                    validUntil = validUntil,
                    clearValidUntil = clearValidUntil,
                    status = status,
                    nowMs = clock(),
                    deviceId = deviceIdProvider(),
                )
            }
        }
        if (updated != null) notifyChanged()
        updated
    }

    suspend fun deleteEvent(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.softDeleteEvent(conn, id, MemoryMutationActor.USER, null, clock(), deviceId = deviceIdProvider())
            }
        }
        if (deleted) notifyChanged()
        deleted
    }

    suspend fun countEventsBySession(sessionId: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.countEventsBySession(conn, sessionId) }
        }
    }

    suspend fun restoreEvent(id: String): Boolean = withContext(Dispatchers.IO) {
        val restored = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.restoreEvent(conn, id, clock(), deviceId = deviceIdProvider())
            }
        }
        if (restored) notifyChanged()
        restored
    }

    suspend fun deleteEventsBySession(sessionId: String): Int = withContext(Dispatchers.IO) {
        val count = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.deleteEventsBySession(conn, sessionId, clock(), deviceId = deviceIdProvider())
            }
        }
        if (count > 0) notifyChanged()
        count
    }

    suspend fun getEvent(id: String): MemoryEvent? = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.findEventById(conn, id) }
        }
    }

    suspend fun listEvents(filter: MemoryEventFilter = MemoryEventFilter()): List<MemoryEvent> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryStorage.listEvents(conn, filter) }
            }
        }

    suspend fun search(
        queries: List<String>,
        filter: MemoryEventFilter = MemoryEventFilter(),
        recordAccess: Boolean = false,
    ): List<MemorySearchHit> = withContext(Dispatchers.IO) {
        if (filter.scope == MemoryAccessScope.OFF) return@withContext emptyList()
        mutex.withLock {
            val nowMs = clock()
            LocalGraphRetriever.search(
                database = database,
                queries = queries,
                filter = filter,
                nowMs = nowMs,
                recordAccess = recordAccess,
                vectorChannel = vectorChannel,
            )
        }
    }

    suspend fun listTags(
        scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
        limit: Int = 20,
    ): List<MemoryTagCount> = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.listTags(conn, scope, limit) }
        }
    }

    suspend fun readArchive(
        slug: String? = null,
        month: String? = null,
        scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
        limit: Int = 30,
    ): String = withContext(Dispatchers.IO) {
        if (scope == MemoryAccessScope.OFF) return@withContext ""
        mutex.withLock {
            database.read { conn ->
                val cleanSlug = slug?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanSlug != null) {
                    val block = MemoryBlockStorage.getBlockBySlug(conn, cleanSlug)
                        ?: return@read "No archived profile block found for slug '$cleanSlug'."
                    if (!scope.allows(block.domain, block.sensitivity)) {
                        return@read "Access denied for profile '$cleanSlug' in current memory scope."
                    }
                    return@read "## ${block.title} (${block.status.wire} · ${block.domain})\n${block.content.ifBlank { "(empty)" }}"
                }

                val cleanMonth = month?.trim()?.takeIf { it.isNotEmpty() }
                if (cleanMonth != null) {
                    val ym = runCatching { YearMonth.parse(cleanMonth) }.getOrNull()
                        ?: return@read "Invalid month format '$cleanMonth'; expected yyyy-MM."
                    val zone = ZoneId.systemDefault()
                    val startMs = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val endMs = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
                    val events = MemoryStorage.listEvents(
                        conn,
                        MemoryEventFilter(
                            sinceEventTimeMs = startMs,
                            untilEventTimeMs = endMs,
                            includeHistory = true,
                            scope = scope,
                            limit = limit.coerceIn(1, 50),
                        ),
                    )
                    if (events.isEmpty()) return@read "No events recorded for $cleanMonth."
                    return@read events.joinToString("\n") { event ->
                        "- [id=${event.id} ${event.eventKind.wire} ${event.status.wire}] ${event.summary ?: event.content}"
                    }
                }

                val archived = MemoryBlockStorage.listBlocks(conn, includeArchived = true)
                    .filter { it.status == MemoryBlockStatus.ARCHIVED && scope.allows(it.domain, it.sensitivity) }
                if (archived.isEmpty()) {
                    "No archived profile blocks. Specify a yyyy-MM month to browse historical timeline events."
                } else {
                    archived.joinToString("\n") { "- [slug=${it.slug}] ${it.title} (${it.domain})" }
                }
            }
        }
    }

    suspend fun listAuditLog(limit: Int = 50): List<MemoryAuditEntry> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryAuditSupport.listAuditLog(conn, limit) }
            }
        }

    suspend fun revertAuditEntry(auditId: String): Boolean = withContext(Dispatchers.IO) {
        val reverted = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryAuditSupport.revertEntry(conn, auditId, clock(), deviceId = deviceIdProvider())
            }
        }
        if (reverted) notifyChanged()
        reverted
    }

    suspend fun overview(): MemoryOverview = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.overview(conn) }
        }
    }

    suspend fun exportMarkdown(labels: MemoryExportLabels = MemoryExportLabels.ENGLISH): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val nowMs = clock()
                val snapshot = database.read { conn -> MemoryStorage.loadSnapshotData(conn, nowMs) }
                MemoryContextRenderer.exportMarkdown(snapshot, labels, nowMs)
            }
        }

    internal suspend fun applyAgentCommitBatch(
        runId: String,
        actor: MemoryMutationActor,
        batch: MemoryAgentCommitBatch,
    ): CommitBatchReceipt = withContext(Dispatchers.IO) {
        val receipt = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.applyAgentCommitBatch(conn, runId, actor, batch, clock(), deviceId = deviceIdProvider())
            }
        }
        if (receipt.blocksPatched > 0 || receipt.eventsRecorded > 0 ||
            receipt.eventsSuperseded > 0 || receipt.eventsForgotten > 0 ||
            receipt.entitiesUpserted > 0 || receipt.relationsUpserted > 0 ||
            receipt.relationsInvalidated > 0
        ) {
            notifyChanged()
        }
        receipt
    }

    internal suspend fun runDeterministicCleanup(staleInferredDays: Long = 45L): Triple<Int, Int, Int> =
        withContext(Dispatchers.IO) {
            val result = mutex.withLock {
                database.writeTransaction { conn ->
                    MemoryStorage.runDeterministicCleanup(conn, clock(), staleInferredDays, deviceId = deviceIdProvider())
                }
            }
            if (result.first > 0 || result.second > 0) notifyChanged()
            result
        }

    internal suspend fun enqueueDispatchJob(
        jobKind: DispatchJobKind,
        sessionId: String?,
        payloadJson: String,
        transcriptHash: String? = null,
        replaceSameSessionKind: Boolean = false,
    ): DispatchQueueJob = withContext(Dispatchers.IO) {
        val job = mutex.withLock {
            database.writeTransaction { conn ->
                MemoryStorage.enqueueDispatchJob(
                    conn = conn,
                    jobKind = jobKind,
                    sessionId = sessionId,
                    payloadJson = payloadJson,
                    transcriptHash = transcriptHash,
                    nowMs = clock(),
                    replaceSameSessionKind = replaceSameSessionKind,
                )
            }
        }
        notifyChanged()
        job
    }

    internal suspend fun dequeueNextJob(maxAttempts: Int = 5): DispatchQueueJob? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryStorage.dequeueNextJob(conn, maxAttempts, clock()) }
            }
        }

    internal suspend fun earliestNextRetryAt(maxAttempts: Int = 5): Long? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryStorage.earliestNextRetryAt(conn, maxAttempts) }
            }
        }

    internal suspend fun resetRetryBackoffNow(): Int = withContext(Dispatchers.IO) {
        val count = mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.resetRetryBackoffNow(conn, clock()) }
        }
        notifyChanged()
        count
    }

    internal suspend fun countActiveEventsByIds(ids: List<String>): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryStorage.countActiveEventsByIds(conn, ids) }
            }
        }

    internal suspend fun completeDispatchJob(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.completeDispatchJob(conn, id) }
        }
        notifyChanged()
    }

    internal suspend fun failDispatchJob(id: String, error: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.failDispatchJob(conn, id, error, clock()) }
        }
    }

    internal suspend fun deleteSessionJobs(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.deleteSessionJobs(conn, sessionId) }
        }
        notifyChanged()
    }

    internal suspend fun pruneExhaustedJobs(maxAttempts: Int = 5) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.pruneExhaustedJobs(conn, maxAttempts) }
        }
        notifyChanged()
    }

    internal suspend fun hasPendingHighPriorityJob(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.hasPendingHighPriorityJob(conn, clock()) }
        }
    }

    internal suspend fun deleteWatermark(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.deleteWatermark(conn, sessionId) }
        }
    }

    internal suspend fun readWatermark(sessionId: String): IngestionWatermark? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.read { conn -> MemoryStorage.readWatermark(conn, sessionId) }
            }
        }

    internal suspend fun saveWatermark(watermark: IngestionWatermark) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.saveWatermark(conn, watermark) }
        }
    }

    internal suspend fun readPauseWindows(): List<PauseWindow> = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.readPauseWindows(conn) }
        }
    }

    internal suspend fun openPauseWindow() = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.openPauseWindow(conn, clock()) }
        }
    }

    internal suspend fun closePauseWindow(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.closePauseWindow(conn, clock()) }
        }
    }

    internal suspend fun recordAgentRun(summary: MemoryAgentRunSummary) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn -> MemoryStorage.recordAgentRun(conn, summary) }
        }
    }

    internal suspend fun latestSuccessfulColdRunAt(): Long? = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.latestSuccessfulColdRunAt(conn) }
        }
    }

    internal suspend fun countNewEventsSince(sinceEpochMs: Long): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.countNewEventsSince(conn, sinceEpochMs) }
        }
    }

    internal suspend fun loadSnapshotForAgent(): ContextSnapshotData = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemoryStorage.loadSnapshotData(conn, clock()) }
        }
    }

    fun capture(sessionId: String, sessionFile: String?, entries: List<SessionEntry>?) {
        if (sessionId.isBlank() || entries.isNullOrEmpty()) return
        captured[sessionId] = CapturedTranscript(sessionId, sessionFile, entries.toList())
    }

    internal fun takeCaptured(sessionId: String): CapturedTranscript? = captured.remove(sessionId)

    suspend fun readPullCursor(accountKey: String): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemorySyncStorage.readPullCursor(conn, accountKey) }
        }
    }

    suspend fun remoteLastConsolidationAt(accountKey: String? = null): Long? = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.read { conn -> MemorySyncStorage.remoteLastConsolidationAt(conn, accountKey) }
        }
    }

    suspend fun recordRemoteConsolidationAt(accountKey: String, timestampMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn ->
                MemorySyncStorage.updateRemoteLastConsolidationAt(conn, accountKey, timestampMs)
            }
        }
    }

    suspend fun clearSyncStateForAccount(accountKey: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn ->
                MemorySyncStorage.clearSyncStateForAccount(conn, accountKey)
            }
        }
    }

    suspend fun inspectAccountSwitch(accountIdentity: String): MemoryAccountSwitchCheck = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn ->
                MemorySyncStorage.inspectAccountSwitch(conn, accountIdentity)
            }
        }
    }

    suspend fun resolveAccountSwitch(
        accountKey: String,
        accountIdentity: String,
        mode: MemoryAccountSwitchMode,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.writeTransaction { conn ->
                    MemorySyncStorage.resolveAccountSwitch(conn, accountKey, accountIdentity, mode, clock())
                }
            }
        }
        notifyChanged()
    }

    suspend fun collectPendingPushBatch(
        accountKey: String,
        deviceId: String,
        limit: Int = 100,
    ): List<MemorySyncOpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn ->
                MemorySyncStorage.collectPendingPushBatch(conn, accountKey, deviceId, limit, clock())
            }
        }
    }

    suspend fun markPushAcked(
        accountKey: String,
        acked: List<MemorySyncOpItem>,
        remoteLastConsolidationAt: Long?,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn ->
                MemorySyncStorage.markPushAcked(conn, accountKey, acked, remoteLastConsolidationAt)
            }
        }
    }

    suspend fun applyRemotePullBatch(
        accountKey: String,
        selfDeviceId: String,
        entries: List<MemoryRemotePullEntry>,
        nextSince: Long,
        remoteLastConsolidationAt: Long?,
        advanceCursor: Boolean = true,
    ): MemoryPullApplyResult {
        val result = withContext(Dispatchers.IO) {
            mutex.withLock {
                database.writeTransaction { conn ->
                    MemorySyncStorage.applyRemotePullBatch(
                        conn = conn,
                        accountKey = accountKey,
                        selfDeviceId = selfDeviceId,
                        entries = entries,
                        nextSince = nextSince,
                        remoteLastConsolidationAt = remoteLastConsolidationAt,
                        nowMs = clock(),
                        advanceCursor = advanceCursor,
                    )
                }
            }
        }
        if (result.appliedCount > 0) {
            notifyChanged()
        }
        return result
    }

    suspend fun commitPullCursor(
        accountKey: String,
        nextSince: Long,
        remoteLastConsolidationAt: Long? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.writeTransaction { conn ->
                MemorySyncStorage.commitPullCursor(
                    conn = conn,
                    accountKey = accountKey,
                    nextSince = nextSince,
                    remoteLastConsolidationAt = remoteLastConsolidationAt,
                )
            }
        }
    }

    private fun notifyChanged() {
        _changes.value += 1L
    }
}
