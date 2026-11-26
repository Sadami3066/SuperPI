package io.github.whywood.superpi.data.relay

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

internal actual object RelayImageCodec {
    private const val MIN_DOWNSCALE_EDGE = 256
    private const val DOWNSCALE_FACTOR = 0.75f
    private const val MAX_DOWNSCALE_PASSES = 4
    private val PRIMARY_QUALITIES = intArrayOf(75, 65, 55, 45, 35)
    private val SCALED_QUALITIES = intArrayOf(65, 50, 35)

    actual fun reencodeToFit(rawBytes: ByteArray, targetChars: Int): RouteImagePart? {
        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(rawBytes)) }.getOrNull() ?: return null
        var current = toRgbWithWhiteBackground(decoded)
        tryQualities(current, PRIMARY_QUALITIES, targetChars)?.let { return it }
        for (pass in 0 until MAX_DOWNSCALE_PASSES) {
            if (maxOf(current.width, current.height) <= MIN_DOWNSCALE_EDGE) break
            val nextW = (current.width * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1)
            val nextH = (current.height * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1)
            if (nextW == current.width && nextH == current.height) break
            current = scaleImage(current, nextW, nextH)
            tryQualities(current, SCALED_QUALITIES, targetChars)?.let { return it }
        }
        return null
    }

    actual fun encodeSquareAvatarDataUrl(file: File, maxPx: Int, maxDataUrlChars: Int): String? = runCatching {
        val decoded = ImageIO.read(file) ?: return@runCatching null
        val squareSize = minOf(decoded.width, decoded.height).coerceAtLeast(1)
        val x = (decoded.width - squareSize) / 2
        val y = (decoded.height - squareSize) / 2
        val cropped = if (decoded.width == decoded.height) {
            decoded
        } else {
            decoded.getSubimage(x, y, squareSize, squareSize)
        }
        val rgb = toRgbWithWhiteBackground(cropped)
        val scaled = if (rgb.width > maxPx) scaleImage(rgb, maxPx, maxPx) else rgb
        for (quality in intArrayOf(80, 65)) {
            val bytes = compressJpeg(scaled, quality / 100f) ?: continue
            val candidate = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
            if (candidate.length <= maxDataUrlChars) return@runCatching candidate
        }
        null
    }.getOrNull()

    private fun tryQualities(image: BufferedImage, qualities: IntArray, targetChars: Int): RouteImagePart? {
        for (quality in qualities) {
            val bytes = compressJpeg(image, quality / 100f) ?: continue
            val encoded = Base64.getEncoder().encodeToString(bytes)
            if (encoded.length <= targetChars) {
                return RouteImagePart(data = encoded, mimeType = "image/jpeg")
            }
        }
        return null
    }

    private fun toRgbWithWhiteBackground(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB && !source.colorModel.hasAlpha()) return source
        val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = output.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, source.width, source.height)
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return output
    }

    private fun scaleImage(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = output.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return output
    }

    private fun compressJpeg(image: BufferedImage, quality: Float): ByteArray? = runCatching {
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return null
        val writer = writers.next()
        val out = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(out).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam.apply {
                    if (canWriteCompressed()) {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality.coerceIn(0.05f, 1.0f)
                    }
                }
                writer.write(null, IIOImage(image, null, null), param)
                ios.flush()
            }
            out.toByteArray()
        } finally {
            writer.dispose()
        }
    }.getOrNull()
}
