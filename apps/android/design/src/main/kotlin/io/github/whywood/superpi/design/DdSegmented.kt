package io.github.whywood.superpi.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun DdSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTags: List<String>? = null,
) {
    if (options.isEmpty()) return
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val index = selectedIndex.coerceIn(0, options.size - 1)
    val indicatorColor by animateColorAsState(
        targetValue = if (colors.dark) colors.raised else colors.surface,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "segmented-indicator",
    )
    DdSegmentedCore(
        options = options,
        selected = options[index],
        onSelect = { option -> onSelect(options.indexOf(option)) },
        modifier = modifier,
        enabled = true,
        height = 44.dp,
        shape = RoundedCornerShape(DdRadius.Segmented),
        indicatorShape = RoundedCornerShape(DdRadius.Indicator),
        indicatorPadding = 3.dp,
        indicatorColor = indicatorColor,
        testTags = testTags,
    ) { option, isSelected ->
        Text(
            text = option,
            style = DdType.chip,
            color = if (isSelected) colors.ink else colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
