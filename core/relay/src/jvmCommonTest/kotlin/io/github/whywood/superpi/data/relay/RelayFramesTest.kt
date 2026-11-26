package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.RunStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFramesTest {
    @Test
    fun parsesHelloOkWithDevicesAndBots() {
        val frame = RelayFrames.parse(
            """
            {"type":"hello_ok","accountId":"acct","memoryVersion":4,"devices":[
              {"deviceId":"dev-1","name":"Phone B","platform":"android","online":true,"lastSeen":10,
               "bots":[{"id":"home","name":"PI"}]}
            ]}
            """.trimIndent(),
        ) as RelayServerFrame.HelloOk

        assertEquals("acct", frame.accountId)
        assertEquals(4L, frame.memoryVersion)
        val device = frame.devices.single()
        assertEquals("Phone B", device.name)
        assertTrue(device.online)
        assertEquals("PI", device.bots.single().name)
    }

    @Test
    fun parsesPresence() {
        val frame = RelayFrames.parse(
            """{"type":"presence","deviceId":"dev-2","online":false,"lastSeen":99}""",
        ) as RelayServerFrame.Presence

        assertEquals("dev-2", frame.deviceId)
        assertEquals(false, frame.online)
        assertEquals(99L, frame.lastSeen)
    }

    @Test
    fun parsesRouteAndResults() {
        val route = RelayFrames.parse(
            """{"type":"route","id":"r1","from":"dev-2","method":"bot.prompt","params":{"botId":"home","text":"hi"}}""",
        ) as RelayServerFrame.Route
        assertEquals("dev-2", route.from)
        assertEquals("home", route.params.getValue("botId").jsonPrimitive.content)

        val ok = RelayFrames.parse("""{"type":"route_ok","id":"r1","result":{"sessionId":"s1"}}""") as RelayServerFrame.RouteOk
        assertEquals("s1", ok.result?.getValue("sessionId")?.jsonPrimitive?.content)

        val failure = RelayFrames.parse(
            """{"type":"route_error","id":"r1","code":"device_offline","message":"offline"}""",
        ) as RelayServerFrame.RouteError
        assertEquals("device_offline", failure.code)

        val event = RelayFrames.parse(
            """{"type":"route_event","id":"r1","seq":3,"event":{"type":"text_delta","delta":"hi"}}""",
        ) as RelayServerFrame.RouteEvent
        assertEquals(3L, event.seq)
        assertEquals("text_delta", event.event.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun parsesMemoryChangedAndPong() {
        val changed = RelayFrames.parse("""{"type":"memory_changed","version":7}""") as RelayServerFrame.MemoryChanged
        assertEquals(7L, changed.version)
        assertEquals(RelayServerFrame.Pong, RelayFrames.parse("""{"type":"pong"}"""))
        assertEquals(RelayServerFrame.Unknown("later"), RelayFrames.parse("""{"type":"later"}"""))
    }

    @Test
    fun encodesHelloAndRoutes() {
        val hello = RelayFrames.hello(
            deviceId = "dev-1",
            platform = "android",
            appVersion = "2.0.1",
            bots = listOf(RelayBot("bot-1", "Bot one")),
        )
        assertEquals("hello", hello.getValue("type").jsonPrimitive.content)
        assertEquals("android", hello.getValue("platform").jsonPrimitive.content)
        val bot = hello.getValue("bots").let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject
        assertEquals("bot-1", bot.getValue("id").jsonPrimitive.content)

        val route = RelayFrames.route("r1", "dev-2", "bot.prompt", buildJsonObject { put("text", "hi") })
        assertEquals("bot.prompt", route.getValue("method").jsonPrimitive.content)
        assertEquals("r1", route.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun roundTripsOptionalBotAvatar() {
        val dataUrl = "data:image/jpeg;base64,/9j/4AAQ"
        val hello = RelayFrames.hello(
            deviceId = "dev-1",
            platform = "android",
            appVersion = "2.0.1",
            bots = listOf(
                RelayBot("bot-a", "Bot A", avatar = dataUrl),
                RelayBot("bot-b", "Bot B"),
            ),
        )
        val encodedBots = hello.getValue("bots").let { it as kotlinx.serialization.json.JsonArray }
        assertEquals(dataUrl, encodedBots[0].jsonObject.getValue("avatar").jsonPrimitive.content)
        assertTrue(!encodedBots[1].jsonObject.containsKey("avatar"))

        val parsed = RelayFrames.parse(
            """
            {"type":"hello_ok","accountId":"acct","memoryVersion":1,"capabilities":["remote-bot","bot-sessions"],"devices":[
              {"deviceId":"dev-1","name":"Phone","platform":"android","online":true,"lastSeen":5,"capabilities":["remote-bot","bot-sessions"],
               "bots":[{"id":"bot-a","name":"Bot A","avatar":"$dataUrl"},{"id":"bot-b","name":"Bot B","avatar":""}]}
            ]}
            """.trimIndent(),
        ) as RelayServerFrame.HelloOk
        assertEquals(listOf("remote-bot", "bot-sessions"), parsed.capabilities)
        assertEquals(listOf("remote-bot", "bot-sessions"), parsed.devices.single().capabilities)
        val bots = parsed.devices.single().bots
        assertEquals(dataUrl, bots[0].avatar)
        assertEquals(null, bots[1].avatar)
    }

    @Test
    fun encodesCapabilitiesAndParsesSessionHistoryAndImages() {
        val hello = RelayFrames.hello(
            deviceId = "dev-1",
            platform = "android",
            appVersion = "2.0.1",
            bots = emptyList(),
        )
        val caps = hello.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(RELAY_CAPABILITIES, caps)

        val sessions = RelayFrames.parseRemoteSessions(
            buildJsonObject {
                put(
                    "sessions",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "s-1")
                                put("title", "Remote Chat")
                                put("updatedAt", 123L)
                                put("messageCount", 4)
                            },
                        )
                    },
                )
            },
        )
        assertEquals(1, sessions.size)
        assertEquals("Remote Chat", sessions.single().title)
        assertEquals(4, sessions.single().messageCount)

        val history = RelayFrames.parseRemoteHistory(
            buildJsonObject {
                put("sessionId", "s-1")
                put("title", "Remote Chat")
                put("busy", true)
                put("seq", 9L)
                put("messages", kotlinx.serialization.json.buildJsonArray {})
            },
        )
        assertEquals(9L, history?.seq)
        assertEquals(true, history?.busy)

        val images = RelayFrames.parseImages(
            buildJsonObject {
                put(
                    "images",
                    kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject { put("data", "YWJj"); put("mimeType", "image/png") })
                        add(buildJsonObject { put("data", "eHl6"); put("mimeType", "image/svg+xml") })
                    },
                )
            },
        )
        assertEquals(listOf(RouteImagePart("YWJj", "image/png")), images)

        val oversizedChunk = "A".repeat(1_200_000)
        val boundedArray = RelayFrames.imagesArray(
            listOf(
                RouteImagePart(oversizedChunk, "image/jpeg"),
                RouteImagePart(oversizedChunk, "image/jpeg"),
                RouteImagePart(oversizedChunk, "image/jpeg"),
            ),
        )
        assertEquals(2, boundedArray.size)
        val boundedParsed = RelayFrames.parseImages(buildJsonObject { put("images", boundedArray) })
        assertEquals(2, boundedParsed.size)
    }

    @Test
    fun parsesAndEncodesCapabilitiesSyncEventsFileEntriesAndPaths() {
        assertTrue(RELAY_CAPABILITIES.containsAll(listOf("bot-live-sync", "bot-manage", "session-manage", "workspace-files", "bot-controls", "session-controls", "sync-events", "memory-v3")))
        assertFalse(RELAY_CAPABILITIES.contains("memory-v2"))
        assertFalse(RELAY_CAPABILITIES.contains("workspace-git"))
        assertFalse(RELAY_CAPABILITIES.contains("session-fork"))
        assertFalse(RELAY_CAPABILITIES_READONLY.contains("bot-manage"))
        assertFalse(RELAY_CAPABILITIES_READONLY.contains("bot-controls"))
        assertFalse(RELAY_CAPABILITIES_READONLY.contains("session-controls"))
        assertTrue(RELAY_CAPABILITIES_READONLY.containsAll(listOf("sync-events", "memory-v3")))
        assertFalse(RELAY_CAPABILITIES_READONLY.contains("memory-v2"))

        val modelSyncJson = RelayFrames.syncEvent(
            kind = "model_changed",
            botId = "home",
            sessionId = "s1",
            provider = "openai",
            modelId = "gpt-5",
            thinkingLevel = "xhigh",
            ts = 1234L,
        )
        val parsedModelSync = RelayFrames.parse(modelSyncJson.toString()
            .replace("}", ""","deviceId":"dev-1"}""")) as RelayServerFrame.SyncEvent
        assertEquals("model_changed", parsedModelSync.kind)
        assertEquals("openai", parsedModelSync.provider)
        assertEquals("gpt-5", parsedModelSync.modelId)
        assertEquals("xhigh", parsedModelSync.thinkingLevel)

        val syncJson = RelayFrames.syncEvent(
            kind = "sessions_changed",
            botId = "home",
            sessionId = "s-9",
            busy = true,
            pendingApprovals = 2,
            ts = 1234L,
        )
        assertEquals("sync_event", syncJson.getValue("type").jsonPrimitive.content)
        val parsedSync = RelayFrames.parse(
            """{"type":"sync_event","deviceId":"dev-2","kind":"sessions_changed","botId":"home","sessionId":"s-9","busy":true,"pendingApprovals":2,"ts":1234}""",
        ) as RelayServerFrame.SyncEvent
        assertEquals("dev-2", parsedSync.deviceId)
        assertEquals("home", parsedSync.botId)
        assertEquals("s-9", parsedSync.sessionId)
        assertEquals(true, parsedSync.busy)
        assertEquals(2, parsedSync.pendingApprovals)

        val sessions = RelayFrames.parseRemoteSessions(
            buildJsonObject {
                put(
                    "sessions",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "s-1")
                                put("title", "Archived Pin")
                                put("updatedAt", 99L)
                                put("pinned", true)
                                put("archived", true)
                            },
                        )
                    },
                )
            },
        )
        assertEquals(true, sessions.single().pinned)
        assertEquals(true, sessions.single().archived)

        val parsedBot = RelayFrames.parseRemoteBot(buildJsonObject { put("id", "b1"); put("name", "Coder") })
        assertEquals("b1", parsedBot?.id)
        assertEquals("Coder", parsedBot?.name)

        val fileList = RelayFrames.parseRemoteFileList(
            buildJsonObject {
                put("path", "src")
                put(
                    "entries",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", "a.txt")
                                put("path", "src/a.txt")
                                put("type", "file")
                                put("size", 12L)
                                put("modifiedAt", 42L)
                                put("mimeType", "text/plain")
                            },
                        )
                    },
                )
            },
        )
        assertEquals("src", fileList?.first)
        assertEquals("src/a.txt", fileList?.second?.single()?.path)

        assertEquals("docs/readme.md", RelayFrames.validateRelativeWorkspacePath("docs/readme.md"))
        assertEquals("", RelayFrames.validateRelativeWorkspacePath("", allowEmpty = true))
        assertTrue(runCatching { RelayFrames.validateRelativeWorkspacePath("../secret") }.isFailure)
        assertTrue(runCatching { RelayFrames.validateRelativeWorkspacePath("C:secret") }.isFailure)
        assertTrue(runCatching { RelayFrames.validateRelativeWorkspacePath("dir:name/file.txt") }.isFailure)
        assertEquals("image/png", RelayFrames.detectImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)))
    }
}

