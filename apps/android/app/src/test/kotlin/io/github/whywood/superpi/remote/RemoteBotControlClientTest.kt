package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.ThinkingLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBotControlClientTest {
    @Test
    fun parsesDesktopAndAndroidControlResponses() = runTest {
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteBotControlClient(transport)

        transport.result = { call ->
            when (call.method) {
                "bot.models" -> buildJsonObject {
                    put(
                        "models",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("provider", "openai")
                                    put("id", "gpt-5")
                                    put("name", "GPT-5")
                                    put("reasoning", true)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("provider", "google")
                                    put("id", "gemini-flash")
                                    put("name", "Gemini Flash")
                                    put("reasoning", false)
                                },
                            )
                        },
                    )
                    put(
                        "thinkingLevels",
                        buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("off"))
                            add(kotlinx.serialization.json.JsonPrimitive("minimal"))
                            add(kotlinx.serialization.json.JsonPrimitive("medium"))
                            add(kotlinx.serialization.json.JsonPrimitive("xhigh"))
                        },
                    )
                    put("model", buildJsonObject { put("provider", "openai"); put("id", "gpt-5"); put("name", "GPT-5") })
                    put("thinkingLevel", "minimal")
                }

                "bot.state" -> buildJsonObject {
                    put("sessionId", "s1")
                    put("busy", false)
                    put("thinkingLevel", "xhigh")
                    put(
                        "usage",
                        buildJsonObject {
                            put("contextTokens", 320L)
                            put("contextWindow", 128000L)
                            put("inputTokens", 200L)
                            put("outputTokens", 120L)
                            put("cacheReadTokens", 0L)
                            put("cacheWriteTokens", 0L)
                            put("cost", 0.05)
                        },
                    )
                }

                "bot.commands" -> buildJsonObject {
                    put(
                        "commands",
                        buildJsonArray {
                            add(buildJsonObject { put("name", "compact"); put("description", "Compact"); put("source", "builtin") })
                        },
                    )
                }

                "bot.instructions.get" -> buildJsonObject { put("content", "Rules") }
                "bot.instructions.set" -> buildJsonObject { put("content", "Updated"); put("saved", true) }
                "bot.reload" -> buildJsonObject { put("reloaded", true); put("botId", "pi"); put("sessionId", "s1") }
                else -> buildJsonObject {}
            }
        }

        val models = (client.fetchModels(FakeRemoteTransport.DEVICE_ID, "pi") as RemoteControlResult.Ok).value
        assertEquals(2, models.models.size)
        assertEquals(2, models.providers.size)
        assertEquals("OpenAI", models.providers.first { it.id == "openai" }.name)
        assertEquals(listOf(ThinkingLevel.OFF), models.models.first { it.id == "gemini-flash" }.thinkingLevels)
        assertEquals(ThinkingLevel.LOW, models.currentThinkingLevel)

        val state = (client.fetchState(FakeRemoteTransport.DEVICE_ID, "pi", "s1") as RemoteControlResult.Ok).value
        assertEquals(ThinkingLevel.XHIGH, state.thinkingLevel)
        assertEquals(320L, state.usage?.contextTokens)

        val commands = (client.fetchCommands(FakeRemoteTransport.DEVICE_ID, "pi") as RemoteControlResult.Ok).value
        assertEquals("compact", commands.single().name)

        val readDoc = (client.readInstructions(FakeRemoteTransport.DEVICE_ID, "pi") as RemoteControlResult.Ok).value
        assertEquals("Rules", readDoc.content)

        val writeDoc = (client.writeInstructions(FakeRemoteTransport.DEVICE_ID, "pi", "Updated") as RemoteControlResult.Ok).value
        assertTrue(writeDoc.saved)

        val oversized = client.writeInstructions(FakeRemoteTransport.DEVICE_ID, "pi", "x".repeat(65_537))
        assertEquals("payload_too_large", (oversized as RemoteControlResult.Error).code)

        val reloaded = (client.reloadBot(FakeRemoteTransport.DEVICE_ID, "pi", "s1") as RemoteControlResult.Ok).value
        assertTrue(reloaded.reloaded)

        transport.failNext = RemoteCallError("conflict", "busy")
        val err = client.setThinkingLevel(FakeRemoteTransport.DEVICE_ID, "pi", ThinkingLevel.HIGH)
        assertEquals("conflict", (err as RemoteControlResult.Error).code)
    }

    @Test
    fun getsSetsAndGatesRemoteBotCapabilities() = runTest {
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteBotControlClient(transport)
        transport.result = { call ->
            when (call.method) {
                "bot.capabilities.get",
                "bot.capabilities.set", -> buildJsonObject {
                    put("botId", "research")
                    put("isHome", false)
                    put(
                        "policy",
                        call.params["policy"] ?: buildJsonObject {
                            put("allCapabilities", false)
                            put("skills", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("pdf")) })
                            put("plugins", buildJsonArray {})
                            put("mcpServers", buildJsonArray {})
                            put("screenTools", true)
                            put("miniappTools", false)
                            put("memoryScope", "PERSONAL")
                            put("memoryDispatch", false)
                        },
                    )
                    put(
                        "available",
                        buildJsonObject {
                            put("skills", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("pdf")) })
                            put(
                                "plugins",
                                buildJsonArray {
                                    add(buildJsonObject { put("id", "plg-1"); put("name", "Notes") })
                                },
                            )
                            put("mcpServers", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("github")) })
                            put("screenToolsReady", true)
                        },
                    )
                }
                else -> buildJsonObject {}
            }
        }

        val initial = (client.getCapabilities(FakeRemoteTransport.DEVICE_ID, "research") as RemoteControlResult.Ok).value
        assertEquals(setOf("pdf"), initial.policy.skills)
        assertEquals(true, initial.policy.screenTools)
        assertEquals(MemoryAccessScope.PERSONAL, initial.policy.memoryScope)
        assertEquals(false, initial.policy.memoryDispatch)
        assertEquals("Notes", initial.plugins.single().name)
        assertEquals(listOf("github"), initial.mcpServers)
        assertEquals(true, initial.screenToolsReady)

        val nextPolicy = WorkspaceToolPolicy(
            allCapabilities = false,
            skills = setOf("pdf"),
            plugins = setOf("plg-1"),
            mcpServers = setOf("github"),
            screenTools = false,
            miniappTools = true,
            memoryScope = MemoryAccessScope.WORK_ONLY,
            memoryDispatch = true,
        )
        val updated = (client.setCapabilities(FakeRemoteTransport.DEVICE_ID, "research", nextPolicy) as RemoteControlResult.Ok).value
        assertEquals(nextPolicy, updated.policy)

        val legacyClient = RemoteBotControlClient(
            FakeRemoteTransport(online = true, capabilities = listOf("remote-bot", "bot-controls")),
        )
        val unsupported = legacyClient.getCapabilities(FakeRemoteTransport.DEVICE_ID, "research")
        assertEquals(RemoteBotControlClient.CAPABILITY_UNAVAILABLE, (unsupported as RemoteControlResult.Error).code)
    }

    @Test
    fun parsesStrictMemoryScopeValuesAndRejectsUnknownAsNull() = runTest {
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteBotControlClient(transport)
        var wireScope = "OFF"
        transport.result = { call ->
            when (call.method) {
                "bot.capabilities.get" -> buildJsonObject {
                    put("botId", "research")
                    put("isHome", false)
                    put(
                        "policy",
                        buildJsonObject {
                            put("allCapabilities", false)
                            put("memoryScope", wireScope)
                            put("memoryDispatch", false)
                        },
                    )
                    put("available", buildJsonObject {})
                }
                else -> buildJsonObject {}
            }
        }

        wireScope = "personal"
        val personal = (client.getCapabilities(FakeRemoteTransport.DEVICE_ID, "research") as RemoteControlResult.Ok).value
        assertEquals(MemoryAccessScope.PERSONAL, personal.policy.memoryScope)

        wireScope = "work_only"
        val workOnly = (client.getCapabilities(FakeRemoteTransport.DEVICE_ID, "research") as RemoteControlResult.Ok).value
        assertEquals(MemoryAccessScope.WORK_ONLY, workOnly.policy.memoryScope)

        wireScope = "off"
        val off = (client.getCapabilities(FakeRemoteTransport.DEVICE_ID, "research") as RemoteControlResult.Ok).value
        assertEquals(MemoryAccessScope.OFF, off.policy.memoryScope)

        wireScope = "ALL"
        val unknown = (client.getCapabilities(FakeRemoteTransport.DEVICE_ID, "research") as RemoteControlResult.Ok).value
        assertEquals(null, unknown.policy.memoryScope)
    }
}
