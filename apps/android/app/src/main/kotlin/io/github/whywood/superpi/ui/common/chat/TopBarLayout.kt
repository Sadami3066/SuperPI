package io.github.whywood.superpi.ui.common.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import io.github.whywood.superpi.design.DdSpacing

@Composable
internal fun TopBarLayout(
    leading: @Composable () -> Unit,
    center: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = DdSpacing.Xs
    Layout(
        contents = listOf(leading, center, trailing),
        modifier = modifier,
    ) { (leadingMeasurables, centerMeasurables, trailingMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceables = leadingMeasurables.map { it.measure(loose) }
        val leadingWidth = leadingPlaceables.maxOfOrNull { it.width } ?: 0
        val trailingConstraints = loose.copy(maxWidth = (constraints.maxWidth - leadingWidth).coerceAtLeast(0))
        val trailingPlaceables = trailingMeasurables.map { it.measure(trailingConstraints) }
        val trailingWidth = trailingPlaceables.maxOfOrNull { it.width } ?: 0
        val gapPx = gap.roundToPx()
        val leftEdge = leadingWidth + gapPx
        val rightEdge = (constraints.maxWidth - trailingWidth - gapPx).coerceAtLeast(leftEdge)
        val maxCenterWidth = (rightEdge - leftEdge).coerceAtLeast(0)
        val centerConstraints = loose.copy(maxWidth = maxCenterWidth)
        val centerPlaceables = centerMeasurables.map { it.measure(centerConstraints) }
        val centerWidth = centerPlaceables.maxOfOrNull { it.width } ?: 0
        val height = maxOf(
            constraints.minHeight,
            leadingPlaceables.maxOfOrNull { it.height } ?: 0,
            centerPlaceables.maxOfOrNull { it.height } ?: 0,
            trailingPlaceables.maxOfOrNull { it.height } ?: 0,
        ).coerceIn(constraints.minHeight, constraints.maxHeight)
        val centeredX = (constraints.maxWidth - centerWidth) / 2
        val maxCenterX = (rightEdge - centerWidth).coerceAtLeast(leftEdge)
        val centerX = centeredX.coerceIn(leftEdge, maxCenterX)
        val trailingX = (constraints.maxWidth - trailingWidth).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            leadingPlaceables.forEach { placeable ->
                placeable.placeRelative(0, (height - placeable.height) / 2)
            }
            centerPlaceables.forEach { placeable ->
                placeable.placeRelative(centerX, (height - placeable.height) / 2)
            }
            trailingPlaceables.forEach { placeable ->
                placeable.placeRelative(trailingX, (height - placeable.height) / 2)
            }
        }
    }
}
