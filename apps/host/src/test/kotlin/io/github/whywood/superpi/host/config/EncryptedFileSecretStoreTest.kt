package io.github.whywood.superpi.host.config

import io.github.whywood.superpi.runtime.AnswerCardPayload
import io.github.whywood.superpi.runtime.EncryptedFileSecretStore
import io.github.whywood.superpi.runtime.JsonFileKeyValueStore
import io.github.whywood.superpi.runtime.putString
import io.github.whywood.superpi.runtime.stringValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class EncryptedFileSecretStoreTest {
    private lateinit var tempDir: File
    private lateinit var secretsFile: File
    private lateinit var keyFile: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-secrets-test").toFile()
        secretsFile = File(tempDir, "secrets.enc")
        keyFile = File(tempDir, "master.key")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun roundTripsEncryptedSecretsAndEnforces0600Permissions() = runBlocking {
        val store1 = EncryptedFileSecretStore(secretsFile, keyFile)
        store1.save("api_key_", "openai", "sk-secret-value-123")
        assertTrue(secretsFile.isFile)
        assertTrue(keyFile.isFile)

        val rawCipher = secretsFile.readText()
        assertTrue("Secret must not be plaintext on disk", !rawCipher.contains("sk-secret-value-123"))

        val keyPerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile.toPath()))
        val secretPerms = PosixFilePermissions.toString(Files.getPosixFilePermissions(secretsFile.toPath()))
        assertEquals("rw-------", keyPerms)
        assertEquals("rw-------", secretPerms)

        val store2 = EncryptedFileSecretStore(secretsFile, keyFile)
        assertEquals("sk-secret-value-123", store2.read("api_key_", "openai"))

        store2.save("api_key_", "openai", null)
        assertNull(store2.read("api_key_", "openai"))
    }

    @Test
    fun fallsBackToEnvLookupWhenMissingFromVault() = runBlocking {
        val store = EncryptedFileSecretStore(
            secretsFile = secretsFile,
            keyFile = keyFile,
            envFallback = { prefix, id -> if (prefix == "api_key_" && id == "deepseek") "env-key-999" else null },
            envEntries = { prefix -> if (prefix == "api_key_") mapOf("deepseek" to "env-key-999", "openai" to "env-openai") else emptyMap() },
        )
        assertEquals("env-key-999", store.read("api_key_", "deepseek"))
        assertEquals(mapOf("deepseek" to "env-key-999", "openai" to "env-openai"), store.readAll("api_key_"))
        store.save("api_key_", "deepseek", "vault-key-111")
        assertEquals("vault-key-111", store.read("api_key_", "deepseek"))
        assertEquals("vault-key-111", store.readAll("api_key_")["deepseek"])
    }

    @Test
    fun doesNotOverwriteCorruptMasterKeyAndReloadsExternalWrites() = runBlocking {
        val store1 = EncryptedFileSecretStore(secretsFile, keyFile)
        store1.save("api_key_", "openai", "sk-first")

        val store2 = EncryptedFileSecretStore(secretsFile, keyFile)
        store2.save("api_key_", "anthropic", "sk-second")

        // store1 did not see store2 write yet, reload picks it up
        store1.reload()
        assertEquals("sk-second", store1.read("api_key_", "anthropic"))

        // Corrupt keyFile: read must return null without overwriting keyFile, save must fail strictly
        keyFile.writeText("corrupt-key", Charsets.UTF_8)
        val corrupted = EncryptedFileSecretStore(secretsFile, keyFile)
        assertNull(corrupted.read("api_key_", "openai"))
        assertEquals("corrupt-key", keyFile.readText(Charsets.UTF_8))
        try {
            corrupted.save("api_key_", "openai", "sk-overwrite-attempt")
            fail("Expected IOException when saving with corrupted master.key")
        } catch (_: IOException) {
        }
        assertEquals("corrupt-key", keyFile.readText(Charsets.UTF_8))
    }

    @Test
    fun jsonFileKeyValueStoreHandlesJsonNullReloadsAndRefusesCorruptEdit() = runBlocking {
        val kvFile = File(tempDir, "kv.json")
        kvFile.writeText("""{"a":"hello","b":null}""", Charsets.UTF_8)
        val kv1 = JsonFileKeyValueStore(kvFile)
        val loaded = kv1.readAll()
        assertEquals("hello", loaded.stringValue("a"))
        assertNull(loaded.stringValue("b"))

        val kv2 = JsonFileKeyValueStore(kvFile)
        kv2.edit { it.putString("c", "external") }
        kv1.reload()
        assertEquals("external", kv1.readAll().stringValue("c"))

        kvFile.writeText("{not valid json", Charsets.UTF_8)
        try {
            kv1.edit { it.putString("d", "wipe") }
            fail("Expected IOException when editing corrupt JSON store")
        } catch (_: IOException) {
        }
    }

    @Test
    fun answerCardPayloadRoundTripsWithKotlinxSerialization() {
        val payload = AnswerCardPayload(
            cardId = "card-1",
            type = "summary",
            title = "Build Status",
            data = buildJsonObject { put("ok", true) },
            sourceExtensionId = "ext:demo",
        )
        val encoded = Json.encodeToString(AnswerCardPayload.serializer(), payload)
        val decoded = Json.decodeFromString(AnswerCardPayload.serializer(), encoded)
        assertEquals(payload, decoded)
    }
}