class RelayEventMapperTest {
    @Test
    fun mapsStreamingTextAndReasoning() {
        val text = RelayEventMapper.map(RuntimeEvent.TextDelta("run", "hello")).single()
        assertEquals("message_update", text.string("type"))
        val delta = text.getValue("assistantMessageEvent") as JsonObject
        assertEquals("text_delta", delta.string("type"))
        assertEquals("hello", delta.string("delta"))

        val reasoning = RelayEventMapper.map(RuntimeEvent.ReasoningDelta("run", "think")).single()
        val reasoningDelta = reasoning.getValue("assistantMessageEvent") as JsonObject
        assertEquals("reasoning_delta", reasoningDelta.string("type"))
    }

    @Test
    fun mapsToolsAndRunEnd() {
        val call = RelayEventMapper.map(
            RuntimeEvent.ToolStarted(
                runId = "run",
                callId = "call-1",
                name = "read",
                arguments = mapOf("path" to "a.txt"),
                startedAt = 1L,
            ),
        ).single()
        assertEquals("tool_call", call.string("type"))
        assertEquals("call-1", call.string("callId"))
        assertEquals("a.txt", (call.getValue("args") as JsonObject).string("path"))

        val result = RelayEventMapper.map(
            RuntimeEvent.ToolFinished(
                runId = "run",
                callId = "call-1",
                name = "read",
                result = "done",
                isError = false,
                durationMs = 3L,
            ),
        ).single()
        assertEquals("tool_result", result.string("type"))
        assertEquals("done", result.string("result"))
        assertEquals(false, result.getValue("isError").jsonPrimitive.boolean)

        val finished = RelayEventMapper.map(RuntimeEvent.RunFinished("run", "ok", RunStatus.DONE, 2L))
        assertEquals(2, finished.size)
        assertEquals("agent_end", finished.first().string("type"))
        assertEquals("agent_settled", finished.last().string("type"))
    }

