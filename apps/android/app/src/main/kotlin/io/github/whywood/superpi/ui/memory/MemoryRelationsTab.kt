package io.github.whywood.superpi.ui.memory

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
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
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityDetail
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.memory.MemoryLimits
import io.github.whywood.superpi.ui.common.DelayedLoadingBlock
import kotlinx.coroutines.launch

@Composable
internal fun MemoryRelationsTab(
    query: String,
    entities: List<MemoryEntity>,
    relations: List<MemoryEntityRelation>,
    filter: RelationFilter,
    onSelectFilter: (RelationFilter) -> Unit,
    onClickEntity: (MemoryEntity) -> Unit,
    onClearFilters: () -> Unit,
) {
    val trimmedQuery = query.trim()
    val hasQuery = trimmedQuery.isNotEmpty()
    val hasFilters = hasQuery || filter != RelationFilter.ALL

    val relationsByEntity = remember(relations) {
        val map = LinkedHashMap<String, MutableList<MemoryEntityRelation>>()
        relations.forEach { rel ->
            map.getOrPut(rel.fromEntityId) { ArrayList() }.add(rel)
            if (rel.toEntityId != rel.fromEntityId) {
                map.getOrPut(rel.toEntityId) { ArrayList() }.add(rel)
            }
        }
        map
    }

    val filtered = remember(entities, relationsByEntity, filter, trimmedQuery) {
        entities.filter { entity ->
            val categoryMatch = when (filter) {
                RelationFilter.ALL -> true
                RelationFilter.PEOPLE -> entity.entityType.uppercase() in PEOPLE_TYPES
                RelationFilter.PLACES -> entity.entityType.uppercase() == MemoryEntityTypes.PLACE
                RelationFilter.PROJECTS -> entity.entityType.uppercase() !in PEOPLE_TYPES &&
                    entity.entityType.uppercase() != MemoryEntityTypes.PLACE
            }
            if (!categoryMatch) return@filter false
            if (!hasQuery) return@filter true
            val entityRelations = relationsByEntity[entity.id].orEmpty()
            entity.canonicalName.contains(trimmedQuery, ignoreCase = true) ||
                entity.aliases.any { it.contains(trimmedQuery, ignoreCase = true) } ||
                entity.summary?.contains(trimmedQuery, ignoreCase = true) == true ||
                entity.attributes.any { (k, v) ->
                    k.contains(trimmedQuery, ignoreCase = true) || v.contains(trimmedQuery, ignoreCase = true)
                } ||
                entityRelations.any { rel ->
                    rel.fromEntityName.contains(trimmedQuery, ignoreCase = true) ||
                        rel.toEntityName.contains(trimmedQuery, ignoreCase = true) ||
                        (rel.label ?: rel.relationType).contains(trimmedQuery, ignoreCase = true)
                }
        }
    }

    Column(Modifier.fillMaxSize()) {
        RelationFilterRow(selected = filter, onSelect = onSelectFilter)
        Spacer(Modifier.height(DdSpacing.Xs))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
                        text = stringResource(R.string.memory_rel_empty),
                        icon = DdIcons.Brain,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("memory_relations_list"),
                contentPadding = PaddingValues(top = DdSpacing.Xs, bottom = DdSpacing.Xxxl),
            ) {
                items(filtered, key = { it.id }) { entity ->
                    Column(Modifier.animateItem()) {
                        EntityListRow(
                            entity = entity,
                            relations = relationsByEntity[entity.id].orEmpty(),
                            onClick = { onClickEntity(entity) },
                        )
                        DdHairline(Modifier.padding(horizontal = DdSpacing.Xxl))
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationFilterRow(
    selected: RelationFilter,
    onSelect: (RelationFilter) -> Unit,
) {
    val items = listOf(
        RelationFilter.ALL to R.string.memory_rel_filter_all,
        RelationFilter.PEOPLE to R.string.memory_rel_filter_people,
        RelationFilter.PLACES to R.string.memory_rel_filter_places,
        RelationFilter.PROJECTS to R.string.memory_rel_filter_projects,
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = DdSpacing.Xxl),
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        items(items, key = { (filter, _) -> filter.name }) { (filter, labelRes) ->
            DdChip(
                text = stringResource(labelRes),
                selected = selected == filter,
                onClick = { onSelect(filter) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntityListRow(
    entity: MemoryEntity,
    relations: List<MemoryEntityRelation>,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = DdShapes.Row,
        contentDescription = entity.canonicalName,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Xxl, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdText(
                    text = entity.canonicalName,
                    modifier = Modifier.weight(1f),
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(DdSpacing.Sm))
                DdText(
                    text = stringResource(entityTypeLabelRes(entity.entityType)),
                    style = DdType.micro,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
                if (entity.pinned) {
                    Spacer(Modifier.width(DdSpacing.Xs))
                    DdIcon(
                        icon = DdIcons.Pin,
                        tint = colors.accent,
                        size = 15.dp,
                        contentDescription = stringResource(R.string.memory_pinned_cd),
                    )
                }
            }
            if (!entity.summary.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                DdText(
                    text = entity.summary.orEmpty(),
                    style = DdType.body,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val previews = relations.take(2)
            if (previews.isNotEmpty()) {
                val resources = LocalResources.current
                Spacer(Modifier.height(6.dp))
                previews.forEach { rel ->
                    val other = if (rel.fromEntityId == entity.id) rel.toEntityName else rel.fromEntityName
                    val relText = rel.label?.takeIf { it.isNotBlank() }
                        ?: relationTypeLabel(rel.relationType, resources)
                    DdText(
                        text = "$relText · $other",
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entity.attributes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
                ) {
                    entity.attributes.entries.take(3).forEach { (k, v) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.wash)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            DdText(
                                text = "$k · $v",
                                style = DdType.micro,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryEntityDetailSheet(
    loading: Boolean,
    loadError: Boolean,
    detail: MemoryEntityDetail?,
    allEntities: List<MemoryEntity>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSaveEntity: (EntityEditorPayload) -> Unit,
    onAddRelation: suspend (RelationDraftPayload) -> Boolean,
    onInvalidateRelation: suspend (String) -> Boolean,
    onClickEvent: (MemoryEvent) -> Unit,
    onMergeEntity: (String, String) -> Unit,
) {
    val colors = superPiColors()
    val scope = rememberCoroutineScope()
    var mergePickerOpen by remember { mutableStateOf(false) }

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.memory_entity_sheet_title),
            onClose = onDismiss,
        )
        when {
            loading && detail == null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Xxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    DelayedLoadingBlock(loading = true)
                }
            }
            loadError && detail == null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(DdSpacing.Xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    DdEmptyState(
                        text = stringResource(R.string.memory_load_failed),
                        icon = DdIcons.Refresh,
                        action = {
                            DdTextAction(
                                text = stringResource(R.string.memory_retry),
                                onClick = onRetry,
                            )
                        },
                    )
                }
            }
            detail != null -> {
                val entity = detail.entity
                var name by remember(entity.id, entity.revision) { mutableStateOf(entity.canonicalName) }
                var entityType by remember(entity.id, entity.revision) { mutableStateOf(entity.entityType) }
                var aliasesText by remember(entity.id, entity.revision) { mutableStateOf(entity.aliases.joinToString(", ")) }
                var summary by remember(entity.id, entity.revision) { mutableStateOf(entity.summary.orEmpty()) }
                var pinned by remember(entity.id, entity.revision) { mutableStateOf(entity.pinned) }
                var relFormOpen by remember(entity.id) { mutableStateOf(false) }
                var relLabel by remember(entity.id) { mutableStateOf("") }
                var relTarget by remember(entity.id) { mutableStateOf("") }
                var addingRelation by remember(entity.id) { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DdSpacing.Xxl)
                        .padding(bottom = DdSpacing.Xxl),
                ) {
                    DdSectionLabel(stringResource(R.string.memory_entity_name_label))
                    Spacer(Modifier.height(DdSpacing.Xs))
                    DdTextField(
                        value = name,
                        onValueChange = { name = capTextPreservingWhitespace(it, MemoryLimits.MAX_ENTITY_NAME_CHARS) },
                        placeholder = stringResource(R.string.memory_entity_name_label),
                        testTag = "memory_entity_name",
                    )

                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdSectionLabel(stringResource(R.string.memory_entity_type_label))
                    Spacer(Modifier.height(DdSpacing.Xs))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
                    ) {
                        ENTITY_TYPE_OPTIONS.forEach { candidate ->
                            DdChip(
                                text = stringResource(entityTypeLabelRes(candidate)),
                                selected = entityType.equals(candidate, ignoreCase = true),
                                onClick = { entityType = candidate },
                            )
                        }
                    }

                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdSectionLabel(stringResource(R.string.memory_entity_aliases_label))
                    Spacer(Modifier.height(DdSpacing.Xs))
                    DdTextField(
                        value = aliasesText,
                        onValueChange = { aliasesText = it },
                        placeholder = stringResource(R.string.memory_entity_aliases_hint),
                    )

                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdSectionLabel(stringResource(R.string.memory_entity_summary_label))
                    Spacer(Modifier.height(DdSpacing.Xs))
                    DdTextArea(
                        value = summary,
                        onValueChange = { summary = capTextPreservingWhitespace(it, MemoryLimits.MAX_SUMMARY_CHARS) },
                        placeholder = stringResource(R.string.memory_entity_summary_hint),
                        minHeight = 88.dp,
                        maxHeight = 160.dp,
                    )

                    Spacer(Modifier.height(DdSpacing.Md))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdText(
                            text = stringResource(R.string.memory_entity_pin_title),
                            modifier = Modifier.weight(1f),
                            style = DdType.body,
                            color = colors.ink,
                        )
                        DdSwitch(checked = pinned, onCheckedChange = { pinned = it })
                    }

                    Spacer(Modifier.height(DdSpacing.Md))
                    DdPrimaryButton(
                        text = stringResource(R.string.memory_save),
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val parsedAliases = aliasesText.split(',', '，')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .take(MemoryLimits.MAX_ALIASES_PER_ENTITY)
                            onSaveEntity(
                                EntityEditorPayload(
                                    id = entity.id,
                                    canonicalName = name.trim(),
                                    entityType = entityType,
                                    aliases = parsedAliases,
                                    summary = summary.trim().takeIf { it.isNotEmpty() },
                                    clearSummary = summary.isBlank(),
                                    pinned = pinned,
                                ),
                            )
                        },
                    )

                    Spacer(Modifier.height(DdSpacing.Xl))
                    DdHairline()
                    Spacer(Modifier.height(DdSpacing.Lg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdSectionLabel(
                            text = stringResource(R.string.memory_entity_relations_label),
                            modifier = Modifier.weight(1f),
                        )
                        DdTextAction(
                            text = stringResource(R.string.memory_entity_add_relation),
                            onClick = { relFormOpen = !relFormOpen },
                        )
                    }

                    if (relFormOpen) {
                        Spacer(Modifier.height(DdSpacing.Sm))
                        DdTextField(
                            value = relLabel,
                            onValueChange = { relLabel = it },
                            placeholder = stringResource(R.string.memory_entity_rel_label_hint),
                        )
                        Spacer(Modifier.height(DdSpacing.Xs))
                        DdTextField(
                            value = relTarget,
                            onValueChange = { relTarget = it },
                            placeholder = stringResource(R.string.memory_entity_rel_target_hint),
                        )
                        Spacer(Modifier.height(DdSpacing.Xs))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            DdTextAction(
                                text = stringResource(R.string.memory_entity_rel_add_confirm),
                                enabled = !addingRelation && relLabel.isNotBlank() && relTarget.isNotBlank(),
                                onClick = {
                                    addingRelation = true
                                    scope.launch {
                                        val ok = onAddRelation(
                                            RelationDraftPayload(
                                                fromName = entity.canonicalName,
                                                relationLabel = relLabel.trim(),
                                                toName = relTarget.trim(),
                                            ),
                                        )
                                        if (ok) {
                                            relLabel = ""
                                            relTarget = ""
                                            relFormOpen = false
                                        }
                                        addingRelation = false
                                    }
                                },
                            )
                        }
                    }

                    val allRelations = detail.outgoingRelations + detail.incomingRelations
                    if (allRelations.isEmpty()) {
                        Spacer(Modifier.height(DdSpacing.Xs))
                        DdText(
                            text = stringResource(R.string.memory_entity_relations_empty),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                        )
                    } else {
                        val resources = LocalResources.current
                        val relationScope = rememberCoroutineScope()
                        var busyRelationIds by remember(entity.id) { mutableStateOf<Set<String>>(emptySet()) }
                        Spacer(Modifier.height(DdSpacing.Xs))
                        DdGroupCard {
                            allRelations.forEachIndexed { idx, rel ->
                                if (idx > 0) DdRowDivider()
                                val relLabel = rel.label?.takeIf { it.isNotBlank() }
                                    ?: relationTypeLabel(rel.relationType, resources)
                                val title = "${rel.fromEntityName} · $relLabel · ${rel.toEntityName}"
                                val statusText = if (rel.isActive) null else stringResource(R.string.memory_status_superseded)
                                DdListRow(
                                    title = title,
                                    value = statusText,
                                    trailing = if (rel.isActive) {
                                        {
                                            val isBusy = rel.id in busyRelationIds
                                            DdIconButton(
                                                icon = DdIcons.Trash,
                                                contentDescription = stringResource(R.string.memory_rel_remove_cd),
                                                enabled = !isBusy,
                                                iconSize = 18.dp,
                                                tint = colors.inkTertiary,
                                                onClick = {
                                                    if (rel.id !in busyRelationIds) {
                                                        busyRelationIds = busyRelationIds + rel.id
                                                        relationScope.launch {
                                                            try {
                                                                onInvalidateRelation(rel.id)
                                                            } finally {
                                                                busyRelationIds = busyRelationIds - rel.id
                                                            }
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }

                    if (detail.linkedEvents.isNotEmpty()) {
                        Spacer(Modifier.height(DdSpacing.Lg))
                        DdSectionLabel(stringResource(R.string.memory_entity_events_label))
                        Spacer(Modifier.height(DdSpacing.Xs))
                        DdGroupCard {
                            detail.linkedEvents.forEachIndexed { idx, ev ->
                                if (idx > 0) DdRowDivider()
                                DdListRow(
                                    title = ev.content,
                                    subtitle = stringResource(eventKindLabelRes(ev.eventKind)),
                                    showChevron = true,
                                    onClick = { onClickEvent(ev) },
                                )
                            }
                        }
                    }

                    val mergeCandidates = remember(allEntities, entity.id) {
                        allEntities.filter { it.id != entity.id }
                    }
                    if (mergeCandidates.isNotEmpty()) {
                        Spacer(Modifier.height(DdSpacing.Lg))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            DdTextAction(
                                text = stringResource(R.string.memory_entity_merge_action),
                                color = colors.inkSecondary,
                                onClick = { mergePickerOpen = true },
                            )
                        }
                    }

                    if (mergePickerOpen) {
                        var mergeQuery by remember(mergePickerOpen, entity.id) { mutableStateOf("") }
                        val filteredMergeCandidates = remember(mergeCandidates, mergeQuery) {
                            val normalized = mergeQuery.trim().lowercase()
                            if (normalized.isEmpty()) {
                                mergeCandidates
                            } else {
                                mergeCandidates.filter { candidate ->
                                    candidate.canonicalName.lowercase().contains(normalized) ||
                                        candidate.aliases.any { it.lowercase().contains(normalized) }
                                }
                            }
                        }
                        DdDialog(
                            onDismissRequest = { mergePickerOpen = false },
                            title = stringResource(R.string.memory_entity_merge_title),
                            content = {
                                Column(Modifier.fillMaxWidth()) {
                                    DdText(
                                        text = stringResource(R.string.memory_entity_merge_body),
                                        style = DdType.bodySmall,
                                        color = colors.inkSecondary,
                                    )
                                    if (mergeCandidates.size > 6) {
                                        Spacer(Modifier.height(DdSpacing.Sm))
                                        DdSearchField(
                                            query = mergeQuery,
                                            onQueryChange = { mergeQuery = it },
                                            placeholder = stringResource(R.string.memory_search_placeholder),
                                            clearContentDescription = stringResource(R.string.memory_search_clear),
                                        )
                                    }
                                    Spacer(Modifier.height(DdSpacing.Sm))
                                    if (filteredMergeCandidates.isEmpty()) {
                                        DdText(
                                            text = stringResource(R.string.memory_empty_filtered),
                                            style = DdType.bodySmall,
                                            color = colors.inkTertiary,
                                            modifier = Modifier.padding(vertical = DdSpacing.Sm),
                                        )
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 320.dp)
                                                .verticalScroll(rememberScrollState()),
                                        ) {
                                            filteredMergeCandidates.forEachIndexed { idx, other ->
                                                if (idx > 0) DdHairline()
                                                DdListRow(
                                                    title = other.canonicalName,
                                                    subtitle = stringResource(entityTypeLabelRes(other.entityType)),
                                                    onClick = {
                                                        mergePickerOpen = false
                                                        onMergeEntity(entity.id, other.id)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            actions = {
                                DdTextAction(
                                    text = stringResource(R.string.memory_cancel),
                                    onClick = { mergePickerOpen = false },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private val PEOPLE_TYPES = setOf(
    MemoryEntityTypes.PERSON,
    MemoryEntityTypes.FAMILY,
    MemoryEntityTypes.FRIEND,
    MemoryEntityTypes.COLLEAGUE,
)

private val ENTITY_TYPE_OPTIONS = listOf(
    MemoryEntityTypes.PERSON,
    MemoryEntityTypes.FAMILY,
    MemoryEntityTypes.FRIEND,
    MemoryEntityTypes.COLLEAGUE,
    MemoryEntityTypes.PLACE,
    MemoryEntityTypes.ORGANIZATION,
    MemoryEntityTypes.PROJECT,
    MemoryEntityTypes.PET,
    MemoryEntityTypes.TOPIC,
    MemoryEntityTypes.OTHER,
)
