package io.github.whywood.superpi.host.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class InboxItem(
    val id: String,
    val source: String, // "timer" | "worker" | "goal"
    val title: String,
    val summary: String,
    val botId: String? = null,
    val sessionId: String? = null,
    val workerId: String? = null,
    val worktreeId: String? = null,
    val status: String = "unread", // "unread" | "approved" | "dismissed"
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Persisted Review Inbox capturing outcomes of unattended background tasks
 * (scheduled timers, Super workers, and long-running goals) under `~/.super-pi/inbox.json`.
 */
class AutomationInboxStore(
    private val file: File,
    private val maxItems: Int = 100,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val _items = MutableStateFlow<List<InboxItem>>(loadFromDisk())
    val items: StateFlow<List<InboxItem>> = _items.asStateFlow()

    fun list(): List<InboxItem> = _items.value

    fun unreadCount(): Int = _items.value.count { it.status == "unread" }

    fun record(
        source: String,
        title: String,
        summary: String,
        botId: String? = null,
        sessionId: String? = null,
        workerId: String? = null,
        worktreeId: String? = null,
    ): InboxItem = synchronized(lock) {
        val cleanTitle = title.trim().ifEmpty { source }
        val cleanSummary = summary.trim().take(400)
        val item = InboxItem(
            id = "inb-${UUID.randomUUID().toString().replace("-", "").take(12)}",
            source = source.trim().lowercase(),
            title = cleanTitle,
            summary = cleanSummary,
            botId = botId?.trim()?.takeIf { it.isNotEmpty() },
            sessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() },
            workerId = workerId?.trim()?.takeIf { it.isNotEmpty() },
            worktreeId = worktreeId?.trim()?.takeIf { it.isNotEmpty() },
            status = "unread",
            createdAt = System.currentTimeMillis(),
        )
        val next = (listOf(item) + _items.value).take(maxItems)
        persistLocked(next)
        _items.value = next
        item
    }

    fun updateStatus(id: String, status: String): InboxItem? = synchronized(lock) {
        val norm = status.trim().lowercase()
        if (norm !in ALLOWED_STATUSES) return null
        var updated: InboxItem? = null
        val next = _items.value.map { cur ->
            if (cur.id == id) {
                cur.copy(status = norm).also { updated = it }
            } else {
                cur
            }
        }
        if (updated != null) {
            persistLocked(next)
            _items.value = next
        }
        updated
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val before = _items.value
        val next = before.filterNot { it.id == id }
        if (next.size == before.size) return false
        persistLocked(next)
        _items.value = next
        true
    }

    fun clearSettled(): Int = synchronized(lock) {
        val before = _items.value
        val next = before.filter { it.status == "unread" }
        val removed = before.size - next.size
        if (removed > 0) {
            persistLocked(next)
            _items.value = next
        }
        removed
    }

    private fun loadFromDisk(): List<InboxItem> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(InboxItem.serializer()), file.readText(Charsets.UTF_8))
                .sortedByDescending { it.createdAt }
                .take(maxItems)
        }.getOrDefault(emptyList())
    }

    private fun persistLocked(items: List<InboxItem>) {
        file.parentFile?.mkdirs()
        val tmp = Files.createTempFile(file.parentFile.toPath(), ".inbox.", ".tmp")
        try {
            tmp.toFile().writeText(
                json.encodeToString(ListSerializer(InboxItem.serializer()), items),
                Charsets.UTF_8,
            )
            runCatching {
                Files.setPosixFilePermissions(
                    tmp,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
            }
            try {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    companion object {
        private val ALLOWED_STATUSES = setOf("unread", "approved", "dismissed")
    }
}
