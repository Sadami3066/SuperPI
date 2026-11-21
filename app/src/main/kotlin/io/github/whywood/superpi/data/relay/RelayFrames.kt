package io.github.whywood.superpi.data.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal val RELAY_CAPABILITIES = listOf(
    "remote-bot",
    "bot-sessions",
    "bot-images",
)

data class RelayBot(
    val id: String,
    val name: String,
    val avatar: String? = null,
)

data class RelayDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    val online: Boolean,
    val lastSeen: Long,
    val bots: List<RelayBot>,
    val capabilities: List<String> = emptyList(),
)

data class RouteImagePart(
    val data: String,
    val mimeType: String,
)

data class RemoteSessionSummaryDto(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int = 0,
)

data class RemoteHistoryDto(
    val sessionId: String,
    val title: String?,
    val busy: Boolean,
    val messages: List<JsonObject>,
    val seq: Long?,
)

sealed interface RelayServerFrame {
    data class HelloOk(
        val accountId: String,
        val devices: List<RelayDevice>,
        val memoryVersion: Long,
        val capabilities: List<String> = emptyList(),
    ) : RelayServerFrame

    data class Presence(
        val deviceId: String,
        val online: Boolean,
        val lastSeen: Long,
        val bots: List<RelayBot>?,
        val capabilities: List<String>? = null,
    ) : RelayServerFrame

    data class Route(
        val id: String,
        val from: String,
        val method: String,
        val params: JsonObject,
    ) : RelayServerFrame

    data class RouteOk(
        val id: String,
        val result: JsonObject?,
    ) : RelayServerFrame

    data class RouteError(
        val id: String,
        val code: String,
        val message: String,
    ) : RelayServerFrame

    data class RouteEvent(
        val id: String,
        val seq: Long,
        val event: JsonObject,
    ) : RelayServerFrame

    data class MemoryChanged(val version: Long) : RelayServerFrame

    data object Pong : RelayServerFrame

    data class Unknown(val type: String) : RelayServerFrame
}

