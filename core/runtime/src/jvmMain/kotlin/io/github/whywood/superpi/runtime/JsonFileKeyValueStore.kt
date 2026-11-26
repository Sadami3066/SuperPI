package io.github.whywood.superpi.runtime

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Atomic JSON-backed [KeyValueStore] for desktop JVM hosts.
 * Keeps entries in a clean, human-readable JSON object file.
 */
class JsonFileKeyValueStore(
    private val file: File,
) : KeyValueStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private val state = MutableStateFlow(readFromDisk(strict = false))

    override val entries: Flow<Map<String, StoredPreference>> = state.asStateFlow()

    override suspend fun readAll(): Map<String, StoredPreference> = mutex.withLock {
        state.value
    }

    suspend fun reload(): Map<String, StoredPreference> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val reloaded = readFromDisk(strict = false)
            state.value = reloaded
            reloaded
        }
    }

    override suspend fun edit(transform: (MutableMap<String, StoredPreference>) -> Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val diskState = readFromDisk(strict = true)
                val next = diskState.toMutableMap()
                transform(next)
                val immutable = next.toMap()
                writeToDisk(immutable)
                state.value = immutable
            }
        }
    }

    private fun readFromDisk(strict: Boolean): Map<String, StoredPreference> {
        if (!file.isFile) return emptyMap()
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (error: IOException) {
            if (strict) throw error
            return emptyMap()
        }
        if (raw.isBlank()) return emptyMap()
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            decodeObject(obj)
        } catch (error: Exception) {
            if (strict) {
                throw IOException("Failed to parse JSON store ${file.absolutePath}: ${error.message}", error)
            }
            emptyMap()
        }
    }

    private fun decodeObject(obj: JsonObject): Map<String, StoredPreference> = buildMap {
        for ((key, element) in obj) {
            when (val pref = decodeElement(element)) {
                null -> Unit
                else -> put(key, pref)
            }
        }
    }

    private fun decodeElement(element: JsonElement): StoredPreference? {
        if (element is JsonNull) return null
        if (element is JsonPrimitive) {
            if (element.isString) return StoredPreference.Text(element.content)
            element.intOrNull?.let { return StoredPreference.Integer(it) }
            return StoredPreference.Text(element.content)
        }
        return StoredPreference.Text(element.toString())
    }

    private fun writeToDisk(entries: Map<String, StoredPreference>) {
        val obj = buildJsonObject {
            entries.toSortedMap().forEach { (key, pref) ->
                when (pref) {
                    is StoredPreference.Text -> put(key, JsonPrimitive(pref.value))
                    is StoredPreference.Integer -> put(key, JsonPrimitive(pref.value))
                }
            }
        }
        val parent = file.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create config directory: ${parent.absolutePath}")
        }
        val tempPath = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp")
        val temp = tempPath.toFile()
        try {
            temp.writeText(json.encodeToString(JsonObject.serializer(), obj) + "\n", Charsets.UTF_8)
            try {
                Files.move(
                    tempPath,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempPath,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }
}
