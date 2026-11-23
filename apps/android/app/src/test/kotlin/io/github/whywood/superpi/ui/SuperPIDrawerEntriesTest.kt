package io.github.whywood.superpi.ui

import io.github.whywood.superpi.data.relay.RemoteSessionSummaryDto
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.RemoteBotRef
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperPIDrawerEntriesTest {
    private val workspace = RuntimeWorkspace(id = "ws-1", name = "Work", cwd = "/work", isHome = true)

    private fun session(
        id: String,
        updatedAt: Long,
        pinned: Boolean = false,
        archived: Boolean = false,
        cwd: String = "/work",
    ) = ConversationSummary(
        id = id,
        title = "Session $id",
        updatedAt = updatedAt,
        filePath = "$cwd/$id.jsonl",
        cwd = cwd,
        pinned = pinned,
        archived = archived,
    )

    private fun remoteWorkspace(online: Boolean) = RuntimeWorkspace(
        id = "remote:phone-b:home",
        name = "PI",
        cwd = "/remote/phone-b/home",
        remote = RemoteBotRef(
            deviceId = "phone-b",
            deviceName = "Backup Phone",
            botId = "home",
            botName = "PI",
            online = online,
        ),
    )

    private fun entries(
        sessions: List<ConversationSummary>,
        workspaceExpanded: Boolean = true,
        sessionsExpanded: Boolean = false,
    ): List<DrawerEntry> = buildDrawerEntries(
        workspaces = listOf(workspace),
        sessionsByCwd = mapOf("/work" to sessions),
        expandedWorkspaceIds = if (workspaceExpanded) setOf(workspace.id) else emptySet(),
        expandedSessionWorkspaceIds = if (sessionsExpanded) setOf(workspace.id) else emptySet(),
    )

    @Test
    fun collapsesToFiveWithMoreRow() {
        val sessions = (1..7).map { session("s$it", it.toLong()) }
        val result = entries(sessions)
        val visible = result.filterIsInstance<DrawerEntry.Session>()
        assertEquals(5, visible.size)
        assertEquals(listOf("s7", "s6", "s5", "s4", "s3"), visible.map { it.conversation.id })
        val more = result.filterIsInstance<DrawerEntry.SessionMore>().single()
        assertEquals(workspace.id, more.workspaceId)
        assertEquals(2, more.remaining)
        assertTrue(result.filterIsInstance<DrawerEntry.SessionLess>().isEmpty())
    }

    @Test
    fun showsAllAndLessRowWhenSessionsExpanded() {
        val sessions = (1..7).map { session("s$it", it.toLong()) }
        val result = entries(sessions, sessionsExpanded = true)
        assertEquals(7, result.filterIsInstance<DrawerEntry.Session>().size)
        val less = result.filterIsInstance<DrawerEntry.SessionLess>().single()
        assertEquals(workspace.id, less.workspaceId)
        assertTrue(result.filterIsInstance<DrawerEntry.SessionMore>().isEmpty())
    }

    @Test
    fun keepsTopFiveOrderedByPinnedThenUpdatedAt() {
        val sessions = listOf(
            session("a", 5),
            session("pin", 1, pinned = true),
            session("b", 4),
            session("c", 3),
            session("d", 2),
            session("e", 6),
        )
        val result = entries(sessions)
        val visible = result.filterIsInstance<DrawerEntry.Session>().map { it.conversation.id }
        assertEquals(listOf("pin", "e", "a", "b", "c"), visible)
        assertEquals(1, result.filterIsInstance<DrawerEntry.SessionMore>().single().remaining)
    }

    @Test
    fun skipsToggleRowsWhenFiveOrFewer() {
        val result = entries((1..3).map { session("s$it", it.toLong()) })
        assertEquals(3, result.filterIsInstance<DrawerEntry.Session>().size)
        assertTrue(result.filterIsInstance<DrawerEntry.SessionMore>().isEmpty())
        assertTrue(result.filterIsInstance<DrawerEntry.SessionLess>().isEmpty())
    }

    @Test
    fun collapsedWorkspaceRendersNoSessions() {
        val result = entries((1..7).map { session("s$it", it.toLong()) }, workspaceExpanded = false)
        assertTrue(result.filterIsInstance<DrawerEntry.Session>().isEmpty())
        assertTrue(result.filterIsInstance<DrawerEntry.SessionMore>().isEmpty())
        assertTrue(result.any { it.key == "workspace_${workspace.id}" })
    }

    @Test
    fun remoteWorkspacesUseTheSameEntriesAsLocalBots() {
        val local = RuntimeWorkspace(id = "ws-1", name = "Work", cwd = "/work")
        val remote = remoteWorkspace(online = true)
        val result = buildDrawerEntries(
            workspaces = listOf(local, remote),
            sessionsByCwd = mapOf(
                local.cwd to listOf(session("s1", 1)),
            ),
            expandedWorkspaceIds = setOf(local.id, remote.id),
            expandedSessionWorkspaceIds = emptySet(),
            remoteDrawerStates = mapOf(
                remote.id to RemoteDrawerState(
                    sessions = listOf(RemoteSessionSummaryDto(id = "remote-s1", title = "Remote chat", updatedAt = 2L)),
                ),
            ),
        )

        assertEquals(
            listOf(local.id, remote.id),
            result.filterIsInstance<DrawerEntry.WorkspaceEntry>().map { it.workspace.id },
        )
        assertEquals(listOf("phone-b"), result.filterIsInstance<DrawerEntry.RemoteDeviceHeader>().map { it.deviceId })
        assertEquals(listOf(local.id), result.filterIsInstance<DrawerEntry.Session>().map { it.workspaceId })
        assertEquals(listOf("remote-s1"), result.filterIsInstance<DrawerEntry.RemoteSession>().map { it.session.id })
        assertEquals(result.size, result.map { it.key }.toSet().size)
    }

    @Test
    fun offlineRemoteWorkspaceCollapsesByDefaultAndExpandsSessionsWhenDeviceExpanded() {
        val remote = remoteWorkspace(online = false)
        val collapsedResult = buildDrawerEntries(
            workspaces = listOf(remote),
            sessionsByCwd = mapOf(remote.cwd to listOf(session("remote-s1", 1, cwd = remote.cwd))),
            expandedWorkspaceIds = setOf(remote.id),
            expandedSessionWorkspaceIds = emptySet(),
        )
        val header = collapsedResult.filterIsInstance<DrawerEntry.RemoteDeviceHeader>().single()
        assertEquals(false, header.online)
        assertEquals(false, header.expanded)
        assertTrue(collapsedResult.filterIsInstance<DrawerEntry.WorkspaceEntry>().isEmpty())

        val expandedResult = buildDrawerEntries(
            workspaces = listOf(remote),
            sessionsByCwd = mapOf(remote.cwd to listOf(session("remote-s1", 1, cwd = remote.cwd))),
            expandedWorkspaceIds = setOf(remote.id),
            expandedSessionWorkspaceIds = emptySet(),
            expandedOfflineRemoteDeviceIds = setOf("phone-b"),
        )

        assertEquals(listOf(remote.id), expandedResult.filterIsInstance<DrawerEntry.WorkspaceEntry>().map { it.workspace.id })
        assertEquals(listOf(remote.id), expandedResult.filterIsInstance<DrawerEntry.Session>().map { it.workspaceId })
    }

    @Test
    fun groupsThreePhonesOnlineFirstAlphabeticallyWithQuickCreateAndUniqueKeys() {
        val local = RuntimeWorkspace(id = "home", name = "PI", cwd = "/home", isHome = true)
        fun phoneBot(deviceId: String, deviceName: String, online: Boolean) = RuntimeWorkspace(
            id = "remote:$deviceId:home",
            name = "PI",
            cwd = "/remote/$deviceId/home",
            remote = RemoteBotRef(
                deviceId = deviceId,
                deviceName = deviceName,
                botId = "home",
                botName = "PI",
                online = online,
            ),
        )
        val offlinePhone = phoneBot("dev-off", "Aaron Tablet", online = false)
        val zetaPhone = phoneBot("dev-zeta", "Zeta Phone", online = true)
        val alphaPhone = phoneBot("dev-alpha", "Alpha Phone", online = true)
        val result = buildDrawerEntries(
            workspaces = listOf(local, offlinePhone, zetaPhone, alphaPhone),
            sessionsByCwd = mapOf(local.cwd to listOf(session("s1", 1))),
            expandedWorkspaceIds = setOf(local.id),
            expandedSessionWorkspaceIds = emptySet(),
            collapsedOnlineRemoteDeviceIds = setOf("dev-zeta"),
            canCreateRemoteBotDeviceIds = setOf("dev-alpha", "dev-zeta", "dev-off"),
        )

        val headers = result.filterIsInstance<DrawerEntry.RemoteDeviceHeader>()
        assertEquals(listOf("dev-alpha", "dev-zeta", "dev-off"), headers.map { it.deviceId })
        assertEquals(listOf(true, false, false), headers.map { it.expanded })
        assertEquals(listOf(true, true, false), headers.map { it.canCreateBot })
        assertEquals(
            listOf("home", "remote:dev-alpha:home"),
            result.filterIsInstance<DrawerEntry.WorkspaceEntry>().map { it.workspace.id },
        )
        assertEquals(result.size, result.map { it.key }.toSet().size)
    }

    @Test
    fun uniqueKeysForAllEntries() {
        val result = entries((1..7).map { session("s$it", it.toLong()) })
        assertEquals(result.size, result.map { it.key }.toSet().size)
    }

    @Test
    fun remoteSessionsSortPinnedFirstAndRenderArchivedToggleWhenManageable() {
        val remote = remoteWorkspace(online = true)
        val state = RemoteDrawerState(
            sessions = listOf(
                RemoteSessionSummaryDto(id = "r1", title = "Recent", updatedAt = 50L, pinned = false),
                RemoteSessionSummaryDto(id = "r2", title = "Pinned old", updatedAt = 10L, pinned = true),
                RemoteSessionSummaryDto(id = "r3", title = "Archived", updatedAt = 99L, pinned = false, archived = true),
            ),
            status = null,
        )
        val result = buildDrawerEntries(
            workspaces = listOf(remote),
            sessionsByCwd = emptyMap(),
            expandedWorkspaceIds = setOf(remote.id),
            expandedSessionWorkspaceIds = emptySet(),
            remoteDrawerStates = mapOf(remote.id to state),
            relayOnline = true,
            remoteSessionManageWorkspaceIds = setOf(remote.id),
            remoteShowArchivedWorkspaceIds = setOf(remote.id),
        )

        val rows = result.filterIsInstance<DrawerEntry.RemoteSession>()
        assertEquals(listOf("r2", "r1", "r3"), rows.map { it.session.id })
        val toggle = result.filterIsInstance<DrawerEntry.RemoteArchivedToggle>().single()
        assertTrue(toggle.showingArchived)
    }
}
