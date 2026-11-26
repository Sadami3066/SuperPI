package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryEventTimePrecision
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.memory.MemoryBlockSyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryEntitySyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryEventEntityLink
import io.github.whywood.superpi.runtime.memory.MemoryEventSyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryProposalSyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryRelationSyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryRemotePullEntry
import io.github.whywood.superpi.runtime.memory.MemorySyncKind
import io.github.whywood.superpi.runtime.memory.MemorySyncOpItem
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.EMPTY
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer

sealed interface RelayMemoryOutcome<out T> {
    data class Ok<T>(val value: T) : RelayMemoryOutcome<T>

    data object Offline : RelayMemoryOutcome<Nothing>

    data object Unauthorized : RelayMemoryOutcome<Nothing>

    data class Rejected(val code: String, val message: String) : RelayMemoryOutcome<Nothing>
}

data class MemoryV3PushRejectedOp(
    val opId: String,
    val reason: String,
)

data class MemoryV3PushResult(
    val serverSeq: Long,
    val appliedCount: Int,
    val skippedCount: Int,
    val lastConsolidationAt: Long?,
    val rejected: List<MemoryV3PushRejectedOp> = emptyList(),
)

data class MemoryV3PullResult(
    val serverSeq: Long,
    val hasMore: Boolean,
    val nextSince: Long,
    val lastConsolidationAt: Long?,
    val entries: List<MemoryRemotePullEntry>,
)

data class ConsolidationLeaseAcquireResult(
    val acquired: Boolean,
    val leaseId: String? = null,
    val holderDeviceId: String? = null,
    val expiresAt: Long,
    val lastConsolidationAt: Long? = null,
    val serverSeq: Long,
)

