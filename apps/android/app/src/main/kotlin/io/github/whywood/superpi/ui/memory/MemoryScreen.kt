package io.github.whywood.superpi.ui.memory

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdNoticeBar
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdSearchField
import io.github.whywood.superpi.design.DdSegmented
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.MemoryAuditEntry
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockProposal
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityDetail
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemoryTagCount
import io.github.whywood.superpi.ui.common.DelayedLoadingBlock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

internal enum class MemoryTab {
    PROFILE,
    RELATIONS,
    TIMELINE,
}

internal enum class RelationFilter {
    ALL,
    PEOPLE,
    PLACES,
    PROJECTS,
}

internal enum class BlockVisibility {
    PERSONAL_ONLY,
    PERSONAL_AND_WORK,
}

internal data class MemoryStatusNotice(
    val text: String,
    val tone: DdNoticeTone = DdNoticeTone.INFO,
    val actionLabel: String? = null,
)

internal data class BlockEditorPayload(
    val id: String?,
    val title: String,
    val purpose: String,
    val content: String,
    val existingDomain: String,
    val existingSensitivity: MemorySensitivity,
    val visibility: BlockVisibility,
    val pinned: Boolean,
    val archived: Boolean,
)

internal data class EventEditorPayload(
    val id: String?,
    val content: String,
    val eventKind: MemoryEventKind,
    val tags: List<String>,
    val pinned: Boolean,
    val status: MemoryItemStatus,
)

internal data class EntityEditorPayload(
    val id: String,
    val canonicalName: String,
    val entityType: String,
    val aliases: List<String>,
    val summary: String?,
    val clearSummary: Boolean = false,
    val pinned: Boolean,
)

internal data class RelationDraftPayload(
    val fromName: String,
    val relationLabel: String,
    val toName: String,
)

internal data class MemoryScreenState(
    val activeTab: MemoryTab = MemoryTab.PROFILE,
    val query: String = "",
    val blocks: List<MemoryBlock> = emptyList(),
    val archivedBlocks: List<MemoryBlock> = emptyList(),
    val proposals: List<MemoryBlockProposal> = emptyList(),
    val entities: List<MemoryEntity> = emptyList(),
    val relations: List<MemoryEntityRelation> = emptyList(),
    val events: List<MemoryEvent> = emptyList(),
    val tags: List<MemoryTagCount> = emptyList(),
    val selectedTag: String? = null,
    val includeHistory: Boolean = false,
    val relationFilter: RelationFilter = RelationFilter.ALL,
    val hasMoreEvents: Boolean = false,
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val organizing: Boolean = false,
    val auditEntries: List<MemoryAuditEntry> = emptyList(),
    val auditLoading: Boolean = false,
    val activitySheetOpen: Boolean = false,
    val revertingAuditIds: Set<String> = emptySet(),
    val busyProposalIds: Set<String> = emptySet(),
    val undoDeleteVisible: Boolean = false,
    val statusNotice: MemoryStatusNotice? = null,
)

