package io.github.whywood.superpi.ui.common.chat

internal data class SoftSpringStep(
    val stepPx: Float,
    val nextVelocityPxPerSec: Float,
    val settled: Boolean,
)

internal object ScrollFollowPolicy {
    const val NearBottomThresholdDp = 80f
    const val AngularFrequencyRadPerSec = 10f
    const val DampingRatio = 1f
    const val PanelHeightSpringStiffness = 120f
    private const val SnapThresholdPx = 0.5f
    private const val SettleVelocityPxPerSec = 8f
    private const val MinDeltaSeconds = 0.004f
    private const val MaxDeltaSeconds = 1f / 30f

    fun nearBottom(distancePx: Float, thresholdPx: Float): Boolean = distancePx <= thresholdPx

    fun softSpringStep(
        overflowPx: Float,
        velocityPxPerSec: Float,
        deltaSeconds: Float,
        smooth: Boolean,
    ): SoftSpringStep {
        if (overflowPx <= 0f) {
            return SoftSpringStep(stepPx = 0f, nextVelocityPxPerSec = 0f, settled = true)
        }
        if (!smooth) {
            return SoftSpringStep(stepPx = overflowPx, nextVelocityPxPerSec = 0f, settled = true)
        }
        val h = deltaSeconds.coerceIn(MinDeltaSeconds, MaxDeltaSeconds)
        val omega = AngularFrequencyRadPerSec
        val zeta = DampingRatio
        val k = omega * omega
        val d = 2f * zeta * omega
        val gamma = 1f / (h * (d + h * k))
        val bias = h * k * gamma * (-overflowPx)
        val impulse = -(velocityPxPerSec + bias) / (1f + gamma)
        val nextVelocity = (velocityPxPerSec + impulse).coerceAtLeast(0f)
        val rawStep = h * nextVelocity
        val remaining = overflowPx - rawStep
        if ((remaining <= SnapThresholdPx && nextVelocity <= SettleVelocityPxPerSec) || rawStep >= overflowPx) {
            return SoftSpringStep(stepPx = overflowPx, nextVelocityPxPerSec = 0f, settled = true)
        }
        return SoftSpringStep(stepPx = rawStep, nextVelocityPxPerSec = nextVelocity, settled = false)
    }
}
