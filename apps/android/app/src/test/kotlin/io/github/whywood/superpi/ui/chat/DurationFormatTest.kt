package io.github.whywood.superpi.ui.chat

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.ui.common.chat.formatElapsed
import io.github.whywood.superpi.ui.common.chat.processDurationMs
import io.github.whywood.superpi.ui.common.chat.processDurationSeconds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationFormatTest {
    private val enHoursMinutesSeconds = "%1\$dh %2\$dm %3\$ds"
    private val enMinutesSeconds = "%1\$dm %2\$ds"
    private val enSeconds = "%1\$ds"
    private val zhHoursMinutesSeconds = "%1\$d 小时 %2\$d 分 %3\$d 秒"
    private val zhMinutesSeconds = "%1\$d 分 %2\$d 秒"
    private val zhSeconds = "%1\$d 秒"

    private fun en(totalSeconds: Long): String =
        formatElapsed(totalSeconds, enHoursMinutesSeconds, enMinutesSeconds, enSeconds)

    private fun zh(totalSeconds: Long): String =
        formatElapsed(totalSeconds, zhHoursMinutesSeconds, zhMinutesSeconds, zhSeconds)

    @Test
    fun secondsOnlyBelowOneMinute() {
        assertEquals("0s", en(0))
        assertEquals("9s", en(9))
        assertEquals("59s", en(59))
        assertEquals("0 秒", zh(0))
        assertEquals("59 秒", zh(59))
    }

    @Test
    fun minutesAndSecondsBelowOneHour() {
        assertEquals("1m 0s", en(60))
        assertEquals("1m 5s", en(65))
        assertEquals("59m 59s", en(3599))
        assertEquals("1 分 5 秒", zh(65))
        assertEquals("59 分 59 秒", zh(3599))
    }

    @Test
    fun hoursMinutesSecondsFromOneHour() {
        assertEquals("1h 0m 0s", en(3600))
        assertEquals("1h 2m 3s", en(3723))
        assertEquals("10h 0m 59s", en(36059))
        assertEquals("1 小时 2 分 3 秒", zh(3723))
        assertEquals("10 小时 0 分 59 秒", zh(36059))
    }

    @Test
    fun negativeClampsToZero() {
        assertEquals("0s", en(-5))
        assertEquals("0 秒", zh(-5))
    }

    @Test
    fun processDurationFloorsSubSecondToOneSecondAndComputesTotalWhenFollowedByText() {
        assertEquals(1L, processDurationSeconds(0L))
        assertEquals(1L, processDurationSeconds(350L))
        assertEquals(5L, processDurationSeconds(5999L))

        val completed = AssistantMessage(
            id = 1L,
            role = AssistantRole.ASSISTANT,
            text = "",
            startedAt = 1_000L,
            finishedAt = 1_250L,
            streaming = false,
        )
        assertEquals(
            250L,
            processDurationMs(
                steps = listOf(AssistantStep.Reasoning(id = 1L, text = "ok")),
                message = completed,
                running = false,
                showTotalDuration = true,
            ),
        )

        val multiBlockSteps = listOf(
            AssistantStep.Tool(TraceTool.trace(1L, durationMs = 200L)),
            AssistantStep.Tool(TraceTool.trace(2L, durationMs = 300L)),
        )
        assertEquals(
            500L,
            processDurationMs(
                steps = multiBlockSteps,
                message = completed,
                running = false,
                showTotalDuration = false,
            ),
        )
        assertNull(
            processDurationMs(
                steps = listOf(AssistantStep.Reasoning(id = 2L, text = "only thought")),
                message = completed,
                running = false,
                showTotalDuration = false,
            ),
        )
    }

    private object TraceTool {
        fun trace(id: Long, durationMs: Long?): ToolTrace = ToolTrace(
            id = id,
            name = "read",
            arguments = emptyMap(),
            durationMs = durationMs,
        )
    }
}
