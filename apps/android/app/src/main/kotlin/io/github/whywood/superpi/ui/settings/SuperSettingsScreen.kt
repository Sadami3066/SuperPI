package io.github.whywood.superpi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_MAX
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_MIN
import io.github.whywood.superpi.data.SUPER_COALESCE_WINDOW_OPTIONS_MS
import io.github.whywood.superpi.data.SUPER_CONCURRENCY_MAX
import io.github.whywood.superpi.data.SUPER_CONCURRENCY_MIN
import io.github.whywood.superpi.data.SUPER_IDLE_EVICT_OPTIONS_MINUTES
import io.github.whywood.superpi.data.SUPER_QUESTION_TIMEOUT_OPTIONS_MINUTES
import io.github.whywood.superpi.data.SUPER_RECEIPT_CHARS_OPTIONS
import io.github.whywood.superpi.data.SUPER_SLOTS_MAX
import io.github.whywood.superpi.data.SUPER_SLOTS_MIN
import io.github.whywood.superpi.data.SUPER_STALL_TIMEOUT_OPTIONS_MINUTES
import io.github.whywood.superpi.data.UiPreferences
import io.github.whywood.superpi.data.normalizeCompactionTriggerPercent
import io.github.whywood.superpi.data.normalizeSuperConcurrency
import io.github.whywood.superpi.data.normalizeSuperSlotsPerBot
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdExpandToggle
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors

