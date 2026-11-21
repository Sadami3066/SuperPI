package io.github.whywood.superpi.ui.shell

import io.github.whywood.superpi.remote.RemoteSlashCommand
import io.github.whywood.superpi.runtime.SkillSummary
import io.github.whywood.superpi.ui.common.chat.ChatCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandsTest {
    @Test
    fun compactResolvesToCompactAction() {
        assertEquals(ShellCommandAction.Compact, resolveChatCommand(ChatCommand.COMPACT, ""))
    }

    @Test
    fun goalArgsResolveToFixedActionsOrInvalid() {
        assertEquals(ShellCommandAction.GoalPause, resolveGoalCommand("pause"))
        assertEquals(ShellCommandAction.GoalResume, resolveGoalCommand(" resume "))
        assertEquals(ShellCommandAction.GoalClear, resolveGoalCommand("clear"))
        assertEquals(ShellCommandAction.GoalInvalid, resolveGoalCommand("   "))
    }

    @Test
    fun goalCreateKeepsTrimmedObjective() {
        assertEquals(ShellCommandAction.GoalCreate("read docs"), resolveGoalCommand("  read docs  "))
    }

    @Test
    fun plainInputPlansSend() {
        assertEquals(ShellSubmitPlan.Send, planSubmission("hello", sending = false))
    }

    @Test
    fun commandsRejectedWhileSending() {
        assertEquals(ShellSubmitPlan.RejectWhileSending, planSubmission("/compact", sending = true))
    }

    @Test
    fun unknownCommandIsReported() {
        val plan = planSubmission("/nope", sending = false)
        assertTrue(plan is ShellSubmitPlan.UnknownCommand)
        assertEquals("nope", (plan as ShellSubmitPlan.UnknownCommand).name)
    }

    @Test
    fun goalCommandPlansRunCommand() {
        assertEquals(ShellSubmitPlan.RunCommand(ShellCommandAction.GoalPause), planSubmission("/goal pause", false))
    }

    @Test
    fun insertionReplacesTriggerOrAppendsWithSeparator() {
        assertEquals("/compact ", commandInsertion("/co", "/compact "))
        assertEquals("hello /goal ", commandInsertion("hello", "/goal "))
    }

    @Test
    fun commandItemsCarryEnabledFlags() {
        val items = buildCommandItems(
            "s1", sending = false, compacting = false,
            skills = listOf(SkillSummary("miniapp", "build pages")),
            compactTitle = "c", compactDescription = "cd", goalTitle = "g", goalDescription = "gd",
        )
        assertEquals(listOf("compact", "goal", "skill:miniapp"), items.map { it.id })
        assertEquals(listOf(true, true, true), items.map { it.enabled })
        val busy = buildCommandItems(
            "s1", sending = true, compacting = false, skills = emptyList(),
            compactTitle = "c", compactDescription = "cd", goalTitle = "g", goalDescription = "gd",
        )
        assertEquals(listOf(false, true), busy.map { it.enabled })
    }

    @Test
    fun remoteSubmissionRoutesCompactLocallyAndForwardsPeerSlashCommands() {
        assertEquals(
            ShellSubmitPlan.RunCommand(ShellCommandAction.Compact),
            planSubmission("/compact focus", sending = false, isRemote = true),
        )
        assertEquals(
            ShellSubmitPlan.RejectWhileSending,
            planSubmission("/compact", sending = true, isRemote = true),
        )
        assertEquals(ShellSubmitPlan.Send, planSubmission("/goal pause", sending = false, isRemote = true))
        assertEquals(ShellSubmitPlan.Send, planSubmission("/custom-template", sending = false, isRemote = true))
        assertEquals(ShellSubmitPlan.Send, planSubmission("/skill:miniapp", sending = true, isRemote = true))
    }

    @Test
    fun remoteCommandItemsReplaceLocalGoalAndSkillsWithPeerCommands() {
        val items = buildCommandItems(
            conversationId = "s1",
            sending = false,
            compacting = false,
            skills = listOf(SkillSummary("local-skill", "local")),
            compactTitle = "c",
            compactDescription = "cd",
            goalTitle = "g",
            goalDescription = "gd",
            isRemote = true,
            canCompact = true,
            remoteCommands = listOf(
                RemoteSlashCommand(name = "compact", description = "dup", source = "builtin"),
                RemoteSlashCommand(name = "review", description = "Review PR", source = "prompt"),
            ),
        )
        assertEquals(listOf("compact", "remote:review"), items.map { it.id })
        assertEquals(listOf("/compact ", "/review "), items.map { it.insertText })
        val withoutCompact = buildCommandItems(
            conversationId = "s1",
            sending = false,
            compacting = false,
            skills = listOf(SkillSummary("local-skill", "local")),
            compactTitle = "c",
            compactDescription = "cd",
            goalTitle = "g",
            goalDescription = "gd",
            isRemote = true,
            canCompact = false,
            remoteCommands = listOf(RemoteSlashCommand(name = "review", description = "Review PR", source = "prompt")),
        )
        assertEquals(listOf("remote:review"), withoutCompact.map { it.id })
    }
}
