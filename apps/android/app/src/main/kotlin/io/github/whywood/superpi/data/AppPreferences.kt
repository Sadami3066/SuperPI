package io.github.whywood.superpi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

enum class SendWhileRunning {
    STEER,
    QUEUE,
}

enum class ProcessDetail {
    DETAILED,
    COMPACT,
}

enum class AppMode {
    NORMAL,
    SUPER,
}

data class UiPreferences(
    val autoRetry: Boolean = true,
    val backgroundJobWake: Boolean = true,
    val pinnedWorkspaceIds: Set<String> = emptySet(),
    val chatFontScale: Float = CHAT_FONT_SCALE_DEFAULT,
    val sendWhileRunning: SendWhileRunning = SendWhileRunning.STEER,
    val processDetail: ProcessDetail = ProcessDetail.DETAILED,
    val metadataMode: SessionNamingMode = SessionNamingMode.AUTO,
    val metadataProviderId: String = "",
    val metadataModelId: String = "",
    val compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
    val appMode: AppMode = AppMode.NORMAL,
    val superMaxConcurrentWorkers: Int = SUPER_CONCURRENCY_DEFAULT,
    val superMaxSlotsPerBot: Int = SUPER_SLOTS_DEFAULT,
    val superStallTimeoutMinutes: Int = SUPER_STALL_TIMEOUT_DEFAULT_MINUTES,
    val superQuestionTimeoutMinutes: Int = SUPER_QUESTION_TIMEOUT_DEFAULT_MINUTES,
    val superWorkerJobWake: Boolean = true,
    val superCoalesceWindowMs: Long = SUPER_COALESCE_WINDOW_DEFAULT_MS,
    val superReceiptMaxChars: Int = SUPER_RECEIPT_CHARS_DEFAULT,
    val superIdleEvictMinutes: Int = SUPER_IDLE_EVICT_DEFAULT_MINUTES,
    val superWorkerCompactionOverrideEnabled: Boolean = false,
    val superWorkerCompactionPercent: Int = COMPACTION_TRIGGER_DEFAULT,
)

fun UiPreferences.toSuperRuntimeSettings(): SuperRuntimeSettings = SuperRuntimeSettings(
    maxConcurrentWorkers = normalizeSuperConcurrency(superMaxConcurrentWorkers),
    maxSlotsPerBot = normalizeSuperSlotsPerBot(superMaxSlotsPerBot),
    stallTimeoutMs = normalizeSuperStallTimeoutMinutes(superStallTimeoutMinutes) * 60_000L,
    questionTimeoutMs = normalizeSuperQuestionTimeoutMinutes(superQuestionTimeoutMinutes) * 60_000L,
    workerJobWake = superWorkerJobWake,
    coalesceWindowMs = normalizeSuperCoalesceWindowMs(superCoalesceWindowMs),
    receiptMaxChars = normalizeSuperReceiptMaxChars(superReceiptMaxChars),
    idleEvictMs = normalizeSuperIdleEvictMinutes(superIdleEvictMinutes) * 60_000L,
    workerCompactionOverrideEnabled = superWorkerCompactionOverrideEnabled,
    workerCompactionTriggerPercent = normalizeCompactionTriggerPercent(superWorkerCompactionPercent),
)

enum class SessionNamingMode { AUTO, MANUAL }

const val CHAT_FONT_SCALE_MIN = 0.85f
const val CHAT_FONT_SCALE_MAX = 1.4f
const val CHAT_FONT_SCALE_STEP = 0.05f
const val CHAT_FONT_SCALE_DEFAULT = 1f

const val COMPACTION_TRIGGER_MIN = 50
const val COMPACTION_TRIGGER_MAX = 90
const val COMPACTION_TRIGGER_STEP = 5
const val COMPACTION_TRIGGER_DEFAULT = 75

const val SUPER_CONCURRENCY_MIN = 1
const val SUPER_CONCURRENCY_MAX = 5
const val SUPER_CONCURRENCY_DEFAULT = 5

const val SUPER_SLOTS_MIN = 3
const val SUPER_SLOTS_MAX = 10
const val SUPER_SLOTS_DEFAULT = 5

val SUPER_STALL_TIMEOUT_OPTIONS_MINUTES = listOf(0, 3, 5, 10, 15, 30)
const val SUPER_STALL_TIMEOUT_DEFAULT_MINUTES = 10

val SUPER_QUESTION_TIMEOUT_OPTIONS_MINUTES = listOf(0, 1, 2, 3, 5, 10)
const val SUPER_QUESTION_TIMEOUT_DEFAULT_MINUTES = 3

