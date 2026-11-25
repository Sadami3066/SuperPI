package io.github.whywood.superpi.floating

import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.UserQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowPolicyTest {
    @Test
    fun floatingWindowRemainsVisibleWheneverEnabledAndOverlayPermitted() {
        assertTrue(floatingWindowVisible(enabled = true, canDrawOverlays = true))
        assertFalse(floatingWindowVisible(enabled = false, canDrawOverlays = true))
        assertFalse(floatingWindowVisible(enabled = true, canDrawOverlays = false))
    }

    @Test
    fun foregroundSuppressesAllBubblesIncludingSubscribedTracking() {
        val state = reduceFloatingFeed(
            FloatingFeedState(
                runs = listOf(FloatingRun("s1", "订阅任务", RuntimeActivity.Thinking, 100L)),
                questions = listOf(
                    PendingQuestion(
                        requestId = "q1",
                        sessionId = "s1",
                        toolCallId = "call-1",
                        questions = listOf(UserQuestion("q", "选项", "选一个", listOf(QuestionOption("A")))),
                    ),
                ),
                subscription = FloatingSubscription(
                    sessionId = "s1",
                    title = "订阅任务",
                    startedAt = 100L,
                    activity = RuntimeActivity.Thinking,
                    text = "进度更新",
                    updatedAt = 150L,
                ),
            ),
            FloatingFeedEvent.Completed("s2", "完成任务", RunStatus.DONE, "已完成", null, whileForeground = false),
            200L,
        )

        assertTrue(floatingSurfaceEventQueue(state, panelOpen = false, appForeground = true).isEmpty())
        assertTrue(floatingSurfaceEventQueue(state, panelOpen = true, appForeground = false).isEmpty())

        val backgroundQueue = floatingSurfaceEventQueue(state, panelOpen = false, appForeground = false)
        assertEquals(3, backgroundQueue.size)
        assertTrue(backgroundQueue.any { it is FloatingEventCard.Tracking })
        assertTrue(backgroundQueue.any { it is FloatingEventCard.Question })
        assertTrue(backgroundQueue.any { it is FloatingEventCard.Completed })
    }

    @Test
    fun leavingForegroundResumesLiveSubscriptionAndQuestionButNotForegroundCompletions() {
        val withFgCompletion = reduceFloatingFeed(
            FloatingFeedState(
                runs = listOf(FloatingRun("s1", "进行中", RuntimeActivity.Thinking, 100L)),
                questions = listOf(
                    PendingQuestion(
                        requestId = "q1",
                        sessionId = "s2",
                        toolCallId = "call-1",
                        questions = listOf(UserQuestion("q", "确认", "继续?", listOf(QuestionOption("好")))),
                    ),
                ),
                subscription = FloatingSubscription(
                    sessionId = "s1",
                    title = "进行中",
                    startedAt = 100L,
                    activity = RuntimeActivity.Thinking,
                    text = "输出中",
                    updatedAt = 150L,
                ),
            ),
            FloatingFeedEvent.Completed("s3", "前台完成", RunStatus.DONE, "已完成", null, whileForeground = true),
            300L,
        )

        assertTrue(floatingSurfaceEventQueue(withFgCompletion, panelOpen = false, appForeground = true).isEmpty())

        val resumed = floatingSurfaceEventQueue(withFgCompletion, panelOpen = false, appForeground = false)
        assertEquals(2, resumed.size)
        assertTrue(resumed[0] is FloatingEventCard.Question)
        assertTrue(resumed[1] is FloatingEventCard.Tracking)
        assertFalse(resumed.any { it is FloatingEventCard.Completed })
    }

    @Test
    fun panelBackdropScrimAndSurfaceOrderFollowPanelState() {
        assertFalse(floatingNeedsDismissScrim(panelOpen = false))
        assertTrue(floatingNeedsDismissScrim(panelOpen = true))

        assertFalse(floatingSurfaceMustRecreateForScrim(panelOpen = false, hasScrim = false, hasSurface = true))
        assertFalse(floatingSurfaceMustRecreateForScrim(panelOpen = true, hasScrim = true, hasSurface = true))
        assertFalse(floatingSurfaceMustRecreateForScrim(panelOpen = true, hasScrim = false, hasSurface = false))
        assertTrue(floatingSurfaceMustRecreateForScrim(panelOpen = true, hasScrim = false, hasSurface = true))

        assertTrue(floatingBubbleToggleNextPanelOpen(panelOpen = false))
        assertFalse(floatingBubbleToggleNextPanelOpen(panelOpen = true))
    }
}
