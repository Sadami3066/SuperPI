package io.github.whywood.superpi.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdChip
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdNoticeBar
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdPrimaryButton
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSearchField
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSheetHeader
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdTextArea
import io.github.whywood.superpi.design.DdTextField
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemoryRecord
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.MemoryTagCount
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.memory.MemoryLimits
import io.github.whywood.superpi.ui.common.DelayedLoadingBlock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

private const val EDITOR_COUNTER_THRESHOLD = 7_000

internal data class MemoryStatusNotice(
    val text: String,
    val tone: DdNoticeTone = DdNoticeTone.INFO,
    val actionLabel: String? = null,
)

internal data class MemoryScreenState(
    val query: String,
    val selectedKind: MemoryKind?,
    val selectedTag: String?,
    val loading: Boolean,
    val organizing: Boolean,
    val records: List<MemoryRecord>,
    val tags: List<MemoryTagCount>,
    val hasMore: Boolean = false,
    val undoDeleteVisible: Boolean,
    val statusNotice: MemoryStatusNotice?,
    val syncNotice: MemoryStatusNotice? = null,
    val memorySettings: MemorySettings,
    val configuredProviders: List<ProviderSummary>,
    val modelsByProvider: Map<String, List<ModelOption>>,
    val defaultProviderId: String,
)

internal data class MemoryEditorPayload(
    val id: String?,
    val content: String,
    val kind: MemoryKind,
    val tags: List<String>,
    val pinned: Boolean,
    val validUntil: Long?,
    val originalConfidence: MemoryConfidence,
    val confirmInferred: Boolean,
)

