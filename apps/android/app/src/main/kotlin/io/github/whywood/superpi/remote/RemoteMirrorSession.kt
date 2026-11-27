package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteHistoryDto
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.sdk.agent.AgentMessage
import io.github.whywood.superpi.sdk.agent.asAgentMessage
import io.github.whywood.superpi.sdk.ai.AssistantMessage
import io.github.whywood.superpi.sdk.ai.BlockContent
import io.github.whywood.superpi.sdk.ai.Content
import io.github.whywood.superpi.sdk.ai.ImageContent
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.ThinkingContent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.Usage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.paths.AgentPaths
import io.github.whywood.superpi.sdk.coding.session.CustomEntry
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionHeader
import io.github.whywood.superpi.sdk.coding.session.SessionManager
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import io.github.whywood.superpi.sdk.coding.session.SessionStats
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

class RemoteMirrorSession internal constructor(
    private val manager: SessionManager,
) {
    val sessionId: String get() = manager.sessionId

    val sessionFile: String? get() = manager.sessionFile?.toString()

    var peerSessionId: String? = null
        private set

    var historyDigest: String? = null
        private set

    var historySeq: Long? = null
        private set

    init {
        val mapping = manager.getEntries().asReversed()
            .filterIsInstance<CustomEntry>()
            .firstOrNull { it.customType == CUSTOM_TYPE }
        val data = mapping?.data as? JsonObject
        peerSessionId = (data?.get("peerSessionId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        historyDigest = (data?.get("historyDigest") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        historySeq = (data?.get("historySeq") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }

    suspend fun appendUserMessage(text: String, images: List<ImagePart> = emptyList()) {
        val content = if (images.isEmpty()) {
            PlainTextContent(text)
        } else {
            BlockContent(
                buildList<Content> {
                    if (text.isNotEmpty()) add(TextContent(text))
                    images.forEach { image -> add(ImageContent(image.data, image.mimeType)) }
                },
            )
        }
        manager.appendMessage(UserMessage(content = content, timestamp = System.currentTimeMillis()))
    }

    suspend fun hydrateRemoteHistory(
        deviceId: String,
        botId: String,
        history: RemoteHistoryDto,
        digest: String? = null,
    ) {
        appendRemoteMapping(deviceId, botId, history.sessionId, digest, history.seq)
        historySeq = history.seq
        history.title?.trim()?.takeIf { it.isNotBlank() }?.let { appendName(it) }
        for (item in history.messages) {
            appendHistoryItem(botId, item)
        }
    }

    private suspend fun appendHistoryItem(botId: String, item: JsonObject) {
        val role = item.str("role") ?: return
        val timestamp = (item["timestamp"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
        when (role) {
            "user" -> {
                val blocks = parseContentBlocks(item["content"])
                val textOnly = blocks.isNotEmpty() && blocks.all { it is TextContent }
                val messageContent = when {
                    blocks.isEmpty() -> {
                        val rawText = (item["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (rawText.isEmpty()) return
                        PlainTextContent(rawText)
                    }

                    textOnly -> PlainTextContent(blocks.filterIsInstance<TextContent>().joinToString("") { it.text })
                    else -> BlockContent(blocks)
                }
                manager.appendMessage(UserMessage(content = messageContent, timestamp = timestamp))
            }

            "assistant" -> {
                val blocks = parseContentBlocks(item["content"])
                val errorMessage = item.str("errorMessage")
                if (blocks.isEmpty() && errorMessage == null) return
                val hasTools = blocks.any { it is ToolCall }
                manager.appendMessage(
                    AssistantMessage(
                        content = blocks,
                        api = "",
                        provider = RemoteTurnState.REMOTE_PROVIDER,
                        model = botId,
                        usage = Usage.empty(),
                        stopReason = when {
                            errorMessage != null -> StopReason.Error
                            hasTools -> StopReason.ToolUse
                            else -> StopReason.Stop
                        },
                        errorMessage = errorMessage,
                        timestamp = timestamp,
                    ),
                )
            }

            "toolResult" -> {
                val toolCallId = item.str("toolCallId") ?: item.str("callId").orEmpty()
                val toolName = item.str("toolName") ?: item.str("name").orEmpty()
                val isError = (item["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
                val blocks = parseContentBlocks(item["content"]).ifEmpty {
                    listOf(TextContent((item["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()))
                }
                manager.appendMessage(
                    ToolResultMessage(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        content = blocks,
                        details = item["details"]?.takeUnless { it is JsonNull },
                        isError = isError,
                        timestamp = timestamp,
                    ),
                )
            }
        }
    }

    private fun parseContentBlocks(element: kotlinx.serialization.json.JsonElement?): MutableList<Content> {
        val out = mutableListOf<Content>()
        if (element is JsonPrimitive) {
            element.contentOrNull?.takeIf { it.isNotEmpty() }?.let { out += TextContent(it) }
            return out
        }
        val array = element as? JsonArray ?: return out
        for (rawBlock in array) {
            val block = rawBlock as? JsonObject ?: continue
            when (block.str("type")) {
                "thinking" -> {
                    val thinking = block.str("thinking") ?: block.str("text").orEmpty()
                    if (thinking.isNotEmpty()) out += ThinkingContent(thinking)
                }

                "text" -> {
                    val text = block.str("text").orEmpty()
                    if (text.isNotEmpty()) out += TextContent(text)
                }

                "image" -> {
                    val data = block.str("data")
                    val mimeType = block.str("mimeType")?.lowercase()
                    if (!data.isNullOrEmpty() &&
                        mimeType in ALLOWED_IMAGE_MIMES &&
                        data.length <= MAX_MIRROR_IMAGE_CHARS
                    ) {
                        out += ImageContent(data = data, mimeType = mimeType!!)
                    }
                }

                "toolCall", "tool_call" -> {
                    val id = block.str("id") ?: block.str("callId") ?: ""
                    val name = block.str("name") ?: ""
                    val args = block["arguments"] as? JsonObject
                        ?: block["args"] as? JsonObject
                        ?: JsonObject(emptyMap())
                    if (id.isNotEmpty() || name.isNotEmpty()) {
                        out += ToolCall(id = id, name = name, arguments = args)
                    }
                }
            }
        }
        return out
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    suspend fun appendAssistantMessage(message: AssistantMessage) {
        manager.appendMessage(message)
    }

    suspend fun appendToolResult(message: ToolResultMessage) {
        manager.appendMessage(message)
    }

    suspend fun appendRemoteMapping(
        deviceId: String,
        botId: String,
        peerSessionId: String,
        historyDigest: String? = null,
        historySeq: Long? = null,
    ) {
        manager.appendCustomEntry(
            CUSTOM_TYPE,
            buildJsonObject {
                put("deviceId", deviceId)
                put("botId", botId)
                put("peerSessionId", peerSessionId)
                historyDigest?.let { put("historyDigest", it) }
                historySeq?.let { put("historySeq", it) }
            },
        )
        this.peerSessionId = peerSessionId
        if (historyDigest != null) this.historyDigest = historyDigest
        if (historySeq != null) this.historySeq = historySeq
    }

    suspend fun clearRemoteMapping(deviceId: String, botId: String) {
        manager.appendCustomEntry(
            CUSTOM_TYPE,
            buildJsonObject {
                put("deviceId", deviceId)
                put("botId", botId)
                put("peerSessionId", JsonNull)
            },
        )
        peerSessionId = null
        historyDigest = null
        historySeq = null
    }

    suspend fun appendName(name: String) {
        manager.appendSessionInfo(name)
    }

    suspend fun appendCustomMessage(customType: String, text: String, payloadJson: String?, display: Boolean) {
        val details = payloadJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        manager.appendCustomMessageEntry(customType, PlainTextContent(text), display, details)
    }

    suspend fun appendCustomEntry(customType: String, dataJson: String) {
        val data = runCatching { Json.parseToJsonElement(dataJson) }.getOrNull()
        manager.appendCustomEntry(customType, data)
    }

    fun messages(): List<AgentMessage> = manager.getBranch()
        .filterIsInstance<SessionMessageEntry>()
        .map { entry -> entry.message.asAgentMessage() }

    fun entries(): List<SessionEntry> = manager.getBranch()

    fun name(): String? = manager.getSessionName()

    fun stats(): SessionStats = manager.getSessionStats()

    suspend fun close() {
        manager.close()
    }

    companion object {
        internal const val CUSTOM_TYPE = "superpi.remote"
        private const val MAX_MIRROR_IMAGE_CHARS = 8_000_000
        private val ALLOWED_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        private val sessionHeaderJson = Json { encodeDefaults = true }

        suspend fun create(
            cwd: File,
            agentDir: File,
            persistEmpty: Boolean = false,
        ): RemoteMirrorSession {
            val paths = AgentPaths(cwd.absolutePath.toPath(), agentDir.absolutePath.toPath())
            val fs = FileSystem.SYSTEM
            val manager = SessionManager.create(paths, fs)
            if (!persistEmpty) return RemoteMirrorSession(manager)

            // The SDK normally writes a file with the first message. A remote
            // history snapshot can contain only the peer mapping and title.
            val file = checkNotNull(manager.sessionFile)
            fs.createDirectories(checkNotNull(file.parent))
            fs.write(file, mustCreate = true) {
                writeUtf8(sessionHeaderJson.encodeToString(SessionHeader.serializer(), manager.getHeader()))
                writeByte('\n'.code)
            }
            manager.close()
            return RemoteMirrorSession(SessionManager.open(file, fs))
        }

        suspend fun open(file: File): RemoteMirrorSession =
            RemoteMirrorSession(SessionManager.open(file.absolutePath.toPath(), FileSystem.SYSTEM))
    }
}