    @Test
    fun mapsFailuresAndQueue() {
        val failed = RelayEventMapper.map(RuntimeEvent.RunFailed("run", "boom"))
        assertEquals("error", failed.first().string("type"))
        assertEquals("boom", failed.first().string("message"))
        assertEquals("agent_settled", failed.last().string("type"))

        val queue = RelayEventMapper.map(RuntimeEvent.QueueChanged("run", listOf("a"), listOf("b"))).single()
        assertEquals("queue_update", queue.string("type"))
        assertEquals("a", (queue.getValue("steering") as kotlinx.serialization.json.JsonArray).single().jsonPrimitive.content)
    }

    @Test
    fun mapsPluginCustomMessage() {
        val custom = RelayEventMapper.map(
            RuntimeEvent.PluginCustomMessage(
                runId = "run",
                customType = "weather_card",
                payload = """{"temp":22}""",
                text = "Sunny 22C",
                display = true,
            ),
        ).single()
        assertEquals("plugin_custom_message", custom.string("type"))
        assertEquals("weather_card", custom.string("customType"))
        assertEquals("""{"temp":22}""", custom.string("payload"))
        assertEquals("Sunny 22C", custom.string("text"))
        assertEquals(true, custom.getValue("display").jsonPrimitive.boolean)
    }

