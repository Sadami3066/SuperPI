package io.github.whywood.superpi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdFontStyle
import io.github.whywood.superpi.design.DdFonts
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors

@Composable
fun FontScreen(
    current: DdFontStyle,
    onBack: () -> Unit,
    onSelect: (DdFontStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    DdPageScaffold(
        modifier = modifier.testTag("font_settings"),
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
                text = stringResource(R.string.settings_font_title),
                style = DdType.pageTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(DdSpacing.Xl))
            DdGroupCard {
                DdFontStyle.entries.forEachIndexed { index, style ->
                    if (index > 0) {
                        DdRowDivider(leadingInset = DdSpacing.RowHorizontal)
                    }
                    FontPreviewRow(
                        style = style,
                        label = if (style == DdFontStyle.SYSTEM) {
                            stringResource(R.string.settings_font_system)
                        } else {
                            style.displayName
                        },
                        selected = style == current,
                        onClick = { onSelect(style) },
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Huge))
        }
    }
}

@Composable
private fun FontPreviewRow(
    style: DdFontStyle,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val family = DdFonts.familyFor(style)
    val currentLabel = stringResource(R.string.settings_font_current)
    DdPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        contentDescription = if (selected) "$label $currentLabel" else label,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .padding(horizontal = DdSpacing.RowHorizontal, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(top = DdSpacing.Xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f, fill = false),
                    style = DdType.label.copy(fontFamily = family),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Spacer(Modifier.width(DdSpacing.Sm))
                    Text(
                        text = currentLabel,
                        style = DdType.chip,
                        color = colors.accent,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(DdSpacing.Xxs))
                    DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 16.dp)
                }
            }
            Spacer(Modifier.width(DdSpacing.Md))
            Column(
                modifier = Modifier.widthIn(max = 168.dp).padding(top = DdSpacing.Md),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs),
            ) {
                Text(
                    text = stringResource(R.string.settings_font_preview_primary),
                    style = DdType.body.copy(fontFamily = family, fontSize = 18.sp, lineHeight = 26.sp),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.settings_font_preview_secondary),
                    style = DdType.bodySmall.copy(fontFamily = family, fontSize = 13.sp, lineHeight = 19.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
