package io.github.whywood.superpi.data.relay

import java.io.File

internal expect object RelayImageCodec {
    fun reencodeToFit(rawBytes: ByteArray, targetChars: Int): RouteImagePart?

    fun encodeSquareAvatarDataUrl(file: File, maxPx: Int, maxDataUrlChars: Int): String?
}
