package io.github.whywood.superpi.plugin.relational

import io.github.whywood.superpi.plugin.api.Cards
import io.github.whywood.superpi.plugin.api.PluginRunSignal
import io.github.whywood.superpi.plugin.api.PluginToolCall
import io.github.whywood.superpi.plugin.api.PluginToolResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelationalCopilotPluginTest {

    private lateinit var tempDir: File
    private lateinit var plugin: RelationalCopilotPlugin
    private lateinit var store: LocalRelationalStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi_relational_test").toFile()
        plugin = RelationalCopilotPlugin(tempDir)
        store = plugin.getStore(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testLocalStorageSaveAndWindowedRetrieval() {
        val contact = ContactDossier(
            id = "c_parent_1",
            name = "李女士（张小明妈妈）",
            category = "EDUCATION",
            role = "家委会副主任",
            taboos = listOf("视力/排座敏感", "反感同伴比较"),
            notes = "某三甲医院护士",
        )
        store.saveContact(contact)

        val loaded = store.getContact("c_parent_1")
        assertNotNull(loaded)
        assertEquals("李女士（张小明妈妈）", loaded?.name)
        assertEquals(2, loaded?.taboos?.size)

        val longSentence = "这是一段非常长的关于近期学生在校状态与家庭作业情况的交流反馈文字记录，包含诸多细节讨论。".repeat(10)
        for (i in 1..10) {
            store.appendChatMessage("c_parent_1", if (i % 2 == 0) "USER" else "CONTACT", "消息 $i: $longSentence")
        }

        val windowed = store.getRecentChatMessages("c_parent_1", maxChars = 2000)
        assertTrue(windowed.isNotEmpty())
        val totalChars = windowed.sumOf { it.text.length }
        assertTrue("Total chars should be within limit", totalChars <= 2500)
        assertTrue(windowed.last().text.contains("消息 10"))
    }

    @Test
    fun testBriefingGenerationProvidesAdvisoryWithoutUnsolicitedReply() = runBlocking {
        val contact = ContactDossier(
            id = "c_parent_2",
            name = "李子轩妈妈",
            category = "EDUCATION",
            taboos = listOf("视力/排座高度戒备", "严禁与同伴做优劣对比"),
        )
        store.saveContact(contact)

        store.appendChatMessage("c_parent_2", "CONTACT", "老师好，昨天科学课拉开桌子，孩子说靠窗反光看不清。")
        store.appendChatMessage("c_parent_2", "CONTACT", "今天数学只考了78分，孩子回来说看不清黑板还被罚站了，自尊心很受挫。")

        val contributions = plugin.contributions(createFakeHost(tempDir))
        val briefingTool = contributions.tools.first { it.name == "get_relationship_briefing" }

        val call = makeCall(
            "call_1",
            buildJsonObject {
                put("contact_id", "c_parent_2")
            },
        )

        val result = briefingTool.execute(call)
        assertFalse(result.isError)

        val text = result.text.orEmpty()
        assertTrue(text.contains("李子轩妈妈"))
        assertTrue(text.contains("视力/排座高度戒备"))
        assertTrue(text.contains("言外之意与关切诊断（基于聊天记录）"))
        assertTrue(text.contains("沟通策略与避坑指南"))
        assertTrue(text.contains("纯参谋模式"))
        assertTrue(text.contains("实际聊天记录为主"))
        assertFalse(text.contains("明天早自习我就把他往前调到中间第三排"))
        assertFalse(text.contains("老师走过去轻轻敲了敲桌子"))
        assertFalse(text.contains("选项 1"))

        assertNotNull(result.details)
        assertTrue(result.details?.containsKey(Cards.DISPLAY_KEY) == true)
        assertTrue(text.contains("Usage:"))
        assertTrue(text.contains("Present briefing to user without generating unprompted reply text."))
    }

    @Test
    fun testGuidedDraftingStrictlyFollowsUserIntentAndChecksTaboos() = runBlocking {
        val contact = ContactDossier(
            id = "c_parent_3",
            name = "轩轩妈",
            category = "EDUCATION",
            taboos = listOf("视力/排座戒备", "反感同伴对比"),
        )
        store.saveContact(contact)

        val contributions = plugin.contributions(createFakeHost(tempDir))
        val draftTool = contributions.tools.first { it.name == "draft_guided_response" }

        val userIntent = "明天早上去教室实际看一下反光，但别答应现在调换座位，明晚放学向家长反馈"

        val call = makeCall(
            "call_2",
            buildJsonObject {
                put("contact_id", "c_parent_3")
                put("user_intent", userIntent)
            },
        )

        val result = draftTool.execute(call)
        assertFalse(result.isError)

        val text = result.text.orEmpty()
        assertTrue(text.contains("已装配真实档案与意图约束，移交 AI 现场动态生成"))
        assertTrue(text.contains("绝不提供写好的回答模板"))
        assertTrue(text.contains("聊天记录为第一核心凭据"))
        assertTrue(text.contains("选项 1【温和共情版】"))
        assertTrue(text.contains("选项 2【专业客观版】"))
        assertTrue(text.contains("选项 3【简明得体版】"))
        assertTrue(text.contains(userIntent))
        assertTrue(text.contains("视力/排座戒备"))
        assertTrue(text.contains("严禁虚构未发生的现场细节"))
    }

    @Test
    fun testWorkplaceCategoryBriefingAndDrafting() = runBlocking {
        val contact = ContactDossier(
            id = "c_boss_1",
            name = "王总（技术VP）",
            category = "WORKPLACE",
            role = "技术负责人",
            taboos = listOf("忌在非工作时间催促", "注重汇报格式与进度节点"),
        )
        store.saveContact(contact)

        store.appendChatMessage("c_boss_1", "CONTACT", "架构方案评审什么时候能出？客户下周就要看。")

        val contributions = plugin.contributions(createFakeHost(tempDir))
        val draftTool = contributions.tools.first { it.name == "draft_guided_response" }

        val intent = "周五下午5点前交付初版方案，周一上午组织对齐评审"
        val call = makeCall(
            "call_3",
            buildJsonObject {
                put("contact_id", "c_boss_1")
                put("user_intent", intent)
            },
        )

        val result = draftTool.execute(call)
        assertFalse(result.isError)
        val text = result.text.orEmpty()
        assertTrue(text.contains("温和共情版"))
        assertTrue(text.contains("专业客观版"))
        assertTrue(text.contains("简明得体版"))
        assertTrue(text.contains(intent))
        assertTrue(text.contains("忌在非工作时间催促"))
    }

    @Test
    fun testDialogueContinuityMatchesContactWhenAvatarAndNameChange() = runBlocking {
        val initialContact = ContactDossier(
            id = "c_parent_4",
            name = "李女士（张小明妈妈）",
            avatarHash = "hash_avatar_v1",
            category = "EDUCATION",
            role = "家委会副主任",
        )
        store.saveContact(initialContact)

        // 写入初始历史聊天记录
        store.appendChatMessage("c_parent_4", "CONTACT", "科学课拉开桌子，孩子说靠窗反光严重看不清。")
        store.appendChatMessage("c_parent_4", "CONTACT", "今天单元测验只考了78分，孩子回来说看不清黑板还被罚站了。")

        // 模拟后续微信中：该家长同时更换了头像（hash_avatar_v2）和昵称（改为“阳光明媚小仙女”）
        val incomingFrame = IncomingChatFrame(
            currentDisplayName = "阳光明媚小仙女",
            currentAvatarHash = "hash_avatar_v2",
            newMessages = listOf(
                IncomingChatMessage(
                    sender = "CONTACT",
                    text = "老师明天孩子想请假半天去同济复查眼睛。",
                ),
            ),
            precedingContextMessages = listOf(
                "今天单元测验只考了78分，孩子回来说看不清黑板还被罚站了。",
            ),
        )

        // 执行载入与身份重识别
        val resolution = store.ingestChatFrame(incomingFrame)

        // 验证基于会话时序重叠准确找回原联系人
        assertEquals("c_parent_4", resolution.contactId)
        assertEquals(MatchStrategy.DIALOGUE_CONTINUITY, resolution.strategy)
        assertTrue("Name must be detected as changed", resolution.nameChanged)
        assertTrue("Avatar must be detected as changed", resolution.avatarChanged)

        // 验证联系人档案已自动更新最新姓名并保存曾用名轨迹
        val updatedContact = store.getContact("c_parent_4")
        assertNotNull(updatedContact)
        assertEquals("阳光明媚小仙女", updatedContact?.name)
        assertTrue(updatedContact?.historicalNames?.contains("李女士（张小明妈妈）") == true)
        assertEquals("hash_avatar_v2", updatedContact?.avatarHash)
        assertTrue(updatedContact?.historicalAvatarHashes?.contains("hash_avatar_v1") == true)

        // 验证新消息已追加至同一联系人的时序存储中
        val history = store.formatRecentChatHistory("c_parent_4")
        assertTrue(history.contains("复查眼睛"))
        assertTrue(history.contains("考了78分"))
    }

    @Test
    fun testAutonomousBrowsingGateRequiresBothAuthorizationAndIdle() = runBlocking {
        val agent = plugin.getAutonomousAgent(tempDir)

        val frame = IncomingChatFrame(
            currentDisplayName = "某联系人",
            newMessages = listOf(IncomingChatMessage("CONTACT", "测试消息")),
        )

        // 1. 未授权情况下：严禁执行
        agent.setAuthorization(false)
        val unauthorizedResult = agent.executeAutonomousBrowse(isSystemIdle = true, listOf(frame))
        assertFalse(unauthorizedResult.executed)
        assertTrue(unauthorizedResult.reason.contains("UNAUTHORIZED"))

        // 2. 已授权但系统繁忙（有前台任务）：严禁执行
        agent.setAuthorization(true)
        val busyResult = agent.executeAutonomousBrowse(isSystemIdle = false, listOf(frame))
        assertFalse(busyResult.executed)
        assertTrue(busyResult.reason.contains("SYSTEM_BUSY"))

        // 3. 已授权且系统空闲（无前台任务）：成功执行
        val successResult = agent.executeAutonomousBrowse(isSystemIdle = true, listOf(frame))
        assertTrue(successResult.executed)
        assertEquals("AUTONOMOUS_SCAN_SUCCESS", successResult.reason)
        assertEquals(1, successResult.framesProcessed)
        assertEquals(1, successResult.messagesIngested)
    }

    @Test
    fun testTranscriptTextBatchImport() = runBlocking {
        val rawTranscript = """
            2026-11-20 14:32 李女士: 老师好，科学课拉开桌子孩子说靠窗反光。
            2026-11-20 14:35 我: 收到，明早我去核查。
            2026-11-21 21:10 李女士: 孩子今天只考了78分，自尊心很受挫。
        """.trimIndent()

        val importResult = store.importChatTranscriptText(
            rawTranscript = rawTranscript,
            currentDisplayName = "李女士（批量导入）",
        )

        assertTrue(importResult.importedCount >= 3)
        val history = store.formatRecentChatHistory(importResult.contactId)
        assertTrue(history.contains("靠窗反光"))
        assertTrue(history.contains("受挫"))
    }

    @Test
    fun testPluginManifestAndToolSchemaCompliance() {
        val contributions = plugin.contributions(createFakeHost(tempDir))
        assertEquals(8, contributions.tools.size)

        for (tool in contributions.tools) {
            assertTrue("Tool name must be valid", tool.name.isNotBlank())
            assertTrue("Tool description must be provided", tool.description.isNotBlank())
            val params = tool.parameters
            assertEquals(JsonPrimitive("object"), params["type"])
            assertNotNull("Tool parameters must declare properties", params["properties"])
        }
    }

    private fun makeCall(callId: String, args: kotlinx.serialization.json.JsonObject): PluginToolCall =
        object : PluginToolCall {
            override val callId: String = callId
            override val args: kotlinx.serialization.json.JsonObject = args
            override val signal: PluginRunSignal? = null
            override val sessionId: String = "session-test"
            override fun partial(result: PluginToolResult) {}
        }

    private fun createFakeHost(dataDir: File): io.github.whywood.superpi.plugin.api.PluginHost {
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "getDataDir" -> dataDir
                "getAssetsDir" -> dataDir
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            io.github.whywood.superpi.plugin.api.PluginHost::class.java.classLoader,
            arrayOf(io.github.whywood.superpi.plugin.api.PluginHost::class.java),
            handler,
        ) as io.github.whywood.superpi.plugin.api.PluginHost
    }
}
