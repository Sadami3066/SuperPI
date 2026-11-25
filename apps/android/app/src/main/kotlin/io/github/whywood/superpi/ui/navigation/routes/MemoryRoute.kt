package io.github.whywood.superpi.ui.navigation.routes

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryAuditEntry
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockProposal
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryExportLabels
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.MemoryTagCount
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.memory.ConsolidationOutcome
import io.github.whywood.superpi.ui.chat.ChatViewModel
import io.github.whywood.superpi.ui.common.shareChooser
import io.github.whywood.superpi.ui.memory.BlockEditorPayload
import io.github.whywood.superpi.ui.memory.BlockVisibility
import io.github.whywood.superpi.ui.memory.EntityEditorPayload
import io.github.whywood.superpi.ui.memory.EventEditorPayload
import io.github.whywood.superpi.ui.memory.MemoryScreen
import io.github.whywood.superpi.ui.memory.MemoryScreenState
import io.github.whywood.superpi.ui.memory.MemoryStatusNotice
import io.github.whywood.superpi.ui.memory.MemoryTab
import io.github.whywood.superpi.ui.memory.RelationDraftPayload
import io.github.whywood.superpi.ui.memory.RelationFilter
import io.github.whywood.superpi.ui.navigation.AppDestination
import io.github.whywood.superpi.ui.navigation.AppNavigator
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MS = 180L
private const val UNDO_WINDOW_MS = 5_000L
private const val PAGE_SIZE = 100

private data class MemoryRouteSnapshot(
    val activeBlocks: List<MemoryBlock>,
    val archivedBlocks: List<MemoryBlock>,
    val proposals: List<MemoryBlockProposal>,
    val entities: List<MemoryEntity>,
    val relations: List<MemoryEntityRelation>,
    val rawEvents: List<MemoryEvent>,
    val tags: List<MemoryTagCount>,
)

