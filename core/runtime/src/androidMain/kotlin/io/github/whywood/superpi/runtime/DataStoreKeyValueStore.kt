package io.github.whywood.superpi.runtime

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreKeyValueStore(
    private val dataStore: DataStore<Preferences>,
) : KeyValueStore {
    override val entries: Flow<Map<String, StoredPreference>> = dataStore.data
        .map { prefs -> prefs.toStoredMap() }
        .catch { error ->
            if (error is IOException) emit(emptyMap()) else throw error
        }

    override suspend fun readAll(): Map<String, StoredPreference> = entries.first()

    override suspend fun edit(transform: (MutableMap<String, StoredPreference>) -> Unit) {
        dataStore.edit { prefs ->
            val before = prefs.toStoredMap()
            val next = before.toMutableMap()
            transform(next)
            val existingKeyMap = prefs.asMap().keys.associateBy { it.name }
            for (name in before.keys - next.keys) {
                existingKeyMap[name]?.let { prefs.remove(it) }
            }
            for ((name, value) in next) {
                when (value) {
                    is StoredPreference.Text -> prefs[stringPreferencesKey(name)] = value.value
                    is StoredPreference.Integer -> prefs[intPreferencesKey(name)] = value.value
                }
            }
        }
    }

    private fun Preferences.toStoredMap(): Map<String, StoredPreference> = buildMap {
        asMap().forEach { (key, value) ->
            when (value) {
                is String -> put(key.name, StoredPreference.Text(value))
                is Int -> put(key.name, StoredPreference.Integer(value))
            }
        }
    }
}
