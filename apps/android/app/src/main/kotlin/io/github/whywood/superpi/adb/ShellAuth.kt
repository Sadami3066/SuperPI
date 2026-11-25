package io.github.whywood.superpi.adb

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ShellAuth {
    const val SHELL_PROTOCOL_VERSION = 3
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
    private val random = SecureRandom()

    fun randomHex(byteCount: Int = 16): String {
        val bytes = ByteArray(byteCount.coerceAtLeast(1))
        random.nextBytes(bytes)
        return toHex(bytes)
    }

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return toHex(digest.digest(text.toByteArray(Charsets.UTF_8)))
    }

    fun hmacHex(secret: String, payload: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return toHex(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }

    fun daemonProof(secret: String, clientNonce: String, serverNonce: String, uid: Int): String =
        hmacHex(secret, "daemon|$SHELL_PROTOCOL_VERSION|$clientNonce|$serverNonce|$uid")

    fun clientProof(
        secret: String,
        serverNonce: String,
        method: String,
        path: String,
        bodySha256: String,
    ): String = hmacHex(secret, "client|$SHELL_PROTOCOL_VERSION|$serverNonce|${method.uppercase()}|$path|$bodySha256")

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }
}
