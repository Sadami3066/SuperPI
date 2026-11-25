package io.github.whywood.superpi.ui.common.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import kotlin.math.roundToInt

@Composable
fun ContextMeter(
    usedTokens: Long,
    windowTokens: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showPercent: Boolean = true,
) {
    if (windowTokens <= 0L) return
    val colors = superPiColors()
    val fraction = (usedTokens.toDouble() / windowTokens.toDouble()).coerceIn(0.0, 1.0).toFloat()
    val percent = (fraction * 100f).roundToInt()
    val trackColor = colors.hairline
    val progressColor = when {
        fraction >= 0.90f -> colors.danger
        fraction >= 0.75f -> colors.warning
        else -> colors.accent
    }
    val description = stringResource(R.string.usage_meter_description, percent)
    DdPressable(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .testTag("context_meter"),
        shape = DdShapes.Small,
        contentDescription = description,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(17.dp)) {
                val strokeWidth = 2.5.dp.toPx()
                val inset = strokeWidth / 2f
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
                if (fraction > 0f) {
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
            if (showPercent && usedTokens > 0L) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.usage_meter_percent, percent),
                    style = DdType.numeric.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
