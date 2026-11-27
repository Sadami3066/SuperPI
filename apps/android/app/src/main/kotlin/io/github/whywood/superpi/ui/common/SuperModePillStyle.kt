package io.github.whywood.superpi.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

internal fun resolveSuperModePillStyle(
    family: DdThemeVisualFamily,
    colors: SuperPIColors,
): SuperModePillStyle = when (family) {
    DdThemeVisualFamily.VIOLET -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF1E1B4B),
            Color(0xFF5B21B6),
            Color(0xFF9333EA),
            Color(0xFFC084FC),
        ),
        glow = Color(0x859333EA),
    )

    DdThemeVisualFamily.OCEAN -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF0B2942),
            Color(0xFF0369A1),
            Color(0xFF0D9488),
            Color(0xFF2DD4BF),
        ),
        glow = Color(0x800D9488),
    )

    DdThemeVisualFamily.WARM -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF431407),
            Color(0xFF9F1239),
            Color(0xFFD94646),
            Color(0xFFFB7185),
        ),
        glow = Color(0x75E11D48),
    )

    DdThemeVisualFamily.CUSTOM -> {
        val accent = colors.accent
        SuperModePillStyle(
            gradient = listOf(
                lerp(accent, Color(0xFF0B0B12), 0.42f),
                accent,
                lerp(accent, Color(0xFFF5D0FE), 0.26f),
            ),
            glow = accent.copy(alpha = 0.52f),
        )
    }

    DdThemeVisualFamily.MONO -> SuperModePillStyle(
        gradient = listOf(
            Color(0xFF111116),
            Color(0xFF7F1D3A),
            Color(0xFFBE123C),
            Color(0xFFF43F5E),
        ),
        glow = Color(0x7AE11D48),
    )
}

@Composable
internal fun Modifier.superModeShimmer(
    active: Boolean,
    motionEnabled: Boolean,
): Modifier {
    if (!active || !motionEnabled) return this
    val transition = rememberInfiniteTransition(label = "super-pill-motion")
    val shimmerProgress by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "super-pill-shimmer",
    )
    val starPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "super-pill-stars",
    )
    return drawWithContent {
        drawContent()
        if (size.width > 12f) {
            val beamHalf = (size.width * 0.24f).coerceAtLeast(22f)
            val centerX = size.width * shimmerProgress
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.28f),
                        Color.Transparent,
                    ),
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
