package io.github.whywood.superpi.data.relay

import java.util.Base64

internal object RelayImageBudget {
    private const val ERROR_MESSAGE = "Images are too large to send remotely. Try fewer or smaller images."

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
        return RelayImageCodec.reencodeToFit(rawBytes, targetChars)
    }
}
