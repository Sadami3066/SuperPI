package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.ai.RunSignal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ToolRiskLevel(val wire: String) {
    STANDARD("standard"),
    OUTSIDE_WORKSPACE("outside_workspace"),
    DESTRUCTIVE("destructive"),
}

enum class ToolApprovalReason(val wire: String) {
    COMMAND_EXECUTION("command_execution"),
    OUTSIDE_WORKSPACE_WRITE("outside_workspace_write"),
    DESTRUCTIVE_COMMAND("destructive_command"),
}

enum class ToolApprovalDecision(val wire: String) {
    ALLOW_ONCE("allow_once"),
    ALLOW_SESSION("allow_session"),
    DENY("deny"),
    ;

    companion object {
        fun parseWireOrNull(raw: String?): ToolApprovalDecision? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.wire.equals(clean, ignoreCase = true) }
        }
    }
}

data class PendingToolApproval(
    val id: String,
    val sessionId: String,
    val workerId: String? = null,
    val toolCallId: String,
    val toolName: String,
    val riskLevel: ToolRiskLevel,
    val reason: ToolApprovalReason,
    val command: String? = null,
    val path: String? = null,
    val workspaceRoot: String? = null,
    val detail: String,
    val createdAt: Long,
)

sealed interface ToolApprovalOutcome {
    data object Approved : ToolApprovalOutcome
    data class Denied(val reason: String? = null) : ToolApprovalOutcome
    data object Cancelled : ToolApprovalOutcome
}

/**
 * In-memory human authorization hub for guarded tool executions.
 * Intentionally separated from [QuestionCenter] so tool permissions are never
 * handled through user-question cards or escalated to autonomous LLMs.
 */
class ToolApprovalCenter {
    private val lock = Any()
    private val pendingState = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    private val waiters = mutableMapOf<String, WaiterEntry>()
    private val sessionGrants = ConcurrentHashMap<String, MutableSet<String>>()

    private data class WaiterEntry(
        val approval: PendingToolApproval,
        val deferred: CompletableDeferred<ToolApprovalOutcome>,
    )

    val pending: StateFlow<List<PendingToolApproval>> = pendingState.asStateFlow()

    suspend fun requestApproval(
        sessionId: String,
        workerId: String? = null,
        toolCallId: String,
        toolName: String,
        riskLevel: ToolRiskLevel,
        reason: ToolApprovalReason,
        command: String? = null,
        path: String? = null,
        workspaceRoot: String? = null,
        detail: String,
        signal: RunSignal? = null,
    ): ToolApprovalOutcome {
        if (signal?.isAborted == true) {
            return ToolApprovalOutcome.Cancelled
        }
        val grantKey = grantKey(riskLevel, reason, toolName)
        if (riskLevel != ToolRiskLevel.DESTRUCTIVE && hasSessionGrant(sessionId, grantKey)) {
            return ToolApprovalOutcome.Approved
        }

        val approvalId = UUID.randomUUID().toString()
        val entry = PendingToolApproval(
            id = approvalId,
            sessionId = sessionId,
            workerId = workerId,
            toolCallId = toolCallId,
            toolName = toolName,
            riskLevel = riskLevel,
            reason = reason,
            command = command,
            path = path,
            workspaceRoot = workspaceRoot,
            detail = detail,
            createdAt = System.currentTimeMillis(),
        )
        val deferred = CompletableDeferred<ToolApprovalOutcome>()
        synchronized(lock) {
            waiters[approvalId] = WaiterEntry(entry, deferred)
            pendingState.value = pendingState.value + entry
        }
        val unsubscribe = signal?.onAbort { cancel(approvalId) }
        return try {
            if (signal?.isAborted == true) {
                cancel(approvalId)
            }
            deferred.await()
        } finally {
            unsubscribe?.invoke()
            removePending(approvalId)
        }
    }

    fun resolve(approvalId: String, decision: ToolApprovalDecision, note: String? = null): Boolean {
        val entry = synchronized(lock) { waiters.remove(approvalId) } ?: return false
        removePending(approvalId)
        val outcome = when (decision) {
            ToolApprovalDecision.ALLOW_ONCE -> ToolApprovalOutcome.Approved
            ToolApprovalDecision.ALLOW_SESSION -> {
                if (entry.approval.riskLevel != ToolRiskLevel.DESTRUCTIVE) {
                    val key = grantKey(entry.approval.riskLevel, entry.approval.reason, entry.approval.toolName)
                    sessionGrants.computeIfAbsent(entry.approval.sessionId) { ConcurrentHashMap.newKeySet() }.add(key)
                }
                ToolApprovalOutcome.Approved
            }
            ToolApprovalDecision.DENY -> ToolApprovalOutcome.Denied(note?.trim()?.takeIf { it.isNotEmpty() })
        }
        entry.deferred.complete(outcome)
        return true
    }

    fun cancel(approvalId: String) {
        val entry = synchronized(lock) { waiters.remove(approvalId) } ?: return
        removePending(approvalId)
        entry.deferred.complete(ToolApprovalOutcome.Cancelled)
    }

    fun cancelForSession(sessionId: String) {
        val ids = synchronized(lock) {
            waiters.values.filter { it.approval.sessionId == sessionId }.map { it.approval.id }
        }
        ids.forEach(::cancel)
    }

    fun cancelForWorker(workerId: String) {
        val ids = synchronized(lock) {
            waiters.values.filter { it.approval.workerId == workerId }.map { it.approval.id }
        }
        ids.forEach(::cancel)
    }

    fun clearSessionGrants(sessionId: String) {
        sessionGrants.remove(sessionId)
    }

    private fun hasSessionGrant(sessionId: String, grantKey: String): Boolean =
        sessionGrants[sessionId]?.contains(grantKey) == true

    private fun grantKey(riskLevel: ToolRiskLevel, reason: ToolApprovalReason, toolName: String): String =
        "${riskLevel.wire}:${reason.wire}:$toolName"

    private fun removePending(approvalId: String) {
        synchronized(lock) {
            waiters.remove(approvalId)
            pendingState.value = pendingState.value.filterNot { it.id == approvalId }
        }
    }
}
