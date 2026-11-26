package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockProposal
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import java.util.UUID

internal object MemoryBlockStorage {
    fun listBlocks(conn: SQLiteConnection, includeArchived: Boolean = false): List<MemoryBlock> {
        val statusClause = if (includeArchived) "" else "AND status = 'ACTIVE'"
        val out = ArrayList<MemoryBlock>()
        conn.prepare(
            """
            SELECT $BLOCK_COLUMNS
            FROM memory_blocks
            WHERE deleted_at IS NULL $statusClause
            ORDER BY pinned DESC, sort_order ASC, updated_at DESC
            """.trimIndent(),
        ).use { stmt ->
            while (stmt.step()) out += readBlockRow(stmt)
        }
        return out
    }

    fun getBlockBySlug(conn: SQLiteConnection, slug: String): MemoryBlock? {
        val cleanSlug = slug.trim()
        if (cleanSlug.isEmpty()) return null
        return conn.prepare(
            "SELECT $BLOCK_COLUMNS FROM memory_blocks WHERE slug = ? AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, cleanSlug)
            if (stmt.step()) readBlockRow(stmt) else null
        }
    }

    fun getBlockById(conn: SQLiteConnection, id: String): MemoryBlock? =
        conn.prepare(
            "SELECT $BLOCK_COLUMNS FROM memory_blocks WHERE id = ? AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) readBlockRow(stmt) else null
        }

    fun countActiveBlocks(conn: SQLiteConnection): Int =
        conn.prepare("SELECT COUNT(*) FROM memory_blocks WHERE deleted_at IS NULL AND status = 'ACTIVE'").use { stmt ->
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }

