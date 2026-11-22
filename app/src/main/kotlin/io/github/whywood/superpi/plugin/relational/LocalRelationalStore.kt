package io.github.whywood.superpi.plugin.relational

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class LocalRelationalStore(private val baseDir: File) {
    private val contactsDir = File(baseDir, "contacts").apply { mkdirs() }
    private val chatsDir = File(baseDir, "chats").apply { mkdirs() }

    private val contactCache = ConcurrentHashMap<String, ContactDossier>()

    init {
        loadAllContacts()
    }

    fun saveContact(dossier: ContactDossier) {
        contactCache[dossier.id] = dossier
        val file = File(contactsDir, "${dossier.id}.json")
        file.writeText(dossier.toJson().toString())
    }

    fun getContact(contactId: String): ContactDossier? {
        return contactCache[contactId] ?: loadContactFromFile(contactId)?.also {
            contactCache[it.id] = it
        }
    }

    fun listContacts(): List<ContactDossier> = contactCache.values.toList()

    fun appendChatMessage(
        contactId: String,
        sender: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        rawSenderName: String = "",
    ) {
        val record = ChatMessageRecord(
            contactId = contactId,
            timestamp = timestamp,
            sender = sender.trim().uppercase(),
            text = text.trim(),
            rawSenderName = rawSenderName.trim(),
        )
        val file = File(chatsDir, "$contactId.jsonl")
        file.appendText(record.toJson().toString() + "\n")
    }

    /**
     * 高级联系人身份解析引擎。
     * 当联系人更换头像、修改群昵称或微信号备注变动时，
     * 依次通过平台唯一ID、姓名别名、头像签名、以及【会话时序重叠指纹比对】准确定位真实对应联系人。
     */
    fun resolveContactIdentity(frame: IncomingChatFrame): IdentityResolutionResult {
        val contacts = contactCache.values.toList()
        val displayName = frame.currentDisplayName.trim()
        val avatarHash = frame.currentAvatarHash.trim()
        val platformId = frame.platformId.trim()

        // 1. 匹配 Platform ID (如不可变的微信原始 ID / wxid)
        if (platformId.isNotBlank()) {
            val matched = contacts.firstOrNull { it.platformId == platformId }
            if (matched != null) {
                val nameChanged = displayName.isNotBlank() && displayName != matched.name
                val avatarChanged = avatarHash.isNotBlank() && avatarHash != matched.avatarHash
                return IdentityResolutionResult(
                    contactId = matched.id,
                    isNewContact = false,
                    strategy = MatchStrategy.PLATFORM_ID,
                    confidence = 1.0,
                    nameChanged = nameChanged,
                    oldName = if (nameChanged) matched.name else null,
                    newName = if (nameChanged) displayName else null,
                    avatarChanged = avatarChanged,
                    explanation = "通过平台唯一持久标识 ($platformId) 锁定联系人",
                )
            }
        }

        // 2. 匹配当前姓名、已知别名或历史曾用名
        if (displayName.isNotBlank()) {
            val exactNameMatch = contacts.firstOrNull { it.name == displayName }
            if (exactNameMatch != null) {
                val avatarChanged = avatarHash.isNotBlank() && avatarHash != exactNameMatch.avatarHash
                return IdentityResolutionResult(
                    contactId = exactNameMatch.id,
                    isNewContact = false,
                    strategy = MatchStrategy.CURRENT_NAME,
                    confidence = 0.95,
                    nameChanged = false,
                    oldName = null,
                    newName = null,
                    avatarChanged = avatarChanged,
                    explanation = "姓名精确匹配当前联系人档案 (${exactNameMatch.name})",
                )
            }

            val aliasOrHistoryMatch = contacts.firstOrNull {
                displayName in it.aliases || displayName in it.historicalNames
            }
            if (aliasOrHistoryMatch != null) {
                val avatarChanged = avatarHash.isNotBlank() && avatarHash != aliasOrHistoryMatch.avatarHash
                return IdentityResolutionResult(
                    contactId = aliasOrHistoryMatch.id,
                    isNewContact = false,
                    strategy = MatchStrategy.ALIAS_OR_HISTORICAL_NAME,
                    confidence = 0.90,
                    nameChanged = aliasOrHistoryMatch.name != displayName,
                    oldName = aliasOrHistoryMatch.name,
                    newName = displayName,
                    avatarChanged = avatarChanged,
                    explanation = "匹配到历史曾用名或已知别名 (${aliasOrHistoryMatch.name})",
                )
            }
        }

        // 3. 匹配头像特征哈希 (头像未换仅改名时)
        if (avatarHash.isNotBlank()) {
            val avatarMatch = contacts.firstOrNull {
                it.avatarHash == avatarHash || avatarHash in it.historicalAvatarHashes
            }
            if (avatarMatch != null) {
                val nameChanged = displayName.isNotBlank() && displayName != avatarMatch.name
                return IdentityResolutionResult(
                    contactId = avatarMatch.id,
                    isNewContact = false,
                    strategy = MatchStrategy.AVATAR_HASH,
                    confidence = 0.85,
                    nameChanged = nameChanged,
                    oldName = if (nameChanged) avatarMatch.name else null,
                    newName = if (nameChanged) displayName else null,
                    avatarChanged = false,
                    explanation = "头像指纹匹配联系人档案 (${avatarMatch.name})",
                )
            }
        }

        // 4. 核心自适应：会话时序重叠指纹比对 (Dialogue Continuity Fingerprint)
        // 关键价值：即使对方同时更换了头像和昵称，会话屏幕中的前序聊天记录仍属于同一客观时序！
        if (frame.precedingContextMessages.isNotEmpty()) {
            val normalizedPreceding = frame.precedingContextMessages.map { normalizeSnippet(it) }.filter { it.isNotBlank() }
            if (normalizedPreceding.isNotEmpty()) {
                var bestContact: ContactDossier? = null
                var bestOverlapCount = 0
                var bestSnippet = ""

                for (c in contacts) {
                    val recentLogs = getRecentChatMessages(c.id, maxChars = 2500)
                    val normalizedRecent = recentLogs.map { normalizeSnippet(it.text) }

                    var overlapCount = 0
                    var sampleSnippet = ""
                    for (preMsg in normalizedPreceding) {
                        if (preMsg.length >= 4) {
                            val matched = normalizedRecent.any { it.contains(preMsg) || preMsg.contains(it) }
                            if (matched) {
                                overlapCount++
                                if (sampleSnippet.isBlank()) sampleSnippet = preMsg
                            }
                        }
                    }

                    if (overlapCount > bestOverlapCount) {
                        bestOverlapCount = overlapCount
                        bestContact = c
                        bestSnippet = sampleSnippet
                    }
                }

                if (bestContact != null && bestOverlapCount >= 1) {
                    val nameChanged = displayName.isNotBlank() && displayName != bestContact.name
                    val avatarChanged = avatarHash.isNotBlank() && avatarHash != bestContact.avatarHash
                    return IdentityResolutionResult(
                        contactId = bestContact.id,
                        isNewContact = false,
                        strategy = MatchStrategy.DIALOGUE_CONTINUITY,
                        confidence = 0.95,
                        nameChanged = nameChanged,
                        oldName = if (nameChanged) bestContact.name else null,
                        newName = if (nameChanged) displayName else null,
                        avatarChanged = avatarChanged,
                        explanation = "对方已同时更换头像与昵称（当前名：'$displayName'），但基于前序会话重叠时序（匹配历史发言：'$bestSnippet'）锁定原联系人 ${bestContact.id} (${bestContact.name})",
                    )
                }
            }
        }

        // 5. 语义锚点匹配（如提到特定学生姓名或职务）
        if (frame.semanticHints.isNotEmpty()) {
            val normalizedHints = frame.semanticHints.map { it.trim() }.filter { it.isNotBlank() }
            val anchorMatch = contacts.firstOrNull { c ->
                normalizedHints.any { hint ->
                    c.semanticAnchors.contains(hint) || c.role.contains(hint) || c.notes.contains(hint)
                }
            }
            if (anchorMatch != null) {
                return IdentityResolutionResult(
                    contactId = anchorMatch.id,
                    isNewContact = false,
                    strategy = MatchStrategy.SEMANTIC_ANCHORS,
                    confidence = 0.75,
                    nameChanged = displayName.isNotBlank() && displayName != anchorMatch.name,
                    oldName = anchorMatch.name,
                    newName = displayName,
                    avatarChanged = avatarHash.isNotBlank() && avatarHash != anchorMatch.avatarHash,
                    explanation = "通过语义锚点提示匹配到联系人 (${anchorMatch.name})",
                )
            }
        }

        // 6. 未匹配任何线索，创建新联系人
        val sanitized = if (displayName.isNotBlank()) sanitizeId(displayName) else "contact"
        val newId = "c_${sanitized}_${System.currentTimeMillis().toString().takeLast(6)}"
        return IdentityResolutionResult(
            contactId = newId,
            isNewContact = true,
            strategy = MatchStrategy.NEW_CONTACT,
            confidence = 1.0,
            nameChanged = false,
            oldName = null,
            newName = displayName,
            avatarChanged = false,
            explanation = "未匹配到任何历史时序或档案指纹，识别为新联系人",
        )
    }

    /**
     * 载入当前聊天帧并执行自动身份重识别与归档更新。
     */
    fun ingestChatFrame(frame: IncomingChatFrame): IdentityResolutionResult {
        val resolution = resolveContactIdentity(frame)
        val contactId = resolution.contactId

        if (resolution.isNewContact) {
            val newDossier = ContactDossier(
                id = contactId,
                name = frame.currentDisplayName.ifBlank { contactId },
                avatarHash = frame.currentAvatarHash,
                platformId = frame.platformId,
                semanticAnchors = frame.semanticHints,
            )
            saveContact(newDossier)
        } else {
            val existing = getContact(contactId)
            if (existing != null && (resolution.nameChanged || resolution.avatarChanged)) {
                val updatedHistoricalNames = existing.historicalNames.toMutableList()
                if (resolution.nameChanged && existing.name.isNotBlank() && !updatedHistoricalNames.contains(existing.name)) {
                    updatedHistoricalNames.add(existing.name)
                }

                val updatedAvatarHashes = existing.historicalAvatarHashes.toMutableList()
                if (resolution.avatarChanged && existing.avatarHash.isNotBlank() && !updatedAvatarHashes.contains(existing.avatarHash)) {
                    updatedAvatarHashes.add(existing.avatarHash)
                }

                val updatedDossier = existing.copy(
                    name = if (resolution.nameChanged && frame.currentDisplayName.isNotBlank()) frame.currentDisplayName else existing.name,
                    historicalNames = updatedHistoricalNames,
                    avatarHash = if (resolution.avatarChanged && frame.currentAvatarHash.isNotBlank()) frame.currentAvatarHash else existing.avatarHash,
                    historicalAvatarHashes = updatedAvatarHashes,
                    updatedAt = System.currentTimeMillis(),
                )
                saveContact(updatedDossier)
            }
        }

        for (msg in frame.newMessages) {
            appendChatMessage(
                contactId = contactId,
                sender = msg.sender,
                text = msg.text,
                timestamp = msg.timestamp,
                rawSenderName = msg.rawSenderName.ifBlank { frame.currentDisplayName },
            )
        }

        return resolution
    }

    /**
     * 纯文本聊天记录转录批量导入接口（如微信复制长文本或导出记录）。
     */
    fun importChatTranscriptText(
        rawTranscript: String,
        defaultContactId: String? = null,
        currentDisplayName: String = "",
    ): TranscriptImportResult {
        val lines = rawTranscript.lines().filter { it.isNotBlank() }
        val parsedMessages = mutableListOf<IncomingChatMessage>()
        val precedingSnippets = mutableListOf<String>()

        val lineRegex = Regex("""^(?:\[?(\d{4}[-/]\d{1,2}[-/]\d{1,2}\s+\d{1,2}:\d{2}(?::\d{2})?)\]?\s+)?(?:\[?(我|对方|教师|家长|[^:：]+)\]?[:：])\s*(.*)$""")

        for (line in lines) {
            val trimmed = line.trim()
            val match = lineRegex.find(trimmed)
            if (match != null) {
                val senderTag = match.groupValues[2].trim()
                val content = match.groupValues[3].trim()
                val isMe = senderTag == "我" || senderTag.contains("教师") || senderTag.equals("USER", ignoreCase = true)
                val sender = if (isMe) "USER" else "CONTACT"
                parsedMessages.add(
                    IncomingChatMessage(
                        sender = sender,
                        text = content,
                        rawSenderName = senderTag,
                    ),
                )
                precedingSnippets.add(content)
            } else {
                parsedMessages.add(
                    IncomingChatMessage(
                        sender = "CONTACT",
                        text = trimmed,
                        rawSenderName = currentDisplayName,
                    ),
                )
                precedingSnippets.add(trimmed)
            }
        }

        val frame = IncomingChatFrame(
            currentDisplayName = currentDisplayName.ifBlank { defaultContactId ?: "导入联系人" },
            newMessages = parsedMessages,
            precedingContextMessages = precedingSnippets.take(5),
        )

        val resolution = if (!defaultContactId.isNullOrBlank() && getContact(defaultContactId) != null) {
            for (msg in parsedMessages) {
                appendChatMessage(defaultContactId, msg.sender, msg.text, msg.timestamp, msg.rawSenderName)
            }
            IdentityResolutionResult(
                contactId = defaultContactId,
                isNewContact = false,
                strategy = MatchStrategy.EXACT_ID,
                confidence = 1.0,
                nameChanged = false,
                oldName = null,
                newName = null,
                avatarChanged = false,
                explanation = "指定联系人 ID 直接导入",
            )
        } else {
            ingestChatFrame(frame)
        }

        return TranscriptImportResult(
            contactId = resolution.contactId,
            importedCount = parsedMessages.size,
            resolvedName = getContact(resolution.contactId)?.name ?: currentDisplayName,
            resolutionResult = resolution,
        )
    }

    private fun normalizeSnippet(text: String): String {
        return text.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
    }

    private fun sanitizeId(name: String): String {
        return name.lowercase().replace(Regex("""[^a-z0-9_]"""), "_").take(16)
    }

    fun getRecentChatMessages(contactId: String, maxChars: Int = 3000): List<ChatMessageRecord> {
        val file = File(chatsDir, "$contactId.jsonl")
        if (!file.exists()) return emptyList()

        val lines = file.readLines()
        val allRecords = lines.mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching { ChatMessageRecord.fromJson(JSONObject(line)) }.getOrNull()
        }

        val result = mutableListOf<ChatMessageRecord>()
        var currentLength = 0
        for (rec in allRecords.asReversed()) {
            if (currentLength + rec.text.length > maxChars && result.isNotEmpty()) {
                break
            }
            result.add(rec)
            currentLength += rec.text.length
        }
        return result.reversed()
    }

    fun formatRecentChatHistory(contactId: String, maxChars: Int = 3000): String {
        val messages = getRecentChatMessages(contactId, maxChars)
        if (messages.isEmpty()) return "(No local chat history recorded yet)"
        return messages.joinToString("\n") { msg ->
            val senderLabel = if (msg.sender == "USER" || msg.sender == "TEACHER") "我" else "对方"
            "[$senderLabel]: ${msg.text}"
        }
    }

    private fun loadAllContacts() {
        val files = contactsDir.listFiles { _, name -> name.endsWith(".json") }.orEmpty()
        for (file in files) {
            runCatching {
                val dossier = ContactDossier.fromJson(JSONObject(file.readText()))
                contactCache[dossier.id] = dossier
            }
        }
    }

    private fun loadContactFromFile(contactId: String): ContactDossier? {
        val file = File(contactsDir, "$contactId.json")
        if (!file.exists()) return null
        return runCatching {
            ContactDossier.fromJson(JSONObject(file.readText()))
        }.getOrNull()
    }
}
