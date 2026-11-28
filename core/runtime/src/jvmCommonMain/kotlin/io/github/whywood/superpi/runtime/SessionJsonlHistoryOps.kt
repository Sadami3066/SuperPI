package io.github.whywood.superpi.runtime

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Shared JSONL history operations used by both the desktop Host server and Android local sessions
 * for turn rewind (truncating before a 0-based user turn) and conversation branching/forking.
 */
object SessionJsonlHistoryOps {
    private const val WORKER_UPDATE_PREFIX = "[Worker update]"
    private val lineJson = Json { ignoreUnknownKeys = true }

    private fun parseLineObject(line: String): JsonObject? =
        runCatching { lineJson.parseToJsonElement(line).jsonObject }.getOrNull()

    private fun collectOmittedEntryIds(parsed: List<JsonObject?>): Set<String> {
        val omitted = HashSet<String>()
        for (obj in parsed) {
            if (obj == null) continue
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "context_edit") continue
            val replacement = obj["replacement"]
            if (replacement == null || replacement is JsonNull) {
                val targetId = (obj["targetId"] as? JsonPrimitive)?.contentOrNull
                if (!targetId.isNullOrBlank()) {
                    omitted.add(targetId)
                }
            }
        }
        return omitted
    }

    private fun isVisibleUserMessage(obj: JsonObject?, omittedEntryIds: Set<String>): Boolean {
        if (obj == null) return false
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "message") return false
        val entryId = (obj["id"] as? JsonPrimitive)?.contentOrNull
        if (entryId != null && entryId in omittedEntryIds) return false
        val msg = obj["message"] as? JsonObject ?: return false
        if ((msg["role"] as? JsonPrimitive)?.contentOrNull != "user") return false
        val content = msg["content"] ?: return false
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { block ->
                val bObj = block as? JsonObject ?: return@mapNotNull null
                if ((bObj["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (bObj["text"] as? JsonPrimitive)?.contentOrNull
                } else {
                    null
                }
            }.joinToString("\n")
            else -> ""
        }
        if (text.trimStart().startsWith(WORKER_UPDATE_PREFIX)) return false
        val hasBlocks = (content as? JsonArray)?.isNotEmpty() == true
        return text.isNotBlank() || hasBlocks
    }

    fun isUserMessageEntry(line: String): Boolean =
        isVisibleUserMessage(parseLineObject(line), emptySet())

    fun hasUserTurnAtIndex(file: File, targetTurnIndex: Int): Boolean {
        if (!file.isFile || targetTurnIndex < 0) return false
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val parsed = lines.map(::parseLineObject)
        val omitted = collectOmittedEntryIds(parsed)
        var seenUsers = 0
        for (obj in parsed) {
            if (isVisibleUserMessage(obj, omitted)) {
                if (seenUsers == targetTurnIndex) return true
                seenUsers++
            }
        }
        return false
    }

    /**
     * Atomically truncates [file] right before the 0-based user message at [targetTurnIndex].
     * Writes a reversible `<name>.pre-rewind.bak` sibling backup before swapping.
     * Returns `true` when [targetTurnIndex] existed and the file was updated.
     */
    fun truncateBeforeUserTurn(file: File, targetTurnIndex: Int): Boolean {
        if (!file.isFile || targetTurnIndex < 0) return false
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        val parsed = lines.map(::parseLineObject)
        val omitted = collectOmittedEntryIds(parsed)
        var seenUsers = 0
        var foundTarget = false
        val kept = ArrayList<String>(lines.size)
        for (i in lines.indices) {
            if (isVisibleUserMessage(parsed[i], omitted)) {
                if (seenUsers >= targetTurnIndex) {
                    foundTarget = true
                    break
                }
                seenUsers++
            }
            kept.add(lines[i])
        }
        if (!foundTarget) return false
        runCatching {
            val bak = File(file.parentFile, "${file.name}.pre-rewind.bak")
            Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val parent = file.parentFile ?: return false
        val tmp = Files.createTempFile(parent.toPath(), ".truncate.", ".tmp")
        try {
            val payload = if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n"
            tmp.toFile().writeText(payload, Charsets.UTF_8)
            try {
                Files.move(
                    tmp,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
        return true
    }

    /**
     * Returns JSONL lines representing a forked session up to and including 0-based user turn
     * [upToUserTurn] (or the full conversation when [upToUserTurn] is null), rewriting the
     * session header id to [newSessionId].
     */
    fun sliceLinesForFork(
        lines: List<String>,
        upToUserTurn: Int?,
        newSessionId: String,
        cwd: String,
    ): List<String> {
        val nonBlank = lines.filter { it.isNotBlank() }
        val parsed = nonBlank.map(::parseLineObject)
        val omitted = collectOmittedEntryIds(parsed)
        val out = ArrayList<String>(nonBlank.size + 1)
        var seenUsers = 0
        var wroteHeader = false
        for (i in nonBlank.indices) {
            val line = nonBlank[i]
            val obj = parsed[i]
            if (obj != null && (obj["type"] as? JsonPrimitive)?.contentOrNull == "session") {
                val rewritten = buildJsonObject {
                    for ((k, v) in obj) {
                        if (k == "id") put("id", newSessionId) else put(k, v)
                    }
                }
                out.add(rewritten.toString())
                wroteHeader = true
                continue
            }
            if (upToUserTurn != null && isVisibleUserMessage(obj, omitted)) {
                if (seenUsers > upToUserTurn) break
                seenUsers++
            }
            out.add(line)
        }
        if (!wroteHeader) {
            val headerObj = buildJsonObject {
                put("type", "session")
                put("version", 3)
                put("id", newSessionId)
                put("timestamp", Instant.now().toString())
                put("cwd", cwd)
            }
            out.add(0, headerObj.toString())
        }
        return out
    }

    fun forkSessionFile(
        sourceFile: File,
        targetFile: File,
        upToUserTurn: Int?,
        newSessionId: String,
        cwd: String,
    ): Boolean {
        if (!sourceFile.isFile) return false
        val sourceLines = sourceFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val kept = sliceLinesForFork(sourceLines, upToUserTurn, newSessionId, cwd)
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
        return true
    }
}
