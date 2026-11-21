package io.github.whywood.superpi.ui

import io.github.whywood.superpi.data.relay.RemoteSessionSummaryDto
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.RemoteBotRef
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDrawerEntriesTest {
    private val remoteWorkspace = RuntimeWorkspace(
        id = "remote:dev-1:bot-1",
        name = "Helper Bot",
        cwd = "/tmp/remote/dev-1/bot-1",
        remote = RemoteBotRef(
            deviceId = "dev-1",
            deviceName = "Backup Phone",
            botId = "bot-1",
            botName = "Helper Bot",
            online = true,
        ),
    )

    @Test
    fun rendersRemoteSessionsUpgradeNoticesAndLoadingState() {
        val loadingEntries = buildDrawerEntries(
            workspaces = listOf(remoteWorkspace),
            sessionsByCwd = emptyMap(),
            expandedWorkspaceIds = setOf(remoteWorkspace.id),
            expandedSessionWorkspaceIds = emptySet(),
            remoteDrawerStates = mapOf(remoteWorkspace.id to RemoteDrawerState(status = RemoteDrawerStatus.Loading)),
        )
        assertTrue(loadingEntries.any { it is DrawerEntry.RemoteLoading })

        val upgradeEntries = buildDrawerEntries(
            workspaces = listOf(remoteWorkspace),
            sessionsByCwd = emptyMap(),
            expandedWorkspaceIds = setOf(remoteWorkspace.id),
            expandedSessionWorkspaceIds = emptySet(),
            remoteDrawerStates = mapOf(
                remoteWorkspace.id to RemoteDrawerState(status = RemoteDrawerStatus.RelayUpgradeRequired),
            ),
        )
        val notice = upgradeEntries.filterIsInstance<DrawerEntry.RemoteNotice>().single()
        assertEquals(RemoteDrawerStatus.RelayUpgradeRequired, notice.status)

        val sessionEntries = buildDrawerEntries(
            workspaces = listOf(remoteWorkspace),
            sessionsByCwd = emptyMap(),
            expandedWorkspaceIds = setOf(remoteWorkspace.id),
            expandedSessionWorkspaceIds = emptySet(),
            remoteDrawerStates = mapOf(
                remoteWorkspace.id to RemoteDrawerState(
                    sessions = listOf(
                        RemoteSessionSummaryDto("s-1", "Session 1", 10L),
                        RemoteSessionSummaryDto("s-2", "Session 2", 20L),
                    ),
                ),
            ),
        )
        assertEquals(listOf("s-1", "s-2"), sessionEntries.filterIsInstance<DrawerEntry.RemoteSession>().map { it.session.id })
    }

    @Test
    fun fallsBackToLocalCachedMirrorSessionsWhenOffline() {
        val offlineWorkspace = remoteWorkspace.copy(
            remote = remoteWorkspace.remote!!.copy(online = false),
        )
        val cached = ConversationSummary("local-1", "Cached Chat", 50L, "/tmp/c.jsonl", offlineWorkspace.cwd)
        val entries = buildDrawerEntries(
            workspaces = listOf(offlineWorkspace),
            sessionsByCwd = mapOf(offlineWorkspace.cwd to listOf(cached)),
            expandedWorkspaceIds = setOf(offlineWorkspace.id),
            expandedSessionWorkspaceIds = emptySet(),
            expandedOfflineRemoteDeviceIds = setOf("dev-1"),
            remoteDrawerStates = mapOf(
                offlineWorkspace.id to RemoteDrawerState(
                    sessions = listOf(RemoteSessionSummaryDto("stale-1", "Stale", 10L)),
                ),
            ),
        )
        assertEquals(listOf("local-1"), entries.filterIsInstance<DrawerEntry.Session>().map { it.conversation.id })
        assertTrue(entries.none { it is DrawerEntry.RemoteSession })
    }
}
