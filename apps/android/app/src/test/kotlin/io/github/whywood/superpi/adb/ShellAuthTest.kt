package io.github.whywood.superpi.adb

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAuthTest {
    @Test
    fun proofsRejectTamperingAndMatchCanonicalInputs() {
        val secret = "0123456789abcdef0123456789abcdef"
        val daemon = ShellAuth.daemonProof(secret, "cnonce1234567890", "snonce1234567890", 2000)
        assertTrue(ShellAuth.constantTimeEquals(daemon, ShellAuth.daemonProof(secret, "cnonce1234567890", "snonce1234567890", 2000)))
        assertFalse(ShellAuth.constantTimeEquals(daemon, ShellAuth.daemonProof(secret, "cnonce1234567890", "snonce1234567890", 1000)))

        val client = ShellAuth.clientProof(secret, "snonce1234567890", "POST", "/exec", ShellAuth.sha256Hex("echo hi"))
        assertTrue(
            ShellAuth.constantTimeEquals(
                client,
                ShellAuth.clientProof(secret, "snonce1234567890", "post", "/exec", ShellAuth.sha256Hex("echo hi")),
            ),
        )
        assertFalse(
            ShellAuth.constantTimeEquals(
                client,
                ShellAuth.clientProof(secret, "snonce1234567890", "POST", "/exec", ShellAuth.sha256Hex("id")),
            ),
        )
    }

    @Test
    fun clientNeverDisclosesSecretToPortSquatterAndRejectsForgedHealth() = runBlocking {
        val secret = "secret-token-should-never-leak"
        val capturedRequests = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val raw = readHttpHeaders(client)
                    capturedRequests += raw
                    val body = "uid=2000 ok protocol=${ShellAuth.SHELL_PROTOCOL_VERSION}\n"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                }
                runCatching { client.close() }
            }
        }
        try {
            val client = ShellClient(server.localPort, secret)
            assertFalse(client.isAlive())
            assertFalse(client.shutdown())
            assertTrue(capturedRequests.isNotEmpty())
            assertTrue(capturedRequests.none { it.contains(secret, ignoreCase = true) })
            assertTrue(capturedRequests.none { it.contains("x-token", ignoreCase = true) })
        } finally {
            runCatching { server.close() }
            worker.join(1000)
        }
    }

    @Test
    fun clientAuthenticatesWithChallengeResponseForExecAndShutdown() = runBlocking {
        val secret = "valid-shared-secret-value-123456"
        val validNonces = mutableSetOf<String>()
        val seenRequests = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val input = client.getInputStream()
                    val head = readHttpHeaders(client)
                    seenRequests += head
                    val lines = head.split("\r\n").filter { it.isNotEmpty() }
                    val parts = lines.first().split(" ")
                    val method = parts[0]
                    val path = parts[1]
                    val headers = lines.drop(1).associate { line ->
                        line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
                    }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var off = 0
                    while (off < contentLength) {
                        val n = input.read(bodyBytes, off, contentLength - off)
                        if (n < 0) break
                        off += n
                    }
                    val body = String(bodyBytes, 0, off, Charsets.UTF_8)
                    val (code, reply) = when {
                        path.startsWith("/challenge?") -> {
                            val cnonce = path.substringAfter("cnonce=")
                            val snonce = "servernonce1234567890"
                            synchronized(validNonces) { validNonces += snonce }
                            val proof = ShellAuth.daemonProof(secret, cnonce, snonce, 2000)
                            200 to "uid=2000 protocol=${ShellAuth.SHELL_PROTOCOL_VERSION} snonce=$snonce proof=$proof\n"
                        }
                        else -> {
                            val nonce = headers["x-nonce"].orEmpty()
                            val auth = headers["x-auth"].orEmpty()
                            val consumed = synchronized(validNonces) { validNonces.remove(nonce) }
                            val expected = ShellAuth.clientProof(secret, nonce, method, path, ShellAuth.sha256Hex(body))
                            if (!consumed || !ShellAuth.constantTimeEquals(auth, expected)) {
                                403 to "DENIED\n"
                            } else if (path == "/exec") {
                                200 to "out:$body\n[EXIT=0]\n"
                            } else if (path == "/shutdown") {
                                200 to "ok\n"
                            } else {
                                404 to "not found\n"
                            }
                        }
                    }
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 $code OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
                runCatching { client.close() }
            }
        }
        try {
            val client = ShellClient(server.localPort, secret)
            assertTrue(client.isAlive())
            assertEquals("out:echo ok\n[EXIT=0]\n", client.exec("echo ok"))
            assertTrue(client.shutdown())
            assertTrue(seenRequests.none { it.contains(secret) })
            assertTrue(seenRequests.none { it.contains("x-token", ignoreCase = true) })
        } finally {
            runCatching { server.close() }
            worker.join(1000)
        }
    }

    private fun readHttpHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            buffer.write(b)
            val arr = buffer.toByteArray()
            val len = arr.size
            if (len >= 4 &&
                arr[len - 4] == '\r'.code.toByte() &&
                arr[len - 3] == '\n'.code.toByte() &&
                arr[len - 2] == '\r'.code.toByte() &&
                arr[len - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return buffer.toString("UTF-8")
    }
}
