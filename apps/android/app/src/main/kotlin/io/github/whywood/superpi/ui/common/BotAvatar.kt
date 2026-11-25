package io.github.whywood.superpi.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BotAvatar(
    workspace: RuntimeWorkspace,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    BotAvatar(
        name = workspace.name,
        avatar = workspace.avatar,
        modifier = modifier,
        size = size,
    )
}

@Composable
internal fun BotAvatar(
    name: String,
    avatar: String?,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val loadedAvatar by produceState<ImageBitmap?>(initialValue = null, avatar) {
        value = loadAvatarFile(avatar)
    }
    val colors = superPiColors()
    val image = loadedAvatar
    if (image != null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .border(1.dp, colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = name,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        PiBrandMark(
            size = size,
            modifier = modifier,
            contentDescription = name,
        )
    }
}

private suspend fun loadAvatarFile(path: String?): ImageBitmap? {
    val file = path?.takeIf { it.isNotBlank() } ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            if (file.startsWith("data:image/")) {
                val base64Marker = ";base64,"
                val markerIndex = file.indexOf(base64Marker)
                if (markerIndex <= 0) return@runCatching null
                val bytes = java.util.Base64.getDecoder().decode(file.substring(markerIndex + base64Marker.length))
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } else {
                BitmapFactory.decodeFile(file)?.asImageBitmap()
            }
        }.getOrNull()
    }
}
