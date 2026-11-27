package io.github.whywood.superpi.host.market

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal object HostMarketParser {
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val SUPPORTED_SCHEMA = 2..3

    fun parse(bytes: ByteArray): HostMarketRegistry {
        if (bytes.size > HostMarketDefaults.MAX_REGISTRY_BYTES) error("Market registry too large")
        val root = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }
            .getOrElse { error("Invalid market registry format") }
        val schemaVersion = root.int("schemaVersion") ?: error("Market registry is missing schemaVersion")
        if (schemaVersion !in SUPPORTED_SCHEMA) error("Unsupported market registry version")
        val generatedAt = root.string("generatedAt").orEmpty()
        val keyId = root.string("keyId").orEmpty()
        val array = root["entries"] as? JsonArray ?: error("Market registry is missing entries")
        if (array.size > HostMarketDefaults.MAX_ENTRIES) error("Too many market entries")
        val seen = HashSet<String>(array.size)
        val entries = array.mapIndexed { index, element ->
            val obj = element as? JsonObject ?: error("Entry #$index has an invalid format")
            val entry = parseEntry(obj)
            if (!seen.add(entry.id)) error("Duplicate market entry: ${entry.id}")
            entry
        }
        return HostMarketRegistry(schemaVersion, generatedAt, keyId, entries)
    }

    private fun parseEntry(obj: JsonObject): HostMarketEntry {
        val type = when (obj.string("type")) {
            "plugin" -> HostMarketEntryType.Plugin
            "skill" -> HostMarketEntryType.Skill
            "mcp" -> HostMarketEntryType.Mcp
            else -> error("Unknown entry type")
        }
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: error("Entry is missing id")
        val category = obj.string("category")?.trim()?.takeIf { it.isNotEmpty() }
        val name = parseLocalized(obj, "name")
        val summary = parseLocalized(obj, "summary")
        val description = parseLocalized(obj, "description")
        val icon = obj.string("icon")
        val tags = parseTags(obj["tags"])
        val version = obj.string("version").orEmpty()
        val artifact = (obj["artifact"] as? JsonObject)?.let { parseArtifact(it, type, id) }
        val skill = (obj["skill"] as? JsonObject)?.let {
            HostMarketSkillMeta(license = it.string("license"), source = it.string("source"))
        }
        val mcp = (obj["mcp"] as? JsonObject)?.let {
            val url = it.string("url").orEmpty()
            if (url.isBlank()) error("MCP entry is missing url")
            val transport = it.string("transport") ?: "http"
            if (transport != "http") error("Unsupported MCP transport: $transport")
            HostMarketMcpMeta(
                url = url,
                transport = transport,
                headers = (it["headers"] as? JsonObject)?.mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                }.orEmpty(),
                authHint = (it["authHint"] as? JsonObject)?.let { hint -> parseLocalized(hint) }
                    ?: it.string("authHint")?.let { hint -> HostLocalizedText(mapOf("default" to hint)) },
                auth = (it["auth"] as? JsonObject)?.let { auth -> parseAuth(auth) },
            )
        }
        if (type == HostMarketEntryType.Plugin && artifact == null) error("Plugin entry is missing artifact")
        if (type == HostMarketEntryType.Skill && artifact == null) error("Skill entry is missing artifact")
        if (type == HostMarketEntryType.Mcp && mcp == null) error("MCP entry is missing configuration")
        return HostMarketEntry(
            type = type,
            id = id,
            category = category,
            name = name,
            summary = summary,
            description = description,
            icon = icon,
            tags = tags,
            version = version,
            artifact = artifact,
            skill = skill,
            mcp = mcp,
        )
    }

    private fun parseAuth(obj: JsonObject): HostMarketMcpAuth {
        val type = when (obj.string("type")) {
            "apiKey" -> HostMarketMcpAuthType.ApiKey
            "oauth" -> HostMarketMcpAuthType.OAuth
            "none", null -> HostMarketMcpAuthType.None
            else -> error("Unknown connector authorization type")
        }
        val header = obj.string("header")
        if (type == HostMarketMcpAuthType.ApiKey && header.isNullOrBlank()) {
            error("API key connector is missing header")
        }
        return HostMarketMcpAuth(
            type = type,
            header = header,
            prefix = obj.string("prefix"),
            placeholder = (obj["placeholder"] as? JsonObject)?.let { parseLocalized(it) },
            helpUrl = obj.string("helpUrl"),
        )
    }

    private fun parseTags(element: JsonElement?): Map<String, List<String>> = when (element) {
        is JsonArray -> {
            val plain = element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (plain.isEmpty()) emptyMap() else mapOf("default" to plain)
        }
        is JsonObject -> element.mapNotNull { (language, value) ->
            val array = value as? JsonArray ?: return@mapNotNull null
            val plain = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (plain.isEmpty()) null else language to plain
        }.toMap()
        else -> emptyMap()
    }

    private fun parseLocalized(obj: JsonObject, key: String): HostLocalizedText {
        val element = obj[key]
        if (element is JsonPrimitive) {
            return HostLocalizedText(mapOf("default" to element.contentOrNull.orEmpty()))
        }
        val objectValue = element as? JsonObject ?: return HostLocalizedText(emptyMap())
        return parseLocalized(objectValue)
    }

    private fun parseLocalized(obj: JsonObject): HostLocalizedText {
        val values = obj.mapNotNull { (language, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (text.isBlank()) null else language to text
        }.toMap()
        if (values.isEmpty()) error("Missing localized text")
        return HostLocalizedText(values)
    }

    private fun parseArtifact(obj: JsonObject, type: HostMarketEntryType, id: String): HostMarketArtifact {
        val url = obj.string("url").orEmpty()
        if (url.isBlank()) error("Entry $id is missing artifact url")
        val sha = obj.string("sha256").orEmpty()
        if (!SHA256.matches(sha)) error("Entry $id has an invalid sha256")
        val size = obj.long("size") ?: 0L
        if (size <= 0L) error("Entry $id is missing file size")
        val limit = if (type == HostMarketEntryType.Skill) {
            HostMarketDefaults.MAX_SKILL_BYTES
        } else {
            HostMarketDefaults.MAX_PLUGIN_BYTES
        }
        if (size > limit) error("Entry $id file is too large")
        return HostMarketArtifact(url, sha, size)
    }

    internal fun isVersionNewer(market: String, installed: String): Boolean {
        val cleanMarket = market.trim().removePrefix("v").removePrefix("V")
        val cleanInstalled = installed.trim().removePrefix("v").removePrefix("V")
        val mParts = parseSemverParts(cleanMarket)
        val iParts = parseSemverParts(cleanInstalled)
        if (mParts == null || iParts == null) return cleanMarket != cleanInstalled
        val count = maxOf(mParts.size, iParts.size)
        for (i in 0 until count) {
            val a = mParts.getOrElse(i) { 0 }
            val b = iParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseSemverParts(raw: String): List<Int>? {
        val core = raw.substringBefore('-').substringBefore('+').trim()
        if (core.isEmpty()) return null
        val segments = core.split('.')
        val parsed = ArrayList<Int>(segments.size)
        for (seg in segments) {
            val num = seg.toIntOrNull() ?: return null
            if (num < 0) return null
            parsed.add(num)
        }
        return parsed
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
