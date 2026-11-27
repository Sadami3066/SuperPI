package io.github.whywood.superpi.host.market

import com.sun.net.httpserver.HttpServer
import io.github.whywood.superpi.data.SkillRepository
import io.github.whywood.superpi.runtime.McpConfigStore
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostMarketServiceTest {
    private lateinit var tempDir: File
    private lateinit var skillRepo: SkillRepository
    private lateinit var mcpStore: McpConfigStore
    private lateinit var httpServer: HttpServer
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-market-test").toFile()
        val agentDir = File(tempDir, "agent").apply { mkdirs() }
        val repoDir = File(agentDir, "skills-repo").apply { mkdirs() }
        val homeDir = File(tempDir, "workspace").apply { mkdirs() }
        val workspacesDir = File(tempDir, "workspaces").apply { mkdirs() }
        skillRepo = SkillRepository(repoDir, agentDir, homeDir, workspacesDir)
        mcpStore = McpConfigStore(agentDir)
    }

    @After
    fun tearDown() {
        if (::httpServer.isInitialized) httpServer.stop(0)
        tempDir.deleteRecursively()
    }

    private fun signBytes(data: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(data)
        return Base64.getEncoder().encode(sig.sign())
    }

    private fun buildSkillZip(name: String, description: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("SKILL.md"))
            zos.write("---\nname: $name\ndescription: $description\n---\n\n# $name\nBody text.\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun filtersPluginsVerifiesSignaturesAndInstallsSkillAndMcp(): Unit = runBlocking {
        val skillZip = buildSkillZip("doc-helper", "Formats project documents.")
        val skillSha = sha256Hex(skillZip)

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = httpServer.address.port
        val registryJson = """
            {
              "schemaVersion": 3,
              "generatedAt": "2026-11-27T12:00:00Z",
              "keyId": "ec256:test",
              "entries": [
                {
                  "type": "plugin",
                  "id": "android-screen-plugin",
                  "name": {"zh": "手机插件", "en": "Phone Plugin"},
                  "summary": {"zh": "仅安卓", "en": "Android only"},
                  "description": {"zh": "desc"},
                  "version": "1.0.0",
                  "artifact": {
                    "url": "http://127.0.0.1:$port/plugin.ddp",
                    "sha256": "$skillSha",
                    "size": ${skillZip.size}
                  }
                },
                {
                  "type": "skill",
                  "id": "doc-helper",
                  "category": "office",
                  "name": {"zh": "文档助手", "en": "Doc Helper"},
                  "summary": {"zh": "整理文档", "en": "Format docs"},
                  "description": {"zh": "详细说明", "en": "Details"},
                  "version": "1.2.0",
                  "artifact": {
                    "url": "http://127.0.0.1:$port/doc-helper.zip",
                    "sha256": "$skillSha",
                    "size": ${skillZip.size}
                  }
                },
                {
                  "type": "mcp",
                  "id": "github-mcp",
                  "category": "dev",
                  "name": {"zh": "GitHub", "en": "GitHub"},
                  "summary": {"zh": "代码托管", "en": "Repo access"},
                  "description": {"zh": "连接仓库"},
                  "version": "1.0.0",
                  "mcp": {
                    "url": "https://api.githubcopilot.com/mcp/",
                    "transport": "http",
                    "auth": {
                      "type": "apiKey",
                      "header": "Authorization",
                      "prefix": "Bearer "
                    }
                  }
                }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val registrySig = signBytes(registryJson)

        httpServer.createContext("/registry.json") { ex ->
            ex.sendResponseHeaders(200, registryJson.size.toLong())
            ex.responseBody.use { it.write(registryJson) }
        }
        httpServer.createContext("/registry.json.sig") { ex ->
            ex.sendResponseHeaders(200, registrySig.size.toLong())
            ex.responseBody.use { it.write(registrySig) }
        }
        httpServer.createContext("/doc-helper.zip") { ex ->
            ex.sendResponseHeaders(200, skillZip.size.toLong())
            ex.responseBody.use { it.write(skillZip) }
        }
        httpServer.start()

        var reloaded = 0
        val service = HostMarketService(
            marketDir = File(tempDir, "market"),
            http = OkHttpClient(),
            skillRepository = skillRepo,
            mcpConfigStore = mcpStore,
            onInstalled = { reloaded += 1 },
            registryUrl = "http://127.0.0.1:$port/registry.json",
            publicKeyBase64 = publicKeyBase64,
            expectedKeyId = "ec256:test",
        )

        val catalog = service.listEntries()
        assertEquals(2, catalog.entries.size)
        assertFalse(catalog.entries.any { it.type == "plugin" || it.id == "android-screen-plugin" })

        val pluginErr = runCatching { service.install("android-screen-plugin") }.exceptionOrNull()
        assertTrue(pluginErr is IllegalArgumentException)

        val skillInstall = service.install("doc-helper")
        assertEquals("skill", skillInstall.type)
        assertEquals(1, reloaded)

        val missingKeyErr = runCatching { service.install("github-mcp", null) }.exceptionOrNull()
        assertTrue(missingKeyErr is IllegalArgumentException)

        val mcpInstall = service.install("github-mcp", "ghp_secret_token")
        assertEquals("mcp", mcpInstall.type)
        assertEquals("github-mcp", mcpInstall.installedName)
        val savedMcp = mcpStore.loadServers()["github-mcp"] as McpServerConfig.Http
        assertEquals("Bearer ghp_secret_token", savedMcp.headers["Authorization"])

        val afterInstall = service.listEntries()
        assertTrue(afterInstall.entries.all { it.installState == "installed" })
        assertTrue(HostMarketParser.isVersionNewer("1.3.0", "1.2.0"))
        assertFalse(HostMarketParser.isVersionNewer("1.2.0", "1.2.0"))
    }

    @Test
    fun rejectsTamperedRegistrySignature(): Unit = runBlocking {
        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = httpServer.address.port
        val body = """{"schemaVersion":3,"keyId":"ec256:test","entries":[]}""".toByteArray(Charsets.UTF_8)
        val validSig = signBytes(body)
        val tamperedBody = """{"schemaVersion":3,"keyId":"ec256:test","entries":[] }""".toByteArray(Charsets.UTF_8)

        httpServer.createContext("/registry.json") { ex ->
            ex.sendResponseHeaders(200, tamperedBody.size.toLong())
            ex.responseBody.use { it.write(tamperedBody) }
        }
        httpServer.createContext("/registry.json.sig") { ex ->
            ex.sendResponseHeaders(200, validSig.size.toLong())
            ex.responseBody.use { it.write(validSig) }
        }
        httpServer.start()

        val service = HostMarketService(
            marketDir = File(tempDir, "market"),
            http = OkHttpClient(),
            skillRepository = skillRepo,
            mcpConfigStore = mcpStore,
            registryUrl = "http://127.0.0.1:$port/registry.json",
            publicKeyBase64 = publicKeyBase64,
            expectedKeyId = "ec256:test",
        )
        val err = runCatching { service.listEntries() }.exceptionOrNull()
        assertTrue(err != null && err.message?.contains("signature", ignoreCase = true) == true)
    }
}
