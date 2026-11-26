package io.github.whywood.superpi.runtime.memory

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.whywood.superpi.runtime.DataStoreKeyValueStore

private val Context.memoryPreferencesDataStore by preferencesDataStore(name = "memory_preferences")

fun MemoryPreferencesStore(
    context: Context,
    onPauseModeChanged: suspend (Boolean) -> Unit = {},
    onInjectionBudgetChanged: suspend () -> Unit = {},
): MemoryPreferencesStore = MemoryPreferencesStore(
    store = DataStoreKeyValueStore(context.applicationContext.memoryPreferencesDataStore),
    onPauseModeChanged = onPauseModeChanged,
    onInjectionBudgetChanged = onInjectionBudgetChanged,
)
