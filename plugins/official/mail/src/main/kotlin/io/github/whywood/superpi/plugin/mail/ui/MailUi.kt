package io.github.whywood.superpi.plugin.mail.ui

import androidx.compose.runtime.Composable
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.PluginUiContributions
import io.github.whywood.superpi.plugin.api.SlotBinding
import io.github.whywood.superpi.plugin.api.ToolResultRenderer
import io.github.whywood.superpi.plugin.api.ToolResultRendererContributor
import io.github.whywood.superpi.plugin.api.ToolResultSource
import io.github.whywood.superpi.plugin.api.ToolResultState
import io.github.whywood.superpi.plugin.api.localizedText
import io.github.whywood.superpi.plugin.api.pluginPage
import io.github.whywood.superpi.plugin.api.toolResultMatch
import io.github.whywood.superpi.plugin.api.toolResultRenderer
import io.github.whywood.superpi.plugin.mail.MailRepository

internal fun mailUi(host: PluginHost, repository: MailRepository): PluginUiContributions =
    object : PluginUiContributions, ToolResultRendererContributor {
        override val bindings: List<SlotBinding> = emptyList()

        override val toolResultRenderers: List<ToolResultRenderer> = listOf(
            mailRenderer("mail-search", "mail_search") { state -> MailSearchChatCard(host = host, state = state) },
            mailRenderer("mail-draft", "mail_draft") { state ->
                MailDraftChatCard(host = host, repository = repository, state = state)
            },
            mailRenderer("mail-read", "mail_read") { state -> MailReadChatCard(host = host, state = state) },
        )

        override val pages = listOf(
            pluginPage(
                id = "mail",
                title = localizedText(default = "邮箱", zh = "邮箱", en = "Mail"),
                content = { MailPage(host, repository) },
            ),
        )
    }

private fun mailRenderer(
    id: String,
    tool: String,
    content: @Composable (ToolResultState) -> Unit,
): ToolResultRenderer = toolResultRenderer(
    id = id,
    match = toolResultMatch(source = ToolResultSource.PLUGIN, tool = tool),
    priority = 20,
    content = content,
)
