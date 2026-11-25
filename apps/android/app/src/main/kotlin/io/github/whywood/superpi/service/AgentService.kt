package io.github.whywood.superpi.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.whywood.superpi.R
import io.github.whywood.superpi.SuperPIApplication
import io.github.whywood.superpi.conversation.ActiveRunView
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.data.FloatingWindowPreferences
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.data.relay.RelayLiveState
import io.github.whywood.superpi.floating.FloatingWindowController
import io.github.whywood.superpi.runtime.ConversationQueries
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SessionRuntimeEvent
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.isActive
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import io.github.whywood.superpi.vscreen.VirtualScreenRuntime
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph by lazy { (application as SuperPIApplication).graph }
    private var floatingWindow: FloatingWindowController? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var activeNetworkHandle: Long? = null
    private var turnWakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewJob: Job? = null
    private val wakeLockGuard = Any()
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                -> scope.launch { graph.timerService.recomputeTargets() }

                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                -> graph.relayLive.reconnectNow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        graph.notificationCenter.start()
        try {
            graph.relayLive.start()
        } catch (error: Exception) {
            Diagnostics.log(this@AgentService, "service: relay start failed", error)
        }
        try {
            graph.relayMemorySync.start()
        } catch (error: Exception) {
            Diagnostics.log(this@AgentService, "service: memory sync start failed", error)
        }
        startForeground(NOTIFICATION_ID, graph.notificationCenter.foregroundNotification())
        graph.notificationCenter.onForegroundStarted()
        registerClockReceiver()
        registerNetworkCallback()
        startFloatingWindow()
        scope.launch {
            combine(graph.relayLive.allowRemote, graph.relayLive.state) { allow, state ->
                allow && state == RelayLiveState.Online
            }.collect { standby ->
                graph.notificationCenter.setRemoteStandby(standby)
            }
        }
        scope.launch {
            combine(
                graph.host.busySessionIds,
                graph.superRuntime.threadState.map { it.busy }.distinctUntilChanged(),
                graph.superRuntime.workers,
                graph.workerJobs.map { jobs -> jobs.any { it.status == JobStatus.RUNNING } }.distinctUntilChanged(),
            ) { _, _, _, _ -> hasActiveTurns() }
                .distinctUntilChanged()
                .collect { active -> updateTurnWakeLock(active) }
        }
        scope.launch {
            ProcessExitLog.record(this@AgentService)
            try {
                graph.processRuntime.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Diagnostics.log(this@AgentService, "service: process start failed", error)
            }
            try {
                graph.superRuntime.bindTimerHandler()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Diagnostics.log(this@AgentService, "service: bindTimerHandler failed", error)
            }
            try {
                graph.timerService.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Diagnostics.log(this@AgentService, "service: timer start failed", error)
            }
            try {
                graph.superRuntime.ensureStarted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Diagnostics.log(this@AgentService, "service: superRuntime start failed", error)
            }
        }
        scope.launch {
            delay(ACCESSIBILITY_CHECK_DELAY_MS)
            if (!AppVisibility.foreground &&
                GuiAccessibilityState.isEnabled(this@AgentService) &&
                GuiAccessibilityState.service == null
            ) {
                graph.notificationCenter.notifyAccessibilityLost()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_SESSION -> {
                val sessionId = intent.getStringExtra(NotificationCenter.EXTRA_SESSION_ID)
                if (!sessionId.isNullOrBlank()) {
                    when {
                        sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID -> graph.superRuntime.abortSuper()
                        sessionId.startsWith(NotificationCenter.WORKER_TARGET_PREFIX) -> {
                            val workerId = sessionId.removePrefix(NotificationCenter.WORKER_TARGET_PREFIX).trim()
                            if (workerId.isNotEmpty()) {
                                scope.launch {
                                    try {
                                        graph.superRuntime.stopWorker(workerId)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        Diagnostics.log(this@AgentService, "service: stop worker failed", error)
                                    }
                                }
                            }
                        }

                        else -> try {
                            graph.host.stop(sessionId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Diagnostics.log(this@AgentService, "service: stop session failed", error)
                        }
                    }
                }
            }

            ACTION_PAIRING_CODE -> {
                val code = intent.getStringExtra(EXTRA_PAIRING_CODE)
                scope.launch { graph.adbBridge.handlePairingReply(code) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterNetworkCallback()
        runCatching { unregisterReceiver(clockReceiver) }
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
        releaseTurnWakeLock()
        floatingWindow?.stop()
        floatingWindow = null
        graph.timerService.stop()
        graph.relayMemorySync.stop()
        graph.relayLive.stop()
        graph.notificationCenter.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startFloatingWindow() {
        val processDetailRef = AtomicReference(ProcessDetail.DETAILED)
        scope.launch {
            graph.appPreferences.preferences
                .map { it.processDetail }
                .distinctUntilChanged()
                .collect { processDetailRef.set(it) }
        }

        val appModeFlow = graph.appPreferences.preferences
            .map { it.appMode }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, AppMode.NORMAL)

        val superThreadStatusFlow = graph.superRuntime.threadState
            .map { Triple(it.busy, it.activeToolText, it.messages.lastOrNull()?.startedAt) }
            .distinctUntilChanged()

        val modeAwareActiveRuns = combine(
            appModeFlow,
            graph.conversationStore.activeRuns,
            superThreadStatusFlow,
            graph.superRuntime.workers,
        ) { mode, normalRuns, _, superWorkers ->
            if (mode == AppMode.NORMAL) {
                normalRuns
            } else {
                buildSuperActiveRuns(graph.superRuntime.threadState.value, superWorkers)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

        val superThreadQuestionFlow = graph.superRuntime.threadState
            .map { it.pendingQuestion }
            .distinctUntilChanged()

        val modeAwareQuestions = combine(
            appModeFlow,
            graph.questionCenter.pending,
            superThreadQuestionFlow,
            graph.superRuntime.workers,
        ) { mode, normalQuestions, _, superWorkers ->
            if (mode == AppMode.NORMAL) {
                normalQuestions
            } else {
                buildSuperPendingQuestions(graph.superRuntime.threadState.value, superWorkers)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

        val modeAwareEvents = MutableSharedFlow<SessionRuntimeEvent>(extraBufferCapacity = 64)
        scope.launch {
            graph.conversationStore.events.collect { ev ->
                if (appModeFlow.value == AppMode.NORMAL) {
                    modeAwareEvents.emit(ev)
                }
            }
        }
        scope.launch {
            graph.superRuntime.superThreadEvents.collect { ev ->
                if (appModeFlow.value == AppMode.SUPER) {
                    modeAwareEvents.emit(SessionRuntimeEvent(SuperRuntime.SUPER_THREAD_SESSION_ID, ev))
                }
            }
        }
        scope.launch {
            graph.superRuntime.workerProgress.collect { progress ->
                if (appModeFlow.value != AppMode.SUPER) return@collect
                val targetId = NotificationCenter.workerSessionTarget(progress.workerId)
                val now = System.currentTimeMillis()
                when (progress) {
                    is WorkerProgressEvent.StatusChanged -> when (progress.status) {
                        WorkerStatus.RUNNING -> {
                            val step = progress.activeStepText?.takeIf { it.isNotBlank() }
                            val activity = if (step != null) RuntimeActivity.RunningTool(step) else RuntimeActivity.Thinking
                            modeAwareEvents.emit(SessionRuntimeEvent(targetId, RuntimeEvent.ActivityChanged("wrk", activity)))
                        }
                        WorkerStatus.IDLE -> {
                            val worker = graph.superRuntime.workers.value.firstOrNull { it.workerId == progress.workerId }
                            val aborted = worker?.lastOutcome == WorkerOutcome.ABORTED
                            val silent = !aborted && (
                                worker?.lastTurnSilent == true ||
                                    LocalWorkerPool.isSilentNoUpdateReply(worker?.lastSummary.orEmpty())
                                )
                            if (!silent) {
                                val runStatus = if (aborted) RunStatus.STOPPED else RunStatus.DONE
                                modeAwareEvents.emit(
                                    SessionRuntimeEvent(
                                        targetId,
                                        RuntimeEvent.RunFinished("wrk", worker?.lastSummary.orEmpty(), runStatus, now),
                                    ),
                                )
                            }
                        }
                        WorkerStatus.FAILED -> {
                            val worker = graph.superRuntime.workers.value.firstOrNull { it.workerId == progress.workerId }
                            modeAwareEvents.emit(
                                SessionRuntimeEvent(
                                    targetId,
                                    RuntimeEvent.RunFailed("wrk", worker?.lastError ?: getString(R.string.super_worker_failed_fallback)),
                                ),
                            )
                        }
                        else -> Unit
                    }
                    is WorkerProgressEvent.StepUpdated -> {
                        if (progress.step.status == ToolActivityStatus.RUNNING) {
                            val rawArgs = progress.step.argsJson
                                .takeIf { it.isNotBlank() }
                                ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                            val displayArgs = rawArgs?.toDisplayArguments() ?: emptyMap()
                            modeAwareEvents.emit(
                                SessionRuntimeEvent(
                                    targetId,
                                    RuntimeEvent.ToolStarted(
                                        runId = "wrk",
                                        callId = progress.step.callId,
                                        name = progress.step.toolName,
                                        arguments = displayArgs,
                                        startedAt = progress.step.updatedAt,
                                        rawArguments = rawArgs,
                                    ),
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        val modeAwareQueries = object : ConversationQueries by graph.host {
            override suspend fun sessionTitle(sessionId: String): String? {
                if (sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID) {
                    return NotificationCenter.SUPER_DISPLAY_TITLE
                }
                if (sessionId.startsWith(NotificationCenter.WORKER_TARGET_PREFIX)) {
                    val workerId = sessionId.removePrefix(NotificationCenter.WORKER_TARGET_PREFIX)
                    val worker = graph.superRuntime.workers.value.firstOrNull { it.workerId == workerId }
                    if (worker != null) {
                        return formatSuperWorkerTitle(worker.botName, worker.title)
                    }
                    return NotificationCenter.SUPER_DISPLAY_TITLE
                }
                return graph.host.sessionTitle(sessionId)
            }
        }

        val controller = FloatingWindowController(
            context = this,
            runtime = modeAwareQueries,
            activeRuns = modeAwareActiveRuns,
            events = modeAwareEvents,
            questionCenter = graph.questionCenter,
            pendingQuestions = modeAwareQuestions,
            onAnswerQuestion = { pending, answers ->
                when {
                    pending.sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID -> {
                        scope.launch {
                            try {
                                graph.superRuntime.answerSuperQuestion(pending.requestId, answers)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Diagnostics.log(this@AgentService, "service: answer super question failed", error)
                            }
                        }
                    }

                    pending.sessionId.startsWith(NotificationCenter.WORKER_TARGET_PREFIX) -> {
                        val workerId = pending.sessionId.removePrefix(NotificationCenter.WORKER_TARGET_PREFIX)
                        scope.launch {
                            try {
                                graph.superRuntime.answerWorkerQuestion(workerId, pending.requestId, answers)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Diagnostics.log(this@AgentService, "service: answer worker question failed", error)
                            }
                        }
                    }

                    else -> graph.questionCenter.answer(pending.requestId, answers)
                }
            },
            preferences = FloatingWindowPreferences(this),
            themeController = graph.themeController,
            virtualScreenProvider = { VirtualScreenRuntime.engine() },
            onOpenApp = { AppVisibility.bringToFront(this) },
            processDetail = { processDetailRef.get() },
        )
        floatingWindow = controller
        controller.start()
    }

    private fun registerClockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, clockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun hasActiveTurns(): Boolean =
        graph.host.busySessionIds.value.isNotEmpty() ||
            graph.superRuntime.threadState.value.busy ||
            graph.superRuntime.workers.value.any { it.status.isActive } ||
            graph.hasAnyRunningWorkerJobs

    private fun updateTurnWakeLock(active: Boolean) {
        if (active) {
            acquireOrRenewTurnWakeLock()
            if (wakeLockRenewJob?.isActive != true) {
                wakeLockRenewJob = scope.launch {
                    while (isActive && hasActiveTurns()) {
                        delay(TURN_WAKELOCK_RENEW_INTERVAL_MS)
                        if (hasActiveTurns()) {
                            acquireOrRenewTurnWakeLock()
                        }
                    }
                }
            }
        } else {
            wakeLockRenewJob?.cancel()
            wakeLockRenewJob = null
            releaseTurnWakeLock()
        }
    }

    private fun acquireOrRenewTurnWakeLock() {
        synchronized(wakeLockGuard) {
            val lock = turnWakeLock ?: getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SuperPI:AgentTurn")
                ?.apply { setReferenceCounted(false) }
                .also { turnWakeLock = it }
            if (lock != null) {
                runCatching { lock.acquire(TURN_WAKELOCK_TIMEOUT_MS) }
            }
        }
    }

    private fun releaseTurnWakeLock() {
        synchronized(wakeLockGuard) {
            val lock = turnWakeLock ?: return
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val previous = activeNetworkHandle
                activeNetworkHandle = handle
                graph.relayLive.reconnectNow(forceCycle = previous != null && previous != handle)
            }

            override fun onLost(network: Network) {
                if (activeNetworkHandle == network.networkHandle) {
                    activeNetworkHandle = null
                }
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun JsonObject.toDisplayArguments(): Map<String, String> = entries.associate { (key, value) ->
        key to ((value as? JsonPrimitive)?.contentOrNull ?: value.toString())
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
        }

        fun applyPairingCode(context: Context, code: String?) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_PAIRING_CODE)
                .putExtra(EXTRA_PAIRING_CODE, code)
            ContextCompat.startForegroundService(context, intent)
        }

        const val ACTION_STOP = "io.github.whywood.superpi.action.STOP"
        const val ACTION_STOP_SESSION = "io.github.whywood.superpi.action.STOP_SESSION"
        const val ACTION_PAIRING_CODE = "io.github.whywood.superpi.action.PAIRING_CODE"
        const val EXTRA_PAIRING_CODE = "io.github.whywood.superpi.extra.PAIRING_CODE"
        private const val NOTIFICATION_ID = 1
        private const val ACCESSIBILITY_CHECK_DELAY_MS = 4_000L
        private const val TURN_WAKELOCK_TIMEOUT_MS = 10 * 60_000L
        private const val TURN_WAKELOCK_RENEW_INTERVAL_MS = 8 * 60_000L
    }
}
