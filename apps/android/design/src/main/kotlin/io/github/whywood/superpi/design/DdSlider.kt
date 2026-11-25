package io.github.whywood.superpi.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private val SLIDER_TOUCH_HEIGHT = 44.dp
private val SLIDER_TRACK_HEIGHT = 4.dp
private val SLIDER_THUMB_SIZE = 18.dp
private val SLIDER_TICK_RADIUS = 1.5.dp
private const val MAX_VISIBLE_TICKS = 12

@Composable
fun DdSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    accentDot: Boolean = false,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val hapticsEnabled = LocalDdHapticsEnabled.current
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    var dragging by remember { mutableStateOf(false) }
    val lastEmitted = remember { FloatArray(1) { value } }
    SideEffect {
        if (!dragging) {
            lastEmitted[0] = value
        }
    }

    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentRange by rememberUpdatedState(valueRange)
    val currentSteps by rememberUpdatedState(steps)
    val currentHapticsEnabled by rememberUpdatedState(hapticsEnabled)

    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value.coerceIn(valueRange.start, valueRange.endInclusive) - valueRange.start) / span)
        .coerceIn(0f, 1f)
    val visualFraction = if (rtl) 1f - fraction else fraction

    val thumbScale by animateFloatAsState(
        targetValue = if (dragging && enabled && motionEnabled) 1.15f else 1f,
        animationSpec = if (motionEnabled) tween(DdMotion.PRESS_IN, easing = DdMotion.EaseOut) else snap(),
        label = "dd-slider-thumb-scale",
    )

    val thumbBorderColor = if ((dragging || accentDot) && enabled) colors.signal else colors.accent

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SLIDER_TOUCH_HEIGHT)
            .alpha(if (enabled) 1f else DdAlpha.Disabled)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    val clamped = snapSliderValue(target, valueRange, steps)
                    if (abs(clamped - value) < 1e-5f) return@setProgress false
                    onValueChange(clamped)
                    onValueChangeFinished?.invoke()
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val thumbRadiusPx = with(density) { (SLIDER_THUMB_SIZE / 2).toPx() }
        val usableWidthPx = (totalWidthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)

        fun dispatchFromPosition(posX: Float, widthPx: Float) {
            val usable = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)
            val rawFraction = ((posX - thumbRadiusPx) / usable).coerceIn(0f, 1f)
            val directedFraction = if (rtl) 1f - rawFraction else rawFraction
            val range = currentRange
            val target = range.start + directedFraction * (range.endInclusive - range.start)
            val snapped = snapSliderValue(target, range, currentSteps)
            if (abs(snapped - lastEmitted[0]) > 1e-4f) {
                if (currentHapticsEnabled && currentSteps > 0) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                lastEmitted[0] = snapped
                currentOnChange(snapped)
            }
        }

        Box(
            Modifier
                .matchParentSize()
                .pointerInput(enabled, rtl) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        dispatchFromPosition(offset.x, size.width.toFloat())
                        currentOnFinished?.invoke()
                    }
                }
                .pointerInput(enabled, rtl) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            dragging = true
                            dispatchFromPosition(startOffset.x, size.width.toFloat())
                        },
                        onDragEnd = {
                            dragging = false
                            currentOnFinished?.invoke()
                        },
                        onDragCancel = {
                            dragging = false
                            currentOnFinished?.invoke()
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dispatchFromPosition(change.position.x, size.width.toFloat())
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SLIDER_THUMB_SIZE),
            ) {
                val trackHeightPx = SLIDER_TRACK_HEIGHT.toPx()
                val centerY = size.height / 2f
                val trackTop = centerY - trackHeightPx / 2f
                val startX = thumbRadiusPx
                val endX = (size.width - thumbRadiusPx).coerceAtLeast(startX)
                val trackLen = (endX - startX).coerceAtLeast(1f)
                val corner = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f)

                drawRoundRect(
                    color = colors.wash,
                    topLeft = Offset(startX, trackTop),
                    size = Size(trackLen, trackHeightPx),
                    cornerRadius = corner,
                )

                val thumbCenterX = startX + trackLen * visualFraction
                val activeLeft = if (rtl) thumbCenterX else startX
                val activeWidth = if (rtl) endX - thumbCenterX else thumbCenterX - startX
                if (activeWidth > 0.5f) {
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = Offset(activeLeft, trackTop),
                        size = Size(activeWidth, trackHeightPx),
                        cornerRadius = corner,
                    )
                }

                if (steps in 1..MAX_VISIBLE_TICKS) {
                    val tickRadiusPx = SLIDER_TICK_RADIUS.toPx()
                    val segments = steps + 1
                    for (index in 0..segments) {
                        val tickFrac = index.toFloat() / segments.toFloat()
                        val directedTickFrac = if (rtl) 1f - tickFrac else tickFrac
                        val cx = startX + trackLen * directedTickFrac
                        val covered = tickFrac <= fraction + 1e-4f
                        drawCircle(
                            color = if (covered) {
                                colors.onAccent.copy(alpha = 0.65f)
                            } else {
                                colors.inkTertiary.copy(alpha = 0.45f)
                            },
                            radius = tickRadiusPx,
                            center = Offset(cx, centerY),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .offset {
                        IntOffset((usableWidthPx * fraction).roundToInt(), 0)
                    }
                    .size(SLIDER_THUMB_SIZE)
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .shadow(
                        elevation = if (dragging) 4.dp else 2.dp,
                        shape = CircleShape,
                        clip = false,
                    )
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.5.dp, thumbBorderColor, CircleShape),
            )
        }
    }
}

private fun snapSliderValue(
    target: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val clamped = target.coerceIn(range.start, range.endInclusive)
    if (steps <= 0) return clamped
    val segments = steps + 1
    val span = range.endInclusive - range.start
    if (span <= 0f) return range.start
    val normalized = ((clamped - range.start) / span).coerceIn(0f, 1f)
    val snappedStep = (normalized * segments).roundToInt().coerceIn(0, segments)
    return range.start + (snappedStep.toFloat() / segments.toFloat()) * span
}
