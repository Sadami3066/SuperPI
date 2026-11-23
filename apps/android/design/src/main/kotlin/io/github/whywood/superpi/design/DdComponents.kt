package io.github.whywood.superpi.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalDdRowHorizontalPadding = staticCompositionLocalOf { 0.dp }
internal val LocalDdRowShape = staticCompositionLocalOf<Shape> { DdShapes.Row }

@Composable
fun DdPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    DdPressable(
        onClick = onClick,
        interactionSource = remember { MutableInteractionSource() },
        modifier = modifier,
        shape = shape,
        background = background,
        enabled = enabled,
        contentDescription = contentDescription,
        content = content,
    )
}

@Composable
fun DdPressable(
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
    hapticFeedbackType: HapticFeedbackType? = null,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val hapticsEnabled = LocalDdHapticsEnabled.current
    val pressed by interactionSource.collectIsPressedAsState()
    val feedbackColor = if (background.alpha > 0.2f && background.luminance() < 0.45f) Color.White else colors.ink
    val wash = animateFloatAsState(
        targetValue = if (pressed && enabled) DdAlpha.Press else 0f,
        animationSpec = if (!motionEnabled || pressed) snap() else tween(DdMotion.PRESS_OUT, easing = DdMotion.EaseOut),
        label = "press-wash",
    )
    val haptics = LocalHapticFeedback.current
    val hapticType = hapticFeedbackType
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Box(
        modifier = modifier
            .then(semantics)
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = feedbackColor.copy(alpha = DdAlpha.Ripple)),
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    if (hapticsEnabled && hapticType != null) {
                        haptics.performHapticFeedback(hapticType)
                    }
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val alpha = wash.value
                    if (alpha > 0f) drawRect(feedbackColor.copy(alpha = alpha))
                },
        )
    }
}

@Composable
fun DdIconButton(
    icon: DdIcon,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = superPiColors().ink,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    DdPressable(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        background = background,
        enabled = enabled,
        contentDescription = contentDescription,
    ) {
        DdIcon(icon = icon, tint = tint, size = iconSize)
    }
}

@Composable
fun DdWordmark(
    modifier: Modifier = Modifier,
    color: Color = superPiColors().ink,
    dotColor: Color = superPiColors().accent,
    style: TextStyle = DdType.wordmark,
) {
    Row(modifier) {
        Text(
            text = "PI",
            style = style,
            color = color,
        )
        Text(
            text = ".",
            modifier = Modifier.alignByBaseline(),
            style = style,
            color = dotColor,
        )
    }
}

@Composable
fun DdHairline(
    modifier: Modifier = Modifier,
    color: Color = superPiColors().hairline.copy(alpha = 0.68f),
) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun DdRowDivider(
    modifier: Modifier = Modifier,
    leadingInset: Dp = 0.dp,
    color: Color = superPiColors().hairline.copy(alpha = 0.68f),
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = leadingInset)
            .height(1.dp)
            .background(color),
    )
}

@Composable
fun DdSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = DdType.micro,
        color = superPiColors().inkTertiary,
    )
}

@Composable
fun DdTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = superPiColors().accent,
    enabled: Boolean = true,
) {
    DdPressable(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(8.dp),
        enabled = enabled,
        contentDescription = null,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = DdSpacing.Md),
            style = DdType.label,
            color = if (enabled) color else color.copy(alpha = 0.38f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DdListRowContainer(
    modifier: Modifier,
    shape: Shape,
    onClick: (() -> Unit)?,
    enabled: Boolean,
    contentDescription: String,
    content: @Composable BoxScope.() -> Unit,
) {
    if (onClick != null) {
        DdPressable(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            enabled = enabled,
            contentDescription = contentDescription,
            content = content,
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape),
            content = content,
        )
    }
}

@Composable
fun DdListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: DdIcon? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    subtitleMaxLines: Int = 1,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = superPiColors()
    val alpha = if (enabled) 1f else 0.42f
    val description = listOfNotNull(title, subtitle, value).joinToString(", ")
    DdListRowContainer(
        modifier = modifier,
        shape = LocalDdRowShape.current,
        onClick = onClick,
        enabled = enabled,
        contentDescription = description,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = LocalDdRowHorizontalPadding.current, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                DdIcon(icon = icon, tint = colors.inkSecondary.copy(alpha = alpha), size = 20.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = DdType.body,
                    color = colors.ink.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = DdType.bodySmall,
                        color = colors.inkTertiary.copy(alpha = alpha),
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(DdSpacing.Md))
                trailing()
            } else if (value != null) {
                Text(
                    text = value,
                    modifier = Modifier.padding(start = 12.dp).widthIn(max = 140.dp),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary.copy(alpha = alpha),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                DdIcon(
                    icon = DdIcons.ChevronRight,
                    tint = colors.inkTertiary.copy(alpha = 0.55f * alpha),
                    size = 15.dp,
                )
            }
        }
    }
}

