package io.github.whywood.superpi.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun DdSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateDescription: String? = null,
    activeColor: Color? = null,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (motionEnabled) DdMotion.SwitchSpring else snap(),
        label = "switch-thumb-offset",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = if (motionEnabled) DdMotion.SwitchSpring else snap(),
        label = "switch-thumb-scale",
    )
    val colorSpec = if (motionEnabled) tween<Color>(DdMotion.FAST, easing = DdMotion.EaseOut) else snap()
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            activeColor ?: colors.accent
        } else {
            colors.ink.copy(alpha = if (colors.dark) 0.22f else 0.12f)
        },
        animationSpec = colorSpec,
        label = "switch-track",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colors.onAccent else if (colors.dark) colors.ink else Color.White,
        animationSpec = colorSpec,
        label = "switch-thumb-color",
    )
    val stateText = stateDescription
    val descriptionModifier = if (stateText == null) {
        Modifier
    } else {
        Modifier.semantics { this.stateDescription = stateText.orEmpty() }
    }
    Box(
        modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else DdAlpha.Disabled)
            .then(descriptionModifier)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
                interactionSource = interaction,
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(CircleShape)
                .background(trackColor)
                .then(
                    if (!checked) {
                        Modifier.border(1.dp, colors.hairline.copy(alpha = 0.6f), CircleShape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(24.dp)
                    .graphicsLayer {
                        translationX = (4f + 20f * thumbProgress).dp.toPx()
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(thumbColor),
            )
        }
    }
}

@Composable
fun <T> DdSegmentedControl(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    item: @Composable (T, Boolean) -> Unit,
) {
    val colors = superPiColors()
    DdSegmentedCore(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        height = 44.dp,
        shape = RoundedCornerShape(DdRadius.Segmented),
        indicatorShape = RoundedCornerShape(DdRadius.Indicator),
        indicatorPadding = 3.dp,
        indicatorColor = if (colors.dark) colors.raised else colors.surface,
        testTags = null,
        item = item,
    )
}

@Composable
fun <T> DdSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (T) -> String,
) {
    val colors = superPiColors()
    DdSegmentedCore(
        options = options,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        height = 44.dp,
        shape = CircleShape,
        indicatorShape = CircleShape,
        indicatorPadding = 3.dp,
        indicatorColor = if (colors.dark) colors.raised else colors.surface,
        testTags = null,
    ) { option, isSelected ->
        Text(
            text = label(option),
            style = DdType.chip,
            color = if (isSelected) colors.ink else colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun <T> DdSegmentedCore(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    height: Dp,
    shape: Shape,
    indicatorShape: Shape,
    indicatorPadding: Dp,
    indicatorColor: Color,
    testTags: List<String>?,
    item: @Composable (T, Boolean) -> Unit,
) {
    if (options.isEmpty()) return
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val selectedIndex = if (selected == null) -1 else options.indexOf(selected).coerceAtLeast(0)
    val animatedIndex by animateFloatAsState(
        targetValue = if (selectedIndex >= 0) selectedIndex.toFloat() else 0f,
        animationSpec = if (motionEnabled) DdMotion.SegmentedSpring else snap(),
        label = "segment-index",
    )
    val indicatorAlpha = if (selectedIndex >= 0) 1f else 0f
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else DdAlpha.Disabled)
            .clip(shape)
            .background(colors.wash),
    ) {
        val segmentWidth = maxWidth / options.size.toFloat()
        Box(
            Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = indicatorAlpha
                    translationX = (size.width * animatedIndex)
                }
                .padding(indicatorPadding)
                .then(
                    if (colors.dark) {
                        Modifier.border(1.dp, colors.hairline.copy(alpha = 0.7f), indicatorShape)
                    } else {
                        Modifier.shadow(elevation = DdElevation.Indicator, shape = indicatorShape, clip = false)
                    },
                )
                .clip(indicatorShape)
                .background(indicatorColor),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val tag = testTags?.getOrNull(index)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (tag == null) Modifier else Modifier.testTag(tag))
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelect(option) },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        )
                        .padding(horizontal = DdSpacing.Sm),
                    contentAlignment = Alignment.Center,
                ) {
                    item(option, isSelected)
                }
            }
        }
    }
}
