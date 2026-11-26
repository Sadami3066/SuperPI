package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.runtime.RuntimeEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAutoRetryControllerTest {
    @Test
    fun classifiesTransientVsPermanentErrors() {
        assertTrue("429 should retry", HostAutoRetryController.isTransientFailure("HTTP 429 Too Many Requests"))
        assertTrue("503 should retry", HostAutoRetryController.isTransientFailure("503 Service Unavailable"))
        assertTrue("timeout should retry", HostAutoRetryController.isTransientFailure("Request timed out"))
        assertFalse("401 must not retry", HostAutoRetryController.isTransientFailure("HTTP 401 Unauthorized: invalid api key"))
        assertFalse("403 must not retry", HostAutoRetryController.isTransientFailure("403 Forbidden"))
        assertFalse("abort must not retry", HostAutoRetryController.isTransientFailure("Request aborted by user"))
    }

    @Test
    fun retriesTransientFailureAndCancelsOnStop() = runBlocking {
        val retried = AtomicInteger(0)
        val emittedAttempts = mutableListOf<Int>()
        val controller = HostAutoRetryController(
            scope = this,
            isEnabled = { true },
            isBusy = { false },
            awaitIdle = {},
            emitRetrying = { _, _, attempt -> emittedAttempts += attempt },
            retryTurn = { retried.incrementAndGet() },
            maxAttempts = 2,
            baseDelayMs = 40L,
        )

        controller.onRuntimeEvent("s1", RuntimeEvent.RunFailed(runId = "r1", message = "HTTP 502 Bad Gateway"))
        delay(80L)
        assertEquals(1, retried.get())
        assertEquals(listOf(1), emittedAttempts)

        // Cancel before delay fires
        controller.onRuntimeEvent("s1", RuntimeEvent.RunFailed(runId = "r2", message = "HTTP 503 Service Unavailable"))
        controller.cancel("s1")
        delay(120L)
        assertEquals(1, retried.get())
    }

    @Test
    fun runStartedDuringRetryDoesNotSelfCancel() = runBlocking {
        val completed = AtomicInteger(0)
        lateinit var controller: HostAutoRetryController
        controller = HostAutoRetryController(
            scope = this,
            isEnabled = { true },
            isBusy = { false },
            awaitIdle = {},
            emitRetrying = { _, _, _ -> },
            retryTurn = { sid ->
                // Real runtimeHost.retryLastTurn emits RunStarted synchronously before awaiting provider!
                controller.onRuntimeEvent(
                    sid,
                    RuntimeEvent.RunStarted(runId = "r-retry", prompt = "retry", startedAt = System.currentTimeMillis()),
                )
                delay(30L)
                completed.incrementAndGet()
            },
            maxAttempts = 2,
            baseDelayMs = 20L,
        )

        controller.onRuntimeEvent("s2", RuntimeEvent.RunFailed(runId = "r1", message = "HTTP 429 rate limit"))
        delay(120L)
        assertEquals("Live retry coroutine must NOT self-cancel on RunStarted", 1, completed.get())
    }
}