@Composable
fun DdListRow(
    title: String,
    leadingIcon: DdIcon,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    iconContainerColor: Color = superPiColors().wash,
    iconTint: Color = superPiColors().inkSecondary,
) {
    val colors = superPiColors()
    val alpha = if (enabled) 1f else DdAlpha.Disabled
    val description = listOfNotNull(title, subtitle).joinToString(", ")
    DdListRowContainer(
        modifier = modifier,
        shape = LocalDdRowShape.current,
        onClick = onClick,
        enabled = enabled,
        contentDescription = description,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = LocalDdRowHorizontalPadding.current, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(
                icon = leadingIcon,
                size = 22.dp,
                tint = iconTint.copy(alpha = alpha),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = DdType.body,
                    color = colors.ink.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = DdType.bodySmall,
                        color = colors.inkTertiary.copy(alpha = alpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(DdSpacing.Md))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                DdIcon(
                    icon = DdIcons.ChevronRight,
                    tint = colors.inkTertiary.copy(alpha = DdAlpha.Muted * alpha),
                    size = 15.dp,
                )
            }
        }
    }
}

@Composable
fun DdChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: DdIcon? = null,
    enabled: Boolean = true,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val chipBackground = if (selected && enabled) colors.accent else colors.wash
    val chipContent = if (selected && enabled) colors.onAccent else colors.inkSecondary
    val feedbackColor = if (chipBackground.alpha > 0.2f && chipBackground.luminance() < 0.45f) Color.White else colors.ink
    val wash = animateFloatAsState(
        targetValue = if (pressed && enabled) DdAlpha.Press else 0f,
        animationSpec = if (!motionEnabled || pressed) snap() else tween(DdMotion.PRESS_OUT, easing = DdMotion.EaseOut),
        label = "chip-press-wash",
    )
    val semantics = if (selected) {
        Modifier.semantics {
            this.selected = true
            this.contentDescription = text
        }
    } else {
        Modifier.semantics { this.contentDescription = text }
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(semantics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .alpha(if (enabled) 1f else DdAlpha.Disabled)
                .clip(CircleShape)
                .background(chipBackground)
                .then(
                    if (!selected || !enabled) {
                        Modifier.border(1.dp, colors.hairline.copy(alpha = 0.7f), CircleShape)
                    } else {
                        Modifier
                    },
                )
                .indication(interaction, ripple(color = feedbackColor.copy(alpha = DdAlpha.Ripple)))
                .drawWithContent {
                    drawContent()
                    val alpha = wash.value
                    if (alpha > 0f) drawRect(feedbackColor.copy(alpha = alpha))
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                DdIcon(
                    icon = icon,
                    tint = chipContent,
                    size = 15.dp,
                )
            }
            Text(
                text = text,
                style = DdType.chip,
                color = chipContent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DdPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed && enabled && motionEnabled) 0.985f else 1f,
        animationSpec = if (motionEnabled) DdMotion.SwitchSpring else snap(),
        label = "primary-button-scale",
    )
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> colors.wash
            pressed -> colors.accentPressed
            else -> colors.accent
        },
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "primary-button-bg",
    )
    DdPressable(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
            },
        shape = RoundedCornerShape(16.dp),
        background = background,
        enabled = enabled,
        contentDescription = text,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = DdSpacing.Lg),
            style = DdType.button,
            color = if (enabled) colors.onAccent else colors.inkTertiary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DdSheet(
    onDismiss: () -> Unit,
    fullBleed: Boolean = false,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DdSheetContent(
        onDismiss = onDismiss,
        fullBleed = fullBleed,
        containerColor = containerColor,
        content = content,
    )
}

@Composable
fun DdSheetHeader(
    title: String,
    onClose: () -> Unit,
) {
    val colors = superPiColors()
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = DdType.heading,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DdSpacing.Sm))
        DdIconButton(
            icon = DdIcons.Close,
            onClick = onClose,
            contentDescription = stringResource(R.string.dd_close),
            tint = colors.inkSecondary,
            iconSize = 20.dp,
        )
    }
}