internal object RelayFrames {
    private const val PROTOCOL_VERSION = 1
    private const val MAX_IMAGES = 4
    private const val MAX_IMAGE_BASE64_CHARS = 8_000_000
    private val ALLOWED_IMAGE_MIME_TYPES = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
    )

    fun hello(
        deviceId: String,
        platform: String,
        appVersion: String,
        bots: List<RelayBot>,
        capabilities: List<String> = RELAY_CAPABILITIES,
    ): JsonObject = buildJsonObject {
        put("type", "hello")
        put("protocolVersion", PROTOCOL_VERSION)
        put("deviceId", deviceId)
        put("platform", platform)
        put("appVersion", appVersion)
        put("bots", botsArray(bots))
        put("capabilities", stringsArray(capabilities))
    }

    fun route(id: String, to: String, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("type", "route")
        put("id", id)
        put("to", to)
        put("method", method)
        put("params", params)
    }

    fun routeOk(id: String, result: JsonObject): JsonObject = buildJsonObject {
        put("type", "route_ok")
        put("id", id)
        put("result", result)
    }

    fun routeError(id: String, code: String, message: String): JsonObject = buildJsonObject {
        put("type", "route_error")
        put("id", id)
        put("code", code)
        put("message", message)
    }

    fun routeEvent(id: String, seq: Long, event: JsonObject): JsonObject = buildJsonObject {
        put("type", "route_event")
        put("id", id)
        put("seq", seq)
        put("event", event)
    }

    fun ping(): JsonObject = buildJsonObject { put("type", "ping") }

    fun parse(raw: String): RelayServerFrame? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (json.string("type")) {
            "hello_ok" -> RelayServerFrame.HelloOk(
                accountId = json.string("accountId").orEmpty(),
                devices = json["devices"].let(::parseDevices),
                memoryVersion = json["memoryVersion"]?.jsonPrimitive?.longOrNull ?: 0L,
                capabilities = parseStrings(json["capabilities"]),
            )

            "presence" -> RelayServerFrame.Presence(
                deviceId = json.string("deviceId") ?: return null,
                online = json["online"]?.jsonPrimitive?.booleanOrNull ?: false,
                lastSeen = json["lastSeen"]?.jsonPrimitive?.longOrNull ?: 0L,
                bots = json["bots"]?.let(::parseBots),
                capabilities = json["capabilities"]?.let(::parseStrings),
            )

            "route" -> RelayServerFrame.Route(
                id = json.string("id") ?: return null,
                from = json.string("from").orEmpty(),
                method = json.string("method") ?: return null,
                params = json["params"] as? JsonObject ?: JsonObject(emptyMap()),
            )

            "route_ok" -> RelayServerFrame.RouteOk(
                id = json.string("id") ?: return null,
                result = json["result"] as? JsonObject,
            )

            "route_error" -> RelayServerFrame.RouteError(
                id = json.string("id") ?: return null,
                code = json.string("code").orEmpty(),
                message = json.string("message").orEmpty(),
            )

            "route_event" -> RelayServerFrame.RouteEvent(
                id = json.string("id") ?: return null,
                seq = json["seq"]?.jsonPrimitive?.longOrNull ?: 0L,
                event = json["event"] as? JsonObject ?: return null,
            )

            "memory_changed" -> RelayServerFrame.MemoryChanged(
                version = json["version"]?.jsonPrimitive?.longOrNull ?: 0L,
            )

            "pong" -> RelayServerFrame.Pong
            else -> RelayServerFrame.Unknown(json.string("type").orEmpty())
        }
    }

    private fun parseDevices(element: JsonElement?): List<RelayDevice> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            val json = item as? JsonObject ?: return@mapNotNull null
            val deviceId = json.string("deviceId") ?: return@mapNotNull null
            RelayDevice(
                deviceId = deviceId,
                name = json.string("name") ?: deviceId,
                platform = json.string("platform").orEmpty(),
                online = json["online"]?.jsonPrimitive?.booleanOrNull ?: false,
                lastSeen = json["lastSeen"]?.jsonPrimitive?.longOrNull ?: 0L,
                bots = json["bots"]?.let(::parseBots).orEmpty(),
                capabilities = parseStrings(json["capabilities"]),
            )
        }
    }

    private fun parseStrings(element: JsonElement?): List<String> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun stringsArray(values: List<String>): JsonArray = JsonArray(
        values.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) },
    )

    fun parseRemoteSessions(result: JsonObject?): List<RemoteSessionSummaryDto> {
        val array = result?.get("sessions") as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val json = item as? JsonObject ?: return@mapNotNull null
            val id = json.string("id") ?: return@mapNotNull null
            RemoteSessionSummaryDto(
                id = id,
                title = json.string("title") ?: "Untitled",
                updatedAt = json["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                messageCount = json["messageCount"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    fun parseRemoteHistory(result: JsonObject?): RemoteHistoryDto? {
        val json = result ?: return null
        val sessionId = json.string("sessionId") ?: return null
        val messages = (json["messages"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        return RemoteHistoryDto(
            sessionId = sessionId,
            title = json.string("title"),
            busy = json["busy"]?.jsonPrimitive?.booleanOrNull ?: false,
            messages = messages,
            seq = json["seq"]?.jsonPrimitive?.longOrNull,
        )
    }

    fun parseImages(params: JsonObject): List<RouteImagePart> {
        val array = params["images"] as? JsonArray ?: return emptyList()
        val out = ArrayList<RouteImagePart>(minOf(array.size, MAX_IMAGES))
        for (item in array) {
            if (out.size >= MAX_IMAGES) break
            val json = item as? JsonObject ?: continue
            val data = json.string("data") ?: continue
            val mimeType = json.string("mimeType")?.lowercase() ?: continue
            if (mimeType !in ALLOWED_IMAGE_MIME_TYPES) continue
            if (data.length > MAX_IMAGE_BASE64_CHARS) continue
            out += RouteImagePart(data = data, mimeType = mimeType)
        }
        return out
    }

    fun imagesArray(images: List<RouteImagePart>): JsonArray = JsonArray(
        images.take(MAX_IMAGES).mapNotNull { image ->
            val data = image.data.trim()
            val mimeType = image.mimeType.trim().lowercase()
            if (data.isEmpty() || mimeType !in ALLOWED_IMAGE_MIME_TYPES || data.length > MAX_IMAGE_BASE64_CHARS) {
                return@mapNotNull null
            }
            buildJsonObject {
                put("data", data)
                put("mimeType", mimeType)
            }
        },
    )

    private fun parseBots(element: JsonElement?): List<RelayBot> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            val json = item as? JsonObject ?: return@mapNotNull null
            val id = json.string("id") ?: return@mapNotNull null
            RelayBot(
                id = id,
                name = json.string("name") ?: id,
                avatar = json.string("avatar")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun botsArray(bots: List<RelayBot>): JsonArray = JsonArray(
        bots.map { bot ->
            buildJsonObject {
                put("id", bot.id)
                put("name", bot.name)
                bot.avatar?.takeIf { it.isNotBlank() }?.let { put("avatar", it) }
            }
        },
    )

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    fun intOrNull(element: JsonElement?): Int? =
        element?.jsonPrimitive?.intOrNull

    fun encodeDevices(devices: List<RelayDevice>): String = relayDevicesArray(devices).toString()

    fun parseDeviceList(raw: String): List<RelayDevice>? {
        val element = runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull() ?: return null
        if (element !is JsonArray) return null
        return parseDevices(element)
    }

    private fun relayDevicesArray(devices: List<RelayDevice>): JsonArray = JsonArray(
        devices.map { device ->
            buildJsonObject {
                put("deviceId", device.deviceId)
                put("name", device.name)
                put("platform", device.platform)
                put("online", device.online)
                put("lastSeen", device.lastSeen)
                put("bots", botsArray(device.bots))
                if (device.capabilities.isNotEmpty()) {
                    put("capabilities", stringsArray(device.capabilities))
                }
            }
        },
    )
}
