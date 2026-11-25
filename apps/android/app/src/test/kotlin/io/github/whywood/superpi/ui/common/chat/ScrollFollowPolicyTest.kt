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
    fun softSpringSnapsWhenDisabledOrNonPositive() {
        val empty = ScrollFollowPolicy.softSpringStep(0f, 50f, 1f / 60f, smooth = true)
        assertEquals(0f, empty.stepPx, 0.001f)
        assertEquals(0f, empty.nextVelocityPxPerSec, 0.001f)
        assertTrue(empty.settled)

        val unsmooth = ScrollFollowPolicy.softSpringStep(420f, 20f, 1f / 60f, smooth = false)
        assertEquals(420f, unsmooth.stepPx, 0.001f)
        assertEquals(0f, unsmooth.nextVelocityPxPerSec, 0.001f)
        assertTrue(unsmooth.settled)
    }

    @Test
    fun softSpringAcceleratesGentlyAndGlidesLargeOverflowWithoutOvershoot() {
        val dt = 1f / 60f
        var remaining = 1200f
        var velocity = 0f

        val frame1 = ScrollFollowPolicy.softSpringStep(remaining, velocity, dt, smooth = true)
        assertFalse(frame1.settled)
        assertTrue(frame1.stepPx > 0f && frame1.stepPx < 60f)
        remaining -= frame1.stepPx
        velocity = frame1.nextVelocityPxPerSec

        val frame2 = ScrollFollowPolicy.softSpringStep(remaining, velocity, dt, smooth = true)
        assertFalse(frame2.settled)
        assertTrue(frame2.stepPx > frame1.stepPx)
        remaining -= frame2.stepPx
        velocity = frame2.nextVelocityPxPerSec

        var frames = 2
        while (frames < 240 && remaining > 0f) {
            val step = ScrollFollowPolicy.softSpringStep(remaining, velocity, dt, smooth = true)
            assertTrue(step.stepPx in 0f..(remaining + 0.001f))
            remaining -= step.stepPx
            velocity = step.nextVelocityPxPerSec
            frames++
            if (step.settled) break
        }
        assertEquals(0f, remaining, 0.01f)
        assertEquals(0f, velocity, 0.01f)
    }

    @Test
    fun softSpringWorksSmoothlyAt120HzAndPreservesVelocityWhenRetargeted() {
        val step60 = ScrollFollowPolicy.softSpringStep(180f, 0f, 1f / 60f, smooth = true)
        val step120 = ScrollFollowPolicy.softSpringStep(180f, 0f, 1f / 120f, smooth = true)
        assertTrue(step120.stepPx > 0f && step120.stepPx < step60.stepPx)

        val movingStep = ScrollFollowPolicy.softSpringStep(
            overflowPx = 200f,
            velocityPxPerSec = step60.nextVelocityPxPerSec,
            deltaSeconds = 1f / 60f,
            smooth = true,
        )
        val fromRestStep = ScrollFollowPolicy.softSpringStep(
            overflowPx = 200f,
            velocityPxPerSec = 0f,
            deltaSeconds = 1f / 60f,
            smooth = true,
        )
        assertTrue(movingStep.stepPx > fromRestStep.stepPx)
        assertFalse(movingStep.settled)
    }
}
