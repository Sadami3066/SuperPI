package io.github.whywood.superpi.ui.common.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdThemeVisualFamily
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.LocalDdThemeVisualFamily
import io.github.whywood.superpi.design.SuperPIColors
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.ui.common.LocalHapticsEnabled
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private data class EffortVisual(
    val labelColor: Color,
    val trackColors: List<Color>,
    val glowColor: Color,
    val shimmerAlpha: Float,
    val stars: Boolean,
)

private data class EffortStar(
    val xFraction: Float,
    val yFraction: Float,
    val radiusDp: Float,
    val phaseOffset: Float,
)

private val EFFORT_STARS = listOf(
    EffortStar(0.16f, 0.30f, 1.3f, 0.0f),
    EffortStar(0.38f, 0.72f, 1.15f, 0.28f),
    EffortStar(0.64f, 0.28f, 1.35f, 0.54f),
    EffortStar(0.84f, 0.68f, 1.15f, 0.80f),
)

private fun resolveEffortVisual(
    family: DdThemeVisualFamily,
    level: ThinkingLevel,
    colors: SuperPIColors,
): EffortVisual {
    val dark = colors.dark
    if (level == ThinkingLevel.OFF) {
        return EffortVisual(
            labelColor = colors.inkTertiary,
            trackColors = listOf(colors.hairline, colors.wash),
            glowColor = Color.Transparent,
            shimmerAlpha = 0f,
            stars = false,
        )
    }
    if (family == DdThemeVisualFamily.CUSTOM) {
        val a = colors.accent
        return when (level) {
            ThinkingLevel.LOW -> EffortVisual(
                labelColor = a,
                trackColors = listOf(lerp(a, colors.inkSecondary, 0.35f), lerp(a, Color.White, 0.14f)),
                glowColor = a.copy(alpha = 0.26f),
                shimmerAlpha = 0.18f,
                stars = false,
            )
            ThinkingLevel.MEDIUM -> EffortVisual(
                labelColor = a,
                trackColors = listOf(lerp(a, Color.Black, 0.22f), a),
                glowColor = a.copy(alpha = 0.34f),
                shimmerAlpha = 0.24f,
                stars = false,
            )
            ThinkingLevel.HIGH -> EffortVisual(
                labelColor = a,
                trackColors = listOf(lerp(a, Color(0xFF111113), 0.28f), a, lerp(a, Color.White, 0.22f)),
                glowColor = a.copy(alpha = 0.42f),
                shimmerAlpha = 0.30f,
                stars = false,
            )
            ThinkingLevel.XHIGH, ThinkingLevel.MAX -> EffortVisual(
                labelColor = a,
                trackColors = listOf(lerp(a, Color(0xFF09090B), 0.38f), a, lerp(a, Color.White, 0.32f)),
                glowColor = a.copy(alpha = 0.48f),
                shimmerAlpha = 0.38f,
                stars = true,
            )
            ThinkingLevel.OFF -> error("handled above")
        }
    }
    return when (family) {
        DdThemeVisualFamily.VIOLET -> when (level) {
            ThinkingLevel.LOW -> EffortVisual(
                labelColor = if (dark) Color(0xFF7AA2F7) else Color(0xFF2E6BE6),
                trackColors = listOf(Color(0xFF2563EB), Color(0xFF6366F1)),
                glowColor = Color(0x476366F1),
                shimmerAlpha = 0.18f,
                stars = false,
            )
            ThinkingLevel.MEDIUM -> EffortVisual(
                labelColor = if (dark) Color(0xFFBB9AF7) else Color(0xFF6D28D9),
                trackColors = listOf(Color(0xFF3730A3), Color(0xFF6366F1), Color(0xFF8B5CF6)),
                glowColor = Color(0x5C8B5CF6),
                shimmerAlpha = 0.24f,
                stars = false,
            )
            ThinkingLevel.HIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFFCBA6F7) else Color(0xFF7C3AED),
                trackColors = listOf(Color(0xFF2E1065), Color(0xFF6D28D9), Color(0xFFA855F7)),
                glowColor = Color(0x66A855F7),
                shimmerAlpha = 0.30f,
                stars = false,
            )
            ThinkingLevel.XHIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFFD8B4FE) else Color(0xFF6B21A8),
                trackColors = listOf(Color(0xFF1E1B4B), Color(0xFF6D28D9), Color(0xFFA855F7), Color(0xFFF472B6)),
                glowColor = Color(0x70C084FC),
                shimmerAlpha = 0.36f,
                stars = true,
            )
            ThinkingLevel.MAX -> EffortVisual(
                labelColor = if (dark) Color(0xFFF5D0FE) else Color(0xFF581C87),
                trackColors = listOf(Color(0xFF17153B), Color(0xFF6D28D9), Color(0xFFC026D3), Color(0xFFF472B6)),
                glowColor = Color(0x7AE879F9),
                shimmerAlpha = 0.42f,
                stars = true,
            )
            ThinkingLevel.OFF -> error("handled above")
        }

        DdThemeVisualFamily.OCEAN -> when (level) {
            ThinkingLevel.LOW -> EffortVisual(
                labelColor = if (dark) Color(0xFF7FDBCA) else Color(0xFF0E657C),
                trackColors = listOf(Color(0xFF0E7490), Color(0xFF2AA198)),
                glowColor = Color(0x470E7490),
                shimmerAlpha = 0.18f,
                stars = false,
            )
            ThinkingLevel.MEDIUM -> EffortVisual(
                labelColor = if (dark) Color(0xFF82AAFF) else Color(0xFF005A9E),
                trackColors = listOf(Color(0xFF005A9E), Color(0xFF0284C7), Color(0xFF0D9488)),
                glowColor = Color(0x570284C7),
                shimmerAlpha = 0.24f,
                stars = false,
            )
            ThinkingLevel.HIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFF88C0D0) else Color(0xFF0C5678),
                trackColors = listOf(Color(0xFF0C4A6E), Color(0xFF268BD2), Color(0xFF2AA198)),
                glowColor = Color(0x610D9488),
                shimmerAlpha = 0.30f,
                stars = false,
            )
            ThinkingLevel.XHIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFF7FDBCA) else Color(0xFF0B4F6C),
                trackColors = listOf(Color(0xFF0C3D59), Color(0xFF268BD2), Color(0xFF0D9488), Color(0xFF2DD4BF)),
                glowColor = Color(0x6B2DD4BF),
                shimmerAlpha = 0.36f,
                stars = true,
            )
            ThinkingLevel.MAX -> EffortVisual(
                labelColor = if (dark) Color(0xFF99F6E4) else Color(0xFF0A4560),
                trackColors = listOf(Color(0xFF0A3147), Color(0xFF0369A1), Color(0xFF0F766E), Color(0xFF2DD4BF)),
                glowColor = Color(0x752DD4BF),
                shimmerAlpha = 0.42f,
                stars = true,
            )
            ThinkingLevel.OFF -> error("handled above")
        }

        DdThemeVisualFamily.WARM -> when (level) {
            ThinkingLevel.LOW -> EffortVisual(
                labelColor = if (dark) Color(0xFFF6C177) else Color(0xFF9A4424),
                trackColors = listOf(Color(0xFF9F3A20), Color(0xFFD97706)),
                glowColor = Color(0x47B4462A),
                shimmerAlpha = 0.18f,
                stars = false,
            )
            ThinkingLevel.MEDIUM -> EffortVisual(
                labelColor = if (dark) Color(0xFFFE8019) else Color(0xFF9A2D1B),
                trackColors = listOf(Color(0xFF8B2819), Color(0xFFC2410C), Color(0xFFEA580C)),
                glowColor = Color(0x57B93822),
                shimmerAlpha = 0.24f,
                stars = false,
            )
            ThinkingLevel.HIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFFEB6F92) else Color(0xFF881337),
                trackColors = listOf(Color(0xFF6C1824), Color(0xFFB44E6B), Color(0xFFE11D48)),
                glowColor = Color(0x5CE11D48),
                shimmerAlpha = 0.30f,
                stars = false,
            )
            ThinkingLevel.XHIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFFEBBCBA) else Color(0xFF881337),
                trackColors = listOf(Color(0xFF52101A), Color(0xFF9F1239), Color(0xFFD946EF), Color(0xFFF472B6)),
                glowColor = Color(0x6BE11D48),
                shimmerAlpha = 0.36f,
                stars = true,
            )
            ThinkingLevel.MAX -> EffortVisual(
                labelColor = if (dark) Color(0xFFF5C2E7) else Color(0xFF7F1D1D),
                trackColors = listOf(Color(0xFF3F0A13), Color(0xFF9F1239), Color(0xFFE11D48), Color(0xFFFB7185)),
                glowColor = Color(0x75F43F5E),
                shimmerAlpha = 0.42f,
                stars = true,
            )
            ThinkingLevel.OFF -> error("handled above")
        }

        // Default MONO (PI Mono): sharp obsidian -> crimson
        DdThemeVisualFamily.MONO, DdThemeVisualFamily.CUSTOM -> when (level) {
            ThinkingLevel.LOW -> EffortVisual(
                labelColor = if (dark) Color(0xFFD4D4DC) else Color(0xFF3F3F46),
                trackColors = listOf(Color(0xFF3F3F46), Color(0xFF71717A)),
                glowColor = Color(0x3D52525B),
                shimmerAlpha = 0.16f,
                stars = false,
            )
            ThinkingLevel.MEDIUM -> EffortVisual(
                labelColor = if (dark) Color(0xFFFCA5A5) else Color(0xFF9F1239),
                trackColors = listOf(Color(0xFF27272A), Color(0xFF881337), Color(0xFFBE123C)),
                glowColor = Color(0x52BE123C),
                shimmerAlpha = 0.24f,
                stars = false,
            )
            ThinkingLevel.HIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFFFB7185) else Color(0xFF9F1239),
                trackColors = listOf(Color(0xFF18181B), Color(0xFF9F1239), Color(0xFFE11D48)),
                glowColor = Color(0x61E11D48),
                shimmerAlpha = 0.30f,
                stars = false,
            )
            ThinkingLevel.XHIGH -> EffortVisual(
                labelColor = if (dark) Color(0xFFFDA4AF) else Color(0xFF881337),
                trackColors = listOf(Color(0xFF111116), Color(0xFF881337), Color(0xFFE11D48), Color(0xFFFB7185)),
                glowColor = Color(0x70E11D48),
                shimmerAlpha = 0.36f,
                stars = true,
            )
            ThinkingLevel.MAX -> EffortVisual(
                labelColor = if (dark) Color(0xFFFECDD3) else Color(0xFF7F1D1D),
                trackColors = listOf(Color(0xFF09090B), Color(0xFF9F1239), Color(0xFFE11D48), Color(0xFFFDA4AF)),
                glowColor = Color(0x7AF43F5E),
                shimmerAlpha = 0.42f,
                stars = true,
            )
            ThinkingLevel.OFF -> error("handled above")
        }
    }
}

