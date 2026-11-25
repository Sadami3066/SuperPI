package io.github.whywood.superpi.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.superPiColors

@Composable
internal fun PiBrandMark(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val dark = superPiColors().dark
    Image(
        painter = painterResource(
            if (dark) R.drawable.pi_brand_bot_dark else R.drawable.pi_brand_bot_light,
        ),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
