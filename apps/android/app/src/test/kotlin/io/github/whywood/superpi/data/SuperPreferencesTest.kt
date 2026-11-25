package io.github.whywood.superpi.data

import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperPreferencesTest {
    @Test
    fun normalizesSuperSettingsWithinSanctionedBounds() {
        assertEquals(SUPER_CONCURRENCY_MIN, normalizeSuperConcurrency(0))
        assertEquals(SUPER_CONCURRENCY_MAX, normalizeSuperConcurrency(99))
        assertEquals(3, normalizeSuperConcurrency(3))

        assertEquals(SUPER_SLOTS_MIN, normalizeSuperSlotsPerBot(1))
        assertEquals(SUPER_SLOTS_MAX, normalizeSuperSlotsPerBot(25))
        assertEquals(7, normalizeSuperSlotsPerBot(7))

        assertEquals(0, normalizeSuperStallTimeoutMinutes(0))
        assertEquals(15, normalizeSuperStallTimeoutMinutes(15))
        assertEquals(30, normalizeSuperStallTimeoutMinutes(99))

        assertEquals(0, normalizeSuperQuestionTimeoutMinutes(0))
        assertEquals(5, normalizeSuperQuestionTimeoutMinutes(5))
        assertEquals(5, normalizeSuperQuestionTimeoutMinutes(7))

        assertEquals(0L, normalizeSuperCoalesceWindowMs(0L))
        assertEquals(3_000L, normalizeSuperCoalesceWindowMs(3_000L))
        assertEquals(0L, normalizeSuperCoalesceWindowMs(42L))

        assertEquals(500, normalizeSuperReceiptMaxChars(500))
        assertEquals(3_000, normalizeSuperReceiptMaxChars(3_000))
        assertEquals(500, normalizeSuperReceiptMaxChars(123))

        assertEquals(15, normalizeSuperIdleEvictMinutes(15))
        assertEquals(5, normalizeSuperIdleEvictMinutes(2))
    }

    @Test
    fun mapsUiPreferencesToSuperRuntimeSettings() {
        val defaults = UiPreferences().toSuperRuntimeSettings()
        assertEquals(SuperRuntimeSettings(), defaults)

        val custom = UiPreferences(
            superMaxConcurrentWorkers = 4,
            superMaxSlotsPerBot = 8,
            superStallTimeoutMinutes = 0,
            superQuestionTimeoutMinutes = 5,
            superWorkerJobWake = false,
            superCoalesceWindowMs = 3_000L,
            superReceiptMaxChars = 3_000,
            superIdleEvictMinutes = 30,
            superWorkerCompactionOverrideEnabled = true,
            superWorkerCompactionPercent = 80,
        ).toSuperRuntimeSettings()

        assertEquals(4, custom.maxConcurrentWorkers)
        assertEquals(8, custom.maxSlotsPerBot)
        assertEquals(0L, custom.stallTimeoutMs)
        assertEquals(5 * 60_000L, custom.questionTimeoutMs)
        assertFalse(custom.workerJobWake)
        assertEquals(3_000L, custom.coalesceWindowMs)
        assertEquals(3_000, custom.receiptMaxChars)
        assertEquals(30 * 60_000L, custom.idleEvictMs)
        assertTrue(custom.workerCompactionOverrideEnabled)
        assertEquals(80, custom.workerCompactionTriggerPercent)
    }
}
