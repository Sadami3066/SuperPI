package io.github.whywood.superpi.data.relay

import android.util.Log
import io.github.whywood.superpi.runtime.memory.DreamLeaseResult
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchCheck
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchMode
import io.github.whywood.superpi.runtime.memory.MemoryDreamCoordinator
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.MemorySyncOpItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RelayMemorySyncErrorKind {
    Unauthorized,
    Offline,
    Rejected,
}

data class MemoryAccountSwitchPrompt(
    val accountKey: String,
    val accountId: String,
    val origin: String,
    val localMemoryCount: Int,
)

data class RelayMemorySyncState(
    val syncing: Boolean = false,
    val serverSeq: Long = 0L,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
    val errorKind: RelayMemorySyncErrorKind? = null,
    val supported: Boolean = true,
    val pendingAccountSwitch: MemoryAccountSwitchPrompt? = null,
)

enum class SyncRunStatus {
    Synced,
    NoAccount,
    Unsupported,
    Offline,
    Unauthorized,
    Rejected,
    PendingAccountSwitch,
}

class RelayMemorySync(
    private val store: MemoryStore,
    private val accounts: RelayAccountStore,
    private val client: RelayMemoryApi,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val relayCapabilities: () -> List<String> = { emptyList() },
    private val onPostMergeConsolidation: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryDreamCoordinator {
    private val started = AtomicBoolean(false)
    private val syncMutex = Mutex()
    private val scheduleMutex = Mutex()
    private val unsupportedOrigins = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var applyingRemote = false

    private var debounceJob: Job? = null
    private var activeSyncJob: Job? = null
    private var pendingResyncDelayMs: Long? = null
    private var lastObservedAccountKey: String? = null

    private val _state = MutableStateFlow(RelayMemorySyncState())
    val state: StateFlow<RelayMemorySyncState> = _state.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            accounts.account.collect { account ->
                if (account == null) {
                    lastObservedAccountKey = null
                    cancelScheduledSync()
                    _state.value = RelayMemorySyncState()
                } else {
                    val key = accountKey(account)
                    if (key != lastObservedAccountKey) {
                        lastObservedAccountKey = key
                        unsupportedOrigins.remove(account.origin)
                        _state.update {
                            it.copy(
                                supported = true,
                                lastError = null,
                                errorKind = null,
                                pendingAccountSwitch = null,
                            )
                        }
                        scheduleSync(0L)
                    }
                }
            }
        }
        scope.launch(io) {
            store.changes.drop(1).collect {
                if (applyingRemote) return@collect
                if (accounts.read() != null &&
                    _state.value.supported &&
                    _state.value.pendingAccountSwitch == null
                ) {
                    scheduleSync(LOCAL_PUSH_DEBOUNCE_MS)
                }
            }
        }
    }

    fun onRemoteVersionHint(serverSeq: Long) {
        scope.launch(io) {
            val current = accounts.read() ?: return@launch
            unsupportedOrigins.remove(current.origin)
            _state.update { it.copy(supported = true) }
            if (_state.value.pendingAccountSwitch != null) return@launch
            val knownSeq = _state.value.serverSeq
            if (serverSeq <= 0L || serverSeq > knownSeq) {
                scheduleSync(REMOTE_PULL_DEBOUNCE_MS)
            }
        }
    }

    fun requestSyncIfConnected() {
        scope.launch(io) {
            val account = accounts.read() ?: return@launch
            if (isSyncUnsupported(account)) return@launch
            if (_state.value.pendingAccountSwitch != null) return@launch
            scheduleSync(0L)
        }
    }

    suspend fun syncNow(): SyncRunStatus = syncInternal(dreamCompletedAt = null).status

    suspend fun resolveAccountSwitch(mode: MemoryAccountSwitchMode): SyncRunStatus {
        val resolved = syncMutex.withLock {
            val account = accounts.read()
            if (account == null) {
                _state.update { it.copy(pendingAccountSwitch = null) }
                return@withLock false
            }
            store.resolveAccountSwitch(
                accountKey = accountKey(account),
                accountIdentity = accountIdentity(account),
                mode = mode,
            )
            _state.update { it.copy(pendingAccountSwitch = null, errorKind = null, lastError = null) }
            true
        }
        if (!resolved) return SyncRunStatus.NoAccount
        val outcome = syncInternal(dreamCompletedAt = null)
        if (outcome.triggerPostMergeDream) {
            onPostMergeConsolidation()
        }
        return outcome.status
    }

    override suspend fun prepareForDream(forceManual: Boolean, minIntervalMs: Long): DreamLeaseResult {
        val account = accounts.read() ?: return DreamLeaseResult.Acquired(null)
        val token = accounts.token()?.takeIf { it.isNotBlank() } ?: return DreamLeaseResult.Acquired(null)
        if (isSyncUnsupported(account)) return DreamLeaseResult.Acquired(null)

        when (syncInternal(dreamCompletedAt = null).status) {
            SyncRunStatus.NoAccount,
            SyncRunStatus.Unsupported,
            -> return DreamLeaseResult.Acquired(null)

            SyncRunStatus.Offline -> return DreamLeaseResult.Offline
            SyncRunStatus.Unauthorized -> return DreamLeaseResult.Unauthorized
            SyncRunStatus.Rejected -> return DreamLeaseResult.Rejected
            SyncRunStatus.PendingAccountSwitch -> return DreamLeaseResult.Blocked("account_switch_pending")
            SyncRunStatus.Synced -> Unit
        }

        val intervalToCheck = if (forceManual) 0L else minIntervalMs.coerceAtLeast(0L)
        val lease = try {
            client.acquireDreamLease(
                account = account,
                token = token,
                ttlMs = DREAM_LEASE_TTL_MS,
                minIntervalMs = intervalToCheck,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RelayMemoryException) {
            return when (error.failure) {
                RelayMemoryFailure.Unauthorized -> DreamLeaseResult.Unauthorized
                RelayMemoryFailure.Rejected -> DreamLeaseResult.Rejected
                RelayMemoryFailure.NotFound -> {
                    unsupportedOrigins.add(account.origin)
                    _state.update { it.copy(supported = false) }
                    return DreamLeaseResult.Acquired(null)
                }
                RelayMemoryFailure.Unreachable,
                RelayMemoryFailure.BadResponse,
                -> DreamLeaseResult.Offline
            }
        }

        if (lease == null) {
            unsupportedOrigins.add(account.origin)
            _state.update { it.copy(supported = false) }
            return DreamLeaseResult.Acquired(null)
        }
        lease.lastDreamAt?.let { store.updateRemoteLastDreamAt(accountKey(account), it) }

        if (lease.acquired) {
            return DreamLeaseResult.Acquired(lease.leaseId)
        }
        val reason = if (!lease.holderDeviceId.isNullOrBlank()) "busy" else "not_due"
        return DreamLeaseResult.Blocked(reason)
    }

    override suspend fun onDreamCompleted(leaseId: String?, finishedAt: Long) {
        try {
            syncInternal(dreamCompletedAt = finishedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runCatching { Log.w(TAG, "post-dream memory sync push failed", error) }
        }
    }

    override suspend fun onDreamFinished(leaseId: String?) {
        if (leaseId.isNullOrBlank()) return
        withContext(NonCancellable) {
            val account = accounts.read() ?: return@withContext
            val token = accounts.token()?.takeIf { it.isNotBlank() } ?: return@withContext
            runCatching { client.releaseDreamLease(account, token, leaseId) }
        }
    }

    private fun scheduleSync(delayMs: Long) {
        scope.launch(io) {
            scheduleMutex.withLock {
                if (activeSyncJob?.isActive == true) {
                    val existing = pendingResyncDelayMs
                    pendingResyncDelayMs = if (existing == null) delayMs else minOf(existing, delayMs)
                    return@withLock
                }
                debounceJob?.cancel()
                debounceJob = scope.launch(io) {
                    if (delayMs > 0L) delay(delayMs)
                    runScheduledLoop()
                }
            }
        }
    }

    private suspend fun runScheduledLoop() {
        scheduleMutex.withLock {
            activeSyncJob = coroutineContext[Job]
            debounceJob = null
        }
        try {
            var nextDelay: Long? = 0L
            while (nextDelay != null) {
                if (nextDelay > 0L) delay(nextDelay)
                val outcome = syncInternal(dreamCompletedAt = null)
                if (outcome.triggerPostMergeDream) {
                    onPostMergeConsolidation()
                }
                nextDelay = scheduleMutex.withLock {
                    val queued = pendingResyncDelayMs
                    pendingResyncDelayMs = null
                    queued
                }
            }
        } finally {
            withContext(NonCancellable) {
                scheduleMutex.withLock {
                    if (activeSyncJob == coroutineContext[Job]) {
                        activeSyncJob = null
                    }
                }
            }
        }
    }

    private suspend fun cancelScheduledSync() {
        scheduleMutex.withLock {
            pendingResyncDelayMs = null
            debounceJob?.cancel()
            debounceJob = null
            activeSyncJob?.cancel()
            activeSyncJob = null
        }
    }

    private suspend fun syncInternal(dreamCompletedAt: Long?): SyncResult {
        var overlapDetected = false
        val status = syncMutex.withLock {
            val account = accounts.read() ?: return@withLock SyncRunStatus.NoAccount
            val token = accounts.token()?.takeIf { it.isNotBlank() } ?: return@withLock SyncRunStatus.NoAccount
            if (isSyncUnsupported(account)) {
                _state.update { it.copy(supported = false, syncing = false) }
                return@withLock SyncRunStatus.Unsupported
            }
            val key = accountKey(account)
            val identity = accountIdentity(account)
            store.ensure()
            when (val switchCheck = store.inspectAccountSwitch(identity)) {
                is MemoryAccountSwitchCheck.NeedsDecision -> {
                    _state.update {
                        it.copy(
                            syncing = false,
                            lastError = null,
                            errorKind = null,
                            pendingAccountSwitch = MemoryAccountSwitchPrompt(
                                accountKey = key,
                                accountId = account.accountId,
                                origin = account.origin,
                                localMemoryCount = switchCheck.localMemoryCount,
                            ),
                        )
                    }
                    return@withLock SyncRunStatus.PendingAccountSwitch
                }
                MemoryAccountSwitchCheck.Ready -> Unit
            }

            _state.update { it.copy(syncing = true, lastError = null, errorKind = null, pendingAccountSwitch = null) }
            try {
                val cursor = store.readSyncCursor(key)
                var currentPulledSeq = cursor.lastPulledServerSeq
                var latestServerSeq = cursor.lastPulledServerSeq

                val firstPull = pullAllLocked(account, key, token, currentPulledSeq)
                currentPulledSeq = firstPull.pulledSeq
                latestServerSeq = maxOf(latestServerSeq, firstPull.serverSeq)
                if (firstPull.hadOverlap) overlapDetected = true

                var dreamStampRemaining = dreamCompletedAt
                while (true) {
                    val batch = store.collectPendingPushBatch(key, account.deviceId, limit = PUSH_BATCH_SIZE)
                    if (batch.ops.isEmpty()) {
                        if (dreamStampRemaining != null) {
                            val response = client.push(account, token, emptyList(), dreamCompletedAt = dreamStampRemaining)
                            dreamStampRemaining = null
                            latestServerSeq = maxOf(latestServerSeq, response.serverSeq)
                            store.markPushAcked(key, batch, response.serverSeq, response.lastDreamAt)
                        } else if (batch.isBaseline && batch.nextInitialPushDone) {
                            store.markPushAcked(key, batch, latestServerSeq, null)
                        }
                        break
                    }
                    val isFinalBatch = (!batch.isBaseline && batch.ops.size < PUSH_BATCH_SIZE) ||
                        (batch.isBaseline && batch.nextInitialPushDone)
                    val chunks = splitOpsByByteBudget(batch.ops)
                    var lastRemoteDreamAt: Long? = null
                    for ((index, chunk) in chunks.withIndex()) {
                        val attachedDreamAt = if (isFinalBatch && index == chunks.lastIndex) dreamStampRemaining else null
                        val response = client.push(account, token, chunk, dreamCompletedAt = attachedDreamAt)
                        if (attachedDreamAt != null) dreamStampRemaining = null
                        latestServerSeq = maxOf(latestServerSeq, response.serverSeq)
                        lastRemoteDreamAt = response.lastDreamAt ?: lastRemoteDreamAt
                        if (response.rejectedCount > 0) {
                            val summary = response.rejected.joinToString("; ") { "${it.opId}:${it.reason}" }
                            runCatching { Log.w(TAG, "server rejected ${response.rejectedCount} sync ops: $summary") }
                        }
                    }
                    store.markPushAcked(key, batch, latestServerSeq, lastRemoteDreamAt)
                }

                if (latestServerSeq > currentPulledSeq) {
                    val trailingPull = pullAllLocked(account, key, token, currentPulledSeq)
                    currentPulledSeq = trailingPull.pulledSeq
                    latestServerSeq = maxOf(latestServerSeq, trailingPull.serverSeq)
                    if (trailingPull.hadOverlap) overlapDetected = true
                }

                _state.update {
                    it.copy(
                        syncing = false,
                        serverSeq = currentPulledSeq,
                        lastSyncedAt = clock(),
                        lastError = null,
                        errorKind = null,
                        supported = true,
                        pendingAccountSwitch = null,
                    )
                }
                SyncRunStatus.Synced
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(syncing = false) }
                throw cancelled
            } catch (error: RelayMemoryException) {
                when (error.failure) {
                    RelayMemoryFailure.NotFound -> {
                        unsupportedOrigins.add(account.origin)
                        _state.update { it.copy(syncing = false, supported = false, lastError = null, errorKind = null) }
                        SyncRunStatus.Unsupported
                    }
                    RelayMemoryFailure.Unauthorized -> {
                        _state.update {
                            it.copy(
                                syncing = false,
                                lastError = error.detail ?: error.failure.name,
                                errorKind = RelayMemorySyncErrorKind.Unauthorized,
                            )
                        }
                        SyncRunStatus.Unauthorized
                    }
                    RelayMemoryFailure.Rejected -> {
                        _state.update {
                            it.copy(
                                syncing = false,
                                lastError = error.detail ?: error.failure.name,
                                errorKind = RelayMemorySyncErrorKind.Rejected,
                            )
                        }
                        SyncRunStatus.Rejected
                    }
                    RelayMemoryFailure.Unreachable,
                    RelayMemoryFailure.BadResponse,
                    -> {
                        _state.update {
                            it.copy(
                                syncing = false,
                                lastError = error.detail ?: error.failure.name,
                                errorKind = RelayMemorySyncErrorKind.Offline,
                            )
                        }
                        SyncRunStatus.Offline
                    }
                }
            } catch (error: Exception) {
                runCatching { Log.w(TAG, "memory sync round failed", error) }
                _state.update {
                    it.copy(
                        syncing = false,
                        lastError = error.message ?: "sync failed",
                        errorKind = RelayMemorySyncErrorKind.Offline,
                    )
                }
                SyncRunStatus.Offline
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
        return SyncResult(
            status = status,
            triggerPostMergeDream = status == SyncRunStatus.Synced && overlapDetected && dreamCompletedAt == null,
        )
    }

    private fun splitOpsByByteBudget(ops: List<MemorySyncOpItem>): List<List<MemorySyncOpItem>> {
        if (ops.isEmpty()) return emptyList()
        val chunks = ArrayList<List<MemorySyncOpItem>>()
        var current = ArrayList<MemorySyncOpItem>()
        var currentBytes = PUSH_ENVELOPE_OVERHEAD_BYTES
        for (op in ops) {
            val opBytes = RelayProtocol.encodeSyncOpJson(op).toByteArray(Charsets.UTF_8).size + 1
            if (current.isNotEmpty() && currentBytes + opBytes > PUSH_MAX_PAYLOAD_BYTES) {
                chunks.add(current)
                current = ArrayList()
                currentBytes = PUSH_ENVELOPE_OVERHEAD_BYTES
            }
            current.add(op)
            currentBytes += opBytes
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private suspend fun pullAllLocked(
        account: RelayAccount,
        accountKey: String,
        token: String,
        initialSince: Long,
    ): PullResult {
        var since = initialSince
        var serverSeq = initialSince
        var anyOverlap = false
        while (true) {
            val page = client.pull(account, token, since = since, limit = PULL_PAGE_SIZE)
            serverSeq = maxOf(serverSeq, page.serverSeq)
            val mergeResult = try {
                applyingRemote = true
                store.applyRemotePullBatch(
                    accountKey = accountKey,
                    selfDeviceId = account.deviceId,
                    entries = page.entries,
                    nextSince = page.nextSince,
                    serverSeq = page.serverSeq,
                    remoteLastDreamAt = page.lastDreamAt,
                )
            } finally {
                applyingRemote = false
            }
            if (mergeResult.hadLocalAndRemoteOverlap) anyOverlap = true
            val progressed = page.nextSince > since
            if (progressed) since = page.nextSince
            if (!page.hasMore || !progressed) break
        }
        return PullResult(pulledSeq = since, serverSeq = serverSeq, hadOverlap = anyOverlap)
    }

    private fun isSyncUnsupported(account: RelayAccount): Boolean {
        if (account.origin in unsupportedOrigins) return true
        val caps = relayCapabilities()
        return caps.isNotEmpty() && CAPABILITY_MEMORY_V2 !in caps
    }

    private fun accountKey(account: RelayAccount): String =
        "${account.accountId}|${account.origin}|${account.deviceId}"

    private fun accountIdentity(account: RelayAccount): String =
        "${account.accountId}|${account.origin}"

    private data class PullResult(
        val pulledSeq: Long,
        val serverSeq: Long,
        val hadOverlap: Boolean,
    )

    private data class SyncResult(
        val status: SyncRunStatus,
        val triggerPostMergeDream: Boolean,
    )

    companion object {
        private const val TAG = "RelayMemorySync"
        private const val CAPABILITY_MEMORY_V2 = "memory-v2"
        private const val PULL_PAGE_SIZE = 200
        private const val PUSH_BATCH_SIZE = 100
        private const val PUSH_MAX_PAYLOAD_BYTES = 1_024 * 1_024
        private const val PUSH_ENVELOPE_OVERHEAD_BYTES = 128
        private const val LOCAL_PUSH_DEBOUNCE_MS = 1_500L
        private const val REMOTE_PULL_DEBOUNCE_MS = 300L
        private const val DREAM_LEASE_TTL_MS = 10 * 60_000L
    }
}
