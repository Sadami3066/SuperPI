package io.github.whywood.superpi.ui.common.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowPolicyTest {
    @Test
    fun nearBottomUsesThreshold() {
        assertTrue(ScrollFollowPolicy.nearBottom(distancePx = 0f, thresholdPx = 80f))
        assertTrue(ScrollFollowPolicy.nearBottom(distancePx = 80f, thresholdPx = 80f))
        assertFalse(ScrollFollowPolicy.nearBottom(distancePx = 80.5f, thresholdPx = 80f))
    }

    @Test
    fun followStepSnapsWhenDisabledSmallOrLarge() {
        assertEquals(0f, ScrollFollowPolicy.followStepPx(0f, 1f / 60f, 960f, smooth = true), 0.001f)
        assertEquals(72f, ScrollFollowPolicy.followStepPx(72f, 1f / 60f, 960f, smooth = false), 0.001f)
        assertEquals(1.4f, ScrollFollowPolicy.followStepPx(1.4f, 1f / 60f, 960f, smooth = true), 0.001f)
        assertEquals(1200f, ScrollFollowPolicy.followStepPx(1200f, 1f / 60f, 960f, smooth = true), 0.001f)
    }

    @Test
    fun followStepScalesDampingAcrossFrameRates() {
        val step60 = ScrollFollowPolicy.followStepPx(72f, 1f / 60f, 960f, smooth = true)
        val step120 = ScrollFollowPolicy.followStepPx(72f, 1f / 120f, 960f, smooth = true)
        assertTrue(step120 > 0f && step120 < step60)
        assertTrue(step60 > 0f && step60 < 72f)
    }
}
