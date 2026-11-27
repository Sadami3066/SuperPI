package io.github.whywood.superpi.ui.workspaces

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.AgentDocStore
import io.github.whywood.superpi.data.SkillRepository
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.WorkspaceToolPolicyStore
import io.github.whywood.superpi.design.DdDropdown
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSheetHeader
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdRowHorizontalPadding
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.manager.PluginManagerFacade
import io.github.whywood.superpi.runtime.McpConfigStore
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.resolveThinkingLevel
import io.github.whywood.superpi.ui.common.AvatarCropDialog
import io.github.whywood.superpi.ui.common.BotAvatar
import io.github.whywood.superpi.ui.common.chat.ThinkingEffortSlider
import io.github.whywood.superpi.ui.common.chat.thinkingLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkspaceSettingsScreen(
    workspace: RuntimeWorkspace,
    skillRepository: SkillRepository,
    agentDocStore: AgentDocStore,
    workspaceToolPolicies: WorkspaceToolPolicyStore,
    pluginManager: PluginManagerFacade,
    mcpConfigStore: McpConfigStore,
    providerCatalog: ProviderDirectory? = null,
    configuredProviderIds: Set<String> = emptySet(),
    globalModelConfig: ModelConfig = ModelConfig(),
    existingNames: List<String>,
    screenToolsReady: Boolean,
    onRename: (String) -> Unit,
    onAvatarPicked: (Bitmap?) -> Unit,
    onIntroChange: (String) -> Unit,
    onUpdateDefaultModel: (ModelConfig?) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val scope = rememberCoroutineScope()
    var reloadKey by remember(workspace.id) { mutableStateOf(0) }
    var showAgentsDoc by rememberSaveable(workspace.id) { mutableStateOf(false) }
    var showAvatarSheet by rememberSaveable(workspace.id) { mutableStateOf(false) }
    var cropSource by rememberSaveable(workspace.id) { mutableStateOf<String?>(null) }
    var showIntroDialog by rememberSaveable(workspace.id) { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(workspace.id) { mutableStateOf(false) }
    var showDefaultModelSheet by rememberSaveable(workspace.id) { mutableStateOf(false) }
    var capabilityKind by rememberSaveable(workspace.id) { mutableStateOf<WorkspaceCapabilityKind?>(null) }
    var agentsDocSet by remember(workspace.id) { mutableStateOf<Boolean?>(null) }
    var policy by remember(workspace.id) {
        mutableStateOf(WorkspaceToolPolicy(allCapabilities = workspace.isHome))
    }
    var policyLoaded by remember(workspace.id) { mutableStateOf(false) }
    var skillCount by remember(workspace.id) { mutableStateOf<Int?>(null) }
    var pluginCount by remember(workspace.id) { mutableStateOf<Int?>(null) }
    var mcpCount by remember(workspace.id) { mutableStateOf<Int?>(null) }
    var skillsLoaded by remember(workspace.id) { mutableStateOf(false) }
    var pluginsLoaded by remember(workspace.id) { mutableStateOf(false) }
    var mcpLoaded by remember(workspace.id) { mutableStateOf(false) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cropSource = uri.toString()
    }

    LaunchedEffect(workspace.id, reloadKey) {
        val loaded = withContext(Dispatchers.IO) {
            val current = effectivePolicy(workspace, workspaceToolPolicies)
            val skills = skillRepository.list().getOrNull().orEmpty()
            val plugins = pluginManager.state.value.plugins.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
            val mcpServers = runCatching { mcpConfigStore.load().servers.keys }.getOrDefault(emptySet())
            SettingsLoad(
                agentsDocSet = agentDocStore.read(workspace.cwd) != null,
                policy = current,
                skillCount = current.enabledSkills(skills.mapTo(mutableSetOf()) { it.name }).size,
                pluginCount = current.enabledPlugins(plugins).size,
                mcpCount = current.enabledMcpServers(mcpServers).size,
            )
        }
        policy = loaded.policy
        policyLoaded = true
        agentsDocSet = loaded.agentsDocSet
        skillCount = loaded.skillCount
        pluginCount = loaded.pluginCount
        mcpCount = loaded.mcpCount
        skillsLoaded = true
        pluginsLoaded = true
        mcpLoaded = true
    }

    fun updatePolicy(block: (WorkspaceToolPolicy) -> WorkspaceToolPolicy) {
        if (!policyLoaded) return
        val next = block(policy)
        policy = next
        scope.launch { workspaceToolPolicies.update(workspace.cwd, next) }
    }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.workspace_settings_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
        },
        modifier = modifier.testTag("workspace_settings"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            DdSectionLabel(stringResource(R.string.workspace_settings_label))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(workspace = workspace, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = workspace.name,
                        style = DdType.pageTitle,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val intro = workspace.intro?.trim().orEmpty()
                    if (intro.isNotEmpty()) {
                        Text(
                            text = intro,
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (workspace.isHome) {
                        Text(
                            text = stringResource(R.string.bot_default_badge),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            DdSectionLabel(stringResource(R.string.bot_identity))
            Spacer(Modifier.height(8.dp))
            DdGroupCard {
                DdListRow(
                    icon = DdIcons.Image,
                    title = stringResource(R.string.bot_avatar),
                    value = workspace.avatar
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.bot_avatar_change) },
                    onClick = { showAvatarSheet = true },
                    showChevron = true,
                    modifier = Modifier.testTag("bot_settings_avatar"),
                )
                DdRowDivider(leadingInset = 50.dp)
                DdListRow(
                    icon = DdIcons.Pen,
                    title = stringResource(R.string.workspace_name_placeholder),
                    value = workspace.name,
                    onClick = { showRenameDialog = true },
                    showChevron = true,
                    modifier = Modifier.testTag("bot_settings_name"),
                )
                DdRowDivider(leadingInset = 50.dp)
                DdListRow(
                    icon = DdIcons.Info,
                    title = stringResource(R.string.bot_intro),
                    value = workspace.intro?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.bot_intro_placeholder),
                    onClick = { showIntroDialog = true },
                    showChevron = true,
                    modifier = Modifier.testTag("bot_settings_intro"),
                )
            }
            Spacer(Modifier.height(20.dp))
            DdSectionLabel(stringResource(R.string.bot_capabilities))
            Spacer(Modifier.height(8.dp))
            DdGroupCard {
                DdListRow(
                    icon = DdIcons.Sparkles,
                    title = stringResource(R.string.bot_capabilities_skills),
                    value = capabilityValue(skillCount, skillsLoaded),
                    onClick = { capabilityKind = WorkspaceCapabilityKind.Skills },
                    showChevron = true,
                    modifier = Modifier.testTag("bot_capabilities_skills"),
                )
                DdRowDivider(leadingInset = 50.dp)
                DdListRow(
                    icon = DdIcons.Puzzle,
                    title = stringResource(R.string.bot_capabilities_plugins),
                    value = capabilityValue(pluginCount, pluginsLoaded),
                    onClick = { capabilityKind = WorkspaceCapabilityKind.Plugins },
                    showChevron = true,
                    modifier = Modifier.testTag("bot_capabilities_plugins"),
                )
                DdRowDivider(leadingInset = 50.dp)
                DdListRow(
                    icon = DdIcons.Plug,
                    title = stringResource(R.string.bot_capabilities_mcp),
                    value = capabilityValue(mcpCount, mcpLoaded),
                    onClick = { capabilityKind = WorkspaceCapabilityKind.Mcp },
                    showChevron = true,
                    modifier = Modifier.testTag("bot_capabilities_mcp"),
                )
            }
            Spacer(Modifier.height(20.dp))
            DdSectionLabel(stringResource(R.string.bot_tools))
            Spacer(Modifier.height(8.dp))
            DdGroupCard {
                DdListRow(
                    icon = DdIcons.Desktop,
                    title = stringResource(R.string.bot_tools_screen),
                    enabled = policyLoaded,
                    onClick = { updatePolicy { it.copy(screenTools = !it.screenTools) } },
                    modifier = Modifier.testTag("bot_tools_screen"),
                    trailing = {
                        DdSwitch(
                            checked = policy.screenTools,
                            onCheckedChange = { checked -> updatePolicy { it.copy(screenTools = checked) } },
                            enabled = policyLoaded,
                        )
                    },
                )
                if (policyLoaded && policy.screenTools && !screenToolsReady) {
                    Text(
                        text = stringResource(R.string.bot_tools_screen_permission),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = DdType.bodySmall,
                        color = colors.danger,
                    )
                }
                DdRowDivider(leadingInset = 50.dp)
                DdListRow(
                    icon = DdIcons.Apps,
                    title = stringResource(R.string.bot_tools_miniapp),
                    enabled = policyLoaded,
                    onClick = { updatePolicy { it.copy(miniappTools = !it.miniappTools) } },
                    modifier = Modifier.testTag("bot_tools_miniapp"),
                    trailing = {
                        DdSwitch(
                            checked = policy.miniappTools,
                            onCheckedChange = { checked -> updatePolicy { it.copy(miniappTools = checked) } },
                            enabled = policyLoaded,
                        )
                    },
                )
            }
            Spacer(Modifier.height(20.dp))
            BotMemoryPolicySection(
                policy = policy,
                isHome = workspace.isHome,
                enabled = policyLoaded,
                useCardContainer = true,
                tagPrefix = "bot_memory",
                onSelectScope = { scope -> updatePolicy { it.copy(memoryScope = scope) } },
                onToggleDispatch = { checked -> updatePolicy { it.copy(memoryDispatch = checked) } },
            )
            Spacer(Modifier.height(20.dp))
            DdGroupCard {
                DdListRow(
                    icon = DdIcons.File,
                    title = stringResource(R.string.bot_instructions),
                    value = when (val set = agentsDocSet) {
                        null -> stringResource(R.string.workspace_settings_loading)
                        true -> stringResource(R.string.workspace_settings_agents_set)
                        else -> stringResource(R.string.workspace_settings_agents_unset)
                    },
                    onClick = { showAgentsDoc = true },
                    showChevron = true,
                    modifier = Modifier.testTag("bot_settings_agents"),
                )
            }
            if (providerCatalog != null) {
                Spacer(Modifier.height(20.dp))
                DdSectionLabel(stringResource(R.string.bot_model_section))
                Spacer(Modifier.height(8.dp))
                DdGroupCard {
                    DdListRow(
                        icon = DdIcons.Cloud,
                        title = stringResource(R.string.bot_default_model),
                        value = botDefaultModelLabel(workspace.defaultModel, providerCatalog),
                        onClick = { showDefaultModelSheet = true },
                        showChevron = true,
                        modifier = Modifier.testTag("bot_settings_default_model"),
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showAgentsDoc) {
        AgentsDocSheet(
            workspace = workspace,
            agentDocStore = agentDocStore,
            onSaved = { reloadKey += 1 },
            onDismiss = { showAgentsDoc = false },
        )
    }

    capabilityKind?.let { kind ->
        WorkspaceCapabilitySheet(
            workspace = workspace,
            kind = kind,
            store = workspaceToolPolicies,
            skillRepository = skillRepository,
            pluginManager = pluginManager,
            mcpConfigStore = mcpConfigStore,
            onDismiss = {
                capabilityKind = null
                reloadKey += 1
            },
        )
    }

    cropSource?.let { raw ->
        AvatarCropDialog(
            source = android.net.Uri.parse(raw),
            onDismiss = { cropSource = null },
            onCropped = {
                cropSource = null
                onAvatarPicked(it)
            },
        )
    }

    if (showAvatarSheet) {
        BotAvatarSheet(
            hasCustomAvatar = !workspace.avatar.isNullOrBlank(),
            onChooseImage = {
                showAvatarSheet = false
                pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onUseDefault = {
                showAvatarSheet = false
                onAvatarPicked(null)
            },
            onDismiss = { showAvatarSheet = false },
        )
    }

    if (showIntroDialog) {
        BotIntroDialog(
            initial = workspace.intro.orEmpty(),
            onConfirm = { text ->
                showIntroDialog = false
                onIntroChange(text)
            },
            onDismiss = { showIntroDialog = false },
        )
    }

    if (showRenameDialog) {
        WorkspaceNameDialog(
            initial = workspace.name,
            existingNames = existingNames,
            onConfirm = { name ->
                showRenameDialog = false
                onRename(name)
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDefaultModelSheet && providerCatalog != null) {
        BotDefaultModelSheet(
            current = workspace.defaultModel,
            globalDefault = globalModelConfig,
            providerCatalog = providerCatalog,
            configuredProviderIds = configuredProviderIds,
            onSelect = onUpdateDefaultModel,
            onDismiss = { showDefaultModelSheet = false },
        )
    }
}

@Composable
private fun botDefaultModelLabel(
    model: ModelConfig?,
    providerCatalog: ProviderDirectory,
): String {
    if (model == null) return stringResource(R.string.bot_default_model_global)
    val modelName = providerCatalog.models(model.providerId).firstOrNull { it.id == model.modelId }?.displayName
        ?: model.modelId
    return if (model.thinkingLevel == ThinkingLevel.OFF) {
        modelName
    } else {
        "$modelName · ${thinkingLabel(model.thinkingLevel)}"
    }
}

@Composable
private fun BotDefaultModelSheet(
    current: ModelConfig?,
    globalDefault: ModelConfig,
    providerCatalog: ProviderDirectory,
    configuredProviderIds: Set<String>,
    onSelect: (ModelConfig?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    val providers = remember(providerCatalog, configuredProviderIds) {
        providerCatalog.summaries.filter { it.id in configuredProviderIds }
    }
    var selectedProviderId by remember(current, globalDefault, providers) {
        mutableStateOf(
            current?.providerId?.takeIf { id -> providers.any { it.id == id } }
                ?: globalDefault.providerId.takeIf { id -> providers.any { it.id == id } }
                ?: providers.firstOrNull()?.id.orEmpty(),
        )
    }
    val models = remember(providerCatalog, selectedProviderId) {
        if (selectedProviderId.isBlank()) emptyList() else providerCatalog.models(selectedProviderId)
    }
    val activeModelId = current?.takeIf { it.providerId == selectedProviderId }?.modelId
        ?: globalDefault.takeIf { it.providerId == selectedProviderId }?.modelId
        ?: models.firstOrNull()?.id.orEmpty()
    val activeOption = models.firstOrNull { it.id == activeModelId } ?: models.firstOrNull()
    val thinkingLevels = activeOption?.thinkingLevels.orEmpty().ifEmpty { listOf(ThinkingLevel.OFF) }
    var selectedThinking by remember(current, activeOption) {
        mutableStateOf(thinkingLevels.resolveThinkingLevel(current?.thinkingLevel ?: globalDefault.thinkingLevel))
    }
    var showProviders by remember { mutableStateOf(false) }

    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.bot_default_model),
            onClose = onDismiss,
        )
        CompositionLocalProvider(LocalDdRowHorizontalPadding provides DdSpacing.Md) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DdSpacing.Md),
            ) {
            Spacer(Modifier.height(DdSpacing.Sm))
            val globalLabel = providerCatalog.models(globalDefault.providerId)
                .firstOrNull { it.id == globalDefault.modelId }?.displayName ?: globalDefault.modelId
            DdListRow(
                title = stringResource(R.string.bot_default_model_global),
                subtitle = globalLabel,
                onClick = {
                    onSelect(null)
                    onDismiss()
                },
                trailing = {
                    if (current == null) {
                        DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 20.dp)
                    }
                },
                modifier = Modifier.testTag("bot_default_model_global"),
            )
            DdRowDivider()
            if (providers.isEmpty()) {
                DdListRow(
                    title = stringResource(R.string.assistant_model_unconfigured),
                    onClick = null,
                    enabled = false,
                )
            } else {
                val providerName = providers.firstOrNull { it.id == selectedProviderId }?.name ?: selectedProviderId
                DdDropdown(
                    expanded = showProviders,
                    onDismissRequest = { showProviders = false },
                    anchor = {
                        DdListRow(
                            title = stringResource(R.string.assistant_model_provider),
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = providerName,
                                        modifier = Modifier.widthIn(max = 160.dp),
                                        style = DdType.bodySmall,
                                        color = colors.inkTertiary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    DdIcon(icon = DdIcons.ChevronDown, tint = colors.inkTertiary, size = 16.dp)
                                }
                            },
                            onClick = { showProviders = true },
                        )
                    },
                ) {
                    providers.forEach { provider ->
                        DdMenuItem(
                            text = provider.name,
                            onClick = {
                                showProviders = false
                                selectedProviderId = provider.id
                            },
                            selected = provider.id == selectedProviderId,
                            trailing = if (provider.id == selectedProviderId) {
                                { DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 16.dp) }
                            } else {
                                null
                            },
                        )
                    }
                }
                DdRowDivider()
                if (thinkingLevels.any { it != ThinkingLevel.OFF }) {
                    ThinkingEffortSlider(
                        levels = thinkingLevels,
                        value = selectedThinking,
                        onChange = { level ->
                            selectedThinking = level
                            if (current != null && current.providerId == selectedProviderId) {
                                onSelect(current.copy(thinkingLevel = level))
                            }
                        },
                    )
                    DdRowDivider()
                }
                if (models.isEmpty()) {
                    DdListRow(
                        title = stringResource(R.string.assistant_remote_models_empty),
                        onClick = null,
                        enabled = false,
                    )
                } else {
                    models.forEachIndexed { index, option ->
                        val selected = current != null &&
                            current.providerId == selectedProviderId &&
                            current.modelId == option.id
                        DdListRow(
                            title = option.displayName,
                            onClick = {
                                val resolvedThinking = option.thinkingLevels.resolveThinkingLevel(selectedThinking)
                                onSelect(ModelConfig(selectedProviderId, option.id, resolvedThinking))
                                onDismiss()
                            },
                            trailing = {
                                if (selected) {
                                    DdIcon(icon = DdIcons.Check, tint = colors.accent, size = 20.dp)
                                }
                            },
                        )
                        if (index != models.lastIndex) DdRowDivider()
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            }
        }
        Spacer(Modifier.height(DdSpacing.Xl))
    }
}

private data class SettingsLoad(
    val agentsDocSet: Boolean,
    val policy: WorkspaceToolPolicy,
    val skillCount: Int,
    val pluginCount: Int,
    val mcpCount: Int,
)

private fun effectivePolicy(workspace: RuntimeWorkspace, store: WorkspaceToolPolicyStore): WorkspaceToolPolicy {
    val stored = store.policyForCwd(workspace.cwd)
    return if (workspace.isHome) stored.copy(allCapabilities = true) else stored
}

@Composable
private fun capabilityValue(count: Int?, loaded: Boolean): String {
    if (!loaded || count == null) return stringResource(R.string.workspace_settings_loading)
    return if (count == 0) {
        stringResource(R.string.bot_capabilities_none)
    } else {
        stringResource(R.string.bot_capabilities_count, count)
    }
}
