package io.github.whywood.superpi.ui.miniapp

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiniappNotificationsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("miniapp-reminders-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun reminderStoreRoundTripsAcrossFreshInstances() {
        val file = File(dir, "miniapp-reminders.json")
        val initial = MiniappReminderStore(file)
        val reminder = StoredReminder(
            appId = "todo",
            appName = "Todo",
            id = "rem-1",
            title = "Standup",
            body = "Join call",
            at = 1_760_000_000_000L,
            repeatDaily = true,
        )

        initial.upsert(reminder)

        val reloaded = MiniappReminderStore(file)
        assertEquals(listOf(reminder), reloaded.forApp("todo"))
        assertEquals(reminder, reloaded.find("todo", "rem-1"))

        reloaded.remove("todo", "rem-1")
        assertNull(MiniappReminderStore(file).find("todo", "rem-1"))
    }

    @Test
    fun bootReconcileRollsMissedDailyForwardAndRemovesExpiredOneShot() {
        val zone = ZoneId.of("Asia/Shanghai")
        val file = File(dir, "miniapp-reminders.json")
        val store = MiniappReminderStore(file)
        val missedDailyAt = ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val futureOneShotAt = ZonedDateTime.of(2026, 11, 25, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val expiredOneShotAt = ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

        store.upsert(StoredReminder("app", "App", "daily", "Daily", "", missedDailyAt, repeatDaily = true))
        store.upsert(StoredReminder("app", "App", "future", "Future", "", futureOneShotAt, repeatDaily = false))
        store.upsert(StoredReminder("app", "App", "expired", "Expired", "", expiredOneShotAt, repeatDaily = false))

        val plan = reconcileRemindersOnBoot(store, now, zone)
        val expectedNextDaily = ZonedDateTime.of(2026, 11, 26, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(setOf("daily", "future"), plan.toSchedule.map { it.id }.toSet())
        assertEquals(expectedNextDaily, plan.toSchedule.first { it.id == "daily" }.at)
        assertEquals(listOf("expired"), plan.toCancel.map { it.id })

        val persisted = MiniappReminderStore(file)
        assertEquals(expectedNextDaily, persisted.find("app", "daily")?.at)
        assertNull(persisted.find("app", "expired"))
        assertEquals(futureOneShotAt, persisted.find("app", "future")?.at)
    }

    @Test
    fun nextDailyOccurrencePreservesWallClockTimeAcrossDstTransition() {
        val ny = ZoneId.of("America/New_York")
        val beforeDst = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        val atTrigger = beforeDst + 1_000L
        val next = nextDailyOccurrence(beforeDst, atTrigger, ny)
        val expectedAfterDst = ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(expectedAfterDst, next)
        assertTrue(next - beforeDst == 23L * 60 * 60 * 1000)
    }
}
