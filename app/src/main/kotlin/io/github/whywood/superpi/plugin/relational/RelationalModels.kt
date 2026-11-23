package io.github.whywood.superpi.plugin.relational

import org.json.JSONArray
import org.json.JSONObject

enum class RelationshipCategory {
    EDUCATION,
    WORKPLACE,
    FAMILY,
    SOCIAL,
    GENERAL;

    companion object {
        fun fromWire(value: String?): RelationshipCategory = when (value?.trim()?.uppercase()) {
            "EDUCATION", "TEACHER_PARENT", "SCHOOL" -> EDUCATION
            "WORKPLACE", "WORK", "CAREER" -> WORKPLACE
            "FAMILY", "HOME", "RELATIVE" -> FAMILY
            "SOCIAL", "FRIEND" -> SOCIAL
            else -> GENERAL
        }
    }
}

data class ContactDossier(
    val id: String,
    val name: String,
    val category: String = RelationshipCategory.GENERAL.name,
    val role: String = "",
    val tags: List<String> = emptyList(),
    val taboos: List<String> = emptyList(),
    val notes: String = "",
    val aliases: List<String> = emptyList(),
    val historicalNames: List<String> = emptyList(),
    val avatarHash: String = "",
    val historicalAvatarHashes: List<String> = emptyList(),
    val platformId: String = "",
    val semanticAnchors: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category)
        put("role", role)
        put("tags", JSONArray(tags))
        put("taboos", JSONArray(taboos))
        put("notes", notes)
        put("aliases", JSONArray(aliases))
        put("historicalNames", JSONArray(historicalNames))
        put("avatarHash", avatarHash)
        put("historicalAvatarHashes", JSONArray(historicalAvatarHashes))
        put("platformId", platformId)
        put("semanticAnchors", JSONArray(semanticAnchors))
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): ContactDossier {
            val tagsList = mutableListOf<String>()
            val tagsArr = json.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    tagsList.add(tagsArr.optString(i))
                }
            }
            val taboosList = mutableListOf<String>()
            val taboosArr = json.optJSONArray("taboos")
            if (taboosArr != null) {
                for (i in 0 until taboosArr.length()) {
                    taboosList.add(taboosArr.optString(i))
                }
            }
            val aliasesList = mutableListOf<String>()
            val aliasesArr = json.optJSONArray("aliases")
            if (aliasesArr != null) {
                for (i in 0 until aliasesArr.length()) {
                    aliasesList.add(aliasesArr.optString(i))
                }
            }
            val historicalNamesList = mutableListOf<String>()
            val historicalNamesArr = json.optJSONArray("historicalNames")
            if (historicalNamesArr != null) {
                for (i in 0 until historicalNamesArr.length()) {
                    historicalNamesList.add(historicalNamesArr.optString(i))
                }
            }
            val historicalAvatarList = mutableListOf<String>()
            val historicalAvatarArr = json.optJSONArray("historicalAvatarHashes")
            if (historicalAvatarArr != null) {
                for (i in 0 until historicalAvatarArr.length()) {
                    historicalAvatarList.add(historicalAvatarArr.optString(i))
                }
            }
            val anchorsList = mutableListOf<String>()
            val anchorsArr = json.optJSONArray("semanticAnchors")
            if (anchorsArr != null) {
                for (i in 0 until anchorsArr.length()) {
                    anchorsList.add(anchorsArr.optString(i))
                }
            }

            return ContactDossier(
                id = json.optString("id"),
                name = json.optString("name"),
                category = json.optString("category", RelationshipCategory.GENERAL.name),
                role = json.optString("role", ""),
                tags = tagsList,
                taboos = taboosList,
                notes = json.optString("notes", ""),
                aliases = aliasesList,
                historicalNames = historicalNamesList,
                avatarHash = json.optString("avatarHash", ""),
                historicalAvatarHashes = historicalAvatarList,
                platformId = json.optString("platformId", ""),
                semanticAnchors = anchorsList,
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}

data class ChatMessageRecord(
    val contactId: String,
    val timestamp: Long,
    val sender: String,
    val text: String,
    val rawSenderName: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("contactId", contactId)
        put("timestamp", timestamp)
        put("sender", sender)
        put("text", text)
        if (rawSenderName.isNotBlank()) {
            put("rawSenderName", rawSenderName)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ChatMessageRecord {
            return ChatMessageRecord(
                contactId = json.optString("contactId"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                sender = json.optString("sender"),
                text = json.optString("text"),
                rawSenderName = json.optString("rawSenderName", ""),
            )
        }
    }
}

data class IncomingChatMessage(
    val sender: String, // "CONTACT" or "USER" / "TEACHER"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val rawSenderName: String = "",
)

data class IncomingChatFrame(
    val currentDisplayName: String,
    val currentAvatarHash: String = "",
    val platformId: String = "",
    val newMessages: List<IncomingChatMessage> = emptyList(),
    val precedingContextMessages: List<String> = emptyList(),
    val semanticHints: List<String> = emptyList(),
)

enum class MatchStrategy {
    EXACT_ID,
    PLATFORM_ID,
    CURRENT_NAME,
    ALIAS_OR_HISTORICAL_NAME,
    AVATAR_HASH,
    DIALOGUE_CONTINUITY,
    SEMANTIC_ANCHORS,
    NEW_CONTACT,
}

data class IdentityResolutionResult(
    val contactId: String,
    val isNewContact: Boolean,
    val strategy: MatchStrategy,
    val confidence: Double,
    val nameChanged: Boolean,
    val oldName: String?,
    val newName: String?,
    val avatarChanged: Boolean,
    val explanation: String,
)

data class AutonomousIngestionConfig(
    val isAuthorized: Boolean = false,
    val allowIdleBrowsing: Boolean = true,
    val autoReconcileIdentity: Boolean = true,
    val scanIntervalMinutes: Int = 15,
    val lastScannedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("isAuthorized", isAuthorized)
        put("allowIdleBrowsing", allowIdleBrowsing)
        put("autoReconcileIdentity", autoReconcileIdentity)
        put("scanIntervalMinutes", scanIntervalMinutes)
        put("lastScannedAt", lastScannedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): AutonomousIngestionConfig = AutonomousIngestionConfig(
            isAuthorized = json.optBoolean("isAuthorized", false),
            allowIdleBrowsing = json.optBoolean("allowIdleBrowsing", true),
            autoReconcileIdentity = json.optBoolean("autoReconcileIdentity", true),
            scanIntervalMinutes = json.optInt("scanIntervalMinutes", 15),
            lastScannedAt = json.optLong("lastScannedAt", 0L),
        )
    }
}

data class AutonomousScanResult(
    val executed: Boolean,
    val reason: String,
    val framesProcessed: Int = 0,
    val messagesIngested: Int = 0,
    val reconciledContacts: List<String> = emptyList(),
)

data class TranscriptImportResult(
    val contactId: String,
    val importedCount: Int,
    val resolvedName: String,
    val resolutionResult: IdentityResolutionResult,
)

data class RelationalBriefing(
    val contact: ContactDossier,
    val recentSummary: String,
    val taboos: List<String>,
    val hiddenIntent: String,
    val advisoryTips: List<String>,
)

data class RelationalPromptContext(
    val contact: ContactDossier,
    val userIntent: String,
    val taboosToCheck: List<String>,
    val recentChatExcerpt: String,
    val guidelines: List<String>,
)
