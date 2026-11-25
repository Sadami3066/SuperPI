package io.github.whywood.superpi.runtime

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.workspaceStore by preferencesDataStore(name = "workspaces")

class WorkspaceStore(context: Context) {
    private val store = context.applicationContext.workspaceStore
    private val key = stringPreferencesKey("entries")
    private val homeModelKey = stringPreferencesKey("home_default_model")
    private val homeProfileKey = stringPreferencesKey("home_profile")

    val workspaces: Flow<List<RuntimeWorkspace>> = store.data
        .map { values -> decode(values[key]) }
        .catch { error ->
            if (error is IOException) emit(emptyList()) else throw error
        }

    suspend fun read(): List<RuntimeWorkspace> = workspaces.first()

    suspend fun readHomeDefaultModel(): ModelConfig? {
        val raw = store.data
            .map { values -> values[homeModelKey] }
            .catch { error ->
                if (error is IOException) emit(null) else throw error
            }
            .first()
            ?: return null
        return decodeModelConfig(runCatching { JSONObject(raw) }.getOrNull())
    }

    suspend fun updateHomeDefaultModel(model: ModelConfig?) {
        store.edit { values ->
            if (model == null) {
                values.remove(homeModelKey)
            } else {
                values[homeModelKey] = encodeModelConfig(model).toString()
            }
        }
    }

    suspend fun readHomeProfile(): HomeWorkspaceProfile {
        val raw = store.data
            .map { values -> values[homeProfileKey] }
            .catch { error ->
                if (error is IOException) emit(null) else throw error
            }
            .first()
            ?: return HomeWorkspaceProfile()
        return decodeHomeProfile(runCatching { JSONObject(raw) }.getOrNull())
    }

    suspend fun updateHomeProfile(profile: HomeWorkspaceProfile) {
        store.edit { values ->
            values[homeProfileKey] = encodeHomeProfile(profile).toString()
        }
    }

    suspend fun add(workspace: RuntimeWorkspace) {
        store.edit { values ->
            val current = decode(values[key]).toMutableList()
            current.removeAll { it.id == workspace.id }
            current.add(workspace)
            values[key] = encode(current)
        }
    }

    suspend fun rename(id: String, name: String) {
        store.edit { values ->
            val current = decode(values[key]).map {
                if (it.id == id) it.copy(name = name) else it
            }
            values[key] = encode(current)
        }
    }

    suspend fun remove(id: String) {
        store.edit { values ->
            values[key] = encode(decode(values[key]).filterNot { it.id == id })
        }
    }

    private fun decode(raw: String?): List<RuntimeWorkspace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").takeIf { it.isNotBlank() } ?: id
                    val cwd = item.optString("cwd")
                    if (cwd.isBlank()) continue
                    add(
                        RuntimeWorkspace(
                            id = id,
                            name = name,
                            cwd = cwd,
                            createdAt = item.optLong("createdAt", 0L),
                            avatar = item.optString("avatar").trim().takeIf { it.isNotEmpty() },
                            intro = item.optString("intro").trim().takeIf { it.isNotEmpty() },
                            defaultModel = decodeModelConfig(item.optJSONObject("defaultModel")),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(workspaces: List<RuntimeWorkspace>): String {
        val array = JSONArray()
        workspaces.forEach { workspace ->
            val item = JSONObject()
                .put("id", workspace.id)
                .put("name", workspace.name)
                .put("cwd", workspace.cwd)
                .put("createdAt", workspace.createdAt)
            workspace.avatar?.let { item.put("avatar", it) }
            workspace.intro?.let { item.put("intro", it) }
            workspace.defaultModel?.let { item.put("defaultModel", encodeModelConfig(it)) }
            array.put(item)
        }
        return array.toString()
    }

    private fun decodeModelConfig(obj: JSONObject?): ModelConfig? {
        if (obj == null) return null
        val providerId = obj.optString("providerId").trim()
        val modelId = obj.optString("modelId").trim()
        if (providerId.isEmpty() || modelId.isEmpty()) return null
        val rawThinking = obj.optString("thinkingLevel").trim()
        val thinking = ThinkingLevel.entries.firstOrNull { it.name.equals(rawThinking, ignoreCase = true) }
            ?: ThinkingLevel.OFF
        return ModelConfig(providerId = providerId, modelId = modelId, thinkingLevel = thinking)
    }

    private fun encodeModelConfig(config: ModelConfig): JSONObject = JSONObject()
        .put("providerId", config.providerId)
        .put("modelId", config.modelId)
        .put("thinkingLevel", config.thinkingLevel.name)

    private fun decodeHomeProfile(obj: JSONObject?): HomeWorkspaceProfile {
        if (obj == null) return HomeWorkspaceProfile()
        val name = obj.optString("name").trim().ifEmpty { AgentRuntimeHost.HOME_NAME }
        val avatar = obj.optString("avatar").trim().takeIf { it.isNotEmpty() }
        val intro = obj.optString("intro").trim().takeIf { it.isNotEmpty() }
        return HomeWorkspaceProfile(name = name, avatar = avatar, intro = intro)
    }

    private fun encodeHomeProfile(profile: HomeWorkspaceProfile): JSONObject {
        val obj = JSONObject().put("name", profile.name.trim().ifEmpty { AgentRuntimeHost.HOME_NAME })
        profile.avatar?.trim()?.takeIf { it.isNotEmpty() }?.let { obj.put("avatar", it) }
        profile.intro?.trim()?.takeIf { it.isNotEmpty() }?.let { obj.put("intro", it) }
        return obj
    }
}
