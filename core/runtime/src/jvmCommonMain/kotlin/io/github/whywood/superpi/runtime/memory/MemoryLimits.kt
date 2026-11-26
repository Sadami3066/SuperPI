package io.github.whywood.superpi.runtime.memory

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object MemoryLimits {
    const val MAX_ID_LENGTH = 128
    const val MAX_SLUG_LENGTH = 64
    const val MAX_TITLE_CHARS = 80
    const val MAX_PURPOSE_CHARS = 300
    const val MAX_SCHEMA_HINT_CHARS = 400
    const val MAX_BLOCK_CONTENT_CHARS = 4_000
    const val MAX_CONTENT_CHARS = 8_192
    const val MAX_SUMMARY_CHARS = 2_048
    const val MAX_QUOTE_CHARS = 1_024
    const val MAX_ENTITY_NAME_CHARS = 128
    const val MAX_TAG_CHARS = 64
    const val MAX_KEYWORD_CHARS = 64
    const val MAX_TAGS_PER_EVENT = 20
    const val MAX_KEYWORDS_PER_EVENT = 16
    const val MAX_ENTITIES_PER_EVENT = 20
    const val MAX_ALIASES_PER_ENTITY = 32
    const val SOFT_MAX_ACTIVE_BLOCKS = 15
    const val MAX_RENDERED_CONTEXT_CHARS = 16_000
    const val MAX_JSON_OBJECT_CHARS = 8_192
    const val MAX_SYNC_BLOCK_CONTENT_CHARS = 32_768
    const val MAX_SYNC_LIST_ITEMS = 64
    const val MAX_SYNC_SHORT_TOKEN_CHARS = 64
    const val MS_PER_DAY = 86_400_000L

    fun clampText(raw: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val trimmed = raw.trim()
        if (trimmed.length <= maxChars) return trimmed
        var end = maxChars
        if (end > 0 && trimmed[end - 1].isHighSurrogate()) {
            end--
        }
        return trimmed.substring(0, end).trimEnd()
    }

    fun clampOptionalText(raw: String?, maxChars: Int): String? =
        raw?.let { clampText(it, maxChars) }?.takeIf { it.isNotEmpty() }

    fun normalizeSlug(raw: String): String {
        val cleaned = raw.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff_-]+"), "_")
            .trim('_', '-')
        if (cleaned.isNotEmpty()) return cleaned.take(MAX_SLUG_LENGTH)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
        return "block_$digest"
    }
}

internal object MemoryJsonSupport {
    val json = Json { ignoreUnknownKeys = true }

    fun parseObject(raw: String): JsonObject? {
        val cleaned = extractJsonObjectText(raw) ?: return null
        return runCatching { json.parseToJsonElement(cleaned) as? JsonObject }.getOrNull()
    }

    fun encodeStringList(items: List<String>): String =
        JsonArray(items.map { JsonPrimitive(it) }).toString()

    fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        }.getOrDefault(emptyList())
    }

    fun encodeStringMap(
        map: Map<String, String>,
        maxChars: Int = MemoryLimits.MAX_JSON_OBJECT_CHARS,
    ): String {
        if (map.isEmpty() || maxChars < 2) return "{}"
        val kept = LinkedHashMap<String, JsonElement>()
        var current = "{}"
        for ((rawKey, rawValue) in map) {
            val cleanKey = rawKey.trim()
            if (cleanKey.isEmpty()) continue
            val candidate = JsonObject(kept + (cleanKey to JsonPrimitive(rawValue))).toString()
            if (candidate.length <= maxChars) {
                kept[cleanKey] = JsonPrimitive(rawValue)
                current = candidate
            }
        }
        return current
    }

    fun boundJsonObject(
        raw: String?,
        maxChars: Int = MemoryLimits.MAX_JSON_OBJECT_CHARS,
    ): String {
        if (raw.isNullOrBlank() || maxChars < 2) return "{}"
        val parsed = parseObject(raw) ?: return "{}"
        val compact = parsed.toString()
        if (compact.length <= maxChars) return compact
        val kept = LinkedHashMap<String, JsonElement>()
        var current = "{}"
        for ((key, value) in parsed.entries) {
            val cleanKey = key.trim()
            if (cleanKey.isEmpty()) continue
            val candidate = JsonObject(kept + (cleanKey to value)).toString()
            if (candidate.length <= maxChars) {
                kept[cleanKey] = value
                current = candidate
            }
        }
        return current
    }

    fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            obj.entries.mapNotNull { (k, v) ->
                val cleanKey = k.trim()
                val cleanVal = (v as? JsonPrimitive)?.contentOrNull?.trim()
                if (cleanKey.isNotEmpty() && !cleanVal.isNullOrEmpty()) cleanKey to cleanVal else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun extractJsonObjectText(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end < start) return null
        return trimmed.substring(start, end + 1)
    }
}

internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

internal fun JsonObject.optStr(key: String): String? =
    str(key).takeIf { it.isNotEmpty() }

internal fun JsonObject.intVal(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.longVal(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.boolVal(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.doubleVal(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .orEmpty()

internal fun JsonObject.stringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject ?: return emptyMap()
    return obj.entries.mapNotNull { (k, v) ->
        val cleanKey = k.trim()
        val cleanVal = (v as? JsonPrimitive)?.contentOrNull?.trim()
        if (cleanKey.isNotEmpty() && !cleanVal.isNullOrEmpty()) cleanKey to cleanVal else null
    }.toMap()
}
