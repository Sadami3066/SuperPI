package io.github.whywood.superpi.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.whywood.superpi.MainActivity
import io.github.whywood.superpi.conversation.ActiveRunView
import io.github.whywood.superpi.conversation.ConversationStore
import io.github.whywood.superpi.conversation.RunCompletion
import io.github.whywood.superpi.R
import io.github.whywood.superpi.runtime.ConversationQueries
import io.github.whywood.superpi.runtime.InterruptedQuestion
import io.github.whywood.superpi.runtime.InterruptedRun
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class NotificationCenter(
    private val context: Context,
    private val runtime: ConversationQueries,
    private val store: ConversationStore,
    private val pendingQuestions: StateFlow<List<PendingQuestion>>,
    private val superRuntime: SuperRuntime? = null,
) {
    private val manager = NotificationManagerCompat.from(context)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val postedQuestionSessions = ConcurrentHashMap.newKeySet<String>()
    private var collector: Job? = null
    private var questionCollector: Job? = null
    private var superThreadCollector: Job? = null
    private var superWorkersCollector: Job? = null
    private var superQuestionsCollector: Job? = null
    private var superEventsCollector: Job? = null
    @Volatile
    private var foregroundStarted = false
    @Volatile
    private var remoteStandbyActive = false

    fun start() {
        if (collector != null) return
        createChannels()
        collector = scope.launch {
            store.runCompletions.collect(::onRunCompletion)
        }
        questionCollector = scope.launch {
            pendingQuestions.collect { refreshAllQuestions() }
        }
        val superRt = superRuntime
        if (superRt != null) {
            superThreadCollector = scope.launch {
                var previousBusyAndTool: Pair<Boolean, String?>? = null
                var previousQuestionId: String? = null
                var initialized = false
                superRt.threadState
                    .map { Triple(it.busy, it.activeToolText, it.pendingQuestion?.requestId) }
                    .distinctUntilChanged()
                    .collect { (busy, activeToolText, questionId) ->
                        val busyAndTool = busy to activeToolText
                        if (!initialized || busyAndTool != previousBusyAndTool) {
                            previousBusyAndTool = busyAndTool
                            updateForeground()
                        }
                        if (!initialized || questionId != previousQuestionId) {
                            previousQuestionId = questionId
                            refreshAllQuestions()
                        }
                        initialized = true
                    }
            }
            superWorkersCollector = scope.launch {
                var initialized = false
                var previousStatuses = emptyMap<String, WorkerStatus>()
                superRt.workers.collect { workers ->
                    if (initialized) {
                        workers.forEach { worker ->
                            val prev = previousStatuses[worker.workerId]
                            if (prev != null && prev.isBusyForCommands && !worker.status.isBusyForCommands) {
                                val targetId = workerSessionTarget(worker.workerId)
                                val title = formatSuperWorkerTitle(worker.botName, worker.title)
                                when (worker.status) {
                                    WorkerStatus.FAILED -> postResult(
                                        sessionId = targetId,
                                        title = title,
                                        status = RunStatus.FAILED,
                                        text = "",
                                        error = worker.lastError,
                                    )
                                    WorkerStatus.IDLE -> if (worker.lastOutcome == WorkerOutcome.SUCCEEDED &&
                                        !worker.lastTurnSilent &&
                                        !LocalWorkerPool.isSilentNoUpdateReply(worker.lastSummary.orEmpty())
                                    ) {
                                        postResult(
                                            sessionId = targetId,
                                            title = title,
                                            status = RunStatus.DONE,
                                            text = worker.lastSummary.orEmpty(),
                                            error = null,
                                        )
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                    previousStatuses = workers.associate { it.workerId to it.status }
                    initialized = true
                    updateForeground()
                }
            }
            superQuestionsCollector = scope.launch {
                superRt.pendingWorkerQuestions.collect {
                    refreshAllQuestions()
                }
            }
            superEventsCollector = scope.launch {
                superRt.superThreadEvents.collect(::onSuperThreadEvent)
            }
        }
    }

    fun stop() {
        collector?.cancel()
        collector = null
        questionCollector?.cancel()
        questionCollector = null
        superThreadCollector?.cancel()
        superThreadCollector = null
        superWorkersCollector?.cancel()
        superWorkersCollector = null
        superQuestionsCollector?.cancel()
        superQuestionsCollector = null
        superEventsCollector?.cancel()
        superEventsCollector = null
        clearQuestionNotifications()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        foregroundStarted = false
        manager.cancel(FOREGROUND_ID)
    }

    fun onForegroundStarted() {
        foregroundStarted = true
        updateForeground()
    }

    fun setRemoteStandby(active: Boolean) {
        if (remoteStandbyActive == active) return
        remoteStandbyActive = active
        updateForeground()
    }

    fun refreshQuestionNotifications() {
        scope.launch { refreshAllQuestions() }
    }

    fun clearQuestionNotifications() {
        postedQuestionSessions.toList().forEach { sessionId ->
            manager.cancel(questionId(sessionId))
        }
        postedQuestionSessions.clear()
    }

    private suspend fun refreshAllQuestions() {
        onPendingQuestions(mergedPendingQuestions())
    }

    private fun mergedPendingQuestions(): List<PendingQuestion> {
        val superRt = superRuntime ?: return pendingQuestions.value
        return pendingQuestions.value + buildSuperPendingQuestions(superRt.threadState.value, superRt.workers.value)
    }

    private suspend fun onPendingQuestions(pending: List<PendingQuestion>) {
        if (AppVisibility.foreground || pending.isEmpty()) {
            clearQuestionNotifications()
            return
        }
        val grouped = pending.groupBy { it.sessionId }
        val stale = postedQuestionSessions - grouped.keys
        stale.forEach { sessionId ->
            manager.cancel(questionId(sessionId))
            postedQuestionSessions -= sessionId
        }
        grouped.forEach { (sessionId, questions) ->
            postQuestion(sessionId, questions)
        }
    }

    private suspend fun postQuestion(sessionId: String, questions: List<PendingQuestion>) {
        val title = resolveNotificationTitle(sessionId)
        val body = questionNotificationBody(questions.first())
        val builder = NotificationCompat.Builder(context, CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_question_title, title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent(sessionId))
            .setAutoCancel(true)
            .setGroup(GROUP_SESSIONS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                0,
                context.getString(R.string.notification_question_action),
                openAppIntent(sessionId),
            )
            .addAction(
                0,
                context.getString(R.string.notification_action_stop),
                stopSessionIntent(sessionId),
            )
        post(questionId(sessionId), builder.build())
        postedQuestionSessions += sessionId
    }

    private suspend fun resolveNotificationTitle(sessionId: String): String {
        if (sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID) {
            return SUPER_DISPLAY_TITLE
        }
        if (sessionId.startsWith(WORKER_TARGET_PREFIX)) {
            val workerId = sessionId.removePrefix(WORKER_TARGET_PREFIX)
            val worker = superRuntime?.workers?.value?.firstOrNull { it.workerId == workerId }
            if (worker != null) {
                return formatSuperWorkerTitle(worker.botName, worker.title)
            }
            return SUPER_DISPLAY_TITLE
        }
        return runCatching { runtime.sessionTitle(sessionId) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
    }

    fun foregroundNotification(): android.app.Notification {
        val active = mergedActiveRuns()
        val latest = active.values.maxByOrNull { it.startedAt }
        val title = when {
            active.isEmpty() -> context.getString(R.string.app_name)
            active.size == 1 -> latest?.title.orEmpty().ifBlank { context.getString(R.string.app_name) }
            else -> context.getString(R.string.notification_sessions_running, active.size)
        }
        val content = when {
            active.isEmpty() -> if (remoteStandbyActive) {
                context.getString(R.string.notification_service_remote_standby)
            } else {
                context.getString(R.string.notification_service_idle)
            }
            latest == null -> context.getString(R.string.notification_activity_thinking)
            else -> activityText(latest.activity)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_RUNTIME)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppIntent(latest?.sessionId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (latest != null) {
            builder.addAction(
                0,
                context.getString(R.string.notification_action_stop),
                stopSessionIntent(latest.sessionId),
            )
        }
        return builder.build()
    }

    fun notifyInstall(label: String, stage: String?, progress: Int?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_download_title))
            .setContentText(stage ?: label)
            .setContentIntent(openAppIntent(null))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress ?: 0, progress == null)
        post(INSTALL_ID, builder.build())
    }

    fun notifyInstallFinished(label: String, success: Boolean, error: String? = null) {
        manager.cancel(INSTALL_ID)
        val text = if (success) {
            context.getString(R.string.notification_download_done, label)
        } else {
            context.getString(R.string.notification_download_failed, error?.take(160).orEmpty())
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_download_title))
            .setContentText(text)
            .setContentIntent(openAppIntent(null))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        post(INSTALL_RESULT_ID, builder.build())
    }

    fun clearInstallNotification() {
        manager.cancel(INSTALL_ID)
        manager.cancel(INSTALL_RESULT_ID)
    }

    fun notifyAccessibilityLost() {
        val text = context.getString(R.string.notification_accessibility_lost_text)
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_accessibility_lost_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(accessibilitySettingsIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        post(ACCESSIBILITY_ID, builder.build())
    }

    fun notifyInterruptedWork(questions: List<InterruptedQuestion>, runs: List<InterruptedRun>) {
        val sessionId = interruptedNotificationTarget(questions, runs) ?: return
        val text = context.getString(R.string.notification_interrupted_text, interruptedNotificationCount(questions, runs))
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_interrupted_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(sessionId))
            .setAutoCancel(true)
            .setGroup(GROUP_SESSIONS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        post(INTERRUPTED_ID, builder.build())
    }

    private fun accessibilitySettingsIntent(): PendingIntent {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun onRunCompletion(change: RunCompletion) {
        updateForeground()
        postResult(
            sessionId = change.sessionId,
            title = change.title,
            status = change.status,
            text = change.text,
            error = change.error,
        )
    }

    private fun onSuperThreadEvent(event: RuntimeEvent) {
        when (event) {
            is RuntimeEvent.RunFinished -> {
                updateForeground()
                postResult(
                    sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                    title = SUPER_DISPLAY_TITLE,
                    status = event.status,
                    text = event.text,
                    error = null,
                )
            }

            is RuntimeEvent.RunFailed -> {
                updateForeground()
                postResult(
                    sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                    title = SUPER_DISPLAY_TITLE,
                    status = RunStatus.FAILED,
                    text = "",
                    error = event.message,
                )
            }

            else -> Unit
        }
    }

    private fun mergedActiveRuns(): Map<String, ActiveRunView> {
        val superRt = superRuntime ?: return store.activeRuns.value
        val merged = LinkedHashMap<String, ActiveRunView>(store.activeRuns.value)
        merged.putAll(buildSuperActiveRuns(superRt.threadState.value, superRt.workers.value))
        return merged
    }

    private fun updateForeground() {
        if (!foregroundStarted) return
        post(FOREGROUND_ID, foregroundNotification())
    }

    private fun postResult(
        sessionId: String,
        title: String,
        status: RunStatus,
        text: String,
        error: String?,
    ) {
        if (AppVisibility.foreground) return
        val line = when (status) {
            RunStatus.DONE -> context.getString(R.string.notification_result_done)
            RunStatus.STOPPED -> context.getString(R.string.notification_result_stopped)
            RunStatus.FAILED -> context.getString(R.string.notification_result_failed, error?.take(160).orEmpty())
        }
        val preview = text.trim().take(600)
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(line)
            .setContentIntent(openAppIntent(sessionId))
            .setAutoCancel(true)
            .setGroup(GROUP_SESSIONS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (preview.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$line\n$preview"))
        }
        post(resultId(sessionId), builder.build())
    }

    private fun activityText(activity: RuntimeActivity?): String = when (activity) {
        null -> context.getString(R.string.notification_activity_thinking)
        is RuntimeActivity.Thinking -> context.getString(R.string.notification_activity_thinking)
        is RuntimeActivity.Retrying -> context.getString(R.string.notification_activity_retrying, activity.attempt)
        is RuntimeActivity.Compacting -> context.getString(R.string.notification_activity_compacting)
        is RuntimeActivity.CompactionFailed -> context.getString(R.string.notification_activity_compacting)
        is RuntimeActivity.RunningTool -> context.getString(R.string.notification_activity_tool, activity.name)
    }

    private fun openAppIntent(sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopSessionIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, AgentService::class.java).apply {
            action = AgentService.ACTION_STOP_SESSION
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getService(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun resultId(sessionId: String): Int = 1000 + (sessionId.hashCode() and 0x3FFFF)

    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RUNTIME,
                context.getString(R.string.notification_channel_runtime),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_runtime_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.notification_channel_results),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_results_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUESTIONS,
                context.getString(R.string.notification_channel_questions),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_questions_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                context.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_downloads_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val EXTRA_SESSION_ID = "io.github.whywood.superpi.extra.SESSION_ID"
        const val WORKER_TARGET_PREFIX = "worker:"
        const val SUPER_DISPLAY_TITLE = "Super PI"
        fun workerSessionTarget(workerId: String): String = "$WORKER_TARGET_PREFIX$workerId"
        private const val CHANNEL_RUNTIME = "agent_runtime"
        private const val CHANNEL_RESULTS = "session_results"
        private const val CHANNEL_QUESTIONS = "agent_questions"
        private const val CHANNEL_DOWNLOADS = "downloads"
        private const val FOREGROUND_ID = 1
        private const val INSTALL_ID = 200
        private const val INSTALL_RESULT_ID = 201
        private const val ACCESSIBILITY_ID = 300
        private const val INTERRUPTED_ID = 400
        private const val QUESTION_ID_BASE = 500
        private const val GROUP_SESSIONS = "superpi_sessions"

        private fun questionId(sessionId: String): Int = QUESTION_ID_BASE + (sessionId.hashCode() and 0x3FFFF)

        fun areNotificationsEnabled(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

internal fun interruptedNotificationCount(
    questions: List<InterruptedQuestion>,
    runs: List<InterruptedRun>,
): Int = questions.size + runs.size

internal fun interruptedNotificationTarget(
    questions: List<InterruptedQuestion>,
    runs: List<InterruptedRun>,
): String? {
    val question = questions.minByOrNull { it.createdAt }
    val run = runs.minByOrNull { it.startedAt }
    return when {
        question == null -> run?.sessionId
        run == null -> question.sessionId
        question.createdAt <= run.startedAt -> question.sessionId
        else -> run.sessionId
    }
}

internal fun questionNotificationBody(question: PendingQuestion): String {
    val first = question.questions.firstOrNull() ?: return ""
    val header = first.header?.takeIf { it.isNotBlank() }
    val text = first.question.trim()
    val line = if (header == null) text else "$header · $text"
    return if (line.length <= 160) line else line.take(160).trimEnd() + "…"
}
