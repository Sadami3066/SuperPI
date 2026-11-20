package io.github.whywood.superpi.data.relay

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayControllerTest {
    private val link = "https://relay.example:8443/#fp=${"ab".repeat(32)}&code=CODE1"

    @Test
    fun signsInWithPairingLink() = runTest {
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = "ab".repeat(32))
        val controller = controller(store, enroller, clock = { 42L })

        controller.signIn(link, loginSecret = null, deviceName = "Pixel")

        val state = controller.state.value
        assertNull(state.error)
        assertEquals(false, state.signingIn)
        assertEquals("relay.example", state.account?.deviceId)
        assertEquals("Pixel", state.account?.deviceName)
        assertEquals(42L, state.account?.enrolledAt)
        assertEquals("PEM", state.account?.caPem)
        assertEquals("ab".repeat(32), state.account?.fingerprint)
        assertEquals("CODE1", enroller.pairCode)
        assertNull(enroller.loginSecret)
        assertEquals("tok", store.savedToken)
    }

    @Test
    fun signsInWithoutCertificateForPlainRelay() = runTest {
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = "ab".repeat(32))
        val controller = controller(store, enroller)

        controller.signIn("http://192.168.1.5:8080", loginSecret = "secret", deviceName = "")

        assertNull(controller.state.value.error)
        assertEquals(null, enroller.fetchedOrigin)
        assertEquals("secret", enroller.loginSecret)
        assertEquals("Android", controller.state.value.account?.deviceName)
    }

    @Test
    fun rejectsFingerprintMismatch() = runTest {
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = "cd".repeat(32))
        val controller = controller(store, enroller)

        controller.signIn(link, loginSecret = null, deviceName = "Pixel")

        assertEquals(RelayError.FingerprintMismatch, controller.state.value.error)
        assertNull(controller.state.value.account)
        assertNull(store.savedToken)
        assertNull(enroller.enrolledOrigin)
    }

    @Test
    fun requiresFingerprintForHttpsLink() = runTest {
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = "ab".repeat(32))
        val controller = controller(store, enroller)

        controller.signIn("https://relay.example:8443/#code=CODE1", loginSecret = null, deviceName = "Pixel")

        assertEquals(RelayError.MissingFingerprint, controller.state.value.error)
        assertNull(enroller.fetchedOrigin)
    }

    @Test
    fun requiresPairCodeOrLoginSecret() = runTest {
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = "ab".repeat(32))
        val controller = controller(store, enroller)

        controller.signIn("http://192.168.1.5:8080", loginSecret = " ", deviceName = "Pixel")

        assertEquals(RelayError.MissingCode, controller.state.value.error)
    }

    @Test
    fun mapsFailures() = runTest {
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = "ab".repeat(32))
        val controller = controller(store, enroller)

        enroller.failure = RelayException(RelayFailure.Unauthorized)
        controller.signIn(link, loginSecret = null, deviceName = "Pixel")
        assertEquals(RelayError.Unauthorized, controller.state.value.error)

        controller.dismissError()
        enroller.failure = RelayException(RelayFailure.Unreachable)
        controller.signIn(link, loginSecret = null, deviceName = "Pixel")
        assertEquals(RelayError.Unreachable, controller.state.value.error)

        controller.dismissError()
        enroller.failure = IOException("offline")
        controller.signIn(link, loginSecret = null, deviceName = "Pixel")
        assertEquals(RelayError.Unreachable, controller.state.value.error)
    }

    @Test
    fun signsOut() = runTest {
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = "ab".repeat(32))
        val controller = controller(store, enroller)
        controller.signIn(link, loginSecret = null, deviceName = "Pixel")
        assertTrue(controller.state.value.account != null)

        controller.signOut()

        assertNull(controller.state.value.account)
        assertTrue(store.cleared)
    }

    @Test
    fun restoresSavedAccount() = runTest {
        val account = account(token = "tok")
        val store = FakeStore(account)
        val controller = controller(store, FakeEnroller("PEM", "ab".repeat(32)))
        assertEquals("dev", controller.state.value.account?.deviceId)
    }

    private fun TestScope.controller(
        store: FakeStore,
        enroller: FakeEnroller,
        clock: () -> Long = System::currentTimeMillis,
    ): RelayController = RelayController(
        store = store,
        enroller = enroller,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        clock = clock,
    )

    @Test
    fun rejectsEnrollmentWhoseReturnedFingerprintDisagreesWithLink() = runTest {
        val fp = "ab".repeat(32)
        val otherFp = "cd".repeat(32)
        val store = FakeStore()
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = fp, enrollmentFingerprint = otherFp)
        val controller = controller(store, enroller)

        controller.signIn("https://relay.example:8443/pair#fp=$fp&code=ABCD2345", null, "Pixel")
        testScheduler.advanceUntilIdle()

        assertEquals(RelayError.FingerprintMismatch, controller.state.value.error)
        assertEquals(null, store.read())
    }

    @Test
    fun generatesPairLinkAndRevokesDevicesAndSelfOnSignOut() = runTest {
        val fp = "ab".repeat(32)
        val initial = account("tok-1")
        val store = FakeStore(initial, initialToken = "tok-1")
        val enroller = FakeEnroller(caPem = "PEM", fingerprint = fp)
        val controller = controller(store, enroller)

        controller.generatePairCode()
        testScheduler.advanceUntilIdle()
        assertEquals("https://relay.example:8443/pair#fp=$fp&code=PAIR1234", controller.state.value.pairLink)

        val revokedCallback = mutableListOf<String>()
        controller.revokeDevice("dev-desktop") { revokedCallback += it }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("dev-desktop"), revokedCallback)
        assertEquals(listOf("dev-desktop"), enroller.revokedDevices)

        controller.signOut()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("dev-desktop", "dev"), enroller.revokedDevices)
        assertEquals(true, store.cleared)
    }

    private class FakeStore(initial: RelayAccount? = null, initialToken: String? = null) : RelayAccountStore {
        private val values = MutableStateFlow(initial)
        var savedToken: String? = initialToken
            private set
        var cleared = false
            private set

        override val account: Flow<RelayAccount?> = values

        override suspend fun read(): RelayAccount? = values.value

        override suspend fun token(): String? = savedToken

        override suspend fun save(account: RelayAccount, token: String) {
            savedToken = token
            values.value = account
        }

        override suspend fun clear() {
            cleared = true
            savedToken = null
            values.value = null
        }
    }

    private class FakeEnroller(
        private val caPem: String,
        private val fingerprint: String,
        private val enrollmentFingerprint: String? = fingerprint,
    ) : RelayEnroller {
        var failure: Exception? = null
        var fetchedOrigin: String? = null
            private set
        var enrolledOrigin: String? = null
            private set
        var pairCode: String? = null
            private set
        var loginSecret: String? = null
            private set
        val revokedDevices = mutableListOf<String>()

        override suspend fun fetchCa(origin: String): String? {
            failure?.let { throw it }
            fetchedOrigin = origin
            return caPem
        }

        override suspend fun enroll(
            origin: String,
            caPem: String?,
            deviceName: String,
            pairCode: String?,
            loginSecret: String?,
        ): RelayEnrollment {
            failure?.let { throw it }
            enrolledOrigin = origin
            this.pairCode = pairCode
            this.loginSecret = loginSecret
            return RelayEnrollment(
                deviceId = origin.substringAfter("//").substringBefore(":"),
                token = "tok",
                accountId = "acct",
                entries = listOf(RelayEntry(RelayEntryKind.Lan, origin)),
                fingerprint = enrollmentFingerprint,
            )
        }

        override suspend fun createPairCode(account: RelayAccount, token: String): RelayPairCode {
            failure?.let { throw it }
            return RelayPairCode(
                code = "PAIR1234",
                expiresAt = 999L,
                entries = listOf(RelayEntry(RelayEntryKind.Public, account.origin)),
                fingerprint = fingerprint,
            )
        }

        override suspend fun revokeDevice(account: RelayAccount, token: String, deviceId: String) {
            failure?.let { throw it }
            revokedDevices += deviceId
        }

        override fun fingerprintOf(caPem: String): String? = fingerprint
    }

    private companion object {
        fun account(token: String) = RelayAccount(
            origin = "https://relay.example:8443",
            fingerprint = "ab".repeat(32),
            caPem = "PEM",
            deviceId = "dev",
            deviceName = "Pixel",
            accountId = "acct",
            enrolledAt = 1L,
        )
    }
}