    fun createBlock(
        conn: SQLiteConnection,
        draft: MemoryBlockDraft,
        actor: MemoryMutationActor,
        nowMs: Long,
        runId: String? = null,
        deviceId: String? = null,
    ): MemoryBlock {
        var baseSlug = MemoryLimits.normalizeSlug(draft.slug.ifBlank { draft.title })
        var slug = baseSlug
        var suffix = 2
        while (getBlockBySlug(conn, slug) != null) {
            slug = "${baseSlug.take(MemoryLimits.MAX_SLUG_LENGTH - 4)}_${suffix++}"
        }
        val id = UUID.randomUUID().toString()
        val maxChars = draft.maxChars.coerceIn(300, MemoryLimits.MAX_BLOCK_CONTENT_CHARS)
        val cleanTitle = MemoryLimits.clampText(draft.title, MemoryLimits.MAX_TITLE_CHARS).ifEmpty { slug }
        val cleanPurpose = MemoryLimits.clampText(draft.purpose, MemoryLimits.MAX_PURPOSE_CHARS).ifEmpty { cleanTitle }
        val cleanHint = MemoryLimits.clampOptionalText(draft.schemaHint, MemoryLimits.MAX_SCHEMA_HINT_CHARS)
        val cleanContent = MemoryLimits.clampText(draft.content, maxChars)
        val cleanDomain = MemoryDomains.normalize(draft.domain, MemoryDomains.CUSTOM)

        conn.prepare(
            """
            INSERT INTO memory_blocks(
                id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                injection_mode, max_chars, owner, pinned, status, sort_order,
                created_at, updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, NULL, 1, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.bindText(2, slug)
            stmt.bindText(3, cleanTitle)
            stmt.bindText(4, cleanPurpose)
            if (cleanHint == null) stmt.bindNull(5) else stmt.bindText(5, cleanHint)
            stmt.bindText(6, cleanContent)
            stmt.bindText(7, cleanDomain)
            stmt.bindText(8, draft.sensitivity.wire)
            stmt.bindText(9, draft.injectionMode.wire)
            stmt.bindLong(10, maxChars.toLong())
            stmt.bindText(11, draft.owner.wire)
            stmt.bindLong(12, if (draft.pinned) 1L else 0L)
            stmt.bindLong(13, draft.sortOrder.toLong())
            stmt.bindLong(14, nowMs)
            stmt.bindLong(15, nowMs)
            if (deviceId == null) stmt.bindNull(16) else stmt.bindText(16, deviceId)
            stmt.step()
        }

        val afterJson = MemoryAuditSupport.blockSnapshotJson(conn, id)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = runId,
            actor = actor,
            actionType = "CREATE_BLOCK",
            targetTable = "memory_blocks",
            targetId = id,
            summaryUserText = "新建了个人档案「$cleanTitle」",
            reason = cleanPurpose,
            beforeSnapshotJson = null,
            afterSnapshotJson = afterJson,
            nowMs = nowMs,
        )
        MemorySyncStorage.markDirty(conn, MemorySyncKind.BLOCK, id, nowMs)
        MemoryStorage.invalidateContextCache(conn)
        return checkNotNull(getBlockById(conn, id))
    }

    fun updateBlock(
        conn: SQLiteConnection,
        id: String,
        title: String? = null,
        purpose: String? = null,
        schemaHint: String? = null,
        content: String? = null,
        domain: String? = null,
        sensitivity: MemorySensitivity? = null,
        injectionMode: MemoryBlockInjectionMode? = null,
        pinned: Boolean? = null,
        status: MemoryBlockStatus? = null,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
        runId: String? = null,
        userSummary: String? = null,
        nowMs: Long,
        deviceId: String? = null,
    ): Pair<MemoryBlock?, GuardDecision> {
        val existing = getBlockById(conn, id) ?: return null to GuardDecision.Blocked("Block not found")
        val guard = EpistemicGuard.canMutateBlock(existing, actor)
        if (guard is GuardDecision.Blocked) return existing to guard

        val beforeJson = MemoryAuditSupport.blockSnapshotJson(conn, id)
        val nextTitle = title?.let { MemoryLimits.clampText(it, MemoryLimits.MAX_TITLE_CHARS) }
            ?.takeIf { it.isNotEmpty() } ?: existing.title
        val nextPurpose = purpose?.let { MemoryLimits.clampText(it, MemoryLimits.MAX_PURPOSE_CHARS) }
            ?.takeIf { it.isNotEmpty() } ?: existing.purpose
        val nextHint = if (schemaHint != null) {
            MemoryLimits.clampOptionalText(schemaHint, MemoryLimits.MAX_SCHEMA_HINT_CHARS)
        } else {
            existing.schemaHint
        }
        val nextContent = content?.let { MemoryLimits.clampText(it, existing.maxChars) } ?: existing.content
        val nextDomain = domain?.let { MemoryDomains.normalize(it, existing.domain) } ?: existing.domain
        val nextSensitivity = sensitivity ?: existing.sensitivity
        val nextInjection = injectionMode ?: existing.injectionMode
        val nextPinned = pinned ?: existing.pinned
        val nextStatus = status ?: existing.status

        conn.prepare(
            """
            UPDATE memory_blocks
            SET title = ?, purpose = ?, schema_hint = ?, content = ?, domain = ?, sensitivity = ?,
                injection_mode = ?, pinned = ?, status = ?, updated_at = ?, revision = revision + 1,
                updated_by_device_id = ?
            WHERE id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, nextTitle)
            stmt.bindText(2, nextPurpose)
            if (nextHint == null) stmt.bindNull(3) else stmt.bindText(3, nextHint)
            stmt.bindText(4, nextContent)
            stmt.bindText(5, nextDomain)
            stmt.bindText(6, nextSensitivity.wire)
            stmt.bindText(7, nextInjection.wire)
            stmt.bindLong(8, if (nextPinned) 1L else 0L)
            stmt.bindText(9, nextStatus.wire)
            stmt.bindLong(10, nowMs)
            if (deviceId == null) stmt.bindNull(11) else stmt.bindText(11, deviceId)
            stmt.bindText(12, id)
            stmt.step()
        }