    @Test
    fun ignoresUiOnlyEvents() {
        assertTrue(RelayEventMapper.map(RuntimeEvent.SessionListChanged()).isEmpty())
    }

    @Test
    fun encodesAndParsesSuperWorkerCapabilityAndRichBotMetadata() {
        assertTrue(RELAY_CAPABILITIES.contains("super-worker"))
        assertFalse(RELAY_CAPABILITIES_READONLY.contains("super-worker"))

        val caps = RelayBotCapabilities(
            skills = listOf("screen-ops", "git-workflow"),
            plugins = listOf("Mail", "Calendar"),
            mcpServers = listOf("github"),
            screenTools = true,
            miniappTools = false,
            memoryScope = "WORK_ONLY",
            memoryDispatch = true,
        )
        val hello = RelayFrames.hello(
            deviceId = "dev-1",
            platform = "android",
            appVersion = "2.0.1",
            bots = listOf(
                RelayBot(
                    id = "home",
                    name = "PI",
                    intro = "Personal assistant",
                    capabilities = caps,
                ),
                RelayBot(id = "plain", name = "Plain"),
            ),
        )
        val parsed = RelayFrames.parse(
            buildJsonObject {
                put("type", "hello_ok")
                put("accountId", "acct")
                put("memoryVersion", 1L)
                put("capabilities", hello.getValue("capabilities"))
                put(
                    "devices",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("deviceId", "dev-1")
                                put("name", "Phone A")
                                put("platform", "android")
                                put("online", true)
                                put("lastSeen", 10L)
                                put("bots", hello.getValue("bots"))
                            },
                        )
                    },
                )
            }.toString(),
        ) as RelayServerFrame.HelloOk

        assertTrue(parsed.capabilities.contains("super-worker"))
        val bots = parsed.devices.single().bots
        assertEquals("Personal assistant", bots[0].intro)
        assertEquals(caps, bots[0].capabilities)
        assertEquals("", bots[1].intro)
        assertEquals(null, bots[1].capabilities)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
