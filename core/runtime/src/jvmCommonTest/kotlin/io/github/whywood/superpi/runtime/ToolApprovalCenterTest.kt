package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.ai.RunSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalCenterTest {

    @Test
    fun `allow once approves single execution without persisting session grant`() = runBlocking {
        val center = ToolApprovalCenter()
        val deferred = async {
            center.requestApproval(
                sessionId = "s1",
                toolCallId = "tc1",
                toolName = "bash",
                riskLevel = ToolRiskLevel.STANDARD,
                reason = ToolApprovalReason.COMMAND_EXECUTION,
                command = "echo hello",
                detail = "echo hello",
            )
        }
        val pending = withTimeout(2_000) { center.pending.first { it.isNotEmpty() } }.single()
        assertTrue(center.resolve(pending.id, ToolApprovalDecision.ALLOW_ONCE))
        assertEquals(ToolApprovalOutcome.Approved, deferred.await())
        assertTrue(center.pending.value.isEmpty())

        val second = async {
            center.requestApproval(
                sessionId = "s1",
                toolCallId = "tc2",
                toolName = "bash",
                riskLevel = ToolRiskLevel.STANDARD,
                reason = ToolApprovalReason.COMMAND_EXECUTION,
                command = "echo again",
                detail = "echo again",
            )
        }
        val secondPending = withTimeout(2_000) { center.pending.first { it.isNotEmpty() } }.single()
        assertTrue(center.resolve(secondPending.id, ToolApprovalDecision.DENY, "No thanks"))
        assertEquals(ToolApprovalOutcome.Denied("No thanks"), second.await())
    }

    @Test
    fun `allow session skips repeat standard prompts but never bypasses destructive commands`() = runBlocking {
        val center = ToolApprovalCenter()
        val first = async {
            center.requestApproval(
                sessionId = "s2",
                toolCallId = "tc1",
                toolName = "bash",
                riskLevel = ToolRiskLevel.STANDARD,
                reason = ToolApprovalReason.COMMAND_EXECUTION,
                command = "ls -la",
                detail = "ls -la",
            )
        }
        val pending = withTimeout(2_000) { center.pending.first { it.isNotEmpty() } }.single()
        assertTrue(center.resolve(pending.id, ToolApprovalDecision.ALLOW_SESSION))
        assertEquals(ToolApprovalOutcome.Approved, first.await())

        val autoApproved = center.requestApproval(
            sessionId = "s2",
            toolCallId = "tc2",
            toolName = "bash",
            riskLevel = ToolRiskLevel.STANDARD,
            reason = ToolApprovalReason.COMMAND_EXECUTION,
            command = "pwd",
            detail = "pwd",
        )
        assertEquals(ToolApprovalOutcome.Approved, autoApproved)
        assertTrue(center.pending.value.isEmpty())

        val destructive = async {
            center.requestApproval(
                sessionId = "s2",
                toolCallId = "tc3",
                toolName = "bash",
                riskLevel = ToolRiskLevel.DESTRUCTIVE,
                reason = ToolApprovalReason.DESTRUCTIVE_COMMAND,
                command = "rm -rf /tmp/danger",
                detail = "rm -rf /tmp/danger",
            )
        }
        val destructivePending = withTimeout(2_000) { center.pending.first { it.isNotEmpty() } }.single()
        assertEquals(ToolRiskLevel.DESTRUCTIVE, destructivePending.riskLevel)
        assertTrue(center.resolve(destructivePending.id, ToolApprovalDecision.DENY))
        assertEquals(ToolApprovalOutcome.Denied(null), destructive.await())
    }

    @Test
    fun `abort signal immediately cancels pending approval without deadlock`() = runBlocking {
        val center = ToolApprovalCenter()
        val signal = RunSignal()
        val started = CompletableDeferred<Unit>()
        val job = async {
            center.requestApproval(
                sessionId = "s3",
                workerId = "wrk-1",
                toolCallId = "tc1",
                toolName = "write",
                riskLevel = ToolRiskLevel.OUTSIDE_WORKSPACE,
                reason = ToolApprovalReason.OUTSIDE_WORKSPACE_WRITE,
                path = "/tmp/out.txt",
                detail = "/tmp/out.txt",
                signal = signal,
            )
        }
        val pending = withTimeout(2_000) { center.pending.first { it.isNotEmpty() } }.single()
        started.complete(Unit)
        assertFalse(pending.workerId.isNullOrBlank())
        signal.abort("user stopped")
        assertEquals(ToolApprovalOutcome.Cancelled, withTimeout(2_000) { job.await() })
        assertTrue(center.pending.value.isEmpty())
    }

    @Test
    fun `timeout denies pending approval and clears pending list`() = runBlocking {
        val center = ToolApprovalCenter()
        val outcome = center.requestApproval(
            sessionId = "s4",
            toolCallId = "tc-timeout",
            toolName = "bash",
            riskLevel = ToolRiskLevel.STANDARD,
            reason = ToolApprovalReason.COMMAND_EXECUTION,
            command = "sleep 5",
            detail = "sleep 5",
            timeoutMs = 50L,
        )
        assertTrue(outcome is ToolApprovalOutcome.Denied)
        assertTrue(center.pending.value.isEmpty())
    }
}