        val afterJson = MemoryAuditSupport.blockSnapshotJson(conn, id)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = runId,
            actor = actor,
            actionType = "UPDATE_BLOCK",
            targetTable = "memory_blocks",
            targetId = id,
            summaryUserText = userSummary ?: "更新了档案「$nextTitle」",
            reason = null,
            beforeSnapshotJson = beforeJson,
            afterSnapshotJson = afterJson,
            nowMs = nowMs,
        )
        MemorySyncStorage.markDirty(conn, MemorySyncKind.BLOCK, id, nowMs)
        MemoryStorage.invalidateContextCache(conn)
        return getBlockById(conn, id) to GuardDecision.Allowed
    }

    fun patchBlock(
        conn: SQLiteConnection,
        op: BlockPatchOp,
        actor: MemoryMutationActor,
        runId: String?,
        nowMs: Long,
        deviceId: String? = null,
    ): GuardDecision {
        val block = getBlockBySlug(conn, op.slug)
            ?: return GuardDecision.Blocked("Block '${op.slug}' not found")
        val guard = EpistemicGuard.canMutateBlock(block, actor)
        if (guard is GuardDecision.Blocked) return guard

        val current = block.content
        val next = when {
            !op.findText.isNullOrEmpty() && op.replaceText != null -> {
                val replaced = replaceBlockSnippet(current, op.findText, op.replaceText)
                if (replaced != null) {
                    replaced
                } else if (!op.appendText.isNullOrBlank()) {
                    if (current.isBlank()) op.appendText.trim() else "$current\n${op.appendText.trim()}"
                } else {
                    return GuardDecision.Blocked("Snippet not found in block '${op.slug}'")
                }
            }
            !op.appendText.isNullOrBlank() -> {
                val add = op.appendText.trim()
                if (current.isBlank()) add else "$current\n$add"
            }
            op.fullContent != null -> op.fullContent.trim()
            else -> return GuardDecision.Blocked("Empty patch for block '${op.slug}'")
        }

        val clamped = MemoryLimits.clampText(next, block.maxChars)
        if (clamped == current) return GuardDecision.Allowed

        val (_, outcome) = updateBlock(
            conn = conn,
            id = block.id,
            content = clamped,
            actor = actor,
            runId = runId,
            userSummary = op.userSummary.ifBlank { "更新了档案「${block.title}」" },
            nowMs = nowMs,
            deviceId = deviceId,
        )
        return outcome
    }

    private fun replaceBlockSnippet(current: String, findText: String, replaceText: String): String? {
        if (current.contains(findText)) {
            return current.replaceFirst(findText, replaceText)
        }
        val trimmedFind = findText.trim()
        if (trimmedFind.isNotEmpty() && current.contains(trimmedFind)) {
            return current.replaceFirst(trimmedFind, replaceText)
        }
        val normalizedTarget = normalizeInlineWhitespace(trimmedFind)
        if (normalizedTarget.isEmpty()) return null
        val lines = current.lines()
        val matchedLineIndices = lines.indices.filter { idx ->
            normalizeInlineWhitespace(lines[idx]) == normalizedTarget
        }
        if (matchedLineIndices.size == 1) {
            val targetIdx = matchedLineIndices.first()
            val updated = lines.toMutableList()
            if (replaceText.isEmpty()) {
                updated.removeAt(targetIdx)
            } else {
                updated[targetIdx] = replaceText.trimEnd()
            }
            return updated.joinToString("\n")
        }
        return null
    }

    private fun normalizeInlineWhitespace(text: String): String =
        text.trim().replace(WHITESPACE_RUN_REGEX, " ")

    private val WHITESPACE_RUN_REGEX = Regex("\\s+")

    fun deleteCustomBlock(
        conn: SQLiteConnection,
        id: String,
        actor: MemoryMutationActor,
        nowMs: Long,
        deviceId: String? = null,
    ): Boolean {
        val existing = getBlockById(conn, id) ?: return false
        if (existing.owner == MemoryBlockOwner.BUILTIN) return false
        val beforeJson = MemoryAuditSupport.blockSnapshotJson(conn, id)
        conn.prepare(
            "UPDATE memory_blocks SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindLong(2, nowMs)
            if (deviceId == null) stmt.bindNull(3) else stmt.bindText(3, deviceId)
            stmt.bindText(4, id)
            stmt.step()
        }
        val afterJson = MemoryAuditSupport.blockSnapshotJson(conn, id)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = null,
            actor = actor,
            actionType = "DELETE_BLOCK",
            targetTable = "memory_blocks",
            targetId = id,
            summaryUserText = "删除了自定义档案「${existing.title}」",
            reason = null,
            beforeSnapshotJson = beforeJson,
            afterSnapshotJson = afterJson,
            nowMs = nowMs,
        )
        MemorySyncStorage.markDirty(conn, MemorySyncKind.BLOCK, id, nowMs)
        MemoryStorage.invalidateContextCache(conn)
        return true
    }

    fun listProposals(conn: SQLiteConnection, status: MemoryProposalStatus? = MemoryProposalStatus.PENDING): List<MemoryBlockProposal> {
        val whereClause = if (status == null) "WHERE deleted_at IS NULL" else "WHERE deleted_at IS NULL AND status = ?"
        val out = ArrayList<MemoryBlockProposal>()
        conn.prepare(
            """
            SELECT id, slug, title, purpose, domain, sensitivity, seed_content,
                   supporting_event_ids_json, reason, status, created_at, resolved_at
            FROM memory_block_proposals
            $whereClause
            ORDER BY created_at DESC
            """.trimIndent(),
        ).use { stmt ->
            if (status != null) stmt.bindText(1, status.wire)
            while (stmt.step()) {
                out += MemoryBlockProposal(
                    id = stmt.getText(0),
                    slug = stmt.getText(1),
                    title = stmt.getText(2),
                    purpose = stmt.getText(3),
                    domain = stmt.getText(4),
                    sensitivity = MemorySensitivity.fromWire(stmt.getText(5)),
                    seedContent = stmt.getText(6),
                    supportingEventIds = MemoryJsonSupport.decodeStringList(stmt.getText(7)),
                    reason = stmt.getText(8),
                    status = MemoryProposalStatus.fromWire(stmt.getText(9)),
                    createdAt = stmt.getLong(10),
                    resolvedAt = if (stmt.isNull(11)) null else stmt.getLong(11),
                )
            }
        }
        return out
    }

    fun createProposal(
        conn: SQLiteConnection,
        slug: String,
        title: String,
        purpose: String,
        domain: String,
        sensitivity: MemorySensitivity,
        seedContent: String,
        supportingEventIds: List<String>,
        reason: String,
        nowMs: Long,
        deviceId: String? = null,
    ): MemoryBlockProposal? {
        if (countActiveBlocks(conn) >= MemoryLimits.SOFT_MAX_ACTIVE_BLOCKS) return null
        val cleanSlug = MemoryLimits.normalizeSlug(slug.ifBlank { title })
        if (getBlockBySlug(conn, cleanSlug) != null) return null
        val existingPending = conn.prepare(
            "SELECT 1 FROM memory_block_proposals WHERE slug = ? AND status = 'PENDING' AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, cleanSlug)
            stmt.step()
        }
        if (existingPending) return null

        val id = UUID.randomUUID().toString()
        val cleanTitle = MemoryLimits.clampText(title, MemoryLimits.MAX_TITLE_CHARS).ifEmpty { cleanSlug }
        val cleanPurpose = MemoryLimits.clampText(purpose, MemoryLimits.MAX_PURPOSE_CHARS).ifEmpty { cleanTitle }
        val cleanDomain = MemoryDomains.normalize(domain, MemoryDomains.PERSONAL)
        val cleanSeed = MemoryLimits.clampText(seedContent, 2_000)
        val cleanReason = MemoryLimits.clampText(reason, 300).ifEmpty { cleanPurpose }

        conn.prepare(
            """
            INSERT INTO memory_block_proposals(
                id, slug, title, purpose, domain, sensitivity, seed_content,
                supporting_event_ids_json, reason, status, created_at, resolved_at,
                updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, NULL, ?, NULL, 1, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.bindText(2, cleanSlug)
            stmt.bindText(3, cleanTitle)
            stmt.bindText(4, cleanPurpose)
            stmt.bindText(5, cleanDomain)
            stmt.bindText(6, sensitivity.wire)
            stmt.bindText(7, cleanSeed)
            stmt.bindText(8, MemoryJsonSupport.encodeStringList(supportingEventIds.distinct().take(20)))
            stmt.bindText(9, cleanReason)
            stmt.bindLong(10, nowMs)
            stmt.bindLong(11, nowMs)
            if (deviceId == null) stmt.bindNull(12) else stmt.bindText(12, deviceId)
            stmt.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.PROPOSAL, id, nowMs)
        return listProposals(conn, MemoryProposalStatus.PENDING).firstOrNull { it.id == id }
    }

    fun acceptProposal(
        conn: SQLiteConnection,
        proposalId: String,
        nowMs: Long,
        deviceId: String? = null,
    ): MemoryBlock? {
        val proposal = listProposals(conn, MemoryProposalStatus.PENDING).firstOrNull { it.id == proposalId } ?: return null
        val created = createBlock(
            conn = conn,
            draft = MemoryBlockDraft(
                slug = proposal.slug,
                title = proposal.title,
                purpose = proposal.purpose,
                content = proposal.seedContent,
                domain = proposal.domain,
                sensitivity = proposal.sensitivity,
                owner = MemoryBlockOwner.AGENT,
                sortOrder = 80,
            ),
            actor = MemoryMutationActor.USER,
            nowMs = nowMs,
            deviceId = deviceId,
        )
        conn.prepare(
            "UPDATE memory_block_proposals SET status = 'ACCEPTED', resolved_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindLong(2, nowMs)
            if (deviceId == null) stmt.bindNull(3) else stmt.bindText(3, deviceId)
            stmt.bindText(4, proposalId)
            stmt.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.PROPOSAL, proposalId, nowMs)
        return created
    }

    fun dismissProposal(
        conn: SQLiteConnection,
        proposalId: String,
        nowMs: Long,
        deviceId: String? = null,
    ): Boolean {
        val exists = conn.prepare(
            "SELECT 1 FROM memory_block_proposals WHERE id = ? AND status = 'PENDING' AND deleted_at IS NULL LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, proposalId)
            stmt.step()
        }
        if (!exists) return false
        conn.prepare(
            "UPDATE memory_block_proposals SET status = 'DISMISSED', resolved_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindLong(2, nowMs)
            if (deviceId == null) stmt.bindNull(3) else stmt.bindText(3, deviceId)
            stmt.bindText(4, proposalId)
            stmt.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.PROPOSAL, proposalId, nowMs)
        return true
    }

    private fun readBlockRow(stmt: androidx.sqlite.SQLiteStatement): MemoryBlock = MemoryBlock(
        id = stmt.getText(0),
        slug = stmt.getText(1),
        title = stmt.getText(2),
        purpose = stmt.getText(3),
        schemaHint = if (stmt.isNull(4)) null else stmt.getText(4),
        content = stmt.getText(5),
        domain = stmt.getText(6),
        sensitivity = MemorySensitivity.fromWire(stmt.getText(7)),
        injectionMode = MemoryBlockInjectionMode.fromWire(stmt.getText(8)),
        maxChars = stmt.getLong(9).toInt(),
        owner = MemoryBlockOwner.fromWire(stmt.getText(10)),
        pinned = stmt.getLong(11) != 0L,
        status = MemoryBlockStatus.fromWire(stmt.getText(12)),
        sortOrder = stmt.getLong(13).toInt(),
        createdAt = stmt.getLong(14),
        updatedAt = stmt.getLong(15),
        revision = stmt.getLong(16),
    )

    private const val BLOCK_COLUMNS =
        "id, slug, title, purpose, schema_hint, content, domain, sensitivity, " +
            "injection_mode, max_chars, owner, pinned, status, sort_order, created_at, updated_at, revision"
}
