package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockProposal
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchMode
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class MemoryProposalDto(
    val id: String,
    val slug: String,
    val title: String,
    val purpose: String,
    val domain: String,
    val seedContent: String,
    val reason: String,
    val status: String,
    val createdAt: Long,
)

@Serializable
data class MemoryPreferencesDto(
    val mode: String = "AUTO",
    val hotProviderId: String? = null,
    val hotModelId: String? = null,
    val coldProviderId: String? = null,
    val coldModelId: String? = null,
    val hotIdleDebounceSeconds: Int = 60,
    val coldConsolidationHours: Int = 12,
    val injectionMaxChars: Int = 16_000,
    val profileBudgetChars: Int? = null,
    val relationsBudgetChars: Int? = null,
    val eventsBudgetChars: Int? = null,
    val directoryBudgetChars: Int? = null,
    val guidance: String = "",
)

@Serializable
data class MemoryFullSnapshotResponse(
    val overview: MemoryOverviewDto,
    val blocks: List<MemoryBlockDto>,
    val proposals: List<MemoryProposalDto> = emptyList(),
    val entities: List<MemoryEntityDto>,
    val relations: List<MemoryRelationDto>,
    val events: List<MemoryEventDto>,
    val auditLog: List<MemoryAuditDto>,
    val preferences: MemoryPreferencesDto,
    val consolidating: Boolean = false,
    val syncStatus: MemorySyncStatusDto = MemorySyncStatusDto(),
)

@Serializable
data class CreateMemoryBlockRequest(
    val title: String,
    val purpose: String = "",
    val content: String = "",
    val domain: String = "CUSTOM",
    val pinned: Boolean = false,
)

