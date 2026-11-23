package io.github.whywood.superpi.ui.miniapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSearchField
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.MiniappCreation
import io.github.whywood.superpi.runtime.MiniappInvalid
import io.github.whywood.superpi.runtime.MiniappStore
import io.github.whywood.superpi.ui.common.DelayedLoadingBlock
import kotlinx.coroutines.launch

private const val ICON_FALLBACK = "✦"

@Composable
fun MiniappScreen(
    store: MiniappStore,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(store) { MiniappStateHolder(store, scope) }
    LaunchedEffect(holder) { holder.load() }
    val state by holder.state.collectAsStateWithLifecycle()

    MiniappContent(
        state = state,
        onBack = onBack,
        onOpen = onOpen,
        onCreate = onCreate,
        onQueryChange = holder::setQuery,
        onRefresh = { scope.launch { holder.refresh() } },
        onRetry = holder::retry,
        onDelete = { id -> scope.launch { holder.delete(id) } },
        modifier = modifier,
    )
}

@Composable
internal fun MiniappContent(
    state: MiniappUiState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    var invalidTarget by remember { mutableStateOf<MiniappInvalid?>(null) }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.miniapp_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            Text(
                text = stringResource(R.string.miniapp_title),
                modifier = Modifier.weight(1f),
                style = DdType.heading,
                color = colors.ink,
            )
            DdIconButton(
                icon = DdIcons.Refresh,
                onClick = onRefresh,
                contentDescription = stringResource(R.string.miniapp_refresh),
                modifier = Modifier.testTag("miniapp_refresh"),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            DdIconButton(
                icon = DdIcons.Plus,
                onClick = onCreate,
                contentDescription = stringResource(R.string.miniapp_new),
                modifier = Modifier.testTag("miniapp_new"),
                tint = colors.ink,
                iconSize = 21.dp,
            )
        },
        modifier = modifier.testTag("miniapp_screen"),
    ) {
        DdSearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.miniapp_search_placeholder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Sm),
            clearContentDescription = stringResource(R.string.search_clear),
            fieldTestTag = "miniapp_search_field",
            clearTestTag = "miniapp_search_clear",
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = DdSpacing.Lg,
                end = DdSpacing.Lg,
                top = DdSpacing.Sm,
                bottom = DdSpacing.Xxxl,
            ),
        ) {
            state.error?.let { error ->
                item(key = "error") {
                    MiniappErrorRow(
                        text = stringResource(error.messageRes),
                        onRetry = if (error.retryable) onRetry else null,
                    )
                }
            }
            when {
                state.loading && state.isEmpty -> item(key = "loading") {
                    DelayedLoadingBlock(
                        loading = true,
                        modifier = Modifier.padding(top = 96.dp),
                    )
                }

                state.error != null && state.isEmpty -> Unit

                state.isEmpty -> item(key = "empty") {
                    MiniappEmpty(onCreate = onCreate)
                }

                state.filtered.creations.isEmpty() && state.filtered.invalid.isEmpty() -> item(key = "search_empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_no_match),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                        )
                    }
                }

                else -> {
                    val creations = state.filtered.creations
                    val invalid = state.filtered.invalid
                    itemsIndexed(creations, key = { _, creation -> creation.manifest.id }) { index, creation ->
                        Column {
                            if (index > 0) DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                            MiniappAppRow(
                                creation = creation,
                                onClick = { onOpen(creation.manifest.id) },
                            )
                        }
                    }
                    itemsIndexed(invalid, key = { _, item -> "invalid_${item.id}" }) { index, item ->
                        Column {
                            if (index > 0 || creations.isNotEmpty()) DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                            MiniappRow(
                                title = item.id,
                                subtitle = invalidSubtitle(item),
                                icon = null,
                                showChevron = false,
                                onClick = { invalidTarget = item },
                                modifier = Modifier.testTag("miniapp_invalid_${item.id}"),
                            )
                        }
                    }
                }
            }
        }
    }

    invalidTarget?.let { target ->
        MiniappInvalidDialog(
            id = target.id,
            reasonText = stringResource(invalidReasonLabelRes(target.reason)),
            onDismiss = { invalidTarget = null },
            onDelete = {
                invalidTarget = null
                onDelete(target.id)
            },
        )
    }
}

@Composable
private fun MiniappErrorRow(
    text: String,
    onRetry: (() -> Unit)? = null,
) {
    val colors = superPiColors()
    Row(
        Modifier.fillMaxWidth().padding(top = DdSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.danger,
        )
        if (onRetry != null) {
            DdTextAction(
                text = stringResource(R.string.miniapp_retry),
                onClick = onRetry,
                modifier = Modifier.testTag("miniapp_retry"),
            )
        }
    }
}

@Composable
private fun MiniappEmpty(onCreate: () -> Unit) {
    val colors = superPiColors()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DdIcon(icon = DdIcons.Apps, tint = colors.inkTertiary, size = 28.dp)
        Spacer(Modifier.height(DdSpacing.Lg))
        Text(
            text = stringResource(R.string.miniapp_empty_title),
            style = DdType.label,
            color = colors.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.miniapp_empty_hint),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DdSpacing.Md))
        DdTextAction(
            text = stringResource(R.string.miniapp_new),
            onClick = onCreate,
            color = colors.accent,
            modifier = Modifier.testTag("miniapp_empty_new"),
        )
    }
}

@Composable
private fun MiniappAppRow(
    creation: MiniappCreation,
    onClick: () -> Unit,
) {
    val manifest = creation.manifest
    MiniappRow(
        title = manifest.name,
        subtitle = manifest.description.trim().ifBlank { null },
        icon = manifest.icon.ifBlank { ICON_FALLBACK },
        showChevron = true,
        onClick = onClick,
        modifier = Modifier.testTag("miniapp_row_${manifest.id}"),
    )
}

@Composable
private fun MiniappRow(
    title: String,
    subtitle: String?,
    icon: String?,
    showChevron: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = DdShapes.Row,
        contentDescription = title,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(vertical = DdSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                if (icon.isBlank() || icon == ICON_FALLBACK) {
                    DdIcon(icon = DdIcons.Apps, tint = colors.inkSecondary, size = 20.dp)
                } else {
                    Text(text = icon, fontSize = 20.sp)
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 16.dp)
            }
        }
    }
}

@Composable
private fun invalidSubtitle(item: MiniappInvalid): String =
    "${stringResource(R.string.miniapp_invalid)} · ${stringResource(invalidReasonLabelRes(item.reason))}"

internal data class MiniappFilterResult(
    val creations: List<MiniappCreation>,
    val invalid: List<MiniappInvalid>,
)

internal fun filterMiniapp(
    creations: List<MiniappCreation>,
    invalid: List<MiniappInvalid>,
    query: String,
): MiniappFilterResult {
    val normalized = query.trim()
    if (normalized.isEmpty()) return MiniappFilterResult(creations, invalid)
    return MiniappFilterResult(
        creations = creations.filter { creation ->
            val manifest = creation.manifest
            manifest.name.contains(normalized, ignoreCase = true) ||
                manifest.id.contains(normalized, ignoreCase = true) ||
                manifest.description.contains(normalized, ignoreCase = true)
        },
        invalid = invalid.filter { it.id.contains(normalized, ignoreCase = true) },
    )
}
