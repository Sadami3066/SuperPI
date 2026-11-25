package io.github.whywood.superpi.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdChip
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdPrimaryButton
import io.github.whywood.superpi.design.DdRowDivider
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
import io.github.whywood.superpi.runtime.MemoryAuditEntry
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.memory.MemoryLimits
import io.github.whywood.superpi.ui.common.DelayedLoadingBlock

private const val EVENT_COUNTER_THRESHOLD = 7_000

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryBlockEditorSheet(
    sessionNonce: Int,
    block: MemoryBlock?,
    onDismiss: () -> Unit,
    onSave: (BlockEditorPayload) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = superPiColors()
    val resources = LocalResources.current
    val maxChars = block?.maxChars ?: 2_000
    val isBuiltin = block?.owner == MemoryBlockOwner.BUILTIN
    val initialTitle = if (block != null) localizedBlockTitle(block, resources) else ""
    val initialPurpose = if (block != null) localizedBlockPurpose(block, resources) else ""
    val initialDomain = block?.domain ?: MemoryDomains.CUSTOM
    val initialSensitivity = block?.sensitivity ?: MemorySensitivity.NORMAL
    val initialVisibility = blockVisibilityOf(initialDomain, initialSensitivity)
    var title by rememberSaveable(sessionNonce) { mutableStateOf(initialTitle) }
    var purpose by rememberSaveable(sessionNonce) { mutableStateOf(initialPurpose) }
    var content by rememberSaveable(sessionNonce) { mutableStateOf(block?.content.orEmpty()) }
    var visibilityName by rememberSaveable(sessionNonce) { mutableStateOf(initialVisibility.name) }
    var pinned by rememberSaveable(sessionNonce) { mutableStateOf(block?.pinned ?: false) }
    var archived by rememberSaveable(sessionNonce) {
        mutableStateOf(block?.status == MemoryBlockStatus.ARCHIVED)
    }
    var confirmDelete by remember { mutableStateOf(false) }

    val visibility = BlockVisibility.entries.firstOrNull { it.name == visibilityName } ?: BlockVisibility.PERSONAL_ONLY

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(
                if (block == null) R.string.memory_block_add_title else R.string.memory_block_edit_title,
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
            if (!isBuiltin) {
                DdSectionLabel(stringResource(R.string.memory_block_title_label))
                Spacer(Modifier.height(DdSpacing.Xs))
                DdTextField(
                    value = title,
                    onValueChange = { title = capTextPreservingWhitespace(it, MemoryLimits.MAX_TITLE_CHARS) },
                    placeholder = stringResource(R.string.memory_block_title_hint),
                    testTag = "memory_block_title",
                )
                Spacer(Modifier.height(DdSpacing.Lg))
                DdSectionLabel(stringResource(R.string.memory_block_purpose_label))
                Spacer(Modifier.height(DdSpacing.Xs))
                DdTextField(
                    value = purpose,
                    onValueChange = { purpose = capTextPreservingWhitespace(it, MemoryLimits.MAX_PURPOSE_CHARS) },
                    placeholder = stringResource(R.string.memory_block_purpose_hint),
                    testTag = "memory_block_purpose",
                )
                Spacer(Modifier.height(DdSpacing.Lg))
            } else if (initialPurpose.isNotBlank()) {
                DdText(
                    text = initialPurpose,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
                Spacer(Modifier.height(DdSpacing.Md))
            }

            DdSectionLabel(stringResource(R.string.memory_block_content_label))
            Spacer(Modifier.height(DdSpacing.Xs))
            DdTextArea(
                value = content,
                onValueChange = { content = capTextPreservingWhitespace(it, maxChars) },
                placeholder = stringResource(R.string.memory_block_content_hint),
                minHeight = 136.dp,
                maxHeight = 240.dp,
                testTag = "memory_block_content",
            )
            if (content.length >= maxChars * 4 / 5) {
                Spacer(Modifier.height(DdSpacing.Xs))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DdText(
                        text = "${content.length} / $maxChars",
                        style = DdType.micro,
                        color = if (content.length >= maxChars) colors.danger else colors.inkTertiary,
                    )
                }
            }

            Spacer(Modifier.height(DdSpacing.Lg))
            DdSectionLabel(stringResource(R.string.memory_visibility_label))
            Spacer(Modifier.height(DdSpacing.Xs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
            ) {
                BlockVisibility.entries.forEach { option ->
                    DdChip(
                        text = stringResource(blockVisibilityLabelRes(option)),
                        selected = visibility == option,
                        onClick = { visibilityName = option.name },
                    )
                }
            }

            Spacer(Modifier.height(DdSpacing.Lg))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdText(
                    text = stringResource(R.string.memory_block_pin_title),
                    style = DdType.body,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).padding(end = DdSpacing.Md),
                )
                DdSwitch(
                    checked = pinned,
                    onCheckedChange = { pinned = it },
                )
            }

            if (block != null && !isBuiltin) {
                Spacer(Modifier.height(DdSpacing.Md))
                DdGroupCard {
                    DdListRow(
                        title = stringResource(R.string.memory_block_archived_toggle),
                        icon = DdIcons.Archive,
                        onClick = { archived = !archived },
                        trailing = {
                            DdSwitch(
                                checked = archived,
                                onCheckedChange = { archived = it },
                                modifier = Modifier.testTag("memory_block_archived_toggle"),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(DdSpacing.Xl))
            val savePayload = BlockEditorPayload(
                id = block?.id,
                title = title.trim(),
                purpose = purpose.trim().ifEmpty { title.trim() },
                content = content.trim(),
                existingDomain = initialDomain,
                existingSensitivity = initialSensitivity,
                visibility = visibility,
                pinned = pinned,
                archived = archived,
            )
            val canSave = isBuiltin || title.isNotBlank()
            if (block != null && !isBuiltin) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdTextAction(
                        text = stringResource(R.string.memory_delete),
                        color = colors.danger,
                        onClick = { confirmDelete = true },
                    )
                    Spacer(Modifier.weight(1f))
                    DdPrimaryButton(
                        text = stringResource(R.string.memory_save),
                        enabled = canSave,
                        onClick = { onSave(savePayload) },
                        modifier = Modifier.testTag("memory_block_save"),
                    )
                }
            } else {
                DdPrimaryButton(
                    text = stringResource(R.string.memory_save),
                    modifier = Modifier.fillMaxWidth().testTag("memory_block_save"),
                    enabled = canSave,
                    onClick = { onSave(savePayload) },
                )
            }
        }
    }

    if (confirmDelete && block != null) {
        DdDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.memory_block_delete_title),
            content = {
                DdText(
                    text = stringResource(R.string.memory_block_delete_body),
                    style = DdType.body,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.memory_cancel),
                    onClick = { confirmDelete = false },
                )
                DdTextAction(
                    text = stringResource(R.string.memory_delete),
                    color = colors.danger,
                    onClick = {
                        confirmDelete = false
                        onDelete(block.id)
                    },
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryEventEditorSheet(
    sessionNonce: Int,
    event: MemoryEvent?,
    onDismiss: () -> Unit,
    onSave: (EventEditorPayload) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSourceSession: (String) -> Unit,
    onRequestPurgeSession: (String) -> Unit,
) {
    val colors = superPiColors()
    var content by rememberSaveable(sessionNonce) { mutableStateOf(event?.content.orEmpty()) }
    var kindWire by rememberSaveable(sessionNonce) {
        mutableStateOf((event?.eventKind ?: MemoryEventKind.NOTE).wire)
    }
    val kind = MemoryEventKind.fromWire(kindWire) ?: MemoryEventKind.NOTE
    var tagsText by rememberSaveable(sessionNonce) {
        mutableStateOf(event?.tags?.joinToString(", ").orEmpty())
    }
    var pinned by rememberSaveable(sessionNonce) { mutableStateOf(event?.pinned ?: false) }
    var statusWire by rememberSaveable(sessionNonce) {
        mutableStateOf((event?.status ?: MemoryItemStatus.ACTIVE).wire)
    }
    val status = MemoryItemStatus.fromWire(statusWire)

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(
                if (event == null) R.string.memory_event_add_title else R.string.memory_event_edit_title,
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
            DdTextArea(
                value = content,
                onValueChange = { content = capTextPreservingWhitespace(it, MemoryLimits.MAX_CONTENT_CHARS) },
                placeholder = stringResource(R.string.memory_content_hint),
                minHeight = 112.dp,
                maxHeight = 200.dp,
                testTag = "memory_event_content",
            )
            if (content.length >= EVENT_COUNTER_THRESHOLD) {
                Spacer(Modifier.height(DdSpacing.Xs))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DdText(
                        text = "${content.length} / ${MemoryLimits.MAX_CONTENT_CHARS}",
                        style = DdType.micro,
                        color = if (content.length >= MemoryLimits.MAX_CONTENT_CHARS) colors.danger else colors.inkTertiary,
                    )
                }
            }

            Spacer(Modifier.height(DdSpacing.Lg))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
            ) {
                MemoryEventKind.entries.forEach { candidate ->
                    DdChip(
                        text = stringResource(eventKindLabelRes(candidate)),
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdText(
                    text = stringResource(R.string.memory_pin_title),
                    style = DdType.body,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).padding(end = DdSpacing.Md),
                )
                DdSwitch(
                    checked = pinned,
                    onCheckedChange = { pinned = it },
                )
            }

            if (event != null) {
                Spacer(Modifier.height(DdSpacing.Md))
                val isResolved = status == MemoryItemStatus.RESOLVED
                DdGroupCard {
                    DdListRow(
                        title = stringResource(R.string.memory_event_resolved_toggle),
                        icon = DdIcons.Check,
                        onClick = {
                            val nextStatus = if (isResolved) MemoryItemStatus.ACTIVE else MemoryItemStatus.RESOLVED
                            statusWire = nextStatus.wire
                        },
                        trailing = {
                            DdSwitch(
                                checked = isResolved,
                                onCheckedChange = { checked ->
                                    statusWire = (if (checked) MemoryItemStatus.RESOLVED else MemoryItemStatus.ACTIVE).wire
                                },
                                modifier = Modifier.testTag("memory_event_resolved_toggle"),
                            )
                        },
                    )
                }

                if (!event.sourceQuote.isNullOrBlank() || !event.sourceSessionId.isNullOrBlank()) {
                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdHairline()
                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdSectionLabel(stringResource(R.string.memory_source_label))
                    Spacer(Modifier.height(DdSpacing.Xs))

                    event.sourceQuote?.takeIf { it.isNotBlank() }?.let { quote ->
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
                                text = formatDateTime(event.createdAt),
                                style = DdType.micro,
                                color = colors.inkTertiary,
                            )
                        }
                        Spacer(Modifier.height(DdSpacing.Sm))
                    }

                    event.sourceSessionId?.takeIf { it.isNotBlank() }?.let { sessionId ->
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
            }

            Spacer(Modifier.height(DdSpacing.Xl))
            val savePayload = EventEditorPayload(
                id = event?.id,
                content = content.trim(),
                eventKind = kind,
                tags = parseTagsInput(tagsText),
                pinned = pinned,
                status = status,
            )
            if (event != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdTextAction(
                        text = stringResource(R.string.memory_delete),
                        color = colors.danger,
                        onClick = { onDelete(event.id) },
                    )
                    Spacer(Modifier.weight(1f))
                    DdPrimaryButton(
                        text = stringResource(R.string.memory_save),
                        enabled = content.isNotBlank(),
                        onClick = { onSave(savePayload) },
                        modifier = Modifier.testTag("memory_event_save"),
                    )
                }
            } else {
                DdPrimaryButton(
                    text = stringResource(R.string.memory_save),
                    modifier = Modifier.fillMaxWidth().testTag("memory_event_save"),
                    enabled = content.isNotBlank(),
                    onClick = { onSave(savePayload) },
                )
            }
        }
    }
}