@Composable
internal fun MemoryScreen(
    state: MemoryScreenState,
    onBack: () -> Unit,
    onSelectTab: (MemoryTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onClearFilters: () -> Unit,
    onRetryLoad: () -> Unit,
    onAcceptProposal: (String) -> Unit,
    onDismissProposal: (String) -> Unit,
    onSaveBlock: (BlockEditorPayload) -> Unit,
    onDeleteBlock: (String) -> Unit,
    onSelectRelationFilter: (RelationFilter) -> Unit,
    onInspectEntity: suspend (String) -> MemoryEntityDetail?,
    onSaveEntity: (EntityEditorPayload) -> Unit,
    onAddRelation: suspend (RelationDraftPayload) -> Boolean,
    onInvalidateRelation: suspend (String) -> Boolean,
    onMergeEntity: (String, String) -> Unit,
    onSelectTag: (String) -> Unit,
    onToggleIncludeHistory: () -> Unit,
    onLoadMoreEvents: () -> Unit,
    onSaveEvent: (EventEditorPayload) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onUndoDelete: () -> Unit,
    onOpenSourceSession: (String) -> Unit,
    onPurgeSession: (String) -> Unit,
    onOpenActivity: () -> Unit,
    onDismissActivity: () -> Unit,
    onRevertAudit: (String) -> Unit,
    onOrganizeNow: () -> Unit,
    onExportMarkdown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    var addMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<MemoryBlock?>(null) }
    var blockSheetOpen by remember { mutableStateOf(false) }
    var blockSessionNonce by rememberSaveable { mutableIntStateOf(0) }
    var editingEvent by remember { mutableStateOf<MemoryEvent?>(null) }
    var eventSheetOpen by remember { mutableStateOf(false) }
    var eventSessionNonce by rememberSaveable { mutableIntStateOf(0) }
    var inspectingEntityId by rememberSaveable { mutableStateOf<String?>(null) }
    var inspectedDetail by remember { mutableStateOf<MemoryEntityDetail?>(null) }
    var entityDetailLoading by remember { mutableStateOf(false) }
    var entityDetailError by remember { mutableStateOf(false) }
    var entityDetailNonce by remember { mutableIntStateOf(0) }
    var confirmPurgeSessionId by remember { mutableStateOf<String?>(null) }

    fun openBlockEditor(block: MemoryBlock?) {
        editingBlock = block
        blockSessionNonce += 1
        blockSheetOpen = true
    }

    fun openEventEditor(event: MemoryEvent?) {
        editingEvent = event
        eventSessionNonce += 1
        eventSheetOpen = true
    }

    LaunchedEffect(inspectingEntityId, entityDetailNonce, state.entities, state.relations, state.events) {
        val targetId = inspectingEntityId
        if (targetId == null) {
            inspectedDetail = null
            entityDetailLoading = false
            entityDetailError = false
            return@LaunchedEffect
        }
        entityDetailLoading = true
        entityDetailError = false
        val loaded = runCatching { onInspectEntity(targetId) }.getOrNull()
        if (loaded == null) {
            entityDetailError = true
            inspectedDetail = null
        } else {
            inspectedDetail = loaded
        }
        entityDetailLoading = false
    }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.memory_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            DdText(
                text = stringResource(R.string.memory_title),
                modifier = Modifier.weight(1f),
                style = DdType.heading,
                color = colors.ink,
            )
            Box {
                DdIconButton(
                    icon = DdIcons.Plus,
                    onClick = { addMenuOpen = true },
                    contentDescription = stringResource(R.string.memory_add),
                    modifier = Modifier.testTag("memory_add"),
                    tint = colors.ink,
                    iconSize = 20.dp,
                )
                DdMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                    menuWidth = 180.dp,
                ) {
                    DdMenuItem(
                        text = stringResource(R.string.memory_add_block),
                        icon = DdIcons.File,
                        onClick = {
                            addMenuOpen = false
                            openBlockEditor(null)
                        },
                        testTag = "memory_add_block",
                    )
                    DdMenuItem(
                        text = stringResource(R.string.memory_add_event),
                        icon = DdIcons.Pen,
                        onClick = {
                            addMenuOpen = false
                            openEventEditor(null)
                        },
                        testTag = "memory_add_event",
                    )
                }
            }
            Box {
                DdIconButton(
                    icon = DdIcons.DotsVertical,
                    onClick = { moreMenuOpen = true },
                    contentDescription = stringResource(R.string.memory_more),
                    modifier = Modifier.testTag("memory_more"),
                    tint = colors.inkSecondary,
                    iconSize = 20.dp,
                )
                DdMenu(
                    expanded = moreMenuOpen,
                    onDismissRequest = { moreMenuOpen = false },
                    menuWidth = 180.dp,
                ) {
                    DdMenuItem(
                        text = stringResource(R.string.memory_menu_activity),
                        icon = DdIcons.Clock,
                        onClick = {
                            moreMenuOpen = false
                            onOpenActivity()
                        },
                        testTag = "memory_menu_activity",
                    )
                    DdMenuItem(
                        text = stringResource(R.string.memory_menu_organize),
                        icon = DdIcons.Sparkles,
                        onClick = {
                            moreMenuOpen = false
                            if (!state.organizing) onOrganizeNow()
                        },
                        testTag = "memory_menu_organize",
                    )
                    DdMenuItem(
                        text = stringResource(R.string.memory_menu_export),
                        icon = DdIcons.Download,
                        onClick = {
                            moreMenuOpen = false
                            onExportMarkdown()
                        },
                        testTag = "memory_menu_export",
                    )
                }
            }
        },
        modifier = modifier.testTag("memory_page"),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            DdSearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.memory_search_placeholder),
                clearContentDescription = stringResource(R.string.memory_search_clear),
                modifier = Modifier.padding(horizontal = DdSpacing.Xxl),
                fieldTestTag = "memory_search",
            )
            Spacer(Modifier.height(DdSpacing.Sm))

            DdSegmented(
                options = listOf(
                    stringResource(R.string.memory_tab_profile),
                    stringResource(R.string.memory_tab_relations),
                    stringResource(R.string.memory_tab_timeline),
                ),
                selectedIndex = state.activeTab.ordinal,
                onSelect = { idx ->
                    val chosen = MemoryTab.entries.getOrNull(idx) ?: MemoryTab.PROFILE
                    onSelectTab(chosen)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Xxl),
                testTags = listOf(
                    "memory_tab_profile",
                    "memory_tab_relations",
                    "memory_tab_timeline",
                ),
            )
            Spacer(Modifier.height(DdSpacing.Sm))

            val noticeEnter = if (motionEnabled) fadeIn() + expandVertically() else EnterTransition.None
            val noticeExit = if (motionEnabled) fadeOut() + shrinkVertically() else ExitTransition.None
            AnimatedVisibility(
                visible = state.undoDeleteVisible,
                enter = noticeEnter,
                exit = noticeExit,
            ) {
                DdNoticeBar(
                    text = stringResource(R.string.memory_event_deleted_notice),
                    modifier = Modifier.padding(horizontal = DdSpacing.Xxl, vertical = DdSpacing.Xs),
                    tone = DdNoticeTone.INFO,
                    actionLabel = stringResource(R.string.memory_undo),
                    onAction = onUndoDelete,
                )
            }

            AnimatedVisibility(
                visible = !state.undoDeleteVisible && state.statusNotice != null,
                enter = noticeEnter,
                exit = noticeExit,
            ) {
                state.statusNotice?.let { notice ->
                    DdNoticeBar(
                        text = notice.text,
                        modifier = Modifier.padding(horizontal = DdSpacing.Xxl, vertical = DdSpacing.Xs),
                        tone = notice.tone,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val hasContent = state.blocks.isNotEmpty() ||
                    state.archivedBlocks.isNotEmpty() ||
                    state.entities.isNotEmpty() ||
                    state.events.isNotEmpty()
                when {
                    state.loading && !hasContent -> {
                        DelayedLoadingBlock(
                            loading = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = DdSpacing.Xxxl),
                        )
                    }
                    state.loadError && !hasContent -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = DdSpacing.Xxl),
                            contentAlignment = Alignment.Center,
                        ) {
                            DdEmptyState(
                                text = stringResource(R.string.memory_load_failed),
                                icon = DdIcons.Refresh,
                                action = {
                                    DdTextAction(
                                        text = stringResource(R.string.memory_retry),
                                        onClick = onRetryLoad,
                                    )
                                },
                            )
                        }
                    }
                    state.activeTab == MemoryTab.PROFILE -> {
                        MemoryProfileTab(
                            query = state.query,
                            blocks = state.blocks,
                            archivedBlocks = state.archivedBlocks,
                            proposals = state.proposals,
                            busyProposalIds = state.busyProposalIds,
                            onAcceptProposal = onAcceptProposal,
                            onDismissProposal = onDismissProposal,
                            onClickBlock = { openBlockEditor(it) },
                            onClearFilters = onClearFilters,
                        )
                    }
                    state.activeTab == MemoryTab.RELATIONS -> {
                        MemoryRelationsTab(
                            query = state.query,
                            entities = state.entities,
                            relations = state.relations,
                            filter = state.relationFilter,
                            onSelectFilter = onSelectRelationFilter,
                            onClickEntity = { entity -> inspectingEntityId = entity.id },
                            onClearFilters = onClearFilters,
                        )
                    }
                    else -> {
                        MemoryTimelineTab(
                            query = state.query,
                            events = state.events,
                            tags = state.tags,
                            selectedTag = state.selectedTag,
                            includeHistory = state.includeHistory,
                            hasMore = state.hasMoreEvents,
                            onSelectTag = onSelectTag,
                            onToggleIncludeHistory = onToggleIncludeHistory,
                            onLoadMore = onLoadMoreEvents,
                            onClickEvent = { openEventEditor(it) },
                            onAddEvent = { openEventEditor(null) },
                            onClearFilters = onClearFilters,
                        )
                    }
                }
            }
        }
    }

    if (blockSheetOpen) {
        MemoryBlockEditorSheet(
            sessionNonce = blockSessionNonce,
            block = editingBlock,
            onDismiss = { blockSheetOpen = false },
            onSave = { payload ->
                blockSheetOpen = false
                onSaveBlock(payload)
            },
            onDelete = { id ->
                blockSheetOpen = false
                onDeleteBlock(id)
            },
        )
    }

    if (eventSheetOpen) {
        MemoryEventEditorSheet(
            sessionNonce = eventSessionNonce,
            event = editingEvent,
            onDismiss = { eventSheetOpen = false },
            onSave = { payload ->
                eventSheetOpen = false
                onSaveEvent(payload)
            },
            onDelete = { id ->
                eventSheetOpen = false
                onDeleteEvent(id)
            },
            onOpenSourceSession = { sessionId ->
                eventSheetOpen = false
                onOpenSourceSession(sessionId)
            },
            onRequestPurgeSession = { sessionId ->
                confirmPurgeSessionId = sessionId
            },
        )
    }

    if (inspectingEntityId != null) {
        MemoryEntityDetailSheet(
            loading = entityDetailLoading,
            loadError = entityDetailError,
            detail = inspectedDetail,
            allEntities = state.entities,
            onRetry = { entityDetailNonce += 1 },
            onDismiss = { inspectingEntityId = null },
            onSaveEntity = { payload ->
                onSaveEntity(payload)
                entityDetailNonce += 1
            },
            onAddRelation = { draft ->
                val ok = onAddRelation(draft)
                if (ok) entityDetailNonce += 1
                ok
            },
            onInvalidateRelation = { relId ->
                val ok = onInvalidateRelation(relId)
                if (ok) entityDetailNonce += 1
                ok
            },
            onClickEvent = { event ->
                inspectingEntityId = null
                openEventEditor(event)
            },
            onMergeEntity = { keepId, mergeId ->
                inspectingEntityId = null
                onMergeEntity(keepId, mergeId)
            },
        )
    }

    if (state.activitySheetOpen) {
        MemoryActivitySheet(
            loading = state.auditLoading,
            entries = state.auditEntries,
            revertingIds = state.revertingAuditIds,
            onRevert = onRevertAudit,
            onDismiss = onDismissActivity,
        )
    }

    confirmPurgeSessionId?.let { sessionId ->
        DdDialog(
            onDismissRequest = { confirmPurgeSessionId = null },
            title = stringResource(R.string.memory_purge_session_title),
            content = {
                DdText(
                    text = stringResource(R.string.memory_purge_session_body),
                    style = DdType.body,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.memory_cancel),
                    onClick = { confirmPurgeSessionId = null },
                )
                DdTextAction(
                    text = stringResource(R.string.memory_purge_session_confirm),
                    color = colors.danger,
                    onClick = {
                        confirmPurgeSessionId = null
                        eventSheetOpen = false
                        onPurgeSession(sessionId)
                    },
                )
            },
        )
    }
}

