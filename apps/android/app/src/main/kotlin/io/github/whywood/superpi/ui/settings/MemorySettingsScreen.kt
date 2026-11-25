package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSearchField
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSegmented
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSheetHeader
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.MemoryInjectionBudget
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderSummary

private enum class MemoryModelTarget {
    HOT,
    COLD,
}

private val IDLE_DEBOUNCE_OPTIONS_SECONDS = listOf(30, 60, 180, 300)
private val COLD_CONSOLIDATION_HOUR_OPTIONS = listOf(6, 12, 24, 0)
private const val INJECTION_UNLIMITED_TICK =
    MemorySettings.INJECTION_MAX_CHARS + MemorySettings.INJECTION_STEP_CHARS
private val PROFILE_QUOTA_STOPS = listOf(
    null,
    MemorySettings.SECTION_DISABLED,
    2_000,
    4_000,
    6_000,
    8_000,
    10_000,
    12_000,
    16_000,
    24_000,
    32_000,
    48_000,
    MemorySettings.INJECTION_UNLIMITED,
)
private val RELATIONS_QUOTA_STOPS = listOf(
    null,
    MemorySettings.SECTION_DISABLED,
    1_000,
    2_000,
    3_000,
    4_000,
    6_000,
    8_000,
    12_000,
    16_000,
    MemorySettings.INJECTION_UNLIMITED,
)
private val EVENTS_QUOTA_STOPS = listOf(
    null,
    MemorySettings.SECTION_DISABLED,
    1_000,
    2_000,
    3_000,
    4_000,
    6_000,
    8_000,
    12_000,
    16_000,
    MemorySettings.INJECTION_UNLIMITED,
)
private val DIRECTORY_QUOTA_STOPS = listOf(
    null,
    MemorySettings.SECTION_DISABLED,
    500,
    1_000,
    1_500,
    2_000,
    3_000,
    4_000,
    6_000,
    8_000,
    MemorySettings.INJECTION_UNLIMITED,
)

