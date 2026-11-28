package io.github.whywood.superpi.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.design.DdThemeVisualFamily
import io.github.whywood.superpi.design.SuperPIColors
import kotlin.math.PI
import kotlin.math.sin

internal data class SuperModePillStyle(
    val gradient: List<Color>,
    val glow: Color,
)

private data class SuperPillStar(
    val xFraction: Float,
    val yFraction: Float,
    val radiusDp: Float,
    val phaseOffset: Float,
)

private val SUPER_PILL_STARS = listOf(
    SuperPillStar(0.14f, 0.24f, 1.15f, 0.00f),
    SuperPillStar(0.34f, 0.74f, 1.00f, 0.28f),
    SuperPillStar(0.68f, 0.22f, 1.25f, 0.54f),
    SuperPillStar(0.86f, 0.68f, 1.00f, 0.78f),
)

private val SUPER_SHIMMER_COLORS = listOf(
    Color.Transparent,
    Color.White.copy(alpha = 0.26f),
    Color.Transparent,
)

// Every stop maintains >= 4.5:1 WCAG AA contrast against the #FAFAFC pill text/icon.
internal fun resolveSuperModePillStyle(
    family: DdThemeVisualFamily,
    colors: SuperPIColors,
): SuperModePillStyle = when (family) {
    DdThemeVisualFamily.VIOLET -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF1E1B4B),
            Color(0xFF4C1D95),
            Color(0xFF6D28D9),
            Color(0xFF7E22CE),
        ),
        glow = Color(0x807E22CE),
    )

    DdThemeVisualFamily.OCEAN -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF0B2942),
            Color(0xFF035388),
            Color(0xFF0F6D66),
            Color(0xFF0B7A75),
        ),
        glow = Color(0x780F766E),
    )

    DdThemeVisualFamily.WARM -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF431407),
            Color(0xFF881337),
            Color(0xFFB43434),
            Color(0xFFB91C3C),
        ),
        glow = Color(0x75B91C3C),
    )

    DdThemeVisualFamily.CUSTOM -> {
        val accent = colors.accent
        SuperModePillStyle(
            gradient = listOf(
                lerp(accent, Color(0xFF0B0B12), 0.62f),
                lerp(accent, Color(0xFF0B0B12), 0.42f),
                lerp(accent, Color(0xFF141422), 0.34f),
            ),
            glow = accent.copy(alpha = 0.45f),
        )
    }

    DdThemeVisualFamily.MONO -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF111116),
            Color(0xFF7F1D3A),
            Color(0xFFA91038),
            Color(0xFFBE123C),
        ),
        glow = Color(0x78BE123C),
    )
}

@Composable
internal fun Modifier.superModeShimmer(
    active: Boolean,
    motionEnabled: Boolean,
): Modifier {
    if (!active || !motionEnabled) return this
    val transition = rememberInfiniteTransition(label = "super-pill-motion")
    val shimmerState = transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "super-pill-shimmer",
    )
    val starPhaseState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "super-pill-stars",
    )
    // Read animated State<Float>.value inside drawWithContent only (zero recomposition per frame).
    return drawWithContent {
        drawContent()
        if (size.width > 12f) {
            val shimmerProgress = shimmerState.value
            val starPhase = starPhaseState.value
            val beamHalf = (size.width * 0.24f).coerceAtLeast(22f)
            val centerX = size.width * shimmerProgress
            drawRect(
                brush = Brush.linearGradient(
                    colors = SUPER_SHIMMER_COLORS,
                    start = Offset(centerX - beamHalf, 0f),
                    end = Offset(centerX + beamHalf, size.height),
                ),
            )
            SUPER_PILL_STARS.forEach { star ->
                val wave = (sin((starPhase + star.phaseOffset) * 2.0 * PI) * 0.5 + 0.5).toFloat()
                val alpha = 0.20f + wave * 0.78f
                val cx = size.width * star.xFraction
                val cy = size.height * star.yFraction
                val r = star.radiusDp.dp.toPx() * (0.75f + wave * 0.35f)
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }
        }
    }
}
