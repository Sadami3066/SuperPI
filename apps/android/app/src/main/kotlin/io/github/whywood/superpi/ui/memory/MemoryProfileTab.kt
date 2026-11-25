package io.github.whywood.superpi.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdExpandToggle
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockProposal

@Composable
internal fun MemoryProfileTab(
    query: String,
    blocks: List<MemoryBlock>,
    archivedBlocks: List<MemoryBlock>,
    proposals: List<MemoryBlockProposal>,
    busyProposalIds: Set<String>,
    onAcceptProposal: (String) -> Unit,
    onDismissProposal: (String) -> Unit,
    onClickBlock: (MemoryBlock) -> Unit,
    onClearFilters: () -> Unit,
) {
    val resources = LocalResources.current
    val trimmedQuery = query.trim()
    val hasQuery = trimmedQuery.isNotEmpty()
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }

    fun matchesBlock(block: MemoryBlock): Boolean {
        if (!hasQuery) return true
        val title = localizedBlockTitle(block, resources)
        val purpose = localizedBlockPurpose(block, resources)
        return title.contains(trimmedQuery, ignoreCase = true) ||
            block.title.contains(trimmedQuery, ignoreCase = true) ||
            purpose.contains(trimmedQuery, ignoreCase = true) ||
            block.content.contains(trimmedQuery, ignoreCase = true)
    }

    val filteredBlocks = remember(blocks, trimmedQuery) { blocks.filter(::matchesBlock) }
    val filteredArchived = remember(archivedBlocks, trimmedQuery) { archivedBlocks.filter(::matchesBlock) }
    val visibleProposals = if (hasQuery) emptyList() else proposals

    if (hasQuery && filteredBlocks.isEmpty() && filteredArchived.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = DdSpacing.Xxl),
            contentAlignment = Alignment.Center,
        ) {
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
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("memory_profile_list"),
        contentPadding = PaddingValues(
            start = DdSpacing.Xxl,
            end = DdSpacing.Xxl,
            top = DdSpacing.Xs,
            bottom = DdSpacing.Xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
    ) {
        if (visibleProposals.isNotEmpty()) {
            item(key = "proposals_header") {
                DdSectionLabel(stringResource(R.string.memory_proposals_section))
            }
            items(visibleProposals, key = { "proposal:${it.id}" }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    busy = proposal.id in busyProposalIds,
                    onAccept = { onAcceptProposal(proposal.id) },
                    onDismiss = { onDismissProposal(proposal.id) },
                )
            }
        }

        items(filteredBlocks, key = { it.id }) { block ->
            ProfileBlockCard(
                block = block,
                displayTitle = localizedBlockTitle(block, resources),
                displayPurpose = localizedBlockPurpose(block, resources),
                onClick = { onClickBlock(block) },
            )
        }

        if (filteredArchived.isNotEmpty()) {
            item(key = "archived_section") {
                val sectionTitle = stringResource(R.string.memory_archived_blocks, filteredArchived.size)
                DdGroupCard {
                    DdExpandToggle(
                        expanded = archivedExpanded || hasQuery,
                        collapsedLabel = sectionTitle,
                        expandedLabel = sectionTitle,
                        onToggle = { archivedExpanded = !archivedExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("memory_archived_toggle"),
                    )
                    if (archivedExpanded || hasQuery) {
                        filteredArchived.forEach { archived ->
                            DdRowDivider()
                            ArchivedBlockRow(
                                block = archived,
                                displayTitle = localizedBlockTitle(archived, resources),
                                onClick = { onClickBlock(archived) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: MemoryBlockProposal,
    busy: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    DdGroupCard(modifier = Modifier.testTag("memory_proposal_${proposal.id}")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.RowHorizontal, vertical = DdSpacing.Md),
        ) {
            DdText(
                text = proposal.title,
                style = DdType.label,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (proposal.reason.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = proposal.reason,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (proposal.seedContent.isNotBlank()) {
                Spacer(Modifier.height(DdSpacing.Xs))
                DdText(
                    text = proposal.seedContent,
                    style = DdType.body,
                    color = colors.inkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(DdSpacing.Xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextAction(
                    text = stringResource(R.string.memory_proposal_dismiss),
                    color = colors.inkSecondary,
                    enabled = !busy,
                    onClick = onDismiss,
                    modifier = Modifier.testTag("memory_proposal_dismiss_${proposal.id}"),
                )
                DdTextAction(
                    text = stringResource(R.string.memory_proposal_accept),
                    color = colors.accent,
                    enabled = !busy,
                    onClick = onAccept,
                    modifier = Modifier.testTag("memory_proposal_accept_${proposal.id}"),
                )
            }
        }
    }
}

@Composable
private fun ProfileBlockCard(
    block: MemoryBlock,
    displayTitle: String,
    displayPurpose: String,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val visibility = blockVisibilityOf(block.domain, block.sensitivity)
    val hasContent = block.content.isNotBlank()
    DdGroupCard(modifier = Modifier.testTag("memory_block_${block.slug}")) {
        DdPressable(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = DdShapes.Card,
            contentDescription = displayTitle,
        ) {
            if (!hasContent) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DdSpacing.RowHorizontal, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdText(
                        text = displayTitle,
                        modifier = Modifier.weight(1f),
                        style = DdType.label,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (visibility != BlockVisibility.PERSONAL_ONLY) {
                        Spacer(Modifier.width(DdSpacing.Sm))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.wash)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            DdText(
                                text = stringResource(blockVisibilityLabelRes(visibility)),
                                style = DdType.micro,
                                color = colors.inkSecondary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    if (block.pinned) {
                        Spacer(Modifier.width(DdSpacing.Sm))
                        DdIcon(
                            icon = DdIcons.Pin,
                            tint = colors.accent,
                            size = 15.dp,
                            contentDescription = stringResource(R.string.memory_pinned_cd),
                        )
                    }
                    Spacer(Modifier.width(DdSpacing.Sm))
                    DdText(
                        text = stringResource(R.string.memory_empty_pending_hint),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(4.dp))
                    DdIcon(
                        icon = DdIcons.ChevronRight,
                        tint = colors.inkTertiary,
                        size = 16.dp,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DdSpacing.RowHorizontal, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdText(
                            text = displayTitle,
                            modifier = Modifier.weight(1f),
                            style = DdType.label,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (visibility != BlockVisibility.PERSONAL_ONLY) {
                            Spacer(Modifier.width(DdSpacing.Sm))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.wash)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                DdText(
                                    text = stringResource(blockVisibilityLabelRes(visibility)),
                                    style = DdType.micro,
                                    color = colors.inkSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        if (block.pinned) {
                            Spacer(Modifier.width(DdSpacing.Sm))
                            DdIcon(
                                icon = DdIcons.Pin,
                                tint = colors.accent,
                                size = 15.dp,
                                contentDescription = stringResource(R.string.memory_pinned_cd),
                            )
                        }
                    }
                    if (displayPurpose.isNotBlank() && !displayPurpose.equals(displayTitle, ignoreCase = true)) {
                        Spacer(Modifier.height(2.dp))
                        DdText(
                            text = displayPurpose,
                            style = DdType.micro,
                            color = colors.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    DdText(
                        text = block.content,
                        style = DdType.body,
                        color = colors.inkSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedBlockRow(
    block: MemoryBlock,
    displayTitle: String,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = DdShapes.Row,
        contentDescription = displayTitle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.RowHorizontal, vertical = 12.dp),
        ) {
            DdText(
                text = displayTitle,
                style = DdType.body,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (block.content.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                DdText(
                    text = block.content,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
