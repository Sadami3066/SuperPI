package io.github.whywood.superpi.ui.common.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdNoticeBar
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.superPiColors

@Composable
internal fun ChatErrorNoticeBar(
    error: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val colors = superPiColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 780.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
        ) {
            DdNoticeBar(
                text = error,
                tone = DdNoticeTone.DANGER,
                actionLabel = if (onRetry != null) stringResource(R.string.super_chat_error_retry) else null,
                onAction = onRetry,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DdTextAction(
                    text = stringResource(R.string.super_chat_error_dismiss),
                    onClick = onDismiss,
                    color = colors.inkTertiary,
                )
            }
        }
    }
}
