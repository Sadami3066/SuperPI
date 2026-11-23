package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.memory.EntitySyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryRemotePullEntry
import io.github.whywood.superpi.runtime.memory.MemorySyncOpItem
import io.github.whywood.superpi.runtime.memory.MemorySyncOpKind
import io.github.whywood.superpi.runtime.memory.MemorySyncRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal object RelayProtocol {
    fun enrollRequest(
        deviceName: String,
        platform: String,
        appVersion: String,
        pairCode: String?,
        loginSecret: String?,
    ): String = buildJsonObject {
        put("deviceName", deviceName)
        put("platform", platform)
        put("appVersion", appVersion)
        if (!pairCode.isNullOrBlank()) put("pairCode", pairCode)
        if (!loginSecret.isNullOrBlank()) put("loginSecret", loginSecret)
    }.toString()

    fun renameDeviceRequest(name: String): String = buildJsonObject {
        put("name", name)
    }.toString()

    fun parseEnrollment(body: String): RelayEnrollment? {
        val json = parseObject(body) ?: return null
        val deviceId = json.string("deviceId") ?: return null
        val token = json.string("token") ?: return null
        val accountId = json.string("accountId") ?: return null
        return RelayEnrollment(
            deviceId = deviceId,
            token = token,
            accountId = accountId,
            entries = parseEntries(json["entries"]),
            fingerprint = RelayPairingLink.normalizeFingerprint(json.string("fingerprint")),
        )
    }

    fun parsePairCode(body: String): RelayPairCode? {
        val json = parseObject(body) ?: return null
        val code = json.string("code") ?: return null
        val expiresAt = json["expiresAt"]?.jsonPrimitive?.longOrNull ?: return null
        return RelayPairCode(
            code = code,
            expiresAt = expiresAt,
            entries = parseEntries(json["entries"]),
            fingerprint = RelayPairingLink.normalizeFingerprint(json.string("fingerprint")),
        )
    }

    fun encodeSyncOpJson(op: MemorySyncOpItem): String = encodeSyncOp(op).toString()

    fun memoryPushRequest(
        ops: List<MemorySyncOpItem>,
        dreamCompletedAt: Long? = null,
    ): String = buildJsonObject {
        put(
            "ops",
            buildJsonArray {
                for (op in ops) {
                    add(encodeSyncOp(op))
                }
            },
        )
        if (dreamCompletedAt != null && dreamCompletedAt > 0L) {
            put("dreamCompletedAt", dreamCompletedAt)
        }
    }.toString()

    fun parseMemoryPushResponse(body: String): RelayMemoryPushResponse? {
        val json = parseObject(body) ?: return null
        val serverSeq = json.long("serverSeq") ?: return null
        val appliedCount = json.int("appliedCount") ?: 0
        val skippedCount = json.int("skippedCount") ?: 0
        val lastDreamAt = json.long("lastDreamAt")
        val rejected = (json["rejected"] as? JsonArray)?.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val opId = obj.string("opId") ?: return@mapNotNull null
            val reason = obj.string("reason") ?: "rejected"
            RelayMemoryRejectedOp(opId = opId, reason = reason)
        }.orEmpty()
        val rejectedCount = json.int("rejectedCount") ?: rejected.size
        return RelayMemoryPushResponse(
            serverSeq = serverSeq,
            appliedCount = appliedCount,
            skippedCount = skippedCount,
            lastDreamAt = lastDreamAt,
            rejectedCount = rejectedCount,
            rejected = rejected,
        )
    }

    fun parseMemoryPullResponse(body: String): RelayMemoryPullResponse? {
        val json = parseObject(body) ?: return null
        val serverSeq = json.long("serverSeq") ?: return null
        val hasMore = json.bool("hasMore") ?: false
        val nextSince = json.long("nextSince") ?: 0L
        val lastDreamAt = json.long("lastDreamAt")
        val rawEntries = json["entries"] as? JsonArray ?: return null
        val entries = rawEntries.mapNotNull { parsePullEntry(it) }
        return RelayMemoryPullResponse(
            serverSeq = serverSeq,
            hasMore = hasMore,
            nextSince = nextSince,
            lastDreamAt = lastDreamAt,
            entries = entries,
        )
    }

    fun memoryDreamLeaseRequest(ttlMs: Long, minIntervalMs: Long): String = buildJsonObject {
        put("ttlMs", ttlMs)
        if (minIntervalMs > 0L) put("minIntervalMs", minIntervalMs)
    }.toString()

    fun parseMemoryDreamLeaseResponse(body: String): RelayMemoryDreamLease? {
        val json = parseObject(body) ?: return null
        val acquired = json.bool("acquired") ?: return null
        val expiresAt = json.long("expiresAt") ?: 0L
        return RelayMemoryDreamLease(
            acquired = acquired,
            leaseId = json.string("leaseId"),
            holderDeviceId = json.string("holderDeviceId"),
            expiresAt = expiresAt,
            lastDreamAt = json.long("lastDreamAt"),
            serverSeq = json.long("serverSeq") ?: 0L,
        )
    }

    fun parseMemoryDreamLeaseRelease(body: String): Boolean {
        val json = parseObject(body) ?: return false
        return json.bool("released") ?: false
    }

    fun parseErrorCode(body: String): String? {
        val json = parseObject(body) ?: return null
        return json.string("code")
    }

    fun parseErrorMessage(body: String): String? {
        val json = parseObject(body) ?: return null
        return json.string("message") ?: json.string("code")
    }

    private fun encodeSyncOp(op: MemorySyncOpItem): JsonObject = buildJsonObject {
        put("opId", op.opId)
        put("kind", op.kind.wire)
        if (op.kind == MemorySyncOpKind.MEMORY && op.memory != null) {
            put("memory", encodeMemory(op.memory!!))
        }
        if (op.kind == MemorySyncOpKind.ENTITY && op.entity != null) {
            put("entity", encodeEntity(op.entity!!))
        }
    }

    private fun encodeMemory(memory: MemorySyncRecord): JsonObject = buildJsonObject {
        put("id", memory.id)
        put("kind", memory.kind.wire)
        put("content", memory.content)
        if (memory.summary != null) put("summary", memory.summary) else put("summary", JsonNull)
        put("tags", JsonArray(memory.tags.map { JsonPrimitive(it) }))
        put("entities", JsonArray(memory.entities.map { JsonPrimitive(it) }))
        put("confidence", memory.confidence.wire)
        put("importance", memory.importance)
        put("pinned", memory.pinned)
        put("validFrom", memory.validFrom)
        if (memory.validUntil != null) put("validUntil", memory.validUntil) else put("validUntil", JsonNull)
        put("status", memory.status.wire)
        if (memory.supersededBy != null) put("supersededBy", memory.supersededBy) else put("supersededBy", JsonNull)
        put("sourceKind", memory.sourceKind.wire)
        if (memory.sourceSessionId != null) put("sourceSessionId", memory.sourceSessionId) else put("sourceSessionId", JsonNull)
        if (memory.sourceEntryId != null) put("sourceEntryId", memory.sourceEntryId) else put("sourceEntryId", JsonNull)
        if (memory.sourceQuote != null) put("sourceQuote", memory.sourceQuote) else put("sourceQuote", JsonNull)
        put("createdAt", memory.createdAt)
        put("updatedAt", memory.updatedAt)
        if (memory.deletedAt != null) put("deletedAt", memory.deletedAt) else put("deletedAt", JsonNull)
        put("revision", memory.revision)
        if (memory.updatedByDeviceId != null) put("updatedByDeviceId", memory.updatedByDeviceId)
    }

    private fun encodeEntity(entity: EntitySyncRecord): JsonObject = buildJsonObject {
        put("canonicalKey", entity.canonicalKey)
        put("id", entity.id)
        put("canonicalName", entity.canonicalName)
        put("kind", entity.kind.wire)
        put("aliases", JsonArray(entity.aliases.map { JsonPrimitive(it) }))
        if (entity.summary != null) put("summary", entity.summary) else put("summary", JsonNull)
        if (entity.mergedInto != null) put("mergedInto", entity.mergedInto) else put("mergedInto", JsonNull)
        put("updatedAt", entity.updatedAt)
        if (entity.deletedAt != null) put("deletedAt", entity.deletedAt) else put("deletedAt", JsonNull)
        put("revision", entity.revision)
        if (entity.updatedByDeviceId != null) put("updatedByDeviceId", entity.updatedByDeviceId)
    }

    private fun parsePullEntry(element: JsonElement): MemoryRemotePullEntry? {
        val obj = element as? JsonObject ?: return null
        val seq = obj.long("seq") ?: return null
        val kind = MemorySyncOpKind.fromWire(obj.string("kind")) ?: return null
        val deviceId = obj.string("deviceId") ?: return null
        return when (kind) {
            MemorySyncOpKind.MEMORY -> {
                val mem = parseMemoryRecord(obj["memory"] as? JsonObject ?: return null) ?: return null
                MemoryRemotePullEntry(seq = seq, kind = kind, deviceId = deviceId, memory = mem)
            }
            MemorySyncOpKind.ENTITY -> {
                val ent = parseEntityRecord(obj["entity"] as? JsonObject ?: return null) ?: return null
                MemoryRemotePullEntry(seq = seq, kind = kind, deviceId = deviceId, entity = ent)
            }
        }
    }

    private fun parseMemoryRecord(obj: JsonObject): MemorySyncRecord? {
        val id = obj.string("id") ?: return null
        val kind = MemoryKind.fromWire(obj.string("kind")) ?: return null
        val content = obj.string("content") ?: return null
        val confidence = MemoryConfidence.fromWire(obj.string("confidence"))
        val importance = obj.int("importance") ?: 3
        val pinned = obj.bool("pinned") ?: false
        val validFrom = obj.long("validFrom") ?: return null
        val status = MemoryItemStatus.fromWire(obj.string("status"))
        val sourceKind = MemorySourceKind.fromWire(obj.string("sourceKind"))
        val createdAt = obj.long("createdAt") ?: return null
        val updatedAt = obj.long("updatedAt") ?: return null
        val revision = obj.long("revision") ?: 1L
        return MemorySyncRecord(
            id = id,
            kind = kind,
            content = content,
            summary = obj.string("summary"),
            tags = obj.stringArray("tags"),
            entities = obj.stringArray("entities"),
            confidence = confidence,
            importance = importance,
            pinned = pinned,
            validFrom = validFrom,
            validUntil = obj.long("validUntil"),
            status = status,
            supersededBy = obj.string("supersededBy"),
            sourceKind = sourceKind,
            sourceSessionId = obj.string("sourceSessionId"),
            sourceEntryId = obj.string("sourceEntryId"),
            sourceQuote = obj.string("sourceQuote"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = obj.long("deletedAt"),
            revision = revision,
            updatedByDeviceId = obj.string("updatedByDeviceId"),
        )
    }

    private fun parseEntityRecord(obj: JsonObject): EntitySyncRecord? {
        val id = obj.string("id") ?: return null
        val canonicalName = obj.string("canonicalName") ?: return null
        val canonicalKey = obj.string("canonicalKey")?.lowercase() ?: canonicalName.trim().lowercase()
        val kind = EntityKind.fromWire(obj.string("kind"))
        val updatedAt = obj.long("updatedAt") ?: return null
        val revision = obj.long("revision") ?: 1L
        return EntitySyncRecord(
            canonicalKey = canonicalKey,
            id = id,
            canonicalName = canonicalName,
            kind = kind,
            aliases = obj.stringArray("aliases"),
            summary = obj.string("summary"),
            mergedInto = obj.string("mergedInto"),
            updatedAt = updatedAt,
            deletedAt = obj.long("deletedAt"),
            revision = revision,
            updatedByDeviceId = obj.string("updatedByDeviceId"),
        )
    }

    private fun parseEntries(element: JsonElement?): List<RelayEntry> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { entry ->
            if (entry !is JsonObject) return@mapNotNull null
            val origin = entry.string("origin") ?: return@mapNotNull null
            val kind = when (entry.string("kind")) {
                "lan" -> RelayEntryKind.Lan
                "public" -> RelayEntryKind.Public
                else -> null
            } ?: return@mapNotNull null
            RelayEntry(kind = kind, origin = origin)
        }
    }

    private fun parseObject(body: String): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringArray(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
