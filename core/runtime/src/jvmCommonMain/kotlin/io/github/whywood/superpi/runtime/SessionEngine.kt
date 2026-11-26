package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig

interface SessionEngine {
    suspend fun create(file: String?, config: ModelConfig): SessionHandle
    suspend fun loadMcpServers(): Map<String, McpServerConfig>
    fun titleFor(text: String): String
}

interface SessionDirectory {
    suspend fun list(cwd: String): List<ConversationSummary>
    suspend fun listAll(cwds: List<String>): List<ConversationSummary>
    suspend fun find(cwds: List<String>, sessionId: String): ConversationSummary?
    suspend fun delete(filePath: String)
    suspend fun rename(id: String, title: String)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun setMemoryExcluded(id: String, excluded: Boolean) {}
    suspend fun isMemoryExcluded(id: String): Boolean = false
    suspend fun removeMeta(id: String)
}

data class HomeWorkspaceProfile(
    val name: String = AgentRuntimeHost.HOME_NAME,
    val avatar: String? = null,
    val intro: String? = null,
)

interface WorkspaceDirectory {
    suspend fun read(): List<RuntimeWorkspace>
    suspend fun add(workspace: RuntimeWorkspace)
    suspend fun remove(id: String)
    suspend fun readHomeDefaultModel(): ModelConfig? = null
    suspend fun updateHomeDefaultModel(model: ModelConfig?) = Unit
    suspend fun readHomeProfile(): HomeWorkspaceProfile = HomeWorkspaceProfile()
    suspend fun updateHomeProfile(profile: HomeWorkspaceProfile) = Unit
}
