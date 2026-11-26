package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.KeyValueStore
import io.github.whywood.superpi.runtime.MemoryInjectionBudget
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.StoredPreference
import io.github.whywood.superpi.runtime.intValue
import io.github.whywood.superpi.runtime.putInt
import io.github.whywood.superpi.runtime.putString
import io.github.whywood.superpi.runtime.stringValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface MemorySettingsSource {
    val settings: Flow<MemorySettings>

    suspend fun read(): MemorySettings

    suspend fun save(value: MemorySettings)
}

class MemoryPreferencesStore(
    private val store: KeyValueStore,
    private val onPauseModeChanged: suspend (Boolean) -> Unit = {},
    private val onInjectionBudgetChanged: suspend () -> Unit = {},
) : MemorySettingsSource {
    override val settings: Flow<MemorySettings> = store.entries
        .map { values -> readFromPreferences(values) }

    override suspend fun read(): MemorySettings = settings.first()

    override suspend fun save(value: MemorySettings) {
        val paused = value.mode == MemoryModelMode.PAUSED
        if (paused) {
            onPauseModeChanged(true)
        }
        var budgetChanged = false
        val normalizedTotal = MemorySettings.normalizeInjectionMaxChars(value.injectionMaxChars)
        val normalizedProfile = MemorySettings.normalizeSectionBudgetChars(value.profileBudgetChars)
        val normalizedRelations = MemorySettings.normalizeSectionBudgetChars(value.relationsBudgetChars)
        val normalizedEvents = MemorySettings.normalizeSectionBudgetChars(value.eventsBudgetChars)
        val normalizedDirectory = MemorySettings.normalizeSectionBudgetChars(value.directoryBudgetChars)
        store.edit { values ->
            val previousBudget = readFromPreferences(values).injectionBudget()
            values.putString(MODE_KEY, value.mode.wire)
            val hotProvider = value.hotProviderId?.trim()?.takeIf { it.isNotEmpty() }
            val hotModel = value.hotModelId?.trim()?.takeIf { it.isNotEmpty() }
            val coldProvider = value.coldProviderId?.trim()?.takeIf { it.isNotEmpty() }
            val coldModel = value.coldModelId?.trim()?.takeIf { it.isNotEmpty() }
            val legacyProvider = value.fixedProviderId?.trim()?.takeIf { it.isNotEmpty() }
            val legacyModel = value.fixedModelId?.trim()?.takeIf { it.isNotEmpty() }

            val hotUseLegacy = hotProvider == null && hotModel == null
            val coldUseLegacy = coldProvider == null && coldModel == null
            writePair(
                values = values,
                providerKey = HOT_PROVIDER_KEY,
                modelKey = HOT_MODEL_KEY,
                providerId = if (hotUseLegacy) legacyProvider else hotProvider,
                modelId = if (hotUseLegacy) legacyModel else hotModel,
            )
            writePair(
                values = values,
                providerKey = COLD_PROVIDER_KEY,
                modelKey = COLD_MODEL_KEY,
                providerId = if (coldUseLegacy) legacyProvider else coldProvider,
                modelId = if (coldUseLegacy) legacyModel else coldModel,
            )
            values.remove(LEGACY_PROVIDER_KEY)
            values.remove(LEGACY_MODEL_KEY)

            values.putInt(HOT_IDLE_DEBOUNCE_KEY, value.hotIdleDebounceSeconds.coerceIn(15, 600))
            values.putInt(COLD_CONSOLIDATION_HOURS_KEY, value.coldConsolidationHours.coerceIn(0, 168))
            values.putInt(INJECTION_MAX_CHARS_KEY, normalizedTotal)
            writeNullableInt(values, PROFILE_BUDGET_CHARS_KEY, normalizedProfile)
            writeNullableInt(values, RELATIONS_BUDGET_CHARS_KEY, normalizedRelations)
            writeNullableInt(values, EVENTS_BUDGET_CHARS_KEY, normalizedEvents)
            writeNullableInt(values, DIRECTORY_BUDGET_CHARS_KEY, normalizedDirectory)
            values.remove(LEGACY_HOT_MAX_STEPS_KEY)
            values.remove(LEGACY_COLD_MAX_STEPS_KEY)
            values.remove(LEGACY_CONTEXT_SCALE_KEY)

            val nextBudget = MemoryInjectionBudget(
                totalMaxChars = normalizedTotal,
                profileMaxChars = normalizedProfile,
                relationsMaxChars = normalizedRelations,
                eventsMaxChars = normalizedEvents,
                directoryMaxChars = normalizedDirectory,
            )
            budgetChanged = previousBudget != nextBudget
        }
        if (budgetChanged) {
            onInjectionBudgetChanged()
        }
        if (!paused) {
            onPauseModeChanged(false)
        }
    }

    private fun readFromPreferences(values: Map<String, StoredPreference>): MemorySettings {
        val legacyProvider = values.stringValue(LEGACY_PROVIDER_KEY)?.takeIf { it.isNotBlank() }
        val legacyModel = values.stringValue(LEGACY_MODEL_KEY)?.takeIf { it.isNotBlank() }
        val hotProvider = values.stringValue(HOT_PROVIDER_KEY)?.takeIf { it.isNotBlank() } ?: legacyProvider
        val hotModel = values.stringValue(HOT_MODEL_KEY)?.takeIf { it.isNotBlank() } ?: legacyModel
        val coldProvider = values.stringValue(COLD_PROVIDER_KEY)?.takeIf { it.isNotBlank() } ?: legacyProvider
        val coldModel = values.stringValue(COLD_MODEL_KEY)?.takeIf { it.isNotBlank() } ?: legacyModel
        return MemorySettings(
            mode = MemoryModelMode.fromWire(values.stringValue(MODE_KEY)),
            fixedProviderId = legacyProvider,
            fixedModelId = legacyModel,
            hotProviderId = hotProvider,
            hotModelId = hotModel,
            coldProviderId = coldProvider,
            coldModelId = coldModel,
            hotIdleDebounceSeconds = (values.intValue(HOT_IDLE_DEBOUNCE_KEY)
                ?: MemorySettings.DEFAULT_HOT_IDLE_DEBOUNCE_SECONDS).coerceIn(15, 600),
            coldConsolidationHours = (values.intValue(COLD_CONSOLIDATION_HOURS_KEY)
                ?: MemorySettings.DEFAULT_COLD_CONSOLIDATION_HOURS).coerceIn(0, 168),
            injectionMaxChars = values.intValue(INJECTION_MAX_CHARS_KEY)
                ?.let(MemorySettings::normalizeInjectionMaxChars)
                ?: MemorySettings.DEFAULT_INJECTION_MAX_CHARS,
            profileBudgetChars = values.intValue(PROFILE_BUDGET_CHARS_KEY)
                ?.let(MemorySettings::normalizeSectionBudgetChars),
            relationsBudgetChars = values.intValue(RELATIONS_BUDGET_CHARS_KEY)
                ?.let(MemorySettings::normalizeSectionBudgetChars),
            eventsBudgetChars = values.intValue(EVENTS_BUDGET_CHARS_KEY)
                ?.let(MemorySettings::normalizeSectionBudgetChars),
            directoryBudgetChars = values.intValue(DIRECTORY_BUDGET_CHARS_KEY)
                ?.let(MemorySettings::normalizeSectionBudgetChars),
        )
    }

    private fun writeNullableInt(
        values: MutableMap<String, StoredPreference>,
        key: String,
        value: Int?,
    ) {
        if (value == null) {
            values.remove(key)
        } else {
            values.putInt(key, value)
        }
    }

    private fun writePair(
        values: MutableMap<String, StoredPreference>,
        providerKey: String,
        modelKey: String,
        providerId: String?,
        modelId: String?,
    ) {
        if (providerId != null && modelId != null) {
            values.putString(providerKey, providerId)
            values.putString(modelKey, modelId)
        } else {
            values.remove(providerKey)
            values.remove(modelKey)
        }
    }

    private companion object {
        const val MODE_KEY = "mode"
        const val LEGACY_PROVIDER_KEY = "fixed_provider_id"
        const val LEGACY_MODEL_KEY = "fixed_model_id"
        const val HOT_PROVIDER_KEY = "hot_provider_id"
        const val HOT_MODEL_KEY = "hot_model_id"
        const val COLD_PROVIDER_KEY = "cold_provider_id"
        const val COLD_MODEL_KEY = "cold_model_id"
        const val HOT_IDLE_DEBOUNCE_KEY = "hot_idle_debounce_seconds"
        const val COLD_CONSOLIDATION_HOURS_KEY = "cold_consolidation_hours"
        const val INJECTION_MAX_CHARS_KEY = "injection_max_chars"
        const val PROFILE_BUDGET_CHARS_KEY = "profile_budget_chars"
        const val RELATIONS_BUDGET_CHARS_KEY = "relations_budget_chars"
        const val EVENTS_BUDGET_CHARS_KEY = "events_budget_chars"
        const val DIRECTORY_BUDGET_CHARS_KEY = "directory_budget_chars"
        const val LEGACY_HOT_MAX_STEPS_KEY = "hot_max_steps"
        const val LEGACY_COLD_MAX_STEPS_KEY = "cold_max_steps"
        const val LEGACY_CONTEXT_SCALE_KEY = "context_scale"
    }
}
