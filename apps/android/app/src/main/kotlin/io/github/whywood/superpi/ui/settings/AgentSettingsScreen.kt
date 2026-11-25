package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_DEFAULT
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_MAX
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_MIN
import io.github.whywood.superpi.data.SessionNamingMode
import io.github.whywood.superpi.data.normalizeCompactionTriggerPercent
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.ModelOption

@Composable
fun AgentSettingsScreen(
    onOpenWebSearch: () -> Unit,
    compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
    onCompactionTriggerPercentChange: (Int) -> Unit = {},
    metadataMode: SessionNamingMode,
    metadataModelLabel: String?,
    metadataModels: List<ModelOption>,
    onMetadataModeChange: (SessionNamingMode) -> Unit,
    onMetadataModelChange: (ModelOption) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    var modeMenu by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
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
        modifier = modifier.testTag("agent_settings"),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.ScreenHorizontal),
        ) {
            Text(
                text = stringResource(R.string.agent_settings_title),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))
            DdSectionLabel(
                stringResource(R.string.agent_section_capabilities),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                DdListRow(
                    title = stringResource(R.string.agent_web_search),
                    value = stringResource(R.string.agent_web_search_value),
                    onClick = onOpenWebSearch,
                    showChevron = true,
                    modifier = Modifier.testTag("agent_web_search"),
                )
            }
            Spacer(Modifier.height(24.dp))
            DdSectionLabel(
                stringResource(R.string.agent_section_context),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                SteppedIntSliderRow(
                    title = stringResource(R.string.agent_compaction_threshold_title),
                    subtitle = stringResource(R.string.agent_compaction_threshold_summary),
                    valueText = { p -> stringResource(R.string.agent_compaction_threshold_value, p) },
                    value = compactionTriggerPercent,
                    min = COMPACTION_TRIGGER_MIN,
                    max = COMPACTION_TRIGGER_MAX,
                    stepSize = 5,
                    normalize = ::normalizeCompactionTriggerPercent,
                    onChange = onCompactionTriggerPercentChange,
                    sliderTag = "agent_compaction_threshold_slider",
                )
                DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                SettingsDropdownRow(
                    title = stringResource(R.string.agent_metadata_title),
                    subtitle = stringResource(R.string.agent_metadata_summary),
                    value = stringResource(
                        when (metadataMode) {
                            SessionNamingMode.AUTO -> R.string.agent_metadata_auto
                            SessionNamingMode.MANUAL -> R.string.agent_metadata_manual
                        },
                    ),
                    expanded = modeMenu,
                    onExpandedChange = { modeMenu = it },
                    options = listOf(SessionNamingMode.AUTO, SessionNamingMode.MANUAL),
                    selected = metadataMode,
                    optionLabel = { mode ->
                        stringResource(
                            when (mode) {
                                SessionNamingMode.AUTO -> R.string.agent_metadata_auto
                                SessionNamingMode.MANUAL -> R.string.agent_metadata_manual
                            },
                        )
                    },
                    onSelect = onMetadataModeChange,
                    rowTag = "agent_metadata_mode",
                    optionTag = { mode ->
                        when (mode) {
                            SessionNamingMode.AUTO -> "agent_metadata_auto"
                            SessionNamingMode.MANUAL -> "agent_metadata_manual"
                        }
                    },
                )
                if (metadataMode == SessionNamingMode.MANUAL) {
                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    DdListRow(
                        title = stringResource(R.string.agent_metadata_model),
                        value = metadataModelLabel ?: stringResource(R.string.agent_metadata_model_pick),
                        onClick = { modelPicker = true },
                        showChevron = true,
                        modifier = Modifier.testTag("agent_metadata_model"),
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Huge))
        }
    }
    if (modelPicker) {
        DdDialog(
            onDismissRequest = { modelPicker = false },
            title = stringResource(R.string.agent_metadata_model),
            content = {
                if (metadataModels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agent_metadata_model_empty),
                        style = DdType.body,
                        color = colors.inkSecondary,
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        metadataModels.forEach { model ->
                            DdListRow(
                                title = model.displayName,
                                subtitle = model.providerId,
                                onClick = {
                                    modelPicker = false
                                    onMetadataModelChange(model)
                                },
                                modifier = Modifier.testTag("agent_metadata_model_${model.providerId}_${model.id}"),
                            )
                        }
                    }
                }
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.agent_metadata_cancel),
                    onClick = { modelPicker = false },
                    color = colors.inkSecondary,
                )
            },
        )
    }
}
