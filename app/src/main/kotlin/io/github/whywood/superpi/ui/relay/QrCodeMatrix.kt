package io.github.whywood.superpi.ui.relay

import android.graphics.Bitmap
import kotlin.math.abs

internal class QrCodeMatrix private constructor(
    val version: Int,
    val size: Int,
    private val modules: BooleanArray,
) {
    fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    fun toPixels(modulePx: Int = 8, quietZoneModules: Int = 4): Pair<Int, IntArray> {
        val totalModules = size + quietZoneModules * 2
        val dim = totalModules * modulePx
        val pixels = IntArray(dim * dim) { WHITE }
        for (y in 0 until size) {
            val rowBase = (y + quietZoneModules) * modulePx
            for (x in 0 until size) {
                if (!get(x, y)) continue
                val colBase = (x + quietZoneModules) * modulePx
                for (dy in 0 until modulePx) {
                    val rowOffset = (rowBase + dy) * dim + colBase
                    pixels.fill(BLACK, rowOffset, rowOffset + modulePx)
                }
            }
        }
        return dim to pixels
    }

    fun toBitmap(modulePx: Int = 8, quietZoneModules: Int = 4): Bitmap {
        val (dim, pixels) = toPixels(modulePx, quietZoneModules)
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
        private const val ECC_FORMAT_BITS_M = 0b00
        private const val FORMAT_MASK = 0x5412
        private const val FORMAT_POLY = 0x537
        private const val VERSION_POLY = 0x1F25

        private data class SpecM(
            val totalDataCodewords: Int,
            val ecCodewordsPerBlock: Int,
            val group1Blocks: Int,
            val group1DataCw: Int,
            val group2Blocks: Int,
            val group2DataCw: Int,
            val remainderBits: Int,
            val alignCenters: IntArray,
        )

        private val SPECS_M = arrayOf(
            SpecM(16, 10, 1, 16, 0, 0, 0, intArrayOf()),
            SpecM(28, 16, 1, 28, 0, 0, 7, intArrayOf(6, 18)),
            SpecM(44, 26, 1, 44, 0, 0, 7, intArrayOf(6, 22)),
            SpecM(64, 18, 2, 32, 0, 0, 7, intArrayOf(6, 26)),
            SpecM(86, 24, 2, 43, 0, 0, 7, intArrayOf(6, 30)),
            SpecM(108, 16, 4, 27, 0, 0, 7, intArrayOf(6, 34)),
            SpecM(124, 18, 4, 31, 0, 0, 0, intArrayOf(6, 22, 38)),
            SpecM(154, 22, 2, 38, 2, 39, 0, intArrayOf(6, 24, 42)),
            SpecM(182, 22, 3, 36, 2, 37, 0, intArrayOf(6, 26, 46)),
            SpecM(216, 26, 4, 43, 1, 44, 0, intArrayOf(6, 28, 50)),
            SpecM(254, 30, 1, 50, 4, 51, 0, intArrayOf(6, 30, 54)),
            SpecM(290, 22, 6, 36, 2, 37, 0, intArrayOf(6, 32, 58)),
            SpecM(334, 22, 8, 37, 1, 38, 0, intArrayOf(6, 34, 62)),
            SpecM(365, 24, 4, 40, 5, 41, 3, intArrayOf(6, 26, 46, 66)),
        )

        private val GF_EXP = IntArray(512).also { exp ->
            var v = 1
            for (i in 0 until 255) {
                exp[i] = v
                exp[i + 255] = v
                v = v shl 1
                if ((v and 0x100) != 0) v = v xor 0x11D
            }
            exp[510] = exp[0]
            exp[511] = exp[1]
        }

        private val GF_LOG = IntArray(256).also { log ->
            for (i in 0 until 255) {
                log[GF_EXP[i]] = i
            }
        }

        fun encode(text: String): QrCodeMatrix? {
            val payload = text.toByteArray(Charsets.UTF_8)
            val version = selectVersion(payload.size) ?: return null
            val spec = SPECS_M[version - 1]
            val dataCodewords = encodeByteDataCodewords(payload, version, spec.totalDataCodewords)
            val finalBits = interleaveAndAppendEc(dataCodewords, spec)
            val size = version * 4 + 17
            val modules = BooleanArray(size * size)
            val isFunction = BooleanArray(size * size)
            drawFunctionPatterns(version, size, spec.alignCenters, modules, isFunction)
            drawDataBits(size, finalBits, modules, isFunction)
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            val candidate = modules.copyOf()
            for (mask in 0 until 8) {
                modules.copyInto(candidate)
                applyMask(size, candidate, isFunction, mask)
                drawFormatBits(size, candidate, isFunction, mask)
                if (version >= 7) drawVersionBits(version, size, candidate, isFunction)
                val penalty = penaltyScore(size, candidate)
                if (penalty < bestPenalty) {
                    bestPenalty = penalty
                    bestMask = mask
                }
            }
            applyMask(size, modules, isFunction, bestMask)
            drawFormatBits(size, modules, isFunction, bestMask)
            if (version >= 7) drawVersionBits(version, size, modules, isFunction)
            return QrCodeMatrix(version = version, size = size, modules = modules)
        }

        private fun selectVersion(byteCount: Int): Int? {
            for (idx in SPECS_M.indices) {
                val version = idx + 1
                val countBits = if (version <= 9) 8 else 16
                val headerBits = 4 + countBits
                val capacityBits = SPECS_M[idx].totalDataCodewords * 8
                if (headerBits + byteCount * 8 <= capacityBits) return version
            }
            return null
        }

        private fun encodeByteDataCodewords(
            payload: ByteArray,
            version: Int,
            totalDataCw: Int,
        ): ByteArray {
            val totalBits = totalDataCw * 8
            val buffer = BooleanArray(totalBits)
            var pos = 0
            fun writeBits(value: Int, width: Int) {
                for (shift in width - 1 downTo 0) {
                    buffer[pos++] = ((value ushr shift) and 1) != 0
                }
            }
            writeBits(0b0100, 4)
            writeBits(payload.size, if (version <= 9) 8 else 16)
            for (b in payload) {
                writeBits(b.toInt() and 0xFF, 8)
            }
            val terminatorBits = minOf(4, totalBits - pos)
            pos += terminatorBits
            val padToByte = (8 - (pos and 7)) and 7
            pos += padToByte
            val out = ByteArray(totalDataCw)
            for (i in 0 until pos / 8) {
                var byteVal = 0
                for (bit in 0 until 8) {
                    if (buffer[i * 8 + bit]) byteVal = byteVal or (1 shl (7 - bit))
                }
                out[i] = byteVal.toByte()
            }
            var toggle = true
            for (i in pos / 8 until totalDataCw) {
                out[i] = (if (toggle) 0xEC else 0x11).toByte()
                toggle = !toggle
            }
            return out
        }

        private fun interleaveAndAppendEc(dataCodewords: ByteArray, spec: SpecM): BooleanArray {
            val totalBlocks = spec.group1Blocks + spec.group2Blocks
            val dataBlocks = ArrayList<ByteArray>(totalBlocks)
            val ecBlocks = ArrayList<ByteArray>(totalBlocks)
            val generator = rsGenerator(spec.ecCodewordsPerBlock)
            var cursor = 0
            for (b in 0 until totalBlocks) {
                val dataLen = if (b < spec.group1Blocks) spec.group1DataCw else spec.group2DataCw
                val block = dataCodewords.copyOfRange(cursor, cursor + dataLen)
                cursor += dataLen
                dataBlocks += block
                ecBlocks += rsComputeRemainder(block, generator, spec.ecCodewordsPerBlock)
            }
            val maxDataLen = maxOf(spec.group1DataCw, spec.group2DataCw)
            val interleaved = ArrayList<Byte>(spec.totalDataCodewords + totalBlocks * spec.ecCodewordsPerBlock)
            for (i in 0 until maxDataLen) {
                for (block in dataBlocks) {
                    if (i < block.size) interleaved += block[i]
                }
            }
            for (i in 0 until spec.ecCodewordsPerBlock) {
                for (ec in ecBlocks) {
                    interleaved += ec[i]
                }
            }
            val bits = BooleanArray(interleaved.size * 8 + spec.remainderBits)
            var bitIdx = 0
            for (cw in interleaved) {
                val unsigned = cw.toInt() and 0xFF
                for (shift in 7 downTo 0) {
                    bits[bitIdx++] = ((unsigned ushr shift) and 1) != 0
                }
            }
            return bits
        }

        private fun rsGenerator(degree: Int): IntArray {
            var poly = intArrayOf(1)
            for (i in 0 until degree) {
                val root = GF_EXP[i]
                val next = IntArray(poly.size + 1)
                for (j in poly.indices) {
                    next[j] = next[j] xor poly[j]
                    next[j + 1] = next[j + 1] xor gfMul(poly[j], root)
                }
                poly = next
            }
            return poly
        }

        private fun rsComputeRemainder(data: ByteArray, generator: IntArray, ecCount: Int): ByteArray {
            val work = IntArray(data.size + ecCount)
            for (i in data.indices) {
                work[i] = data[i].toInt() and 0xFF
            }
            for (i in data.indices) {
                val factor = work[i]
                if (factor == 0) continue
                for (j in 1 until generator.size) {
                    work[i + j] = work[i + j] xor gfMul(generator[j], factor)
                }
            }
            return ByteArray(ecCount) { idx -> work[data.size + idx].toByte() }
        }

        private fun gfMul(a: Int, b: Int): Int {
            if (a == 0 || b == 0) return 0
            return GF_EXP[GF_LOG[a] + GF_LOG[b]]
        }

        private fun drawFunctionPatterns(
            version: Int,
            size: Int,
            alignCenters: IntArray,
            modules: BooleanArray,
            isFunction: BooleanArray,
        ) {
            fun setFunc(x: Int, y: Int, dark: Boolean) {
                val idx = y * size + x
                modules[idx] = dark
                isFunction[idx] = true
            }

            fun drawFinder(ox: Int, oy: Int) {
                for (dy in -1..7) {
                    for (dx in -1..7) {
                        val x = ox + dx
                        val y = oy + dy
                        if (x !in 0 until size || y !in 0 until size) continue
                        val dist = maxOf(abs(dx - 3), abs(dy - 3))
                        val dark = dist == 0 || dist == 1 || dist == 3
                        setFunc(x, y, dark)
                    }
                }
            }

            drawFinder(0, 0)
            drawFinder(size - 7, 0)
            drawFinder(0, size - 7)

            for (i in 8 until size - 8) {
                val dark = i % 2 == 0
                setFunc(i, 6, dark)
                setFunc(6, i, dark)
            }

            for (cy in alignCenters) {
                for (cx in alignCenters) {
                    val inTopLeft = cx <= 8 && cy <= 8
                    val inTopRight = cx >= size - 8 && cy <= 8
                    val inBottomLeft = cx <= 8 && cy >= size - 8
                    if (inTopLeft || inTopRight || inBottomLeft) continue
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val dist = maxOf(abs(dx), abs(dy))
                            setFunc(cx + dx, cy + dy, dist != 1)
                        }
                    }
                }
            }

            setFunc(8, size - 8, true)

            drawFormatBits(size, modules, isFunction, 0)
            if (version >= 7) {
                drawVersionBits(version, size, modules, isFunction)
            }
        }

        private fun drawFormatBits(
            size: Int,
            modules: BooleanArray,
            isFunction: BooleanArray,
            mask: Int,
        ) {
            val data = (ECC_FORMAT_BITS_M shl 3) or mask
            val bits = ((data shl 10) or bchRemainder(data shl 10, FORMAT_POLY, 10)) xor FORMAT_MASK
            fun setFmt(x: Int, y: Int, bitIndex: Int) {
                val idx = y * size + x
                modules[idx] = ((bits ushr bitIndex) and 1) != 0
                isFunction[idx] = true
            }
            for (i in 0..5) setFmt(8, i, i)
            setFmt(8, 7, 6)
            setFmt(8, 8, 7)
            setFmt(7, 8, 8)
            for (i in 9..14) setFmt(14 - i, 8, i)

            for (i in 0..7) setFmt(size - 1 - i, 8, i)
            for (i in 8..14) setFmt(8, size - 15 + i, i)
        }

        private fun drawVersionBits(
            version: Int,
            size: Int,
            modules: BooleanArray,
            isFunction: BooleanArray,
        ) {
            val bits = (version shl 12) or bchRemainder(version shl 12, VERSION_POLY, 12)
            for (i in 0 until 18) {
                val dark = ((bits ushr i) and 1) != 0
                val a = i / 3
                val b = size - 11 + (i % 3)
                val tr = a * size + b
                modules[tr] = dark
                isFunction[tr] = true
                val bl = b * size + a
                modules[bl] = dark
                isFunction[bl] = true
            }
        }

        private fun bchRemainder(value: Int, poly: Int, degree: Int): Int {
            var rem = value
            val polyTop = 31 - Integer.numberOfLeadingZeros(poly)
            while (rem != 0 && 31 - Integer.numberOfLeadingZeros(rem) >= polyTop) {
                val shift = (31 - Integer.numberOfLeadingZeros(rem)) - polyTop
                rem = rem xor (poly shl shift)
            }
            return rem and ((1 shl degree) - 1)
        }

        private fun drawDataBits(
            size: Int,
            bits: BooleanArray,
            modules: BooleanArray,
            isFunction: BooleanArray,
        ) {
            var bitIdx = 0
            var upward = true
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (step in 0 until size) {
                    val y = if (upward) size - 1 - step else step
                    for (dx in 0..1) {
                        val x = right - dx
                        val idx = y * size + x
                        if (!isFunction[idx]) {
                            modules[idx] = bitIdx < bits.size && bits[bitIdx++]
                        }
                    }
                }
                upward = !upward
                right -= 2
            }
        }

        private fun applyMask(
            size: Int,
            modules: BooleanArray,
            isFunction: BooleanArray,
            mask: Int,
        ) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val idx = y * size + x
                    if (isFunction[idx]) continue
                    val invert = when (mask) {
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
                    if (invert) modules[idx] = !modules[idx]
                }
            }
        }

        private fun penaltyScore(size: Int, modules: BooleanArray): Int {
            var score = 0
            for (y in 0 until size) {
                var runColor = modules[y * size]
                var runLen = 1
                var finderHist = 0
                for (x in 0 until size) {
                    val dark = modules[y * size + x]
                    if (x > 0) {
                        if (dark == runColor) {
                            runLen++
                        } else {
                            if (runLen >= 5) score += 3 + (runLen - 5)
                            runColor = dark
                            runLen = 1
                        }
                    }
                    finderHist = ((finderHist shl 1) or (if (dark) 1 else 0)) and 0x7FF
                    if (x >= 10 && (finderHist == 0b10111010000 || finderHist == 0b00001011101)) {
                        score += 40
                    }
                }
                if (runLen >= 5) score += 3 + (runLen - 5)
            }
            for (x in 0 until size) {
                var runColor = modules[x]
                var runLen = 1
                var finderHist = 0
                for (y in 0 until size) {
                    val dark = modules[y * size + x]
                    if (y > 0) {
                        if (dark == runColor) {
                            runLen++
                        } else {
                            if (runLen >= 5) score += 3 + (runLen - 5)
                            runColor = dark
                            runLen = 1
                        }
                    }
                    finderHist = ((finderHist shl 1) or (if (dark) 1 else 0)) and 0x7FF
                    if (y >= 10 && (finderHist == 0b10111010000 || finderHist == 0b00001011101)) {
                        score += 40
                    }
                }
                if (runLen >= 5) score += 3 + (runLen - 5)
            }
            var darkCount = 0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (modules[y * size + x]) darkCount++
                    if (y + 1 < size && x + 1 < size) {
                        val c = modules[y * size + x]
                        if (modules[y * size + x + 1] == c &&
                            modules[(y + 1) * size + x] == c &&
                            modules[(y + 1) * size + x + 1] == c
                        ) {
                            score += 3
                        }
                    }
                }
            }
            val total = size * size
            val k = (abs(darkCount * 20 - total * 10) + total - 1) / total
            if (k > 0) score += (k - 1) * 10
            return score
        }
    }
}
