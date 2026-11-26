package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAgentRunSummary
import io.github.whywood.superpi.runtime.MemoryDispatchIntent
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface ConsolidationOutcome {
    data class Done(val summary: MemoryAgentRunSummary) : ConsolidationOutcome

    data class Skipped(val reason: String) : ConsolidationOutcome

    data object Paused : ConsolidationOutcome

    data object NoModel : ConsolidationOutcome

    data object Busy : ConsolidationOutcome

    data class Failed(val error: String) : ConsolidationOutcome
}

data class ManualConsolidationState(
    val running: Boolean = false,
    val outcome: ConsolidationOutcome? = null,
    val outcomeSeq: Long = 0L,
)

class MemoryAgentService(
    private val store: MemoryStore,
    gateway: MemoryAgentGateway,
    private val scope: CoroutineScope,
    private val isSessionExcluded: suspend (String) -> Boolean = { false },
    private val isPaused: suspend () -> Boolean = { false },
    private val settingsProvider: suspend () -> MemorySettings = { MemorySettings() },
    private val userGuidanceProvider: suspend () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val consolidationCoordinator: MemoryConsolidationCoordinator = MemoryConsolidationCoordinator.NOOP,
) {
    private val runner = MemoryAgentRunner(
        store = store,
        gateway = gateway,
        userGuidanceProvider = userGuidanceProvider,
        clock = clock,
    )
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionExclusionVersions = ConcurrentHashMap<String, Long>()
    private val agentRunMutex = Mutex()
    private val workerStarted = AtomicBoolean(false)
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val inFlightDispatches = AtomicInteger(0)
    private val highPriorityWaiters = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
    private val consolidationRunning = AtomicBoolean(false)
    private val retryWakeLock = Any()

    private var workerJob: Job? = null
    private var retryWakeJob: Job? = null

    private val _manualState = MutableStateFlow(ManualConsolidationState())
    val manualState: StateFlow<ManualConsolidationState> = _manualState.asStateFlow()

    suspend fun enqueueDispatch(
        sessionId: String?,
        intent: MemoryDispatchIntent,
        detail: String,
    ): String {
        val payload = buildJsonObject {
            put("intent", intent.wire)
            put("detail", MemoryLimits.clampText(detail, 4_000))
        }.toString()
        inFlightDispatches.incrementAndGet()
        val job = try {
            store.enqueueDispatchJob(
                jobKind = DispatchJobKind.DISPATCH_INTENT,
                sessionId = sessionId,
                payloadJson = payload,
                transcriptHash = MemoryTranscriptExtractor.sha256("${intent.wire}:$detail"),
            )
        } catch (t: Throwable) {
            if (inFlightDispatches.get() > 0) inFlightDispatches.decrementAndGet()
            throw t
        }
        ensureWorkerStarted()
        wakeSignal.trySend(Unit)
        return job.id
    }

    suspend fun awaitHighPriority(timeoutMs: Long = DEFAULT_AWAIT_HIGH_PRIORITY_MS) {
        if (timeoutMs <= 0L || isPaused()) return
        if (inFlightDispatches.get() <= 0 && !store.hasPendingHighPriorityJob()) return
        val waiter = CompletableDeferred<Unit>()
        highPriorityWaiters += waiter
        if (inFlightDispatches.get() <= 0 && !store.hasPendingHighPriorityJob()) {
            highPriorityWaiters.remove(waiter)
            waiter.complete(Unit)
            return
        }
        ensureWorkerStarted()
        wakeSignal.trySend(Unit)
        withTimeoutOrNull(timeoutMs) {
            waiter.await()
        }
        highPriorityWaiters.remove(waiter)
    }

    fun launchIdleSession(
        sessionId: String,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        if (entries.isNullOrEmpty()) return
        val captured = CapturedTranscript(sessionId, sessionFile, entries.toList())
        scope.launch(ioDispatcher) {
            try {
                stageCapturedSession(
                    captured = captured,
                    jobKind = DispatchJobKind.SESSION_IDLE,
                    minInitialMessages = DEFAULT_MIN_INITIAL_MESSAGES,
                    minIncrementalMessages = DEFAULT_MIN_INCREMENTAL_MESSAGES,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "idle session memory staging failed", error)
            }
        }
    }

    fun launchCompactionSession(
        sessionId: String,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        if (entries.isNullOrEmpty()) return
        val captured = CapturedTranscript(sessionId, sessionFile, entries.toList())
        scope.launch(ioDispatcher) {
            try {
                stageCapturedSession(
                    captured = captured,
                    jobKind = DispatchJobKind.SESSION_COMPACT,
                    minInitialMessages = 2,
                    minIncrementalMessages = 2,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "compaction session memory staging failed", error)
            }
        }
    }

    fun launchSessionClose(sessionId: String) {
        val captured = store.takeCaptured(sessionId) ?: return
        scope.launch(ioDispatcher) {
            try {
                stageCapturedSession(
                    captured = captured,
                    jobKind = DispatchJobKind.SESSION_CLOSE,
                    minInitialMessages = DEFAULT_MIN_INITIAL_MESSAGES,
                    minIncrementalMessages = DEFAULT_MIN_INCREMENTAL_MESSAGES,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn(TAG, "session close memory staging failed", error)
            }
        }
    }

    suspend fun onSessionExclusionChanged(
        sessionId: String,
        excluded: Boolean,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        lockFor(sessionId).withLock {
            sessionExclusionVersions[sessionId] = (sessionExclusionVersions[sessionId] ?: 0L) + 1L
            store.takeCaptured(sessionId)
            store.deleteSessionJobs(sessionId)
            if (!excluded) {
                advanceTailWatermarkLocked(sessionId, sessionFile, entries)
            }
        }
    }

    suspend fun stageArchivedSessionDelta(
        sourceSessionId: String,
        archivedSessionId: String,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        val delta = lockFor(sourceSessionId).withLock {
            store.takeCaptured(sourceSessionId)
            store.deleteSessionJobs(sourceSessionId)
            val watermark = store.readWatermark(sourceSessionId)
            val sliced = if (entries.isNullOrEmpty() || isSessionExcluded(sourceSessionId)) {
                emptyList()
            } else {
                MemoryTranscriptExtractor.deltaEntries(entries, watermark)
            }
            store.deleteWatermark(sourceSessionId)
            sliced
        }
        if (delta.isNotEmpty()) {
            withContext(ioDispatcher) {
                stageCapturedSession(
                    captured = CapturedTranscript(archivedSessionId, sessionFile, delta),
                    jobKind = DispatchJobKind.SESSION_CLOSE,
                    minInitialMessages = 1,
                    minIncrementalMessages = 1,
                )
            }
        }
    }

    suspend fun advanceSessionWatermark(
        sessionId: String,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        lockFor(sessionId).withLock {
            sessionExclusionVersions[sessionId] = (sessionExclusionVersions[sessionId] ?: 0L) + 1L
            store.takeCaptured(sessionId)
            store.deleteSessionJobs(sessionId)
            advanceTailWatermarkLocked(sessionId, sessionFile, entries)
        }
    }

    fun discardCaptured(sessionId: String) {
        store.takeCaptured(sessionId)
    }

    fun launchRetryPending() {
        cancelScheduledRetry()
        ensureWorkerStarted()
        wakeSignal.trySend(Unit)
    }

    fun launchRetryPendingNow() {
        scope.launch(ioDispatcher) {
            retryPendingNow()
        }
    }

    suspend fun retryPendingNow() {
        cancelScheduledRetry()
        store.resetRetryBackoffNow()
        ensureWorkerStarted()
        wakeSignal.trySend(Unit)
    }

    suspend fun pendingDispatchCount(): Int = store.overview().pendingDispatchJobs

    suspend fun onPauseModeChanged(paused: Boolean) {
        if (paused) {
            cancelScheduledRetry()
            store.openPauseWindow()
            releaseHighPriorityWaiters()
        } else {
            val closed = store.closePauseWindow()
            if (closed) {
                launchRetryPending()
            }
        }
    }

    fun launchConsolidationIfDue() {
        scope.launch(ioDispatcher) {
            val hours = settingsProvider().coldConsolidationHours
            if (hours <= 0) return@launch
            runConsolidation(
                manual = false,
                minIntervalMs = hours.toLong() * 3_600_000L,
                minNewEvents = STARTUP_MIN_NEW_EVENTS,
            )
        }
    }

    fun launchManualConsolidation() {
        scope.launch(ioDispatcher) {
            val outcome = runConsolidation(
                manual = true,
                minIntervalMs = 0L,
                minNewEvents = 0,
            )
            _manualState.update { current ->
                ManualConsolidationState(
                    running = if (outcome == ConsolidationOutcome.Busy) current.running else false,
                    outcome = outcome,
                    outcomeSeq = current.outcomeSeq + 1L,
                )
            }
        }
    }

    suspend fun runConsolidationFromWorker(): ConsolidationOutcome {
        val hours = settingsProvider().coldConsolidationHours
        if (hours <= 0) return ConsolidationOutcome.Skipped("automatic_cold_disabled")
        return runConsolidation(
            manual = false,
            minIntervalMs = hours.toLong() * 3_600_000L,
            minNewEvents = WORKER_MIN_NEW_EVENTS,
        )
    }

    internal suspend fun drainQueueOnceForTesting() {
        drainAvailableJobs()
    }

    internal suspend fun runConsolidation(
        manual: Boolean,
        minIntervalMs: Long,
        minNewEvents: Int,
    ): ConsolidationOutcome {
        if (!consolidationRunning.compareAndSet(false, true)) {
            return ConsolidationOutcome.Busy
        }
        if (manual) {
            _manualState.value = _manualState.value.copy(running = true)
        }
        var leaseId: String? = null
        return try {
            if (isPaused()) return ConsolidationOutcome.Paused
            if (!manual && settingsProvider().coldConsolidationHours <= 0) {
                return ConsolidationOutcome.Skipped("automatic_cold_disabled")
            }

            when (val lease = consolidationCoordinator.prepareForConsolidation(manual, minIntervalMs)) {
                is ConsolidationLeaseResult.Acquired -> leaseId = lease.leaseId
                is ConsolidationLeaseResult.Blocked -> {
                    return if (lease.reason == "busy") {
                        ConsolidationOutcome.Busy
                    } else {
                        ConsolidationOutcome.Skipped(lease.reason)
                    }
                }

                ConsolidationLeaseResult.Offline,
                ConsolidationLeaseResult.Unauthorized,
                ConsolidationLeaseResult.Rejected,
                -> {
                    if (!manual) return ConsolidationOutcome.Skipped("sync_unavailable")
                }
            }

            agentRunMutex.withLock {
                val (expired, archived, _) = store.runDeterministicCleanup()
                if (isPaused()) return@withLock ConsolidationOutcome.Paused

                val nowMs = clock()
                val localColdAt = store.latestSuccessfulColdRunAt()
                val remoteColdAt = store.remoteLastConsolidationAt()
                val lastColdAt = when {
                    localColdAt != null && remoteColdAt != null -> maxOf(localColdAt, remoteColdAt)
                    localColdAt != null -> localColdAt
                    else -> remoteColdAt
                } ?: 0L
                val elapsed = if (lastColdAt <= 0L) Long.MAX_VALUE else nowMs - lastColdAt
                val newEvents = store.countNewEventsSince(lastColdAt)

                if (!manual) {
                    if (settingsProvider().coldConsolidationHours <= 0) {
                        return@withLock ConsolidationOutcome.Skipped("automatic_cold_disabled")
                    }
                    if (lastColdAt > 0L && elapsed < minIntervalMs) {
                        return@withLock ConsolidationOutcome.Skipped("cooldown_active")
                    }
                    if (newEvents < minNewEvents && expired == 0 && archived == 0) {
                        return@withLock ConsolidationOutcome.Skipped("insufficient_new_events")
                    }
                }

                when (val outcome = runner.runColdConsolidation()) {
                    is MemoryAgentOutcome.Done -> {
                        consolidationCoordinator.onConsolidationCompleted(
                            leaseId,
                            outcome.summary.finishedAt ?: clock(),
                        )
                        ConsolidationOutcome.Done(outcome.summary)
                    }

                    MemoryAgentOutcome.Paused -> ConsolidationOutcome.Paused
                    MemoryAgentOutcome.NoModel -> ConsolidationOutcome.NoModel
                    is MemoryAgentOutcome.Failed -> ConsolidationOutcome.Failed(outcome.error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn(TAG, "consolidation failed", error)
            ConsolidationOutcome.Failed(error.message ?: "consolidation failed")
        } finally {
            withContext(NonCancellable) {
                runCatching { consolidationCoordinator.onConsolidationFinished(leaseId) }
            }
            consolidationRunning.set(false)
            if (manual) {
                _manualState.value = _manualState.value.copy(running = false)
            }
        }
    }

    internal suspend fun stageCapturedSession(
        captured: CapturedTranscript,
        jobKind: DispatchJobKind,
        minInitialMessages: Int,
        minIncrementalMessages: Int,
    ): Boolean = lockFor(captured.sessionId).withLock {
        if (isSessionExcluded(captured.sessionId)) {
            advanceTailWatermarkLocked(captured.sessionId, captured.sessionFile, captured.entries)
            store.deleteSessionJobs(captured.sessionId)
            return@withLock false
        }
        val windows = store.readPauseWindows()
        val watermark = store.readWatermark(captured.sessionId)
        val delta = MemoryTranscriptExtractor.deltaEntries(captured.entries, watermark)
        val minMessages = if (watermark?.lastEntryId == null && jobKind != DispatchJobKind.SESSION_COMPACT) {
            minInitialMessages
        } else {
            minIncrementalMessages
        }

        if (isPaused()) {
            val hasOpenWindow = windows.any { it.endMs == null }
            if (!hasOpenWindow) {
                advanceTailWatermarkLocked(captured.sessionId, captured.sessionFile, captured.entries)
                return@withLock false
            }
            if (delta.isNotEmpty()) {
                val eligiblePrePause = MemoryTranscriptExtractor.filterOutPausedEntries(delta, windows)
                val extracted = MemoryTranscriptExtractor.extract(
                    eligiblePrePause,
                    minMessages,
                )
                if (extracted != null && extracted.hash != watermark?.lastHash) {
                    enqueueSessionJob(captured, jobKind, extracted, pauseNote = true)
                    return@withLock true
                }
            }
            return@withLock false
        }

        if (delta.isEmpty()) return@withLock false
        val eligible = MemoryTranscriptExtractor.filterOutPausedEntries(delta, windows)
        val extracted = MemoryTranscriptExtractor.extract(
            eligible,
            minMessages,
        )
        val tailMsgId = delta.lastOrNull { it is SessionMessageEntry }?.id
        if (extracted == null) {
            if (eligible.none { it is SessionMessageEntry } && tailMsgId != null) {
                store.saveWatermark(
                    IngestionWatermark(
                        sessionId = captured.sessionId,
                        sessionFile = captured.sessionFile ?: watermark?.sessionFile,
                        lastEntryId = tailMsgId,
                        lastHash = watermark?.lastHash,
                        updatedAt = clock(),
                    ),
                )
            }
            return@withLock false
        }

        if (extracted.hash == watermark?.lastHash) {
            if (tailMsgId != null) {
                store.saveWatermark(
                    IngestionWatermark(
                        sessionId = captured.sessionId,
                        sessionFile = captured.sessionFile ?: watermark?.sessionFile,
                        lastEntryId = tailMsgId,
                        lastHash = extracted.hash,
                        updatedAt = clock(),
                    ),
                )
            }
            return@withLock false
        }

        enqueueSessionJob(captured, jobKind, extracted, pauseNote = false)
        ensureWorkerStarted()
        wakeSignal.trySend(Unit)
        true
    }

    private suspend fun enqueueSessionJob(
        captured: CapturedTranscript,
        jobKind: DispatchJobKind,
        extracted: ExtractedTranscript,
        pauseNote: Boolean,
    ) {
        val payload = buildJsonObject {
            put("reason", if (pauseNote) "paused_pre_window" else jobKind.wire.lowercase())
            put("transcript", extracted.text)
            extracted.lastEntryId?.let { put("last_entry_id", it) }
            captured.sessionFile?.let { put("session_file", it) }
        }.toString()
        store.enqueueDispatchJob(
            jobKind = jobKind,
            sessionId = captured.sessionId,
            payloadJson = payload,
            transcriptHash = extracted.hash,
            replaceSameSessionKind = true,
        )
    }

    private fun ensureWorkerStarted() {
        if (!workerStarted.compareAndSet(false, true)) return
        workerJob = scope.launch(ioDispatcher) {
            try {
                while (true) {
                    drainAvailableJobs()
                    wakeSignal.receive()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                workerStarted.set(false)
            }
        }
    }

    private suspend fun drainAvailableJobs() {
        if (isPaused()) {
            cancelScheduledRetry()
            releaseHighPriorityWaiters()
            return
        }
        store.pruneExhaustedJobs(MAX_JOB_ATTEMPTS)
        var consecutiveFailures = 0
        while (true) {
            if (isPaused()) {
                cancelScheduledRetry()
                releaseHighPriorityWaiters()
                return
            }
            val nextJob = store.dequeueNextJob(MAX_JOB_ATTEMPTS) ?: break
            val isDispatch = nextJob.jobKind == DispatchJobKind.DISPATCH_INTENT
            val exclusionVersionBefore = nextJob.sessionId?.let { sessionExclusionVersions[it] ?: 0L } ?: 0L
            try {
                if (nextJob.sessionId != null &&
                    nextJob.jobKind != DispatchJobKind.DISPATCH_INTENT &&
                    isSessionExcluded(nextJob.sessionId)
                ) {
                    store.completeDispatchJob(nextJob.id)
                    continue
                }
                val outcome = agentRunMutex.withLock {
                    runner.runHotJob(nextJob)
                }
                when (outcome) {
                    is MemoryAgentOutcome.Done -> {
                        consecutiveFailures = 0
                        if (nextJob.sessionId != null && nextJob.jobKind != DispatchJobKind.DISPATCH_INTENT) {
                            markWatermarkForJob(nextJob, exclusionVersionBefore)
                        }
                        store.completeDispatchJob(nextJob.id)
                    }
                    MemoryAgentOutcome.Paused,
                    MemoryAgentOutcome.NoModel,
                    -> {
                        releaseHighPriorityWaiters()
                        return
                    }
                    is MemoryAgentOutcome.Failed -> {
                        store.failDispatchJob(nextJob.id, outcome.error)
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            break
                        }
                    }
                }
            } finally {
                if (isDispatch) {
                    if (inFlightDispatches.get() > 0) inFlightDispatches.decrementAndGet()
                    if (!store.hasPendingHighPriorityJob()) {
                        releaseHighPriorityWaiters()
                    }
                }
            }
        }
        releaseHighPriorityWaiters()
        scheduleRetryWakeIfNeeded()
    }

    private suspend fun scheduleRetryWakeIfNeeded() {
        if (isPaused()) {
            cancelScheduledRetry()
            return
        }
        val nextRetryAt = store.earliestNextRetryAt(MAX_JOB_ATTEMPTS) ?: run {
            cancelScheduledRetry()
            return
        }
        val waitMs = (nextRetryAt - clock()).coerceAtLeast(MIN_RETRY_WAKE_DELAY_MS)
        synchronized(retryWakeLock) {
            retryWakeJob?.cancel()
            retryWakeJob = scope.launch(ioDispatcher) {
                delay(waitMs)
                synchronized(retryWakeLock) {
                    retryWakeJob = null
                }
                ensureWorkerStarted()
                wakeSignal.trySend(Unit)
            }
        }
    }

    private fun cancelScheduledRetry() {
        synchronized(retryWakeLock) {
            retryWakeJob?.cancel()
            retryWakeJob = null
        }
    }

    private suspend fun markWatermarkForJob(job: DispatchQueueJob, expectedExclusionVersion: Long) {
        val sessionId = job.sessionId ?: return
        lockFor(sessionId).withLock {
            if ((sessionExclusionVersions[sessionId] ?: 0L) != expectedExclusionVersion) return@withLock
            if (isSessionExcluded(sessionId)) return@withLock
            val payload = MemoryJsonSupport.parseObject(job.payloadJson)
            val entryId = payload?.optStr("last_entry_id")
            val sessionFile = payload?.optStr("session_file")
            val existing = store.readWatermark(sessionId)
            if (existing != null && existing.updatedAt > job.updatedAt) return@withLock
            store.saveWatermark(
                IngestionWatermark(
                    sessionId = sessionId,
                    sessionFile = sessionFile ?: existing?.sessionFile,
                    lastEntryId = entryId ?: existing?.lastEntryId,
                    lastHash = job.transcriptHash ?: existing?.lastHash,
                    updatedAt = clock(),
                ),
            )
        }
    }

    private suspend fun advanceTailWatermarkLocked(
        sessionId: String,
        sessionFile: String?,
        entries: List<SessionEntry>?,
    ) {
        val lastMessageId = entries?.lastOrNull { it is SessionMessageEntry }?.id ?: return
        val existing = store.readWatermark(sessionId)
        store.saveWatermark(
            IngestionWatermark(
                sessionId = sessionId,
                sessionFile = sessionFile ?: existing?.sessionFile,
                lastEntryId = lastMessageId,
                lastHash = existing?.lastHash,
                updatedAt = clock(),
            ),
        )
    }

    private fun releaseHighPriorityWaiters() {
        if (highPriorityWaiters.isEmpty()) return
        val snapshot = highPriorityWaiters.toList()
        highPriorityWaiters.removeAll(snapshot.toSet())
        snapshot.forEach { it.complete(Unit) }
    }

    private fun lockFor(sessionId: String): Mutex = sessionLocks.getOrPut(sessionId) { Mutex() }

    private companion object {
        private const val TAG = "MemoryAgentService"
        private const val DEFAULT_AWAIT_HIGH_PRIORITY_MS = 1_500L
        private const val DEFAULT_MIN_INITIAL_MESSAGES = 4
        private const val DEFAULT_MIN_INCREMENTAL_MESSAGES = 2
        private const val MAX_JOB_ATTEMPTS = 5
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MIN_RETRY_WAKE_DELAY_MS = 1_000L
        private const val STARTUP_MIN_NEW_EVENTS = 5
        private const val WORKER_MIN_NEW_EVENTS = 1
    }
}
