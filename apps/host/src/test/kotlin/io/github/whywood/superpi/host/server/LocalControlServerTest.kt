package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.platform.LinuxHostPlatform
import io.github.whywood.superpi.host.platform.PathLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalControlServerTest {
    private lateinit var tempDir: File
    private lateinit var controller: SuperPiController
    private lateinit var server: LocalControlServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-server-test").toFile()
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

    @Test
    fun enforcesBearerTokenCreates0600LockAndHandlesBotCrud() = runBlocking {
        val port = server.start()
        assertTrue(port > 0)
        assertTrue(controller.paths.serveLockFile.isFile)
        val perms = PosixFilePermissions.toString(
            Files.getPosixFilePermissions(controller.paths.serveLockFile.toPath()),
        )
        assertEquals("rw-------", perms)

        // Unauthenticated -> 401
        val noAuthReq = Request.Builder().url("http://127.0.0.1:$port/api/v1/health").get().build()
        http.newCall(noAuthReq).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // Wrong token -> 401
        val badAuthReq = Request.Builder()
            .url("http://127.0.0.1:$port/api/v1/health")
            .header("Authorization", "Bearer wrong-token")
            .get()
            .build()
        http.newCall(badAuthReq).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        val client = LocalControlClient(controller.paths.serveLockFile)
        val status = client.fetchStatus()
        assertNotNull(status)
        assertEquals(port, status!!.port)
        assertEquals(1, status.botsCount)

        // Add bot via POST /api/v1/bots
        val repoDir = File(tempDir, "my-repo").apply { mkdirs() }
        val createReq = Request.Builder()
            .url("http://127.0.0.1:$port/api/v1/bots")
            .header("Authorization", "Bearer ${server.bearerToken}")
            .post("""{"dir":"${repoDir.absolutePath}","name":"MyRepo"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val createdId = http.newCall(createReq).execute().use { resp ->
            assertEquals(201, resp.code)
            resp.body.string()
        }
        assertTrue(createdId.contains("MyRepo"))
        assertEquals(2, client.fetchStatus()!!.botsCount)

        // Delete bot via DELETE /api/v1/bots/{id}
        val deleteReq = Request.Builder()
            .url("http://127.0.0.1:$port/api/v1/bots/myrepo")
            .header("Authorization", "Bearer ${server.bearerToken}")
            .delete()
            .build()
        http.newCall(deleteReq).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertEquals(1, client.fetchStatus()!!.botsCount)

        // Malformed model string should return 400 Bad Request
        val badBotReq = Request.Builder()
            .url("http://127.0.0.1:$port/api/v1/bots")
            .header("Authorization", "Bearer ${server.bearerToken}")
            .post("""{"dir":"${repoDir.absolutePath}","name":"BadModel","model":"invalid_model_without_slash"}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(badBotReq).execute().use { resp ->
            assertEquals(400, resp.code)
        }

        // Second LocalControlServer on same homeDir must fail with single-instance lock
        val duplicateServer = LocalControlServer(controller = controller, requestedPort = 0)
        var duplicateBlocked = false
        try {
            duplicateServer.start()
        } catch (_: IllegalStateException) {
            duplicateBlocked = true
        } finally {
            duplicateServer.stop()
        }
        assertTrue("Second serve instance on same homeDir must be blocked", duplicateBlocked)

        server.stop()
        assertFalse(controller.paths.serveLockFile.exists())
    }
}
