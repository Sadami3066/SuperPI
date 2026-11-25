package io.github.whywood.superpi.ui.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.design.DdListRow
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSheetHeader
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.remote.RemoteBotCapabilitiesSnapshot
import io.github.whywood.superpi.remote.RemoteSyncEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

@Composable
fun RemoteBotCapabilitiesSheet(
    deviceId: String,
    botId: String,
    botName: String,
    deviceName: String,
    syncEvents: SharedFlow<RemoteSyncEvent>,
    loadCapabilities: suspend () -> Result<RemoteBotCapabilitiesSnapshot>,
    saveCapabilities: suspend (WorkspaceToolPolicy) -> Result<RemoteBotCapabilitiesSnapshot>,
    onDismiss: () -> Unit,
) {
    val colors = superPiColors()
    val scope = rememberCoroutineScope()
    var loadEpoch by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<RemoteBotCapabilitiesSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val loadFailedFallback = stringResource(R.string.remote_capabilities_load_failed)
    val saveFailedFallback = stringResource(R.string.remote_capabilities_save_failed)

    LaunchedEffect(deviceId, botId, loadEpoch) {
        if (snapshot == null) loading = true
        loadError = null
        loadCapabilities().fold(
            onSuccess = { loaded ->
                snapshot = loaded
                loading = false
            },
            onFailure = { error ->
                loadError = error.message?.takeIf { it.isNotBlank() } ?: loadFailedFallback
                loading = false
            },
        )
    }

    LaunchedEffect(syncEvents, deviceId, botId) {
        syncEvents.collect { event ->
            if (event.kind == "capabilities_changed" &&
                event.deviceId == deviceId &&
                event.botId == botId &&
                !saving
            ) {
                loadEpoch += 1
            }
        }
    }

    fun applyPolicyUpdate(next: WorkspaceToolPolicy) {
        if (saving) return
        val previous = snapshot ?: return
        saving = true
        saveError = null
        snapshot = previous.copy(policy = next)
        scope.launch {
            saveCapabilities(next).fold(
                onSuccess = { updated ->
                    snapshot = updated
                    saving = false
                },
                onFailure = { error ->
                    snapshot = previous
                    saving = false
                    saveError = error.message?.takeIf { it.isNotBlank() } ?: saveFailedFallback
                },
            )
        }
    }

    DdSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .testTag("remote_capabilities_sheet"),
        ) {
            DdSheetHeader(
                title = stringResource(R.string.bot_capabilities),
                onClose = onDismiss,
            )
            if (loading && snapshot == null) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    DdSpinner(size = 22.dp, strokeWidth = 2.dp)
                }
            } else if (loadError != null && snapshot == null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = loadError.orEmpty(),
                        style = DdType.bodySmall,
                        color = colors.danger,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    DdTextAction(
                        text = stringResource(R.string.drawer_remote_retry),
                        onClick = { loadEpoch += 1 },
                    )
                    Spacer(Modifier.weight(1f))
                }
            } else {
                val active = snapshot ?: return@Column
                val policy = active.policy
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "$deviceName · $botName",
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                        )
                        if (saving) {
                            DdSpinner(size = 14.dp, strokeWidth = 1.5.dp)
                        }
                    }
                    if (saveError != null) {
                        Spacer(Modifier.height(DdSpacing.Sm))
                        Text(
                            text = saveError.orEmpty(),
                            style = DdType.bodySmall,
                            color = colors.danger,
                            modifier = Modifier.testTag("remote_capabilities_save_error"),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    val installedSkills = active.skills.toSet()
                    val enabledSkills = policy.enabledSkills(installedSkills)
                    CapabilityToggleSection(
                        title = stringResource(R.string.bot_capabilities_skills),
                        items = active.skills.map { it to it },
                        enabledIds = enabledSkills,
                        emptyText = stringResource(R.string.bot_capabilities_empty_skills),
                        saving = saving,
                        onToggle = { skill ->
                            val nextEnabled = if (skill in enabledSkills) enabledSkills - skill else enabledSkills + skill
                            val storedSelection = if (policy.allCapabilities) installedSkills - nextEnabled else nextEnabled
                            applyPolicyUpdate(policy.copy(skills = storedSelection))
                        },
                    )
                    Spacer(Modifier.height(18.dp))
                    val installedPlugins = active.plugins.mapTo(LinkedHashSet()) { it.id }
                    val enabledPlugins = policy.enabledPlugins(installedPlugins)
                    CapabilityToggleSection(
                        title = stringResource(R.string.bot_capabilities_plugins),
                        items = active.plugins.map { it.id to it.name },
                        enabledIds = enabledPlugins,
                        emptyText = stringResource(R.string.bot_capabilities_empty_plugins),
                        saving = saving,
                        onToggle = { pluginId ->
                            val nextEnabled = if (pluginId in enabledPlugins) enabledPlugins - pluginId else enabledPlugins + pluginId
                            val storedSelection = if (policy.allCapabilities) installedPlugins - nextEnabled else nextEnabled
                            applyPolicyUpdate(policy.copy(plugins = storedSelection))
                        },
                    )
                    Spacer(Modifier.height(18.dp))
                    val installedMcp = active.mcpServers.toSet()
                    val enabledMcp = policy.enabledMcpServers(installedMcp)
                    CapabilityToggleSection(
                        title = stringResource(R.string.bot_capabilities_mcp),
                        items = active.mcpServers.map { it to it },
                        enabledIds = enabledMcp,
                        emptyText = stringResource(R.string.bot_capabilities_empty_mcp),
                        saving = saving,
                        onToggle = { server ->
                            val nextEnabled = if (server in enabledMcp) enabledMcp - server else enabledMcp + server
                            val storedSelection = if (policy.allCapabilities) installedMcp - nextEnabled else nextEnabled
                            applyPolicyUpdate(policy.copy(mcpServers = storedSelection))
                        },
                    )
                    Spacer(Modifier.height(18.dp))
                    DdSectionLabel(stringResource(R.string.bot_tools))
                    Spacer(Modifier.height(6.dp))
                    DdListRow(
                        title = stringResource(R.string.bot_tools_screen),
                        subtitle = if (policy.screenTools && !active.screenToolsReady) {
                            stringResource(R.string.bot_tools_screen_permission)
                        } else {
                            null
                        },
                        enabled = !saving,
                        trailing = {
                            DdSwitch(
                                checked = policy.screenTools,
                                enabled = !saving,
                                onCheckedChange = { checked ->
                                    applyPolicyUpdate(policy.copy(screenTools = checked))
                                },
                            )
                        },
                        onClick = { applyPolicyUpdate(policy.copy(screenTools = !policy.screenTools)) },
                    )
                    DdRowDivider()
                    DdListRow(
                        title = stringResource(R.string.bot_tools_miniapp),
                        enabled = !saving,
                        trailing = {
                            DdSwitch(
                                checked = policy.miniappTools,
                                enabled = !saving,
                                onCheckedChange = { checked ->
                                    applyPolicyUpdate(policy.copy(miniappTools = checked))
                                },
                            )
                        },
                        onClick = { applyPolicyUpdate(policy.copy(miniappTools = !policy.miniappTools)) },
                    )
                    Spacer(Modifier.height(18.dp))
                    BotMemoryPolicySection(
                        policy = policy,
                        isHome = active.isHome,
                        enabled = !saving,
                        useCardContainer = false,
                        tagPrefix = "remote_memory",
                        onSelectScope = { scope -> applyPolicyUpdate(policy.copy(memoryScope = scope)) },
                        onToggleDispatch = { checked -> applyPolicyUpdate(policy.copy(memoryDispatch = checked)) },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun CapabilityToggleSection(
    title: String,
    items: List<Pair<String, String>>,
    enabledIds: Set<String>,
    emptyText: String,
    saving: Boolean,
    onToggle: (String) -> Unit,
) {
    val colors = superPiColors()
    DdSectionLabel(title)
    Spacer(Modifier.height(6.dp))
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    items.forEachIndexed { index, (id, label) ->
        if (index > 0) DdRowDivider()
        val checked = id in enabledIds
        DdListRow(
            title = label,
            enabled = !saving,
            trailing = {
                DdSwitch(
                    checked = checked,
                    enabled = !saving,
                    onCheckedChange = { onToggle(id) },
                )
            },
            onClick = { onToggle(id) },
        )
    }
}
