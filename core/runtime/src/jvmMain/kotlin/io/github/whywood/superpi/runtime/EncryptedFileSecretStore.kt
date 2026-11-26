package io.github.whywood.superpi.runtime

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * AES-256-GCM encrypted file [SecretStore] for JVM hosts.
 * Uses a 256-bit random master key stored with 0600 POSIX permissions alongside the encrypted vault,
 * plus an optional environment-variable fallback for read lookups.
 */
class EncryptedFileSecretStore(
    private val secretsFile: File,
    private val keyFile: File,
    private val envFallback: (prefix: String, id: String) -> String? = { _, _ -> null },
    private val envEntries: (prefix: String) -> Map<String, String> = { emptyMap() },
) : SecretStore {
    private val json = Json { prettyPrint = true }
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val state = MutableStateFlow(readVaultFromDisk(strict = false))
    private var cachedKey: SecretKey? = null
    private var cachedKeyStamp: Pair<Long, Long>? = null

    override fun entries(prefix: String): Flow<Set<String>> = state
        .map { entries -> keysWithPrefix(entries, prefix) + envEntries(prefix).keys }
        .distinctUntilChanged()

    override fun has(prefix: String, providerId: String): Flow<Boolean> = state
        .map { entries ->
            entries.containsKey(prefix + providerId) || !envFallback(prefix, providerId).isNullOrBlank()
        }
        .distinctUntilChanged()

    override suspend fun read(prefix: String, providerId: String): String? = withContext(Dispatchers.IO) {
        val decrypted = mutex.withLock {
            val encoded = state.value[prefix + providerId] ?: return@withLock null
            val key = loadMasterKeyOrNullLocked() ?: return@withLock null
            decryptWithKey(encoded, key)
        }
        if (!decrypted.isNullOrBlank()) return@withContext decrypted
        envFallback(prefix, providerId)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun readAll(prefix: String): Map<String, String> = withContext(Dispatchers.IO) {
        val envMap = envEntries(prefix)
            .mapValues { (_, v) -> v.trim() }
            .filterValues { it.isNotEmpty() }
        val vaultMap = mutex.withLock {
            val snapshot = state.value
            val key = loadMasterKeyOrNullLocked()
            if (key == null) {
                emptyMap()
            } else {
                buildMap {
                    for ((fullKey, encoded) in snapshot) {
                        if (!fullKey.startsWith(prefix)) continue
                        val id = fullKey.removePrefix(prefix)
                        val plain = decryptWithKey(encoded, key)
                        if (!plain.isNullOrBlank()) put(id, plain)
                    }
                }
            }
        }
        if (envMap.isEmpty()) vaultMap else envMap + vaultMap
    }

    override suspend fun save(prefix: String, providerId: String, value: String?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val diskState = readVaultFromDisk(strict = true)
                val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
                val next = LinkedHashMap(diskState)
                val fullKey = prefix + providerId
                if (trimmed == null) {
                    next.remove(fullKey)
                } else {
                    val key = loadOrCreateMasterKeyLocked()
                    next[fullKey] = encryptWithKey(trimmed, key)
                }
                val immutable = next.toMap()
                writeVaultToDisk(immutable)
                state.value = immutable
            }
        }
    }

    suspend fun reload(): Map<String, String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            cachedKey = null
            cachedKeyStamp = null
            val reloaded = readVaultFromDisk(strict = false)
            state.value = reloaded
            reloaded
        }
    }

    private fun keysWithPrefix(entries: Map<String, String>, prefix: String): Set<String> =
        entries.keys.mapNotNull { key ->
            key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        }.toSet()

    private fun encryptWithKey(plainText: String, key: SecretKey): String {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        iv.copyInto(combined, 0)
        cipherText.copyInto(combined, iv.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decryptWithKey(encoded: String, key: SecretKey): String? = runCatching {
        val combined = Base64.getDecoder().decode(encoded)
        if (combined.size <= IV_SIZE) return@runCatching null
        val iv = combined.copyOfRange(0, IV_SIZE)
        val cipherText = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    private fun loadMasterKeyOrNullLocked(): SecretKey? {
        if (!keyFile.isFile) {
            cachedKey = null
            cachedKeyStamp = null
            return null
        }
        val stamp = keyFile.lastModified() to keyFile.length()
        cachedKey?.let { existing ->
            if (cachedKeyStamp == stamp) return existing
        }
        val encoded = runCatching { keyFile.readText(Charsets.UTF_8).trim() }.getOrNull() ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.size != 32) return null
        restrictOwnerOnly(keyFile)
        return SecretKeySpec(bytes, "AES").also {
            cachedKey = it
            cachedKeyStamp = keyFile.lastModified() to keyFile.length()
        }
    }

    private fun loadOrCreateMasterKeyLocked(): SecretKey {
        if (keyFile.exists()) {
            return loadMasterKeyOrNullLocked()
                ?: throw IOException("Master key file is corrupted or unreadable: ${keyFile.absolutePath}")
        }
        if (secretsFile.isFile && secretsFile.length() > 0L) {
            val disk = readVaultFromDisk(strict = true)
            if (disk.isNotEmpty()) {
                throw IOException("Refusing to generate a new master key while encrypted entries exist without ${keyFile.name}")
            }
        }
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256, random) }
        val key = keyGen.generateKey()
        val encoded = Base64.getEncoder().encodeToString(key.encoded)
        writeRestrictedAtomically(keyFile, encoded + "\n")
        cachedKey = key
        cachedKeyStamp = keyFile.lastModified() to keyFile.length()
        return key
    }

    private fun readVaultFromDisk(strict: Boolean): Map<String, String> {
        if (!secretsFile.isFile) return emptyMap()
        val raw = try {
            secretsFile.readText(Charsets.UTF_8)
        } catch (error: IOException) {
            if (strict) throw error
            return emptyMap()
        }
        if (raw.isBlank()) return emptyMap()
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            buildMap {
                obj.forEach { (k, v) ->
                    val text = (v as? JsonPrimitive)?.contentOrNull
                    if (!text.isNullOrBlank()) put(k, text)
                }
            }
        } catch (error: Exception) {
            if (strict) {
                throw IOException("Failed to parse encrypted vault ${secretsFile.absolutePath}: ${error.message}", error)
            }
            emptyMap()
        }
    }

    private fun writeVaultToDisk(entries: Map<String, String>) {
        val obj = buildJsonObject {
            entries.toSortedMap().forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        writeRestrictedAtomically(secretsFile, json.encodeToString(JsonObject.serializer(), obj) + "\n")
    }

    private fun writeRestrictedAtomically(target: File, content: String) {
        val parent = target.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create secrets directory: ${parent.absolutePath}")
        }
        val tempPath = try {
            Files.createTempFile(
                parent.toPath(),
                ".${target.name}.",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } catch (_: UnsupportedOperationException) {
            Files.createTempFile(parent.toPath(), ".${target.name}.", ".tmp")
        }
        val temp = tempPath.toFile()
        restrictOwnerOnly(temp)
        try {
            temp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    tempPath,
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempPath,
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
        restrictOwnerOnly(target)
    }

    private fun restrictOwnerOnly(file: File) {
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
