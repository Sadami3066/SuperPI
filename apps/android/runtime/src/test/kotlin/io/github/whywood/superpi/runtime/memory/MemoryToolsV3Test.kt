package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.sdk.ai.Context
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryToolsV3Test {
    private lateinit var rootDir: File
    private var nowMs = 1_760_500_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("superpi-mem-tools-v3").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun toolVisibilityFollowsScopeAndDispatchPolicyWithoutLegacyTools() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            store.ensure()
            val service = MemoryAgentService(
                store = store,
                gateway = object : MemoryAgentGateway {
                    override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult =
                        MemoryAgentStepResult.NoModel
                },
                scope = scope,
            )

            val fullTools = memoryTools(
                store = store,
                agentService = service,
                scope = { MemoryAccessScope.PERSONAL },
                allowDispatch = { true },
            )
            assertEquals(
                listOf("memory_search", "memory_inspect_entity", "memory_read_archive", "memory_dispatch"),
                fullTools.map { it.name },
            )
            assertFalse(fullTools.any { it.name in setOf("memory_save", "memory_forget", "memory_overview") })

            val readOnlyPersonal = memoryTools(
                store = store,
                agentService = service,
                scope = { MemoryAccessScope.PERSONAL },
                allowDispatch = { false },
            )
            assertEquals(
                listOf("memory_search", "memory_inspect_entity", "memory_read_archive"),
                readOnlyPersonal.map { it.name },
            )

            val workerTools = memoryTools(
                store = store,
                agentService = service,
                scope = { MemoryAccessScope.WORK_ONLY },
                allowDispatch = { false },
            )
            assertEquals(
                listOf("memory_search", "memory_inspect_entity", "memory_read_archive"),
                workerTools.map { it.name },
            )

            val disabledTools = memoryTools(
                store = store,
                agentService = service,
                scope = { MemoryAccessScope.OFF },
                allowDispatch = { true },
            )
            assertTrue(disabledTools.isEmpty())
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun dispatchIsNonBlockingInspectEntityAndReadArchiveWorkAndScopeIsolatesData() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val blockedGatewayGate = CompletableDeferred<Unit>()
        try {
            store.ensure()
            val personalEntity = store.upsertEntity(
                MemoryEntityRef(
                    name = "妈妈",
                    entityType = MemoryEntityTypes.FAMILY,
                    aliases = listOf("母亲"),
                    domain = MemoryDomains.RELATIONSHIPS,
                    sensitivity = MemorySensitivity.PRIVATE,
                ),
            )
            store.upsertRelation(
                fromEntityName = "妈妈",
                relationType = MemoryRelationTypes.LIVES_IN,
                toEntityName = "杭州",
                domain = MemoryDomains.RELATIONSHIPS,
                sensitivity = MemorySensitivity.PRIVATE,
            )
            val archived = store.createBlock(
                MemoryBlockDraft(
                    slug = "trip_2025",
                    title = "京都秋季旅行",
                    purpose = "行程汇总",
                    content = "伏见稻荷与岚山漫步",
                    domain = MemoryDomains.LIFE,
                ),
            )
            store.updateBlock(id = archived.id, status = MemoryBlockStatus.ARCHIVED)
            store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.EPISODE,
                    content = "和妈妈散步看红叶",
                    entities = listOf(MemoryEntityRef(personalEntity.canonicalName, MemoryEntityTypes.FAMILY)),
                    domain = MemoryDomains.RELATIONSHIPS,
                    sensitivity = MemorySensitivity.PRIVATE,
                    eventTime = nowMs,
                ),
            )

            val service = MemoryAgentService(
                store = store,
                gateway = object : MemoryAgentGateway {
                    override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult {
                        blockedGatewayGate.await()
                        return MemoryAgentStepResult.NoModel
                    }
                },
                scope = scope,
            )

            var sessionExcluded = false
            val personalTools = memoryTools(
                store = store,
                agentService = service,
                scope = { MemoryAccessScope.PERSONAL },
                allowDispatch = { true },
                sessionId = { "sess-chat-1" },
                isSessionExcluded = { sessionExcluded },
            )

            val dispatchTool = personalTools.first { it.name == "memory_dispatch" }
            val dispatchRes = dispatchTool.execute(
                "tc-disp",
                buildJsonObject {
                    put("intent", "REMEMBER")
                    put("detail", "记住我以后搬去上海静安区")
                    put("description", "记录搬家计划")
                },
                null,
                null,
            )
            assertTrue(dispatchRes.text().contains("queued"))
            assertTrue(store.hasPendingHighPriorityJob())

            sessionExcluded = true
            val pausedDispatch = dispatchTool.execute(
                "tc-disp-excl",
                buildJsonObject {
                    put("intent", "REMEMBER")
                    put("detail", "privacy turn")
                    put("description", "跳过无痕会话")
                },
                null,
                null,
            )
            assertTrue(pausedDispatch.text().contains("paused"))

            val inspectTool = personalTools.first { it.name == "memory_inspect_entity" }
            val entityOut = inspectTool.execute(
                "tc-insp",
                buildJsonObject {
                    put("name", "母亲")
                    put("description", "查看母亲关系")
                },
                null,
                null,
            )
            assertTrue(entityOut.text().contains("妈妈"))
            assertTrue(entityOut.text().contains("杭州"))

            val archiveTool = personalTools.first { it.name == "memory_read_archive" }
            val slugOut = archiveTool.execute(
                "tc-arch-slug",
                buildJsonObject {
                    put("slug", "trip_2025")
                    put("description", "读取归档游记")
                },
                null,
                null,
            )
            assertTrue(slugOut.text().contains("岚山漫步"))

            val monthStr = DateTimeFormatter.ofPattern("yyyy-MM")
                .format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate())
            val monthOut = archiveTool.execute(
                "tc-arch-month",
                buildJsonObject {
                    put("month", monthStr)
                    put("description", "查看本月历史")
                },
                null,
                null,
            )
            assertTrue(monthOut.text().contains("看红叶"))

            val workerTools = memoryTools(
                store = store,
                agentService = service,
                scope = { MemoryAccessScope.WORK_ONLY },
                allowDispatch = { false },
            )
            val workerInspect = workerTools.first { it.name == "memory_inspect_entity" }.execute(
                "tc-w-insp",
                buildJsonObject {
                    put("name", "妈妈")
                    put("description", "查找联系人")
                },
                null,
                null,
            )
            assertTrue(workerInspect.text().contains("No visible memory entry"))

            val workerSearch = workerTools.first { it.name == "memory_search" }.execute(
                "tc-w-search",
                buildJsonObject {
                    put("queries", buildJsonArray { add(JsonPrimitive("红叶")) })
                    put("description", "搜索记忆")
                },
                null,
                null,
            )
            assertTrue(workerSearch.text().contains("No matching memories"))
        } finally {
            blockedGatewayGate.complete(Unit)
            scope.cancel()
            store.close()
        }
    }

    private fun AgentToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("") { it.text }
}
