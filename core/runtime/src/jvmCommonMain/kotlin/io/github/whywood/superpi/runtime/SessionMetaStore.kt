package io.github.whywood.superpi.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class SessionMeta(
    val titleOverride: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val memoryExcluded: Boolean = false,
    val updatedAt: Long = 0,
)

class SessionMetaStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    val metas: Flow<Map<String, SessionMeta>> = store.entries
        .map { values -> decode(values.stringValue(ENTRIES_KEY)) }

    suspend fun read(): Map<String, SessionMeta> = metas.first()

    suspend fun update(sessionId: String, transform: (SessionMeta) -> SessionMeta) {
        store.edit { values ->
            val current = decode(values.stringValue(ENTRIES_KEY)).toMutableMap()
            val next = transform(current[sessionId] ?: SessionMeta())
            current[sessionId] = next.copy(updatedAt = next.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            values.putString(ENTRIES_KEY, encode(current))
        }
    }

    suspend fun remove(sessionId: String) {
        store.edit { values ->
            val current = decode(values.stringValue(ENTRIES_KEY)).toMutableMap()
            current.remove(sessionId)
            values.putString(ENTRIES_KEY, encode(current))
        }
    }

    private fun decode(raw: String?): Map<String, SessionMeta> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            root.entries.associate { (id, element) ->
                val item = element as? JsonObject ?: JsonObject(emptyMap())
                id to SessionMeta(
                    titleOverride = (item["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                    pinned = (item["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    archived = (item["archived"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    memoryExcluded = (item["memoryExcluded"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    updatedAt = (item["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun encode(entries: Map<String, SessionMeta>): String = buildJsonObject {
        entries.forEach { (id, meta) ->
            put(
                id,
                buildJsonObject {
                    put("title", meta.titleOverride ?: "")
                    put("pinned", meta.pinned)
                    put("archived", meta.archived)
                    put("memoryExcluded", meta.memoryExcluded)
                    put("updatedAt", meta.updatedAt)
                },
            )
        }
    }.toString()

    private companion object {
        const val ENTRIES_KEY = "entries"
    }
}
