package io.github.whywood.superpi.host.market

import io.github.whywood.superpi.data.SkillArchiveResult
import io.github.whywood.superpi.data.SkillRepository
import io.github.whywood.superpi.runtime.McpConfigStore
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

class HostMarketService(
    private val marketDir: File,
    private val http: OkHttpClient,
    private val skillRepository: SkillRepository,
    private val mcpConfigStore: McpConfigStore,
    private val onInstalled: suspend () -> Unit = {},
    private val registryUrl: String = HostMarketDefaults.REGISTRY_URL,
    private val publicKeyBase64: String = HostMarketDefaults.PUBLIC_KEY_DER_BASE64,
    private val expectedKeyId: String = HostMarketDefaults.KEY_ID,
) {
    private val registryFile = File(marketDir, "registry.json")
    private val signatureFile = File(marketDir, "registry.json.sig")
    private val metaFile = File(marketDir, "meta.json")
    private val skillVersionsFile = File(marketDir, "skills.json")
    private val downloadDir = File(marketDir, "download")

    private val refreshMutex = Mutex()
    private val installMutex = Mutex()

    suspend fun listEntries(
        typeFilter: String? = null,
        forceRefresh: Boolean = false,
    ): MarketCatalogResponse {
        val loaded = loadRegistry(forceRefresh)
        val filterType = when (typeFilter?.trim()?.lowercase()) {
            null, "" -> null
            "skill" -> HostMarketEntryType.Skill
            "mcp" -> HostMarketEntryType.Mcp
            else -> throw IllegalArgumentException("Unsupported market entry type: $typeFilter")
        }
        val desktopEntries = loaded.registry.entries.filter {
            it.type == HostMarketEntryType.Skill || it.type == HostMarketEntryType.Mcp
        }.let { list ->
            if (filterType == null) list else list.filter { it.type == filterType }
        }
        val skillVersions = withContext(Dispatchers.IO) { readSkillVersions() }
        val installedSkills = runCatching { skillRepository.list().getOrNull() }.getOrNull().orEmpty()
            .filter { it.id.isNotBlank() }
            .associate { it.id to (skillVersions[it.id] ?: "") }
        val installedMcp = runCatching { mcpConfigStore.loadServers() }.getOrDefault(emptyMap())
            .mapNotNull { (name, cfg) -> (cfg as? McpServerConfig.Http)?.let { name to it.url } }
            .toMap()

        val mapped = desktopEntries.map { entry ->
            toEntryDto(entry, installedSkills, installedMcp)
        }
        return MarketCatalogResponse(
            entries = mapped,
            source = loaded.source,
            fetchedAt = loaded.fetchedAt,
            stale = loaded.stale,
            warning = loaded.warning,
        )
    }

    suspend fun install(entryId: String, apiKey: String? = null): MarketInstallResponse = installMutex.withLock {
        val cleanId = entryId.trim()
        if (cleanId.isEmpty()) throw IllegalArgumentException("Missing entryId")
        val loaded = loadRegistry(forceRefresh = false)
        val entry = loaded.registry.entries.firstOrNull { it.id == cleanId }
            ?: throw NoSuchElementException("Market entry not found: $cleanId")
        when (entry.type) {
            HostMarketEntryType.Plugin ->
                throw IllegalArgumentException("Plugin entries are not supported on desktop")
            HostMarketEntryType.Skill -> installSkill(entry)
            HostMarketEntryType.Mcp -> installMcp(entry, apiKey)
        }
    }

    private suspend fun loadRegistry(forceRefresh: Boolean): LoadedRegistry = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            marketDir.mkdirs()
            val cached = readCached()
            val now = System.currentTimeMillis()
            val fresh = !forceRefresh && cached != null && (now - cached.fetchedAt) < HostMarketDefaults.CACHE_TTL_MS
            if (fresh) {
                return@withContext LoadedRegistry(
                    registry = cached.registry,
                    source = "cache",
                    fetchedAt = cached.fetchedAt,
                    stale = false,
                )
            }
            val fetched = runCatching { fetchRemote() }
            val network = fetched.getOrNull()
            if (network != null) {
                val ts = System.currentTimeMillis()
                saveCache(network, ts)
                return@withContext LoadedRegistry(
                    registry = network.registry,
                    source = "network",
                    fetchedAt = ts,
                    stale = false,
                )
            }
            val errMsg = fetched.exceptionOrNull()?.message ?: "Unable to fetch market catalog"
            if (cached != null) {
                return@withContext LoadedRegistry(
                    registry = cached.registry,
                    source = "cache",
                    fetchedAt = cached.fetchedAt,
                    stale = true,
                    warning = errMsg,
                )
            }
            throw IllegalStateException(errMsg)
        }
    }

    private suspend fun installSkill(entry: HostMarketEntry): MarketInstallResponse {
        val artifact = entry.artifact ?: throw IllegalStateException("Skill artifact is missing")
        val downloaded = withContext(Dispatchers.IO) {
            downloadVerified(artifact, HostMarketDefaults.MAX_SKILL_BYTES)
        }
        try {
            val bytes = withContext(Dispatchers.IO) { downloaded.readBytes() }
            val result = skillRepository.install(bytes, entry.id)
            if (result is SkillArchiveResult.Failure) {
                throw IllegalStateException("Skill archive failed: ${result.error.name}")
            }
            val installed = (result as SkillArchiveResult.Success).skill
            withContext(Dispatchers.IO) {
                skillRepository.syncActiveSkills()
                writeSkillVersion(installed.id, entry.version)
            }
            onInstalled()
            return MarketInstallResponse(
                ok = true,
                entryId = entry.id,
                type = "skill",
                installedName = installed.name,
                requiresOAuth = false,
            )
        } finally {
            runCatching { downloaded.delete() }
        }
    }

    private suspend fun installMcp(entry: HostMarketEntry, apiKey: String?): MarketInstallResponse {
        val meta = entry.mcp ?: throw IllegalStateException("MCP configuration is missing")
        if (meta.transport != "http") throw IllegalArgumentException("Unsupported MCP transport: ${meta.transport}")
        val headers = try {
            meta.resolvedHeaders(apiKey)
        } catch (err: IllegalStateException) {
            throw IllegalArgumentException(err.message ?: "Access key is required")
        }
        val servers = mcpConfigStore.loadServers()
        val existingName = servers.entries.firstOrNull { (_, cfg) ->
            (cfg as? McpServerConfig.Http)?.url == meta.url
        }?.key
        val name = existingName ?: uniqueMcpName(servers.keys, entry.id)
        mcpConfigStore.upsert(
            name,
            McpServerConfig.Http(
                url = meta.url,
                headers = headers,
                enabled = true,
            ),
        )
        onInstalled()
        return MarketInstallResponse(
            ok = true,
            entryId = entry.id,
            type = "mcp",
            installedName = name,
            requiresOAuth = meta.auth?.type == HostMarketMcpAuthType.OAuth,
        )
    }

    private fun downloadVerified(artifact: HostMarketArtifact, maxBytes: Long): File {
        downloadDir.mkdirs()
        val tmpFile = Files.createTempFile(downloadDir.toPath(), "skill-", ".zip.part").toFile()
        var deleteTemp = true
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(artifact.url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
                val body = response.body
                if (body.contentLength() > maxBytes) error("File exceeds size limit")
                tmpFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) error("File exceeds size limit")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(artifact.sha256, ignoreCase = true)) {
                error("File checksum verification failed")
            }
            if (tmpFile.length() != artifact.size) {
                error("File size verification failed")
            }
            deleteTemp = false
            return tmpFile
        } finally {
            if (deleteTemp) runCatching { tmpFile.delete() }
        }
    }

    private fun fetchRemote(): FetchedRegistry {
        val registryBytes = fetchBytes(registryUrl, HostMarketDefaults.MAX_REGISTRY_BYTES)
        val sigBytes = fetchBytes(registryUrl + HostMarketDefaults.SIGNATURE_SUFFIX, 4096)
        return verifyAndParse(registryBytes, sigBytes)
    }

    private fun fetchBytes(url: String, maxBytes: Long): ByteArray {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Market request failed (HTTP ${response.code})")
            val body = response.body
            if (body.contentLength() > maxBytes) error("Market response too large")
            val bytes = body.bytes()
            if (bytes.size > maxBytes) error("Market response too large")
            return bytes
        }
    }

    private fun verifyAndParse(registryBytes: ByteArray, signatureBytes: ByteArray): FetchedRegistry {
        val signatureDer = runCatching {
            HostMarketSignature.decodeSignatureText(signatureBytes.decodeToString())
        }.getOrElse { signatureBytes }
        val verified = runCatching {
            HostMarketSignature.verify(registryBytes, signatureDer, publicKeyBase64)
        }.getOrDefault(false)
        if (!verified) error("Market registry signature verification failed")
        val registry = HostMarketParser.parse(registryBytes)
        if (registry.keyId != expectedKeyId) error("Market registry key ID mismatch")
        return FetchedRegistry(registry, registryBytes, signatureBytes)
    }

    private fun readCached(): CachedRegistry? {
        if (!registryFile.isFile || !signatureFile.isFile) return null
        return runCatching {
            val registry = verifyAndParse(registryFile.readBytes(), signatureFile.readBytes()).registry
            val fetchedAt = runCatching {
                val root = Json.parseToJsonElement(metaFile.readText(Charsets.UTF_8)).jsonObject
                (root["fetchedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
            }.getOrDefault(0L)
            CachedRegistry(registry, fetchedAt)
        }.getOrNull()
    }

    private fun saveCache(fetched: FetchedRegistry, fetchedAt: Long) {
        marketDir.mkdirs()
        writeAtomically(registryFile, fetched.registryBytes)
        writeAtomically(signatureFile, fetched.signatureBytes)
        writeAtomically(metaFile, "{\"fetchedAt\":$fetchedAt}\n".toByteArray(Charsets.UTF_8))
    }

    private fun readSkillVersions(): Map<String, String> = runCatching {
        if (!skillVersionsFile.isFile) return emptyMap()
        Json.parseToJsonElement(skillVersionsFile.readText(Charsets.UTF_8)).jsonObject.mapNotNull { (id, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeSkillVersion(id: String, version: String) {
        runCatching {
            marketDir.mkdirs()
            val next = readSkillVersions() + (id to version)
            val obj = buildJsonObject {
                next.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }
            writeAtomically(skillVersionsFile, obj.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: marketDir
        parent.mkdirs()
        val tmp = Files.createTempFile(parent.toPath(), ".tmp-", ".part")
        try {
            tmp.toFile().writeBytes(bytes)
            try {
                Files.move(
                    tmp,
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun toEntryDto(
        entry: HostMarketEntry,
        installedSkills: Map<String, String>,
        installedMcp: Map<String, String>,
    ): MarketEntryDto {
        val typeWire = when (entry.type) {
            HostMarketEntryType.Skill -> "skill"
            HostMarketEntryType.Mcp -> "mcp"
            HostMarketEntryType.Plugin -> "plugin"
        }
        val (installState, installedVersion, installedName) = when (entry.type) {
            HostMarketEntryType.Skill -> {
                if (entry.id in installedSkills) {
                    val recordedVersion = installedSkills[entry.id].orEmpty().ifBlank { entry.version }
                    val state = if (HostMarketParser.isVersionNewer(entry.version, recordedVersion)) {
                        "update_available"
                    } else {
                        "installed"
                    }
                    Triple(state, recordedVersion, entry.id)
                } else {
                    Triple("not_installed", null, null)
                }
            }
            HostMarketEntryType.Mcp -> {
                val url = entry.mcp?.url
                val matchedName = if (url != null) {
                    installedMcp.entries.firstOrNull { (_, configuredUrl) -> configuredUrl == url }?.key
                } else {
                    null
                }
                if (matchedName != null) {
                    Triple("installed", entry.version, matchedName)
                } else {
                    Triple("not_installed", null, null)
                }
            }
            HostMarketEntryType.Plugin -> Triple("not_installed", null, null)
        }
        val mcpDto = entry.mcp?.let { m ->
            MarketMcpMetaDto(
                url = m.url,
                transport = m.transport,
                authHint = m.authHint?.values.orEmpty(),
                auth = m.auth?.let { a ->
                    MarketMcpAuthDto(
                        type = when (a.type) {
                            HostMarketMcpAuthType.ApiKey -> "apiKey"
                            HostMarketMcpAuthType.OAuth -> "oauth"
                            HostMarketMcpAuthType.None -> "none"
                        },
                        header = a.header,
                        prefix = a.prefix,
                        placeholder = a.placeholder?.values.orEmpty(),
                        helpUrl = a.helpUrl,
                    )
                },
            )
        }
        return MarketEntryDto(
            id = entry.id,
            type = typeWire,
            category = entry.category,
            name = entry.name.values,
            summary = entry.summary.values,
            description = entry.description.values,
            icon = entry.icon,
            tags = entry.tags,
            version = entry.version,
            license = entry.skill?.license,
            sourceUrl = entry.skill?.source,
            mcp = mcpDto,
            installState = installState,
            installedVersion = installedVersion,
            installedName = installedName,
        )
    }

    private fun uniqueMcpName(taken: Set<String>, id: String): String {
        if (id !in taken) return id
        var idx = 2
        while ("$id-$idx" in taken) idx += 1
        return "$id-$idx"
    }

    private data class CachedRegistry(val registry: HostMarketRegistry, val fetchedAt: Long)

    private data class FetchedRegistry(
        val registry: HostMarketRegistry,
        val registryBytes: ByteArray,
        val signatureBytes: ByteArray,
    )

    private data class LoadedRegistry(
        val registry: HostMarketRegistry,
        val source: String,
        val fetchedAt: Long,
        val stale: Boolean,
        val warning: String? = null,
    )
}
