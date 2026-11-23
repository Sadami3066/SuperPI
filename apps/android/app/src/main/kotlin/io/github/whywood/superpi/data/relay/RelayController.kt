package io.github.whywood.superpi.data.relay

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val renamingDeviceId: String? = null,
)

class RelayController(
    private val store: RelayAccountStore,
    private val enroller: RelayEnroller,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(RelayUiState())
    val state: StateFlow<RelayUiState> = _state.asStateFlow()
    private val accountGeneration = AtomicLong(0L)

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
        val attemptGeneration = accountGeneration.incrementAndGet()
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
                if (accountGeneration.get() != attemptGeneration) {
                    _state.update { state -> state.copy(signingIn = false) }
                    return@launch
                }
                store.save(account, enrollment.token)
                _state.update { state ->
                    state.copy(account = account, signingIn = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                _state.update { state -> state.copy(signingIn = false) }
                throw cancelled
            } catch (error: RelayException) {
                fail(error.failure.toRelayError())
            } catch (_: IOException) {
                fail(RelayError.Unreachable)
            } catch (_: Exception) {
                fail(RelayError.BadResponse)
            }
        }
    }

    fun signOut() {
        val current = _state.value.account
        val signOutGeneration = accountGeneration.incrementAndGet()
        _state.update { state ->
            state.copy(
                account = null,
                error = null,
                pairLink = null,
                pairExpiresAt = null,
                generatingPairCode = false,
                revokingDeviceId = null,
                renamingDeviceId = null,
            )
        }
        scope.launch {
            val token = runCatching { store.token() }.getOrNull()
            withContext(NonCancellable) {
                if (accountGeneration.get() == signOutGeneration) {
                    store.clear()
                }
            }
            if (current != null && !token.isNullOrBlank()) {
                runCatching { enroller.revokeDevice(current, token, current.deviceId) }
            }
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
            } catch (cancelled: CancellationException) {
                _state.update { state -> state.copy(generatingPairCode = false) }
                throw cancelled
            } catch (error: RelayException) {
                _state.update { state ->
                    state.copy(generatingPairCode = false, error = error.failure.toRelayError())
                }
            } catch (_: IOException) {
                _state.update { state ->
                    state.copy(generatingPairCode = false, error = RelayError.Unreachable)
                }
            } catch (_: Exception) {
                _state.update { state ->
                    state.copy(generatingPairCode = false, error = RelayError.BadResponse)
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
            } catch (cancelled: CancellationException) {
                _state.update { state -> state.copy(revokingDeviceId = null) }
                throw cancelled
            } catch (error: RelayException) {
                _state.update { state ->
                    state.copy(revokingDeviceId = null, error = error.failure.toRelayError())
                }
            } catch (_: IOException) {
                _state.update { state ->
                    state.copy(revokingDeviceId = null, error = RelayError.Unreachable)
                }
            } catch (_: Exception) {
                _state.update { state ->
                    state.copy(revokingDeviceId = null, error = RelayError.BadResponse)
                }
            }
        }
    }

    fun renameDevice(
        deviceId: String,
        name: String,
        onRenamed: (deviceId: String, name: String) -> Unit = { _, _ -> },
    ) {
        val current = _state.value.account ?: return
        val cleanName = name.trim().take(MAX_DEVICE_NAME_LENGTH)
        if (cleanName.isEmpty() || _state.value.renamingDeviceId != null) return
        _state.update { state -> state.copy(renamingDeviceId = deviceId, error = null) }
        scope.launch {
            try {
                val token = store.token() ?: throw RelayException(RelayFailure.Unauthorized)
                enroller.renameDevice(current, token, deviceId, cleanName)
                if (deviceId == current.deviceId && current.deviceName != cleanName) {
                    val updatedAccount = current.copy(deviceName = cleanName)
                    store.save(updatedAccount, token)
                    _state.update { state ->
                        state.copy(account = updatedAccount, renamingDeviceId = null, error = null)
                    }
                } else {
                    _state.update { state -> state.copy(renamingDeviceId = null, error = null) }
                }
                onRenamed(deviceId, cleanName)
            } catch (cancelled: CancellationException) {
                _state.update { state -> state.copy(renamingDeviceId = null) }
                throw cancelled
            } catch (error: RelayException) {
                _state.update { state ->
                    state.copy(renamingDeviceId = null, error = error.failure.toRelayError())
                }
            } catch (_: IOException) {
                _state.update { state ->
                    state.copy(renamingDeviceId = null, error = RelayError.Unreachable)
                }
            } catch (_: Exception) {
                _state.update { state ->
                    state.copy(renamingDeviceId = null, error = RelayError.BadResponse)
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
        private const val MAX_DEVICE_NAME_LENGTH = 128
    }
}