@Composable
internal fun ThinkingEffortSlider(
    levels: List<ThinkingLevel>,
    value: ThinkingLevel,
    onChange: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.assistant_thinking_sheet),
) {
    val ordered = remember(levels) {
        levels.distinct().sortedBy { it.ordinal }.ifEmpty { listOf(ThinkingLevel.OFF) }
    }
    val colors = superPiColors()
    val family = LocalDdThemeVisualFamily.current
    val motionEnabled = LocalDdMotionEnabled.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val committedIndex = remember(ordered, value) {
        ordered.indexOf(value).takeIf { it >= 0 } ?: 0
    }
    var dragging by remember { mutableStateOf(false) }
    var dragPreviewIndex by remember { mutableStateOf<Int?>(null) }
    val activeIndex = if (dragging) (dragPreviewIndex ?: committedIndex).coerceIn(0, ordered.lastIndex) else committedIndex
    val activeLevel = ordered[activeIndex]
    val maxIndex = (ordered.size - 1).coerceAtLeast(1)
    val targetFraction = if (ordered.size <= 1) 1f else activeIndex.toFloat() / maxIndex.toFloat()

    val latestOrdered by rememberUpdatedState(ordered)
    val latestCommittedIndex by rememberUpdatedState(committedIndex)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestHapticsEnabled by rememberUpdatedState(hapticsEnabled)
    val latestIsRtl by rememberUpdatedState(isRtl)

    val visual = remember(family, activeLevel, colors) {
        resolveEffortVisual(family, activeLevel, colors)
    }
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = if (motionEnabled && !dragging) {
            tween(durationMillis = DdMotion.BASE, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "effort-fraction",
    )
    val animatedLabelColor by animateColorAsState(
        targetValue = visual.labelColor,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST) else snap(),
        label = "effort-label-color",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.14f else 1f,
        animationSpec = if (motionEnabled) tween(140) else snap(),
        label = "effort-thumb-scale",
    )

    val shimmerActive = motionEnabled && activeLevel != ThinkingLevel.OFF && visual.shimmerAlpha > 0f
    val shimmerState: State<Float>? = if (shimmerActive) {
        val transition = rememberInfiniteTransition(label = "effort-shimmer")
        transition.animateFloat(
            initialValue = -0.35f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (visual.stars) 2100 else 2800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "effort-shimmer-x",
        )
    } else {
        null
    }

    val starPhaseState: State<Float>? = if (motionEnabled && visual.stars) {
        val transition = rememberInfiniteTransition(label = "effort-stars")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "effort-star-phase",
        )
    } else {
        null
    }

    val shimmerColors = remember(visual.shimmerAlpha) {
        listOf(
            Color.Transparent,
            Color.White.copy(alpha = visual.shimmerAlpha),
            Color.Transparent,
        )
    }

    val currentLabel = thinkingLabel(activeLevel)

    fun indexFromX(xPx: Float, widthPx: Float): Int {
        val usable = widthPx.coerceAtLeast(1f)
        val raw = (xPx / usable).coerceIn(0f, 1f)
        val ratio = if (latestIsRtl) 1f - raw else raw
        return (ratio * (latestOrdered.size - 1)).roundToInt().coerceIn(0, latestOrdered.lastIndex)
    }

    fun updatePreviewIndex(index: Int) {
        val clamped = index.coerceIn(0, latestOrdered.lastIndex)
        if (clamped != (dragPreviewIndex ?: latestCommittedIndex)) {
            if (latestHapticsEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            dragPreviewIndex = clamped
        }
    }

    fun commitIndex(index: Int) {
        val clamped = index.coerceIn(0, latestOrdered.lastIndex)
        if (clamped != latestCommittedIndex) {
            if (!dragging && latestHapticsEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            latestOnChange(latestOrdered[clamped])
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { stateDescription = "$title $currentLabel" }
            .testTag("assistant_thinking_row"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = currentLabel,
                style = DdType.label.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = animatedLabelColor,
                maxLines = 1,
                softWrap = false,
            )
        }

        Spacer(Modifier.height(6.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(ordered.size) {
                    detectTapGestures { offset ->
                        commitIndex(indexFromX(offset.x, size.width.toFloat()))
                    }
                }
                .pointerInput(ordered.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val idx = indexFromX(offset.x, size.width.toFloat())
                            dragPreviewIndex = idx
                            if (idx != latestCommittedIndex && latestHapticsEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            val finalIdx = dragPreviewIndex
                            dragging = false
                            dragPreviewIndex = null
                            if (finalIdx != null) {
                                commitIndex(finalIdx)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            dragPreviewIndex = null
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            updatePreviewIndex(indexFromX(change.position.x, size.width.toFloat()))
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbSize = 18.dp
            val trackHeight = 10.dp
            val thumbSizePx = with(density) { thumbSize.toPx() }
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val usablePx = (maxWidthPx - thumbSizePx).coerceAtLeast(1f)
            val fillWidthDp = with(density) {
                (thumbSizePx * 0.5f + usablePx * animatedFraction).coerceAtLeast(trackHeight.toPx()).toDp()
            }
            val thumbOffsetX = (usablePx * animatedFraction).roundToInt()

            // Background track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(colors.hairline.copy(alpha = if (colors.dark) 0.72f else 0.85f)),
            )

            // Active gradient fill + shimmer + twinkling stars (state reads deferred into Canvas)
            Box(
                modifier = Modifier
                    .size(width = fillWidthDp, height = trackHeight)
                    .then(
                        if (visual.glowColor != Color.Transparent) {
                            Modifier.shadow(
                                elevation = if (visual.stars) 6.dp else 3.dp,
                                shape = CircleShape,
                                ambientColor = visual.glowColor,
                                spotColor = visual.glowColor,
                                clip = false,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(visual.trackColors)),
            ) {
                if (shimmerState != null || starPhaseState != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val shimmerProgress = shimmerState?.value
                        if (shimmerProgress != null && size.width > 12f) {
                            val beamHalf = (size.width * 0.24f).coerceAtLeast(24f)
                            val centerX = size.width * shimmerProgress
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = shimmerColors,
                                    start = Offset(centerX - beamHalf, 0f),
                                    end = Offset(centerX + beamHalf, size.height),
                                ),
                            )
                        }
                        val starPhase = starPhaseState?.value
                        if (starPhase != null && size.width > 28f) {
                            EFFORT_STARS.forEach { star ->
                                val wave = (sin((starPhase + star.phaseOffset) * 2.0 * PI) * 0.5 + 0.5).toFloat()
                                val alpha = 0.22f + wave * 0.78f
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
            }

            // Subtle step ticks on the inactive side (RTL-aware)
            if (ordered.size > 2) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight),
                ) {
                    val stepSpan = (size.width - thumbSizePx).coerceAtLeast(1f)
                    ordered.indices.forEach { idx ->
                        if (idx > activeIndex) {
                            val ltrX = thumbSizePx * 0.5f + stepSpan * (idx.toFloat() / maxIndex.toFloat())
                            val cx = if (isRtl) size.width - ltrX else ltrX
                            drawCircle(
                                color = colors.inkTertiary.copy(alpha = 0.45f),
                                radius = 1.6.dp.toPx(),
                                center = Offset(cx, size.height * 0.5f),
                            )
                        }
                    }
                }
            }

            // Draggable thumb
            val thumbAccent = if (activeLevel == ThinkingLevel.OFF) {
                colors.inkTertiary
            } else {
                visual.trackColors.last()
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetX, 0) }
                    .size(thumbSize)
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .shadow(
                        elevation = if (dragging) 5.dp else 2.dp,
                        shape = CircleShape,
                        clip = false,
                    )
                    .clip(CircleShape)
                    .background(colors.raised)
                    .border(2.dp, thumbAccent, CircleShape),
            )
        }

        Spacer(Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ordered.forEachIndexed { index, level ->
                val isSelected = index == activeIndex
                val label = thinkingLabel(level)
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { commitIndex(index) }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .testTag("assistant_thinking_${level.name.lowercase()}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = DdType.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (isSelected) animatedLabelColor else colors.inkTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