@Serializable
data class PatchMemoryBlockRequest(
    val title: String? = null,
    val purpose: String? = null,
    val content: String? = null,
    val domain: String? = null,
    val pinned: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class CreateMemoryEntityRequest(
    val canonicalName: String,
    val entityType: String = "TOPIC",
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val domain: String = "PERSONAL",
)

@Serializable
data class UpdateMemoryEntityRequest(
    val canonicalName: String? = null,
    val entityType: String? = null,
    val aliases: List<String>? = null,
    val summary: String? = null,
    val clearSummary: Boolean = false,
    val domain: String? = null,
    val pinned: Boolean? = null,
)

@Serializable
data class MergeMemoryEntitiesRequest(
    val keepId: String,
    val mergeIds: List<String>,
    val mergedSummary: String? = null,
)

@Serializable
data class CreateMemoryRelationRequest(
    val fromEntityName: String,
    val relationType: String = "related_to",
    val toEntityName: String,
    val label: String? = null,
    val domain: String = "PERSONAL",
)

@Serializable
data class CreateMemoryEventRequest(
    val eventKind: String = "FACT",
    val content: String,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    val domain: String = "PERSONAL",
    val importance: Int = 3,
    val pinned: Boolean = false,
)

@Serializable
data class UpdateMemoryEventRequest(
    val eventKind: String? = null,
    val content: String? = null,
    val summary: String? = null,
    val tags: List<String>? = null,
    val domain: String? = null,
    val pinned: Boolean? = null,
)

@Serializable
data class UpdateMemoryPreferencesRequest(
    val mode: String? = null,
    val hotProviderId: String? = null,
    val hotModelId: String? = null,
    val coldProviderId: String? = null,
    val coldModelId: String? = null,
    val clearHotModel: Boolean = false,
    val clearColdModel: Boolean = false,
    val hotIdleDebounceSeconds: Int? = null,
    val coldConsolidationHours: Int? = null,
    val injectionMaxChars: Int? = null,
    val profileBudgetChars: Int? = null,
    val relationsBudgetChars: Int? = null,
    val eventsBudgetChars: Int? = null,
    val directoryBudgetChars: Int? = null,
    val clearSectionBudgets: Boolean = false,
    val guidance: String? = null,
)

internal fun Route.installMemoryRoutes(controller: SuperPiController) {
    get("/v1/memory/snapshot") {
        val includeArchived = call.request.queryParameters["includeArchived"]?.equals("true", ignoreCase = true) == true
        val includeHistory = call.request.queryParameters["includeHistory"]?.equals("true", ignoreCase = true) == true
        val query = call.request.queryParameters["query"]?.trim()?.takeIf { it.isNotEmpty() }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(10, 400) ?: 120
        controller.memoryStore.ensure()
        val overview = controller.memoryStore.overview()
        val blocks = controller.memoryStore.listBlocks(includeArchived = includeArchived)
        val proposals = controller.memoryStore.listProposals(MemoryProposalStatus.PENDING)
        val entities = controller.memoryStore.listEntities()
        val relations = controller.memoryStore.listRelations()
        val events = controller.memoryStore.listEvents(
            MemoryEventFilter(query = query, limit = limit, includeHistory = includeHistory),
        )
        val audit = controller.memoryStore.listAuditLog(limit = 60)
        val prefs = readMemoryPrefsDto(controller)
        call.respond(
            MemoryFullSnapshotResponse(
                overview = overview.toDto(),
                blocks = blocks.map { it.toDto() },
                proposals = proposals.map { it.toDto() },
                entities = entities.map { it.toDto() },
                relations = relations.map { it.toDto() },
                events = events.map { it.toDto() },
                auditLog = audit.map { it.toDto() },
                preferences = prefs,
                consolidating = controller.memoryAgentService.manualState.value.running,
                syncStatus = readMemorySyncStatusDto(controller),
            ),
        )
    }

    // ── Blocks & Proposals ───────────────────────────────────────────────────
    post("/v1/memory/blocks") {
        val req = call.receive<CreateMemoryBlockRequest>()
        val title = req.title.trim()
        if (title.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Block title is required"))
            return@post
        }
        controller.memoryStore.ensure()
        val slug = slugify(title)
        val created = controller.memoryStore.createBlock(
            MemoryBlockDraft(
                slug = slug,
                title = title,
                purpose = req.purpose.trim().ifBlank { title },
                content = req.content,
                domain = MemoryDomains.normalize(req.domain, MemoryDomains.CUSTOM),
                pinned = req.pinned,
            ),
        )
        call.respond(HttpStatusCode.Created, created.toDto())
    }

    put("/v1/memory/blocks/{id}") {
        val id = call.parameters["id"].orEmpty()
        val req = call.receive<PatchMemoryBlockRequest>()
        controller.memoryStore.ensure()
        val updated = controller.memoryStore.updateBlock(
            id = id,
            title = req.title?.trim()?.takeIf { it.isNotEmpty() },
            purpose = req.purpose?.trim(),
            content = req.content,
            domain = req.domain?.let { MemoryDomains.normalize(it) },
            pinned = req.pinned,
            status = req.status?.let { MemoryBlockStatus.fromWire(it) },
        )
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Memory block not found: $id"))
        } else {
            call.respond(updated.toDto())
        }
    }

    delete("/v1/memory/blocks/{id}") {
        val id = call.parameters["id"].orEmpty()
        controller.memoryStore.ensure()
        val deleted = controller.memoryStore.deleteCustomBlock(id)
        if (!deleted) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Block not found or cannot delete built-in block"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    post("/v1/memory/proposals/{id}/accept") {
        val id = call.parameters["id"].orEmpty()
        controller.memoryStore.ensure()
        val block = controller.memoryStore.acceptProposal(id)
        if (block == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Proposal not found: $id"))
        } else {
            call.respond(block.toDto())
        }
    }

    post("/v1/memory/proposals/{id}/dismiss") {
        val id = call.parameters["id"].orEmpty()
        controller.memoryStore.ensure()
        val ok = controller.memoryStore.dismissProposal(id)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Proposal not found: $id"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    // ── Entities & Relations ─────────────────────────────────────────────────
    post("/v1/memory/entities") {
        val req = call.receive<CreateMemoryEntityRequest>()
        val name = req.canonicalName.trim()
        if (name.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Entity name is required"))
            return@post
        }
        controller.memoryStore.ensure()
        val entity = controller.memoryStore.upsertEntity(
            MemoryEntityRef(
                name = name,
                entityType = MemoryEntityTypes.normalize(req.entityType),
                aliases = req.aliases.map { it.trim() }.filter { it.isNotEmpty() },
                summary = req.summary?.trim()?.takeIf { it.isNotEmpty() },
                domain = MemoryDomains.normalize(req.domain),
            ),
        )
        call.respond(HttpStatusCode.Created, entity.toDto())
    }

    get("/v1/memory/entities/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        val includeHistory = call.request.queryParameters["includeHistory"]?.equals("true", ignoreCase = true) == true
        controller.memoryStore.ensure()
        val detail = controller.memoryStore.inspectEntity(id, includeHistory = includeHistory)
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Entity not found: $id"))
        } else {
            call.respond(
                MemoryEntityDetailDto(
                    entity = detail.entity.toDto(),
                    outgoingRelations = detail.outgoingRelations.map { it.toDto() },
                    incomingRelations = detail.incomingRelations.map { it.toDto() },
                    linkedEvents = detail.linkedEvents.map { it.toDto() },
                ),
            )
        }
    }

    put("/v1/memory/entities/{id}") {
        val id = call.parameters["id"].orEmpty()
        val req = call.receive<UpdateMemoryEntityRequest>()
        controller.memoryStore.ensure()
        val updated = controller.memoryStore.updateEntity(
            id = id,
            canonicalName = req.canonicalName?.trim()?.takeIf { it.isNotEmpty() },
            entityType = req.entityType?.let { MemoryEntityTypes.normalize(it) },
            aliases = req.aliases?.map { it.trim() }?.filter { it.isNotEmpty() },
            summary = req.summary?.trim(),
            clearSummary = req.clearSummary,
            domain = req.domain?.let { MemoryDomains.normalize(it) },
            pinned = req.pinned,
        )
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Entity not found: $id"))
        } else {
            call.respond(updated.toDto())
        }
    }

    post("/v1/memory/entities/merge") {
        val req = call.receive<MergeMemoryEntitiesRequest>()
        if (req.keepId.isBlank() || req.mergeIds.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("keepId and mergeIds are required"))
            return@post
        }
        controller.memoryStore.ensure()
        val count = controller.memoryStore.mergeEntities(
            keepId = req.keepId.trim(),
            mergeIds = req.mergeIds.map { it.trim() }.filter { it.isNotEmpty() },
            mergedSummary = req.mergedSummary?.trim()?.takeIf { it.isNotEmpty() },
        )
        call.respond(HealthResponse(ok = count > 0))
    }

    post("/v1/memory/relations") {
        val req = call.receive<CreateMemoryRelationRequest>()
        if (req.fromEntityName.isBlank() || req.toEntityName.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("fromEntityName and toEntityName are required"))
            return@post
        }
        controller.memoryStore.ensure()
        val created = controller.memoryStore.upsertRelation(
            fromEntityName = req.fromEntityName.trim(),
            relationType = MemoryRelationTypes.normalize(req.relationType),
            toEntityName = req.toEntityName.trim(),
            label = req.label?.trim()?.takeIf { it.isNotEmpty() },
            domain = MemoryDomains.normalize(req.domain),
        )
        if (created == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Unable to create relation"))
        } else {
            call.respond(HttpStatusCode.Created, created.toDto())
        }
    }

    delete("/v1/memory/relations/{id}") {
        val id = call.parameters["id"].orEmpty()
        controller.memoryStore.ensure()
        val ok = controller.memoryStore.invalidateRelation(id)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Relation not found: $id"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    // ── Events ───────────────────────────────────────────────────────────────
    get("/v1/memory/events") {
        val query = call.request.queryParameters["query"]?.trim()?.takeIf { it.isNotEmpty() }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(10, 400) ?: 100
        val includeHistory = call.request.queryParameters["includeHistory"]?.equals("true", ignoreCase = true) == true
        controller.memoryStore.ensure()
        val list = controller.memoryStore.listEvents(
            MemoryEventFilter(query = query, limit = limit, includeHistory = includeHistory),
        )
        call.respond(list.map { it.toDto() })
    }

    post("/v1/memory/events") {
        val req = call.receive<CreateMemoryEventRequest>()
        val content = req.content.trim()
        if (content.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Event content is required"))
            return@post
        }
        controller.memoryStore.ensure()
        val saved = controller.memoryStore.saveEvent(
            MemoryEventDraft(
                eventKind = MemoryEventKind.fromWire(req.eventKind) ?: MemoryEventKind.FACT,
                content = content,
                summary = req.summary?.trim()?.takeIf { it.isNotEmpty() },
                tags = req.tags.map { it.trim() }.filter { it.isNotEmpty() },
                domain = MemoryDomains.normalize(req.domain),
                importance = req.importance.coerceIn(1, 5),
                pinned = req.pinned,
            ),
        )
        call.respond(HttpStatusCode.Created, saved.toDto())
    }

    put("/v1/memory/events/{id}") {
        val id = call.parameters["id"].orEmpty()
        val req = call.receive<UpdateMemoryEventRequest>()
        controller.memoryStore.ensure()
        val updated = controller.memoryStore.updateEvent(
            id = id,
            eventKind = req.eventKind?.let { MemoryEventKind.fromWire(it) },
            content = req.content?.trim()?.takeIf { it.isNotEmpty() },
            summary = req.summary?.trim(),
            domain = req.domain?.let { MemoryDomains.normalize(it) },
            tags = req.tags?.map { it.trim() }?.filter { it.isNotEmpty() },
            pinned = req.pinned,
        )
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Event not found: $id"))
        } else {
            call.respond(updated.toDto())
        }
    }

    delete("/v1/memory/events/{id}") {
        val id = call.parameters["id"].orEmpty()
        controller.memoryStore.ensure()
        val deleted = controller.memoryStore.deleteEvent(id)
        if (!deleted) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Event not found: $id"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    post("/v1/memory/events/{id}/restore") {
        val id = call.parameters["id"].orEmpty()
        controller.memoryStore.ensure()
        val restored = controller.memoryStore.restoreEvent(id)
        if (!restored) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Event not found: $id"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    delete("/v1/memory/sessions/{sessionId}/events") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        controller.memoryStore.ensure()
        controller.memoryStore.deleteEventsBySession(sessionId)
        call.respond(HealthResponse(ok = true))
    }

    post("/v1/memory/audit/{id}/revert") {
        val id = call.parameters["id"].orEmpty()
        controller.memoryStore.ensure()
        val reverted = controller.memoryStore.revertAuditEntry(id)
        if (!reverted) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Audit entry cannot be reverted: $id"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    // ── Preferences, Guidance, Consolidation, Sync, Export ───────────────────
    get("/v1/memory/preferences") {
        call.respond(readMemoryPrefsDto(controller))
    }

    put("/v1/memory/preferences") {
        val req = call.receive<UpdateMemoryPreferencesRequest>()
        val cur = controller.memoryPreferencesStore.read()
        val next = cur.copy(
            mode = req.mode?.let { MemoryModelMode.fromWire(it) } ?: cur.mode,
            hotProviderId = if (req.clearHotModel) null else (req.hotProviderId ?: cur.hotProviderId),
            hotModelId = if (req.clearHotModel) null else (req.hotModelId ?: cur.hotModelId),
            coldProviderId = if (req.clearColdModel) null else (req.coldProviderId ?: cur.coldProviderId),
            coldModelId = if (req.clearColdModel) null else (req.coldModelId ?: cur.coldModelId),
            hotIdleDebounceSeconds = req.hotIdleDebounceSeconds?.coerceIn(15, 600) ?: cur.hotIdleDebounceSeconds,
            coldConsolidationHours = req.coldConsolidationHours?.coerceIn(0, 168) ?: cur.coldConsolidationHours,
            injectionMaxChars = req.injectionMaxChars?.let(MemorySettings::normalizeInjectionMaxChars) ?: cur.injectionMaxChars,
            profileBudgetChars = if (req.clearSectionBudgets) null else (req.profileBudgetChars ?: cur.profileBudgetChars),
            relationsBudgetChars = if (req.clearSectionBudgets) null else (req.relationsBudgetChars ?: cur.relationsBudgetChars),
            eventsBudgetChars = if (req.clearSectionBudgets) null else (req.eventsBudgetChars ?: cur.eventsBudgetChars),
            directoryBudgetChars = if (req.clearSectionBudgets) null else (req.directoryBudgetChars ?: cur.directoryBudgetChars),
        )
        controller.memoryPreferencesStore.save(next)
        if (req.guidance != null) {
            val clean = req.guidance.trim()
            if (clean.toByteArray(Charsets.UTF_8).size > io.github.whywood.superpi.data.AGENT_DOC_MAX_BYTES) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse("Memory guidance exceeds maximum size of ${io.github.whywood.superpi.data.AGENT_DOC_MAX_BYTES / 1024} KB."),
                )
                return@put
            }
            withContext(Dispatchers.IO) {
                controller.paths.memoryDir.mkdirs()
                val dirPath = controller.paths.memoryDir.absolutePath
                if (clean.isEmpty()) {
                    controller.botRegistry.agentDocStore.delete(dirPath)
                } else {
                    controller.botRegistry.agentDocStore.save(dirPath, clean + "\n")
                }
            }
        }
        call.respond(readMemoryPrefsDto(controller))
    }

    post("/v1/memory/consolidate") {
        controller.memoryStore.ensure()
        controller.memoryAgentService.launchManualConsolidation()
        call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
    }

    post("/v1/memory/retry-pending") {
        controller.memoryStore.ensure()
        controller.memoryAgentService.launchRetryPending()
        call.respond(HttpStatusCode.Accepted, HealthResponse(ok = true))
    }

    get("/v1/memory/sync/status") {
        call.respond(readMemorySyncStatusDto(controller))
    }

    post("/v1/memory/sync/resolve") {
        if (controller.relayStore.read() == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Not signed in to Relay"))
            return@post
        }
        val req = call.receive<ResolveMemorySyncRequest>()
        val mode = when (req.mode.trim().uppercase()) {
            "MERGE" -> MemoryAccountSwitchMode.MERGE
            "REPLACE_LOCAL" -> MemoryAccountSwitchMode.REPLACE_LOCAL
            else -> {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid sync resolve mode: ${req.mode}"))
                return@post
            }
        }
        controller.relayMemorySync.resolveAccountSwitch(mode)
        call.respond(readMemorySyncStatusDto(controller))
    }

    post("/v1/memory/sync") {
        if (controller.relayStore.read() == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Not signed in to Relay"))
            return@post
        }
        val ok = controller.relayMemorySync.syncNow(manual = true)
        if (ok) {
            call.respond(HealthResponse(ok = true))
        } else {
            val st = controller.relayMemorySync.state.value
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorResponse("Memory sync incomplete (${st.status.name.lowercase()})"),
            )
        }
    }

    get("/v1/memory/export") {
        controller.memoryStore.ensure()
        val md = controller.memoryStore.exportMarkdown()
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName,
                "superpi-memory.md",
            ).toString(),
        )
        call.respondText(md, ContentType.Text.Plain)
    }
}

