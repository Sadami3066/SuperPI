package io.github.whywood.superpi.ui.superagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.SuperPIColors
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.ui.common.BotAvatar
import io.github.whywood.superpi.ui.common.chat.AskQuestionCard
import io.github.whywood.superpi.ui.common.chat.ContextMeter
import io.github.whywood.superpi.ui.common.chat.TopBarLayout

@Composable
internal fun SuperTopBar(
    onOpenDrawer: () -> Unit,
    onOpenTeam: () -> Unit,
    onRequestArchiveAndReset: () -> Unit,
    modifier: Modifier = Modifier,
    usage: SessionUsage? = null,
    onOpenUsage: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
) {
    val colors = superPiColors()
    var menuOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        val compact = maxWidth < 370.dp || LocalDensity.current.fontScale > 1.15f
        TopBarLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            leading = {
                DdIconButton(
                    icon = DdIcons.Menu,
                    onClick = onOpenDrawer,
                    contentDescription = stringResource(R.string.assistant_menu),
                    modifier = Modifier.testTag("super_menu"),
                    size = 44.dp,
                    iconSize = 20.dp,
                    tint = colors.inkSecondary,
                )
            },
            center = {
                Text(
                    text = buildAnnotatedString {
                        append("Super PI")
                        withStyle(SpanStyle(color = colors.signal)) { append(".") }
                    },
                    style = DdType.heading.copy(fontSize = 18.sp),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (usage != null && usage.contextWindow > 0L) {
                        ContextMeter(
                            usedTokens = usage.contextTokens,
                            windowTokens = usage.contextWindow,
                            onClick = onOpenUsage,
                            showPercent = !compact,
                        )
                    }
                    Box {
                        DdIconButton(
                            icon = DdIcons.DotsVertical,
                            onClick = { menuOpen = true },
                            contentDescription = stringResource(R.string.assistant_more),
                            modifier = Modifier.testTag("super_more"),
                            size = 44.dp,
                            iconSize = 19.dp,
                            tint = colors.inkSecondary,
                        )
                        DdMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            menuWidth = 176.dp,
                        ) {
                            DdMenuItem(
                                text = stringResource(R.string.super_chat_menu_team),
                                icon = DdIcons.Sparkles,
                                onClick = {
                                    menuOpen = false
                                    onOpenTeam()
                                },
                                testTag = "super_menu_team",
                            )
                            if (onOpenSettings != null) {
                                DdMenuItem(
                                    text = stringResource(R.string.super_settings_title),
                                    icon = DdIcons.Settings,
                                    onClick = {
                                        menuOpen = false
                                        onOpenSettings()
                                    },
                                    testTag = "super_menu_settings",
                                )
                            }
                            DdRowDivider()
                            DdMenuItem(
                                text = stringResource(R.string.super_chat_menu_archive),
                                icon = DdIcons.Archive,
                                onClick = {
                                    menuOpen = false
                                    onRequestArchiveAndReset()
                                },
                                testTag = "super_menu_archive",
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
internal fun ActiveWorkersStrip(
    workers: List<WorkerRunRecord>,
    onOpenWorkerMonitor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (workers.isEmpty()) return
    val colors = superPiColors()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = DdSpacing.Md, end = DdSpacing.Md, top = DdSpacing.Xs, bottom = DdSpacing.Sm)
                .testTag("super_active_workers_strip"),
            horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            workers.forEach { worker ->
                val stepOrStatus = worker.activeStepText
                    ?.takeIf { it.isNotBlank() }
                    ?: workerStatusLabel(worker.status)
                val label = buildString {
                    append(
                        worker.title.takeIf { it.isNotBlank() && it != worker.workerId }
                            ?: worker.botName,
                    )
                    if (worker.isRemote && worker.deviceName.isNotBlank()) {
                        append(" · ")
                        append(worker.deviceName)
                    }
                }
                DdPressable(
                    onClick = { onOpenWorkerMonitor(worker.workerId) },
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("super_strip_worker_${worker.workerId}"),
                    shape = CircleShape,
                    background = Color.Transparent,
                    contentDescription = "$label: $stepOrStatus",
                ) {
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .border(1.dp, colors.hairline.copy(alpha = DdAlpha.Secondary), CircleShape)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        BotAvatar(
                            name = worker.botName,
                            avatar = worker.botAvatar,
                            size = 22.dp,
                        )
                        if (worker.status == WorkerStatus.RUNNING) {
                            DdSpinner(
                                color = colors.accent,
                                size = 11.dp,
                                strokeWidth = 1.5.dp,
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(workerStatusColor(worker.status, colors)),
                            )
                        }
                        Text(
                            text = label,
                            style = DdType.chip.copy(fontWeight = FontWeight.Medium),
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 132.dp),
                        )
                        Text(
                            text = stepOrStatus,
                            style = DdType.chip,
                            color = colors.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp),
                        )
                    }
                }
            }
        }
        DdRowDivider(color = colors.hairline.copy(alpha = 0.45f))
    }
}

@Composable
internal fun WorkerApprovalBanner(
    worker: WorkerRunRecord,
    question: PendingQuestion,
    onOpenWorkerMonitor: (String) -> Unit,
    onAnswer: (List<QuestionAnswer>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val botLabel = buildString {
        append(worker.botName)
        if (worker.isRemote && worker.deviceName.isNotBlank()) {
            append(" · ")
            append(worker.deviceName)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 780.dp)
            .clip(DdShapes.Card)
            .background(colors.raised)
            .border(1.dp, colors.hairline.copy(alpha = DdAlpha.Secondary), DdShapes.Card)
            .padding(DdSpacing.Md)
            .testTag("super_worker_approval_${worker.workerId}"),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
        ) {
            BotAvatar(
                name = worker.botName,
                avatar = worker.botAvatar,
                size = 24.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.super_chat_worker_approval_title, botLabel),
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = worker.title,
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DdTextAction(
                text = stringResource(R.string.super_chat_worker_approval_open),
                onClick = { onOpenWorkerMonitor(worker.workerId) },
            )
            DdTextAction(
                text = stringResource(R.string.super_chat_worker_approval_dismiss),
                onClick = onDismiss,
                color = colors.inkTertiary,
            )
        }
        AskQuestionCard(
            questions = question.questions,
            answers = null,
            submitted = false,
            onAnswer = onAnswer,
        )
    }
}

@Composable
internal fun workerStatusLabel(status: WorkerStatus): String = stringResource(
    when (status) {
        WorkerStatus.QUEUED -> R.string.super_worker_status_queued
        WorkerStatus.RUNNING -> R.string.super_worker_status_running
        WorkerStatus.WAITING_INPUT -> R.string.super_worker_status_waiting
        WorkerStatus.STALLED -> R.string.super_worker_status_stalled
        WorkerStatus.IDLE -> R.string.super_worker_status_idle
        WorkerStatus.FAILED -> R.string.super_worker_status_failed
    },
)

internal fun workerStatusColor(status: WorkerStatus, colors: SuperPIColors): Color = when (status) {
    WorkerStatus.QUEUED,
    WorkerStatus.RUNNING,
    WorkerStatus.WAITING_INPUT,
    -> colors.accent

    WorkerStatus.STALLED -> colors.warning
    WorkerStatus.FAILED -> colors.danger
    WorkerStatus.IDLE -> colors.success
}
