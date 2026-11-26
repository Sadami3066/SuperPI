package io.github.whywood.superpi.data.relay

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RelayClient(
    private val platform: String = "android",
    private val appVersion: String = "",
) : RelayEnroller {
    override suspend fun fetchCa(origin: String): String? = withContext(Dispatchers.IO) {
        val secure = origin.startsWith("https://")
        val capture = if (secure) CaptureTrustManager() else null
        val client = if (capture != null) {
            RelayTls.capturingClient(capture, remote = true)
        } else {
            RelayTls.client(pin = null, remote = true, bootstrap = true)
        }
        val request = Request.Builder().url("$origin/v1/ca").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RelayException(RelayFailure.BadResponse)
                val pem = response.body.string().takeIf { it.isNotBlank() }
                if (capture != null && pem != null) {
                    val ca = RelayTls.decodeBytes(pem.toByteArray(Charsets.UTF_8))
                        ?: throw RelayException(RelayFailure.BadResponse)
                    if (!RelayTls.verifies(ca, capture.chain)) {
                        throw RelayException(RelayFailure.FingerprintMismatch)
                    }
                }
                pem
            }
        } catch (error: RelayException) {
            throw error
        } catch (error: SSLException) {
            throw RelayException(RelayFailure.CertificateRejected, cause = error)
        } catch (error: IOException) {
            throw RelayException(RelayFailure.Unreachable, cause = error)
        }
    }

    override suspend fun enroll(
        origin: String,
        caPem: String?,
        deviceName: String,
        pairCode: String?,
        loginSecret: String?,
    ): RelayEnrollment = withContext(Dispatchers.IO) {
        val pin = caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
        val client = RelayTls.client(pin = pin, remote = true)
        val body = RelayProtocol
            .enrollRequest(deviceName, platform, appVersion, pairCode, loginSecret)
            .toRequestBody(JSON)
        val request = Request.Builder().url("$origin/v1/pair/enroll").post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.isSuccessful -> RelayProtocol.parseEnrollment(text)
                        ?: throw RelayException(RelayFailure.BadResponse)
                    response.code == 401 || response.code == 403 -> throw RelayException(RelayFailure.Unauthorized)
                    else -> throw RelayException(RelayFailure.BadResponse)
                }
            }
        } catch (error: RelayException) {
            throw error
        } catch (error: SSLException) {
            throw RelayException(RelayFailure.CertificateRejected, cause = error)
        } catch (error: IOException) {
            throw RelayException(RelayFailure.Unreachable, cause = error)
        }
    }

    override suspend fun createPairCode(account: RelayAccount, token: String): RelayPairCode =
        withContext(Dispatchers.IO) {
            val pin = account.caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
            val client = RelayTls.client(pin = pin, remote = true)
            val request = Request.Builder()
                .url("${account.origin}/v1/pair/code")
                .header("authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when {
                        response.isSuccessful -> RelayProtocol.parsePairCode(text)
                            ?: throw RelayException(RelayFailure.BadResponse)
                        response.code == 401 || response.code == 403 -> throw RelayException(RelayFailure.Unauthorized)
                        else -> throw RelayException(RelayFailure.BadResponse)
                    }
                }
            } catch (error: RelayException) {
                throw error
            } catch (error: SSLException) {
                throw RelayException(RelayFailure.CertificateRejected, cause = error)
            } catch (error: IOException) {
                throw RelayException(RelayFailure.Unreachable, cause = error)
            }
        }

    override suspend fun revokeDevice(account: RelayAccount, token: String, deviceId: String) =
        withContext(Dispatchers.IO) {
            val pin = account.caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
            val client = RelayTls.client(pin = pin, remote = true)
            val url = account.origin.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegment("v1")
                ?.addPathSegment("devices")
                ?.addPathSegment(deviceId)
                ?.build()
                ?: throw RelayException(RelayFailure.BadResponse)
            val request = Request.Builder()
                .url(url)
                .header("authorization", "Bearer $token")
                .delete()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Unit
                        response.code == 401 || response.code == 403 -> throw RelayException(RelayFailure.Unauthorized)
                        else -> throw RelayException(RelayFailure.BadResponse)
                    }
                }
            } catch (error: RelayException) {
                throw error
            } catch (error: SSLException) {
                throw RelayException(RelayFailure.CertificateRejected, cause = error)
            } catch (error: IOException) {
                throw RelayException(RelayFailure.Unreachable, cause = error)
            }
        }

    override suspend fun renameDevice(
        account: RelayAccount,
        token: String,
        deviceId: String,
        name: String,
    ) = withContext(Dispatchers.IO) {
        val pin = account.caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
        val client = RelayTls.client(pin = pin, remote = true)
        val url = account.origin.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("v1")
            ?.addPathSegment("devices")
            ?.addPathSegment(deviceId)
            ?.build()
            ?: throw RelayException(RelayFailure.BadResponse)
        val body = RelayProtocol.renameDeviceRequest(name).toRequestBody(JSON)
        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer $token")
            .patch(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Unit
                    response.code == 401 || response.code == 403 -> throw RelayException(RelayFailure.Unauthorized)
                    else -> throw RelayException(RelayFailure.BadResponse)
                }
            }
        } catch (error: RelayException) {
            throw error
        } catch (error: SSLException) {
            throw RelayException(RelayFailure.CertificateRejected, cause = error)
        } catch (error: IOException) {
            throw RelayException(RelayFailure.Unreachable, cause = error)
        }
    }

    override fun fingerprintOf(caPem: String): String? {
        val certificate = RelayTls.decodeBytes(caPem.toByteArray(Charsets.UTF_8)) ?: return null
        return RelayPairingLink.normalizeFingerprint(RelayTls.sha256Hex(certificate))
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
