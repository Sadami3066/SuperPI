package io.github.whywood.superpi.data.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

internal actual object RelayImageCodec {
    private const val MIN_DOWNSCALE_EDGE = 256
    private const val DOWNSCALE_FACTOR = 0.75f
    private const val MAX_DOWNSCALE_PASSES = 4
    private val PRIMARY_QUALITIES = intArrayOf(75, 65, 55, 45, 35)
    private val SCALED_QUALITIES = intArrayOf(65, 50, 35)

    actual fun reencodeToFit(rawBytes: ByteArray, targetChars: Int): RouteImagePart? {
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(
                rawBytes,
                0,
                rawBytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        }.getOrNull() ?: return null
        var current: Bitmap = if (decoded.hasAlpha()) {
            flattenToWhite(decoded).also { decoded.recycle() }
        } else {
            decoded
        }
        try {
            tryQualities(current, PRIMARY_QUALITIES, targetChars)?.let { return it }
            for (pass in 0 until MAX_DOWNSCALE_PASSES) {
                if (maxOf(current.width, current.height) <= MIN_DOWNSCALE_EDGE) break
                val nextW = (current.width * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1)
                val nextH = (current.height * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1)
                if (nextW == current.width && nextH == current.height) break
                val scaled = Bitmap.createScaledBitmap(current, nextW, nextH, true)
                if (scaled !== current) {
                    current.recycle()
                    current = scaled
                }
                tryQualities(current, SCALED_QUALITIES, targetChars)?.let { return it }
            }
            return null
        } finally {
            if (!current.isRecycled) current.recycle()
        }
    }

    actual fun encodeSquareAvatarDataUrl(file: File, maxPx: Int, maxDataUrlChars: Int): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options()
        if (maxDim > maxPx * 2) {
            var sample = 1
            while (maxDim / (sample * 2) >= maxPx) sample *= 2
            options.inSampleSize = sample
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val squareSize = minOf(decoded.width, decoded.height).coerceAtLeast(1)
            val x = (decoded.width - squareSize) / 2
            val y = (decoded.height - squareSize) / 2
            cropped = if (decoded.width == decoded.height) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, x, y, squareSize, squareSize)
            }
            scaled = if (cropped.width > maxPx) {
                Bitmap.createScaledBitmap(cropped, maxPx, maxPx, true)
            } else {
                cropped
            }
            for (quality in intArrayOf(80, 65)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val candidate = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
                if (candidate.length <= maxDataUrlChars) return@runCatching candidate
            }
            null
        } finally {
            if (scaled != null && scaled !== cropped && scaled !== decoded) scaled.recycle()
            if (cropped != null && cropped !== decoded) cropped.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    private fun tryQualities(bitmap: Bitmap, qualities: IntArray, targetChars: Int): RouteImagePart? {
        val buffer = ByteArrayOutputStream()
        for (quality in qualities) {
            buffer.reset()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)) continue
            val encoded = Base64.getEncoder().encodeToString(buffer.toByteArray())
            if (encoded.length <= targetChars) {
                return RouteImagePart(data = encoded, mimeType = "image/jpeg")
            }
        }
        return null
    }

    private fun flattenToWhite(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return output
    }
}
