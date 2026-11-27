package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.platform.LinuxHostPlatform
import io.github.whywood.superpi.host.platform.PathLayout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalControlServerApiTest {
    private lateinit var tempDir: File
    private lateinit var controller: SuperPiController
    private lateinit var server: LocalControlServer
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-api-test").toFile()
        val home = File(tempDir, ".super-pi")
        controller = SuperPiController(LinuxHostPlatform(PathLayout(home)))
        runBlocking { controller.bootstrap() }
        server = LocalControlServer(controller = controller, requestedPort = 0)
    }

    @After
    fun tearDown() {
        server.stop()
        runBlocking { controller.shutdown() }
        tempDir.deleteRecursively()
    }

    private fun authReq(port: Int, path: String): Request.Builder =
        Request.Builder()
            .url("http://127.0.0.1:$port$path")
            .header("Authorization", "Bearer ${server.bearerToken}")

    @Test
    fun enforcesStrictOriginCorsAndServesStaticRootWithoutAuth() = runBlocking {
        val port = server.start()

        // Static UI root is accessible without Bearer
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("<html", ignoreCase = true))
        }

        // Normalized / encoded API path bypasses MUST be rejected with 401
        http.newCall(Request.Builder().url("http://127.0.0.1:$port//api/v1/status").get().build()).execute().use { resp ->
            assertEquals(401, resp.code)
        }
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/%61pi/v1/status").get().build()).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // DNS rebinding Host header rejected with 403
        val rebindReq = authReq(port, "/api/v1/health")
            .header("Host", "evil.example.com")
            .get()
            .build()
        http.newCall(rebindReq).execute().use { resp ->
            assertEquals(403, resp.code)
        }

        // Malformed JSON returns 400 with JSON error body (not 500)
        val malformedReq = authReq(port, "/api/v1/bots")
            .post("{not valid json".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(malformedReq).execute().use { resp ->
            assertEquals(400, resp.code)
            assertTrue(resp.body.string().contains("error"))
        }

        // Evil origin rejected with 403
        val evilReq = authReq(port, "/api/v1/health")
            .header("Origin", "https://evil.example.com")
            .get()
            .build()
        http.newCall(evilReq).execute().use { resp ->
            assertEquals(403, resp.code)
        }

        val electronReq = authReq(port, "/api/v1/health")
            .header("Origin", "superpi://app")
            .get()
            .build()
        http.newCall(electronReq).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("superpi://app", resp.header("Access-Control-Allow-Origin"))
        }

        // Localhost preflight OPTIONS succeeds with 204
        val optReq = Request.Builder()
            .url("http://127.0.0.1:$port/api/v1/status")
            .header("Origin", "http://127.0.0.1:5173")
            .method("OPTIONS", null)
            .build()
        http.newCall(optReq).execute().use { resp ->
            assertEquals(204, resp.code)
            assertEquals("http://127.0.0.1:5173", resp.header("Access-Control-Allow-Origin"))
        }
    }

    @Test
    fun workspaceFilesGuardBlocksTraversalGitAndEscapingSymlinks() = runBlocking {
        val port = server.start()
        val outsideSecret = File(tempDir, "outside.txt").apply { writeText("TOP_SECRET") }
        val repoDir = File(tempDir, "proj").apply { mkdirs() }
        File(repoDir, "README.md").writeText("# Hello Workspace\n")
        File(repoDir, ".git").apply { mkdirs(); File(this, "config").writeText("[core]\n") }
        Files.createSymbolicLink(File(repoDir, "escape-link.txt").toPath(), outsideSecret.toPath())

        val bot = controller.botRegistry.addBot(repoDir, name = "Proj")

        // List root excludes .git and escaping symlink
        http.newCall(authReq(port, "/api/v1/bots/${bot.id}/files").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body.string()
            assertTrue(body.contains("README.md"))
            assertFalse(body.contains(".git"))
            assertFalse(body.contains("escape-link.txt"))
        }

        // Read allowed file
        http.newCall(authReq(port, "/api/v1/bots/${bot.id}/files/read?path=README.md").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("Hello Workspace"))
        }

        // Traversal -> 403
        http.newCall(authReq(port, "/api/v1/bots/${bot.id}/files/read?path=../outside.txt").get().build()).execute().use { resp ->
            assertEquals(403, resp.code)
        }

        // .git -> 403
        http.newCall(authReq(port, "/api/v1/bots/${bot.id}/files/read?path=.git/config").get().build()).execute().use { resp ->
            assertEquals(403, resp.code)
        }

        // Symlink outside root -> 403
        http.newCall(authReq(port, "/api/v1/bots/${bot.id}/files/read?path=escape-link.txt").get().build()).execute().use { resp ->
            assertEquals(403, resp.code)
        }
    }

    @Test
    fun sseTicketIsSingleUseAndProvidersMaskApiKeys() = runBlocking {
        val port = server.start()

        // Issue single-use SSE ticket
        val ticketJson = http.newCall(
            authReq(port, "/api/v1/auth/sse-ticket")
                .post("".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject
        }
        val ticket = ticketJson["ticket"]!!.jsonPrimitive.content
        assertTrue(ticket.length >= 16)

        // First use opens SSE stream and emits ready event
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/api/v1/events?ticket=$ticket").get().build())
            .execute().use { resp ->
                assertEquals(200, resp.code)
                val firstLine = resp.body.source().readUtf8Line()
                assertEquals("event: ready", firstLine)
            }

        // Second use of same ticket is rejected with 401
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/api/v1/events?ticket=$ticket").get().build())
            .execute().use { resp ->
                assertEquals(401, resp.code)
            }

        // Save provider API key -> GET /api/v1/providers masks it and never leaks raw key
        val fakeKey = "sk-test-supersecret-9876543210"
        http.newCall(
            authReq(port, "/api/v1/providers/openai/key")
                .put("""{"apiKey":"$fakeKey"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        http.newCall(authReq(port, "/api/v1/providers").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body.string()
            assertFalse("Raw API key must never be returned by GET /api/v1/providers", body.contains(fakeKey))
            assertTrue(body.contains("sk-t••••3210"))
        }

        // Session lifecycle + Memory snapshot + Settings + Doctor
        val sessionId = http.newCall(
            authReq(port, "/api/v1/bots/home/sessions")
                .post("".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["sessionId"]!!.jsonPrimitive.content
        }
        http.newCall(authReq(port, "/api/v1/sessions/$sessionId").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains(sessionId))
        }
        http.newCall(authReq(port, "/api/v1/memory/snapshot").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertTrue(obj["blocks"]!!.jsonArray.isNotEmpty())
        }
        http.newCall(authReq(port, "/api/v1/doctor").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("checks"))
        }
    }

    @Test
    fun botLifecycleAgentsDocPolicyAndProviderCatalogAreComplete() = runBlocking {
        val port = server.start()

        // Create bot without dir -> managed workspace dir
        val createdBot = http.newCall(
            authReq(port, "/api/v1/bots")
                .post("""{"name":"ResearchBot","intro":"Helps research"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject
        }
        val botId = createdBot["id"]!!.jsonPrimitive.content
        assertEquals("ResearchBot", createdBot["name"]!!.jsonPrimitive.content)

        // Patch bot name & intro
        http.newCall(
            authReq(port, "/api/v1/bots/$botId")
                .patch("""{"name":"Researcher","intro":"Updated intro"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body.string()
            assertTrue(body.contains("Researcher"))
            assertTrue(body.contains("Updated intro"))
        }

        // Write, read, delete AGENTS.md
        http.newCall(
            authReq(port, "/api/v1/bots/$botId/agents-doc")
                .put("""{"content":"# Instructions\nBe concise."}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("Be concise."))
        }
        http.newCall(authReq(port, "/api/v1/bots/$botId/agents-doc").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("Be concise."))
        }
        http.newCall(authReq(port, "/api/v1/bots/$botId/agents-doc").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // Update bot policy (memoryScope + memoryDispatch)
        http.newCall(
            authReq(port, "/api/v1/bots/$botId/policy")
                .put("""{"memoryScope":"work_only","memoryDispatch":true,"allCapabilities":false}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertEquals("work_only", obj["memoryScope"]!!.jsonPrimitive.content)
            assertEquals("true", obj["memoryDispatch"]!!.jsonPrimitive.content)
            assertEquals("false", obj["allCapabilities"]!!.jsonPrimitive.content)
        }

        // Providers catalog returns all opencode-go models without truncation
        http.newCall(authReq(port, "/api/v1/providers").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val providers = json.parseToJsonElement(resp.body.string()).jsonArray
            val opencodeGo = providers.firstOrNull {
                it.jsonObject["id"]?.jsonPrimitive?.content == "opencode-go"
            }?.jsonObject
            assertTrue("opencode-go should exist in catalog", opencodeGo != null)
            assertTrue(
                "opencode-go must return all models without truncation",
                opencodeGo!!["models"]!!.jsonArray.size >= 20,
            )
        }

        // Settings patch supports language, sendKey, chatFontScale
        http.newCall(
            authReq(port, "/api/v1/settings")
                .patch("""{"language":"en","sendKey":"mod_enter","chatFontScale":115,"compactionTriggerPercent":70}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertEquals("en", obj["language"]!!.jsonPrimitive.content)
            assertEquals("mod_enter", obj["sendKey"]!!.jsonPrimitive.content)
            assertEquals("115", obj["chatFontScale"]!!.jsonPrimitive.content)
            assertEquals("70", obj["compactionTriggerPercent"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun milestone2RoutesSupportCommandsArchivedExportQuestionsRelayAllowRemoteAndSuperTeam() = runBlocking {
        val port = server.start()

        // Slash commands for home bot
        http.newCall(authReq(port, "/api/v1/bots/home/commands").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val arr = json.parseToJsonElement(resp.body.string()).jsonArray
            val names = arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue("/compact must be included", "compact" in names)
            assertTrue("/goal must be included", "goal" in names)
        }

        // Bot models endpoint returns valid structure
        http.newCall(authReq(port, "/api/v1/bots/home/models").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("providers"))
        }

        // Create session, persist a real message header+turn line, archive it, list archived, export jsonl
        val createdSession = http.newCall(
            authReq(port, "/api/v1/bots/home/sessions")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject
        }
        val sessionId = createdSession["sessionId"]!!.jsonPrimitive.content
        val sessionFilePath = createdSession["filePath"]!!.jsonPrimitive.content
        val sessionFile = File(sessionFilePath)
        sessionFile.parentFile?.mkdirs()
        if (!sessionFile.exists() || sessionFile.length() == 0L) {
            val cwd = createdSession["cwd"]!!.jsonPrimitive.content.replace("\\", "\\\\")
            sessionFile.writeText(
                """{"type":"session","version":3,"id":"$sessionId","timestamp":"2026-11-26T00:00:00Z","cwd":"$cwd"}""" + "\n" +
                    """{"type":"message","id":"m1","parentId":null,"timestamp":"2026-11-26T00:00:01Z","message":{"role":"user","content":"hello"}}""" + "\n",
                Charsets.UTF_8,
            )
        }

        http.newCall(
            authReq(port, "/api/v1/sessions/$sessionId")
                .patch("""{"pinned":true,"archived":true}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        http.newCall(authReq(port, "/api/v1/sessions/archived").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val archived = json.parseToJsonElement(resp.body.string()).jsonArray
            assertTrue("Archived session must appear in /v1/sessions/archived", archived.any {
                it.jsonObject["id"]!!.jsonPrimitive.content == sessionId
            })
        }

        // Export jsonl
        http.newCall(authReq(port, "/api/v1/sessions/$sessionId/export").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.header("Content-Disposition").orEmpty().contains(".jsonl"))
        }

        // Queue delete and invalid image MIME rejection
        http.newCall(authReq(port, "/api/v1/sessions/$sessionId/queue").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        http.newCall(
            authReq(port, "/api/v1/sessions/$sessionId/prompt")
                .post("""{"text":"hi","images":[{"data":"AAAA","mimeType":"application/x-executable"}]}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(400, resp.code)
        }

        // Relay allow-remote toggle + devices endpoint + questions endpoint
        http.newCall(
            authReq(port, "/api/v1/relay/allow-remote")
                .put("""{"allowRemote":false}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertEquals("false", obj["allowRemote"]!!.jsonPrimitive.content)
        }
        http.newCall(authReq(port, "/api/v1/relay/devices").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        http.newCall(authReq(port, "/api/v1/questions").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // Super PI team & settings
        http.newCall(authReq(port, "/api/v1/super/team").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val arr = json.parseToJsonElement(resp.body.string()).jsonArray
            assertTrue("Team should contain at least home bot", arr.isNotEmpty())
        }
        http.newCall(
            authReq(port, "/api/v1/super/settings")
                .put("""{"maxConcurrentWorkers":4,"workerMaxSlotsPerBot":2,"workerCompactionOverrideEnabled":true,"workerCompactionTriggerPercent":72}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertEquals("4", obj["maxConcurrentWorkers"]!!.jsonPrimitive.content)
            assertEquals("2", obj["workerMaxSlotsPerBot"]!!.jsonPrimitive.content)
            assertEquals("72", obj["workerCompactionTriggerPercent"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun milestone3RoutesSupportCustomProvidersWebSearchMemoryCrudMcpSkillsAndTimers() = runBlocking {
        val port = server.start()

        // Custom provider CRUD
        val createdCustom = http.newCall(
            authReq(port, "/api/v1/providers/custom")
                .post(
                    """{"name":"My Local LLM","api":"openai-completions","baseUrl":"http://127.0.0.1:11434/v1","apiKey":"sk-local-test-1234","models":[{"id":"qwen3-coder","name":"Qwen3 Coder","reasoning":true,"thinkingLevels":["OFF","MEDIUM","HIGH"]}]}"""
                        .toRequestBody("application/json".toMediaType()),
                )
                .build(),
        ).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject
        }
        val customId = createdCustom["id"]!!.jsonPrimitive.content
        assertTrue(createdCustom["keyHint"]!!.jsonPrimitive.content.contains("••"))

        http.newCall(authReq(port, "/api/v1/providers/custom").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains(customId))
        }
        http.newCall(authReq(port, "/api/v1/providers/custom/$customId").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // Web search key save & list & delete
        http.newCall(
            authReq(port, "/api/v1/web-search/tavily")
                .put("""{"apiKey":"tvly-test-key-9999"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        http.newCall(authReq(port, "/api/v1/web-search").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body.string()
            assertTrue(body.contains("tavily"))
            assertTrue(body.contains("9999"))
        }
        http.newCall(authReq(port, "/api/v1/web-search/tavily").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // Memory v3 full CRUD + Preferences + Export
        val blockId = http.newCall(
            authReq(port, "/api/v1/memory/blocks")
                .post("""{"title":"Work Style","purpose":"Preferences","content":"Prefers concise diffs","domain":"WORK"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["id"]!!.jsonPrimitive.content
        }
        val eventId = http.newCall(
            authReq(port, "/api/v1/memory/events")
                .post("""{"eventKind":"PREFERENCE","content":"Uses dark mode at night","tags":["ui"]}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["id"]!!.jsonPrimitive.content
        }
        http.newCall(
            authReq(port, "/api/v1/memory/preferences")
                .put("""{"mode":"PAUSED","injectionMaxChars":20000,"guidance":"Focus on coding preferences"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertEquals("PAUSED", obj["mode"]!!.jsonPrimitive.content)
            assertEquals("20000", obj["injectionMaxChars"]!!.jsonPrimitive.content)
        }
        http.newCall(authReq(port, "/api/v1/memory/export").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("Prefers concise diffs"))
        }
        http.newCall(authReq(port, "/api/v1/memory/events/$eventId").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        http.newCall(authReq(port, "/api/v1/memory/blocks/$blockId").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // MCP CRUD + Skills list
        http.newCall(
            authReq(port, "/api/v1/mcp/demo-http")
                .put("""{"transport":"http","url":"https://example.com/mcp","enabled":true}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        http.newCall(authReq(port, "/api/v1/mcp").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body.string().contains("demo-http"))
        }
        http.newCall(authReq(port, "/api/v1/mcp/demo-http").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        http.newCall(authReq(port, "/api/v1/skills").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // Timers CRUD
        val timerId = http.newCall(
            authReq(port, "/api/v1/timers")
                .post("""{"name":"Morning Standup","prompt":"Summarize yesterday's git log","triggerType":"daily","time":"09:00","enabled":true}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["id"]!!.jsonPrimitive.content
        }
        http.newCall(
            authReq(port, "/api/v1/timers/$timerId/toggle")
                .post("""{"enabled":false}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("false", json.parseToJsonElement(resp.body.string()).jsonObject["enabled"]!!.jsonPrimitive.content)
        }
        http.newCall(authReq(port, "/api/v1/timers/$timerId").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
    }

    @Test
    fun securityRegressionBlocksEncodedPathAuthBypassAndValidatesInputs() = runBlocking {
        val port = server.start()

        // P0: Encoded traversal in /api path must NEVER bypass Bearer authentication.
        http.newCall(
            Request.Builder()
                .url("http://127.0.0.1:$port/api/v1/mcp/..%2F..%2F..%2Fevil")
                .put("""{"transport":"stdio","command":"echo","args":["pwned"]}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertTrue("Expected 400/401, got ${resp.code}", resp.code == 400 || resp.code == 401)
        }
        http.newCall(
            Request.Builder()
                .url("http://127.0.0.1:$port/api/v1/bots/..%2F..%2F..%2Fhome/files")
                .get()
                .build(),
        ).execute().use { resp ->
            assertTrue("Expected 400/401, got ${resp.code}", resp.code == 400 || resp.code == 401)
        }

        // Custom provider ID validation & built-in collision rejection
        http.newCall(
            authReq(port, "/api/v1/providers/custom")
                .post(
                    """{"id":"INVALID SPACE","name":"Bad","api":"openai-completions","baseUrl":"http://127.0.0.1:11434/v1","apiKey":"sk-1","models":[{"id":"m1"}]}"""
                        .toRequestBody("application/json".toMediaType()),
                )
                .build(),
        ).execute().use { resp ->
            assertEquals(400, resp.code)
        }
        http.newCall(
            authReq(port, "/api/v1/providers/custom")
                .post(
                    """{"id":"openai","name":"Shadow OpenAI","api":"openai-completions","baseUrl":"http://127.0.0.1:11434/v1","apiKey":"sk-1","models":[{"id":"m1"}]}"""
                        .toRequestBody("application/json".toMediaType()),
                )
                .build(),
        ).execute().use { resp ->
            assertEquals(400, resp.code)
        }

        // Nonexistent explicit botId on timer must return 400 (not silently fall back to home)
        http.newCall(
            authReq(port, "/api/v1/timers")
                .post("""{"name":"Bad Target","prompt":"Hello","triggerType":"daily","time":"09:00","botId":"does-not-exist"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(400, resp.code)
        }

        // Saving first configured provider key auto-migrates default model off unconfigured default
        http.newCall(
            authReq(port, "/api/v1/providers/opencode-go/key")
                .put("""{"apiKey":"oc_sk_unit_test_dummy_123456"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        http.newCall(authReq(port, "/api/v1/settings").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertEquals("opencode-go", obj["defaultProvider"]!!.jsonPrimitive.content)
        }

        // Project .agents/skills discovery works alongside .pi/skills
        val agentsSkillDir = File(controller.paths.defaultWorkspaceDir, ".agents/skills/demo-cross-harness").apply { mkdirs() }
        File(agentsSkillDir, "SKILL.md").writeText(
            "---\nname: demo-cross-harness\ndescription: Cross-harness skill test\n---\n# Demo\n",
            Charsets.UTF_8,
        )
        http.newCall(authReq(port, "/api/v1/skills").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val body = resp.body.string()
            assertTrue("Expected .agents/skills entry in /api/v1/skills", body.contains("demo-cross-harness"))
        }
    }

    @Test
    fun getSessionDoesNotEmitSessionUpdatedSseAndUiSettingsPatchSkipsReload() = runBlocking {
        val port = server.start()
        val botsResp = http.newCall(authReq(port, "/api/v1/bots").get().build()).execute()
        val botsArr = botsResp.use { json.parseToJsonElement(it.body.string()).jsonArray }
        val botId = botsArr.first().jsonObject["id"]!!.jsonPrimitive.content

        val createReq = authReq(port, "/api/v1/bots/$botId/sessions")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val sessionId = http.newCall(createReq).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["sessionId"]!!.jsonPrimitive.content
        }

        val ticketReq = authReq(port, "/api/v1/auth/sse-ticket")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val ticket = http.newCall(ticketReq).execute().use { resp ->
            assertEquals(200, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["ticket"]!!.jsonPrimitive.content
        }

        val sseHttp = http.newBuilder().readTimeout(2, TimeUnit.SECONDS).build()
        val sseResp = sseHttp.newCall(
            Request.Builder().url("http://127.0.0.1:$port/api/v1/events?ticket=$ticket").get().build(),
        ).execute()
        val reader = sseResp.body.byteStream().bufferedReader()

        val readyEvent = buildString {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                appendLine(line)
            }
        }
        assertTrue("Expected ready SSE event, got: $readyEvent", readyEvent.contains("event: ready"))

        repeat(3) {
            http.newCall(authReq(port, "/api/v1/sessions/$sessionId").get().build()).execute().use { resp ->
                assertEquals(200, resp.code)
            }
        }

        val patchReq = authReq(port, "/api/v1/settings")
            .patch("""{"chatFontScale":115,"processDetail":"compact"}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(patchReq).execute().use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            assertEquals(115, obj["chatFontScale"]!!.jsonPrimitive.content.toInt())
        }

        val stream = sseResp.body.byteStream()
        kotlinx.coroutines.delay(250L)
        val hasBufferedReaderData = reader.ready()
        val extraBytes = stream.available()
        sseResp.close()
        assertFalse("GET /sessions/{id} and UI settings patch must not emit SSE events", hasBufferedReaderData)
        assertEquals("GET /sessions/{id} and UI settings patch must not emit SSE events", 0, extraBytes)
    }

    @Test
    fun `newly created sessions appear immediately in bot session listing before first message`() = runBlocking {
        val port = server.start()

        val createHomeReq = authReq(port, "/api/v1/bots/home/sessions")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val homeSessionId = http.newCall(createHomeReq).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["sessionId"]!!.jsonPrimitive.content
        }

        http.newCall(authReq(port, "/api/v1/bots/home/sessions").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val ids = json.parseToJsonElement(resp.body.string()).jsonArray.map {
                it.jsonObject["id"]!!.jsonPrimitive.content
            }
            assertTrue("Expected home session $homeSessionId in $ids", homeSessionId in ids)
        }

        val createBotReq = authReq(port, "/api/v1/bots")
            .post("""{"name":"WorkBot"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val bot2Id = http.newCall(createBotReq).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["id"]!!.jsonPrimitive.content
        }

        val createBot2SessReq = authReq(port, "/api/v1/bots/$bot2Id/sessions")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val bot2SessionId = http.newCall(createBot2SessReq).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["sessionId"]!!.jsonPrimitive.content
        }

        http.newCall(authReq(port, "/api/v1/bots/$bot2Id/sessions").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val ids = json.parseToJsonElement(resp.body.string()).jsonArray.map {
                it.jsonObject["id"]!!.jsonPrimitive.content
            }
            assertTrue("Expected second bot session $bot2SessionId in $ids", bot2SessionId in ids)
        }
    }

    @Test
    fun workspaceUploadDetectsExternalModificationConflictViaExpectedMtimeMs(): Unit = runBlocking {
        val port = server.start()
        val encoded = java.util.Base64.getEncoder().encodeToString("v1".toByteArray())
        val createReq = authReq(port, "/api/v1/bots/home/files/upload")
            .post("""{"path":"conflict-test.txt","dataBase64":"$encoded","overwrite":true}""".toRequestBody("application/json".toMediaType()))
            .build()
        val firstMtime = http.newCall(createReq).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["mtimeMs"]!!.jsonPrimitive.content.toLong()
        }

        val readReq = authReq(port, "/api/v1/bots/home/files/read?path=conflict-test.txt").get().build()
        val readMtime = http.newCall(readReq).execute().use { resp ->
            assertEquals(200, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["mtimeMs"]!!.jsonPrimitive.content.toLong()
        }
        assertEquals(firstMtime, readMtime)

        val staleReq = authReq(port, "/api/v1/bots/home/files/upload")
            .post(
                """{"path":"conflict-test.txt","dataBase64":"$encoded","overwrite":true,"expectedMtimeMs":${firstMtime - 1000}}"""
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        http.newCall(staleReq).execute().use { resp ->
            assertEquals(409, resp.code)
        }
    }

    @Test
    fun terminalResizeReturns404ForMissingTerminalAnd200ForLiveTerminal(): Unit = runBlocking {
        val port = server.start()
        val missingReq = authReq(port, "/api/v1/terminals/term-missing/resize")
            .post("""{"cols":80,"rows":24}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(missingReq).execute().use { resp ->
            assertEquals(404, resp.code)
        }

        val createTermReq = authReq(port, "/api/v1/bots/home/terminals")
            .post("""{"fresh":true}""".toRequestBody("application/json".toMediaType()))
            .build()
        val termId = http.newCall(createTermReq).execute().use { resp ->
            assertEquals(201, resp.code)
            json.parseToJsonElement(resp.body.string()).jsonObject["id"]!!.jsonPrimitive.content
        }
        val resizeReq = authReq(port, "/api/v1/terminals/$termId/resize")
            .post("""{"cols":140,"rows":42}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(resizeReq).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertTrue(controller.ptySessionManager.kill(termId))
    }
}
