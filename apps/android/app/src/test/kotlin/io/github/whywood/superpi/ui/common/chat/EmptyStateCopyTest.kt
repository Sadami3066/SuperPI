package io.github.whywood.superpi.ui.common.chat

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyStateCopyTest {
    @Test
    fun greetingTimeBandMapsHoursCleanly() {
        assertEquals(GreetingTimeBand.LATE_NIGHT, greetingTimeBand(0))
        assertEquals(GreetingTimeBand.LATE_NIGHT, greetingTimeBand(4))
        assertEquals(GreetingTimeBand.MORNING, greetingTimeBand(5))
        assertEquals(GreetingTimeBand.MORNING, greetingTimeBand(10))
        assertEquals(GreetingTimeBand.AFTERNOON, greetingTimeBand(11))
        assertEquals(GreetingTimeBand.AFTERNOON, greetingTimeBand(16))
        assertEquals(GreetingTimeBand.SUNDOWN, greetingTimeBand(17))
        assertEquals(GreetingTimeBand.SUNDOWN, greetingTimeBand(19))
        assertEquals(GreetingTimeBand.NIGHT, greetingTimeBand(20))
        assertEquals(GreetingTimeBand.NIGHT, greetingTimeBand(23))
    }

    @Test
    fun selectNormalGreetingIncludesGeneralAndTimeBandOptions() {
        val general = (1..11).map { "general-$it" }
        val morning = "morning-egg"
        val afternoon = "afternoon-egg"
        val sundown = "sundown-egg"
        val night = listOf("night-1", "night-2")
        val lateNight = listOf("late-1", "late-2")

        val rng = Random(42)
        val morningSamples = (0 until 300).map {
            selectNormalGreeting(general, morning, afternoon, sundown, night, lateNight, 8, rng)
        }.toSet()
        assertTrue(morning in morningSamples)
        assertFalse(afternoon in morningSamples)
        assertFalse(sundown in morningSamples)
        assertFalse("night-1" in morningSamples)
        assertFalse("late-1" in morningSamples)

        val night23Samples = (0 until 300).map {
            selectNormalGreeting(general, morning, afternoon, sundown, night, lateNight, 23, rng)
        }.toSet()
        assertTrue("night-1" in night23Samples)
        assertTrue("night-2" in night23Samples)
        assertFalse(sundown in night23Samples)
        assertFalse("late-1" in night23Samples)

        val lateNightSamples = (0 until 300).map {
            selectNormalGreeting(general, morning, afternoon, sundown, night, lateNight, 2, rng)
        }.toSet()
        assertTrue("late-1" in lateNightSamples)
        assertTrue("late-2" in lateNightSamples)
        assertFalse(sundown in lateNightSamples)
        assertFalse("night-1" in lateNightSamples)
    }

    @Test
    fun formatGreetingTemplateReplacesNicknameCleanly() {
        assertEquals("又上线了，Alex。", formatGreetingTemplate("又上线了，{name}。", " Alex "))
        assertEquals("又上线了。", formatGreetingTemplate("又上线了。", ""))
    }

    @Test
    fun pickRandomItemsReturnsDistinctSubsetWithoutDuplicates() {
        val pool = (1..12).map { "prompt-$it" }
        val picked = pickRandomItems(pool, EMPTY_STARTER_COUNT, Random(42))
        assertEquals(3, picked.size)
        assertEquals(3, picked.distinct().size)
        assertTrue(picked.all { it in pool })
    }
}
