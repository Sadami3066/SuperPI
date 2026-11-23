package io.github.whywood.superpi.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

@Composable
fun DdGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = superPiColors()
    CompositionLocalProvider(
        LocalDdRowHorizontalPadding provides DdSpacing.RowHorizontal,
        LocalDdRowShape provides RectangleShape,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(DdShapes.Card)
                .background(colors.surface)
                .border(1.dp, colors.hairline.copy(alpha = 0.65f), DdShapes.Card),
            content = content,
        )
    }
}

@Composable
fun DdGroupCard(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = superPiColors()
    CompositionLocalProvider(
        LocalDdRowHorizontalPadding provides DdSpacing.RowHorizontal,
        LocalDdRowShape provides RectangleShape,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(DdShapes.Card)
                .background(colors.surface)
                .border(1.dp, colors.hairline.copy(alpha = 0.65f), DdShapes.Card),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
            ) {
                header()
            }
            DdRowDivider(leadingInset = DdSpacing.Card)
            Column(content = content)
            if (footer != null) {
                DdRowDivider(leadingInset = DdSpacing.Card)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Md),
                ) {
                    footer()
                }
            }
        }
    }
}
