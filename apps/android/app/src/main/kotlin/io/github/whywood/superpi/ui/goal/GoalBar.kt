package io.github.whywood.superpi.ui.goal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.GoalPhase
import io.github.whywood.superpi.runtime.GoalView

@Composable
fun GoalBar(
    view: GoalView,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    onEnd: (() -> Unit)? = null,
) {
    val colors = superPiColors()
    val goal = view.goal
    val phase = if (goal.phase == GoalPhase.ACTIVE && !view.armed) GoalPhase.PAUSED else goal.phase
    val dotColor = when (phase) {
        GoalPhase.ACTIVE -> colors.accent
        GoalPhase.BLOCKED -> colors.danger
        GoalPhase.PAUSED, GoalPhase.COMPLETE -> colors.inkTertiary
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = DdSpacing.Xs)
            .clip(DdShapes.Card)
            .background(colors.surface)
            .border(1.dp, colors.hairline.copy(alpha = 0.65f), DdShapes.Card)
            .padding(start = DdSpacing.Md, end = DdSpacing.Xs, top = DdSpacing.Sm, bottom = DdSpacing.Xxs)
            .testTag("goal_bar"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = DdSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            Text(
                text = goal.objective.ifBlank { stringResource(R.string.goal_untitled) },
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = goalPhaseLabel(phase),
                    style = DdType.micro,
                    color = goalPhaseColor(phase),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = if (goal.maxRounds > 0) {
                        stringResource(R.string.goal_round, goal.roundsStarted, goal.maxRounds)
                    } else {
                        stringResource(R.string.goal_round_unbounded, goal.roundsStarted)
                    },
                    style = DdType.micro,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (phase) {
                GoalPhase.ACTIVE -> {
                    DdTextAction(
                        text = stringResource(R.string.goal_pause),
                        onClick = onPause,
                        modifier = Modifier.testTag("goal_action"),
                        color = colors.inkSecondary,
                    )
                }

                GoalPhase.PAUSED, GoalPhase.BLOCKED -> {
                    DdTextAction(
                        text = stringResource(R.string.goal_resume),
                        onClick = onResume,
                        modifier = Modifier.testTag("goal_action"),
                        color = colors.accent,
                    )
                }

                GoalPhase.COMPLETE -> Unit
            }
            if (onEnd != null) {
                DdTextAction(
                    text = stringResource(R.string.goal_end),
                    onClick = onEnd,
                    modifier = Modifier.testTag("goal_end"),
                    color = colors.danger,
                )
            }
        }
    }
}

@Composable
internal fun goalPhaseLabel(phase: GoalPhase): String = stringResource(
    when (phase) {
        GoalPhase.ACTIVE -> R.string.goal_phase_active
        GoalPhase.PAUSED -> R.string.goal_phase_paused
        GoalPhase.BLOCKED -> R.string.goal_phase_blocked
        GoalPhase.COMPLETE -> R.string.goal_phase_complete
    },
)

@Composable
internal fun goalPhaseColor(phase: GoalPhase): Color {
    val colors = superPiColors()
    return when (phase) {
        GoalPhase.ACTIVE -> colors.accent
        GoalPhase.PAUSED -> colors.inkSecondary
        GoalPhase.BLOCKED -> colors.danger
        GoalPhase.COMPLETE -> colors.inkTertiary
    }
}
