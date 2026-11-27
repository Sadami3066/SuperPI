package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            BuiltInThemeGrid(
                options = builtIn,
                current = current,
                onSelect = onSelect,
            )
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
private fun BuiltInThemeGrid(
    options: List<ThemeOption>,
    current: ThemeChoice,
    onSelect: (ThemeChoice) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { option ->
                    ThemeGridCard(
                        option = option,
                        selected = option.id == current,
                        onClick = { onSelect(option.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ThemeGridCard(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val preview = option.colors
    val currentLabel = stringResource(R.string.settings_theme_current)
    val cardShape = RoundedCornerShape(16.dp)
    val previewShape = RoundedCornerShape(11.dp)
    val cardBorderWidth = if (selected) 1.5.dp else 0.75.dp
    val cardBorderColor = if (selected) colors.accent else colors.hairline
    DdPressable(
        onClick = onClick,
        modifier = modifier
            .testTag("theme_card_${option.id.key}")
            .semantics { this.selected = selected },
        shape = cardShape,
        contentDescription = if (selected) "${option.label} $currentLabel" else option.label,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(colors.surface)
                .border(cardBorderWidth, cardBorderColor, cardShape)
                .padding(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(previewShape)
                    .background(preview.paper)
                    .border(0.75.dp, preview.hairline, previewShape)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(preview.codeKeyword))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(preview.codeString))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(preview.codeFunction))
                    }
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(preview.ink.copy(alpha = 0.72f)),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.56f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(preview.userBubble)
                            .border(0.75.dp, preview.userBubbleBorder, RoundedCornerShape(5.dp)),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.54f)
                            .height(3.5.dp)
                            .clip(CircleShape)
                            .background(preview.inkSecondary.copy(alpha = 0.65f)),
                    )
                    Box(
                        Modifier
                            .width(16.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(preview.accent),
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = option.label,
                        style = DdType.label.copy(
                            fontSize = 13.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = secondaryLabel(option),
                        style = DdType.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        DdIcon(icon = DdIcons.Check, tint = colors.onAccent, size = 12.dp)
                    }
                }
            }
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
        ThemeChoice.TokyoNight -> stringResource(R.string.settings_theme_tokyo_night_subtitle)
        ThemeChoice.NightOwl -> stringResource(R.string.settings_theme_night_owl_subtitle)
        ThemeChoice.Catppuccin -> stringResource(R.string.settings_theme_catppuccin_subtitle)
        ThemeChoice.Nord -> stringResource(R.string.settings_theme_nord_subtitle)
        ThemeChoice.RosePine -> stringResource(R.string.settings_theme_rose_pine_subtitle)
        ThemeChoice.Gruvbox -> stringResource(R.string.settings_theme_gruvbox_subtitle)
        ThemeChoice.Dracula -> stringResource(R.string.settings_theme_dracula_subtitle)
        ThemeChoice.Solarized -> stringResource(R.string.settings_theme_solarized_subtitle)
        is ThemeChoice.Pack -> if (option.dark) {
            stringResource(R.string.settings_theme_dark)
        } else {
            stringResource(R.string.settings_theme_light)
        }
    }
    val source = option.source as? ThemeSource.Plugin ?: return tone
    return "$tone · ${source.pluginName}"
}
