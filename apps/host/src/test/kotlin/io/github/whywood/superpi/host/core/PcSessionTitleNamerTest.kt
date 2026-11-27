package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.sdk.ai.auth.Credential
import io.github.whywood.superpi.sdk.ai.auth.CredentialInfo
import io.github.whywood.superpi.sdk.ai.auth.CredentialStore
import io.github.whywood.superpi.sdk.ai.http.HttpRequest
import io.github.whywood.superpi.sdk.ai.http.HttpResponse
import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.coding.models.CustomModelConfig
import io.github.whywood.superpi.sdk.coding.models.CustomProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcSessionTitleNamerTest {
    private fun makeCredentials(): CredentialStore = object : CredentialStore {
        override fun read(providerId: String): Credential? = null
        override fun readAll(): Map<String, Credential> = emptyMap()
        override fun readApiKey(providerId: String): String? = null
        override fun readEnv(providerId: String): Map<String, String>? = null
        override fun list(): List<CredentialInfo> = emptyList()
        override fun keys(): List<String> = emptyList()
        override fun modify(providerId: String, transform: (Credential?) -> Credential?): Credential? = null
        override fun writeApiKey(providerId: String, key: String, env: Map<String, String>?) = Unit
        override fun write(providerId: String, credential: Credential) = Unit
        override fun writeRawCredential(providerId: String, credential: JsonElement) = Unit
        override fun delete(providerId: String) = Unit
        override fun readRaw(): Map<String, JsonElement> = emptyMap()
    }

    private fun openAiSse(text: String): Buffer {
        val escaped = text.replace("\"", "\\\"")
        val payload = """{"choices":[{"index":0,"delta":{"role":"assistant","content":"$escaped"},"finish_reason":"stop"}]}"""
        return Buffer().writeUtf8("data: $payload\n\ndata: [DONE]\n\n")
    }

    @Test
    fun returnsNullWhenDisabledWithoutCallingModel(): Unit = runBlocking {
        var called = 0
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                called += 1
                error("must not execute when disabled")
            }
        }
        val catalog = ProviderCatalog(
            transport = transport,
            credentials = makeCredentials(),
            customProviders = listOf(
                CustomProviderConfig(
                    id = "custom-a",
                    name = "Custom A",
                    baseUrl = "https://example.invalid/v1",
                    api = "openai-completions",
                    apiKey = "sk-test",
                    models = listOf(CustomModelConfig(id = "custom-flash", name = "Custom Flash")),
                ),
            ),
        )
        val namer = PcSessionTitleNamer(
            catalog = catalog,
            configuredProviders = { setOf("custom-a") },
            isEnabled = { false },
        )
        assertNull(namer.title("帮我写个季度总结"))
        assertEquals(0, called)
    }

    @Test
    fun prefersConfiguredTitleModelOverHeuristic(): Unit = runBlocking {
        val requestedBodies = mutableListOf<String>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requestedBodies += request.body?.toString(Charsets.UTF_8).orEmpty()
                return HttpResponse(
                    status = 200,
                    headers = mapOf("content-type" to "text/event-stream"),
                    body = openAiSse("Quarterly Summary"),
                )
            }
        }
        val catalog = ProviderCatalog(
            transport = transport,
            credentials = makeCredentials(),
            customProviders = listOf(
                CustomProviderConfig(
                    id = "custom-a",
                    name = "Custom A",
                    baseUrl = "https://example.invalid/v1",
                    api = "openai-completions",
                    apiKey = "sk-test",
                    models = listOf(
                        CustomModelConfig(id = "model-flash", name = "Flash"),
                        CustomModelConfig(id = "model-pro", name = "Pro"),
                    ),
                ),
            ),
        )
        val namer = PcSessionTitleNamer(
            catalog = catalog,
            configuredProviders = { setOf("custom-a") },
            isEnabled = { true },
            preferredModelSpec = { "custom-a/model-pro" },
        )
        val title = namer.title("Write quarterly summary")
        assertEquals("Quarterly Summary", title)
        assertEquals(1, requestedBodies.size)
        assertTrue(requestedBodies.first().contains("model-pro"))
    }
}
