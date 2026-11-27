package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.runtime.RuntimeEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Host-level automatic retry controller for transient provider/network failures.
 * Mirrors Android's AutoRetryController: waits with short backoff when `autoRetry` is enabled,
 * emits a live `RuntimeActivity.Retrying(attempt)` indicator, and cancels immediately on user
 * stop, new user input, or session removal.
 */
class HostAutoRetryController(
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val isBusy: (sessionId: String) -> Boolean,
    private val awaitIdle: suspend (sessionId: String) -> Unit,
    private val emitRetrying: suspend (sessionId: String, runId: String, attempt: Int) -> Unit,
    private val retryTurn: suspend (sessionId: String) -> Unit,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
) {
    private val attemptsBySession = ConcurrentHashMap<String, Int>()
    private val pendingJobs = ConcurrentHashMap<String, Job>()
    private val activeRetries = ConcurrentHashMap.newKeySet<String>()

    fun onRuntimeEvent(sessionId: String, event: RuntimeEvent) {
        when (event) {
            is RuntimeEvent.RunStarted -> {
                // Cancel any pending backoff timer; live retries already detached themselves before invoking retryTurn.
                pendingJobs.remove(sessionId)?.cancel()
                // Reset retry counter for fresh user-initiated runs, while preserving it for internal retryTurn starts.
                if (!activeRetries.remove(sessionId)) {
                    attemptsBySession.remove(sessionId)
                }
            }
            is RuntimeEvent.RunFinished, is RuntimeEvent.SessionRemoved -> {
                cancel(sessionId)
            }
            is RuntimeEvent.RunFailed -> {
                handleFailure(sessionId, event.runId, event.message)
            }
            else -> Unit
        }
    }

    fun cancel(sessionId: String) {
        pendingJobs.remove(sessionId)?.cancel()
        attemptsBySession.remove(sessionId)
    }

    private fun handleFailure(sessionId: String, runId: String, message: String) {
        if (!isEnabled()) return
        if (!isTransientFailure(message)) {
            attemptsBySession.remove(sessionId)
            return
        }
        val nextAttempt = (attemptsBySession[sessionId] ?: 0) + 1
        if (nextAttempt > maxAttempts) {
            attemptsBySession.remove(sessionId)
            return
        }
        attemptsBySession[sessionId] = nextAttempt
        pendingJobs.remove(sessionId)?.cancel()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                emitRetrying(sessionId, runId.ifBlank { "retry-$nextAttempt" }, nextAttempt)
                delay(baseDelayMs * nextAttempt)
                if (!isEnabled()) {
                    attemptsBySession.remove(sessionId)
                    return@launch
                }
                if (isBusy(sessionId)) {
                    awaitIdle(sessionId)
                }
                if (!isEnabled()) {
                    attemptsBySession.remove(sessionId)
                    return@launch
                }
                // Detach from pendingJobs BEFORE invoking retryTurn so RunStarted emitted
                // inside retryLastTurn does NOT cancel this running retry coroutine!
                if (!pendingJobs.remove(sessionId, job)) {
                    return@launch
                }
                activeRetries.add(sessionId)
                try {
                    retryTurn(sessionId)
                } finally {
                    activeRetries.remove(sessionId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                attemptsBySession.remove(sessionId)
            } finally {
                pendingJobs.remove(sessionId, job)
            }
        }
        pendingJobs[sessionId] = job
        job.start()
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BASE_DELAY_MS = 2_000L

        internal fun isTransientFailure(rawMessage: String): Boolean {
            val msg = rawMessage.lowercase()
            if (msg.isBlank()) return false
            val nonRetryable = listOf(
                "401", "403", "unauthorized", "forbidden", "invalid api key", "invalid_api_key",
                "authentication", "not configured", "model not found", "context length",
                "maximum context", "token limit", "aborted", "cancelled", "canceled",
                "insufficient_quota", "billing", "payment required", "402",
            )
            if (nonRetryable.any { it in msg }) return false

            val transientMarkers = listOf(
                "429", "rate limit", "too many requests", "overloaded",
                "500", "502", "503", "504", "bad gateway", "service unavailable",
                "gateway timeout", "internal server error", "temporarily unavailable",
                "timeout", "timed out", "connection reset", "econnreset", "broken pipe",
                "unexpected eof", "eof", "socket", "network", "unable to resolve host",
                "connection refused", "stream closed",
            )
            return transientMarkers.any { it in msg }
        }
    }
}
