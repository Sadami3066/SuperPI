package io.github.whywood.superpi.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.ui.common.BotAvatar
import io.github.whywood.superpi.ui.common.chat.ContextMeter
import io.github.whywood.superpi.ui.common.chat.TopBarLayout

data class ChatTopBarState(
    val reminderCount: Int,
    val reloading: Boolean,
    val usage: SessionUsage?,
    val jobCount: Int,
    val remoteFilesVisible: Boolean = false,
    val reloadVisible: Boolean = true,
    val memoryExcluded: Boolean = false,
    val memoryToggleVisible: Boolean = false,
)

data class ChatTopBarActions(
    val onMenu: () -> Unit,
    val onReload: () -> Unit,
    val onOpenTimers: () -> Unit,
    val onOpenUsage: () -> Unit,
    val onOpenJobs: () -> Unit,
    val onOpenFiles: () -> Unit = {},
    val onExportSession: () -> Unit = {},
    val onOpenBotSettings: (String) -> Unit = {},
    val onToggleMemoryExcluded: () -> Unit = {},
)

private val TopBarEdge = 6.dp
private val TopBarIconButtonSize = 44.dp

@Composable
internal fun ChatTopBar(
    state: ChatTopBarState,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier,
    activeBot: RuntimeWorkspace? = null,
) {
    val colors = superPiColors()
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        val compact = maxWidth < 370.dp || LocalDensity.current.fontScale > 1.15f
        val crowded = state.jobCount > 0 || state.reminderCount > 0
        TopBarLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TopBarEdge),
            leading = {
                DdIconButton(
                    icon = DdIcons.Menu,
                    onClick = actions.onMenu,
                    contentDescription = stringResource(R.string.assistant_menu),
                    modifier = Modifier.testTag("assistant_menu"),
                    size = TopBarIconButtonSize,
                    iconSize = 20.dp,
                    tint = colors.inkSecondary,
                )
            },
            center = {
                if (activeBot != null) {
                    val remote = activeBot.remote
                    val headerContent: @Composable () -> Unit = {
                        Row(
                            modifier = Modifier.padding(horizontal = DdSpacing.Sm, vertical = DdSpacing.Xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            BotAvatar(workspace = activeBot, size = 24.dp)
                            if (remote == null) {
                                Text(
                                    text = activeBot.name,
                                    style = DdType.label.copy(fontSize = 16.sp),
                                    color = colors.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Column {
                                    Text(
                                        text = remote.botName,
                                        style = DdType.label.copy(fontSize = 15.sp, lineHeight = 19.sp),
                                        color = if (remote.online) colors.ink else colors.inkTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (remote.online) {
                                            remote.deviceName
                                        } else {
                                            stringResource(R.string.workspace_remote_offline_annotation, remote.deviceName)
                                        },
                                        style = DdType.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                                        color = colors.inkTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (remote == null) {
                        DdPressable(
                            onClick = { actions.onOpenBotSettings(activeBot.id) },
                            modifier = Modifier.heightIn(min = 44.dp).testTag("assistant_bot"),
                            shape = CircleShape,
                            contentDescription = activeBot.name,
                        ) {
                            headerContent()
                        }
                    } else {
                        Box(
                            modifier = Modifier.heightIn(min = 44.dp).testTag("assistant_bot"),
                            contentAlignment = Alignment.Center,
                        ) {
                            headerContent()
                        }
                    }
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.reminderCount > 0) {
                        DdIconButton(
                            icon = DdIcons.Clock,
                            onClick = actions.onOpenTimers,
                            contentDescription = stringResource(R.string.assistant_reminders),
                            modifier = Modifier.testTag("assistant_reminders"),
                            size = TopBarIconButtonSize,
                            iconSize = 17.dp,
                            tint = colors.inkSecondary,
                        )
                    }
                    JobsMeter(
                        count = state.jobCount,
                        onClick = actions.onOpenJobs,
                    )
                    if (state.reloading) {
                        DdSpinner(
                            modifier = Modifier.padding(end = DdSpacing.Xs).testTag("assistant_reloading"),
                            size = 16.dp,
                            strokeWidth = 1.5.dp,
                        )
                    }
                    val usage = state.usage
                    if (usage != null && usage.contextWindow > 0L) {
                        ContextMeter(
                            usedTokens = usage.contextTokens,
                            windowTokens = usage.contextWindow,
                            onClick = actions.onOpenUsage,
                            showPercent = !compact && !crowded,
                        )
                    }
                    Box {
                        DdIconButton(
                            icon = DdIcons.DotsVertical,
                            onClick = { menuOpen = true },
                            contentDescription = stringResource(R.string.assistant_more),
                            modifier = Modifier.testTag("assistant_more"),
                            size = TopBarIconButtonSize,
                            iconSize = 19.dp,
                            tint = colors.inkSecondary,
                        )
                        DdMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            menuWidth = 172.dp,
                        ) {
                            if (activeBot?.remote == null || state.remoteFilesVisible) {
                                DdMenuItem(
                                    text = stringResource(R.string.files_menu),
                                    icon = DdIcons.Folder,
                                    onClick = {
                                        menuOpen = false
                                        actions.onOpenFiles()
                                    },
                                    testTag = "assistant_files",
                                )
                            }
                            DdMenuItem(
                                text = stringResource(R.string.assistant_export_session),
                                icon = DdIcons.Download,
                                onClick = {
                                    menuOpen = false
                                    actions.onExportSession()
                                },
                                testTag = "chat_export",
                            )
                            if (state.memoryToggleVisible || state.reloadVisible) {
                                DdRowDivider()
                            }
                            if (state.memoryToggleVisible) {
                                DdMenuItem(
                                    text = stringResource(
                                        if (state.memoryExcluded) {
                                            R.string.memory_session_resume
                                        } else {
                                            R.string.memory_session_exclude
                                        },
                                    ),
                                    icon = DdIcons.Privacy,
                                    selected = state.memoryExcluded,
                                    onClick = {
                                        menuOpen = false
                                        actions.onToggleMemoryExcluded()
                                    },
                                    testTag = "chat_memory_toggle",
                                )
                            }
                            if (state.reloadVisible) {
                                DdMenuItem(
                                    text = stringResource(R.string.assistant_reload),
                                    icon = DdIcons.Refresh,
                                    onClick = {
                                        menuOpen = false
                                        actions.onReload()
                                    },
                                    testTag = "assistant_reload",
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}