@Composable
internal fun MemoryScreen(
    state: MemoryScreenState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectKind: (MemoryKind?) -> Unit,
    onSelectTag: (String) -> Unit,
    onLoadMore: () -> Unit = {},
    onClearFilters: () -> Unit,
    onSaveEditor: (MemoryEditorPayload) -> Unit,
    onConfirmRecord: (String) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onUndoDelete: () -> Unit,
    onForgetTag: (String) -> Unit,
    onOpenSourceSession: (String) -> Unit,
    onPurgeSession: (String) -> Unit,
    onOrganizeNow: () -> Unit,
    onExportMarkdown: () -> Unit,
    onSaveMemorySettings: (MemorySettings) -> Unit,
    onSyncNoticeAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    var menuOpen by remember { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorSessionNonce by rememberSaveable { mutableIntStateOf(0) }
    var editingRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorInitialContent by rememberSaveable { mutableStateOf("") }
    var editorInitialKindWire by rememberSaveable { mutableStateOf(MemoryKind.ACTIVE.wire) }
    var editorInitialTagsText by rememberSaveable { mutableStateOf("") }
    var editorInitialPinned by rememberSaveable { mutableStateOf(false) }
    var editorOriginalConfidenceWire by rememberSaveable { mutableStateOf(MemoryConfidence.CONFIRMED.wire) }
    var editorValidUntil by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorSourceQuote by rememberSaveable { mutableStateOf<String?>(null) }
    var editorSourceSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorCreatedAt by rememberSaveable { mutableLongStateOf(0L) }
    var modelSheetOpen by remember { mutableStateOf(false) }
    var confirmForgetTag by remember { mutableStateOf<String?>(null) }
    var confirmPurgeSessionId by remember { mutableStateOf<String?>(null) }

    fun openEditor(record: MemoryRecord?) {
        editingRecordId = record?.id
        editorInitialContent = record?.content.orEmpty()
        editorInitialKindWire = (record?.kind ?: MemoryKind.ACTIVE).wire
        editorInitialTagsText = record?.tags?.joinToString(", ").orEmpty()
        editorInitialPinned = record?.pinned ?: false
        editorOriginalConfidenceWire = (record?.confidence ?: MemoryConfidence.CONFIRMED).wire
        editorValidUntil = record?.validUntil
        editorSourceQuote = record?.sourceQuote
        editorSourceSessionId = record?.sourceSessionId
        editorCreatedAt = record?.createdAt ?: 0L
        editorSessionNonce += 1
        editorOpen = true
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
            DdIconButton(
                icon = DdIcons.Plus,
                onClick = { openEditor(null) },
                contentDescription = stringResource(R.string.memory_add),
                modifier = Modifier.testTag("memory_add"),
                tint = colors.ink,
                iconSize = 20.dp,
            )
            Box {
                DdIconButton(
                    icon = DdIcons.DotsVertical,
                    onClick = { menuOpen = true },
                    contentDescription = stringResource(R.string.memory_more),
                    modifier = Modifier.testTag("memory_more"),
                    tint = colors.inkSecondary,
                    iconSize = 20.dp,
                )
                DdMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    menuWidth = 176.dp,
                ) {
                    DdMenuItem(
                        text = stringResource(R.string.memory_menu_organize),
                        icon = DdIcons.Sparkles,
                        onClick = {
                            menuOpen = false
                            if (!state.organizing) onOrganizeNow()
                        },
                        testTag = "memory_menu_organize",
                    )
                    DdMenuItem(
                        text = stringResource(R.string.memory_menu_model),
                        icon = DdIcons.Settings,
                        onClick = {
                            menuOpen = false
                            modelSheetOpen = true
                        },
                        testTag = "memory_menu_model",
                    )
                    DdMenuItem(
                        text = stringResource(R.string.memory_menu_export),
                        icon = DdIcons.Download,
                        onClick = {
                            menuOpen = false
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

            CategoryChipsRow(
                selectedKind = state.selectedKind,
                onSelect = onSelectKind,
            )

            if (state.tags.isNotEmpty()) {
                TagChipsRow(
                    tags = state.tags,
                    selectedTag = state.selectedTag,
                    onSelectTag = onSelectTag,
                )
            }

            state.selectedTag?.let { activeTag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DdSpacing.Xxl, vertical = DdSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    DdTextAction(
                        text = stringResource(R.string.memory_forget_tag_action, activeTag),
                        onClick = { confirmForgetTag = activeTag },
                        color = colors.danger,
                    )
                }
            }

            AnimatedVisibility(
                visible = state.undoDeleteVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                DdNoticeBar(
                    text = stringResource(R.string.memory_deleted_notice),
                    modifier = Modifier.padding(horizontal = DdSpacing.Xxl, vertical = DdSpacing.Xs),
                    tone = DdNoticeTone.INFO,
                    actionLabel = stringResource(R.string.memory_undo),
                    onAction = onUndoDelete,
                )
            }

            AnimatedVisibility(
                visible = !state.undoDeleteVisible && (state.statusNotice != null || state.syncNotice != null),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val activeNotice = state.statusNotice ?: state.syncNotice
                val isSyncNotice = state.statusNotice == null && state.syncNotice != null
                activeNotice?.let { notice ->
                    DdNoticeBar(
                        text = notice.text,
                        modifier = Modifier.padding(horizontal = DdSpacing.Xxl, vertical = DdSpacing.Xs),
                        tone = notice.tone,
                        actionLabel = if (isSyncNotice) notice.actionLabel else null,
                        onAction = if (isSyncNotice && notice.actionLabel != null) onSyncNoticeAction else null,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val hasFilters = state.query.isNotBlank() || state.selectedKind != null || state.selectedTag != null
                when {
                    state.loading && state.records.isEmpty() -> {
                        DelayedLoadingBlock(
                            loading = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = DdSpacing.Xxxl),
                        )
                    }
                    state.records.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = DdSpacing.Xxl),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hasFilters) {
                                DdEmptyState(
                                    text = stringResource(R.string.memory_empty_filtered),
                                    icon = DdIcons.Search,
                                    action = {
                                        DdTextAction(
                                            text = stringResource(R.string.memory_clear_filters),
                                            onClick = onClearFilters,
                                        )
                                    },
                                )
                            } else {
                                DdEmptyState(
                                    text = stringResource(R.string.memory_empty_all),
                                    icon = DdIcons.Brain,
                                    action = {
                                        DdPrimaryButton(
                                            text = stringResource(R.string.memory_add),
                                            onClick = { openEditor(null) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = DdSpacing.Xs, bottom = DdSpacing.Xxxl),
                        ) {
                            items(
                                items = state.records,
                                key = { item -> item.id },
                            ) { record ->
                                Column(Modifier.animateItem()) {
                                    MemoryRecordRow(
                                        record = record,
                                        onClick = { openEditor(record) },
                                    )
                                    DdHairline(Modifier.padding(horizontal = DdSpacing.Xxl))
                                }
                            }
                            if (state.hasMore) {
                                item(key = "memory_load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = DdSpacing.Lg),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        DdTextAction(
                                            text = stringResource(R.string.memory_load_more),
                                            onClick = onLoadMore,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        MemoryEditorSheet(
            sessionNonce = editorSessionNonce,
            recordId = editingRecordId,
            initialContent = editorInitialContent,
            initialKind = MemoryKind.fromWire(editorInitialKindWire) ?: MemoryKind.ACTIVE,
            initialTagsText = editorInitialTagsText,
            initialPinned = editorInitialPinned,
            originalConfidence = MemoryConfidence.fromWire(editorOriginalConfidenceWire),
            validUntil = editorValidUntil,
            sourceQuote = editorSourceQuote,
            sourceSessionId = editorSourceSessionId,
            createdAt = editorCreatedAt,
            onDismiss = { editorOpen = false },
            onSave = { payload ->
                editorOpen = false
                onSaveEditor(payload)
            },
            onConfirmInferred = { id ->
                editorOpen = false
                onConfirmRecord(id)
            },
            onDelete = { id ->
                editorOpen = false
                onDeleteRecord(id)
            },
            onOpenSourceSession = { sessionId ->
                editorOpen = false
                onOpenSourceSession(sessionId)
            },
            onRequestPurgeSession = { sessionId ->
                confirmPurgeSessionId = sessionId
            },
        )
    }

    if (modelSheetOpen) {
        MemoryModelSheet(
            settings = state.memorySettings,
            configuredProviders = state.configuredProviders,
            modelsByProvider = state.modelsByProvider,
            defaultProviderId = state.defaultProviderId,
            onSelectSettings = onSaveMemorySettings,
            onDismiss = { modelSheetOpen = false },
        )
    }

    confirmForgetTag?.let { tag ->
        DdDialog(
            onDismissRequest = { confirmForgetTag = null },
            title = stringResource(R.string.memory_forget_tag_title),
            content = {
                DdText(
                    text = stringResource(R.string.memory_forget_tag_body, tag),
                    style = DdType.body,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.memory_cancel),
                    onClick = { confirmForgetTag = null },
                )
                DdTextAction(
                    text = stringResource(R.string.memory_forget_tag_confirm),
                    color = colors.danger,
                    onClick = {
                        confirmForgetTag = null
                        onForgetTag(tag)
                    },
                )
            },
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
                        editorOpen = false
                        onPurgeSession(sessionId)
                    },
                )
            },
        )
    }
}

@Composable
private fun CategoryChipsRow(
    selectedKind: MemoryKind?,
    onSelect: (MemoryKind?) -> Unit,
) {
    val entries = listOf<Pair<MemoryKind?, Int>>(
        null to R.string.memory_cat_all,
        MemoryKind.IDENTITY to R.string.memory_cat_identity,
        MemoryKind.ACTIVE to R.string.memory_cat_active,
        MemoryKind.HABIT to R.string.memory_cat_habit,
        MemoryKind.EPISODIC to R.string.memory_cat_episodic,
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = DdSpacing.Xxl),
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        items(entries, key = { (kind, _) -> kind?.wire ?: "ALL" }) { (kind, labelRes) ->
            DdChip(
                text = stringResource(labelRes),
                selected = selectedKind == kind,
                onClick = {
                    if (kind == null) {
                        onSelect(null)
                    } else {
                        onSelect(kind)
                    }
                },
            )
        }
    }
}

@Composable
private fun TagChipsRow(
    tags: List<MemoryTagCount>,
    selectedTag: String?,
    onSelectTag: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DdSpacing.Xs),
        contentPadding = PaddingValues(horizontal = DdSpacing.Xxl),
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        items(tags.take(20), key = { it.tag }) { tag ->
            DdChip(
                text = "#${tag.tag}",
                selected = selectedTag == tag.tag,
                onClick = { onSelectTag(tag.tag) },
            )
        }
    }
}

@Composable
private fun MemoryRecordRow(
    record: MemoryRecord,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val todayLabel = stringResource(R.string.memory_date_today)
    val yesterdayLabel = stringResource(R.string.memory_date_yesterday)
    val daysAgoFormat = stringResource(R.string.memory_date_days_ago)
    val dateText = remember(record.validFrom, todayLabel, yesterdayLabel, daysAgoFormat) {
        formatRelativeDate(record.validFrom, todayLabel, yesterdayLabel, daysAgoFormat)
    }

    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = DdShapes.Row,
        contentDescription = record.content,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Xxl, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                DdText(
                    text = record.content,
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (record.pinned) {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdIcon(
                        icon = DdIcons.Pin,
                        tint = colors.accent,
                        size = 15.dp,
                        contentDescription = stringResource(R.string.memory_pinned_cd),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                ) {
                    DdText(
                        text = stringResource(kindLabelRes(record.kind)),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (record.tags.isNotEmpty()) {
                        val tagsSummary = remember(record.tags) {
                            record.tags.take(3).joinToString(" ") { "#$it" }
                        }
                        DdText(
                            text = tagsSummary,
                            modifier = Modifier.weight(1f, fill = false),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (record.confidence == MemoryConfidence.INFERRED) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.wash)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        DdText(
                            text = stringResource(R.string.memory_inferred_badge),
                            style = DdType.micro,
                            color = colors.accent,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                DdText(
                    text = dateText,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditorSheet(
    sessionNonce: Int,
    recordId: String?,
    initialContent: String,
    initialKind: MemoryKind,
    initialTagsText: String,
    initialPinned: Boolean,
    originalConfidence: MemoryConfidence,
    validUntil: Long?,
    sourceQuote: String?,
    sourceSessionId: String?,
    createdAt: Long,
    onDismiss: () -> Unit,
    onSave: (MemoryEditorPayload) -> Unit,
    onConfirmInferred: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSourceSession: (String) -> Unit,
    onRequestPurgeSession: (String) -> Unit,
) {
    val colors = superPiColors()
    var content by rememberSaveable(sessionNonce) { mutableStateOf(initialContent) }
    var kindWire by rememberSaveable(sessionNonce) { mutableStateOf(initialKind.wire) }
    val kind = MemoryKind.fromWire(kindWire) ?: MemoryKind.ACTIVE
    var tagsText by rememberSaveable(sessionNonce) { mutableStateOf(initialTagsText) }
    var pinned by rememberSaveable(sessionNonce) { mutableStateOf(initialPinned) }

    val kindOptions = listOf(
        MemoryKind.IDENTITY,
        MemoryKind.ACTIVE,
        MemoryKind.HABIT,
        MemoryKind.EPISODIC,
    )

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(
                if (recordId == null) R.string.memory_sheet_add_title else R.string.memory_sheet_edit_title,
            ),
            onClose = onDismiss,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.Xxl)
                .padding(bottom = DdSpacing.Xxl),
        ) {
            if (recordId != null && originalConfidence == MemoryConfidence.INFERRED) {
                DdNoticeBar(
                    text = stringResource(R.string.memory_inferred_hint),
                    tone = DdNoticeTone.INFO,
                    actionLabel = stringResource(R.string.memory_confirm_accurate),
                    onAction = { onConfirmInferred(recordId) },
                )
                Spacer(Modifier.height(DdSpacing.Md))
            }

            DdTextArea(
                value = content,
                onValueChange = { content = capTextPreservingWhitespace(it, MemoryLimits.MAX_CONTENT_CHARS) },
                placeholder = stringResource(R.string.memory_content_hint),
                minHeight = 112.dp,
                maxHeight = 200.dp,
                testTag = "memory_editor_content",
            )
            if (content.length >= EDITOR_COUNTER_THRESHOLD) {
                Spacer(Modifier.height(DdSpacing.Xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DdText(
                        text = "${content.length} / ${MemoryLimits.MAX_CONTENT_CHARS}",
                        style = DdType.micro,
                        color = if (content.length >= MemoryLimits.MAX_CONTENT_CHARS) colors.danger else colors.inkTertiary,
                    )
                }
            }

            Spacer(Modifier.height(DdSpacing.Lg))
            DdSectionLabel(stringResource(R.string.memory_category_label))
            Spacer(Modifier.height(DdSpacing.Xs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
            ) {
                kindOptions.forEach { candidate ->
                    DdChip(
                        text = stringResource(kindLabelRes(candidate)),
                        selected = kind == candidate,
                        onClick = { kindWire = candidate.wire },
                    )
                }
            }

            Spacer(Modifier.height(DdSpacing.Lg))
            DdSectionLabel(stringResource(R.string.memory_tags_label))
            Spacer(Modifier.height(DdSpacing.Xs))
            DdTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                placeholder = stringResource(R.string.memory_tags_hint),
            )

            Spacer(Modifier.height(DdSpacing.Lg))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = DdSpacing.Md)) {
                    DdText(
                        text = stringResource(R.string.memory_pin_title),
                        style = DdType.body,
                        color = colors.ink,
                    )
                    DdText(
                        text = stringResource(R.string.memory_pin_subtitle),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                }
                DdSwitch(
                    checked = pinned,
                    onCheckedChange = { pinned = it },
                )
            }

            if (recordId != null && (!sourceQuote.isNullOrBlank() || !sourceSessionId.isNullOrBlank())) {
                Spacer(Modifier.height(DdSpacing.Lg))
                DdHairline()
                Spacer(Modifier.height(DdSpacing.Lg))
                DdSectionLabel(stringResource(R.string.memory_source_label))
                Spacer(Modifier.height(DdSpacing.Xs))

                sourceQuote?.takeIf { it.isNotBlank() }?.let { quote ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(DdShapes.Medium)
                            .background(colors.wash)
                            .padding(DdSpacing.Md),
                    ) {
                        DdText(
                            text = "“$quote”",
                            style = DdType.bodySmall,
                            color = colors.inkSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        DdText(
                            text = formatDateTime(createdAt),
                            style = DdType.micro,
                            color = colors.inkTertiary,
                        )
                    }
                    Spacer(Modifier.height(DdSpacing.Sm))
                }

                sourceSessionId?.takeIf { it.isNotBlank() }?.let { sessionId ->
                    DdGroupCard {
                        DdListRow(
                            title = stringResource(R.string.memory_source_open_chat),
                            icon = DdIcons.Message,
                            showChevron = true,
                            onClick = { onOpenSourceSession(sessionId) },
                        )
                        DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        DdListRow(
                            title = stringResource(R.string.memory_source_purge_session),
                            icon = DdIcons.Trash,
                            onClick = { onRequestPurgeSession(sessionId) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(DdSpacing.Xl))
            val parsedTags = parseTagsInput(tagsText)
            val savePayload = MemoryEditorPayload(
                id = recordId,
                content = content.trim(),
                kind = kind,
                tags = parsedTags,
                pinned = pinned,
                validUntil = validUntil,
                originalConfidence = originalConfidence,
                confirmInferred = false,
            )
            if (recordId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdTextAction(
                        text = stringResource(R.string.memory_delete),
                        color = colors.danger,
                        onClick = { onDelete(recordId) },
                    )
                    Spacer(Modifier.weight(1f))
                    DdPrimaryButton(
                        text = stringResource(R.string.memory_save),
                        enabled = content.isNotBlank(),
                        onClick = { onSave(savePayload) },
                    )
                }
            } else {
                DdPrimaryButton(
                    text = stringResource(R.string.memory_save),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = content.isNotBlank(),
                    onClick = { onSave(savePayload) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryAccountSwitchDialog(
    localMemoryCount: Int,
    busy: Boolean,
    onMerge: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    DdDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(R.string.memory_account_switch_title),
        content = {
            DdText(
                text = stringResource(R.string.memory_account_switch_body, localMemoryCount),
                style = DdType.body,
                color = colors.inkSecondary,
            )
        },
        actions = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
            ) {
                DdTextAction(
                    text = stringResource(R.string.memory_account_switch_clear),
                    color = colors.danger,
                    enabled = !busy,
                    onClick = onClear,
                )
                DdTextAction(
                    text = stringResource(R.string.memory_account_switch_merge),
                    color = colors.accent,
                    enabled = !busy,
                    onClick = onMerge,
                )
            }
        },
    )
}

private fun capTextPreservingWhitespace(raw: String, maxChars: Int): String {
    if (raw.length <= maxChars) return raw
    var end = maxChars
    if (end > 0 && raw[end - 1].isHighSurrogate()) {
        end--
    }
    return raw.substring(0, end)
}

@Composable
private fun MemoryModelSheet(
    settings: MemorySettings,
    configuredProviders: List<ProviderSummary>,
    modelsByProvider: Map<String, List<ModelOption>>,
    defaultProviderId: String,
    onSelectSettings: (MemorySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    val initialProvider = settings.fixedProviderId?.takeIf { p -> configuredProviders.any { it.id == p } }
        ?: configuredProviders.firstOrNull { it.id == defaultProviderId }?.id
        ?: configuredProviders.firstOrNull()?.id
        ?: ""
    var selectedProviderId by remember(settings.fixedProviderId, configuredProviders) {
        mutableStateOf(initialProvider)
    }

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.memory_model_title),
            onClose = onDismiss,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.Xxl)
                .padding(bottom = DdSpacing.Xxl),
        ) {
            DdGroupCard {
                DdListRow(
                    title = stringResource(R.string.memory_model_auto),
                    subtitle = stringResource(R.string.memory_model_auto_desc),
                    subtitleMaxLines = 1,
                    trailing = { ModeIndicator(selected = settings.mode == MemoryModelMode.AUTO) },
                    onClick = {
                        onSelectSettings(
                            settings.copy(mode = MemoryModelMode.AUTO),
                        )
                    },
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                DdListRow(
                    title = stringResource(R.string.memory_model_fixed),
                    trailing = { ModeIndicator(selected = settings.mode == MemoryModelMode.FIXED) },
                    onClick = {
                        val providerModels = modelsByProvider[selectedProviderId].orEmpty()
                        val targetModelId = settings.fixedModelId?.takeIf { id -> providerModels.any { it.id == id } }
                            ?: providerModels.firstOrNull()?.id
                        onSelectSettings(
                            MemorySettings(
                                mode = MemoryModelMode.FIXED,
                                fixedProviderId = selectedProviderId.takeIf { it.isNotEmpty() },
                                fixedModelId = targetModelId,
                            ),
                        )
                    },
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                DdListRow(
                    title = stringResource(R.string.memory_model_paused),
                    subtitle = stringResource(R.string.memory_model_paused_desc),
                    subtitleMaxLines = 1,
                    trailing = { ModeIndicator(selected = settings.mode == MemoryModelMode.PAUSED) },
                    onClick = {
                        onSelectSettings(
                            settings.copy(mode = MemoryModelMode.PAUSED),
                        )
                    },
                )
            }

            if (settings.mode == MemoryModelMode.FIXED) {
                Spacer(Modifier.height(DdSpacing.Lg))
                if (configuredProviders.isEmpty()) {
                    DdText(
                        text = stringResource(R.string.memory_model_none),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                    ) {
                        items(configuredProviders, key = { it.id }) { provider ->
                            DdChip(
                                text = provider.name,
                                selected = selectedProviderId == provider.id,
                                onClick = { selectedProviderId = provider.id },
                            )
                        }
                    }
                    Spacer(Modifier.height(DdSpacing.Md))
                    val models = modelsByProvider[selectedProviderId].orEmpty()
                    if (models.isNotEmpty()) {
                        DdGroupCard {
                            models.forEachIndexed { index, model ->
                                if (index > 0) {
                                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                                }
                                val isCurrent = settings.fixedProviderId == selectedProviderId &&
                                    settings.fixedModelId == model.id
                                DdListRow(
                                    title = model.displayName.ifBlank { model.id },
                                    trailing = { ModeIndicator(selected = isCurrent) },
                                    onClick = {
                                        onSelectSettings(
                                            MemorySettings(
                                                mode = MemoryModelMode.FIXED,
                                                fixedProviderId = selectedProviderId,
                                                fixedModelId = model.id,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeIndicator(selected: Boolean) {
    val colors = superPiColors()
    DdIcon(
        icon = if (selected) DdIcons.Check else DdIcons.RadioOff,
        tint = if (selected) colors.accent else colors.inkTertiary,
        size = 18.dp,
    )
}

private fun kindLabelRes(kind: MemoryKind): Int = when (kind) {
    MemoryKind.IDENTITY -> R.string.memory_cat_identity
    MemoryKind.ACTIVE -> R.string.memory_cat_active
    MemoryKind.HABIT -> R.string.memory_cat_habit
    MemoryKind.EPISODIC -> R.string.memory_cat_episodic
}

private fun parseTagsInput(raw: String): List<String> =
    raw.split(',', '，', '#', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(8)

private fun formatRelativeDate(
    epochMs: Long,
    today: String,
    yesterday: String,
    daysAgoFormat: String,
): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val now = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(date, now)
    return when {
        days <= 0L -> today
        days == 1L -> yesterday
        days in 2L..6L -> String.format(daysAgoFormat, days.toInt())
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

private fun formatDateTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
