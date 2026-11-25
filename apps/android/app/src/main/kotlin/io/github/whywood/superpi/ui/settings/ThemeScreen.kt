package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSegmentedControl
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.theme.ThemeAppearanceMode
import io.github.whywood.superpi.theme.ThemeChoice
import io.github.whywood.superpi.theme.ThemeOption
import io.github.whywood.superpi.theme.ThemeSource
import io.github.whywood.superpi.ui.common.ThemeMiniPreview

@Composable
fun ThemeScreen(
    builtIn: List<ThemeOption>,
    fromPlugins: List<ThemeOption>,
    current: ThemeChoice,
    appearance: ThemeAppearanceMode,
    onSelectAppearance: (ThemeAppearanceMode) -> Unit,
    onBack: () -> Unit,
    onSelect: (ThemeChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val systemLabel = stringResource(R.string.settings_theme_appearance_system)
    val lightLabel = stringResource(R.string.settings_theme_appearance_light)
    val darkLabel = stringResource(R.string.settings_theme_appearance_dark)
    DdPageScaffold(
        modifier = modifier.testTag("theme_settings"),
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.ScreenHorizontal),
        ) {
            Text(
                text = stringResource(R.string.settings_theme),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))
            DdSectionLabel(
                stringResource(R.string.settings_theme_appearance),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdSegmentedControl(
                options = ThemeAppearanceMode.entries,
                selected = appearance,
                onSelect = onSelectAppearance,
                enabled = current !is ThemeChoice.Pack,
            ) { mode ->
                when (mode) {
                    ThemeAppearanceMode.SYSTEM -> systemLabel
                    ThemeAppearanceMode.LIGHT -> lightLabel
                    ThemeAppearanceMode.DARK -> darkLabel
                }
            }
            Spacer(Modifier.height(22.dp))
            DdSectionLabel(
                stringResource(R.string.settings_theme_builtin),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            DdGroupCard {
                builtIn.forEachIndexed { index, option ->
                    if (index > 0) DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    ThemeRow(
                        option = option,
                        selected = option.id == current,
                        onClick = { onSelect(option.id) },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            DdSectionLabel(
                stringResource(R.string.settings_theme_from_plugins),
                Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            if (fromPlugins.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_theme_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = DdSpacing.Sm),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            } else {
                DdGroupCard {
                    fromPlugins.forEachIndexed { index, option ->
                        if (index > 0) DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                        ThemeRow(
                            option = option,
                            selected = option.id == current,
                            onClick = { onSelect(option.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(DdSpacing.Huge))
        }
    }
}

@Composable
private fun ThemeRow(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val currentLabel = stringResource(R.string.settings_theme_current)
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        contentDescription = if (selected) "${option.label} $currentLabel" else option.label,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = DdSpacing.RowHorizontal, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeMiniPreview(
                preview = option.colors,
                selected = selected,
                selectedBorder = colors.accent,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = option.label,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondaryLabel(option),
                    style = DdType.chip,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Spacer(Modifier.width(DdSpacing.Md))
                DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 18.dp)
            }
        }
    }
}

@Composable
private fun secondaryLabel(option: ThemeOption): String {
    val tone = when (option.id) {
        ThemeChoice.Pi -> stringResource(R.string.settings_theme_pi_subtitle)
        ThemeChoice.Paper -> stringResource(R.string.settings_theme_paper_subtitle)
        ThemeChoice.Slate -> stringResource(R.string.settings_theme_slate_subtitle)
        is ThemeChoice.Pack -> if (option.dark) {
            stringResource(R.string.settings_theme_dark)
        } else {
            stringResource(R.string.settings_theme_light)
        }
    }
    val source = option.source as? ThemeSource.Plugin ?: return tone
    return "$tone · ${source.pluginName}"
}