internal fun localizedBlockTitle(block: MemoryBlock, resources: Resources): String {
    val resId = when (block.slug) {
        "self_profile" -> R.string.memory_builtin_self_profile_title
        "relationships" -> R.string.memory_builtin_relationships_title
        "preferences_boundaries" -> R.string.memory_builtin_preferences_boundaries_title
        "life_rhythm_health" -> R.string.memory_builtin_life_rhythm_health_title
        "active_threads" -> R.string.memory_builtin_active_threads_title
        "work_study" -> R.string.memory_builtin_work_study_title
        else -> null
    }
    return if (resId != null) resources.getString(resId) else block.title
}

internal fun localizedBlockPurpose(block: MemoryBlock, resources: Resources): String {
    val resId = when (block.slug) {
        "self_profile" -> R.string.memory_builtin_self_profile_purpose
        "relationships" -> R.string.memory_builtin_relationships_purpose
        "preferences_boundaries" -> R.string.memory_builtin_preferences_boundaries_purpose
        "life_rhythm_health" -> R.string.memory_builtin_life_rhythm_health_purpose
        "active_threads" -> R.string.memory_builtin_active_threads_purpose
        "work_study" -> R.string.memory_builtin_work_study_purpose
        else -> null
    }
    return if (resId != null) resources.getString(resId) else block.purpose
}