@Composable
internal fun MemoryRoute(
    dependencies: AppDependencies,
    chatViewModel: ChatViewModel,
    navigator: AppNavigator,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    val storeRevision by dependencies.memory.changes.collectAsStateWithLifecycle()
    val manualState by dependencies.memoryAgent.manualState.collectAsStateWithLifecycle()
    val organizing = manualState.running

    var activeTabName by rememberSaveable { mutableStateOf(MemoryTab.PROFILE.name) }
    val activeTab = MemoryTab.entries.firstOrNull { it.name == activeTabName } ?: MemoryTab.PROFILE
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var includeHistory by rememberSaveable { mutableStateOf(false) }
    var relationFilterName by rememberSaveable { mutableStateOf(RelationFilter.ALL.name) }
    val relationFilter = RelationFilter.entries.firstOrNull { it.name == relationFilterName } ?: RelationFilter.ALL
    var visibleLimit by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    var hasMoreEvents by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }

    var blocks by remember { mutableStateOf<List<MemoryBlock>>(emptyList()) }
    var archivedBlocks by remember { mutableStateOf<List<MemoryBlock>>(emptyList()) }
    var proposals by remember { mutableStateOf<List<MemoryBlockProposal>>(emptyList()) }
    var entities by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    var relations by remember { mutableStateOf<List<MemoryEntityRelation>>(emptyList()) }
    var events by remember { mutableStateOf<List<MemoryEvent>>(emptyList()) }
    var tags by remember { mutableStateOf<List<MemoryTagCount>>(emptyList()) }

    var auditEntries by remember { mutableStateOf<List<MemoryAuditEntry>>(emptyList()) }
    var auditLoading by remember { mutableStateOf(false) }
    var auditOpen by rememberSaveable { mutableStateOf(false) }
    var auditReloadNonce by remember { mutableLongStateOf(0L) }
    var revertingAuditIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busyProposalIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var statusNotice by remember { mutableStateOf<MemoryStatusNotice?>(null) }
    var undoDeleteId by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableLongStateOf(0L) }
    var lastHandledOutcomeSeq by rememberSaveable { mutableLongStateOf(manualState.outcomeSeq) }

    LaunchedEffect(query, selectedTag, includeHistory, visibleLimit, storeRevision, reloadNonce) {
        val currentQuery = query.trim()
        if (currentQuery.isNotEmpty()) {
            delay(SEARCH_DEBOUNCE_MS)
        }
        val filter = MemoryEventFilter(
            tag = selectedTag,
            includeHistory = includeHistory,
            scope = MemoryAccessScope.PERSONAL,
            limit = visibleLimit + 1,
        )
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val allBlocks = dependencies.memory.listBlocks(includeArchived = true)
                val active = allBlocks.filter { it.status == MemoryBlockStatus.ACTIVE }
                val archived = allBlocks.filter { it.status != MemoryBlockStatus.ACTIVE }
                val pending = dependencies.memory.listProposals(MemoryProposalStatus.PENDING)
                val loadedEntities = dependencies.memory.listEntities(scope = MemoryAccessScope.PERSONAL)
                val loadedRelations = dependencies.memory.listRelations(scope = MemoryAccessScope.PERSONAL)
                val rawEvents = if (currentQuery.isNotEmpty()) {
                    dependencies.memory.search(listOf(currentQuery), filter, recordAccess = false).map { it.event }
                } else {
                    dependencies.memory.listEvents(filter)
                }
                val activeTags = dependencies.memory.listTags(scope = MemoryAccessScope.PERSONAL)
                MemoryRouteSnapshot(
                    activeBlocks = active,
                    archivedBlocks = archived,
                    proposals = pending,
                    entities = loadedEntities,
                    relations = loadedRelations,
                    rawEvents = rawEvents,
                    tags = activeTags,
                )
            }.getOrNull()
        }
        if (loaded != null) {
            blocks = loaded.activeBlocks
            archivedBlocks = loaded.archivedBlocks
            proposals = loaded.proposals
            entities = loaded.entities
            relations = loaded.relations
            hasMoreEvents = loaded.rawEvents.size > visibleLimit
            events = loaded.rawEvents.take(visibleLimit)
            tags = loaded.tags
            val activeTag = selectedTag
            if (activeTag != null && loaded.tags.none { it.tag == activeTag } && loaded.rawEvents.isEmpty()) {
                selectedTag = null
            }
            loadError = false
        } else {
            loadError = true
        }
        loading = false
    }

    LaunchedEffect(auditOpen, auditReloadNonce, storeRevision) {
        if (!auditOpen) return@LaunchedEffect
        auditLoading = true
        val loadedAudit = withContext(Dispatchers.IO) {
            runCatching { dependencies.memory.listAuditLog(limit = 60) }.getOrNull()
        }
        if (loadedAudit != null) {
            auditEntries = loadedAudit
        }
        auditLoading = false
    }

    LaunchedEffect(undoDeleteId) {
        if (undoDeleteId == null) return@LaunchedEffect
        delay(UNDO_WINDOW_MS)
        undoDeleteId = null
    }

    LaunchedEffect(statusNotice, organizing) {
        if (statusNotice == null || organizing) return@LaunchedEffect
        delay(4_000L)
        statusNotice = null
    }

    LaunchedEffect(manualState.running, manualState.outcomeSeq) {
        if (manualState.running) {
            undoDeleteId = null
            statusNotice = MemoryStatusNotice(resources.getString(R.string.memory_organizing), DdNoticeTone.INFO)
            return@LaunchedEffect
        }
        if (manualState.outcomeSeq != lastHandledOutcomeSeq) {
            lastHandledOutcomeSeq = manualState.outcomeSeq
            val outcome = manualState.outcome ?: return@LaunchedEffect
            statusNotice = when (outcome) {
                is ConsolidationOutcome.Done -> {
                    val s = outcome.summary
                    val profileUpdates = s.blocksPatched
                    val eventUpdates = s.eventsRecorded + s.eventsSuperseded + s.eventsForgotten +
                        s.entitiesMerged + s.proposalsCreated
                    if (profileUpdates == 0 && eventUpdates == 0) {
                        MemoryStatusNotice(resources.getString(R.string.memory_organize_none), DdNoticeTone.INFO)
                    } else {
                        MemoryStatusNotice(
                            resources.getString(R.string.memory_organize_done_v3, profileUpdates, eventUpdates),
                            DdNoticeTone.SUCCESS,
                        )
                    }
                }
                is ConsolidationOutcome.Skipped ->
                    MemoryStatusNotice(resources.getString(R.string.memory_organize_none), DdNoticeTone.INFO)
                ConsolidationOutcome.Paused ->
                    MemoryStatusNotice(resources.getString(R.string.memory_organize_paused), DdNoticeTone.INFO)
                ConsolidationOutcome.NoModel ->
                    MemoryStatusNotice(resources.getString(R.string.memory_organize_no_model), DdNoticeTone.INFO)
                ConsolidationOutcome.Busy ->
                    MemoryStatusNotice(resources.getString(R.string.memory_organizing), DdNoticeTone.INFO)
                is ConsolidationOutcome.Failed ->
                    MemoryStatusNotice(resources.getString(R.string.memory_organize_failed), DdNoticeTone.DANGER)
            }
            reloadNonce += 1L
        }
    }

    fun acceptProposal(id: String) {
        if (id in busyProposalIds) return
        busyProposalIds = busyProposalIds + id
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { dependencies.memory.acceptProposal(id) }
                }
                val created = result.getOrNull()
                if (created != null) {
                    statusNotice = MemoryStatusNotice(
                        resources.getString(R.string.memory_proposal_accepted, created.title),
                        DdNoticeTone.SUCCESS,
                    )
                    reloadNonce += 1L
                } else if (result.isFailure) {
                    statusNotice = MemoryStatusNotice(
                        resources.getString(R.string.memory_save_failed),
                        DdNoticeTone.DANGER,
                    )
                }
            } finally {
                busyProposalIds = busyProposalIds - id
            }
        }
    }

    fun dismissProposal(id: String) {
        if (id in busyProposalIds) return
        busyProposalIds = busyProposalIds + id
        scope.launch {
            try {
                val dismissed = withContext(Dispatchers.IO) { dependencies.memory.dismissProposal(id) }
                if (dismissed) {
                    statusNotice = MemoryStatusNotice(
                        resources.getString(R.string.memory_proposal_dismissed),
                        DdNoticeTone.INFO,
                    )
                    reloadNonce += 1L
                }
            } finally {
                busyProposalIds = busyProposalIds - id
            }
        }
    }

    fun saveBlock(payload: BlockEditorPayload) {
        scope.launch {
            val nextDomain = when (payload.visibility) {
                BlockVisibility.PERSONAL_AND_WORK ->
                    if (payload.existingDomain in MemoryDomains.WORK_SCOPED) payload.existingDomain else MemoryDomains.WORK
                BlockVisibility.PERSONAL_ONLY ->
                    if (payload.existingDomain in MemoryDomains.WORK_SCOPED) MemoryDomains.PERSONAL else payload.existingDomain
            }
            val nextSensitivity = when (payload.visibility) {
                BlockVisibility.PERSONAL_AND_WORK -> MemorySensitivity.NORMAL
                BlockVisibility.PERSONAL_ONLY -> payload.existingSensitivity
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val existingId = payload.id
                    if (existingId == null) {
                        dependencies.memory.createBlock(
                            MemoryBlockDraft(
                                slug = payload.title,
                                title = payload.title,
                                purpose = payload.purpose,
                                content = payload.content,
                                domain = nextDomain,
                                sensitivity = nextSensitivity,
                                owner = MemoryBlockOwner.USER,
                                pinned = payload.pinned,
                            ),
                        )
                    } else {
                        dependencies.memory.updateBlock(
                            id = existingId,
                            title = payload.title.takeIf { it.isNotBlank() },
                            purpose = payload.purpose.takeIf { it.isNotBlank() },
                            content = payload.content,
                            domain = nextDomain,
                            sensitivity = nextSensitivity,
                            pinned = payload.pinned,
                            status = if (payload.archived) MemoryBlockStatus.ARCHIVED else MemoryBlockStatus.ACTIVE,
                        )
                    }
                }.isSuccess
            }
            if (!ok) {
                statusNotice = MemoryStatusNotice(
                    resources.getString(R.string.memory_save_failed),
                    DdNoticeTone.DANGER,
                )
            }
            reloadNonce += 1L
        }
    }

    fun deleteBlock(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { dependencies.memory.deleteCustomBlock(id) }
            reloadNonce += 1L
        }
    }

    fun saveEntity(payload: EntityEditorPayload) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                dependencies.memory.updateEntity(
                    id = payload.id,
                    canonicalName = payload.canonicalName,
                    entityType = payload.entityType,
                    aliases = payload.aliases,
                    summary = payload.summary,
                    clearSummary = payload.clearSummary,
                    pinned = payload.pinned,
                )
            }
            if (updated != null) {
                statusNotice = MemoryStatusNotice(
                    resources.getString(R.string.memory_entity_saved),
                    DdNoticeTone.SUCCESS,
                )
                reloadNonce += 1L
            } else {
                statusNotice = MemoryStatusNotice(
                    resources.getString(R.string.memory_save_failed),
                    DdNoticeTone.DANGER,
                )
            }
        }
    }

    fun mergeEntity(keepId: String, mergeId: String) {
        scope.launch {
            val merged = withContext(Dispatchers.IO) {
                dependencies.memory.mergeEntities(keepId = keepId, mergeIds = listOf(mergeId))
            }
            if (merged > 0) {
                statusNotice = MemoryStatusNotice(
                    resources.getString(R.string.memory_entity_merge_done),
                    DdNoticeTone.SUCCESS,
                )
                reloadNonce += 1L
            }
        }
    }

    fun saveEvent(payload: EventEditorPayload) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val existingId = payload.id
                if (existingId == null) {
                    dependencies.memory.saveEvent(
                        MemoryEventDraft(
                            eventKind = payload.eventKind,
                            content = payload.content,
                            tags = payload.tags,
                            epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                            importance = if (payload.pinned) 5 else 4,
                            pinned = payload.pinned,
                            sourceKind = MemorySourceKind.USER_MANUAL,
                        ),
                    )
                } else {
                    dependencies.memory.updateEvent(
                        id = existingId,
                        eventKind = payload.eventKind,
                        content = payload.content,
                        tags = payload.tags,
                        pinned = payload.pinned,
                        status = payload.status,
                    )
                }
            }
            reloadNonce += 1L
        }
    }

    fun deleteEvent(id: String) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) { dependencies.memory.deleteEvent(id) }
            if (removed) {
                undoDeleteId = id
                statusNotice = null
                reloadNonce += 1L
            }
        }
    }

    fun undoDelete() {
        val targetId = undoDeleteId ?: return
        undoDeleteId = null
        scope.launch {
            val restored = withContext(Dispatchers.IO) { dependencies.memory.restoreEvent(targetId) }
            if (restored) {
                statusNotice = MemoryStatusNotice(resources.getString(R.string.memory_restored), DdNoticeTone.SUCCESS)
                reloadNonce += 1L
            }
        }
    }

    fun purgeSession(sessionId: String) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) { dependencies.memory.deleteEventsBySession(sessionId) }
            statusNotice = MemoryStatusNotice(
                resources.getString(R.string.memory_purge_session_done, removed),
                DdNoticeTone.INFO,
            )
            reloadNonce += 1L
        }
    }

    fun revertAudit(auditId: String) {
        if (auditId in revertingAuditIds) return
        revertingAuditIds = revertingAuditIds + auditId
        scope.launch {
            try {
                val reverted = withContext(Dispatchers.IO) {
                    runCatching { dependencies.memory.revertAuditEntry(auditId) }.getOrDefault(false)
                }
                statusNotice = if (reverted) {
                    MemoryStatusNotice(resources.getString(R.string.memory_activity_revert_done), DdNoticeTone.SUCCESS)
                } else {
                    MemoryStatusNotice(resources.getString(R.string.memory_activity_revert_failed), DdNoticeTone.DANGER)
                }
                auditReloadNonce += 1L
                reloadNonce += 1L
            } finally {
                revertingAuditIds = revertingAuditIds - auditId
            }
        }
    }

    fun triggerOrganize() {
        if (organizing) return
        undoDeleteId = null
        dependencies.memoryAgent.launchManualConsolidation()
    }

    fun exportMarkdown() {
        scope.launch {
            val ok = runCatching {
                val labels = MemoryExportLabels(
                    title = resources.getString(R.string.memory_export_header),
                    exportedOn = resources.getString(R.string.memory_export_date),
                    sectionProfiles = resources.getString(R.string.memory_export_profiles),
                    sectionArchivedProfiles = resources.getString(R.string.memory_export_archived_profiles),
                    sectionRelationships = resources.getString(R.string.memory_export_entities),
                    sectionTimeline = resources.getString(R.string.memory_export_timeline),
                    sectionHistory = resources.getString(R.string.memory_export_history),
                    pinnedPrefix = "${resources.getString(R.string.memory_export_pinned)} ",
                    inferredSuffix = " (${resources.getString(R.string.memory_inferred_badge)})",
                    statusSuperseded = resources.getString(R.string.memory_status_superseded),
                    statusResolved = resources.getString(R.string.memory_status_resolved),
                    statusExpired = resources.getString(R.string.memory_status_expired),
                    statusArchived = resources.getString(R.string.memory_status_archived),
                    aliasesPrefix = resources.getString(R.string.memory_export_aliases),
                )
                val markdown = withContext(Dispatchers.IO) { dependencies.memory.exportMarkdown(labels) }
                val shareDir = File(context.cacheDir, "share-incoming").apply { mkdirs() }
                val outFile = File(shareDir, "memory-export-${LocalDate.now()}.md")
                withContext(Dispatchers.IO) { outFile.writeText(markdown, Charsets.UTF_8) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", outFile)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val title = resources.getString(R.string.memory_export_title)
                context.startActivity(shareChooser(context, sendIntent, title))
            }.isSuccess
            if (!ok) {
                statusNotice = MemoryStatusNotice(resources.getString(R.string.memory_export_failed), DdNoticeTone.DANGER)
            }
        }
    }

    MemoryScreen(
        state = MemoryScreenState(
            activeTab = activeTab,
            query = query,
            blocks = blocks,
            archivedBlocks = archivedBlocks,
            proposals = proposals,
            entities = entities,
            relations = relations,
            events = events,
            tags = tags,
            selectedTag = selectedTag,
            includeHistory = includeHistory,
            relationFilter = relationFilter,
            hasMoreEvents = hasMoreEvents,
            loading = loading,
            loadError = loadError,
            organizing = organizing,
            auditEntries = auditEntries,
            auditLoading = auditLoading,
            activitySheetOpen = auditOpen,
            revertingAuditIds = revertingAuditIds,
            busyProposalIds = busyProposalIds,
            undoDeleteVisible = undoDeleteId != null,
            statusNotice = statusNotice,
        ),
        onBack = onBack,
        onSelectTab = { tab -> activeTabName = tab.name },
        onQueryChange = {
            query = it
            visibleLimit = PAGE_SIZE
        },
        onClearFilters = {
            query = ""
            selectedTag = null
            includeHistory = false
            relationFilterName = RelationFilter.ALL.name
            visibleLimit = PAGE_SIZE
        },
        onRetryLoad = { reloadNonce += 1L },
        onAcceptProposal = ::acceptProposal,
        onDismissProposal = ::dismissProposal,
        onSaveBlock = ::saveBlock,
        onDeleteBlock = ::deleteBlock,
        onSelectRelationFilter = { filter -> relationFilterName = filter.name },
        onInspectEntity = { entityId ->
            withContext(Dispatchers.IO) {
                dependencies.memory.inspectEntity(
                    nameOrId = entityId,
                    scope = MemoryAccessScope.PERSONAL,
                    includeHistory = true,
                )
            }
        },
        onSaveEntity = ::saveEntity,
        onAddRelation = { draft: RelationDraftPayload ->
            val ok = withContext(Dispatchers.IO) {
                dependencies.memory.upsertRelation(
                    fromEntityName = draft.fromName,
                    relationType = MemoryRelationTypes.normalize(draft.relationLabel),
                    toEntityName = draft.toName,
                    label = draft.relationLabel,
                ) != null
            }
            if (ok) reloadNonce += 1L
            ok
        },
        onInvalidateRelation = { relId ->
            val ok = withContext(Dispatchers.IO) {
                dependencies.memory.invalidateRelation(relId)
            }
            if (ok) reloadNonce += 1L
            ok
        },
        onMergeEntity = ::mergeEntity,
        onSelectTag = { tag ->
            selectedTag = if (selectedTag == tag) null else tag
            visibleLimit = PAGE_SIZE
        },
        onToggleIncludeHistory = {
            includeHistory = !includeHistory
            visibleLimit = PAGE_SIZE
        },
        onLoadMoreEvents = { visibleLimit += PAGE_SIZE },
        onSaveEvent = ::saveEvent,
        onDeleteEvent = ::deleteEvent,
        onUndoDelete = ::undoDelete,
        onOpenSourceSession = { sessionId ->
            scope.launch {
                when {
                    sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID ||
                        sessionId.startsWith("${SuperRuntime.SUPER_THREAD_SESSION_ID}:archived:") -> {
                        if (dependencies.appPreferences.read().appMode != AppMode.SUPER) {
                            dependencies.appPreferences.setAppMode(AppMode.SUPER)
                        }
                        navigator.reset(AppDestination.Chat)
                    }

                    sessionId.startsWith(SuperRuntime.WORKER_SESSION_PREFIX) -> {
                        val workerId = sessionId.removePrefix(SuperRuntime.WORKER_SESSION_PREFIX).substringAfterLast(':')
                        dependencies.superRuntime.ensureStarted()
                        val exists = dependencies.superRuntime.workers.value.any { it.workerId == workerId }
                        if (exists) {
                            if (dependencies.appPreferences.read().appMode != AppMode.SUPER) {
                                dependencies.appPreferences.setAppMode(AppMode.SUPER)
                            }
                            navigator.reset(AppDestination.Chat)
                            navigator.push(AppDestination.WorkerMonitor(workerId))
                        } else {
                            statusNotice = MemoryStatusNotice(
                                resources.getString(R.string.memory_source_worker_removed),
                                DdNoticeTone.INFO,
                            )
                        }
                    }

                    else -> {
                        val localExists = withContext(Dispatchers.IO) {
                            runCatching {
                                dependencies.host.allSessions().any { it.id == sessionId } ||
                                    dependencies.host.archivedSessions().any { it.id == sessionId }
                            }.getOrDefault(false)
                        }
                        if (localExists) {
                            if (dependencies.appPreferences.read().appMode != AppMode.NORMAL) {
                                dependencies.appPreferences.setAppMode(AppMode.NORMAL)
                            }
                            chatViewModel.openConversationById(sessionId)
                            navigator.reset(AppDestination.Chat)
                        } else {
                            statusNotice = MemoryStatusNotice(
                                resources.getString(R.string.memory_source_other_device),
                                DdNoticeTone.INFO,
                            )
                        }
                    }
                }
            }
        },
        onPurgeSession = ::purgeSession,
        onOpenActivity = {
            auditOpen = true
            auditReloadNonce += 1L
        },
        onDismissActivity = { auditOpen = false },
        onRevertAudit = ::revertAudit,
        onOrganizeNow = ::triggerOrganize,
        onExportMarkdown = ::exportMarkdown,
    )
}
