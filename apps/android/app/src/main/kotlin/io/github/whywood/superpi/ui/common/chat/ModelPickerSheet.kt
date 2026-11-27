package io.github.whywood.superpi.ui.common.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdDropdown
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSheetHeader
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.ThinkingLevel

@Composable
internal fun ModelPickerSheet(
    providerId: String,
    modelId: String,
    configured: Boolean,
    providers: List<ProviderSummary>,
    models: List<ModelOption>,
    thinkingLevel: ThinkingLevel,
    thinkingLevels: List<ThinkingLevel>,
    onDismiss: () -> Unit,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onThinkingLevelChange: (ThinkingLevel) -> Unit,
    onManageProviders: () -> Unit,
    manageProvidersVisible: Boolean = true,
    loading: Boolean = false,
) {
    val colors = superPiColors()
    var showProviders by remember { mutableStateOf(false) }
    val showThinkingRow = !loading && configured && thinkingLevels.any { it != ThinkingLevel.OFF }
    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.assistant_model_sheet),
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = DdSpacing.Xxl),
        ) {
            Spacer(Modifier.height(DdSpacing.Xs))
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = DdSpacing.Xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    DdSpinner(size = 20.dp, strokeWidth = 2.dp)
                }
            } else {
                if (providers.isNotEmpty() || showThinkingRow) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(DdShapes.Large)
                            .background(colors.wash.copy(alpha = if (colors.dark) 0.55f else 0.72f))
                            .border(0.5.dp, colors.surfaceRim, DdShapes.Large),
                    ) {
                        if (providers.isNotEmpty()) {
                            val providerName = providers.firstOrNull { it.id == providerId }?.name ?: providerId
                            DdDropdown(
                                expanded = showProviders,
                                onDismissRequest = { showProviders = false },
                                anchor = {
                                    PickerHeaderRow(
                                        title = stringResource(R.string.assistant_model_provider),
                                        value = providerName,
                                        valueMaxWidth = 160.dp,
                                        onClick = { showProviders = true },
                                        modifier = Modifier.testTag("assistant_provider_row"),
                                    )
                                },
                            ) {
                                providers.forEach { provider ->
                                    DdMenuItem(
                                        text = provider.name,
                                        onClick = {
                                            showProviders = false
                                            onProviderChange(provider.id)
                                        },
                                        selected = provider.id == providerId,
                                        trailing = if (provider.id == providerId) {
                                            {
                                                DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 16.dp)
                                            }
                                        } else {
                                            null
                                        },
                                        testTag = "assistant_provider_${provider.id}",
                                    )
                                }
                            }
                        }
                        if (providers.isNotEmpty() && showThinkingRow) {
                            DdRowDivider(leadingInset = DdSpacing.Lg)
                        }
                        if (showThinkingRow) {
                            ThinkingEffortSlider(
                                levels = thinkingLevels,
                                value = thinkingLevel,
                                onChange = onThinkingLevelChange,
                            )
                        }
                    }
                    Spacer(Modifier.height(DdSpacing.Md))
                    DdHairline()
                }
                if (!configured) {
                    DdListRow(
                        title = stringResource(R.string.assistant_model_unconfigured),
                        onClick = null,
                        enabled = false,
                    )
                } else if (models.isEmpty()) {
                    DdListRow(
                        title = stringResource(R.string.assistant_remote_models_empty),
                        onClick = null,
                        enabled = false,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        itemsIndexed(models, key = { _, option -> option.id }) { index, option ->
                            val selected = option.id == modelId
                            DdPressable(
                                onClick = {
                                    onDismiss()
                                    onModelChange(option.id)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                shape = DdShapes.Medium,
                                background = if (selected) colors.wash else Color.Transparent,
                                contentDescription = option.displayName,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .padding(horizontal = DdSpacing.Md, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = option.displayName,
                                        modifier = Modifier.weight(1f),
                                        style = DdType.body,
                                        color = colors.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected) {
                                        Spacer(Modifier.width(DdSpacing.Sm))
                                        DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
                                    }
                                }
                            }
                            if (index != models.lastIndex && !selected && models.getOrNull(index + 1)?.id != modelId) {
                                DdRowDivider(leadingInset = DdSpacing.Md)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(DdSpacing.Md))
        }
        if (manageProvidersVisible) {
            DdRowDivider(Modifier.padding(horizontal = DdSpacing.Xxl))
            Spacer(Modifier.height(DdSpacing.Xs))
            val manageLabel = stringResource(R.string.assistant_model_manage_providers)
            DdPressable(
                onClick = {
                    onDismiss()
                    onManageProviders()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Md),
                shape = DdShapes.Medium,
                contentDescription = manageLabel,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = DdSpacing.Md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = manageLabel,
                        modifier = Modifier.weight(1f),
                        style = DdType.body,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    DdIcon(
                        icon = DdIcons.ChevronRight,
                        tint = colors.inkTertiary.copy(alpha = 0.55f),
                        size = 15.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(DdSpacing.Xl))
    }
}

@Composable
private fun PickerHeaderRow(
    title: String,
    value: String,
    valueMaxWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        contentDescription = "$title, $value",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = DdSpacing.Lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = DdType.body,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DdSpacing.Md))
            Text(
                text = value,
                modifier = Modifier.widthIn(max = valueMaxWidth),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            DdIcon(icon = DdIcons.ChevronDown, tint = colors.inkTertiary, size = 16.dp)
        }
    }
}
