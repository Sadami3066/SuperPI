package io.github.whywood.superpi.ui.navigation

import androidx.compose.ui.graphics.ImageBitmap
import io.github.whywood.superpi.conversation.ConversationStrings
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.data.AppLanguage
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_DEFAULT
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.design.DdFontStyle
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope

internal data class ChatRouteData(
    val conversationId: String?,
    val workspaces: List<RuntimeWorkspace>,
    val archived: List<ConversationSummary>,
    val archivedCount: Int,
)

internal data class ShellRouteUi(
    val themeLabel: String,
    val fontStyle: DdFontStyle,
    val onFontStyleChange: (DdFontStyle) -> Unit,
    val nickname: String,
    val avatar: ImageBitmap?,
    val providerName: String?,
    val modelLabel: String?,
    val modelConfig: ModelConfig,
    val configuredProviderIds: Set<String>,
    val processDetail: ProcessDetail = ProcessDetail.COMPACT,
    val chatFontScale: Float = 1f,
    val voiceLanguage: AppLanguage = AppLanguage.SYSTEM,
    val appMode: AppMode = AppMode.NORMAL,
    val compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
    val workerCompactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
    val conversationStrings: ConversationStrings = ConversationStrings.fallback(),
)

internal class AppRouteHost(
    val navigator: AppNavigator,
    val dependencies: AppDependencies,
    val scope: CoroutineScope,
    val chatViewModel: ChatViewModel,
    val chat: ChatRouteData,
    val ui: ShellRouteUi,
    val onOpenChat: () -> Unit,
    val onOpenMiniappCreate: () -> Unit,
    val onOpenFile: (String) -> Unit,
    val onOpenAssistantSettings: () -> Unit,
    val pluginImportUri: String? = null,
    val onPluginImportHandled: () -> Unit = {},
) {
    fun back() {
        navigator.pop()
    }
}
