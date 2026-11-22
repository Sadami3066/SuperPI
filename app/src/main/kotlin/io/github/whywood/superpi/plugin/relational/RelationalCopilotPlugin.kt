package io.github.whywood.superpi.plugin.relational

import io.github.whywood.superpi.plugin.api.Cards
import io.github.whywood.superpi.plugin.api.Contributions
import io.github.whywood.superpi.plugin.api.PluginAvailability
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.PluginTool
import io.github.whywood.superpi.plugin.api.PluginToolExposure
import io.github.whywood.superpi.plugin.api.SuperPIPlugin
import io.github.whywood.superpi.plugin.api.available
import io.github.whywood.superpi.plugin.api.contributions
import io.github.whywood.superpi.plugin.api.pluginTool
import io.github.whywood.superpi.plugin.api.textResult
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class RelationalCopilotPlugin(
    storeDir: File? = null,
) : SuperPIPlugin {

    private var store: LocalRelationalStore? = storeDir?.let { LocalRelationalStore(it) }
    private var autonomousAgent: AutonomousRelationalAgent? = storeDir?.let {
        AutonomousRelationalAgent(store ?: LocalRelationalStore(it), it)
    }

    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override suspend fun onEnable(host: PluginHost) {
        if (store == null) {
            val dir = File(host.dataDir, "relational_store")
            val s = LocalRelationalStore(dir)
            store = s
            autonomousAgent = AutonomousRelationalAgent(s, dir)
        }
    }

    override fun contributions(host: PluginHost): Contributions {
        val activeStore = store ?: LocalRelationalStore(File(host.dataDir, "relational_store")).also { store = it }
        val activeAgent = autonomousAgent ?: AutonomousRelationalAgent(activeStore, File(host.dataDir, "relational_store")).also { autonomousAgent = it }

        return contributions(
            tools = listOf(
                createBriefingTool(activeStore),
                createGuidedDraftTool(activeStore),
                createSaveProfileTool(activeStore),
                createRecordChatTool(activeStore),
                createIngestChatFrameTool(activeStore),
                createImportTranscriptTool(activeStore),
                createAutonomousScanTool(activeStore, activeAgent),
                createConfigureAutonomousTool(activeAgent),
            ),
        )
    }

    fun getStore(hostDataDir: File): LocalRelationalStore {
        return store ?: LocalRelationalStore(File(hostDataDir, "relational_store")).also { store = it }
    }

    fun getAutonomousAgent(hostDataDir: File): AutonomousRelationalAgent {
        val s = getStore(hostDataDir)
        return autonomousAgent ?: AutonomousRelationalAgent(s, File(hostDataDir, "relational_store")).also { autonomousAgent = it }
    }

    private fun createBriefingTool(store: LocalRelationalStore): PluginTool = pluginTool(
        name = "get_relationship_briefing",
        label = "获取人际关系情报简报与避坑建议",
        description = "分析指定联系人的近期本地聊天记录与敏感点档案，生成不带自作主张回复的客观情报、言外之意诊断与沟通策略建议。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("contact_id") {
                    put("type", "string")
                    put("description", "联系人唯一ID，如 c_parent_zhang 或 c_boss_wang")
                }
                putJsonObject("new_chat_message") {
                    put("type", "string")
                    put("description", "最新收到的消息正文（可选，若提供将先增量写入本地纯文本存储）")
                }
                putJsonObject("sender") {
                    put("type", "string")
                    put("description", "发送者角色，默认为 CONTACT")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("contact_id"))
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val contactId = (call.args["contact_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val newChat = (call.args["new_chat_message"] as? JsonPrimitive)?.contentOrNull
        val sender = (call.args["sender"] as? JsonPrimitive)?.contentOrNull ?: "CONTACT"

        if (contactId.isBlank()) {
            return@pluginTool textResult("Error: contact_id must not be blank", isError = true)
        }

        if (!newChat.isNullOrBlank()) {
            store.appendChatMessage(contactId, sender, newChat)
        }

        val contact = store.getContact(contactId) ?: ContactDossier(
            id = contactId,
            name = contactId,
            category = RelationshipCategory.GENERAL.name,
        )

        val chatHistory = store.formatRecentChatHistory(contactId, maxChars = 3000)
        val briefing = RelationalCognitiveEngine.generateBriefing(contact, chatHistory)

        val reportText = buildString {
            appendLine("【人际关系洞察简报】")
            appendLine("• 对象：${briefing.contact.name} (${briefing.contact.category} / 角色：${briefing.contact.role.ifBlank { "普通联系人" }})")
            if (briefing.taboos.isNotEmpty()) {
                appendLine("• ⚠️ 核心敏感点/雷区：")
                briefing.taboos.forEach { appendLine("   - $it") }
            } else {
                appendLine("• ⚠️ 核心敏感点/雷区：暂无特定禁忌记录")
            }
            appendLine()
            appendLine("• 💬 近期真实聊天脉络（核心凭据）：\n${briefing.recentSummary}")
            appendLine("• 💡 言外之意与关切诊断（基于聊天记录）：${briefing.hiddenIntent}")
            appendLine()
            appendLine("• 🛡️ 沟通策略与避坑指南：")
            briefing.advisoryTips.forEach { appendLine("   $it") }
            appendLine()
            appendLine("（提示：本模式为纯参谋模式，不输出任何写好的回答。一切判断以实际聊天记录为主，不引入主观性格印象输入，避免认知偏差。请老师知情后直接亲自回复；若实在太忙想让 AI 协助起草，请提供回复的大致内容与底线要求，交由 AI 现场生成。）")
        }

        val details = buildJsonObject {
            put(Cards.RAW_KEY, "Contact: ${contact.id}\nHistory Window (3000 chars):\n$chatHistory")
            put(Cards.GUIDANCE_KEY, "Present briefing to user without generating unprompted reply text.")
        }

        textResult(reportText, details = details)
    }

    private fun createGuidedDraftTool(store: LocalRelationalStore): PluginTool = pluginTool(
        name = "draft_guided_response",
        label = "根据用户意图装配AI生成上下文与雷区约束",
        description = "在用户明确输入其真实意图或事实底线后，提取本地适量聊天记录、档案雷区及防捏造准则，交由 AI 现场动态推演生成 3 种得体风格（杜绝预制写好的模板回答）。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("contact_id") {
                    put("type", "string")
                    put("description", "联系人唯一ID")
                }
                putJsonObject("user_intent") {
                    put("type", "string")
                    put("description", "用户/老师想表达的核心意思、真实情况或底线动作（严禁由AI自行瞎编）")
                }
                putJsonObject("tone_preference") {
                    put("type", "string")
                    put("description", "偏好的语气风格偏好（可选）")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("contact_id"))
                add(JsonPrimitive("user_intent"))
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val contactId = (call.args["contact_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val userIntent = (call.args["user_intent"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val tone = (call.args["tone_preference"] as? JsonPrimitive)?.contentOrNull

        if (contactId.isBlank() || userIntent.isBlank()) {
            return@pluginTool textResult("Error: contact_id and user_intent must not be blank", isError = true)
        }

        val contact = store.getContact(contactId) ?: ContactDossier(
            id = contactId,
            name = contactId,
            category = RelationshipCategory.GENERAL.name,
        )

        val chatHistory = store.formatRecentChatHistory(contactId, maxChars = 3000)
        val promptCtx = RelationalCognitiveEngine.assembleAiPromptContext(contact, chatHistory, userIntent, tone)

        val outputText = buildString {
            appendLine("【已装配真实档案与意图约束，移交 AI 现场动态生成】")
            appendLine("• 目标联系人：${contact.name}")
            appendLine("• 用户输入的底线意图：${promptCtx.userIntent}")
            if (promptCtx.taboosToCheck.isNotEmpty()) {
                appendLine("• ⚠️ 必须核验并避开的已知雷区：${promptCtx.taboosToCheck.joinToString("；")}")
            }
            appendLine()
            appendLine("• 🤖 AI 现场生成铁律与风格规范：")
            promptCtx.guidelines.forEach { appendLine("   $it") }
            appendLine()
            appendLine("• 📜 本机真实聊天记录上下文截选（最近约3000字）：")
            appendLine(promptCtx.recentChatExcerpt)
            appendLine()
            appendLine("（系统提示：本插件绝不提供写好的回答模板。请 AI 模型严格基于上述真实意图、雷区与对话记录，现场实时生成选项 1【温和共情版】、选项 2【专业客观版】、选项 3【简明得体版】3 种定制回复供用户挑选。）")
        }

        val details = buildJsonObject {
            put(Cards.RAW_KEY, "Intent: $userIntent\nTaboos: ${promptCtx.taboosToCheck.joinToString(",")}")
            put(Cards.GUIDANCE_KEY, "Generate 3 dynamically tailored reply options right now for user based strictly on the intent and constraints. DO NOT use canned responses.")
        }

        textResult(outputText, details = details)
    }

    private fun createSaveProfileTool(store: LocalRelationalStore): PluginTool = pluginTool(
        name = "save_contact_profile",
        label = "录入或更新联系人客观角色与雷区档案",
        description = "将联系人的基础信息、客观社会角色及不可触碰的敏感点保存至本地存储。以聊天记录为核心，不记录主观性格形容，避免先入为主的认知偏差。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("contact_id") {
                    put("type", "string")
                    put("description", "联系人唯一ID")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "联系人姓名或称谓，如 李女士（张小明妈妈）")
                }
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "关系分类：EDUCATION (家校), WORKPLACE (职场), FAMILY (家庭), SOCIAL (社交), GENERAL (通用)")
                }
                putJsonObject("role") {
                    put("type", "string")
                    put("description", "客观社会或班级角色（如 家委会副主任、某三甲医院护士、项目主管）")
                }
                putJsonObject("taboos") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "已知核心敏感点与禁忌雷区列表")
                }
                putJsonObject("notes") {
                    put("type", "string")
                    put("description", "客观事实备忘说明（勿记录主观性格形容）")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("contact_id"))
                add(JsonPrimitive("name"))
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val contactId = (call.args["contact_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val name = (call.args["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val category = (call.args["category"] as? JsonPrimitive)?.contentOrNull ?: RelationshipCategory.GENERAL.name
        val role = (call.args["role"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val notes = (call.args["notes"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        val taboos = (call.args["taboos"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

        val dossier = ContactDossier(
            id = contactId,
            name = name,
            category = category,
            role = role,
            taboos = taboos,
            notes = notes,
        )

        store.saveContact(dossier)
        textResult("成功更新联系人 [${name}] 的档案，已记录角色 [${role.ifBlank { "普通联系人" }}] 及 ${taboos.size} 条敏感雷区。")
    }

    private fun createRecordChatTool(store: LocalRelationalStore): PluginTool = pluginTool(
        name = "record_chat_message",
        label = "静默记录单条聊天至本地纯文本存储",
        description = "将收发消息按时序写入本机文件，供后续即时提取分析，无需反复翻阅聊天窗口。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("contact_id") {
                    put("type", "string")
                    put("description", "联系人ID")
                }
                putJsonObject("sender") {
                    put("type", "string")
                    put("description", "发送者角色：CONTACT 或 USER")
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "消息正文文本")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("contact_id"))
                add(JsonPrimitive("sender"))
                add(JsonPrimitive("text"))
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val contactId = (call.args["contact_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val sender = (call.args["sender"] as? JsonPrimitive)?.contentOrNull ?: "CONTACT"
        val text = (call.args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (contactId.isBlank() || text.isBlank()) {
            return@pluginTool textResult("Error: contact_id and text must not be blank", isError = true)
        }

        store.appendChatMessage(contactId, sender, text)
        textResult("已将对话记录安全追加写入本机存储。")
    }

    private fun createIngestChatFrameTool(store: LocalRelationalStore): PluginTool = pluginTool(
        name = "ingest_chat_frame",
        label = "载入聊天时序帧并自动执行改名与换头像重识别",
        description = "载入当前屏幕或会话的聊天帧，利用前序聊天记录指纹、头像哈希与平台ID，在联系人更换头像或修改昵称后依然精准归档至同一联系人记录中。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("display_name") {
                    put("type", "string")
                    put("description", "当前会话窗口顶部显示的联系人或群昵称")
                }
                putJsonObject("new_messages") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "消息正文")
                            }
                            putJsonObject("sender") {
                                put("type", "string")
                                put("description", "发送者：CONTACT 或 USER，默认 CONTACT")
                            }
                            putJsonObject("timestamp") {
                                put("type", "integer")
                                put("description", "毫秒时间戳（可选）")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("text"))
                        }
                    }
                    put("description", "待载入的最新消息列表")
                }
                putJsonObject("preceding_context_messages") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "当前屏幕可见的前序历史消息文本（关键指纹：用于换头像和改名后的时序重叠连续性比对）")
                }
                putJsonObject("avatar_hash") {
                    put("type", "string")
                    put("description", "当前头像特征签名或图片哈希（可选）")
                }
                putJsonObject("platform_id") {
                    put("type", "string")
                    put("description", "不可变底层平台标识符（如 wxid，可选）")
                }
                putJsonObject("semantic_hints") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "语义锚点提示（如孩子姓名、所在班级等客观事实）")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("display_name"))
                add(JsonPrimitive("new_messages"))
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val displayName = (call.args["display_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val avatarHash = (call.args["avatar_hash"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val platformId = (call.args["platform_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        val preceding = (call.args["preceding_context_messages"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }.orEmpty()

        val semanticHints = (call.args["semantic_hints"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }.orEmpty()

        val rawMsgsArray = call.args["new_messages"] as? JsonArray ?: JsonArray(emptyList())
        val newMsgs = rawMsgsArray.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val sender = (obj["sender"] as? JsonPrimitive)?.contentOrNull ?: "CONTACT"
            val timestamp = (obj["timestamp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis()
            IncomingChatMessage(
                sender = sender,
                text = text,
                timestamp = timestamp,
                rawSenderName = displayName,
            )
        }

        if (displayName.isBlank() || newMsgs.isEmpty()) {
            return@pluginTool textResult("Error: display_name and new_messages must not be empty", isError = true)
        }

        val frame = IncomingChatFrame(
            currentDisplayName = displayName,
            currentAvatarHash = avatarHash,
            platformId = platformId,
            newMessages = newMsgs,
            precedingContextMessages = preceding,
            semanticHints = semanticHints,
        )

        val resolution = store.ingestChatFrame(frame)

        val report = buildString {
            appendLine("【聊天时序帧载入与身份重识别结果】")
            appendLine("• 归档目标联系人：${resolution.contactId}")
            appendLine("• 匹配策略：${resolution.strategy} (置信度: ${(resolution.confidence * 100).toInt()}%)")
            appendLine("• 说明：${resolution.explanation}")
            if (resolution.nameChanged) {
                appendLine("• ⚠️ 监测到昵称变动：'${resolution.oldName}' → '${resolution.newName}'（已自动记入历史曾用名）")
            }
            if (resolution.avatarChanged) {
                appendLine("• ⚠️ 监测到头像更换：已自动更新并归档旧头像签名")
            }
            appendLine("• 新增消息写入：${newMsgs.size} 条")
        }

        val details = buildJsonObject {
            put(Cards.RAW_KEY, "ContactId: ${resolution.contactId}\nStrategy: ${resolution.strategy}")
            put(Cards.GUIDANCE_KEY, "Chat frame ingested and linked to contact ${resolution.contactId}.")
        }

        textResult(report, details = details)
    }

    private fun createImportTranscriptTool(store: LocalRelationalStore): PluginTool = pluginTool(
        name = "import_chat_transcript",
        label = "批量导入纯文本转录聊天记录",
        description = "支持直接粘贴微信多选复制的长文本记录或导出文本，自动解析时间、双方发言角色并按序落库。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("transcript_text") {
                    put("type", "string")
                    put("description", "导出的聊天记录纯文本或剪贴板文本")
                }
                putJsonObject("contact_id_hint") {
                    put("type", "string")
                    put("description", "预期归档的目标联系人ID（可选，若提供则优先归档）")
                }
                putJsonObject("display_name") {
                    put("type", "string")
                    put("description", "该联系人的当前显示称谓或姓名（可选）")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("transcript_text"))
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val transcriptText = (call.args["transcript_text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val contactHint = (call.args["contact_id_hint"] as? JsonPrimitive)?.contentOrNull
        val displayName = (call.args["display_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        if (transcriptText.isBlank()) {
            return@pluginTool textResult("Error: transcript_text must not be blank", isError = true)
        }

        val result = store.importChatTranscriptText(
            rawTranscript = transcriptText,
            defaultContactId = contactHint,
            currentDisplayName = displayName,
        )

        val report = buildString {
            appendLine("【聊天记录批量导入完成】")
            appendLine("• 归档联系人：${result.contactId} (${result.resolvedName})")
            appendLine("• 成功解析并入库消息：${result.importedCount} 条")
            appendLine("• 匹配判定：${result.resolutionResult.explanation}")
        }

        textResult(report)
    }

    private fun createAutonomousScanTool(
        store: LocalRelationalStore,
        agent: AutonomousRelationalAgent,
    ): PluginTool = pluginTool(
        name = "trigger_autonomous_chat_scan",
        label = "在空闲且授权状态下自动浏览与同步聊天记录",
        description = "AI 在系统没有前台任务（空闲状态）且用户已显式授权的情况下，安全扫描同步未归档的聊天记录帧并自动解析联系人更名与头像变动。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("is_system_idle") {
                    put("type", "boolean")
                    put("description", "当前系统是否处于空闲无前台任务状态（默认为 true）")
                }
                putJsonObject("mock_chat_frames") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("display_name") { put("type", "string") }
                            putJsonObject("avatar_hash") { put("type", "string") }
                            putJsonObject("preceding_messages") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                            putJsonObject("new_messages") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("text") { put("type", "string") }
                                        putJsonObject("sender") { put("type", "string") }
                                    }
                                    putJsonArray("required") { add(JsonPrimitive("text")) }
                                }
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("display_name"))
                        }
                    }
                    put("description", "本次扫描检测到的会话帧列表（可选）")
                }
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val isIdle = (call.args["is_system_idle"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val rawFrames = call.args["mock_chat_frames"] as? JsonArray ?: JsonArray(emptyList())

        val frames = rawFrames.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = (obj["display_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val avatar = (obj["avatar_hash"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val preceding = (obj["preceding_messages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val msgsArr = obj["new_messages"] as? JsonArray ?: JsonArray(emptyList())
            val newMsgs = msgsArr.mapNotNull { m ->
                val mObj = m as? JsonObject ?: return@mapNotNull null
                val text = (mObj["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val sender = (mObj["sender"] as? JsonPrimitive)?.contentOrNull ?: "CONTACT"
                IncomingChatMessage(sender = sender, text = text)
            }
            IncomingChatFrame(
                currentDisplayName = name,
                currentAvatarHash = avatar,
                newMessages = newMsgs,
                precedingContextMessages = preceding,
            )
        }

        val scanResult = agent.executeAutonomousBrowse(
            isSystemIdle = isIdle,
            discoveredChatFrames = frames,
        )

        val report = buildString {
            appendLine("【后台自主聊天记录扫描报告】")
            appendLine("• 运行状态：${if (scanResult.executed) "成功完成" else "已拦截跳过"}")
            appendLine("• 状态原因：${scanResult.reason}")
            if (scanResult.executed) {
                appendLine("• 处理会话帧数：${scanResult.framesProcessed} 帧")
                appendLine("• 增量入库消息：${scanResult.messagesIngested} 条")
                if (scanResult.reconciledContacts.isNotEmpty()) {
                    appendLine("• 识别联系人更名/换头像联动：")
                    scanResult.reconciledContacts.forEach { appendLine("   - $it") }
                }
            }
        }

        textResult(report)
    }

    private fun createConfigureAutonomousTool(agent: AutonomousRelationalAgent): PluginTool = pluginTool(
        name = "configure_autonomous_ingestion",
        label = "配置后台自动浏览聊天记录授权与策略",
        description = "管理后台自动浏览的授权开关（用户未授权严禁启动）、空闲扫描频率及自动身份对齐策略。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("is_authorized") {
                    put("type", "boolean")
                    put("description", "是否授权 AI 在后台空闲时自动浏览并同步聊天记录")
                }
                putJsonObject("allow_idle_browsing") {
                    put("type", "boolean")
                    put("description", "是否仅在系统空闲无前台任务时运行")
                }
                putJsonObject("scan_interval_minutes") {
                    put("type", "integer")
                    put("description", "后台扫描间隔分钟数")
                }
            }
        },
        exposure = PluginToolExposure.DIRECT,
    ) { call ->
        val current = agent.getConfig()
        val authorized = (call.args["is_authorized"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: current.isAuthorized
        val allowIdle = (call.args["allow_idle_browsing"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: current.allowIdleBrowsing
        val interval = (call.args["scan_interval_minutes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: current.scanIntervalMinutes

        val updated = current.copy(
            isAuthorized = authorized,
            allowIdleBrowsing = allowIdle,
            scanIntervalMinutes = interval,
        )
        agent.updateConfig(updated)

        val report = buildString {
            appendLine("【后台自动浏览配置已更新】")
            appendLine("• 用户授权状态：${if (updated.isAuthorized) "已明确授权 (Active)" else "未授权 (Disabled)"}")
            appendLine("• 仅空闲时运行：${if (updated.allowIdleBrowsing) "开启（严守无前台任务门禁）" else "关闭"}")
            appendLine("• 扫描间隔：每 ${updated.scanIntervalMinutes} 分钟")
        }

        textResult(report)
    }
}