internal fun blockVisibilityOf(domain: String, sensitivity: MemorySensitivity): BlockVisibility =
    if (domain.trim().uppercase() in MemoryDomains.WORK_SCOPED &&
        (sensitivity == MemorySensitivity.PUBLIC || sensitivity == MemorySensitivity.NORMAL)
    ) {
        BlockVisibility.PERSONAL_AND_WORK
    } else {
        BlockVisibility.PERSONAL_ONLY
    }

internal fun blockVisibilityLabelRes(visibility: BlockVisibility): Int = when (visibility) {
    BlockVisibility.PERSONAL_ONLY -> R.string.memory_visibility_personal
    BlockVisibility.PERSONAL_AND_WORK -> R.string.memory_visibility_work
}

internal fun relationTypeLabel(relationType: String, resources: Resources): String {
    val clean = relationType.trim().lowercase().replace(' ', '_')
    val resId = when (clean) {
        MemoryRelationTypes.FAMILY_OF -> R.string.memory_relation_type_family_of
        MemoryRelationTypes.PARTNER_OF -> R.string.memory_relation_type_partner_of
        MemoryRelationTypes.FRIEND_OF -> R.string.memory_relation_type_friend_of
        MemoryRelationTypes.COLLEAGUE_OF -> R.string.memory_relation_type_colleague_of
        MemoryRelationTypes.LIVES_IN -> R.string.memory_relation_type_lives_in
        MemoryRelationTypes.WORKS_AT -> R.string.memory_relation_type_works_at
        MemoryRelationTypes.STUDIES_AT -> R.string.memory_relation_type_studies_at
        MemoryRelationTypes.PREFERS -> R.string.memory_relation_type_prefers
        MemoryRelationTypes.DISLIKES -> R.string.memory_relation_type_dislikes
        MemoryRelationTypes.ALLERGIC_TO -> R.string.memory_relation_type_allergic_to
        MemoryRelationTypes.OWNS -> R.string.memory_relation_type_owns
        MemoryRelationTypes.LOCATED_IN -> R.string.memory_relation_type_located_in
        MemoryRelationTypes.RELATED_TO -> R.string.memory_relation_type_related_to
        else -> null
    }
    return if (resId != null) resources.getString(resId) else clean.replace('_', ' ')
}

