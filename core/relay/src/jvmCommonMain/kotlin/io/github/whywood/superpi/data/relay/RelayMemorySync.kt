package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.memory.ConsolidationLeaseResult
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchMode
import io.github.whywood.superpi.runtime.memory.MemoryConsolidationCoordinator
import io.github.whywood.superpi.runtime.memory.MemoryRemotePullEntry
import io.github.whywood.superpi.runtime.memory.MemoryStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RelayMemorySyncStatus {
    Idle,
    Syncing,
    Synced,
    Error,
    AwaitingAccountSwitch,
    Unsupported,
}

data class RelayMemoryAccountSwitchPrompt(
    val accountKey: String,
    val accountIdentity: String,
    val localRecordCount: Int,
)

data class RelayMemorySyncUiState(
    val status: RelayMemorySyncStatus = RelayMemorySyncStatus.Idle,
    val lastSyncedAt: Long? = null,
    val lastErrorCode: String? = null,
    val accountSwitchPrompt: RelayMemoryAccountSwitchPrompt? = null,
)

class RelayMemorySync(
    private val scope: CoroutineScope,
    private val accounts: RelayAccountStore,
    private val memory: MemoryStore,
    private val client: RelayMemoryClient,
    private val relayCapabilities: () -> Set<String> = { emptySet() },
    private val onConsolidationTrigger: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localChangeDebounceMs: Long = 1_500L,
) : MemoryConsolidationCoordinator {
    private val _state = MutableStateFlow(RelayMemorySyncUiState())
    val state: StateFlow<RelayMemorySyncUiState> = _state.asStateFlow()

    private val syncMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val completedLeases = ConcurrentHashMap.newKeySet<String>()
    private val leaseAccounts = ConcurrentHashMap<String, Pair<String, RelayAccount>>()
    private val lastSettledChangeVersion = AtomicLong(-1L)

    @Volatile
    private var accountWatchJob: Job? = null

    @Volatile
    private var memoryWatchJob: Job? = null

    @Volatile
    private var activeAccountKey: String? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        accountWatchJob = scope.launch(ioDispatcher) {
            accounts.account.collect { account ->
                if (account == null) {
                    activeAccountKey = null
                    lastSettledChangeVersion.set(-1L)
                    _state.value = RelayMemorySyncUiState()
                } else {
                    val key = accountKey(account)
                    if (activeAccountKey != key) {
                        activeAccountKey = key
                        lastSettledChangeVersion.set(-1L)
                    }
                    syncNow(manual = false)
                }
            }
        }
        memoryWatchJob = scope.launch(ioDispatcher) {
            memory.changes
                .drop(1)
                .debounce(localChangeDebounceMs)
                .collect { version ->
                    if (version == lastSettledChangeVersion.get()) return@collect
                    if (accounts.read() != null) {
                        syncNow(manual = false)
                    }
                }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        accountWatchJob?.cancel()
        accountWatchJob = null
        memoryWatchJob?.cancel()
        memoryWatchJob = null
    }

    fun onRemoteVersionHint(version: Long) {
        if (version < 0L) return
        scope.launch(ioDispatcher) {
            syncNow(manual = false)
        }
    }

    fun triggerSyncNow() {
        scope.launch(ioDispatcher) {
            syncNow(manual = true)
        }
    }

    fun triggerResolveAccountSwitch(mode: MemoryAccountSwitchMode) {
        scope.launch(ioDispatcher) {
            resolveAccountSwitch(mode)
        }
    }

    suspend fun syncNow(manual: Boolean = false): Boolean {
        var triggerOverlapConsolidation = false
        val synced = syncMutex.withLock {
            val account = accounts.read() ?: run {
                _state.value = RelayMemorySyncUiState()
                return@withLock false
            }
            val caps = relayCapabilities()
            if (caps.isNotEmpty() && CAPABILITY_MEMORY_V3 !in caps) {
                _state.value = _state.value.copy(
                    status = RelayMemorySyncStatus.Unsupported,
                    lastErrorCode = null,
                    accountSwitchPrompt = null,
                )
                return@withLock false
            }
            val key = accountKey(account)
            val check = memory.inspectAccountSwitch(key)
            if (check.requiresPrompt) {
                _state.value = _state.value.copy(
                    status = RelayMemorySyncStatus.AwaitingAccountSwitch,
                    lastErrorCode = null,
                    accountSwitchPrompt = RelayMemoryAccountSwitchPrompt(
                        accountKey = key,
                        accountIdentity = key,
                        localRecordCount = check.localRecordCount,
                    ),
                )
                return@withLock false
            }

            _state.value = _state.value.copy(
                status = RelayMemorySyncStatus.Syncing,
                lastErrorCode = null,
                accountSwitchPrompt = null,
            )

            val pullOutcome = pullCycleLocked(account, key)
            if (!pullOutcome.ok) {
                return@withLock false
            }
            triggerOverlapConsolidation = pullOutcome.hadOverlap

            val pushOutcome = pushCycleLocked(account, key, consolidationCompletedAt = null)
            if (!pushOutcome.ok) {
                return@withLock false
            }

            pushOutcome.settledChangeVersion?.let { lastSettledChangeVersion.set(it) }
            _state.value = RelayMemorySyncUiState(
                status = RelayMemorySyncStatus.Synced,
                lastSyncedAt = clock(),
                lastErrorCode = null,
                accountSwitchPrompt = null,
            )
            true
        }
        if (synced && triggerOverlapConsolidation) {
            onConsolidationTrigger()
        }
        return synced
    }

    suspend fun resolveAccountSwitch(mode: MemoryAccountSwitchMode) {
        syncMutex.withLock {
            val account = accounts.read() ?: return
            val key = accountKey(account)
            memory.resolveAccountSwitch(key, key, mode)
            _state.value = _state.value.copy(
                status = RelayMemorySyncStatus.Idle,
                lastErrorCode = null,
                accountSwitchPrompt = null,
            )
        }
        syncNow(manual = true)
    }

    override suspend fun prepareForConsolidation(
        forceManual: Boolean,
        minIntervalMs: Long,
    ): ConsolidationLeaseResult {
        val account = accounts.read() ?: return ConsolidationLeaseResult.Acquired(null)
        val caps = relayCapabilities()
        if (caps.isNotEmpty() && CAPABILITY_MEMORY_V3 !in caps) {
            return if (forceManual) {
                ConsolidationLeaseResult.Acquired(null)
            } else {
                ConsolidationLeaseResult.Blocked("unsupported")
            }
        }
        return syncMutex.withLock {
            val key = accountKey(account)
            val check = memory.inspectAccountSwitch(key)
            if (check.requiresPrompt) {
                _state.value = _state.value.copy(
                    status = RelayMemorySyncStatus.AwaitingAccountSwitch,
                    accountSwitchPrompt = RelayMemoryAccountSwitchPrompt(
                        accountKey = key,
                        accountIdentity = key,
                        localRecordCount = check.localRecordCount,
                    ),
                )
                return@withLock ConsolidationLeaseResult.Blocked("account_switch_pending")
            }

            when (val pull = pullCycleForLeaseLocked(account, key)) {
                is LeasePullStep.Failed -> return@withLock pull.result
                LeasePullStep.Ok -> Unit
            }

            when (val res = client.acquireConsolidationLease(account, minIntervalMs, forceManual)) {
                is RelayMemoryOutcome.Ok -> {
                    val value = res.value
                    if (value.acquired) {
                        value.leaseId?.let { id -> leaseAccounts[id] = key to account }
                        ConsolidationLeaseResult.Acquired(value.leaseId)
                    } else if (value.holderDeviceId != null) {
                        ConsolidationLeaseResult.Blocked("busy")
                    } else {
                        value.lastConsolidationAt?.let { ts ->
                            memory.recordRemoteConsolidationAt(key, ts)
                        }
                        ConsolidationLeaseResult.Blocked("cooldown")
                    }
                }

                RelayMemoryOutcome.Offline -> ConsolidationLeaseResult.Offline
                RelayMemoryOutcome.Unauthorized -> {
                    _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "unauthorized")
                    ConsolidationLeaseResult.Unauthorized
                }

                is RelayMemoryOutcome.Rejected -> {
                    if (res.code == "not_found") {
                        _state.value = _state.value.copy(status = RelayMemorySyncStatus.Unsupported, lastErrorCode = null)
                        if (forceManual) {
                            ConsolidationLeaseResult.Acquired(null)
                        } else {
                            ConsolidationLeaseResult.Blocked("unsupported")
                        }
                    } else {
                        ConsolidationLeaseResult.Rejected
                    }
                }
            }
        }
    }

    override suspend fun onConsolidationCompleted(leaseId: String?, finishedAt: Long) {
        val leased = leaseId?.let { leaseAccounts[it] }
        val currentAccount = accounts.read()
        val targetAccount = leased?.second ?: currentAccount ?: return
        val targetKey = leased?.first ?: accountKey(targetAccount)
        syncMutex.withLock {
            val accountMatches = currentAccount != null && accountKey(currentAccount) == targetKey
            val check = if (accountMatches) memory.inspectAccountSwitch(targetKey) else null
            val pushOutcome = if (accountMatches && check != null && !check.requiresPrompt) {
                pushCycleLocked(targetAccount, targetKey, consolidationCompletedAt = finishedAt)
            } else {
                PushCycleOutcome(ok = false)
            }
            if (pushOutcome.ok) {
                pushOutcome.settledChangeVersion?.let { lastSettledChangeVersion.set(it) }
                memory.recordRemoteConsolidationAt(targetKey, finishedAt)
                _state.value = _state.value.copy(
                    status = RelayMemorySyncStatus.Synced,
                    lastSyncedAt = clock(),
                    lastErrorCode = null,
                )
            }
            if (leaseId != null) {
                val released = client.releaseConsolidationLease(targetAccount, leaseId)
                if (released is RelayMemoryOutcome.Ok) {
                    completedLeases += leaseId
                    leaseAccounts.remove(leaseId)
                }
            }
        }
    }

    override suspend fun onConsolidationFinished(leaseId: String?) {
        if (leaseId == null) return
        val leased = leaseAccounts.remove(leaseId)
        if (completedLeases.remove(leaseId)) return
        val account = leased?.second ?: accounts.read() ?: return
        client.releaseConsolidationLease(account, leaseId)
    }

    private suspend fun pullCycleLocked(account: RelayAccount, accountKey: String): PullCycleOutcome =
        when (val result = pullPagesLocked(account, accountKey)) {
            is InternalPullResult.Ok -> PullCycleOutcome(ok = true, hadOverlap = result.hadOverlap)
            InternalPullResult.Offline -> {
                _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "offline")
                PullCycleOutcome(ok = false, hadOverlap = false)
            }

            InternalPullResult.Unauthorized -> {
                _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "unauthorized")
                PullCycleOutcome(ok = false, hadOverlap = false)
            }

            is InternalPullResult.Rejected -> {
                _state.value = if (result.code == "not_found") {
                    _state.value.copy(status = RelayMemorySyncStatus.Unsupported, lastErrorCode = null)
                } else {
                    _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = result.code)
                }
                PullCycleOutcome(ok = false, hadOverlap = false)
            }
        }

    private suspend fun pullCycleForLeaseLocked(account: RelayAccount, accountKey: String): LeasePullStep =
        when (val result = pullPagesLocked(account, accountKey)) {
            is InternalPullResult.Ok -> LeasePullStep.Ok
            InternalPullResult.Offline -> LeasePullStep.Failed(ConsolidationLeaseResult.Offline)
            InternalPullResult.Unauthorized -> {
                _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "unauthorized")
                LeasePullStep.Failed(ConsolidationLeaseResult.Unauthorized)
            }

            is InternalPullResult.Rejected -> {
                if (result.code == "not_found") {
                    _state.value = _state.value.copy(status = RelayMemorySyncStatus.Unsupported, lastErrorCode = null)
                }
                LeasePullStep.Failed(ConsolidationLeaseResult.Rejected)
            }
        }

    private suspend fun pullPagesLocked(account: RelayAccount, accountKey: String): InternalPullResult {
        val startCursor = memory.readPullCursor(accountKey)
        var cursor = startCursor
        var safeCursor = startCursor
        var latestRemoteConsolidationAt: Long? = null
        var hadOverlap = false
        var paginationCompleted = false
        val deferred = ArrayList<MemoryRemotePullEntry>()
        for (pageIndex in 0 until MAX_PULL_PAGES) {
            when (val res = client.pull(account, since = cursor, limit = PULL_PAGE_LIMIT)) {
                is RelayMemoryOutcome.Ok -> {
                    val page = res.value
                    page.lastConsolidationAt?.takeIf { it > 0L }?.let { latestRemoteConsolidationAt = it }
                    val applied = memory.applyRemotePullBatch(
                        accountKey = accountKey,
                        selfDeviceId = account.deviceId,
                        entries = page.entries,
                        nextSince = page.nextSince,
                        remoteLastConsolidationAt = null,
                        advanceCursor = false,
                    )
                    if (applied.hadLocalAndRemoteOverlap) hadOverlap = true
                    if (applied.deferredEntries.isNotEmpty()) deferred += applied.deferredEntries
                    val progressed = page.nextSince > cursor
                    if (progressed) {
                        cursor = page.nextSince
                    }
                    if (deferred.isEmpty() && cursor > safeCursor) {
                        safeCursor = cursor
                    }
                    if (!page.hasMore) {
                        paginationCompleted = true
                        break
                    }
                    if (!progressed) break
                }

                RelayMemoryOutcome.Offline -> {
                    if (safeCursor > startCursor || latestRemoteConsolidationAt != null) {
                        memory.commitPullCursor(accountKey, safeCursor, latestRemoteConsolidationAt)
                    }
                    return InternalPullResult.Offline
                }

                RelayMemoryOutcome.Unauthorized -> {
                    if (safeCursor > startCursor || latestRemoteConsolidationAt != null) {
                        memory.commitPullCursor(accountKey, safeCursor, latestRemoteConsolidationAt)
                    }
                    return InternalPullResult.Unauthorized
                }

                is RelayMemoryOutcome.Rejected -> {
                    if (safeCursor > startCursor || latestRemoteConsolidationAt != null) {
                        memory.commitPullCursor(accountKey, safeCursor, latestRemoteConsolidationAt)
                    }
                    return InternalPullResult.Rejected(res.code)
                }
            }
        }
        var unresolvedDeferred = false
        if (deferred.isNotEmpty()) {
            val replay = memory.applyRemotePullBatch(
                accountKey = accountKey,
                selfDeviceId = account.deviceId,
                entries = deferred,
                nextSince = 0L,
                remoteLastConsolidationAt = null,
                advanceCursor = false,
            )
            if (replay.hadLocalAndRemoteOverlap) hadOverlap = true
            unresolvedDeferred = replay.deferredEntries.isNotEmpty()
        }
        val finalCursor = if (!paginationCompleted && unresolvedDeferred) safeCursor else cursor
        if (finalCursor > startCursor || latestRemoteConsolidationAt != null) {
            memory.commitPullCursor(accountKey, finalCursor, latestRemoteConsolidationAt)
        }
        return InternalPullResult.Ok(hadOverlap = hadOverlap)
    }

    private suspend fun pushCycleLocked(
        account: RelayAccount,
        accountKey: String,
        consolidationCompletedAt: Long?,
    ): PushCycleOutcome {
        var pendingCompletionAt = consolidationCompletedAt
        var hadRejected = false
        var firstRejectedReason: String? = null
        var latestSettledVersion: Long? = null
        for (batchIndex in 0 until MAX_PUSH_BATCHES) {
            val versionBeforeCollect = memory.changes.value
            val batch = memory.collectPendingPushBatch(accountKey, account.deviceId, limit = PUSH_BATCH_LIMIT)
            if (batch.isEmpty()) {
                val stamp = if (hadRejected) null else pendingCompletionAt
                if (stamp == null) {
                    if (!hadRejected) {
                        latestSettledVersion = versionBeforeCollect
                    }
                    break
                }
                pendingCompletionAt = null
                when (val res = client.push(account, emptyList(), consolidationCompletedAt = stamp)) {
                    is RelayMemoryOutcome.Ok -> {
                        memory.markPushAcked(accountKey, emptyList(), res.value.lastConsolidationAt)
                        latestSettledVersion = versionBeforeCollect
                        break
                    }

                    RelayMemoryOutcome.Offline -> {
                        _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "offline")
                        return PushCycleOutcome(ok = false)
                    }

                    RelayMemoryOutcome.Unauthorized -> {
                        _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "unauthorized")
                        return PushCycleOutcome(ok = false)
                    }

                    is RelayMemoryOutcome.Rejected -> {
                        _state.value = if (res.code == "not_found") {
                            _state.value.copy(status = RelayMemorySyncStatus.Unsupported, lastErrorCode = null)
                        } else {
                            _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = res.code)
                        }
                        return PushCycleOutcome(ok = false)
                    }
                }
            }
            val stamp = if (batch.size < PUSH_BATCH_LIMIT && !hadRejected) pendingCompletionAt else null
            when (val res = client.push(account, batch, consolidationCompletedAt = stamp)) {
                is RelayMemoryOutcome.Ok -> {
                    if (stamp != null) pendingCompletionAt = null
                    val result = res.value
                    memory.markPushAcked(accountKey, batch, result.lastConsolidationAt)
                    if (result.rejected.isNotEmpty()) {
                        hadRejected = true
                        if (firstRejectedReason == null) {
                            firstRejectedReason = result.rejected.firstOrNull()?.reason
                        }
                    } else {
                        latestSettledVersion = versionBeforeCollect
                    }
                }

                RelayMemoryOutcome.Offline -> {
                    _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "offline")
                    return PushCycleOutcome(ok = false)
                }

                RelayMemoryOutcome.Unauthorized -> {
                    _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "unauthorized")
                    return PushCycleOutcome(ok = false)
                }

                is RelayMemoryOutcome.Rejected -> {
                    _state.value = if (res.code == "not_found") {
                        _state.value.copy(status = RelayMemorySyncStatus.Unsupported, lastErrorCode = null)
                    } else {
                        _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = res.code)
                    }
                    return PushCycleOutcome(ok = false)
                }
            }
        }
        if (hadRejected) {
            _state.value = _state.value.copy(
                status = RelayMemorySyncStatus.Error,
                lastErrorCode = firstRejectedReason ?: "push_rejected",
            )
            return PushCycleOutcome(ok = false)
        }
        if (pendingCompletionAt != null) {
            val versionBeforeFinal = memory.changes.value
            when (val res = client.push(account, emptyList(), consolidationCompletedAt = pendingCompletionAt)) {
                is RelayMemoryOutcome.Ok -> {
                    memory.markPushAcked(accountKey, emptyList(), res.value.lastConsolidationAt)
                    latestSettledVersion = versionBeforeFinal
                }

                RelayMemoryOutcome.Offline -> {
                    _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "offline")
                    return PushCycleOutcome(ok = false)
                }

                RelayMemoryOutcome.Unauthorized -> {
                    _state.value = _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = "unauthorized")
                    return PushCycleOutcome(ok = false)
                }

                is RelayMemoryOutcome.Rejected -> {
                    _state.value = if (res.code == "not_found") {
                        _state.value.copy(status = RelayMemorySyncStatus.Unsupported, lastErrorCode = null)
                    } else {
                        _state.value.copy(status = RelayMemorySyncStatus.Error, lastErrorCode = res.code)
                    }
                    return PushCycleOutcome(ok = false)
                }
            }
        }
        return PushCycleOutcome(ok = true, settledChangeVersion = latestSettledVersion)
    }

    private fun accountKey(account: RelayAccount): String =
        "${account.origin.trimEnd('/')}|${account.accountId}"

    private data class PullCycleOutcome(
        val ok: Boolean,
        val hadOverlap: Boolean,
    )

    private data class PushCycleOutcome(
        val ok: Boolean,
        val settledChangeVersion: Long? = null,
    )

    private sealed interface InternalPullResult {
        data class Ok(val hadOverlap: Boolean) : InternalPullResult
        data object Offline : InternalPullResult
        data object Unauthorized : InternalPullResult
        data class Rejected(val code: String) : InternalPullResult
    }

    private sealed interface LeasePullStep {
        data object Ok : LeasePullStep
        data class Failed(val result: ConsolidationLeaseResult) : LeasePullStep
    }

    companion object {
        private const val CAPABILITY_MEMORY_V3 = "memory-v3"
        private const val PULL_PAGE_LIMIT = 200
        private const val MAX_PULL_PAGES = 100
        private const val PUSH_BATCH_LIMIT = 100
        private const val MAX_PUSH_BATCHES = 100
    }
}
