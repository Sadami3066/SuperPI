package io.github.whywood.superpi.ui.common

import io.github.whywood.superpi.runtime.RuntimeWorkspace

internal enum class WorkspaceMenuAction {
    NEW_SESSION,
    FILES,
    INSTRUCTIONS,
    CAPABILITIES,
    EDIT_REMOTE,
    RENAME,
    PIN,
    SETTINGS,
    DELETE,
}

internal data class WorkspaceRowMeta(
    val selectable: Boolean,
    val canCreateSession: Boolean,
    val manageable: Boolean,
    val sourceDevice: String?,
    val offline: Boolean,
)

internal fun workspaceRowMeta(workspace: RuntimeWorkspace): WorkspaceRowMeta {
    val remote = workspace.remote
    return WorkspaceRowMeta(
        selectable = true,
        canCreateSession = remote?.online != false,
        manageable = remote == null,
        sourceDevice = remote?.deviceName,
        offline = remote != null && !remote.online,
    )
}

internal fun workspaceMenuActions(
    workspace: RuntimeWorkspace,
    supportsBotManage: Boolean = false,
    supportsWorkspaceFiles: Boolean = false,
    supportsBotControls: Boolean = false,
    supportsBotCapabilities: Boolean = false,
): List<WorkspaceMenuAction> {
    val meta = workspaceRowMeta(workspace)
    val remote = workspace.remote
    val isProtectedDefault = remote?.botId == "home"
    return buildList {
        if (meta.canCreateSession) add(WorkspaceMenuAction.NEW_SESSION)
        if (remote != null && remote.online && supportsWorkspaceFiles) {
            add(WorkspaceMenuAction.FILES)
        }
        if (remote != null && remote.online && supportsBotControls) {
            add(WorkspaceMenuAction.INSTRUCTIONS)
        }
        if (remote != null && remote.online && supportsBotCapabilities) {
            add(WorkspaceMenuAction.CAPABILITIES)
        }
        if (remote != null && remote.online && supportsBotManage) {
            add(WorkspaceMenuAction.EDIT_REMOTE)
        }
        if (meta.manageable) add(WorkspaceMenuAction.RENAME)
        add(WorkspaceMenuAction.PIN)
        if (meta.manageable) add(WorkspaceMenuAction.SETTINGS)
        val canDeleteRemote = remote != null && remote.online && supportsBotManage && !isProtectedDefault
        if ((!workspace.isHome && meta.manageable) || canDeleteRemote) {
            add(WorkspaceMenuAction.DELETE)
        }
    }
}

internal fun preferredNewChatWorkspaceId(workspaces: List<RuntimeWorkspace>, activeWorkspaceId: String): String? {
    val active = workspaces.firstOrNull { workspace ->
        workspace.id == activeWorkspaceId && workspaceRowMeta(workspace).canCreateSession
    }
    return (active ?: workspaces.firstOrNull { workspace -> workspaceRowMeta(workspace).canCreateSession })?.id
}