val SUPER_COALESCE_WINDOW_OPTIONS_MS = listOf(0L, 1_000L, 1_500L, 3_000L, 5_000L)
const val SUPER_COALESCE_WINDOW_DEFAULT_MS = 1_500L

val SUPER_RECEIPT_CHARS_OPTIONS = listOf(500, 1_000, 1_500, 3_000, 5_000)
const val SUPER_RECEIPT_CHARS_DEFAULT = 1_500

val SUPER_IDLE_EVICT_OPTIONS_MINUTES = listOf(5, 15, 30, 60)
const val SUPER_IDLE_EVICT_DEFAULT_MINUTES = 5

internal fun normalizeSuperConcurrency(value: Int): Int =
    value.coerceIn(SUPER_CONCURRENCY_MIN, SUPER_CONCURRENCY_MAX)

internal fun normalizeSuperSlotsPerBot(value: Int): Int =
    value.coerceIn(SUPER_SLOTS_MIN, SUPER_SLOTS_MAX)

internal fun normalizeSuperStallTimeoutMinutes(value: Int): Int =
    SUPER_STALL_TIMEOUT_OPTIONS_MINUTES.minByOrNull { kotlin.math.abs(it - value) }
        ?: SUPER_STALL_TIMEOUT_DEFAULT_MINUTES

internal fun normalizeSuperQuestionTimeoutMinutes(value: Int): Int =
    SUPER_QUESTION_TIMEOUT_OPTIONS_MINUTES.minByOrNull { kotlin.math.abs(it - value) }
        ?: SUPER_QUESTION_TIMEOUT_DEFAULT_MINUTES

internal fun normalizeSuperCoalesceWindowMs(value: Long): Long =
    SUPER_COALESCE_WINDOW_OPTIONS_MS.minByOrNull { kotlin.math.abs(it - value) }
        ?: SUPER_COALESCE_WINDOW_DEFAULT_MS

internal fun normalizeSuperReceiptMaxChars(value: Int): Int =
    SUPER_RECEIPT_CHARS_OPTIONS.minByOrNull { kotlin.math.abs(it - value) }
        ?: SUPER_RECEIPT_CHARS_DEFAULT

internal fun normalizeSuperIdleEvictMinutes(value: Int): Int =
    SUPER_IDLE_EVICT_OPTIONS_MINUTES.minByOrNull { kotlin.math.abs(it - value) }
        ?: SUPER_IDLE_EVICT_DEFAULT_MINUTES

internal fun normalizeCompactionTriggerPercent(value: Int): Int {
    val clamped = value.coerceIn(COMPACTION_TRIGGER_MIN, COMPACTION_TRIGGER_MAX)
    val offset = clamped - COMPACTION_TRIGGER_MIN
    val rounded = ((offset + COMPACTION_TRIGGER_STEP / 2) / COMPACTION_TRIGGER_STEP) * COMPACTION_TRIGGER_STEP
    return (COMPACTION_TRIGGER_MIN + rounded).coerceIn(COMPACTION_TRIGGER_MIN, COMPACTION_TRIGGER_MAX)
}

internal fun normalizeChatFontScale(value: Float): Float {
    if (!value.isFinite()) return CHAT_FONT_SCALE_DEFAULT
    val percent = (value * 100f).roundToInt().coerceIn(
        (CHAT_FONT_SCALE_MIN * 100f).roundToInt(),
        (CHAT_FONT_SCALE_MAX * 100f).roundToInt(),
    )
    val roundedPercent = (percent / (CHAT_FONT_SCALE_STEP * 100f)).roundToInt() * (CHAT_FONT_SCALE_STEP * 100f).roundToInt()
    return roundedPercent / 100f
}

enum class UiToggle {
    AUTO_RETRY,
    BACKGROUND_JOB_WAKE,
}

