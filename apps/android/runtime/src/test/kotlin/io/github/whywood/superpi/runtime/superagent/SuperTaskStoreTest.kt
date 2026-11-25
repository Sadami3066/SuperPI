package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.WorkerStepSnapshot
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SuperTaskStoreTest {
    private lateinit var root: File
    private var nowMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("super-task-store-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun whitelistDefaultsHomeTrueOthersFalseAndPersists() = runTest {
        val store = SuperTaskStore(root, clock = { nowMs })
        store.ensure()

        assertTrue(store.isBotEnabled(SuperRuntime.HOME_BOT_REF))
        assertFalse(store.isBotEnabled("local:coding"))
        assertFalse(store.isBotEnabled("remote:dev-2:home"))

        store.setBotEnabled("local:coding", true)
        store.setBotEnabled(SuperRuntime.HOME_BOT_REF, false)
        assertTrue(store.isBotEnabled("local:coding"))
        assertFalse(store.isBotEnabled(SuperRuntime.HOME_BOT_REF))
        store.close()

        val reopened = SuperTaskStore(root, clock = { nowMs })
        reopened.ensure()
        assertTrue(reopened.isBotEnabled("local:coding"))
        assertFalse(reopened.isBotEnabled(SuperRuntime.HOME_BOT_REF))
        assertFalse(reopened.isBotEnabled("remote:dev-2:home"))
        reopened.close()
    }

    @Test
    fun persistsWorkersStepsArtifactsAndCardsAcrossReopen() = runTest {
        val store = SuperTaskStore(root, clock = { nowMs })
        val step = WorkerStepSnapshot(
            callId = "call-1",
            toolName = "bash",
            argsJson = "{\"command\":\"ls\"}",
            status = ToolActivityStatus.DONE,
            outputPreview = "ok",
            updatedAt = nowMs,
        )
        val artifact = WorkerArtifactRef(
            artifactId = "art_1",
            workerId = "wrk-1",
            botRef = SuperRuntime.HOME_BOT_REF,
            kind = "image",
            path = File(root, "chart.png").absolutePath,
            name = "chart.png",
            title = "Chart",
            mimeType = "image/png",
            sizeBytes = 123L,
            createdAt = nowMs,
        )
        val card = WorkerCardRef(
            cardId = "call-2",
            workerId = "wrk-1",
            botRef = SuperRuntime.HOME_BOT_REF,
            toolName = "weather",
            title = "Weather",
            payloadJson = "{\"temp\":22}",
            createdAt = nowMs,
        )
        val worker = WorkerRunRecord(
            workerId = "wrk-1",
            botRef = SuperRuntime.HOME_BOT_REF,
            botId = "home",
            botName = "PI",
            deviceId = null,
            deviceName = "Pixel",
            title = "Inspect project",
            status = WorkerStatus.IDLE,
            lastOutcome = WorkerOutcome.SUCCEEDED,
            turnCount = 1,
            createdAt = nowMs,
            updatedAt = nowMs,
            lastProgressAt = nowMs,
            lastSummary = "Done",
            steps = listOf(step),
            artifacts = listOf(artifact),
            cards = listOf(card),
        )
        store.upsertWorker(worker)
        store.close()

        val reopened = SuperTaskStore(root, clock = { nowMs })
        reopened.ensure()
        val loaded = reopened.getWorker("wrk-1")
        assertNotNull(loaded)
        assertEquals("Inspect project", loaded!!.title)
        assertEquals(1, loaded.steps.size)
        assertEquals("call-1", loaded.steps.single().callId)
        assertEquals(artifact, reopened.findArtifact("wrk-1", "art_1"))
        assertEquals(artifact, reopened.findArtifactById("art_1"))
        assertEquals(card, reopened.cardById("call-2"))

        assertTrue(reopened.deleteWorker("wrk-1"))
        assertNull(reopened.getWorker("wrk-1"))
        assertNull(reopened.cardById("call-2"))
        reopened.close()
    }

    @Test
    fun recoverStaleLocalWorkersMarksActiveLocalWorkersFailedOnly() = runTest {
        val store = SuperTaskStore(root, clock = { nowMs })
        val runningLocal = WorkerRunRecord(
            workerId = "wrk-local-run",
            botRef = SuperRuntime.HOME_BOT_REF,
            botId = "home",
            botName = "PI",
            deviceName = "Pixel",
            title = "Running local",
            status = WorkerStatus.RUNNING,
            steps = listOf(WorkerStepSnapshot("c1", "bash", status = ToolActivityStatus.RUNNING)),
        )
        val idleLocal = runningLocal.copy(
            workerId = "wrk-local-idle",
            title = "Idle local",
            status = WorkerStatus.IDLE,
            lastOutcome = WorkerOutcome.SUCCEEDED,
            steps = emptyList(),
        )
        val runningRemote = runningLocal.copy(
            workerId = "wrk-remote-run",
            botRef = SuperRuntime.remoteBotRef("dev-2", "home"),
            deviceId = "dev-2",
            deviceName = "Tablet",
            title = "Running remote",
            steps = emptyList(),
        )
        store.upsertWorker(runningLocal)
        store.upsertWorker(idleLocal)
        store.upsertWorker(runningRemote)

        nowMs += 5_000L
        val recovered = store.recoverStaleLocalWorkers()
        assertEquals(listOf("wrk-local-run"), recovered.map { it.workerId })

        val updatedLocal = store.getWorker("wrk-local-run")!!
        assertEquals(WorkerStatus.FAILED, updatedLocal.status)
        assertEquals(WorkerOutcome.FAILED, updatedLocal.lastOutcome)
        assertEquals(ToolActivityStatus.FAILED, updatedLocal.steps.single().status)

        assertEquals(WorkerStatus.IDLE, store.getWorker("wrk-local-idle")!!.status)
        assertEquals(WorkerStatus.RUNNING, store.getWorker("wrk-remote-run")!!.status)
        store.close()
    }
}
