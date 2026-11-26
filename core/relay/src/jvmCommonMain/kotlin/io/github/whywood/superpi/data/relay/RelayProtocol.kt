package io.github.whywood.superpi.data.relay

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal object RelayProtocol {
    fun enrollRequest(
        deviceName: String,
        platform: String,
        appVersion: String,
        pairCode: String?,
        loginSecret: String?,
    ): String = buildJsonObject {
        put("deviceName", deviceName)
        put("platform", platform)
        put("appVersion", appVersion)
        if (!pairCode.isNullOrBlank()) put("pairCode", pairCode)
        if (!loginSecret.isNullOrBlank()) put("loginSecret", loginSecret)
    }.toString()

    fun renameDeviceRequest(name: String): String = buildJsonObject {
        put("name", name)
    }.toString()

    fun memoryPushUrl(origin: String): String =
        origin.trimEnd('/') + "/v3/memory/push"

    fun memoryPullUrl(origin: String, since: Long, limit: Int): String =
        origin.trimEnd('/') + "/v3/memory/pull?since=${since.coerceAtLeast(0L)}&limit=${limit.coerceAtLeast(1)}"

    fun memoryConsolidationLeaseUrl(origin: String, leaseId: String? = null): String {
        val base = origin.trimEnd('/') + "/v3/memory/consolidation-lease"
        val cleanLease = leaseId?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        return "$base?leaseId=${URLEncoder.encode(cleanLease, "UTF-8")}"
    }

    fun parseEnrollment(body: String): RelayEnrollment? {
        val json = parseObject(body) ?: return null
        val deviceId = json.string("deviceId") ?: return null
        val token = json.string("token") ?: return null
        val accountId = json.string("accountId") ?: return null
        return RelayEnrollment(
            deviceId = deviceId,
            token = token,
            accountId = accountId,
            entries = parseEntries(json["entries"]),
            fingerprint = RelayPairingLink.normalizeFingerprint(json.string("fingerprint")),
        )
    }

    fun parsePairCode(body: String): RelayPairCode? {
        val json = parseObject(body) ?: return null
        val code = json.string("code") ?: return null
        val expiresAt = json["expiresAt"]?.jsonPrimitive?.longOrNull ?: return null
        return RelayPairCode(
            code = code,
            expiresAt = expiresAt,
            entries = parseEntries(json["entries"]),
            fingerprint = RelayPairingLink.normalizeFingerprint(json.string("fingerprint")),
        )
    }

    fun parseErrorCode(body: String): String? {
        val json = parseObject(body) ?: return null
        return json.string("code")
    }

    fun parseErrorMessage(body: String): String? {
        val json = parseObject(body) ?: return null
        return json.string("message") ?: json.string("code")
    }

    private fun parseEntries(element: JsonElement?): List<RelayEntry> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { entry ->
            if (entry !is JsonObject) return@mapNotNull null
            val origin = entry.string("origin") ?: return@mapNotNull null
            val kind = when (entry.string("kind")) {
                "lan" -> RelayEntryKind.Lan
                "public" -> RelayEntryKind.Public
                else -> null
            } ?: return@mapNotNull null
            RelayEntry(kind = kind, origin = origin)
        }
    }

    private fun parseObject(body: String): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
}
