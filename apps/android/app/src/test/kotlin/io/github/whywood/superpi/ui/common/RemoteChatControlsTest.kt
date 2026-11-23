package io.github.whywood.superpi.ui.common

import io.github.whywood.superpi.runtime.RemoteBotRef
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChatControlsTest {
    private val local = RuntimeWorkspace(id = "ws-1", name = "Work", cwd = "/work")

    private fun remote(online: Boolean) = RuntimeWorkspace(
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

    @Test
    fun localWorkspaceKeepsModelControlsAndInput() {
        val controls = remoteChatControls(listOf(local), local.id)

        assertNull(controls.remote)
        assertTrue(controls.inputEnabled)
        assertTrue(controls.modelControlsVisible)
    }

    @Test
    fun onlineRemoteWorkspaceHidesModelControlsByDefaultAndShowsWithCapability() {
        val workspace = remote(online = true)
        val defaultControls = remoteChatControls(listOf(local, workspace), workspace.id)

        assertEquals("Backup Phone", defaultControls.remote?.deviceName)
        assertTrue(defaultControls.inputEnabled)
        assertFalse(defaultControls.modelControlsVisible)

        val capableControls = remoteChatControls(listOf(local, workspace), workspace.id, canControlModel = true)
        assertTrue(capableControls.inputEnabled)
        assertTrue(capableControls.modelControlsVisible)
    }

    @Test
    fun offlineRemoteWorkspaceDisablesInputEvenWithCapability() {
        val workspace = remote(online = false)
        val controls = remoteChatControls(listOf(workspace), workspace.id, canControlModel = true)

        assertFalse(controls.inputEnabled)
        assertFalse(controls.modelControlsVisible)
    }

    @Test
    fun unknownWorkspaceFallsBackToLocalControls() {
        val controls = remoteChatControls(listOf(local), "missing")

        assertNull(controls.remote)
        assertTrue(controls.inputEnabled)
        assertTrue(controls.modelControlsVisible)
    }
}
