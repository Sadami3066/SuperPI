package io.github.whywood.superpi.runtime

import kotlinx.coroutines.flow.Flow

sealed interface StoredPreference {
    data class Text(val value: String) : StoredPreference
    data class Integer(val value: Int) : StoredPreference
}

interface KeyValueStore {
    val entries: Flow<Map<String, StoredPreference>>

    suspend fun readAll(): Map<String, StoredPreference>

    suspend fun edit(transform: (MutableMap<String, StoredPreference>) -> Unit)
}

fun Map<String, StoredPreference>.stringValue(key: String): String? =
    (this[key] as? StoredPreference.Text)?.value

fun Map<String, StoredPreference>.intValue(key: String): Int? = when (val entry = this[key]) {
    is StoredPreference.Integer -> entry.value
    is StoredPreference.Text -> entry.value.toIntOrNull()
    null -> null
}

fun MutableMap<String, StoredPreference>.putString(key: String, value: String) {
    this[key] = StoredPreference.Text(value)
}

fun MutableMap<String, StoredPreference>.putInt(key: String, value: Int) {
    this[key] = StoredPreference.Integer(value)
}
