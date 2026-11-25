package io.github.whywood.superpi.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSearchField
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSheetHeader
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.ui.common.ProviderIcon

private val ProviderDividerInset = 54.dp

@Composable
fun ProviderListScreen(
    providers: List<ProviderSummary>,
    customProviderIds: Set<String>,
    configuredProviderIds: Set<String>,
    onBack: () -> Unit,
    onAddCustomProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    defaultModel: ModelConfig = ModelConfig(),
    modelsByProvider: Map<String, List<ModelOption>> = emptyMap(),
    onSelectDefaultModel: (String, String) -> Unit = { _, _ -> },
) {
    val colors = superPiColors()
    var query by rememberSaveable { mutableStateOf("") }
    var defaultModelSheetOpen by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(providers, query) {
        val keyword = query.trim()
        providers
            .filter {
                keyword.isEmpty() ||
                    it.name.contains(keyword, ignoreCase = true) ||
                    it.id.contains(keyword, ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
    }
    val customProviders = remember(filtered, customProviderIds) {
        filtered.filter { it.id in customProviderIds }
    }
    val builtinProviders = remember(filtered, customProviderIds) {
        filtered.filterNot { it.id in customProviderIds }
    }
    val configuredBuiltin = remember(builtinProviders, configuredProviderIds) {
        builtinProviders.filter { it.id in configuredProviderIds }
    }
    val remainingBuiltin = remember(builtinProviders, configuredProviderIds) {
        builtinProviders.filterNot { it.id in configuredProviderIds }
    }
    val countLabel = stringResource(
        if (query.isBlank()) {
            R.string.settings_provider_all_count
        } else {
            R.string.settings_provider_result_count
        },
        filtered.size,
    )

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
        },
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = DdSpacing.ScreenHorizontal,
                end = DdSpacing.ScreenHorizontal,
                bottom = DdSpacing.Huge,
            ),
        ) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_provider_title),
                        style = DdType.pageTitle,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.settings_provider_search),
                        clearContentDescription = stringResource(R.string.search_clear),
                        fieldTestTag = "provider_search_field",
                    )
                    Spacer(Modifier.height(DdSpacing.Lg))
                }
            }
            if (configuredProviderIds.isNotEmpty() && query.isBlank()) {
                item(key = "default_model_section") {
                    val activeProvider = remember(providers, defaultModel.providerId) {
                        providers.firstOrNull { it.id == defaultModel.providerId }
                    }
                    val activeModel = remember(modelsByProvider, defaultModel) {
                        modelsByProvider[defaultModel.providerId].orEmpty().firstOrNull { it.id == defaultModel.modelId }
                    }
                    val modelLabel = activeModel?.displayName?.ifBlank { activeModel.id }
                        ?: defaultModel.modelId.ifBlank { stringResource(R.string.settings_account_no_model) }
                    val providerLabel = activeProvider?.name ?: defaultModel.providerId
                    Column(Modifier.fillMaxWidth()) {
                        DdSectionLabel(
                            stringResource(R.string.settings_provider_default_model_section),
                            Modifier.padding(start = DdSpacing.Xs),
                        )
                        Spacer(Modifier.height(10.dp))
                        DdGroupCard {
                            DdPressable(
                                onClick = { defaultModelSheetOpen = true },
                                modifier = Modifier.fillMaxWidth().testTag("provider_default_model_card"),
                                shape = RectangleShape,
                                contentDescription = "$modelLabel, $providerLabel",
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 68.dp)
                                        .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Md),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProviderIcon(defaultModel.providerId, providerLabel)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = modelLabel,
                                            style = DdType.body,
                                            color = colors.ink,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = providerLabel,
                                            style = DdType.bodySmall,
                                            color = colors.inkTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 16.dp)
                                }
                            }
                        }
                        Spacer(Modifier.height(DdSpacing.Lg))
                    }
                }
            }
            item(key = "custom_section") {
                Column(Modifier.fillMaxWidth()) {
                    DdSectionLabel(
                        stringResource(R.string.settings_provider_custom_section),
                        Modifier.padding(start = DdSpacing.Xs),
                    )
                    Spacer(Modifier.height(10.dp))
                    DdGroupCard {
                        DdListRow(
                            title = stringResource(R.string.settings_provider_add_custom),
                            icon = DdIcons.Plus,
                            onClick = onAddCustomProvider,
                            showChevron = true,
                        )
                        if (customProviders.isNotEmpty()) {
                            DdRowDivider(leadingInset = ProviderDividerInset)
                            customProviders.forEachIndexed { index, provider ->
                                ProviderRow(
                                    provider = provider,
                                    configured = provider.id in configuredProviderIds,
                                    onClick = { onProviderClick(provider.id) },
                                )
                                if (index != customProviders.lastIndex) {
                                    DdRowDivider(leadingInset = ProviderDividerInset)
                                }
                            }
                        }
                    }
                }
            }
            if (configuredBuiltin.isNotEmpty()) {
                item(key = "configured_section") {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(DdSpacing.Lg))
                        DdSectionLabel(
                            stringResource(R.string.settings_provider_configured_section),
                            Modifier.padding(start = DdSpacing.Xs),
                        )
                        Spacer(Modifier.height(10.dp))
                        DdGroupCard {
                            configuredBuiltin.forEachIndexed { index, provider ->
                                ProviderRow(
                                    provider = provider,
                                    configured = true,
                                    onClick = { onProviderClick(provider.id) },
                                )
                                if (index != configuredBuiltin.lastIndex) {
                                    DdRowDivider(leadingInset = ProviderDividerInset)
                                }
                            }
                        }
                    }
                }
            }
            if (remainingBuiltin.isNotEmpty() || filtered.isEmpty()) {
                item(key = "all_section") {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(DdSpacing.Lg))
                        DdSectionLabel(countLabel, Modifier.padding(start = DdSpacing.Xs))
                        Spacer(Modifier.height(10.dp))
                        DdGroupCard {
                            if (remainingBuiltin.isEmpty() && configuredBuiltin.isEmpty()) {
                                DdEmptyState(
                                    text = stringResource(R.string.settings_provider_no_result),
                                    modifier = Modifier.padding(vertical = DdSpacing.Xl),
                                )
                            } else {
                                remainingBuiltin.forEachIndexed { index, provider ->
                                    ProviderRow(
                                        provider = provider,
                                        configured = false,
                                        onClick = { onProviderClick(provider.id) },
                                    )
                                    if (index != remainingBuiltin.lastIndex) {
                                        DdRowDivider(leadingInset = ProviderDividerInset)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (defaultModelSheetOpen) {
        val configuredProviders = remember(providers, configuredProviderIds) {
            providers.filter { it.id in configuredProviderIds }.sortedBy { it.name.lowercase() }
        }
        DefaultModelPickerSheet(
            providers = configuredProviders,
            modelsByProvider = modelsByProvider,
            defaultModel = defaultModel,
            onSelect = { providerId, modelId ->
                defaultModelSheetOpen = false
                onSelectDefaultModel(providerId, modelId)
            },
            onDismiss = { defaultModelSheetOpen = false },
        )
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderSummary,
    configured: Boolean,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        contentDescription = provider.name,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = DdSpacing.Lg, vertical = DdSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(provider.id, provider.name)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = provider.name,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.settings_provider_models_count, provider.modelCount),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (configured) {
                Spacer(Modifier.width(DdSpacing.Md))
                StatusPill(stringResource(R.string.settings_status_configured), active = true)
            }
            Spacer(Modifier.width(6.dp))
            DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 16.dp)
        }
    }
}

@Composable
private fun DefaultModelPickerSheet(
    providers: List<ProviderSummary>,
    modelsByProvider: Map<String, List<ModelOption>>,
    defaultModel: ModelConfig,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    var search by remember { mutableStateOf("") }
    val cleanQuery = search.trim()

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.settings_provider_default_model_section),
            onClose = onDismiss,
        )
        DdSearchField(
            query = search,
            onQueryChange = { search = it },
            placeholder = stringResource(R.string.settings_provider_model_search),
            clearContentDescription = stringResource(R.string.search_clear),
            modifier = Modifier.padding(horizontal = DdSpacing.Xxl),
            fieldTestTag = "provider_default_model_search",
        )
        Spacer(Modifier.height(DdSpacing.Md))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.Xxl)
                .padding(bottom = DdSpacing.Xxl),
        ) {
            var renderedAny = false
            providers.forEach { provider ->
                val allModels = modelsByProvider[provider.id].orEmpty()
                val filteredModels = if (cleanQuery.isEmpty()) {
                    allModels
                } else {
                    allModels.filter { model ->
                        model.displayName.contains(cleanQuery, ignoreCase = true) ||
                            model.id.contains(cleanQuery, ignoreCase = true) ||
                            provider.name.contains(cleanQuery, ignoreCase = true)
                    }
                }
                if (filteredModels.isEmpty()) return@forEach
                if (renderedAny) Spacer(Modifier.height(DdSpacing.Lg))
                renderedAny = true
                DdSectionLabel(provider.name, Modifier.padding(start = DdSpacing.Xs))
                Spacer(Modifier.height(DdSpacing.Xs))
                DdGroupCard {
                    filteredModels.forEachIndexed { idx, model ->
                        if (idx > 0) DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        val isSelected = defaultModel.providerId == provider.id && defaultModel.modelId == model.id
                        DdListRow(
                            title = model.displayName.ifBlank { model.id },
                            onClick = { onSelect(provider.id, model.id) },
                            trailing = if (isSelected) {
                                {
                                    DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag("provider_default_pick_${provider.id}_${model.id}"),
                        )
                    }
                }
            }
            if (!renderedAny) {
                DdEmptyState(
                    text = stringResource(R.string.settings_provider_no_model_result),
                    modifier = Modifier.padding(vertical = DdSpacing.Xl),
                )
            }
        }
    }
}