@Composable
fun MemorySettingsScreen(
    settings: MemorySettings,
    configuredProviders: List<ProviderSummary>,
    modelsByProvider: Map<String, List<ModelOption>>,
    autoHotModelLabel: String?,
    autoColdModelLabel: String?,
    guidanceSet: Boolean,
    pendingDispatchJobs: Int,
    retryingQueue: Boolean,
    onSaveSettings: (MemorySettings) -> Unit,
    onOpenGuidance: () -> Unit,
    onRetryPendingJobs: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    var pickerTarget by remember { mutableStateOf<MemoryModelTarget?>(null) }
    var debounceMenuOpen by remember { mutableStateOf(false) }
    var coldCycleMenuOpen by remember { mutableStateOf(false) }
    var quotasSheetOpen by remember { mutableStateOf(false) }

    val modes = listOf(
        MemoryModelMode.AUTO,
        MemoryModelMode.FIXED,
        MemoryModelMode.PAUSED,
    )
    val selectedModeIndex = modes.indexOf(settings.mode).coerceAtLeast(0)
    val noneConfiguredLabel = stringResource(R.string.memory_settings_model_none)
    val autoFallbackLabel = stringResource(R.string.memory_settings_model_auto_fallback)

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
        modifier = modifier.testTag("memory_settings"),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.ScreenHorizontal),
        ) {
            Text(
                text = stringResource(R.string.memory_settings_title),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))

            DdSegmented(
                options = listOf(
                    stringResource(R.string.memory_settings_mode_auto),
                    stringResource(R.string.memory_settings_mode_fixed),
                    stringResource(R.string.memory_settings_mode_paused),
                ),
                selectedIndex = selectedModeIndex,
                onSelect = { idx ->
                    val chosen = modes.getOrElse(idx) { MemoryModelMode.AUTO }
                    if (chosen != settings.mode) {
                        onSaveSettings(settings.copy(mode = chosen))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                testTags = listOf(
                    "memory_settings_mode_auto",
                    "memory_settings_mode_fixed",
                    "memory_settings_mode_paused",
                ),
            )

            if (settings.mode != MemoryModelMode.PAUSED) {
                Spacer(Modifier.height(24.dp))
                DdSectionLabel(
                    stringResource(R.string.memory_settings_section_models),
                    Modifier.padding(start = DdSpacing.Xs),
                )
                Spacer(Modifier.height(DdSpacing.Sm))
                val isFixed = settings.mode == MemoryModelMode.FIXED
                val hotValue = if (isFixed) {
                    formatModelLabel(
                        providerId = settings.effectiveHotProviderId(),
                        modelId = settings.effectiveHotModelId(),
                        configuredProviders = configuredProviders,
                        modelsByProvider = modelsByProvider,
                    ) ?: autoFallbackLabel
                } else {
                    autoHotModelLabel ?: noneConfiguredLabel
                }
                val coldValue = if (isFixed) {
                    formatModelLabel(
                        providerId = settings.effectiveColdProviderId(),
                        modelId = settings.effectiveColdModelId(),
                        configuredProviders = configuredProviders,
                        modelsByProvider = modelsByProvider,
                    ) ?: autoFallbackLabel
                } else {
                    autoColdModelLabel ?: noneConfiguredLabel
                }
                DdGroupCard {
                    DdListRow(
                        title = stringResource(R.string.memory_settings_hot_model),
                        value = hotValue,
                        onClick = if (isFixed) {
                            { pickerTarget = MemoryModelTarget.HOT }
                        } else {
                            null
                        },
                        showChevron = isFixed,
                        modifier = Modifier.testTag("memory_settings_hot_model"),
                    )
                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    DdListRow(
                        title = stringResource(R.string.memory_settings_cold_model),
                        value = coldValue,
                        onClick = if (isFixed) {
                            { pickerTarget = MemoryModelTarget.COLD }
                        } else {
                            null
                        },
                        showChevron = isFixed,
                        modifier = Modifier.testTag("memory_settings_cold_model"),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            DdSectionLabel(
                stringResource(R.string.memory_settings_section_guidance),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            val injectionSliderTick = if (settings.injectionMaxChars <= 0) {
                INJECTION_UNLIMITED_TICK
            } else {
                MemorySettings.normalizeInjectionMaxChars(settings.injectionMaxChars)
            }
            DdGroupCard {
                DdListRow(
                    icon = DdIcons.File,
                    title = stringResource(R.string.memory_settings_guidance),
                    value = stringResource(
                        if (guidanceSet) {
                            R.string.workspace_settings_agents_set
                        } else {
                            R.string.workspace_settings_agents_unset
                        },
                    ),
                    onClick = onOpenGuidance,
                    showChevron = true,
                    modifier = Modifier.testTag("memory_settings_guidance"),
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SteppedIntSliderRow(
                    title = stringResource(R.string.memory_settings_injection_title),
                    subtitle = null,
                    valueText = { tick ->
                        if (tick >= INJECTION_UNLIMITED_TICK) {
                            stringResource(R.string.memory_settings_injection_unlimited)
                        } else {
                            stringResource(R.string.memory_settings_injection_chars, tick)
                        }
                    },
                    value = injectionSliderTick,
                    min = MemorySettings.INJECTION_MIN_CHARS,
                    max = INJECTION_UNLIMITED_TICK,
                    stepSize = MemorySettings.INJECTION_STEP_CHARS,
                    normalize = { raw ->
                        val clamped = raw.coerceIn(MemorySettings.INJECTION_MIN_CHARS, INJECTION_UNLIMITED_TICK)
                        val step = MemorySettings.INJECTION_STEP_CHARS
                        val rounded = ((clamped + step / 2) / step) * step
                        rounded.coerceIn(MemorySettings.INJECTION_MIN_CHARS, INJECTION_UNLIMITED_TICK)
                    },
                    onChange = { tick ->
                        val persisted = if (tick >= INJECTION_UNLIMITED_TICK) {
                            MemorySettings.INJECTION_UNLIMITED
                        } else {
                            tick
                        }
                        onSaveSettings(settings.copy(injectionMaxChars = persisted))
                    },
                    sliderTag = "memory_settings_injection_budget",
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                DdListRow(
                    title = stringResource(R.string.memory_settings_quotas_title),
                    value = stringResource(
                        if (settings.hasCustomSectionBudgets()) {
                            R.string.memory_settings_quotas_custom
                        } else {
                            R.string.memory_settings_quotas_auto
                        },
                    ),
                    onClick = { quotasSheetOpen = true },
                    showChevron = true,
                    modifier = Modifier.testTag("memory_settings_quotas"),
                )
            }

            if (settings.mode != MemoryModelMode.PAUSED) {
                Spacer(Modifier.height(24.dp))
                DdSectionLabel(
                    stringResource(R.string.memory_settings_section_agent),
                    Modifier.padding(start = DdSpacing.Xs),
                )
                Spacer(Modifier.height(DdSpacing.Sm))
                DdGroupCard {
                    val selectedDebounce = closestOption(settings.hotIdleDebounceSeconds, IDLE_DEBOUNCE_OPTIONS_SECONDS)
                    SettingsDropdownRow(
                        title = stringResource(R.string.memory_settings_debounce_title),
                        value = formatDebounceLabel(selectedDebounce),
                        expanded = debounceMenuOpen,
                        onExpandedChange = { debounceMenuOpen = it },
                        options = IDLE_DEBOUNCE_OPTIONS_SECONDS,
                        selected = selectedDebounce,
                        optionLabel = { secs -> formatDebounceLabel(secs) },
                        onSelect = { secs -> onSaveSettings(settings.copy(hotIdleDebounceSeconds = secs)) },
                        rowTag = "memory_settings_debounce",
                        optionTag = { secs -> "memory_settings_debounce_$secs" },
                    )
                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    val selectedCycle = closestOption(settings.coldConsolidationHours, COLD_CONSOLIDATION_HOUR_OPTIONS)
                    SettingsDropdownRow(
                        title = stringResource(R.string.memory_settings_cold_interval_title),
                        value = formatColdIntervalLabel(selectedCycle),
                        expanded = coldCycleMenuOpen,
                        onExpandedChange = { coldCycleMenuOpen = it },
                        options = COLD_CONSOLIDATION_HOUR_OPTIONS,
                        selected = selectedCycle,
                        optionLabel = { hours -> formatColdIntervalLabel(hours) },
                        onSelect = { hours -> onSaveSettings(settings.copy(coldConsolidationHours = hours)) },
                        rowTag = "memory_settings_cold_interval",
                        optionTag = { hours -> "memory_settings_cold_interval_$hours" },
                    )
                }

                if (pendingDispatchJobs > 0) {
                    Spacer(Modifier.height(DdSpacing.Lg))
                    DdGroupCard {
                        DdListRow(
                            title = stringResource(R.string.memory_settings_pending_jobs, pendingDispatchJobs),
                            modifier = Modifier.testTag("memory_settings_pending_row"),
                            trailing = {
                                DdTextAction(
                                    text = stringResource(R.string.memory_retry),
                                    color = colors.accent,
                                    enabled = !retryingQueue,
                                    onClick = onRetryPendingJobs,
                                    modifier = Modifier.testTag("memory_settings_retry_queue"),
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(DdSpacing.Huge))
        }
    }

    if (quotasSheetOpen) {
        MemoryInjectionQuotasSheet(
            settings = settings,
            onSaveSettings = onSaveSettings,
            onDismiss = { quotasSheetOpen = false },
        )
    }

    pickerTarget?.let { target ->
        val currentProvider = if (target == MemoryModelTarget.HOT) {
            settings.effectiveHotProviderId()
        } else {
            settings.effectiveColdProviderId()
        }
        val currentModel = if (target == MemoryModelTarget.HOT) {
            settings.effectiveHotModelId()
        } else {
            settings.effectiveColdModelId()
        }
        val sheetTitle = stringResource(
            if (target == MemoryModelTarget.HOT) {
                R.string.memory_settings_hot_model
            } else {
                R.string.memory_settings_cold_model
            },
        )
        MemoryModelPickerSheet(
            title = sheetTitle,
            configuredProviders = configuredProviders,
            modelsByProvider = modelsByProvider,
            selectedProviderId = currentProvider,
            selectedModelId = currentModel,
            onSelect = { providerId, modelId ->
                pickerTarget = null
                val next = if (target == MemoryModelTarget.HOT) {
                    settings.copy(
                        mode = MemoryModelMode.FIXED,
                        fixedProviderId = null,
                        fixedModelId = null,
                        hotProviderId = providerId,
                        hotModelId = modelId,
                        coldProviderId = settings.effectiveColdProviderId(),
                        coldModelId = settings.effectiveColdModelId(),
                    )
                } else {
                    settings.copy(
                        mode = MemoryModelMode.FIXED,
                        fixedProviderId = null,
                        fixedModelId = null,
                        hotProviderId = settings.effectiveHotProviderId(),
                        hotModelId = settings.effectiveHotModelId(),
                        coldProviderId = providerId,
                        coldModelId = modelId,
                    )
                }
                onSaveSettings(next)
            },
            onDismiss = { pickerTarget = null },
        )
    }
}

@Composable
private fun MemoryModelPickerSheet(
    title: String,
    configuredProviders: List<ProviderSummary>,
    modelsByProvider: Map<String, List<ModelOption>>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelect: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    var query by remember { mutableStateOf("") }
    val cleanQuery = query.trim()

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = title,
            onClose = onDismiss,
        )
        DdSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.memory_settings_model_search),
            clearContentDescription = stringResource(R.string.memory_search_clear),
            modifier = Modifier.padding(horizontal = DdSpacing.Xxl),
            fieldTestTag = "memory_model_picker_search",
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
            if (cleanQuery.isEmpty()) {
                val autoSelected = selectedProviderId.isNullOrBlank() || selectedModelId.isNullOrBlank()
                DdGroupCard {
                    DdListRow(
                        title = stringResource(R.string.memory_settings_model_auto_fallback),
                        onClick = { onSelect(null, null) },
                        trailing = {
                            if (autoSelected) {
                                DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
                            }
                        },
                        modifier = Modifier.testTag("memory_model_picker_auto"),
                    )
                }
                Spacer(Modifier.height(DdSpacing.Lg))
            }

            if (configuredProviders.isEmpty()) {
                DdText(
                    text = stringResource(R.string.memory_settings_model_none),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            } else {
                var renderedAny = false
                configuredProviders.forEach { provider ->
                    val allModels = modelsByProvider[provider.id].orEmpty()
                    val filteredModels = if (cleanQuery.isEmpty()) {
                        allModels
                    } else {
                        allModels.filter { m ->
                            m.displayName.contains(cleanQuery, ignoreCase = true) ||
                                m.id.contains(cleanQuery, ignoreCase = true) ||
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
                            val isSelected = selectedProviderId == provider.id && selectedModelId == model.id
                            DdListRow(
                                title = model.displayName.ifBlank { model.id },
                                onClick = { onSelect(provider.id, model.id) },
                                trailing = {
                                    if (isSelected) {
                                        DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
                                    }
                                },
                                modifier = Modifier.testTag("memory_model_pick_${provider.id}_${model.id}"),
                            )
                        }
                    }
                }
                if (!renderedAny) {
                    DdText(
                        text = stringResource(R.string.memory_empty_filtered),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                }
            }
        }
    }
}

private fun formatModelLabel(
    providerId: String?,
    modelId: String?,
    configuredProviders: List<ProviderSummary>,
    modelsByProvider: Map<String, List<ModelOption>>,
): String? {
    val pId = providerId?.takeIf { it.isNotBlank() } ?: return null
    val mId = modelId?.takeIf { it.isNotBlank() } ?: return null
    val providerName = configuredProviders.firstOrNull { it.id == pId }?.name ?: pId
    val modelName = modelsByProvider[pId].orEmpty().firstOrNull { it.id == mId }?.displayName ?: mId
    return "$providerName · $modelName"
}

private fun closestOption(current: Int, options: List<Int>): Int =
    if (current in options) current else options.minByOrNull { kotlin.math.abs(it - current) } ?: options.first()

@Composable
private fun formatDebounceLabel(seconds: Int): String =
    if (seconds < 60) {
        stringResource(R.string.memory_settings_seconds, seconds)
    } else {
        stringResource(R.string.memory_settings_minutes, seconds / 60)
    }

@Composable
private fun MemoryInjectionQuotasSheet(
    settings: MemorySettings,
    onSaveSettings: (MemorySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    val total = settings.injectionMaxChars
    val autoProfile = MemoryInjectionBudget.autoSectionChars(
        MemoryInjectionBudget.DEFAULT_PERSONAL_PROFILE_CHARS,
        total,
        MemoryInjectionBudget.MIN_AUTO_PROFILE_CHARS,
    )
    val autoRelations = MemoryInjectionBudget.autoSectionChars(
        MemoryInjectionBudget.DEFAULT_PERSONAL_RELATIONS_CHARS,
        total,
        MemoryInjectionBudget.MIN_AUTO_RELATIONS_CHARS,
    )
    val autoEvents = MemoryInjectionBudget.autoSectionChars(
        MemoryInjectionBudget.DEFAULT_PERSONAL_EVENTS_CHARS,
        total,
        MemoryInjectionBudget.MIN_AUTO_EVENTS_CHARS,
    )
    val autoDirectory = MemoryInjectionBudget.autoSectionChars(
        MemoryInjectionBudget.DEFAULT_PERSONAL_DIRECTORY_CHARS,
        total,
        MemoryInjectionBudget.MIN_AUTO_DIRECTORY_CHARS,
    )

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.memory_settings_quotas_title),
            onClose = onDismiss,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.Xxl)
                .padding(bottom = DdSpacing.Xxl),
        ) {
            DdGroupCard {
                SectionQuotaSliderRow(
                    title = stringResource(R.string.memory_settings_quota_profile),
                    currentValue = settings.profileBudgetChars,
                    autoPreviewChars = autoProfile,
                    stops = PROFILE_QUOTA_STOPS,
                    sliderTag = "memory_quota_profile",
                    onChange = { next -> onSaveSettings(settings.copy(profileBudgetChars = next)) },
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SectionQuotaSliderRow(
                    title = stringResource(R.string.memory_settings_quota_relations),
                    currentValue = settings.relationsBudgetChars,
                    autoPreviewChars = autoRelations,
                    stops = RELATIONS_QUOTA_STOPS,
                    sliderTag = "memory_quota_relations",
                    onChange = { next -> onSaveSettings(settings.copy(relationsBudgetChars = next)) },
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SectionQuotaSliderRow(
                    title = stringResource(R.string.memory_settings_quota_events),
                    currentValue = settings.eventsBudgetChars,
                    autoPreviewChars = autoEvents,
                    stops = EVENTS_QUOTA_STOPS,
                    sliderTag = "memory_quota_events",
                    onChange = { next -> onSaveSettings(settings.copy(eventsBudgetChars = next)) },
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SectionQuotaSliderRow(
                    title = stringResource(R.string.memory_settings_quota_directory),
                    currentValue = settings.directoryBudgetChars,
                    autoPreviewChars = autoDirectory,
                    stops = DIRECTORY_QUOTA_STOPS,
                    sliderTag = "memory_quota_directory",
                    onChange = { next -> onSaveSettings(settings.copy(directoryBudgetChars = next)) },
                )
            }
            if (settings.hasCustomSectionBudgets()) {
                Spacer(Modifier.height(DdSpacing.Md))
                DdTextAction(
                    text = stringResource(R.string.memory_settings_quotas_reset),
                    color = colors.accent,
                    onClick = {
                        onSaveSettings(
                            settings.copy(
                                profileBudgetChars = null,
                                relationsBudgetChars = null,
                                eventsBudgetChars = null,
                                directoryBudgetChars = null,
                            ),
                        )
                    },
                    modifier = Modifier.testTag("memory_quotas_reset"),
                )
            }
        }
    }
}

@Composable
private fun SectionQuotaSliderRow(
    title: String,
    currentValue: Int?,
    autoPreviewChars: Int,
    stops: List<Int?>,
    sliderTag: String,
    onChange: (Int?) -> Unit,
) {
    val selectedIndex = resolveStopIndex(currentValue, stops)
    SteppedIntSliderRow(
        title = title,
        subtitle = null,
        valueText = { index ->
            when (val stop = stops.getOrNull(index)) {
                null -> {
                    val autoStr = if (autoPreviewChars <= 0) {
                        stringResource(R.string.memory_settings_injection_unlimited)
                    } else {
                        stringResource(R.string.memory_settings_injection_chars, autoPreviewChars)
                    }
                    stringResource(R.string.memory_settings_quota_auto_value, autoStr)
                }
                MemorySettings.SECTION_DISABLED -> stringResource(R.string.memory_settings_quota_disabled)
                MemorySettings.INJECTION_UNLIMITED -> stringResource(R.string.memory_settings_injection_unlimited)
                else -> stringResource(R.string.memory_settings_injection_chars, stop)
            }
        },
        value = selectedIndex,
        min = 0,
        max = stops.lastIndex,
        stepSize = 1,
        normalize = { it.coerceIn(0, stops.lastIndex) },
        onChange = { index -> onChange(stops[index]) },
        sliderTag = sliderTag,
    )
}

private fun resolveStopIndex(value: Int?, stops: List<Int?>): Int {
    if (value == null) return 0
    val exact = stops.indexOf(value)
    if (exact >= 0) return exact
    if (value <= MemorySettings.SECTION_DISABLED) {
        return stops.indexOf(MemorySettings.SECTION_DISABLED).coerceAtLeast(0)
    }
    if (value == MemorySettings.INJECTION_UNLIMITED) {
        return stops.indexOf(MemorySettings.INJECTION_UNLIMITED).coerceAtLeast(0)
    }
    return stops.withIndex()
        .filter { (_, candidate) -> candidate != null && candidate > 0 }
        .minByOrNull { (_, candidate) -> kotlin.math.abs((candidate ?: 0) - value) }
        ?.index ?: 0
}

@Composable
private fun formatColdIntervalLabel(hours: Int): String =
    if (hours <= 0) {
        stringResource(R.string.memory_settings_manual_only)
    } else {
        stringResource(R.string.memory_settings_every_hours, hours)
    }