@Composable
internal fun MemoryActivitySheet(
    loading: Boolean,
    entries: List<MemoryAuditEntry>,
    revertingIds: Set<String>,
    onRevert: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    val todayLabel = stringResource(R.string.memory_date_today)
    val yesterdayLabel = stringResource(R.string.memory_date_yesterday)
    val daysAgoFormat = stringResource(R.string.memory_date_days_ago)
    val tomorrowLabel = stringResource(R.string.memory_date_tomorrow)
    val inDaysFormat = stringResource(R.string.memory_date_in_days)

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.memory_activity_title),
            onClose = onDismiss,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.Xxl)
                .padding(bottom = DdSpacing.Xxl),
        ) {
            when {
                loading && entries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Xxxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        DelayedLoadingBlock(loading = true)
                    }
                }
                entries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        DdEmptyState(
                            text = stringResource(R.string.memory_activity_empty),
                            icon = DdIcons.Clock,
                        )
                    }
                }
                else -> {
                    DdGroupCard {
                        entries.forEachIndexed { idx, entry ->
                            if (idx > 0) DdRowDivider()
                            val whenText = formatRelativeDate(
                                epochMs = entry.createdAt,
                                today = todayLabel,
                                yesterday = yesterdayLabel,
                                daysAgoFormat = daysAgoFormat,
                                tomorrow = tomorrowLabel,
                                inDaysFormat = inDaysFormat,
                            )
                            val actorText = stringResource(actorLabelRes(entry.actor))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = DdSpacing.RowHorizontal, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    DdText(
                                        text = entry.summaryUserText,
                                        style = DdType.body,
                                        color = colors.ink,
                                    )
                                    entry.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                                        Spacer(Modifier.height(2.dp))
                                        DdText(
                                            text = reason,
                                            style = DdType.bodySmall,
                                            color = colors.inkSecondary,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    DdText(
                                        text = "$actorText · $whenText",
                                        style = DdType.micro,
                                        color = colors.inkTertiary,
                                    )
                                }
                                Spacer(Modifier.width(DdSpacing.Sm))
                                when {
                                    entry.canRevert -> {
                                        DdTextAction(
                                            text = stringResource(R.string.memory_activity_revert),
                                            color = colors.accent,
                                            enabled = entry.id !in revertingIds,
                                            onClick = { onRevert(entry.id) },
                                            modifier = Modifier.testTag("memory_audit_revert_${entry.id}"),
                                        )
                                    }
                                    entry.isReverted -> {
                                        DdText(
                                            text = stringResource(R.string.memory_activity_reverted),
                                            style = DdType.micro,
                                            color = colors.inkTertiary,
                                            modifier = Modifier.padding(horizontal = DdSpacing.Sm),
                                        )
                                    }
                                    else -> {
                                        DdText(
                                            text = stringResource(R.string.memory_activity_system),
                                            style = DdType.micro,
                                            color = colors.inkTertiary,
                                            modifier = Modifier.padding(horizontal = DdSpacing.Sm),
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
}

private fun parseTagsInput(raw: String): List<String> =
    raw.split(',', '，', '#', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MemoryLimits.MAX_TAGS_PER_EVENT)
