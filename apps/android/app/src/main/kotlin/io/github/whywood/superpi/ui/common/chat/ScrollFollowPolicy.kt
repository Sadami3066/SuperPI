package io.github.whywood.superpi.ui.common.chat

import kotlin.math.exp

internal object ScrollFollowPolicy {
    const val NearBottomThresholdDp = 80f
    const val MaxSmoothOverflowDp = 320f
    private const val DampingRate = 18f
    private const val SnapThresholdPx = 1.5f
    private const val MinDeltaSeconds = 0.004f
    private const val MaxDeltaSeconds = 0.05f

    fun nearBottom(distancePx: Float, thresholdPx: Float): Boolean = distancePx <= thresholdPx

    fun followStepPx(
        overflowPx: Float,
        deltaSeconds: Float,
        maxSmoothOverflowPx: Float,
        smooth: Boolean,
    ): Float {
        if (overflowPx <= 0f) return 0f
        if (!smooth || overflowPx <= SnapThresholdPx || overflowPx > maxSmoothOverflowPx) {
            return overflowPx
        }
        val dt = deltaSeconds.coerceIn(MinDeltaSeconds, MaxDeltaSeconds)
        val fraction = 1f - exp(-DampingRate * dt)
        return maxOf(overflowPx * fraction, SnapThresholdPx).coerceAtMost(overflowPx)
    }
}
