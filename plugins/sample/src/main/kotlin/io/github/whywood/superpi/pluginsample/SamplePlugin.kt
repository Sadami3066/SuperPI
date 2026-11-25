package io.github.whywood.superpi.pluginsample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.api.Contributions
import io.github.whywood.superpi.plugin.api.SuperPIPlugin
import io.github.whywood.superpi.plugin.api.PluginAvailability
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.PluginPage
import io.github.whywood.superpi.plugin.api.PluginUiContributions
import io.github.whywood.superpi.plugin.api.SlotBinding
import io.github.whywood.superpi.plugin.api.assetIcon
import io.github.whywood.superpi.plugin.api.available
import io.github.whywood.superpi.plugin.api.contributions
import io.github.whywood.superpi.plugin.api.localizedText
import io.github.whywood.superpi.plugin.api.pluginPage
import io.github.whywood.superpi.plugin.api.pluginTool
import io.github.whywood.superpi.plugin.api.textResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class SamplePlugin : SuperPIPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override fun contributions(host: PluginHost): Contributions =
        contributions(tools = listOf(echoTool, stateTool(host)))

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = PAGE_ID,
                title = localizedText(
                    mapOf("default" to "Sample page", "zh" to "示例页面", "en" to "Sample page"),
                ),
                icon = assetIcon(ICON_ASSET),
                content = { SamplePage(host) },
            ),
        )
    }

    private val echoTool = pluginTool(
        name = "sample_echo",
        label = "sample_echo",
        description = "Echo the given text with an `echo:` prefix. Used by the host end-to-end tests.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to echo back.")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("text"))
            }
        },
        promptSnippet = "sample_echo echoes text back",
    ) { call ->
        val text = call.args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        textResult("echo: $text")
    }

    private fun stateTool(host: PluginHost) = pluginTool(
        name = "sample_state",
        label = "sample_state",
        description = "Read or write a value in the host settings store. Demonstrates host.settings().",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "get or set")
                }
                putJsonObject("value") {
                    put("type", "string")
                    put("description", "Value stored when action is set.")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("action"))
            }
        },
    ) { call ->
        when (val action = call.args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
            "set" -> {
                val value = call.args["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                host.settings().put(STATE_KEY, value)
                textResult("stored: $value")
            }

            "get" -> {
                val value = host.settings().get(STATE_KEY)
                textResult(value?.let { "value: $it" } ?: "value: none")
            }

            else -> textResult("action must be get or set", isError = true)
        }
    }

    private companion object {
        const val PAGE_ID = "sample.main"
        const val ICON_ASSET = "icon.png"
        const val STATE_KEY = "sample_state"
    }
}

@Composable
private fun SamplePage(host: PluginHost) {
    val colors = superPiColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DdSpacing.ScreenHorizontal, vertical = DdSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
    ) {
        DdText(
            text = host.text(
                localizedText(default = "Sample page", zh = "示例页面", en = "Sample page"),
            ),
            style = DdType.heading,
            color = colors.ink,
        )
        DdText(
            text = host.text(
                localizedText(
                    default = "The sample plugin is active. This page confirms that plugin pages open correctly.",
                    zh = "示例插件已启用，这个页面用来确认插件页面可以正常打开。",
                    en = "The sample plugin is active. This page confirms that plugin pages open correctly.",
                ),
            ),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
        )
    }
}
