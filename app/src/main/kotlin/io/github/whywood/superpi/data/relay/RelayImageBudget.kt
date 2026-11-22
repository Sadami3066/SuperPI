package io.github.whywood.superpi.data.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.Base64

internal object RelayImageBudget {
    private const val ERROR_MESSAGE = "Images are too large to send remotely. Try fewer or smaller images."
    private const val MIN_DOWNSCALE_EDGE = 256
    private const val DOWNSCALE_FACTOR = 0.75f
    private const val MAX_DOWNSCALE_PASSES = 4
    private val PRIMARY_QUALITIES = intArrayOf(75, 65, 55, 45, 35)
    private val SCALED_QUALITIES = intArrayOf(65, 50, 35)

    fun fit(
        images: List<RouteImagePart>,
        maxCount: Int = RelayFrames.MAX_IMAGES,
        maxSingleBase64: Int = RelayFrames.MAX_IMAGE_BASE64_CHARS,
        maxTotalBase64: Int = RelayFrames.MAX_IMAGES_TOTAL_BASE64_CHARS,
    ): Result<List<RouteImagePart>> {
        val selected = images.asSequence()
            .filter { it.data.isNotBlank() && it.mimeType.isNotBlank() }
            .take(maxCount)
            .toList()
        if (selected.isEmpty()) return Result.success(emptyList())
        if (selected.all { it.data.length <= maxSingleBase64 } &&
            selected.sumOf { it.data.length } <= maxTotalBase64
        ) {
            return Result.success(selected)
        }
        val equalShare = (maxTotalBase64 / selected.size).coerceAtMost(maxSingleBase64).coerceAtLeast(1)
        val keepMask = BooleanArray(selected.size) { index ->
            val len = selected[index].data.length
            len <= equalShare && len <= maxSingleBase64
        }
        val keptChars = selected.indices.sumOf { index -> if (keepMask[index]) selected[index].data.length else 0 }
        val shrinkCount = keepMask.count { !it }
        val targetPerShrink = if (shrinkCount > 0) {
            ((maxTotalBase64 - keptChars) / shrinkCount).coerceAtMost(maxSingleBase64).coerceAtLeast(1)
        } else {
            equalShare
        }
        val fitted = ArrayList<RouteImagePart>(selected.size)
        for (index in selected.indices) {
            val part = selected[index]
            if (keepMask[index]) {
                fitted += part
            } else {
                val compressed = reencodeToFit(part, targetPerShrink)
                    ?: return Result.failure(IllegalArgumentException(ERROR_MESSAGE))
                fitted += compressed
            }
        }
        if (fitted.any { it.data.length > maxSingleBase64 } ||
            fitted.sumOf { it.data.length } > maxTotalBase64
        ) {
            return Result.failure(IllegalArgumentException(ERROR_MESSAGE))
        }
        return Result.success(fitted)
    }

    private fun reencodeToFit(image: RouteImagePart, targetChars: Int): RouteImagePart? {
        val rawBytes = runCatching { Base64.getDecoder().decode(image.data.trim()) }.getOrNull() ?: return null
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
