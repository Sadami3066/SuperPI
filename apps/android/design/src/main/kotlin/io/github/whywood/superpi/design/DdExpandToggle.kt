package io.github.whywood.superpi.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun DdExpandToggle(
    expanded: Boolean,
    collapsedLabel: String,
    expandedLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "expand-chevron",
    )
    DdPressable(
        onClick = onToggle,
        modifier = modifier.heightIn(min = 44.dp),
        shape = DdShapes.Small,
        contentDescription = if (expanded) expandedLabel else collapsedLabel,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DdSpacing.Sm, vertical = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
        ) {
            DdText(
                text = if (expanded) expandedLabel else collapsedLabel,
                modifier = Modifier.weight(1f, fill = false),
                style = DdType.label,
                color = colors.inkSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            DdIcon(
                icon = DdIcons.ChevronDown,
                tint = colors.inkTertiary,
                size = 14.dp,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }
    }
}
