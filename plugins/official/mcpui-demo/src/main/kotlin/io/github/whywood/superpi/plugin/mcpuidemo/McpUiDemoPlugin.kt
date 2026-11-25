package io.github.whywood.superpi.plugin.mcpuidemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.api.SuperPIPlugin
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.PluginUiContributions
import io.github.whywood.superpi.plugin.api.ToolResultRendererContributor
import io.github.whywood.superpi.plugin.api.ToolResultSource
import io.github.whywood.superpi.plugin.api.available
import io.github.whywood.superpi.plugin.api.localizedText
import io.github.whywood.superpi.plugin.api.pluginPage
import io.github.whywood.superpi.plugin.api.toolResultMatch
import io.github.whywood.superpi.plugin.api.toolResultRenderer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpUiDemoPlugin : SuperPIPlugin {
    override suspend fun availability(host: PluginHost) = available()

    override fun ui(host: PluginHost): PluginUiContributions = object :
        PluginUiContributions,
        ToolResultRendererContributor {
        override val bindings = emptyList<io.github.whywood.superpi.plugin.api.SlotBinding>()

        override val pages = listOf(
            pluginPage(
                id = "demo",
                title = localizedText(default = "天气卡片", zh = "天气卡片", en = "Weather card"),
            ) {
                DemoPage(host)
            },
        )

        override val toolResultRenderers = listOf(
            toolResultRenderer(
                id = "demo-weather",
                match = toolResultMatch(
                    source = ToolResultSource.MCP,
                    server = "demo",
                    tool = "weather",
                ),
                priority = 10,
            ) { state ->
                WeatherCard(host, state.structuredContent as? JsonObject)
            },
        )
    }
}

@Composable
private fun DemoPage(host: PluginHost) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DdSpacing.Xl, vertical = DdSpacing.Lg),
    ) {
        WeatherCard(
            host = host,
            data = buildJsonObject {
                put("city", host.t("杭州", "Hangzhou"))
                put("temperature", 26)
                put("condition", host.t("晴", "Sunny"))
                put("high", 29)
                put("low", 20)
            },
        )
    }
}

@Composable
private fun WeatherCard(host: PluginHost, data: JsonObject?) {
    val colors = superPiColors()
    val city = data?.get("city")?.jsonPrimitive?.content ?: host.t("杭州", "Hangzhou")
    val temp = data?.get("temperature")?.jsonPrimitive?.intOrNull ?: 26
    val condition = data?.get("condition")?.jsonPrimitive?.content ?: host.t("晴", "Sunny")
    val high = data?.get("high")?.jsonPrimitive?.intOrNull
    val low = data?.get("low")?.jsonPrimitive?.intOrNull
    DdGroupCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
        ) {
            DdText(text = city, style = DdType.bodySmall, color = colors.inkSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = temp.toString(),
                    color = colors.ink,
                    style = DdType.heading,
                )
                DdText(
                    text = "°C  $condition",
                    modifier = Modifier.padding(start = DdSpacing.Sm),
                    style = DdType.body,
                    color = colors.ink,
                )
            }
            if (high != null && low != null) {
                DdText(
                    text = host.t("最高 $high° / 最低 $low°", "High $high° / Low $low°"),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
        }
    }
}

private fun PluginHost.t(zh: String, en: String): String =
    text(localizedText(default = zh, zh = zh, en = en))
