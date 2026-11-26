package io.github.whywood.superpi.host.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a compact, crisp Unicode half-block QR code in UTF-8 terminals.
 */
object TerminalQr {
    fun render(payload: String, margin: Int = 1): String {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to margin,
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        val width = matrix.width
        val height = matrix.height
        return buildString {
            var y = 0
            while (y < height) {
                for (x in 0 until width) {
                    val topDark = matrix.get(x, y)
                    val bottomDark = if (y + 1 < height) matrix.get(x, y + 1) else false
                    val ch = when {
                        topDark && bottomDark -> '█'
                        topDark && !bottomDark -> '▀'
                        !topDark && bottomDark -> '▄'
                        else -> ' '
                    }
                    append(ch)
                }
                if (y + 2 < height) append('\n')
                y += 2
            }
        }
    }
}
