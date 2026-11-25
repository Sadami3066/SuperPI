package io.github.whywood.superpi.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
fun DdMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    menuWidth: Dp = 160.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val colors = superPiColors()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        containerColor = colors.raised,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, colors.hairline.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.widthIn(min = menuWidth, max = 280.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
fun DdMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: DdIcon? = null,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    testTag: String? = null,
) {
    val colors = superPiColors()
    val contentColor = when {
        destructive -> colors.danger
        selected -> colors.accent
        else -> colors.ink
    }
    DdPressable(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = RoundedCornerShape(10.dp),
        background = if (selected) colors.wash else Color.Transparent,
        contentDescription = text,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                DdIcon(icon = icon, tint = contentColor, size = 16.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = contentColor,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Spacer(Modifier.width(DdSpacing.Sm))
                trailing()
            }
        }
    }
}
