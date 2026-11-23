package io.github.whywood.superpi.ui.common.chat

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import io.github.whywood.superpi.design.LocalDdMotionEnabled

internal fun chatAnimationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

@Composable
internal fun rememberShimmerProgress(active: Boolean): State<Float>? {
    if (!active || !LocalDdMotionEnabled.current || !chatAnimationsEnabled()) return null
    val transition = rememberInfiniteTransition(label = "chat_shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "chat_shimmer_progress",
    )
}

internal fun Modifier.textShimmer(active: Boolean, base: Color, highlight: Color): Modifier = composed {
    val progress = rememberShimmerProgress(active) ?: return@composed this
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val bandWidth = (size.width * 0.55f).coerceAtLeast(size.height * 2f).coerceAtLeast(80f)
            val brush = Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset.Zero,
                end = Offset(bandWidth, 0f),
            )
            onDrawWithContent {
                drawContent()
                val left = -bandWidth + (size.width + bandWidth * 2f) * progress.value
                translate(left = left) {
                    drawRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                        size = Size(bandWidth, size.height),
                        blendMode = BlendMode.SrcAtop,
                    )
                }
            }
        }
}