internal fun eventKindLabelRes(kind: MemoryEventKind): Int = when (kind) {
    MemoryEventKind.FACT -> R.string.memory_event_kind_fact
    MemoryEventKind.PREFERENCE -> R.string.memory_event_kind_preference
    MemoryEventKind.COMMITMENT -> R.string.memory_event_kind_commitment
    MemoryEventKind.EPISODE -> R.string.memory_event_kind_episode
    MemoryEventKind.GOAL -> R.string.memory_event_kind_goal
    MemoryEventKind.BOUNDARY -> R.string.memory_event_kind_boundary
    MemoryEventKind.NOTE -> R.string.memory_event_kind_note
}

internal fun eventStatusLabelRes(status: MemoryItemStatus): Int? = when (status) {
    MemoryItemStatus.ACTIVE -> null
    MemoryItemStatus.RESOLVED -> R.string.memory_status_resolved
    MemoryItemStatus.SUPERSEDED -> R.string.memory_status_superseded
    MemoryItemStatus.EXPIRED -> R.string.memory_status_expired
    MemoryItemStatus.ARCHIVED -> R.string.memory_status_archived
}

internal fun entityTypeLabelRes(type: String): Int = when (type.trim().uppercase()) {
    MemoryEntityTypes.PERSON -> R.string.memory_rel_type_person
    MemoryEntityTypes.FAMILY -> R.string.memory_rel_type_family
    MemoryEntityTypes.FRIEND -> R.string.memory_rel_type_friend
    MemoryEntityTypes.COLLEAGUE -> R.string.memory_rel_type_colleague
    MemoryEntityTypes.PLACE -> R.string.memory_rel_type_place
    MemoryEntityTypes.ORGANIZATION -> R.string.memory_rel_type_org
    MemoryEntityTypes.PROJECT -> R.string.memory_rel_type_project
    MemoryEntityTypes.PET -> R.string.memory_rel_type_pet
    MemoryEntityTypes.TOPIC -> R.string.memory_rel_type_topic
    else -> R.string.memory_rel_type_other
}

internal fun actorLabelRes(actor: MemoryMutationActor): Int = when (actor) {
    MemoryMutationActor.USER -> R.string.memory_actor_user
    MemoryMutationActor.MEMORY_AGENT_HOT,
    MemoryMutationActor.MEMORY_AGENT_COLD,
    MemoryMutationActor.MEMORY_AGENT_MIGRATION,
    -> R.string.memory_actor_auto
}

internal fun formatRelativeDate(
    epochMs: Long,
    today: String,
    yesterday: String,
    daysAgoFormat: String,
    tomorrow: String = today,
    inDaysFormat: String = daysAgoFormat,
): String {
    if (epochMs <= 0L) return today
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val now = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(date, now)
    return when {
        days == -1L -> tomorrow
        days in -6L..-2L -> String.format(inDaysFormat, (-days).toInt())
        days < -6L -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        days == 0L -> today
        days == 1L -> yesterday
        days in 2L..6L -> String.format(daysAgoFormat, days.toInt())
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

internal fun formatDateTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))

internal fun capTextPreservingWhitespace(raw: String, maxChars: Int): String {
    if (raw.length <= maxChars) return raw
    var end = maxChars
    if (end > 0 && raw[end - 1].isHighSurrogate()) {
        end--
    }
    return raw.substring(0, end)
}