open class RelayMemoryClient(
    private val tokenProvider: suspend () -> String? = { null },
    private val clientFactory: (RelayAccount) -> OkHttpClient = ::defaultHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    open suspend fun push(
        account: RelayAccount,
        ops: List<MemorySyncOpItem>,
        consolidationCompletedAt: Long? = null,
    ): RelayMemoryOutcome<MemoryV3PushResult> = withContext(ioDispatcher) {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext RelayMemoryOutcome.Unauthorized
        val payload = encodePushRequest(ops, consolidationCompletedAt)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > MAX_MEMORY_BODY_BYTES) {
            return@withContext RelayMemoryOutcome.Rejected("payload_too_large", "memory push body exceeds 4 MiB limit")
        }
        val request = Request.Builder()
            .url(RelayProtocol.memoryPushUrl(account.origin))
            .header("authorization", "Bearer $token")
            .post(payloadBytes.toRequestBody(JSON))
            .build()
        executeRequest(account, request, ::parsePushResponse)
    }

    open suspend fun pull(
        account: RelayAccount,
        since: Long,
        limit: Int = 200,
    ): RelayMemoryOutcome<MemoryV3PullResult> = withContext(ioDispatcher) {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext RelayMemoryOutcome.Unauthorized
        val request = Request.Builder()
            .url(RelayProtocol.memoryPullUrl(account.origin, since, limit))
            .header("authorization", "Bearer $token")
            .get()
            .build()
        executeRequest(account, request, ::parsePullResponse)
    }

    open suspend fun acquireConsolidationLease(
        account: RelayAccount,
        minIntervalMs: Long,
        force: Boolean = false,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
    ): RelayMemoryOutcome<ConsolidationLeaseAcquireResult> = withContext(ioDispatcher) {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext RelayMemoryOutcome.Unauthorized
        val body = buildJsonObject {
            put("ttlMs", ttlMs.coerceAtLeast(1_000L))
            put("minIntervalMs", if (force) 0L else minIntervalMs.coerceAtLeast(0L))
        }.toString()
        val request = Request.Builder()
            .url(RelayProtocol.memoryConsolidationLeaseUrl(account.origin))
            .header("authorization", "Bearer $token")
            .post(body.toRequestBody(JSON))
            .build()
        executeRequest(account, request, ::parseLeaseAcquireResponse)
    }

    open suspend fun releaseConsolidationLease(
        account: RelayAccount,
        leaseId: String,
    ): RelayMemoryOutcome<Boolean> = withContext(ioDispatcher) {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return@withContext RelayMemoryOutcome.Unauthorized
        val request = Request.Builder()
            .url(RelayProtocol.memoryConsolidationLeaseUrl(account.origin, leaseId))
            .header("authorization", "Bearer $token")
            .delete(EMPTY)
            .build()
        executeRequest(account, request) { text ->
            val obj = parseObject(text) ?: return@executeRequest null
            (obj["released"] as? JsonPrimitive)?.booleanOrNull ?: true
        }
    }

    private fun <T> executeRequest(
        account: RelayAccount,
        request: Request,
        parser: (String) -> T?,
    ): RelayMemoryOutcome<T> {
        val client = clientFactory(account)
        return try {
            client.newCall(request).execute().use { response ->
                val text = readBoundedBody(response.body)
                    ?: return@use RelayMemoryOutcome.Rejected("payload_too_large", "response exceeds 4 MiB limit")
                when {
                    response.isSuccessful -> {
                        val parsed = parser(text)
                        if (parsed != null) {
                            RelayMemoryOutcome.Ok(parsed)
                        } else {
                            RelayMemoryOutcome.Rejected("bad_response", "invalid memory v3 payload")
                        }
                    }

                    response.code == 401 || response.code == 403 -> RelayMemoryOutcome.Unauthorized
                    else -> {
                        val code = RelayProtocol.parseErrorCode(text) ?: "http_${response.code}"
                        val message = RelayProtocol.parseErrorMessage(text) ?: "request failed (${response.code})"
                        RelayMemoryOutcome.Rejected(code, message)
                    }
                }
            }
        } catch (_: SSLException) {
            RelayMemoryOutcome.Offline
        } catch (_: IOException) {
            RelayMemoryOutcome.Offline
        }
    }

    private fun readBoundedBody(body: ResponseBody): String? {
        val advertised = body.contentLength()
        if (advertised > MAX_MEMORY_BODY_BYTES) return null
        val source = body.source()
        val buffer = Buffer()
        var remaining = MAX_MEMORY_BODY_BYTES.toLong() + 1L
        while (remaining > 0L) {
            val read = source.read(buffer, minOf(remaining, 8_192L))
            if (read == -1L) break
            remaining -= read
            if (buffer.size > MAX_MEMORY_BODY_BYTES) {
                return null
            }
        }
        return buffer.readUtf8()
    }

    internal fun encodePushRequest(
        ops: List<MemorySyncOpItem>,
        consolidationCompletedAt: Long?,
    ): String = buildJsonObject {
        put(
            "ops",
            buildJsonArray {
                ops.forEach { op ->
                    addJsonObject {
                        put("opId", op.opId)
                        put("kind", op.kind.wire)
                        when (op.kind) {
                            MemorySyncKind.BLOCK -> op.block?.let { put("block", encodeBlock(it)) }
                            MemorySyncKind.ENTITY -> op.entity?.let { put("entity", encodeEntity(it)) }
                            MemorySyncKind.EVENT -> op.event?.let { put("event", encodeEvent(it)) }
                            MemorySyncKind.RELATION -> op.relation?.let { put("relation", encodeRelation(it)) }
                            MemorySyncKind.PROPOSAL -> op.proposal?.let { put("proposal", encodeProposal(it)) }
                        }
                    }
                }
            },
        )
        if (consolidationCompletedAt != null && consolidationCompletedAt > 0L) {
            put("consolidationCompletedAt", consolidationCompletedAt)
        }
    }.toString()

    internal fun parsePushResponse(raw: String): MemoryV3PushResult? {
        val obj = parseObject(raw) ?: return null
        val serverSeq = obj.long("serverSeq") ?: return null
        val appliedCount = obj.int("appliedCount") ?: return null
        val skippedCount = obj.int("skippedCount") ?: return null
        val lastConsolidationAt = obj.long("lastConsolidationAt")
        val rejected = (obj["rejected"] as? JsonArray)?.mapNotNull { item ->
            val r = item as? JsonObject ?: return@mapNotNull null
            val opId = r.str("opId") ?: return@mapNotNull null
            val reason = r.str("reason") ?: "rejected"
            MemoryV3PushRejectedOp(opId = opId, reason = reason)
        }.orEmpty()
        return MemoryV3PushResult(
            serverSeq = serverSeq,
            appliedCount = appliedCount,
            skippedCount = skippedCount,
            lastConsolidationAt = lastConsolidationAt,
            rejected = rejected,
        )
    }

    internal fun parsePullResponse(raw: String): MemoryV3PullResult? {
        val obj = parseObject(raw) ?: return null
        val serverSeq = obj.long("serverSeq") ?: return null
        val hasMore = obj.bool("hasMore") ?: return null
        val nextSince = obj.long("nextSince") ?: return null
        val lastConsolidationAt = obj.long("lastConsolidationAt")
        val entriesArray = obj["entries"] as? JsonArray ?: return null
        val entries = ArrayList<MemoryRemotePullEntry>(entriesArray.size)
        for (element in entriesArray) {
            val item = element as? JsonObject ?: return null
            val parsed = decodePullEntry(item) ?: return null
            entries += parsed
        }
        return MemoryV3PullResult(
            serverSeq = serverSeq,
            hasMore = hasMore,
            nextSince = nextSince,
            lastConsolidationAt = lastConsolidationAt,
            entries = entries,
        )
    }

    internal fun parseLeaseAcquireResponse(raw: String): ConsolidationLeaseAcquireResult? {
        val obj = parseObject(raw) ?: return null
        val acquired = obj.bool("acquired") ?: return null
        val expiresAt = obj.long("expiresAt") ?: return null
        val serverSeq = obj.long("serverSeq") ?: return null
        return ConsolidationLeaseAcquireResult(
            acquired = acquired,
            leaseId = obj.str("leaseId"),
            holderDeviceId = obj.str("holderDeviceId"),
            expiresAt = expiresAt,
            lastConsolidationAt = obj.long("lastConsolidationAt"),
            serverSeq = serverSeq,
        )
    }

    private fun decodePullEntry(obj: JsonObject): MemoryRemotePullEntry? {
        val seq = obj.long("seq") ?: return null
        val kind = MemorySyncKind.fromWire(obj.str("kind")) ?: return null
        val deviceId = obj.str("deviceId") ?: return null
        return when (kind) {
            MemorySyncKind.BLOCK -> {
                val blockObj = obj["block"] as? JsonObject ?: return null
                val block = decodeBlock(blockObj) ?: return null
                MemoryRemotePullEntry(seq = seq, kind = kind, deviceId = deviceId, block = block)
            }

            MemorySyncKind.ENTITY -> {
                val entityObj = obj["entity"] as? JsonObject ?: return null
                val entity = decodeEntity(entityObj) ?: return null
                MemoryRemotePullEntry(seq = seq, kind = kind, deviceId = deviceId, entity = entity)
            }

            MemorySyncKind.EVENT -> {
                val eventObj = obj["event"] as? JsonObject ?: return null
                val event = decodeEvent(eventObj) ?: return null
                MemoryRemotePullEntry(seq = seq, kind = kind, deviceId = deviceId, event = event)
            }

            MemorySyncKind.RELATION -> {
                val relationObj = obj["relation"] as? JsonObject ?: return null
                val relation = decodeRelation(relationObj) ?: return null
                MemoryRemotePullEntry(seq = seq, kind = kind, deviceId = deviceId, relation = relation)
            }

            MemorySyncKind.PROPOSAL -> {
                val proposalObj = obj["proposal"] as? JsonObject ?: return null
                val proposal = decodeProposal(proposalObj) ?: return null
                MemoryRemotePullEntry(seq = seq, kind = kind, deviceId = deviceId, proposal = proposal)
            }
        }
    }

    private fun encodeBlock(record: MemoryBlockSyncRecord): JsonObject = buildJsonObject {
        put("id", record.id)
        put("slug", record.slug)
        put("title", record.title)
        put("purpose", record.purpose)
        putNullableText("schemaHint", record.schemaHint)
        put("content", record.content)
        put("domain", record.domain)
        put("sensitivity", record.sensitivity.wire)
        put("injectionMode", record.injectionMode.wire)
        put("maxChars", record.maxChars)
        put("owner", record.owner.wire)
        put("pinned", record.pinned)
        put("status", record.status.wire)
        put("sortOrder", record.sortOrder)
        put("createdAt", record.createdAt)
        put("updatedAt", record.updatedAt)
        putNullableLong("deletedAt", record.deletedAt)
        put("revision", record.revision)
        putNullableText("updatedByDeviceId", record.updatedByDeviceId)
    }

    private fun decodeBlock(obj: JsonObject): MemoryBlockSyncRecord? {
        val id = obj.str("id") ?: return null
        val slug = obj.str("slug") ?: return null
        val title = obj.str("title") ?: return null
        val purpose = obj.str("purpose") ?: return null
        val domain = obj.str("domain") ?: return null
        val maxChars = obj.int("maxChars") ?: return null
        val pinned = obj.bool("pinned") ?: return null
        val sortOrder = obj.int("sortOrder") ?: return null
        val createdAt = obj.long("createdAt") ?: return null
        val updatedAt = obj.long("updatedAt") ?: return null
        val revision = obj.long("revision") ?: return null
        return MemoryBlockSyncRecord(
            id = id,
            slug = slug,
            title = title,
            purpose = purpose,
            schemaHint = obj.str("schemaHint"),
            content = obj.rawStr("content") ?: "",
            domain = domain,
            sensitivity = MemorySensitivity.fromWire(obj.str("sensitivity")),
            injectionMode = MemoryBlockInjectionMode.fromWire(obj.str("injectionMode")),
            maxChars = maxChars,
            owner = MemoryBlockOwner.fromWire(obj.str("owner")),
            pinned = pinned,
            status = MemoryBlockStatus.fromWire(obj.str("status")),
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = obj.long("deletedAt"),
            revision = revision,
            updatedByDeviceId = obj.str("updatedByDeviceId"),
        )
    }

    private fun encodeEntity(record: MemoryEntitySyncRecord): JsonObject = buildJsonObject {
        put("id", record.id)
        put("canonicalName", record.canonicalName)
        put("entityType", record.entityType)
        put("aliases", encodeStrings(record.aliases))
        put("attributesJson", record.attributesJson)
        putNullableText("summary", record.summary)
        put("domain", record.domain)
        put("sensitivity", record.sensitivity.wire)
        put("pinned", record.pinned)
        put("createdAt", record.createdAt)
        put("updatedAt", record.updatedAt)
        putNullableLong("deletedAt", record.deletedAt)
        put("revision", record.revision)
        putNullableText("mergedInto", record.mergedInto)
        putNullableText("updatedByDeviceId", record.updatedByDeviceId)
    }

    private fun decodeEntity(obj: JsonObject): MemoryEntitySyncRecord? {
        val id = obj.str("id") ?: return null
        val canonicalName = obj.str("canonicalName") ?: return null
        val entityType = obj.str("entityType") ?: return null
        val domain = obj.str("domain") ?: return null
        val pinned = obj.bool("pinned") ?: return null
        val createdAt = obj.long("createdAt") ?: return null
        val updatedAt = obj.long("updatedAt") ?: return null
        val revision = obj.long("revision") ?: return null
        return MemoryEntitySyncRecord(
            id = id,
            canonicalName = canonicalName,
            entityType = entityType,
            aliases = decodeStrings(obj["aliases"]),
            attributesJson = obj.rawStr("attributesJson") ?: "{}",
            summary = obj.str("summary"),
            domain = domain,
            sensitivity = MemorySensitivity.fromWire(obj.str("sensitivity")),
            pinned = pinned,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = obj.long("deletedAt"),
            revision = revision,
            mergedInto = obj.str("mergedInto"),
            updatedByDeviceId = obj.str("updatedByDeviceId"),
        )
    }

    private fun encodeEvent(record: MemoryEventSyncRecord): JsonObject = buildJsonObject {
        put("id", record.id)
        put("eventKind", record.eventKind.wire)
        put("content", record.content)
        putNullableText("summary", record.summary)
        put("qualifiersJson", record.qualifiersJson)
        put("semanticKeywords", encodeStrings(record.semanticKeywords))
        put("tags", encodeStrings(record.tags))
        put("domain", record.domain)
        put("sensitivity", record.sensitivity.wire)
        put("epistemicTier", record.epistemicTier.wire)
        put("importance", record.importance)
        put("pinned", record.pinned)
        put("eventTime", record.eventTime)
        put("eventTimePrecision", record.eventTimePrecision.wire)
        put("validFrom", record.validFrom)
        putNullableLong("validUntil", record.validUntil)
        put("status", record.status.wire)
        putNullableText("supersededBy", record.supersededBy)
        put("sourceKind", record.sourceKind.wire)
        putNullableText("sourceSessionId", record.sourceSessionId)
        putNullableText("sourceEntryId", record.sourceEntryId)
        putNullableText("sourceQuote", record.sourceQuote)
        put(
            "entityLinks",
            buildJsonArray {
                record.entityLinks.forEach { link ->
                    addJsonObject {
                        put("entityId", link.entityId)
                        put("role", link.role)
                    }
                }
            },
        )
        put("createdAt", record.createdAt)
        put("updatedAt", record.updatedAt)
        putNullableLong("deletedAt", record.deletedAt)
        put("revision", record.revision)
        putNullableText("updatedByDeviceId", record.updatedByDeviceId)
    }

    private fun decodeEvent(obj: JsonObject): MemoryEventSyncRecord? {
        val id = obj.str("id") ?: return null
        val eventKind = MemoryEventKind.fromWire(obj.str("eventKind")) ?: return null
        val content = obj.str("content") ?: return null
        val domain = obj.str("domain") ?: return null
        val importance = obj.int("importance") ?: return null
        val pinned = obj.bool("pinned") ?: return null
        val eventTime = obj.long("eventTime") ?: return null
        val validFrom = obj.long("validFrom") ?: return null
        val createdAt = obj.long("createdAt") ?: return null
        val updatedAt = obj.long("updatedAt") ?: return null
        val revision = obj.long("revision") ?: return null
        val links = (obj["entityLinks"] as? JsonArray)?.mapNotNull { item ->
            val linkObj = item as? JsonObject ?: return@mapNotNull null
            val entityId = linkObj.str("entityId") ?: return@mapNotNull null
            val role = linkObj.str("role") ?: "MENTIONED"
            MemoryEventEntityLink(entityId = entityId, role = role)
        }.orEmpty()
        return MemoryEventSyncRecord(
            id = id,
            eventKind = eventKind,
            content = content,
            summary = obj.str("summary"),
            qualifiersJson = obj.rawStr("qualifiersJson") ?: "{}",
            semanticKeywords = decodeStrings(obj["semanticKeywords"]),
            tags = decodeStrings(obj["tags"]),
            domain = domain,
            sensitivity = MemorySensitivity.fromWire(obj.str("sensitivity")),
            epistemicTier = MemoryEpistemicTier.fromWire(obj.str("epistemicTier")),
            importance = importance,
            pinned = pinned,
            eventTime = eventTime,
            eventTimePrecision = MemoryEventTimePrecision.fromWire(obj.str("eventTimePrecision")),
            validFrom = validFrom,
            validUntil = obj.long("validUntil"),
            status = MemoryItemStatus.fromWire(obj.str("status")),
            supersededBy = obj.str("supersededBy"),
            sourceKind = MemorySourceKind.fromWire(obj.str("sourceKind")),
            sourceSessionId = obj.str("sourceSessionId"),
            sourceEntryId = obj.str("sourceEntryId"),
            sourceQuote = obj.str("sourceQuote"),
            entityLinks = links,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = obj.long("deletedAt"),
            revision = revision,
            updatedByDeviceId = obj.str("updatedByDeviceId"),
        )
    }

    private fun encodeRelation(record: MemoryRelationSyncRecord): JsonObject = buildJsonObject {
        put("id", record.id)
        put("fromEntityId", record.fromEntityId)
        put("relationType", record.relationType)
        put("toEntityId", record.toEntityId)
        putNullableText("label", record.label)
        put("attributesJson", record.attributesJson)
        put("validFrom", record.validFrom)
        putNullableLong("validUntil", record.validUntil)
        putNullableLong("invalidatedAt", record.invalidatedAt)
        putNullableText("invalidatedBy", record.invalidatedBy)
        put("epistemicTier", record.epistemicTier.wire)
        put("domain", record.domain)
        put("sensitivity", record.sensitivity.wire)
        putNullableText("sourceEventId", record.sourceEventId)
        put("createdAt", record.createdAt)
        put("updatedAt", record.updatedAt)
        putNullableLong("deletedAt", record.deletedAt)
        put("revision", record.revision)
        putNullableText("updatedByDeviceId", record.updatedByDeviceId)
    }

    private fun decodeRelation(obj: JsonObject): MemoryRelationSyncRecord? {
        val id = obj.str("id") ?: return null
        val fromEntityId = obj.str("fromEntityId") ?: return null
        val relationType = obj.str("relationType") ?: return null
        val toEntityId = obj.str("toEntityId") ?: return null
        val validFrom = obj.long("validFrom") ?: return null
        val domain = obj.str("domain") ?: return null
        val createdAt = obj.long("createdAt") ?: return null
        val updatedAt = obj.long("updatedAt") ?: return null
        val revision = obj.long("revision") ?: return null
        return MemoryRelationSyncRecord(
            id = id,
            fromEntityId = fromEntityId,
            relationType = relationType,
            toEntityId = toEntityId,
            label = obj.str("label"),
            attributesJson = obj.rawStr("attributesJson") ?: "{}",
            validFrom = validFrom,
            validUntil = obj.long("validUntil"),
            invalidatedAt = obj.long("invalidatedAt"),
            invalidatedBy = obj.str("invalidatedBy"),
            epistemicTier = MemoryEpistemicTier.fromWire(obj.str("epistemicTier")),
            domain = domain,
            sensitivity = MemorySensitivity.fromWire(obj.str("sensitivity")),
            sourceEventId = obj.str("sourceEventId"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = obj.long("deletedAt"),
            revision = revision,
            updatedByDeviceId = obj.str("updatedByDeviceId"),
        )
    }

    private fun encodeProposal(record: MemoryProposalSyncRecord): JsonObject = buildJsonObject {
        put("id", record.id)
        put("slug", record.slug)
        put("title", record.title)
        put("purpose", record.purpose)
        put("domain", record.domain)
        put("sensitivity", record.sensitivity.wire)
        put("seedContent", record.seedContent)
        put("supportingEventIds", encodeStrings(record.supportingEventIds))
        put("reason", record.reason)
        put("status", record.status.wire)
        put("createdAt", record.createdAt)
        putNullableLong("resolvedAt", record.resolvedAt)
        put("updatedAt", record.updatedAt)
        putNullableLong("deletedAt", record.deletedAt)
        put("revision", record.revision)
        putNullableText("updatedByDeviceId", record.updatedByDeviceId)
    }

    private fun decodeProposal(obj: JsonObject): MemoryProposalSyncRecord? {
        val id = obj.str("id") ?: return null
        val slug = obj.str("slug") ?: return null
        val title = obj.str("title") ?: return null
        val purpose = obj.str("purpose") ?: return null
        val domain = obj.str("domain") ?: return null
        val reason = obj.str("reason") ?: return null
        val createdAt = obj.long("createdAt") ?: return null
        val updatedAt = obj.long("updatedAt") ?: return null
        val revision = obj.long("revision") ?: return null
        return MemoryProposalSyncRecord(
            id = id,
            slug = slug,
            title = title,
            purpose = purpose,
            domain = domain,
            sensitivity = MemorySensitivity.fromWire(obj.str("sensitivity")),
            seedContent = obj.rawStr("seedContent") ?: "",
            supportingEventIds = decodeStrings(obj["supportingEventIds"]),
            reason = reason,
            status = MemoryProposalStatus.fromWire(obj.str("status")),
            createdAt = createdAt,
            resolvedAt = obj.long("resolvedAt"),
            updatedAt = updatedAt,
            deletedAt = obj.long("deletedAt"),
            revision = revision,
            updatedByDeviceId = obj.str("updatedByDeviceId"),
        )
    }

    private fun encodeStrings(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun decodeStrings(element: JsonElement?): List<String> =
        (element as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableText(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(key: String, value: Long?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun parseObject(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.rawStr(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    companion object {
        private const val MAX_MEMORY_BODY_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_LEASE_TTL_MS = 10 * 60 * 1_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(account: RelayAccount): OkHttpClient {
            val pin = account.caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
            return RelayTls.client(pin = pin, remote = true)
        }
    }
}
