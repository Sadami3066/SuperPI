package io.github.whywood.superpi.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class WorkspaceStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    val workspaces: Flow<List<RuntimeWorkspace>> = store.entries
        .map { values -> decode(values.stringValue(ENTRIES_KEY)) }

    suspend fun read(): List<RuntimeWorkspace> = workspaces.first()

    suspend fun readHomeDefaultModel(): ModelConfig? {
        val raw = store.readAll().stringValue(HOME_MODEL_KEY) ?: return null
        return decodeModelConfig(runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull())
    }

    suspend fun updateHomeDefaultModel(model: ModelConfig?) {
        store.edit { values ->
            if (model == null) {
                values.remove(HOME_MODEL_KEY)
            } else {
                values.putString(HOME_MODEL_KEY, encodeModelConfig(model).toString())
            }
        }
    }

    suspend fun readHomeProfile(): HomeWorkspaceProfile {
        val raw = store.readAll().stringValue(HOME_PROFILE_KEY) ?: return HomeWorkspaceProfile()
        return decodeHomeProfile(runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull())
    }

    suspend fun updateHomeProfile(profile: HomeWorkspaceProfile) {
        store.edit { values ->
            values.putString(HOME_PROFILE_KEY, encodeHomeProfile(profile).toString())
        }
    }

    suspend fun add(workspace: RuntimeWorkspace) {
        store.edit { values ->
            val current = decode(values.stringValue(ENTRIES_KEY)).toMutableList()
            current.removeAll { it.id == workspace.id }
            current.add(workspace)
            values.putString(ENTRIES_KEY, encode(current))
        }
    }

    suspend fun rename(id: String, name: String) {
        store.edit { values ->
            val current = decode(values.stringValue(ENTRIES_KEY)).map {
                if (it.id == id) it.copy(name = name) else it
            }
            values.putString(ENTRIES_KEY, encode(current))
        }
    }

    suspend fun remove(id: String) {
        store.edit { values ->
            values.putString(ENTRIES_KEY, encode(decode(values.stringValue(ENTRIES_KEY)).filterNot { it.id == id }))
        }
    }

    private fun decode(raw: String?): List<RuntimeWorkspace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(raw).jsonArray
            buildList {
                for (element in array) {
                    val item = element as? JsonObject ?: continue
                    val id = item.text("id")?.takeIf { it.isNotBlank() } ?: continue
                    val name = item.text("name")?.takeIf { it.isNotBlank() } ?: id
                    val cwd = item.text("cwd").orEmpty()
                    if (cwd.isBlank()) continue
                    add(
                        RuntimeWorkspace(
                            id = id,
                            name = name,
                            cwd = cwd,
                            createdAt = (item["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                            avatar = item.text("avatar")?.trim()?.takeIf { it.isNotEmpty() },
                            intro = item.text("intro")?.trim()?.takeIf { it.isNotEmpty() },
                            defaultModel = decodeModelConfig(item["defaultModel"] as? JsonObject),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(workspaces: List<RuntimeWorkspace>): String = buildJsonArray {
        workspaces.forEach { workspace ->
            add(
                buildJsonObject {
                    put("id", workspace.id)
                    put("name", workspace.name)
                    put("cwd", workspace.cwd)
                    put("createdAt", workspace.createdAt)
                    workspace.avatar?.let { put("avatar", it) }
                    workspace.intro?.let { put("intro", it) }
                    workspace.defaultModel?.let { put("defaultModel", encodeModelConfig(it)) }
                },
            )
        }
    }.toString()

    private fun decodeModelConfig(obj: JsonObject?): ModelConfig? {
        if (obj == null) return null
        val providerId = obj.text("providerId")?.trim().orEmpty()
        val modelId = obj.text("modelId")?.trim().orEmpty()
        if (providerId.isEmpty() || modelId.isEmpty()) return null
        val rawThinking = obj.text("thinkingLevel")?.trim().orEmpty()
        val thinking = ThinkingLevel.entries.firstOrNull { it.name.equals(rawThinking, ignoreCase = true) }
            ?: ThinkingLevel.OFF
        return ModelConfig(providerId = providerId, modelId = modelId, thinkingLevel = thinking)
    }

    private fun encodeModelConfig(config: ModelConfig): JsonObject = buildJsonObject {
        put("providerId", config.providerId)
        put("modelId", config.modelId)
        put("thinkingLevel", config.thinkingLevel.name)
    }

    private fun decodeHomeProfile(obj: JsonObject?): HomeWorkspaceProfile {
        if (obj == null) return HomeWorkspaceProfile()
        val name = obj.text("name")?.trim().orEmpty().ifEmpty { AgentRuntimeHost.HOME_NAME }
        val avatar = obj.text("avatar")?.trim()?.takeIf { it.isNotEmpty() }
        val intro = obj.text("intro")?.trim()?.takeIf { it.isNotEmpty() }
        return HomeWorkspaceProfile(name = name, avatar = avatar, intro = intro)
    }

    private fun encodeHomeProfile(profile: HomeWorkspaceProfile): JsonObject = buildJsonObject {
        put("name", profile.name.trim().ifEmpty { AgentRuntimeHost.HOME_NAME })
        profile.avatar?.trim()?.takeIf { it.isNotEmpty() }?.let { put("avatar", it) }
        profile.intro?.trim()?.takeIf { it.isNotEmpty() }?.let { put("intro", it) }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val ENTRIES_KEY = "entries"
        const val HOME_MODEL_KEY = "home_default_model"
        const val HOME_PROFILE_KEY = "home_profile"
    }
}
