package io.github.whywood.superpi.ui.superagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdNoticeBar
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSectionLabel
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdSwitch
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.ui.common.BotAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SuperTeamScreen(
    superRuntime: SuperRuntime,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val scope = rememberCoroutineScope()
    val blueprints by superRuntime.blueprints.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    val refreshFailedMessage = stringResource(R.string.super_team_refresh_failed)

    suspend fun refreshBlueprintsNow() {
        if (refreshing) return
        refreshing = true
        try {
            superRuntime.refreshBlueprints()
            refreshError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            refreshError = err.message?.takeIf { it.isNotBlank() } ?: refreshFailedMessage
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        refreshBlueprintsNow()
    }

    val localBots = remember(blueprints) { blueprints.filter { !it.isRemote } }
    val remoteGroups = remember(blueprints) {
        blueprints.filter { it.isRemote }
            .groupBy { it.deviceId.orEmpty() }
            .entries
            .toList()
    }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.super_team_back),
                modifier = Modifier.testTag("super_team_back"),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            Text(
                text = stringResource(R.string.super_team_title),
                modifier = Modifier.weight(1f),
                style = DdType.heading,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (refreshing) {
                Box(
                    modifier = Modifier.padding(end = DdSpacing.Md),
                    contentAlignment = Alignment.Center,
                ) {
                    DdSpinner(size = 18.dp, strokeWidth = 2.dp, color = colors.inkTertiary)
                }
            } else {
                DdIconButton(
                    icon = DdIcons.Refresh,
                    onClick = { scope.launch { refreshBlueprintsNow() } },
                    contentDescription = stringResource(R.string.super_team_refresh),
                    modifier = Modifier.testTag("super_team_refresh"),
                    tint = colors.inkSecondary,
                    iconSize = 20.dp,
                )
            }
        },
        modifier = modifier.testTag("super_team_screen"),
    ) {
        refreshError?.let { err ->
            DdNoticeBar(
                text = err,
                tone = DdNoticeTone.DANGER,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.ScreenHorizontal, vertical = DdSpacing.Xs),
                actionLabel = stringResource(R.string.super_team_refresh),
                onAction = { scope.launch { refreshBlueprintsNow() } },
            )
        }
        if (blueprints.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                DdEmptyState(
                    text = stringResource(R.string.super_team_empty),
                    icon = DdIcons.Sparkles,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = DdSpacing.ScreenHorizontal,
                    vertical = DdSpacing.Md,
                ),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xl),
            ) {
                if (localBots.isNotEmpty()) {
                    item(key = "local_group") {
                        TeamDeviceGroup(
                            title = stringResource(R.string.super_team_device_local),
                            online = true,
                            bots = localBots,
                            onToggle = { ref, enabled ->
                                scope.launch { superRuntime.setBotEnabledInSuper(ref, enabled) }
                            },
                        )
                    }
                }
                remoteGroups.forEach { (deviceId, bots) ->
                    item(key = "remote_group_$deviceId") {
                        val first = bots.first()
                        TeamDeviceGroup(
                            title = first.deviceName.ifBlank { deviceId },
                            online = bots.any { it.online },
                            bots = bots,
                            onToggle = { ref, enabled ->
                                scope.launch { superRuntime.setBotEnabledInSuper(ref, enabled) }
                            },
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(DdSpacing.Xl))
                }
            }
        }
    }
}

@Composable
private fun TeamDeviceGroup(
    title: String,
    online: Boolean,
    bots: List<BotBlueprint>,
    onToggle: (String, Boolean) -> Unit,
) {
    val header = if (online) {
        title
    } else {
        "$title · ${stringResource(R.string.super_team_device_offline)}"
    }
    Column(Modifier.fillMaxWidth()) {
        DdSectionLabel(
            text = header,
            modifier = Modifier.padding(start = DdSpacing.Xs),
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        DdGroupCard {
            bots.forEachIndexed { index, blueprint ->
                if (index > 0) {
                    DdRowDivider(leadingInset = DdSpacing.RowHorizontal + 48.dp)
                }
                TeamBlueprintRow(
                    blueprint = blueprint,
                    onToggle = { checked -> onToggle(blueprint.ref, checked) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamBlueprintRow(
    blueprint: BotBlueprint,
    onToggle: (Boolean) -> Unit,
) {
    val colors = superPiColors()
    val capabilityTags = buildList {
        if (blueprint.capabilities.skills.isNotEmpty()) {
            add(stringResource(R.string.super_team_cap_skills, blueprint.capabilities.skills.size))
        }
        if (blueprint.capabilities.plugins.isNotEmpty()) {
            add(stringResource(R.string.super_team_cap_plugins, blueprint.capabilities.plugins.size))
        }
        if (blueprint.capabilities.mcpServers.isNotEmpty()) {
            add(stringResource(R.string.super_team_cap_mcp, blueprint.capabilities.mcpServers.size))
        }
        if (blueprint.capabilities.screenTools) {
            add(stringResource(R.string.super_team_cap_screen))
        }
        if (blueprint.capabilities.miniappTools) {
            add(stringResource(R.string.super_team_cap_miniapp))
        }
        add(
            if (blueprint.usedWorkerSlots == 0) {
                stringResource(R.string.super_team_idle_slots, blueprint.maxWorkerSlots)
            } else {
                stringResource(R.string.super_team_slots, blueprint.usedWorkerSlots, blueprint.maxWorkerSlots)
            },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.RowHorizontal, vertical = DdSpacing.Md)
            .testTag("team_bot_${blueprint.ref}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Md),
    ) {
        BotAvatar(
            name = blueprint.name,
            avatar = blueprint.avatar,
            size = 36.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
        ) {
            Text(
                text = blueprint.name,
                style = DdType.body,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (blueprint.intro.isNotBlank()) {
                Text(
                    text = blueprint.intro,
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!blueprint.supportsSuperWorker) {
                Text(
                    text = stringResource(R.string.super_team_unsupported),
                    style = DdType.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = colors.warning,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (capabilityTags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs),
                ) {
                    capabilityTags.forEachIndexed { index, tag ->
                        val isSlotTag = index == capabilityTags.lastIndex
                        Text(
                            text = tag,
                            style = if (isSlotTag) {
                                DdType.numeric.copy(fontSize = 11.5.sp, lineHeight = 16.sp)
                            } else {
                                DdType.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp)
                            },
                            color = colors.inkTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        DdSwitch(
            checked = blueprint.enabledInSuper,
            onCheckedChange = onToggle,
            enabled = blueprint.supportsSuperWorker,
            stateDescription = blueprint.name,
            modifier = Modifier.testTag("team_switch_${blueprint.ref}"),
        )
    }
}
