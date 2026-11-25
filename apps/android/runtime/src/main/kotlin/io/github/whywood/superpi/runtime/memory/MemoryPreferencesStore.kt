package io.github.whywood.superpi.runtime.memory

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.whywood.superpi.runtime.MemoryInjectionBudget
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemorySettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.memoryPreferencesDataStore by preferencesDataStore(name = "memory_preferences")

interface MemorySettingsSource {
    val settings: Flow<MemorySettings>

    suspend fun read(): MemorySettings

    suspend fun save(value: MemorySettings)
}

class MemoryPreferencesStore(
    context: Context,
    private val onPauseModeChanged: suspend (Boolean) -> Unit = {},
    private val onInjectionBudgetChanged: suspend () -> Unit = {},
) : MemorySettingsSource {
    private val store = context.applicationContext.memoryPreferencesDataStore
    private val modeKey = stringPreferencesKey("mode")
    private val legacyProviderKey = stringPreferencesKey("fixed_provider_id")
    private val legacyModelKey = stringPreferencesKey("fixed_model_id")
    private val hotProviderKey = stringPreferencesKey("hot_provider_id")
    private val hotModelKey = stringPreferencesKey("hot_model_id")
    private val coldProviderKey = stringPreferencesKey("cold_provider_id")
    private val coldModelKey = stringPreferencesKey("cold_model_id")
    private val hotIdleDebounceKey = intPreferencesKey("hot_idle_debounce_seconds")
    private val coldConsolidationHoursKey = intPreferencesKey("cold_consolidation_hours")
    private val injectionMaxCharsKey = intPreferencesKey("injection_max_chars")
    private val profileBudgetCharsKey = intPreferencesKey("profile_budget_chars")
    private val relationsBudgetCharsKey = intPreferencesKey("relations_budget_chars")
    private val eventsBudgetCharsKey = intPreferencesKey("events_budget_chars")
    private val directoryBudgetCharsKey = intPreferencesKey("directory_budget_chars")
    private val legacyHotMaxStepsKey = intPreferencesKey("hot_max_steps")
    private val legacyColdMaxStepsKey = intPreferencesKey("cold_max_steps")
    private val legacyContextScaleKey = stringPreferencesKey("context_scale")

    override val settings: Flow<MemorySettings> = store.data
        .map { values -> readFromPreferences(values) }
        .catch { error ->
            if (error is IOException) emit(MemorySettings()) else throw error
        }

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
            values[modeKey] = value.mode.wire
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
                providerKey = hotProviderKey,
                modelKey = hotModelKey,
                providerId = if (hotUseLegacy) legacyProvider else hotProvider,
                modelId = if (hotUseLegacy) legacyModel else hotModel,
            )
            writePair(
                values = values,
                providerKey = coldProviderKey,
                modelKey = coldModelKey,
                providerId = if (coldUseLegacy) legacyProvider else coldProvider,
                modelId = if (coldUseLegacy) legacyModel else coldModel,
            )
            values.remove(legacyProviderKey)
            values.remove(legacyModelKey)

            values[hotIdleDebounceKey] = value.hotIdleDebounceSeconds.coerceIn(15, 600)
            values[coldConsolidationHoursKey] = value.coldConsolidationHours.coerceIn(0, 168)
            values[injectionMaxCharsKey] = normalizedTotal
            writeNullableInt(values, profileBudgetCharsKey, normalizedProfile)
            writeNullableInt(values, relationsBudgetCharsKey, normalizedRelations)
            writeNullableInt(values, eventsBudgetCharsKey, normalizedEvents)
            writeNullableInt(values, directoryBudgetCharsKey, normalizedDirectory)
            values.remove(legacyHotMaxStepsKey)
            values.remove(legacyColdMaxStepsKey)
            values.remove(legacyContextScaleKey)

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

    private fun readFromPreferences(values: Preferences): MemorySettings {
        val legacyProvider = values[legacyProviderKey]?.takeIf { it.isNotBlank() }
        val legacyModel = values[legacyModelKey]?.takeIf { it.isNotBlank() }
        val hotProvider = values[hotProviderKey]?.takeIf { it.isNotBlank() } ?: legacyProvider
        val hotModel = values[hotModelKey]?.takeIf { it.isNotBlank() } ?: legacyModel
        val coldProvider = values[coldProviderKey]?.takeIf { it.isNotBlank() } ?: legacyProvider
        val coldModel = values[coldModelKey]?.takeIf { it.isNotBlank() } ?: legacyModel
        return MemorySettings(
            mode = MemoryModelMode.fromWire(values[modeKey]),
            fixedProviderId = legacyProvider,
            fixedModelId = legacyModel,
            hotProviderId = hotProvider,
            hotModelId = hotModel,
            coldProviderId = coldProvider,
            coldModelId = coldModel,
            hotIdleDebounceSeconds = (values[hotIdleDebounceKey]
                ?: MemorySettings.DEFAULT_HOT_IDLE_DEBOUNCE_SECONDS).coerceIn(15, 600),
            coldConsolidationHours = (values[coldConsolidationHoursKey]
                ?: MemorySettings.DEFAULT_COLD_CONSOLIDATION_HOURS).coerceIn(0, 168),
            injectionMaxChars = values[injectionMaxCharsKey]
                ?.let(MemorySettings::normalizeInjectionMaxChars)
                ?: MemorySettings.DEFAULT_INJECTION_MAX_CHARS,
            profileBudgetChars = values[profileBudgetCharsKey]
                ?.let(MemorySettings::normalizeSectionBudgetChars),
            relationsBudgetChars = values[relationsBudgetCharsKey]
                ?.let(MemorySettings::normalizeSectionBudgetChars),
            eventsBudgetChars = values[eventsBudgetCharsKey]
                ?.let(MemorySettings::normalizeSectionBudgetChars),
            directoryBudgetChars = values[directoryBudgetCharsKey]
                ?.let(MemorySettings::normalizeSectionBudgetChars),
        )
    }

    private fun writeNullableInt(
        values: MutablePreferences,
        key: Preferences.Key<Int>,
        value: Int?,
    ) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }

    private fun writePair(
        values: MutablePreferences,
        providerKey: Preferences.Key<String>,
        modelKey: Preferences.Key<String>,
        providerId: String?,
        modelId: String?,
    ) {
        if (providerId != null && modelId != null) {
            values[providerKey] = providerId
            values[modelKey] = modelId
        } else {
            values.remove(providerKey)
            values.remove(modelKey)
        }
    }
}