class AppPreferences(context: Context) {
    private val store = context.applicationContext.appPreferencesDataStore
    private val autoRetryKey = booleanPreferencesKey("auto_retry")
    private val backgroundJobWakeKey = booleanPreferencesKey("background_job_wake")
    private val pinnedWorkspacesKey = stringSetPreferencesKey("pinned_workspace_ids")
    private val chatFontScaleKey = floatPreferencesKey("chat_font_scale")
    private val sendWhileRunningKey = stringPreferencesKey("send_while_running")
    private val processDetailKey = stringPreferencesKey("process_detail")
    private val metadataModeKey = stringPreferencesKey("metadata_mode")
    private val metadataProviderKey = stringPreferencesKey("metadata_provider")
    private val metadataModelKey = stringPreferencesKey("metadata_model")
    private val compactionTriggerPercentKey = intPreferencesKey("compaction_trigger_percent")
    private val appModeKey = stringPreferencesKey("app_mode")
    private val superMaxConcurrentWorkersKey = intPreferencesKey("super_max_concurrent_workers")
    private val superMaxSlotsPerBotKey = intPreferencesKey("super_max_slots_per_bot")
    private val superStallTimeoutMinutesKey = intPreferencesKey("super_stall_timeout_minutes")
    private val superQuestionTimeoutMinutesKey = intPreferencesKey("super_question_timeout_minutes")
    private val superWorkerJobWakeKey = booleanPreferencesKey("super_worker_job_wake")
    private val superCoalesceWindowMsKey = longPreferencesKey("super_coalesce_window_ms")
    private val superReceiptMaxCharsKey = intPreferencesKey("super_receipt_max_chars")
    private val superIdleEvictMinutesKey = intPreferencesKey("super_idle_evict_minutes")
    private val superWorkerCompactionOverrideEnabledKey = booleanPreferencesKey("super_worker_compaction_override_enabled")
    private val superWorkerCompactionPercentKey = intPreferencesKey("super_worker_compaction_percent")

    val preferences: Flow<UiPreferences> = store.data
        .map { values ->
            UiPreferences(
                autoRetry = values[autoRetryKey] ?: true,
                backgroundJobWake = values[backgroundJobWakeKey] ?: true,
                pinnedWorkspaceIds = values[pinnedWorkspacesKey] ?: emptySet(),
                chatFontScale = normalizeChatFontScale(values[chatFontScaleKey] ?: CHAT_FONT_SCALE_DEFAULT),
                sendWhileRunning = values[sendWhileRunningKey]
                    ?.let { stored -> SendWhileRunning.entries.firstOrNull { it.name == stored } }
                    ?: SendWhileRunning.STEER,
                processDetail = values[processDetailKey]
                    ?.let { stored -> ProcessDetail.entries.firstOrNull { it.name == stored } }
                    ?: ProcessDetail.DETAILED,
                metadataMode = values[metadataModeKey]
                    ?.let { stored -> SessionNamingMode.entries.firstOrNull { it.name == stored } }
                    ?: SessionNamingMode.AUTO,
                metadataProviderId = values[metadataProviderKey].orEmpty(),
                metadataModelId = values[metadataModelKey].orEmpty(),
                compactionTriggerPercent = normalizeCompactionTriggerPercent(
                    values[compactionTriggerPercentKey] ?: COMPACTION_TRIGGER_DEFAULT,
                ),
                appMode = parseAppMode(values[appModeKey]),
                superMaxConcurrentWorkers = normalizeSuperConcurrency(
                    values[superMaxConcurrentWorkersKey] ?: SUPER_CONCURRENCY_DEFAULT,
                ),
                superMaxSlotsPerBot = normalizeSuperSlotsPerBot(
                    values[superMaxSlotsPerBotKey] ?: SUPER_SLOTS_DEFAULT,
                ),
                superStallTimeoutMinutes = normalizeSuperStallTimeoutMinutes(
                    values[superStallTimeoutMinutesKey] ?: SUPER_STALL_TIMEOUT_DEFAULT_MINUTES,
                ),
                superQuestionTimeoutMinutes = normalizeSuperQuestionTimeoutMinutes(
                    values[superQuestionTimeoutMinutesKey] ?: SUPER_QUESTION_TIMEOUT_DEFAULT_MINUTES,
                ),
                superWorkerJobWake = values[superWorkerJobWakeKey] ?: true,
                superCoalesceWindowMs = normalizeSuperCoalesceWindowMs(
                    values[superCoalesceWindowMsKey] ?: SUPER_COALESCE_WINDOW_DEFAULT_MS,
                ),
                superReceiptMaxChars = normalizeSuperReceiptMaxChars(
                    values[superReceiptMaxCharsKey] ?: SUPER_RECEIPT_CHARS_DEFAULT,
                ),
                superIdleEvictMinutes = normalizeSuperIdleEvictMinutes(
                    values[superIdleEvictMinutesKey] ?: SUPER_IDLE_EVICT_DEFAULT_MINUTES,
                ),
                superWorkerCompactionOverrideEnabled = values[superWorkerCompactionOverrideEnabledKey] ?: false,
                superWorkerCompactionPercent = normalizeCompactionTriggerPercent(
                    values[superWorkerCompactionPercentKey] ?: COMPACTION_TRIGGER_DEFAULT,
                ),
            )
        }
        .catch { error ->
            if (error is IOException) emit(UiPreferences()) else throw error
        }