private fun readMemorySyncStatusDto(controller: SuperPiController): MemorySyncStatusDto {
    if (!controller.isRelayMemorySyncInitialized) return MemorySyncStatusDto()
    val st = controller.relayMemorySync.state.value
    return MemorySyncStatusDto(
        status = st.status.name.lowercase(),
        lastSyncedAt = st.lastSyncedAt,
        lastErrorCode = st.lastErrorCode,
        accountSwitchPrompt = st.accountSwitchPrompt?.let { p ->
            MemorySyncPromptDto(
                accountKey = p.accountKey,
                accountIdentity = p.accountIdentity,
                localRecordCount = p.localRecordCount,
            )
        },
    )
}

private suspend fun readMemoryPrefsDto(controller: SuperPiController): MemoryPreferencesDto {
    val s = controller.memoryPreferencesStore.read()
    val guidance = withContext(Dispatchers.IO) {
        controller.botRegistry.agentDocStore.read(controller.paths.memoryDir.absolutePath)?.content.orEmpty()
    }
    return MemoryPreferencesDto(
        mode = s.mode.wire,
        hotProviderId = s.hotProviderId,
        hotModelId = s.hotModelId,
        coldProviderId = s.coldProviderId,
        coldModelId = s.coldModelId,
        hotIdleDebounceSeconds = s.hotIdleDebounceSeconds,
        coldConsolidationHours = s.coldConsolidationHours,
        injectionMaxChars = s.injectionMaxChars,
        profileBudgetChars = s.profileBudgetChars,
        relationsBudgetChars = s.relationsBudgetChars,
        eventsBudgetChars = s.eventsBudgetChars,
        directoryBudgetChars = s.directoryBudgetChars,
        guidance = guidance,
    )
}

private fun MemoryBlockProposal.toDto(): MemoryProposalDto = MemoryProposalDto(
    id = id,
    slug = slug,
    title = title,
    purpose = purpose,
    domain = domain,
    seedContent = seedContent,
    reason = reason,
    status = status.wire,
    createdAt = createdAt,
)

private fun slugify(raw: String): String {
    val cleaned = raw.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(36)
    return if (cleaned.isNotEmpty()) cleaned else "block-${System.currentTimeMillis().toString(36)}"
}
