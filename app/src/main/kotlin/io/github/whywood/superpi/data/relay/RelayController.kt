package io.github.whywood.superpi.data.relay

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RelayError {
    InvalidLink,
    MissingCode,
    MissingFingerprint,
    FingerprintMismatch,
    Unreachable,
    Unauthorized,
    BadResponse,
}

data class RelayUiState(
    val account: RelayAccount? = null,
    val signingIn: Boolean = false,
    val error: RelayError? = null,
    val pairLink: String? = null,
    val pairExpiresAt: Long? = null,
    val generatingPairCode: Boolean = false,
    val revokingDeviceId: String? = null,
)

class RelayController(
    private val store: RelayAccountStore,
    private val enroller: RelayEnroller,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(RelayUiState())
    val state: StateFlow<RelayUiState> = _state.asStateFlow()

    init {
        scope.launch {
            store.account.collect { account ->
                _state.update { state -> state.copy(account = account) }
            }
        }
    }

    fun signIn(link: String, loginSecret: String?, deviceName: String) {
        if (_state.value.signingIn) return
        val parsed = RelayPairingLink.parse(link) ?: run {
            fail(RelayError.InvalidLink)
            return
        }
        val secret = loginSecret?.trim()?.takeIf { it.isNotEmpty() }
        if (parsed.pairCode == null && secret == null) {
            fail(RelayError.MissingCode)
            return
        }
        val secure = parsed.origin.startsWith("https://")
        if (secure && parsed.fingerprint == null) {
            fail(RelayError.MissingFingerprint)
            return
        }
        _state.update { state -> state.copy(signingIn = true, error = null) }
        scope.launch {
            try {
                val caPem = if (secure) fetchCa(parsed) else null
                val enrollment = enroller.enroll(
                    origin = parsed.origin,
                    caPem = caPem,
                    deviceName = deviceName.trim().ifEmpty { DEFAULT_DEVICE_NAME },
                    pairCode = parsed.pairCode,
                    loginSecret = secret,
                )
                val enrollmentFingerprint = enrollment.fingerprint?.let(RelayPairingLink::normalizeFingerprint)
                val fingerprint = parsed.fingerprint ?: enrollmentFingerprint
                if (secure && enrollmentFingerprint != null && enrollmentFingerprint != parsed.fingerprint) {
                    throw RelayException(RelayFailure.FingerprintMismatch)
                }
                val account = RelayAccount(
                    origin = parsed.origin,
                    fingerprint = fingerprint,
                    caPem = caPem,
                    deviceId = enrollment.deviceId,
                    deviceName = deviceName.trim().ifEmpty { DEFAULT_DEVICE_NAME },
                    accountId = enrollment.accountId,
                    enrolledAt = clock(),
                )
                store.save(account, enrollment.token)
                _state.update { state ->
                    state.copy(account = account, signingIn = false, error = null)
                }
            } catch (error: RelayException) {
                fail(error.failure.toRelayError())
            } catch (error: IOException) {
                fail(RelayError.Unreachable)
            }
        }
    }

    fun signOut() {
        val current = _state.value.account
        _state.update { state ->
            state.copy(
                account = null,
                error = null,
                pairLink = null,
                pairExpiresAt = null,
                generatingPairCode = false,
                revokingDeviceId = null,
            )
        }
        scope.launch {
            if (current != null) {
                val token = runCatching { store.token() }.getOrNull()
                if (!token.isNullOrBlank()) {
                    runCatching { enroller.revokeDevice(current, token, current.deviceId) }
                }
            }
            store.clear()
        }
    }

    fun generatePairCode() {
        val current = _state.value.account ?: return
        if (_state.value.generatingPairCode) return
        _state.update { state -> state.copy(generatingPairCode = true, error = null) }
        scope.launch {
            try {
                val token = store.token() ?: throw RelayException(RelayFailure.Unauthorized)
                val issued = enroller.createPairCode(current, token)
                val origin = issued.entries.firstOrNull { it.kind == RelayEntryKind.Public }?.origin
                    ?: issued.entries.firstOrNull()?.origin
                    ?: current.origin
                val lan = issued.entries.firstOrNull { it.kind == RelayEntryKind.Lan }?.origin
                    ?.takeIf { it != origin }
                val link = RelayPairingLink.build(
                    origin = origin,
                    fingerprint = issued.fingerprint ?: current.fingerprint,
                    pairCode = issued.code,
                    lanOrigin = lan,
                )
                _state.update { state ->
                    state.copy(
                        pairLink = link,
                        pairExpiresAt = issued.expiresAt,
                        generatingPairCode = false,
                        error = null,
                    )
                }
            } catch (error: RelayException) {
                _state.update { state ->
                    state.copy(generatingPairCode = false, error = error.failure.toRelayError())
                }
            } catch (_: IOException) {
                _state.update { state ->
                    state.copy(generatingPairCode = false, error = RelayError.Unreachable)
                }
            }
        }
    }

    fun dismissPairCode() {
        _state.update { state -> state.copy(pairLink = null, pairExpiresAt = null) }
    }

    fun revokeDevice(deviceId: String, onRevoked: (String) -> Unit = {}) {
        val current = _state.value.account ?: return
        if (_state.value.revokingDeviceId != null) return
        _state.update { state -> state.copy(revokingDeviceId = deviceId, error = null) }
        scope.launch {
            try {
                val token = store.token() ?: throw RelayException(RelayFailure.Unauthorized)
                enroller.revokeDevice(current, token, deviceId)
                onRevoked(deviceId)
                _state.update { state -> state.copy(revokingDeviceId = null, error = null) }
            } catch (error: RelayException) {
                _state.update { state ->
                    state.copy(revokingDeviceId = null, error = error.failure.toRelayError())
                }
            } catch (_: IOException) {
                _state.update { state ->
                    state.copy(revokingDeviceId = null, error = RelayError.Unreachable)
                }
            }
        }
    }

    fun dismissError() {
        _state.update { state -> state.copy(error = null) }
    }

    private suspend fun fetchCa(parsed: RelayPairingInfo): String {
        val caPem = enroller.fetchCa(parsed.origin)
            ?: throw RelayException(RelayFailure.BadResponse)
        val actual = enroller.fingerprintOf(caPem)
            ?: throw RelayException(RelayFailure.BadResponse)
        if (actual != parsed.fingerprint) {
            throw RelayException(RelayFailure.FingerprintMismatch)
        }
        return caPem
    }

    private fun fail(error: RelayError) {
        _state.update { state -> state.copy(signingIn = false, error = error) }
    }

    private fun RelayFailure.toRelayError(): RelayError = when (this) {
        RelayFailure.Unreachable -> RelayError.Unreachable
        RelayFailure.CertificateRejected -> RelayError.FingerprintMismatch
        RelayFailure.FingerprintMismatch -> RelayError.FingerprintMismatch
        RelayFailure.Unauthorized -> RelayError.Unauthorized
        RelayFailure.BadResponse -> RelayError.BadResponse
    }

    private companion object {
        private const val DEFAULT_DEVICE_NAME = "Android"
    }
}
