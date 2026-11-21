package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.UserQuestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
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
    "bot-approval",
    "bot-live-sync",
    "bot-manage",
    "session-manage",
    "workspace-files",
    "bot-controls",
    "session-controls",
    "sync-events",
    "bot-capabilities",
    "present-artifacts",
)

internal val RELAY_CAPABILITIES_READONLY = listOf(
    "remote-bot",
    "bot-sessions",
    "bot-images",
    "bot-approval",
    "bot-live-sync",
    "sync-events",
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
    val busy: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

data class RemoteFileEntryDto(
    val name: String,
    val path: String,
    val type: String,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String? = null,
)

data class RelaySyncEvent(
    val deviceId: String = "",
    val kind: String,
    val botId: String,
    val sessionId: String? = null,
    val busy: Boolean? = null,
    val pendingApprovals: Int? = null,
    val provider: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String? = null,
    val ts: Long = System.currentTimeMillis(),
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

    data class SyncEvent(
        val deviceId: String,
        val kind: String,
        val botId: String,
        val sessionId: String? = null,
        val busy: Boolean? = null,
        val pendingApprovals: Int? = null,
        val provider: String? = null,
        val modelId: String? = null,
        val thinkingLevel: String? = null,
        val ts: Long = 0L,
    ) : RelayServerFrame {
        fun toEvent(): RelaySyncEvent = RelaySyncEvent(
            deviceId = deviceId,
            kind = kind,
            botId = botId,
            sessionId = sessionId,
            busy = busy,
            pendingApprovals = pendingApprovals,
            provider = provider,
            modelId = modelId,
            thinkingLevel = thinkingLevel,
            ts = ts,
        )
    }

    data object Pong : RelayServerFrame

    data class Unknown(val type: String) : RelayServerFrame
}

internal object RelayFrames {
    private const val PROTOCOL_VERSION = 1
    private const val MAX_IMAGES = 4
    private const val MAX_IMAGE_BASE64_CHARS = 8_000_000
    const val MAX_RELATIVE_PATH_LENGTH = 512
    const val MAX_FILE_CHUNK_BYTES = 256 * 1024
    const val MAX_FILE_CHUNK_BASE64_CHARS = 360_000
    const val MAX_FILE_TRANSFER_BYTES = 50L * 1024L * 1024L
    const val MAX_AVATAR_DATA_URL_LENGTH = 65_536
    val SYNC_EVENT_KINDS = setOf("sessions_changed", "bot_activity", "approval_changed", "model_changed", "capabilities_changed")
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

    fun syncEvent(
        kind: String,
        botId: String,
        sessionId: String? = null,
        busy: Boolean? = null,
        pendingApprovals: Int? = null,
        provider: String? = null,
        modelId: String? = null,
        thinkingLevel: String? = null,
        ts: Long = System.currentTimeMillis(),
    ): JsonObject = buildJsonObject {
        put("type", "sync_event")
        put("kind", kind)
        put("botId", botId)
        sessionId?.takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
        busy?.let { put("busy", it) }
        pendingApprovals?.let { put("pendingApprovals", it) }
        provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
        modelId?.takeIf { it.isNotBlank() }?.let { put("modelId", it) }
        thinkingLevel?.takeIf { it.isNotBlank() }?.let { put("thinkingLevel", it) }
        put("ts", ts)
    }

    fun validateRelativeWorkspacePath(raw: String, allowEmpty: Boolean = false): String {
        if (raw.isEmpty()) {
            if (allowEmpty) return ""
            throw IllegalArgumentException("path must not be empty")
        }
        if (raw.length > MAX_RELATIVE_PATH_LENGTH) {
            throw IllegalArgumentException("path exceeds maximum length")
        }
        if ('\u0000' in raw) {
            throw IllegalArgumentException("path contains null byte")
        }
        if ('\\' in raw) {
            throw IllegalArgumentException("path must use forward slashes")
        }
        if (raw.startsWith('/') || raw.endsWith('/')) {
            throw IllegalArgumentException("path must be relative without leading or trailing slash")
        }
        for (segment in raw.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw IllegalArgumentException("path contains invalid segment")
            }
        }
        return raw
    }

    fun detectImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return "image/png"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

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

            "sync_event" -> {
                val deviceId = json.string("deviceId") ?: return null
                val kind = json.string("kind")?.takeIf { it in SYNC_EVENT_KINDS } ?: return null
                val botId = json.string("botId") ?: return null
                RelayServerFrame.SyncEvent(
                    deviceId = deviceId,
                    kind = kind,
                    botId = botId,
                    sessionId = json.string("sessionId"),
                    busy = json["busy"]?.jsonPrimitive?.booleanOrNull,
                    pendingApprovals = json["pendingApprovals"]?.jsonPrimitive?.intOrNull,
                    provider = json.string("provider"),
                    modelId = json.string("modelId"),
                    thinkingLevel = json.string("thinkingLevel"),
                    ts = json["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }

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
                busy = json["busy"]?.jsonPrimitive?.booleanOrNull ?: false,
                pinned = json["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                archived = json["archived"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    fun parseRemoteBot(element: JsonElement?): RelayBot? {
        val json = element as? JsonObject ?: return null
        val id = json.string("id") ?: return null
        return RelayBot(
            id = id,
            name = json.string("name") ?: id,
            avatar = json.string("avatar")?.takeIf { it.isNotBlank() },
        )
    }

    fun parseRemoteFileEntry(element: JsonElement?): RemoteFileEntryDto? {
        val json = element as? JsonObject ?: return null
        val name = json.string("name") ?: return null
        val path = json.string("path") ?: return null
        val type = json.string("type")?.takeIf { it == "file" || it == "directory" } ?: return null
        return RemoteFileEntryDto(
            name = name,
            path = path,
            type = type,
            size = json["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            modifiedAt = json["modifiedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            mimeType = json.string("mimeType"),
        )
    }

    fun parseRemoteFileList(result: JsonObject?): Pair<String, List<RemoteFileEntryDto>>? {
        val json = result ?: return null
        val array = json["entries"] as? JsonArray ?: return null
        val path = (json["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val entries = array.mapNotNull(::parseRemoteFileEntry)
        return path to entries
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

    fun parseQuestions(element: JsonElement?): List<UserQuestion> {
        val array = element as? JsonArray ?: return emptyList()
        val out = mutableListOf<UserQuestion>()
        val usedIds = HashSet<String>()
        array.take(16).forEachIndexed { index, rawItem ->
            val json = rawItem as? JsonObject ?: return@forEachIndexed
            val prompt = json.string("question")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
            var id = json.string("id")?.trim()?.takeIf { it.isNotEmpty() } ?: "q${index + 1}"
            while (!usedIds.add(id)) id = "${id}_${index + 1}"
            val header = json.string("header")?.trim()?.takeIf { it.isNotEmpty() }
            val multiSelect = (json["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false
            val options = (json["options"] as? JsonArray)?.take(16)?.mapNotNull { optRaw ->
                val optJson = optRaw as? JsonObject ?: return@mapNotNull null
                val label = optJson.string("label")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val desc = optJson.string("description")?.trim()?.takeIf { it.isNotEmpty() }
                QuestionOption(label = label, description = desc)
            }.orEmpty()
            out += UserQuestion(
                id = id,
                header = header,
                question = prompt,
                options = options,
                multiSelect = multiSelect,
            )
        }
        return out
    }

    fun questionsArray(questions: List<UserQuestion>): JsonArray = buildJsonArray {
        for (q in questions) {
            addJsonObject {
                put("id", q.id)
                q.header?.takeIf { it.isNotBlank() }?.let { put("header", it) }
                put("question", q.question)
                if (q.multiSelect) put("multiSelect", true)
                if (q.options.isNotEmpty()) {
                    put(
                        "options",
                        buildJsonArray {
                            for (opt in q.options) {
                                addJsonObject {
                                    put("label", opt.label)
                                    opt.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    fun questionAnswersArray(answers: List<QuestionAnswer>): JsonArray = buildJsonArray {
        for (answer in answers) {
            addJsonObject {
                put("id", answer.questionId)
                if (answer.selected.isNotEmpty()) put("selected", stringsArray(answer.selected))
                answer.custom?.takeIf { it.isNotBlank() }?.let { put("custom", it) }
                if (answer.skipped) put("skipped", true)
            }
        }
    }

    fun parseQuestionAnswers(element: JsonElement?): List<QuestionAnswer> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { rawItem ->
            val json = rawItem as? JsonObject ?: return@mapNotNull null
            val id = json.string("id") ?: json.string("questionId") ?: return@mapNotNull null
            QuestionAnswer(
                questionId = id,
                selected = parseStrings(json["selected"]),
                custom = json.string("custom"),
                skipped = json["skipped"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

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
