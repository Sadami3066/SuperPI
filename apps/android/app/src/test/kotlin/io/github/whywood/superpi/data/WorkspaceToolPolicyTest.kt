package io.github.whywood.superpi.data

import io.github.whywood.superpi.runtime.MemoryAccessScope
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolPolicyTest {
    private val installed = setOf("a", "b", "c")

    @Test
    fun newBotStartsWithNothingEnabled() {
        val policy = WorkspaceToolPolicy()

        assertTrue(policy.enabledSkills(installed).isEmpty())
        assertTrue(policy.enabledPlugins(installed).isEmpty())
        assertTrue(policy.enabledMcpServers(installed).isEmpty())
        assertFalse(policy.screenTools)
        assertFalse(policy.miniappTools)
        assertEquals(MemoryAccessScope.PERSONAL, policy.effectiveMemoryScope())
        assertFalse(policy.effectiveMemoryDispatch())
        assertTrue(policy.effectiveMemoryDispatch(isHome = true))
        assertTrue(policy.isEmpty)
    }

    @Test
    fun explicitSelectionOnlyEnablesChosenItems() {
        val policy = WorkspaceToolPolicy(skills = setOf("a"), plugins = setOf("b"), mcpServers = setOf("c"))

        assertEquals(setOf("a"), policy.enabledSkills(installed))
        assertEquals(setOf("b"), policy.enabledPlugins(installed))
        assertEquals(setOf("c"), policy.enabledMcpServers(installed))
    }

    @Test
    fun defaultAllEnablesEverythingExceptExcludedItems() {
        val policy = WorkspaceToolPolicy(allCapabilities = true, skills = setOf("b"))

        assertEquals(setOf("a", "c"), policy.enabledSkills(installed))
        assertEquals(installed, policy.enabledPlugins(installed))
        assertTrue(policy.effectiveMemoryDispatch())
    }

    @Test
    fun unknownInstalledIdsAreIgnored() {
        val policy = WorkspaceToolPolicy(skills = setOf("missing"))

        assertTrue(policy.enabledSkills(installed).isEmpty())
    }

    @Test
    fun toolSwitchesAndMemorySettingsMakePolicyNonEmpty() {
        assertTrue(WorkspaceToolPolicy().isEmpty)
        assertFalse(WorkspaceToolPolicy(screenTools = true).isEmpty)
        assertFalse(WorkspaceToolPolicy(miniappTools = true).isEmpty)
        assertFalse(WorkspaceToolPolicy(memoryScope = MemoryAccessScope.WORK_ONLY).isEmpty)
        assertFalse(WorkspaceToolPolicy(memoryDispatch = true).isEmpty)
    }

    @Test
    fun memoryScopeOffDisablesDispatchRegardlessOfBotSetting() {
        val offHome = WorkspaceToolPolicy(
            allCapabilities = true,
            memoryScope = MemoryAccessScope.OFF,
            memoryDispatch = true,
        )
        assertFalse(offHome.effectiveMemoryDispatch(isHome = true))

        val workWithDispatch = WorkspaceToolPolicy(
            memoryScope = MemoryAccessScope.WORK_ONLY,
            memoryDispatch = true,
        )
        assertEquals(MemoryAccessScope.WORK_ONLY, workWithDispatch.effectiveMemoryScope())
        assertTrue(workWithDispatch.effectiveMemoryDispatch(isHome = false))
    }

    @Test
    fun storePersistsAndReloadsMemoryScopeAndDispatch() = runTest {
        val dir = Files.createTempDirectory("policy-store-v3").toFile()
        try {
            val policyFile = File(dir, "policies.json")
            val botDir = File(dir, "bot-work").apply { mkdirs() }
            val store = WorkspaceToolPolicyStore(policyFile)
            val saved = WorkspaceToolPolicy(
                skills = setOf("s1"),
                memoryScope = MemoryAccessScope.WORK_ONLY,
                memoryDispatch = true,
            )
            store.update(botDir.absolutePath, saved)

            val reloaded = WorkspaceToolPolicyStore(policyFile)
            val loaded = reloaded.policyForCwd(botDir.absolutePath)
            assertEquals(saved, loaded)
            assertEquals(MemoryAccessScope.WORK_ONLY, loaded.effectiveMemoryScope())
            assertTrue(loaded.effectiveMemoryDispatch())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun allCapabilitiesAloneIsNotEmpty() {
        assertFalse(WorkspaceToolPolicy(allCapabilities = true).isEmpty)
    }
}
