package io.github.whywood.superpi.host.config

import io.github.whywood.superpi.data.AgentDoc
import io.github.whywood.superpi.data.AgentDocStore
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.WorkspaceToolPolicyStore
import io.github.whywood.superpi.host.platform.PathLayout
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.HomeWorkspaceProfile
import io.github.whywood.superpi.runtime.JsonFileKeyValueStore
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.WorkspaceStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ResolvedBotContext(
    val workspace: RuntimeWorkspace?,
    val effectiveCwd: String,
    val isRegisteredBot: Boolean,
    val isHome: Boolean,
    val policy: WorkspaceToolPolicy,
    val agentDoc: AgentDoc?,
)

/**
 * Unifies SuperPI's Bot paradigm across CLI, Relay, and local Web/Tauri:
 * - Always provides a default Home bot (~/.super-pi/workspaces/default).
 * - Registered bots bind arbitrary local project directories in bots.json + bot-policies.json.
 * - Unregistered ephemeral directories run cleanly without mutating bots.json.
 */
class BotRegistry(
    private val paths: PathLayout,
    private val botsKvStore: JsonFileKeyValueStore = JsonFileKeyValueStore(paths.botsFile),
    val workspaceStore: WorkspaceStore = WorkspaceStore(botsKvStore),
    val policyStore: WorkspaceToolPolicyStore = WorkspaceToolPolicyStore(paths.botPoliciesFile),
    val agentDocStore: AgentDocStore = AgentDocStore(),
    private val settingsStore: HostSettingsStore,
) {
    @Volatile
    private var cachedBots: List<RuntimeWorkspace> = emptyList()
    private val mutationMutex = Mutex()

    suspend fun reload() {
        botsKvStore.reload()
        policyStore.reload()
        listAllBots()
    }

    suspend fun ensureDefaultHomeBot() {
        val homeDir = paths.defaultWorkspaceDir
        if (!homeDir.exists()) homeDir.mkdirs()
        val agentsFile = File(homeDir, "AGENTS.md")
        if (!agentsFile.exists()) {
            agentsFile.writeText(DEFAULT_HOME_AGENTS_MD, Charsets.UTF_8)
        }
        listAllBots()
    }

    suspend fun homeBot(): RuntimeWorkspace {
        val profile = workspaceStore.readHomeProfile()
        val homeModel = workspaceStore.readHomeDefaultModel()
        return RuntimeWorkspace(
            id = AgentRuntimeHost.HOME_ID,
            name = profile.name.ifBlank { AgentRuntimeHost.HOME_NAME },
            cwd = paths.normalizePath(paths.defaultWorkspaceDir.absolutePath),
            createdAt = 0L,
            isHome = true,
            avatar = profile.avatar,
            intro = profile.intro,
            defaultModel = homeModel,
        )
    }

    suspend fun listAllBots(): List<RuntimeWorkspace> {
        val home = homeBot()
        val extras = workspaceStore.read()
        return (listOf(home) + extras).also { cachedBots = it }
    }

    suspend fun findBotById(id: String): RuntimeWorkspace? {
        val needle = id.trim()
        if (needle.isEmpty()) return null
        return listAllBots().firstOrNull { it.id.equals(needle, ignoreCase = true) }
    }

    suspend fun findBot(idOrNameOrPath: String): RuntimeWorkspace? {
        val needle = idOrNameOrPath.trim()
        if (needle.isEmpty()) return null
        val all = listAllBots()
        all.firstOrNull { it.id.equals(needle, ignoreCase = true) }?.let { return it }
        all.firstOrNull { it.name.equals(needle, ignoreCase = true) }?.let { return it }
        val canonicalNeedle = runCatching { paths.normalizePath(needle) }.getOrNull()
        if (canonicalNeedle != null) {
            return all.firstOrNull { paths.normalizePath(it.cwd) == canonicalNeedle }
        }
        return null
    }

    suspend fun addBot(
        directory: File?,
        name: String? = null,
        defaultModel: ModelConfig? = null,
        intro: String? = null,
        avatar: String? = null,
    ): RuntimeWorkspace = mutationMutex.withLock {
        val cleanAvatar = avatar?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= MAX_AVATAR_CHARS) { "Avatar exceeds maximum size." }
        }
        val existing = workspaceStore.read()
        val resolvedName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: directory?.name?.ifBlank { null }
            ?: "Bot"
        require(resolvedName.length <= 40) { "Bot name must be at most 40 characters." }

        val baseId = sanitizeBotId(resolvedName)
        val usedIds = existing.map { it.id }.toSet() + AgentRuntimeHost.HOME_ID
        var id = baseId
        var suffix = 2
        while (id in usedIds) {
            id = "$baseId-$suffix"
            suffix++
        }

        val targetDir = if (directory != null) {
            require(directory.isDirectory) { "Directory does not exist: ${directory.absolutePath}" }
            directory
        } else {
            File(paths.workspacesDir, id).apply { mkdirs() }
        }

        val canonical = paths.normalizePath(targetDir.absolutePath)
        val homeCanonical = paths.normalizePath(paths.defaultWorkspaceDir.absolutePath)
        require(canonical != homeCanonical) { "The default Home workspace is already registered." }

        existing.firstOrNull { paths.normalizePath(it.cwd) == canonical }?.let { dup ->
            throw IllegalArgumentException("Directory is already registered as bot '${dup.name}' (${dup.id}).")
        }

        val workspace = RuntimeWorkspace(
            id = id,
            name = resolvedName,
            cwd = canonical,
            createdAt = System.currentTimeMillis(),
            isHome = false,
            avatar = cleanAvatar,
            intro = intro?.trim()?.take(200)?.takeIf { it.isNotEmpty() },
            defaultModel = defaultModel,
        )
        workspaceStore.add(workspace)
        policyStore.update(canonical, WorkspaceToolPolicy(allCapabilities = true))
        listAllBots()
        workspace
    }

    suspend fun updateBot(
        idOrName: String,
        name: String? = null,
        intro: String? = null,
        clearIntro: Boolean = false,
        avatar: String? = null,
        clearAvatar: Boolean = false,
        defaultModel: ModelConfig? = null,
        updateDefaultModel: Boolean = false,
        byIdOnly: Boolean = false,
    ): RuntimeWorkspace? = mutationMutex.withLock {
        val target = (if (byIdOnly) findBotById(idOrName) else findBot(idOrName)) ?: return@withLock null
        val nextName = name?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= 40) { "Bot name must be at most 40 characters." }
        } ?: target.name
        val nextIntro = when {
            clearIntro -> null
            intro != null -> intro.trim().take(200).takeIf { it.isNotEmpty() }
            else -> target.intro
        }
        val nextAvatar = when {
            clearAvatar -> null
            avatar != null -> avatar.trim().takeIf { it.isNotEmpty() }?.also {
                require(it.length <= MAX_AVATAR_CHARS) { "Avatar exceeds maximum size." }
            }
            else -> target.avatar
        }
        if (target.isHome) {
            workspaceStore.updateHomeProfile(
                HomeWorkspaceProfile(
                    name = nextName,
                    avatar = nextAvatar,
                    intro = nextIntro,
                ),
            )
            if (updateDefaultModel) {
                workspaceStore.updateHomeDefaultModel(defaultModel)
            }
            return@withLock homeBot().also { listAllBots() }
        }
        val updated = target.copy(
            name = nextName,
            intro = nextIntro,
            avatar = nextAvatar,
            defaultModel = if (updateDefaultModel) defaultModel else target.defaultModel,
        )
        workspaceStore.add(updated)
        listAllBots()
        updated
    }

    suspend fun removeBot(idOrNameOrPath: String, byIdOnly: Boolean = false): RuntimeWorkspace? = mutationMutex.withLock {
        val target = (if (byIdOnly) findBotById(idOrNameOrPath) else findBot(idOrNameOrPath)) ?: return@withLock null
        require(!target.isHome) { "The default Home bot cannot be removed." }
        workspaceStore.remove(target.id)
        policyStore.update(target.cwd, WorkspaceToolPolicy())
        listAllBots()
        target
    }

    suspend fun setBotDefaultModel(idOrName: String, model: ModelConfig?): RuntimeWorkspace? = mutationMutex.withLock {
        val target = findBot(idOrName) ?: return@withLock null
        if (target.isHome) {
            workspaceStore.updateHomeDefaultModel(model)
            return@withLock homeBot().also { listAllBots() }
        }
        val updated = target.copy(defaultModel = model)
        workspaceStore.add(updated)
        listAllBots()
        updated
    }

    fun resolvePolicySync(cwd: String?): Pair<WorkspaceToolPolicy, Boolean> {
        val rawCwd = cwd?.trim().orEmpty()
        if (rawCwd.isEmpty()) {
            return WorkspaceToolPolicy(allCapabilities = true, memoryScope = MemoryAccessScope.PERSONAL) to false
        }
        val canonicalCwd = paths.normalizePath(rawCwd)
        val homeCanonical = paths.normalizePath(paths.defaultWorkspaceDir.absolutePath)
        val candidates = if (cachedBots.isNotEmpty()) {
            cachedBots
        } else {
            listOf(
                RuntimeWorkspace(
                    id = AgentRuntimeHost.HOME_ID,
                    name = AgentRuntimeHost.HOME_NAME,
                    cwd = homeCanonical,
                    createdAt = 0L,
                    isHome = true,
                ),
            )
        }
        val matched = candidates
            .map { ws -> ws to paths.normalizePath(ws.cwd) }
            .filter { (_, wsCwd) ->
                canonicalCwd == wsCwd || canonicalCwd.startsWith(wsCwd.trimEnd(File.separatorChar) + File.separatorChar)
            }
            .maxByOrNull { (_, wsCwd) -> wsCwd.length }
            ?.first
            ?: return WorkspaceToolPolicy(allCapabilities = true, memoryScope = MemoryAccessScope.PERSONAL) to false
        val wsCanonical = paths.normalizePath(matched.cwd)
        val stored = policyStore.policyForCwd(wsCanonical)
        val effective = if (matched.isHome) {
            stored.copy(allCapabilities = true)
        } else {
            stored
        }
        return effective to matched.isHome
    }

    suspend fun policyForCwd(cwd: String): WorkspaceToolPolicy {
        if (cwd.isBlank()) return WorkspaceToolPolicy(allCapabilities = true)
        val resolved = resolveForCwd(File(cwd))
        return resolved.policy
    }

    /**
     * Resolves [cwd] against registered bots by exact canonical match or longest registered ancestor.
     * Unregistered directories return an ephemeral context with global defaults (never polluting bots.json).
     */
    suspend fun resolveForCwd(cwd: File): ResolvedBotContext {
        val canonicalCwd = paths.normalizePath(cwd.absolutePath)
        val all = listAllBots()
        val matched = all
            .map { ws -> ws to paths.normalizePath(ws.cwd) }
            .filter { (_, wsCwd) ->
                canonicalCwd == wsCwd || canonicalCwd.startsWith(wsCwd.trimEnd(File.separatorChar) + File.separatorChar)
            }
            .maxByOrNull { (_, wsCwd) -> wsCwd.length }
            ?.first

        val policy = if (matched != null) {
            val wsCanonical = paths.normalizePath(matched.cwd)
            val stored = policyStore.policyForCwd(wsCanonical)
            if (matched.isHome) {
                stored.copy(allCapabilities = true)
            } else {
                stored
            }
        } else {
            WorkspaceToolPolicy(
                allCapabilities = true,
                memoryScope = MemoryAccessScope.PERSONAL,
            )
        }

        val doc = agentDocStore.read(canonicalCwd)
            ?: matched?.let { agentDocStore.read(it.cwd) }

        return ResolvedBotContext(
            workspace = matched,
            effectiveCwd = canonicalCwd,
            isRegisteredBot = matched != null,
            isHome = matched?.isHome == true,
            policy = policy,
            agentDoc = doc,
        )
    }

    suspend fun effectiveDefaultModel(cwd: String): ModelConfig {
        val resolved = resolveForCwd(File(cwd))
        return resolved.workspace?.defaultModel ?: settingsStore.read()
    }

    private fun sanitizeBotId(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .take(36)
        return cleaned.ifEmpty { "bot-" + UUID.randomUUID().toString().take(8) }
    }

    private companion object {
        const val MAX_AVATAR_CHARS = 128 * 1024
        val DEFAULT_HOME_AGENTS_MD = """
            # Default SuperPI Home Bot

            This is the default Home bot workspace for `super-pi` on this machine.
            Use `super-pi bot add <dir>` to mount any local repository or folder as a dedicated bot.
        """.trimIndent() + "\n"
    }
}
