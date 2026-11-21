package io.github.whywood.superpi.data.relay

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

internal data class RegisteredPresentedArtifact(
    val artifactId: String,
    val size: Long,
    val mime: String?,
    val name: String,
)

internal sealed interface ArtifactChunkOutcome {
    data class Ok(
        val dataBase64: String,
        val size: Long,
        val eof: Boolean,
        val mime: String?,
        val name: String,
    ) : ArtifactChunkOutcome

    data class Error(
        val code: String,
        val message: String,
    ) : ArtifactChunkOutcome
}

internal class PresentedArtifactRegistry(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxChunkBytes: Int = MAX_CHUNK_BYTES,
) {
    private data class ArtifactKey(
        val botId: String,
        val artifactId: String,
    )

    private data class Entry(
        val file: File,
        val size: Long,
        val lastModified: Long,
        val mime: String?,
        val name: String,
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<ArtifactKey, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ArtifactKey, Entry>?): Boolean =
            size > maxEntries
    }

    fun register(
        botId: String,
        file: File,
        mime: String?,
        name: String?,
    ): RegisteredPresentedArtifact? {
        if (botId.isBlank()) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.isFile || !canonical.canRead()) return null
        val size = canonical.length()
        if (size < 0L || size > maxFileBytes) return null
        val modified = canonical.lastModified()
        val resolvedName = name?.trim()?.takeIf { it.isNotEmpty() } ?: canonical.name.ifBlank { "artifact" }
        val resolvedMime = mime?.trim()?.takeIf { it.isNotEmpty() }
        val artifactId = artifactIdFor(botId, canonical.path, size, modified)
        synchronized(lock) {
            entries[ArtifactKey(botId, artifactId)] = Entry(
                file = canonical,
                size = size,
                lastModified = modified,
                mime = resolvedMime,
                name = resolvedName,
            )
        }
        return RegisteredPresentedArtifact(
            artifactId = artifactId,
            size = size,
            mime = resolvedMime,
            name = resolvedName,
        )
    }

    fun readChunk(
        botId: String,
        artifactId: String,
        offset: Long,
        length: Int,
    ): ArtifactChunkOutcome {
        if (offset < 0L) {
            return ArtifactChunkOutcome.Error("bad_request", "offset must be non-negative")
        }
        if (length < 1) {
            return ArtifactChunkOutcome.Error("bad_request", "length must be positive")
        }
        if (length > maxChunkBytes) {
            return ArtifactChunkOutcome.Error("payload_too_large", "chunk length exceeds $maxChunkBytes bytes")
        }
        val key = ArtifactKey(botId, artifactId)
        val entry = synchronized(lock) { entries[key] }
            ?: return ArtifactChunkOutcome.Error("not_found", "artifact $artifactId was not found")
        if (!entry.file.isFile ||
            !entry.file.canRead() ||
            entry.file.length() != entry.size ||
            entry.file.lastModified() != entry.lastModified
        ) {
            synchronized(lock) { entries.remove(key) }
            return ArtifactChunkOutcome.Error("not_found", "artifact $artifactId is no longer available")
        }
        val clampedOffset = minOf(offset, entry.size)
        val available = (entry.size - clampedOffset).toInt().coerceAtMost(length)
        val buffer = if (available <= 0) {
            ByteArray(0)
        } else {
            runCatching {
                RandomAccessFile(entry.file, "r").use { raf ->
                    raf.seek(clampedOffset)
                    val data = ByteArray(available)
                    var readTotal = 0
                    while (readTotal < available) {
                        val n = raf.read(data, readTotal, available - readTotal)
                        if (n < 0) break
                        readTotal += n
                    }
                    if (readTotal == available) data else data.copyOf(readTotal)
                }
            }.getOrElse {
                return ArtifactChunkOutcome.Error("not_found", "artifact $artifactId could not be read")
            }
        }
        val encoded = Base64.getEncoder().encodeToString(buffer)
        val eof = clampedOffset + buffer.size >= entry.size
        return ArtifactChunkOutcome.Ok(
            dataBase64 = encoded,
            size = entry.size,
            eof = eof,
            mime = entry.mime,
            name = entry.name,
        )
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun artifactIdFor(
        botId: String,
        path: String,
        size: Long,
        lastModified: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$botId:$path:$size:$lastModified".toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(Character.forDigit((b.toInt() ushr 4) and 0xF, 16))
            hex.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return hex.substring(0, 32)
    }

    private companion object {
        private const val MAX_ENTRIES = 512
        private const val MAX_FILE_BYTES = 50L * 1024L * 1024L
        private const val MAX_CHUNK_BYTES = RelayFrames.MAX_FILE_CHUNK_BYTES
    }
}
