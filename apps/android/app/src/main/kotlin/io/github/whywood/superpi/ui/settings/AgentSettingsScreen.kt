package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.layout.BoxWithConstraints
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
import io.github.whywood.superpi.data.SessionNamingMode
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
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
                    modifier = Modifier.testTag("agent_web_search"),
                )
            }
            Spacer(Modifier.height(28.dp))
            DdSectionLabel(
                stringResource(R.string.agent_section_metadata),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    DdListRow(
                        title = stringResource(R.string.agent_metadata_title),
                        subtitle = stringResource(R.string.agent_metadata_summary),
                        subtitleMaxLines = 2,
                        value = stringResource(
                            when (metadataMode) {
                                SessionNamingMode.AUTO -> R.string.agent_metadata_auto
                                SessionNamingMode.MANUAL -> R.string.agent_metadata_manual
                            },
                        ),
                        onClick = { modeMenu = true },
                        modifier = Modifier.testTag("agent_metadata_mode"),
                    )
                    DdMenu(
                        expanded = modeMenu,
                        onDismissRequest = { modeMenu = false },
                        menuWidth = maxWidth,
                    ) {
                        DdMenuItem(
                            text = stringResource(R.string.agent_metadata_auto),
                            onClick = {
                                modeMenu = false
                                onMetadataModeChange(SessionNamingMode.AUTO)
                            },
                            selected = metadataMode == SessionNamingMode.AUTO,
                            testTag = "agent_metadata_auto",
                        )
                        DdMenuItem(
                            text = stringResource(R.string.agent_metadata_manual),
                            onClick = {
                                modeMenu = false
                                onMetadataModeChange(SessionNamingMode.MANUAL)
                            },
                            selected = metadataMode == SessionNamingMode.MANUAL,
                            testTag = "agent_metadata_manual",
                        )
                    }
                }
                if (metadataMode == SessionNamingMode.MANUAL) {
                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    DdListRow(
                        title = stringResource(R.string.agent_metadata_model),
                        value = metadataModelLabel ?: stringResource(R.string.agent_metadata_model_pick),
                        onClick = { modelPicker = true },
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
