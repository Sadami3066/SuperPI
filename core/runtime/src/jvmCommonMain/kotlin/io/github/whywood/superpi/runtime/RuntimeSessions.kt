package io.github.whywood.superpi.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RuntimeSessions(
    private val mutex: Mutex,
    private val handles: ConcurrentHashMap<String, RuntimeHandle>,
    private val catalog: SessionDirectory,
    private val visibleCwds: suspend () -> List<String>,
    private val workspaceFor: suspend (String) -> RuntimeWorkspace,
    private val cwdsForWorkspace: suspend (String) -> List<String> = { id -> listOf(workspaceFor(id).cwd) },
    private val emitListChanged: suspend () -> Unit,
    private val emitSessionRemoved: suspend (String) -> Unit,
    private val detachForget: suspend (String) -> Unit,
    private val sessionCleaner: ((String) -> Unit)?,
    private val sessionArchived: ((String) -> Unit)?,
) {
    private fun normalizeCwd(raw: String): String =
        runCatching { File(raw).canonicalPath }.getOrDefault(File(raw).absolutePath)

    private suspend fun handleToSummary(handle: RuntimeHandle): ConversationSummary {
        val sid = handle.session.sessionId
        return ConversationSummary(
            id = sid,
            title = handle.session.sessionName()?.takeIf { it.isNotBlank() } ?: "",
            updatedAt = handle.lastActiveAt,
            filePath = handle.session.sessionFile.orEmpty(),
            cwd = handle.cwd,
            memoryExcluded = catalog.isMemoryExcluded(sid),
        )
    }

    private fun isVisibleInMemory(handle: RuntimeHandle): Boolean =
        handle.state.busy || handle.session.history().isNotEmpty()

    suspend fun all(): List<ConversationSummary> {
        val cwds = visibleCwds()
        val persistedAll = catalog.listAll(cwds)
        val knownIds = persistedAll.mapTo(HashSet()) { it.id }
        val normalizedVisible = cwds.mapTo(HashSet()) { normalizeCwd(it) }
        val inMemory = handles.values
            .filter { h ->
                val sid = h.session.sessionId
                sid !in knownIds && normalizeCwd(h.cwd) in normalizedVisible && isVisibleInMemory(h)
            }
            .map { handleToSummary(it) }
        return (inMemory + persistedAll.filterNot { it.archived }).sortedByDescending { it.updatedAt }
    }

    suspend fun workspaceCount(workspaceId: String): Int = list(workspaceId).size

    suspend fun title(sessionId: String): String? = mutex.withLock {
        handles[sessionId]?.session?.sessionName()
            ?: catalog.find(visibleCwds(), sessionId)?.title
    }

    suspend fun list(workspaceId: String): List<ConversationSummary> {
        val targetCwds = cwdsForWorkspace(workspaceId)
        val normalizedTargets = targetCwds.mapTo(HashSet()) { normalizeCwd(it) }
        val persistedAll = if (targetCwds.size <= 1) {
            catalog.list(targetCwds.firstOrNull() ?: workspaceFor(workspaceId).cwd)
        } else {
            catalog.listAll(targetCwds)
        }
        val knownIds = persistedAll.mapTo(HashSet()) { it.id }
        val inMemory = handles.values
            .filter { h ->
                val sid = h.session.sessionId
                sid !in knownIds && normalizeCwd(h.cwd) in normalizedTargets && isVisibleInMemory(h)
            }
            .map { handleToSummary(it) }
        return (inMemory + persistedAll.filterNot { it.archived }).sortedByDescending { it.updatedAt }
    }

    suspend fun archived(): List<ConversationSummary> =
        catalog.listAll(visibleCwds()).filter { it.archived }

    suspend fun rename(id: String, title: String) {
        catalog.rename(id, title)
        emitListChanged()
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        catalog.setPinned(id, pinned)
        emitListChanged()
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        catalog.setArchived(id, archived)
        if (archived) sessionArchived?.invoke(id)
        emitSessionRemoved(id)
        emitListChanged()
    }

    suspend fun setMemoryExcluded(id: String, excluded: Boolean) {
        catalog.setMemoryExcluded(id, excluded)
        emitListChanged()
    }

    suspend fun isMemoryExcluded(id: String): Boolean = catalog.isMemoryExcluded(id)

    suspend fun filePath(sessionId: String): String? =
        handles[sessionId]?.session?.sessionFile
            ?: catalog.find(visibleCwds(), sessionId)?.filePath

    suspend fun cwd(sessionId: String): String? =
        handles[sessionId]?.cwd
            ?: catalog.find(visibleCwds(), sessionId)?.cwd

    suspend fun delete(id: String) {
        val handleFile = handles[id]?.session?.sessionFile
        val summaries = catalog.listAll(visibleCwds()).filter { it.id == id }
        detachForget(id)
        sessionCleaner?.invoke(id)
        val pathsToDelete = buildSet {
            handleFile?.takeIf { it.isNotBlank() }?.let { add(it) }
            summaries.forEach { if (it.filePath.isNotBlank()) add(it.filePath) }
        }
        for (path in pathsToDelete) {
            catalog.delete(path)
        }
        catalog.removeMeta(id)
        emitSessionRemoved(id)
        emitListChanged()
    }
}
