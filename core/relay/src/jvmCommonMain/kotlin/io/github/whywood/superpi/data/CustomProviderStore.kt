package io.github.whywood.superpi.data

import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.sdk.ai.ModelInputModality
import io.github.whywood.superpi.sdk.coding.models.CustomModelConfig
import io.github.whywood.superpi.sdk.coding.models.CustomProviderConfig
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class CustomProviderProtocol(val value: String) {
    OpenAiCompletions("openai-completions"),
    OpenAiResponses("openai-responses"),
    AnthropicMessages("anthropic-messages"),
    ;

    companion object {
        fun fromValue(value: String): CustomProviderProtocol? = entries.firstOrNull { it.value == value }
    }
}

data class CustomProviderModel(
    val id: String,
    val name: String,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val supportsImages: Boolean = false,
    val reasoning: Boolean = false,
    val thinkingLevels: Set<ThinkingLevel> = emptySet(),
)

data class CustomProviderEntry(
    val id: String,
    val name: String,
    val api: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<CustomProviderModel>,
)

private const val SDK_MINIMAL_LEVEL = "minimal"

private val SDK_DEFAULT_THINKING_LEVELS = setOf(
    ThinkingLevel.OFF,
    ThinkingLevel.LOW,
    ThinkingLevel.MEDIUM,
    ThinkingLevel.HIGH,
)

fun customThinkingLevelMap(levels: Set<ThinkingLevel>): Map<String, String?> = buildMap<String, String?> {
    if (ThinkingLevel.OFF !in levels) put(ThinkingLevel.OFF.name.lowercase(), null)
    put(SDK_MINIMAL_LEVEL, null)
    ThinkingLevel.entries.forEach { level ->
        if (level == ThinkingLevel.OFF) return@forEach
        val name = level.name.lowercase()
        put(name, if (level in levels) name else null)
    }
}

class CustomProviderStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow(readFromDisk())

    val entries: StateFlow<List<CustomProviderEntry>> = state.asStateFlow()

    suspend fun reload(): List<CustomProviderEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val reloaded = readFromDisk()
            state.value = reloaded
            reloaded
        }
    }

    suspend fun save(entries: List<CustomProviderEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = entries.mapNotNull(::normalize)
            writeToDisk(next)
            state.value = next
        }
    }

    suspend fun saveEntry(entry: CustomProviderEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val normalized = normalize(entry) ?: return@withLock
            val current = readFromDisk()
            val index = current.indexOfFirst { it.id == normalized.id }
            val next = if (index >= 0) {
                current.toMutableList().also { it[index] = normalized }
            } else {
                current + normalized
            }
            writeToDisk(next)
            state.value = next
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = readFromDisk().filterNot { it.id == id }
            writeToDisk(next)
            state.value = next
        }
    }

    private fun readFromDisk(): List<CustomProviderEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val providers = root["providers"] as? JsonObject ?: return@runCatching emptyList()
            providers.entries
                .mapNotNull { (id, element) -> (element as? JsonObject)?.let { entry(id, it) } }
                .mapNotNull(::normalize)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun entry(id: String, item: JsonObject): CustomProviderEntry {
        val modelsElement = item["models"] as? JsonArray ?: JsonArray(emptyList())
        val models = buildList {
            for (element in modelsElement) {
                val modelObj = element as? JsonObject ?: continue
                val modelId = modelObj.text("id")?.trim().orEmpty()
                if (modelId.isEmpty()) continue
                add(model(modelId, modelObj))
            }
        }
        return CustomProviderEntry(
            id = id.trim(),
            name = item.text("name")?.trim().orEmpty(),
            api = item.text("api")?.trim().orEmpty(),
            baseUrl = item.text("baseUrl")?.trim().orEmpty(),
            apiKey = item.text("apiKey")?.trim().orEmpty(),
            models = models,
        )
    }

    private fun model(id: String, item: JsonObject): CustomProviderModel {
        val input = item["input"] as? JsonArray
        val supportsImages =
            input != null && input.any { (it as? JsonPrimitive)?.contentOrNull == "image" }
        val reasoning = (item["reasoning"] as? JsonPrimitive)?.booleanOrNull ?: false
        val thinkingLevels = if (reasoning) {
            thinkingLevelsFromMap(item["thinkingLevelMap"] as? JsonObject)
        } else {
            emptySet()
        }
        return CustomProviderModel(
            id = id,
            name = item.text("name")?.trim()?.ifBlank { id } ?: id,
            contextWindow = item.positiveLong("contextWindow"),
            maxTokens = item.positiveLong("maxTokens"),
            supportsImages = supportsImages,
            reasoning = reasoning && thinkingLevels.isNotEmpty(),
            thinkingLevels = thinkingLevels,
        )
    }

    private fun thinkingLevelsFromMap(levels: JsonObject?): Set<ThinkingLevel> {
        if (levels == null) return SDK_DEFAULT_THINKING_LEVELS
        return ThinkingLevel.entries.filterTo(LinkedHashSet()) { level ->
            val name = level.name.lowercase()
            if (name in levels) levels[name] !is JsonNull else level in SDK_DEFAULT_THINKING_LEVELS
        }
    }

    private fun JsonObject.positiveLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun writeToDisk(entries: List<CustomProviderEntry>) {
        val root = buildJsonObject {
            put(
                "providers",
                buildJsonObject {
                    entries.forEach { entry ->
                        put(
                            entry.id,
                            buildJsonObject {
                                put("name", entry.name)
                                put("api", entry.api)
                                put("baseUrl", entry.baseUrl)
                                put("apiKey", entry.apiKey)
                                put(
                                    "models",
                                    buildJsonArray {
                                        entry.models.forEach { model ->
                                            add(
                                                buildJsonObject {
                                                    put("id", model.id)
                                                    put("name", model.name)
                                                    model.contextWindow?.let { put("contextWindow", it) }
                                                    model.maxTokens?.let { put("maxTokens", it) }
                                                    put(
                                                        "input",
                                                        buildJsonArray {
                                                            add(JsonPrimitive("text"))
                                                            if (model.supportsImages) add(JsonPrimitive("image"))
                                                        },
                                                    )
                                                    put("reasoning", model.reasoning)
                                                    if (model.reasoning) {
                                                        put(
                                                            "thinkingLevelMap",
                                                            buildJsonObject {
                                                                customThinkingLevelMap(model.thinkingLevels)
                                                                    .forEach { (level, value) ->
                                                                        if (value == null) put(level, JsonNull) else put(level, value)
                                                                    }
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        val text = json.encodeToString(JsonObject.serializer(), root)
        val parent = file.parentFile ?: File(".")
        parent.mkdirs()
        val tempPath = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp")
        val temp = tempPath.toFile()
        try {
            temp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(
                    tempPath,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }

    private fun normalize(entry: CustomProviderEntry): CustomProviderEntry? {
        val id = entry.id.trim()
        val name = entry.name.trim()
        val api = entry.api.trim()
        val baseUrl = entry.baseUrl.trim()
        val apiKey = entry.apiKey.trim()
        if (!id.matches(PROVIDER_ID_REGEX)) return null
        if (name.isEmpty() || api.isEmpty() || baseUrl.isEmpty() || apiKey.isEmpty()) return null
        if (CustomProviderProtocol.fromValue(api) == null) return null
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) return null
        val models = entry.models.mapNotNull { model ->
            val modelId = model.id.trim()
            if (modelId.isEmpty()) {
                null
            } else {
                val reasoning = model.reasoning && model.thinkingLevels.isNotEmpty()
                CustomProviderModel(
                    id = modelId,
                    name = model.name.trim().ifBlank { modelId },
                    contextWindow = model.contextWindow?.takeIf { it > 0 },
                    maxTokens = model.maxTokens?.takeIf { it > 0 },
                    supportsImages = model.supportsImages,
                    reasoning = reasoning,
                    thinkingLevels = if (reasoning) model.thinkingLevels else emptySet(),
                )
            }
        }
        if (models.isEmpty()) return null
        return CustomProviderEntry(id, name, api, baseUrl, apiKey, models)
    }

    companion object {
        private val PROVIDER_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        fun newId(): String = "custom-" + UUID.randomUUID().toString().replace("-", "").take(8)
    }
}

fun CustomProviderEntry.toProviderConfig(): CustomProviderConfig = CustomProviderConfig(
    id = id,
    name = name,
    baseUrl = baseUrl,
    api = api,
    apiKey = apiKey,
    models = models.map { model ->
        CustomModelConfig(
            id = model.id,
            name = model.name,
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
            reasoning = model.reasoning.takeIf { it },
            input = if (model.supportsImages) {
                listOf(ModelInputModality.Text, ModelInputModality.Image)
            } else {
                listOf(ModelInputModality.Text)
            },
            thinkingLevelMap = if (model.reasoning) customThinkingLevelMap(model.thinkingLevels) else null,
        )
    },
)
