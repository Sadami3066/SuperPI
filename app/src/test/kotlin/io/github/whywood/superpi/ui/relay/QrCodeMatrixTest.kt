package io.github.whywood.superpi.ui.relay

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeMatrixTest {
    @Test
    fun decodesShortMediumPairLinkUtf8AndMaxCapacityAcrossVersions() {
        val versionBoundaries = listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213, 251, 287, 331, 362)
        val payloads = listOf(
            "hi",
            "https://relay.example.com/pair",
            "superpi://pair?url=https%3A%2F%2Frelay.example.com&code=ABCD1234EFGH5678&fp=SHA256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "superpi://pair?url=https%3A%2F%2Fsuperpi.tail12345.ts.net%3A8443%2Fv1%2Frelay%2Fconnect&code=9876543210ABCDEF9876543210ABCDEF&fp=SHA256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210&device=Pixel-9",
            "超级派://配对?设备=主力机&校验=你好世界-αβγ-🚀",
        ) + versionBoundaries.mapIndexed { idx, len -> ('A' + (idx % 26)).toString().repeat(len) }
        for (payload in payloads) {
            val matrix = QrCodeMatrix.encode(payload)
            assertNotNull("Expected matrix for byte len ${payload.toByteArray().size}", matrix)
            val decoded = IndependentIsoQrDecoder.decode(matrix!!)
            assertEquals("Round-trip mismatch for payload len ${payload.length}", payload, decoded)
        }
    }

    @Test
    fun returnsNullWhenPayloadExceedsVersion14Capacity() {
        assertNull(QrCodeMatrix.encode("B".repeat(363)))
    }

    @Test
    fun rendersCrispPixelsWithWhiteQuietZone() {
        val matrix = QrCodeMatrix.encode("superpi://pair?code=TEST")!!
        val (dim, pixels) = matrix.toPixels(modulePx = 4, quietZoneModules = 4)
        val expectedDim = (matrix.size + 8) * 4
        assertEquals(expectedDim, dim)
        assertEquals(expectedDim * expectedDim, pixels.size)
        assertEquals(-0x1, pixels[0])
        assertEquals(-0x1, pixels[dim * dim - 1])
        assertEquals(-0x1000000, pixels[(4 * 4) * dim + (4 * 4)])
    }

    private object IndependentIsoQrDecoder {
        private data class BlockGeometry(
            val ecPerBlock: Int,
            val g1Blocks: Int,
            val g1Data: Int,
            val g2Blocks: Int,
            val g2Data: Int,
        )

        private val LEVEL_M_BY_VERSION = mapOf(
            1 to BlockGeometry(10, 1, 16, 0, 0),
            2 to BlockGeometry(16, 1, 28, 0, 0),
            3 to BlockGeometry(26, 1, 44, 0, 0),
            4 to BlockGeometry(18, 2, 32, 0, 0),
            5 to BlockGeometry(24, 2, 43, 0, 0),
            6 to BlockGeometry(16, 4, 27, 0, 0),
            7 to BlockGeometry(18, 4, 31, 0, 0),
            8 to BlockGeometry(22, 2, 38, 2, 39),
            9 to BlockGeometry(22, 3, 36, 2, 37),
            10 to BlockGeometry(26, 4, 43, 1, 44),
            11 to BlockGeometry(30, 1, 50, 4, 51),
            12 to BlockGeometry(22, 6, 36, 2, 37),
            13 to BlockGeometry(22, 8, 37, 1, 38),
            14 to BlockGeometry(24, 4, 40, 5, 41),
        )

        private val ALIGN_BY_VERSION = mapOf(
            1 to intArrayOf(),
            2 to intArrayOf(6, 18),
            3 to intArrayOf(6, 22),
            4 to intArrayOf(6, 26),
            5 to intArrayOf(6, 30),
            6 to intArrayOf(6, 34),
            7 to intArrayOf(6, 22, 38),
            8 to intArrayOf(6, 24, 42),
            9 to intArrayOf(6, 26, 46),
            10 to intArrayOf(6, 28, 50),
            11 to intArrayOf(6, 30, 54),
            12 to intArrayOf(6, 32, 58),
            13 to intArrayOf(6, 34, 62),
            14 to intArrayOf(6, 26, 46, 66),
        )

        fun decode(matrix: QrCodeMatrix): String {
            val size = matrix.size
            assertEquals(0, (size - 17) % 4)
            val version = (size - 17) / 4
            assertEquals(version, matrix.version)

            verifyFinder(matrix, 0, 0)
            verifyFinder(matrix, size - 7, 0)
            verifyFinder(matrix, 0, size - 7)
            for (i in 8 until size - 8) {
                assertEquals(i % 2 == 0, matrix.get(i, 6))
                assertEquals(i % 2 == 0, matrix.get(6, i))
            }
            assertTrue(matrix.get(8, size - 8))

            val fmtCopy1 = readFormatCopy1(matrix)
            val fmtCopy2 = readFormatCopy2(matrix)
            assertEquals(fmtCopy1, fmtCopy2)
            val unmaskedFmt = fmtCopy1 xor 0x5412
            assertEquals(0, bchRemainder(unmaskedFmt, 0x537))
            val ecLevel = (unmaskedFmt ushr 13) and 0x3
            assertEquals(0, ecLevel)
            val mask = (unmaskedFmt ushr 10) and 0x7

            if (version >= 7) {
                val ver1 = readVersionTopRight(matrix)
                val ver2 = readVersionBottomLeft(matrix)
                assertEquals(ver1, ver2)
                if (version == 7) assertEquals(0x07C94, ver1)
                assertEquals(0, bchRemainder(ver1, 0x1F25))
                assertEquals(version, ver1 ushr 12)
            }

            val functionMask = buildFunctionMask(size, ALIGN_BY_VERSION.getValue(version), version)
            val unmasked = BooleanArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val idx = y * size + x
                    val raw = matrix.get(x, y)
                    unmasked[idx] = if (functionMask[idx]) raw else raw xor maskBit(mask, x, y)
                }
            }

            val bits = ArrayList<Boolean>(size * size)
            var right = size - 1
            var upward = true
            while (right >= 1) {
                if (right == 6) right = 5
                for (step in 0 until size) {
                    val y = if (upward) size - 1 - step else step
                    for (dx in 0..1) {
                        val x = right - dx
                        if (!functionMask[y * size + x]) {
                            bits += unmasked[y * size + x]
                        }
                    }
                }
                upward = !upward
                right -= 2
            }

            val geom = LEVEL_M_BY_VERSION.getValue(version)
            val totalBlocks = geom.g1Blocks + geom.g2Blocks
            val totalDataCw = geom.g1Blocks * geom.g1Data + geom.g2Blocks * geom.g2Data
            val totalCw = totalDataCw + totalBlocks * geom.ecPerBlock
            val codewords = IntArray(totalCw) { cwIdx ->
                var v = 0
                for (b in 0 until 8) {
                    v = (v shl 1) or (if (bits[cwIdx * 8 + b]) 1 else 0)
                }
                v
            }

            val dataBlocks = Array(totalBlocks) { idx ->
                IntArray(if (idx < geom.g1Blocks) geom.g1Data else geom.g2Data)
            }
            val ecBlocks = Array(totalBlocks) { IntArray(geom.ecPerBlock) }
            var cwCursor = 0
            val maxData = maxOf(geom.g1Data, geom.g2Data)
            for (i in 0 until maxData) {
                for (b in 0 until totalBlocks) {
                    if (i < dataBlocks[b].size) {
                        dataBlocks[b][i] = codewords[cwCursor++]
                    }
                }
            }
            for (i in 0 until geom.ecPerBlock) {
                for (b in 0 until totalBlocks) {
                    ecBlocks[b][i] = codewords[cwCursor++]
                }
            }
            assertEquals(totalCw, cwCursor)

            for (b in 0 until totalBlocks) {
                verifyRsSyndromes(dataBlocks[b], ecBlocks[b])
            }

            val streamBits = ArrayList<Int>(totalDataCw * 8)
            for (block in dataBlocks) {
                for (cw in block) {
                    for (s in 7 downTo 0) streamBits += (cw ushr s) and 1
                }
            }

            var bitPos = 0
            fun readInt(width: Int): Int {
                var v = 0
                repeat(width) {
                    v = (v shl 1) or streamBits[bitPos++]
                }
                return v
            }
            val mode = readInt(4)
            assertEquals(0b0100, mode)
            val count = readInt(if (version <= 9) 8 else 16)
            val out = ByteArray(count) { readInt(8).toByte() }
            return String(out, Charsets.UTF_8)
        }

        private fun verifyFinder(matrix: QrCodeMatrix, ox: Int, oy: Int) {
            for (dy in 0..6) {
                for (dx in 0..6) {
                    val dist = maxOf(abs(dx - 3), abs(dy - 3))
                    assertEquals(dist != 2, matrix.get(ox + dx, oy + dy))
                }
            }
        }

        private fun readFormatCopy1(matrix: QrCodeMatrix): Int {
            var v = 0
            fun push(x: Int, y: Int, bitIndex: Int) {
                if (matrix.get(x, y)) v = v or (1 shl bitIndex)
            }
            for (i in 0..5) push(8, i, i)
            push(8, 7, 6)
            push(8, 8, 7)
            push(7, 8, 8)
            for (i in 9..14) push(14 - i, 8, i)
            return v
        }

        private fun readFormatCopy2(matrix: QrCodeMatrix): Int {
            var v = 0
            val s = matrix.size
            fun push(x: Int, y: Int, bitIndex: Int) {
                if (matrix.get(x, y)) v = v or (1 shl bitIndex)
            }
            for (i in 0..7) push(s - 1 - i, 8, i)
            for (i in 8..14) push(8, s - 15 + i, i)
            return v
        }

        private fun readVersionTopRight(matrix: QrCodeMatrix): Int {
            var v = 0
            val s = matrix.size
            for (i in 0 until 18) {
                val a = i / 3
                val b = s - 11 + (i % 3)
                if (matrix.get(b, a)) v = v or (1 shl i)
            }
            return v
        }

        private fun readVersionBottomLeft(matrix: QrCodeMatrix): Int {
            var v = 0
            val s = matrix.size
            for (i in 0 until 18) {
                val a = i / 3
                val b = s - 11 + (i % 3)
                if (matrix.get(a, b)) v = v or (1 shl i)
            }
            return v
        }

        private fun bchRemainder(value: Int, poly: Int): Int {
            var rem = value
            val deg = 31 - Integer.numberOfLeadingZeros(poly)
            while (rem != 0 && 31 - Integer.numberOfLeadingZeros(rem) >= deg) {
                rem = rem xor (poly shl ((31 - Integer.numberOfLeadingZeros(rem)) - deg))
            }
            return rem
        }

        private fun buildFunctionMask(size: Int, align: IntArray, version: Int): BooleanArray {
            val f = BooleanArray(size * size)
            fun mark(x: Int, y: Int) {
                if (x in 0 until size && y in 0 until size) f[y * size + x] = true
            }
            for (y in 0..8) for (x in 0..8) mark(x, y)
            for (y in 0..8) for (x in size - 8 until size) mark(x, y)
            for (y in size - 8 until size) for (x in 0..8) mark(x, y)
            for (i in 8 until size - 8) {
                mark(i, 6)
                mark(6, i)
            }
            for (cy in align) {
                for (cx in align) {
                    if ((cx <= 8 && cy <= 8) || (cx >= size - 8 && cy <= 8) || (cx <= 8 && cy >= size - 8)) continue
                    for (dy in -2..2) for (dx in -2..2) mark(cx + dx, cy + dy)
                }
            }
            if (version >= 7) {
                for (a in 0..5) for (b in size - 11..size - 9) {
                    mark(b, a)
                    mark(a, b)
                }
            }
            return f
        }

        private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> ((y / 2) + (x / 3)) % 2 == 0
            5 -> ((x * y) % 2) + ((x * y) % 3) == 0
            6 -> ((((x * y) % 2) + ((x * y) % 3)) % 2) == 0
            7 -> ((((x + y) % 2) + ((x * y) % 3)) % 2) == 0
            else -> false
        }

        private fun verifyRsSyndromes(data: IntArray, ec: IntArray) {
            val exp = IntArray(512)
            val log = IntArray(256)
            var v = 1
            for (i in 0 until 255) {
                exp[i] = v
                exp[i + 255] = v
                log[v] = i
                v = v shl 1
                if ((v and 0x100) != 0) v = v xor 0x11D
            }
            val block = data + ec
            for (rootIdx in ec.indices) {
                val root = exp[rootIdx]
                var acc = 0
                for (cw in block) {
                    acc = (if (acc == 0) 0 else exp[log[acc] + log[root]]) xor cw
                }
                assertEquals("Non-zero RS syndrome at alpha^$rootIdx", 0, acc)
            }
        }
    }
}
