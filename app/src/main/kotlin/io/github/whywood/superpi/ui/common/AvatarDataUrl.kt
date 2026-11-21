package io.github.whywood.superpi.ui.common

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

private const val MAX_AVATAR_DIMENSION_PX = 256
private const val DATA_URL_PREFIX = "data:image/jpeg;base64,"

internal fun encodeAvatarBitmapDataUrl(bitmap: Bitmap, maxDataUrlChars: Int = 60_000): String? {
    val scaled = scaleAvatarBitmap(bitmap, MAX_AVATAR_DIMENSION_PX)
    return try {
        val qualities = intArrayOf(85, 70, 55, 40)
        for (quality in qualities) {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) continue
            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            val dataUrl = DATA_URL_PREFIX + encoded
            if (dataUrl.length <= maxDataUrlChars) return dataUrl
        }
        null
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}

private fun scaleAvatarBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return bitmap
    val longest = maxOf(width, height)
    if (longest <= maxDim) return bitmap
    val ratio = maxDim.toFloat() / longest.toFloat()
    val targetWidth = (width * ratio).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * ratio).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}
