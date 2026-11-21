package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteCallCodes
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RemoteBotSummary(
    val id: String,
    val name: String,
    val avatar: String? = null,
)

data class RemoteSessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val busy: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

data class RemoteSessionMetaResult(
    val sessionId: String,
    val pinned: Boolean,
    val archived: Boolean,
)

sealed interface RemoteAdminResult<out T> {
    data class Ok<T>(val value: T) : RemoteAdminResult<T>
    data class Error(val code: String, val message: String) : RemoteAdminResult<Nothing>
}

class RemoteBotAdminClient(
    private val transport: RemoteAgentTransport,
) {
    suspend fun createBot(
        deviceId: String,
        name: String,
        avatarDataUrl: String? = null,
        intro: String? = null,
    ): RemoteAdminResult<RemoteBotSummary> {
        checkReady(deviceId, CAP_BOT_MANAGE)?.let { return it }
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return RemoteAdminResult.Error("bad_request", "name is required")
        }
        val params = buildJsonObject {
            put("name", cleanName)
            avatarDataUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { put("avatar", it) }
            intro?.trim()?.takeIf { it.isNotEmpty() }?.let { put("intro", it) }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.create", params)) {
            is RemoteCallOutcome.Ok -> {
                val bot = parseBot(outcome.result?.get("bot") as? JsonObject)
                    ?: return RemoteAdminResult.Error("bad_response", "invalid bot response")
                RemoteAdminResult.Ok(bot)
            }
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun updateBot(
        deviceId: String,
        botId: String,
        name: String? = null,
        avatarDataUrl: String? = null,
        clearAvatar: Boolean = false,
        intro: String? = null,
        clearIntro: Boolean = false,
    ): RemoteAdminResult<RemoteBotSummary> {
        checkReady(deviceId, CAP_BOT_MANAGE)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            name?.trim()?.let { put("name", it) }
            if (clearAvatar) {
                put("avatar", JsonNull)
            } else {
                avatarDataUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { put("avatar", it) }
            }
            if (clearIntro) {
                put("intro", JsonNull)
            } else {
                intro?.trim()?.let { put("intro", it) }
            }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.update", params)) {
            is RemoteCallOutcome.Ok -> {
                val bot = parseBot(outcome.result?.get("bot") as? JsonObject)
                    ?: return RemoteAdminResult.Error("bad_response", "invalid bot response")
                RemoteAdminResult.Ok(bot)
            }
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun deleteBot(deviceId: String, botId: String): RemoteAdminResult<Boolean> {
        if (botId == "home") {
            return RemoteAdminResult.Error("permission_denied", "default bot cannot be deleted")
        }
        checkReady(deviceId, CAP_BOT_MANAGE)?.let { return it }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.delete", buildJsonObject { put("botId", botId) })) {
            is RemoteCallOutcome.Ok -> RemoteAdminResult.Ok(true)
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun listSessions(
        deviceId: String,
        botId: String,
        includeArchived: Boolean = false,
    ): RemoteAdminResult<List<RemoteSessionItem>> {
        if (deviceId !in transport.onlineDevices.value) {
            return RemoteAdminResult.Error(RemoteCallCodes.NOT_CONNECTED, "device is offline")
        }
        val params = buildJsonObject {
            put("botId", botId)
            if (includeArchived) put("includeArchived", true)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.sessions", params)) {
            is RemoteCallOutcome.Ok -> RemoteAdminResult.Ok(parseSessions(outcome.result))
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun createSession(
        deviceId: String,
        botId: String,
        title: String? = null,
    ): RemoteAdminResult<RemoteSessionItem> {
        checkReady(deviceId, CAP_SESSION_MANAGE)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "session.create", params)) {
            is RemoteCallOutcome.Ok -> {
                val item = parseSession(outcome.result?.get("session") as? JsonObject)
                    ?: return RemoteAdminResult.Error("bad_response", "invalid session response")
                RemoteAdminResult.Ok(item)
            }
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun renameSession(
        deviceId: String,
        botId: String,
        sessionId: String,
        title: String,
    ): RemoteAdminResult<String> {
        checkReady(deviceId, CAP_SESSION_MANAGE)?.let { return it }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            return RemoteAdminResult.Error("bad_request", "title is required")
        }
        val params = buildJsonObject {
            put("botId", botId)
            put("sessionId", sessionId)
            put("title", cleanTitle)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "session.rename", params)) {
            is RemoteCallOutcome.Ok -> {
                val returned = outcome.result.stringOrNull("title") ?: cleanTitle
                RemoteAdminResult.Ok(returned)
            }
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun deleteSession(
        deviceId: String,
        botId: String,
        sessionId: String,
    ): RemoteAdminResult<Boolean> {
        checkReady(deviceId, CAP_SESSION_MANAGE)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("sessionId", sessionId)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "session.delete", params)) {
            is RemoteCallOutcome.Ok -> RemoteAdminResult.Ok(true)
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun updateSessionMeta(
        deviceId: String,
        botId: String,
        sessionId: String,
        pinned: Boolean? = null,
        archived: Boolean? = null,
    ): RemoteAdminResult<RemoteSessionMetaResult> {
        checkReady(deviceId, CAP_SESSION_MANAGE)?.let { return it }
        if (pinned == null && archived == null) {
            return RemoteAdminResult.Error("bad_request", "pinned or archived is required")
        }
        val params = buildJsonObject {
            put("botId", botId)
            put("sessionId", sessionId)
            pinned?.let { put("pinned", it) }
            archived?.let { put("archived", it) }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "session.update_meta", params)) {
            is RemoteCallOutcome.Ok -> RemoteAdminResult.Ok(
                RemoteSessionMetaResult(
                    sessionId = outcome.result.stringOrNull("sessionId") ?: sessionId,
                    pinned = outcome.result?.get("pinned")?.jsonPrimitive?.booleanOrNull ?: (pinned == true),
                    archived = outcome.result?.get("archived")?.jsonPrimitive?.booleanOrNull ?: (archived == true),
                ),
            )
            is RemoteCallOutcome.Failed -> RemoteAdminResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    private fun checkReady(deviceId: String, capability: String): RemoteAdminResult.Error? {
        if (deviceId !in transport.onlineDevices.value) {
            return RemoteAdminResult.Error(RemoteCallCodes.NOT_CONNECTED, "device is offline")
        }
        if (!transport.supportsPeerCapability(deviceId, capability)) {
            return RemoteAdminResult.Error(CAPABILITY_UNAVAILABLE, "peer upgrade required")
        }
        return null
    }

    private fun parseBot(json: JsonObject?): RemoteBotSummary? {
        val obj = json ?: return null
        val id = obj.stringOrNull("id") ?: return null
        return RemoteBotSummary(
            id = id,
            name = obj.stringOrNull("name") ?: id,
            avatar = obj.stringOrNull("avatar"),
        )
    }

    private fun parseSessions(result: JsonObject?): List<RemoteSessionItem> {
        val array = result?.get("sessions") as? JsonArray ?: return emptyList()
        return array.mapNotNull { parseSession(it as? JsonObject) }
    }

    private fun parseSession(json: JsonObject?): RemoteSessionItem? {
        val obj = json ?: return null
        val id = obj.stringOrNull("id") ?: return null
        return RemoteSessionItem(
            id = id,
            title = obj.stringOrNull("title") ?: "Untitled",
            updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            messageCount = obj["messageCount"]?.jsonPrimitive?.intOrNull ?: 0,
            busy = obj["busy"]?.jsonPrimitive?.booleanOrNull ?: false,
            pinned = obj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
            archived = obj["archived"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun JsonObject?.stringOrNull(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

    companion object {
        const val CAP_BOT_MANAGE = "bot-manage"
        const val CAP_SESSION_MANAGE = "session-manage"
        const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
    }
}