    suspend fun read(): UiPreferences = preferences.first()

    suspend fun set(toggle: UiToggle, enabled: Boolean) {
        store.edit { values ->
            when (toggle) {
                UiToggle.AUTO_RETRY -> values[autoRetryKey] = enabled
                UiToggle.BACKGROUND_JOB_WAKE -> values[backgroundJobWakeKey] = enabled
            }
        }
    }

    suspend fun setChatFontScale(value: Float) {
        store.edit { it[chatFontScaleKey] = normalizeChatFontScale(value) }
    }

    suspend fun setSendWhileRunning(value: SendWhileRunning) {
        store.edit { it[sendWhileRunningKey] = value.name }
    }

    suspend fun setProcessDetail(value: ProcessDetail) {
        store.edit { it[processDetailKey] = value.name }
    }

    suspend fun setMetadataMode(value: SessionNamingMode) {
        store.edit { it[metadataModeKey] = value.name }
    }

    suspend fun setMetadataModel(providerId: String, modelId: String) {
        store.edit { values ->
            values[metadataProviderKey] = providerId
            values[metadataModelKey] = modelId
        }
    }

    suspend fun setCompactionTriggerPercent(value: Int) {
        store.edit { values ->
            values[compactionTriggerPercentKey] = normalizeCompactionTriggerPercent(value)
        }
    }

    suspend fun setAppMode(mode: AppMode) {
        store.edit { values ->
            values[appModeKey] = mode.name
        }
    }

    suspend fun setSuperMaxConcurrentWorkers(value: Int) {
        store.edit { it[superMaxConcurrentWorkersKey] = normalizeSuperConcurrency(value) }
    }

    suspend fun setSuperMaxSlotsPerBot(value: Int) {
        store.edit { it[superMaxSlotsPerBotKey] = normalizeSuperSlotsPerBot(value) }
    }

    suspend fun setSuperStallTimeoutMinutes(value: Int) {
        store.edit { it[superStallTimeoutMinutesKey] = normalizeSuperStallTimeoutMinutes(value) }
    }

    suspend fun setSuperQuestionTimeoutMinutes(value: Int) {
        store.edit { it[superQuestionTimeoutMinutesKey] = normalizeSuperQuestionTimeoutMinutes(value) }
    }

    suspend fun setSuperWorkerJobWake(enabled: Boolean) {
        store.edit { it[superWorkerJobWakeKey] = enabled }
    }

    suspend fun setSuperCoalesceWindowMs(value: Long) {
        store.edit { it[superCoalesceWindowMsKey] = normalizeSuperCoalesceWindowMs(value) }
    }

    suspend fun setSuperReceiptMaxChars(value: Int) {
        store.edit { it[superReceiptMaxCharsKey] = normalizeSuperReceiptMaxChars(value) }
    }

    suspend fun setSuperIdleEvictMinutes(value: Int) {
        store.edit { it[superIdleEvictMinutesKey] = normalizeSuperIdleEvictMinutes(value) }
    }

    suspend fun setSuperWorkerCompactionOverrideEnabled(enabled: Boolean) {
        store.edit { it[superWorkerCompactionOverrideEnabledKey] = enabled }
    }

    suspend fun setSuperWorkerCompactionPercent(value: Int) {
        store.edit { it[superWorkerCompactionPercentKey] = normalizeCompactionTriggerPercent(value) }
    }

    suspend fun resetSuperDefaults() {
        store.edit { values ->
            values.remove(superMaxConcurrentWorkersKey)
            values.remove(superMaxSlotsPerBotKey)
            values.remove(superStallTimeoutMinutesKey)
            values.remove(superQuestionTimeoutMinutesKey)
            values.remove(superWorkerJobWakeKey)
            values.remove(superCoalesceWindowMsKey)
            values.remove(superReceiptMaxCharsKey)
            values.remove(superIdleEvictMinutesKey)
            values.remove(superWorkerCompactionOverrideEnabledKey)
            values.remove(superWorkerCompactionPercentKey)
        }
    }

    suspend fun setWorkspacePinned(workspaceId: String, pinned: Boolean) {
        if (workspaceId.isBlank()) return
        store.edit { values ->
            val current = values[pinnedWorkspacesKey] ?: emptySet()
            values[pinnedWorkspacesKey] = if (pinned) current + workspaceId else current - workspaceId
        }
    }
}

internal fun parseAppMode(raw: String?): AppMode =
    raw?.let { stored -> AppMode.entries.firstOrNull { it.name == stored } } ?: AppMode.NORMAL
