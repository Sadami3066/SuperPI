package io.github.whywood.superpi.ui.common.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_DEFAULT
import io.github.whywood.superpi.data.normalizeCompactionTriggerPercent
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdPrimaryButton
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSheetHeader
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.SessionUsage
import java.util.Locale

@Composable
fun UsageSheet(
    usage: SessionUsage,
    compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
    onDismiss: () -> Unit,
    onCompact: (() -> Unit)? = null,
) {
    val colors = superPiColors()
    val progress = if (usage.contextWindow > 0L) {
        (usage.contextTokens.toDouble() / usage.contextWindow.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    val effectivePercent = normalizeCompactionTriggerPercent(compactionTriggerPercent)
    val threshold = (usage.contextWindow * effectivePercent / 100L).coerceIn(0L, usage.contextWindow)
    val thresholdFraction = (effectivePercent / 100f).coerceIn(0.05f, 0.98f)
    val fillColor = when {
        progress >= 0.90f -> colors.danger
        progress >= 0.75f -> colors.warning
        else -> colors.accent
    }
    val cacheSummary = buildString {
        append(stringResource(R.string.usage_cache_read))
        append(' ')
        append(formatTokens(usage.cacheReadTokens))
        append(" · ")
        append(stringResource(R.string.usage_cache_write))
        append(' ')
        append(formatTokens(usage.cacheWriteTokens))
    }
    DdSheet(onDismiss = onDismiss) {
        DdSheetHeader(
            title = stringResource(R.string.usage_title),
            onClose = onDismiss,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DdSpacing.Xxl),
        ) {
            Spacer(Modifier.height(DdSpacing.Xs))
            DdGroupCard {
                Column(Modifier.fillMaxWidth().padding(DdSpacing.Lg)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.usage_context),
                            modifier = Modifier.weight(1f),
                            style = DdType.label,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(DdSpacing.Sm))
                        Text(
                            text = stringResource(
                                R.string.usage_context_value,
                                formatContextTokens(usage.contextTokens),
                                formatContextTokens(usage.contextWindow),
                            ),
                            style = DdType.numeric.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.ink,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(colors.wash),
                        ) {
                            if (progress > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(progress)
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(fillColor),
                                )
                            }
                        }
                        if (usage.contextWindow > 0L && thresholdFraction in 0.05f..0.98f) {
                            val tickOffset = (maxWidth * thresholdFraction - 1.dp)
                                .coerceIn(0.dp, (maxWidth - 2.dp).coerceAtLeast(0.dp))
                            Box(
                                Modifier
                                    .offset(x = tickOffset)
                                    .width(2.dp)
                                    .height(12.dp)
                                    .clip(CircleShape)
                                    .background(colors.inkTertiary),
                            )
                        }
                    }
                    Spacer(Modifier.height(DdSpacing.Sm))
                    Text(
                        text = stringResource(
                            R.string.usage_compact_threshold,
                            formatContextTokens(threshold),
                        ),
                        style = DdType.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = colors.inkTertiary,
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Lg))
            Text(
                text = stringResource(R.string.usage_cumulative),
                style = DdType.micro,
                color = colors.inkTertiary,
                modifier = Modifier.padding(start = DdSpacing.Xs),
            )
            Spacer(Modifier.height(DdSpacing.Sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                UsageMetricTile(
                    label = stringResource(R.string.usage_input),
                    value = formatTokens(usage.inputTokens),
                    modifier = Modifier.weight(1f),
                )
                UsageMetricTile(
                    label = stringResource(R.string.usage_output),
                    value = formatTokens(usage.outputTokens),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(DdSpacing.Sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
            ) {
                UsageMetricTile(
                    label = stringResource(R.string.usage_cache),
                    value = formatTokens(usage.cacheReadTokens + usage.cacheWriteTokens),
                    detail = cacheSummary,
                    modifier = Modifier.weight(1f),
                )
                UsageMetricTile(
                    label = stringResource(R.string.usage_cost),
                    value = stringResource(R.string.usage_cost_value, formatCost(usage.cost)),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(DdSpacing.Xl))
            if (onCompact != null) {
                DdPrimaryButton(
                    text = stringResource(R.string.usage_compact_now),
                    onClick = onCompact,
                    modifier = Modifier.fillMaxWidth().testTag("usage_compact_now"),
                )
                Spacer(Modifier.height(DdSpacing.Md))
            }
            Text(
                text = stringResource(R.string.usage_footnote),
                style = DdType.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.inkTertiary,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun UsageMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val colors = superPiColors()
    Column(
        modifier = modifier
            .clip(DdShapes.Medium)
            .background(colors.surface)
            .border(0.5.dp, colors.surfaceRim, DdShapes.Medium)
            .padding(horizontal = DdSpacing.Md, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = DdType.numeric.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = DdType.numeric.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun formatTokens(value: Long): String = when {
    value < 1_000L -> value.toString()
    value < 1_000_000L -> {
        val thousands = value / 1_000.0
        if (thousands < 10.0) {
            String.format(Locale.US, "%.1fK", thousands)
        } else {
            "${value / 1_000L}K"
        }
    }

    else -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
}

private fun formatContextTokens(value: Long): String = when {
    value < 1_000L -> value.toString()
    value < 1_000_000L -> compact(value / 1_000.0) + "K"
    else -> compact(value / 1_000_000.0) + "M"
}

private fun compact(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

private fun formatCost(cost: Double): String = String.format(Locale.US, "%.3f", cost)
