package io.github.whywood.superpi.runtime

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.json.Json
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
    private val lineJson = Json { ignoreUnknownKeys = true }

    fun isUserMessageEntry(line: String): Boolean {
        val obj = runCatching { lineJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return false
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "message") return false
        val msg = obj["message"] as? JsonObject ?: return false
        return (msg["role"] as? JsonPrimitive)?.contentOrNull == "user"
    }

    fun hasUserTurnAtIndex(file: File, targetTurnIndex: Int): Boolean {
        if (!file.isFile || targetTurnIndex < 0) return false
        var seenUsers = 0
        return file.useLines(Charsets.UTF_8) { lines ->
            for (line in lines) {
                if (line.isNotBlank() && isUserMessageEntry(line)) {
                    if (seenUsers == targetTurnIndex) return@useLines true
                    seenUsers++
                }
            }
            false
        }
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
        var seenUsers = 0
        var foundTarget = false
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            if (isUserMessageEntry(line)) {
                if (seenUsers >= targetTurnIndex) {
                    foundTarget = true
                    break
                }
                seenUsers++
            }
            kept.add(line)
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
        val out = ArrayList<String>(lines.size + 1)
        var seenUsers = 0
        var wroteHeader = false
        for (line in lines) {
            if (line.isBlank()) continue
            val obj = runCatching { lineJson.parseToJsonElement(line).jsonObject }.getOrNull()
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
            if (upToUserTurn != null && isUserMessageEntry(line)) {
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