@Composable
fun SuperSettingsScreen(
    preferences: UiPreferences,
    onMaxConcurrentWorkersChange: (Int) -> Unit,
    onMaxSlotsPerBotChange: (Int) -> Unit,
    onStallTimeoutMinutesChange: (Int) -> Unit,
    onQuestionTimeoutMinutesChange: (Int) -> Unit,
    onWorkerJobWakeChange: (Boolean) -> Unit,
    onCoalesceWindowMsChange: (Long) -> Unit,
    onReceiptMaxCharsChange: (Int) -> Unit,
    onIdleEvictMinutesChange: (Int) -> Unit,
    onWorkerCompactionOverrideChange: (Boolean) -> Unit,
    onWorkerCompactionPercentChange: (Int) -> Unit,
    onResetDefaults: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenInstructions: (() -> Unit)? = null,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    var stallMenu by remember { mutableStateOf(false) }
    var questionTimeoutMenu by remember { mutableStateOf(false) }
    var coalesceMenu by remember { mutableStateOf(false) }
    var receiptCharsMenu by remember { mutableStateOf(false) }
    var idleEvictMenu by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var resetConfirmOpen by remember { mutableStateOf(false) }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.weight(1f))
            DdTextAction(
                text = stringResource(R.string.super_settings_reset),
                onClick = { resetConfirmOpen = true },
                color = colors.inkSecondary,
                modifier = Modifier.testTag("super_settings_reset"),
            )
        },
        modifier = modifier.testTag("super_settings"),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.ScreenHorizontal),
        ) {
            Text(
                text = stringResource(R.string.super_settings_title),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))

            if (onOpenInstructions != null) {
                DdGroupCard {
                    DdListRow(
                        icon = DdIcons.Pen,
                        title = stringResource(R.string.super_settings_instructions),
                        subtitle = stringResource(R.string.super_settings_instructions_subtitle),
                        onClick = onOpenInstructions,
                        showChevron = true,
                        modifier = Modifier.testTag("super_settings_instructions"),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            DdSectionLabel(
                stringResource(R.string.super_settings_section_concurrency),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                SteppedIntSliderRow(
                    title = stringResource(R.string.super_settings_concurrency_title),
                    subtitle = null,
                    valueText = { v -> stringResource(R.string.super_settings_concurrency_value, v) },
                    value = preferences.superMaxConcurrentWorkers,
                    min = SUPER_CONCURRENCY_MIN,
                    max = SUPER_CONCURRENCY_MAX,
                    stepSize = 1,
                    normalize = ::normalizeSuperConcurrency,
                    onChange = onMaxConcurrentWorkersChange,
                    sliderTag = "super_concurrency_slider",
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SteppedIntSliderRow(
                    title = stringResource(R.string.super_settings_slots_title),
                    subtitle = null,
                    valueText = { v -> stringResource(R.string.super_settings_slots_value, v) },
                    value = preferences.superMaxSlotsPerBot,
                    min = SUPER_SLOTS_MIN,
                    max = SUPER_SLOTS_MAX,
                    stepSize = 1,
                    normalize = ::normalizeSuperSlotsPerBot,
                    onChange = onMaxSlotsPerBotChange,
                    sliderTag = "super_slots_slider",
                )
            }

            Spacer(Modifier.height(24.dp))
            DdSectionLabel(
                stringResource(R.string.super_settings_section_context),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                SwitchSettingRow(
                    title = stringResource(R.string.super_settings_job_wake_title),
                    subtitle = null,
                    checked = preferences.superWorkerJobWake,
                    onCheckedChange = onWorkerJobWakeChange,
                    tag = "super_job_wake",
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SwitchSettingRow(
                    title = stringResource(R.string.super_settings_compaction_override_title),
                    subtitle = stringResource(R.string.super_settings_compaction_override_desc),
                    checked = preferences.superWorkerCompactionOverrideEnabled,
                    onCheckedChange = onWorkerCompactionOverrideChange,
                    tag = "super_worker_compaction_override",
                )
                if (preferences.superWorkerCompactionOverrideEnabled) {
                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    SteppedIntSliderRow(
                        title = stringResource(R.string.super_settings_compaction_threshold_title),
                        subtitle = null,
                        valueText = { p -> stringResource(R.string.super_settings_compaction_threshold_value, p) },
                        value = preferences.superWorkerCompactionPercent,
                        min = COMPACTION_TRIGGER_MIN,
                        max = COMPACTION_TRIGGER_MAX,
                        stepSize = 5,
                        normalize = ::normalizeCompactionTriggerPercent,
                        onChange = onWorkerCompactionPercentChange,
                        sliderTag = "super_worker_compaction_slider",
                    )
                }
            }

            Spacer(Modifier.height(DdSpacing.Lg))
            DdGroupCard {
                DdExpandToggle(
                    expanded = advancedExpanded,
                    collapsedLabel = stringResource(R.string.super_settings_advanced_expand),
                    expandedLabel = stringResource(R.string.super_settings_advanced_collapse),
                    onToggle = { advancedExpanded = !advancedExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("super_advanced_toggle"),
                )
                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = if (motionEnabled) {
                        fadeIn(tween(DdMotion.FAST)) + expandVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
                    } else {
                        fadeIn(snap()) + expandVertically(snap())
                    },
                    exit = if (motionEnabled) {
                        fadeOut(tween(DdMotion.FAST)) + shrinkVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
                    } else {
                        fadeOut(snap()) + shrinkVertically(snap())
                    },
                ) {
                    Column {
                        DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        SettingsDropdownRow(
                            title = stringResource(R.string.super_settings_stall_title),
                            subtitle = stringResource(R.string.super_settings_stall_desc),
                            value = formatStallTimeoutLabel(preferences.superStallTimeoutMinutes),
                            expanded = stallMenu,
                            onExpandedChange = { stallMenu = it },
                            options = SUPER_STALL_TIMEOUT_OPTIONS_MINUTES,
                            selected = preferences.superStallTimeoutMinutes,
                            optionLabel = { mins -> formatStallTimeoutLabel(mins) },
                            onSelect = onStallTimeoutMinutesChange,
                            rowTag = "super_stall_timeout",
                            optionTag = { mins -> "super_stall_$mins" },
                        )
                        DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        SettingsDropdownRow(
                            title = stringResource(R.string.super_settings_question_timeout_title),
                            subtitle = stringResource(R.string.super_settings_question_timeout_desc),
                            value = formatQuestionTimeoutLabel(preferences.superQuestionTimeoutMinutes),
                            expanded = questionTimeoutMenu,
                            onExpandedChange = { questionTimeoutMenu = it },
                            options = SUPER_QUESTION_TIMEOUT_OPTIONS_MINUTES,
                            selected = preferences.superQuestionTimeoutMinutes,
                            optionLabel = { mins -> formatQuestionTimeoutLabel(mins) },
                            onSelect = onQuestionTimeoutMinutesChange,
                            rowTag = "super_question_timeout",
                            optionTag = { mins -> "super_question_timeout_$mins" },
                        )
                        DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        SettingsDropdownRow(
                            title = stringResource(R.string.super_settings_coalesce_title),
                            subtitle = stringResource(R.string.super_settings_coalesce_desc),
                            value = formatCoalesceWindowLabel(preferences.superCoalesceWindowMs),
                            expanded = coalesceMenu,
                            onExpandedChange = { coalesceMenu = it },
                            options = SUPER_COALESCE_WINDOW_OPTIONS_MS,
                            selected = preferences.superCoalesceWindowMs,
                            optionLabel = { ms -> formatCoalesceWindowLabel(ms) },
                            onSelect = onCoalesceWindowMsChange,
                            rowTag = "super_coalesce_window",
                            optionTag = { ms -> "super_coalesce_$ms" },
                        )
                        DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        SettingsDropdownRow(
                            title = stringResource(R.string.super_settings_receipt_chars_title),
                            subtitle = null,
                            value = stringResource(R.string.super_settings_chars_count, preferences.superReceiptMaxChars),
                            expanded = receiptCharsMenu,
                            onExpandedChange = { receiptCharsMenu = it },
                            options = SUPER_RECEIPT_CHARS_OPTIONS,
                            selected = preferences.superReceiptMaxChars,
                            optionLabel = { chars -> stringResource(R.string.super_settings_chars_count, chars) },
                            onSelect = onReceiptMaxCharsChange,
                            rowTag = "super_receipt_chars",
                            optionTag = { chars -> "super_receipt_chars_$chars" },
                        )
                        DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        SettingsDropdownRow(
                            title = stringResource(R.string.super_settings_idle_evict_title),
                            subtitle = stringResource(R.string.super_settings_idle_evict_desc),
                            value = stringResource(R.string.super_settings_minutes, preferences.superIdleEvictMinutes),
                            expanded = idleEvictMenu,
                            onExpandedChange = { idleEvictMenu = it },
                            options = SUPER_IDLE_EVICT_OPTIONS_MINUTES,
                            selected = preferences.superIdleEvictMinutes,
                            optionLabel = { mins -> stringResource(R.string.super_settings_minutes, mins) },
                            onSelect = onIdleEvictMinutesChange,
                            rowTag = "super_idle_evict",
                            optionTag = { mins -> "super_idle_evict_$mins" },
                        )
                    }
                }
            }
            Spacer(Modifier.height(DdSpacing.Huge))
        }
    }

    if (resetConfirmOpen) {
        DdDialog(
            onDismissRequest = { resetConfirmOpen = false },
            title = stringResource(R.string.super_settings_reset_title),
            content = {
                DdText(
                    text = stringResource(R.string.super_settings_reset_body),
                    style = DdType.body,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.super_worker_cancel),
                    onClick = { resetConfirmOpen = false },
                )
                DdTextAction(
                    text = stringResource(R.string.super_settings_reset_confirm),
                    color = colors.danger,
                    onClick = {
                        resetConfirmOpen = false
                        onResetDefaults()
                    },
                )
            },
        )
    }
}

@Composable
private fun formatStallTimeoutLabel(minutes: Int): String = if (minutes <= 0) {
    stringResource(R.string.super_settings_off)
} else {
    stringResource(R.string.super_settings_minutes, minutes)
}

@Composable
private fun formatQuestionTimeoutLabel(minutes: Int): String = if (minutes <= 0) {
    stringResource(R.string.super_settings_never_handoff)
} else {
    stringResource(R.string.super_settings_minutes, minutes)
}

@Composable
private fun formatCoalesceWindowLabel(ms: Long): String = when {
    ms <= 0L -> stringResource(R.string.super_settings_immediate)
    ms % 1_000L == 0L -> stringResource(R.string.super_settings_seconds_int, (ms / 1_000L).toInt())
    else -> stringResource(R.string.super_settings_seconds_decimal, ms / 1_000f)
}
