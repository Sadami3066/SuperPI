package io.github.whywood.superpi.data

import io.github.whywood.superpi.runtime.MemoryAccessScope
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class WorkspaceToolPolicy(
    val allCapabilities: Boolean = false,
    val skills: Set<String> = emptySet(),
    val plugins: Set<String> = emptySet(),
    val mcpServers: Set<String> = emptySet(),
    val screenTools: Boolean = false,
    val miniappTools: Boolean = false,
    val memoryScope: MemoryAccessScope? = null,
    val memoryDispatch: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = !allCapabilities && skills.isEmpty() && plugins.isEmpty() &&
            mcpServers.isEmpty() && !screenTools && !miniappTools &&
            memoryScope == null && memoryDispatch == null

    fun enabledSkills(installed: Set<String>): Set<String> = resolve(installed, skills)

    fun enabledPlugins(installed: Set<String>): Set<String> = resolve(installed, plugins)

    fun enabledMcpServers(installed: Set<String>): Set<String> = resolve(installed, mcpServers)

    fun effectiveMemoryScope(): MemoryAccessScope = memoryScope ?: MemoryAccessScope.PERSONAL

    fun effectiveMemoryDispatch(isHome: Boolean = allCapabilities): Boolean =
        if (effectiveMemoryScope() == MemoryAccessScope.OFF) false else (memoryDispatch ?: isHome)

    private fun resolve(installed: Set<String>, selection: Set<String>): Set<String> =
        if (allCapabilities) installed - selection else installed.intersect(selection)
}

class WorkspaceToolPolicyStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cache: Map<String, WorkspaceToolPolicy> = readFromDisk()

    private val _changes = MutableStateFlow(0L)

    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun policyForCwd(cwd: String): WorkspaceToolPolicy {
        if (cwd.isBlank()) return WorkspaceToolPolicy()
        return cache[key(cwd)] ?: WorkspaceToolPolicy()
    }

    suspend fun reload(): Map<String, WorkspaceToolPolicy> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val reloaded = readFromDisk()
            cache = reloaded
            _changes.value += 1
            reloaded
        }
    }

    suspend fun update(cwd: String, policy: WorkspaceToolPolicy) = withContext(Dispatchers.IO) {
        if (cwd.isBlank()) return@withContext
        mutex.withLock {
            val latest = readFromDisk()
            val next = if (policy.isEmpty) latest - key(cwd) else latest + (key(cwd) to policy)
            writeToDisk(next)
            cache = next
            _changes.value += 1
        }
    }

    private fun key(cwd: String): String =
        runCatching { File(cwd).canonicalPath }.getOrDefault(File(cwd).absolutePath)

    private fun readFromDisk(): Map<String, WorkspaceToolPolicy> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            buildMap {
                root.forEach { (cwd, element) ->
                    val item = element as? JsonObject ?: return@forEach
                    put(
                        cwd,
                        WorkspaceToolPolicy(
                            allCapabilities = (item["allCapabilities"] as? JsonPrimitive)?.booleanOrNull ?: false,
                            skills = item.stringSet("skills"),
                            plugins = item.stringSet("plugins"),
                            mcpServers = item.stringSet("mcpServers"),
                            screenTools = (item["screenTools"] as? JsonPrimitive)?.booleanOrNull ?: false,
                            miniappTools = (item["miniappTools"] as? JsonPrimitive)?.booleanOrNull ?: false,
                            memoryScope = (item["memoryScope"] as? JsonPrimitive)?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let { MemoryAccessScope.fromWire(it) },
                            memoryDispatch = (item["memoryDispatch"] as? JsonPrimitive)?.booleanOrNull,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeToDisk(policies: Map<String, WorkspaceToolPolicy>) {
        val root = buildJsonObject {
            policies.forEach { (cwd, policy) ->
                put(
                    cwd,
                    buildJsonObject {
                        put("allCapabilities", policy.allCapabilities)
                        put("skills", buildJsonArray { policy.skills.forEach { add(JsonPrimitive(it)) } })
                        put("plugins", buildJsonArray { policy.plugins.forEach { add(JsonPrimitive(it)) } })
                        put("mcpServers", buildJsonArray { policy.mcpServers.forEach { add(JsonPrimitive(it)) } })
                        put("screenTools", policy.screenTools)
                        put("miniappTools", policy.miniappTools)
                        policy.memoryScope?.let { put("memoryScope", it.wire) }
                        policy.memoryDispatch?.let { put("memoryDispatch", it) }
                    },
                )
            }
        }
        val parent = file.parentFile ?: File(".")
        parent.mkdirs()
        val tempPath = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp")
        val temp = tempPath.toFile()
        try {
            temp.writeText(json.encodeToString(JsonObject.serializer(), root), Charsets.UTF_8)
            try {
                Files.move(
                    tempPath,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }

    private fun JsonObject.stringSet(key: String): Set<String> {
        val array = this[key] as? JsonArray ?: return emptySet()
        return buildSet {
            for (element in array) {
                (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
