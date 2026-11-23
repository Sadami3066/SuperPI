package io.github.whywood.superpi.runtime.memory

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
) : MemorySettingsSource {
    private val store = context.applicationContext.memoryPreferencesDataStore
    private val modeKey = stringPreferencesKey("mode")
    private val providerKey = stringPreferencesKey("fixed_provider_id")
    private val modelKey = stringPreferencesKey("fixed_model_id")

    override val settings: Flow<MemorySettings> = store.data
        .map { values ->
            MemorySettings(
                mode = MemoryModelMode.fromWire(values[modeKey]),
                fixedProviderId = values[providerKey]?.takeIf { it.isNotBlank() },
                fixedModelId = values[modelKey]?.takeIf { it.isNotBlank() },
            )
        }
        .catch { error ->
            if (error is IOException) emit(MemorySettings()) else throw error
        }

    override suspend fun read(): MemorySettings = settings.first()

    override suspend fun save(value: MemorySettings) {
        val paused = value.mode == MemoryModelMode.PAUSED
        if (paused) {
            onPauseModeChanged(true)
        }
        store.edit { values ->
            values[modeKey] = value.mode.wire
            val provider = value.fixedProviderId?.trim()?.takeIf { it.isNotEmpty() }
            val model = value.fixedModelId?.trim()?.takeIf { it.isNotEmpty() }
            if (provider != null && model != null) {
                values[providerKey] = provider
                values[modelKey] = model
            } else {
                values.remove(providerKey)
                values.remove(modelKey)
            }
        }
        if (!paused) {
            onPauseModeChanged(false)
        }
    }
}
