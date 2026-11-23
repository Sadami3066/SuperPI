package io.github.whywood.superpi.runtime.memory

import java.text.Normalizer
import java.util.Locale

internal object MemoryTokenizer {
    private val CONNECTOR_SEPARATORS = Regex("[_+.#-]+")
    private val COMBINING_DIACRITICS = Regex("\\p{M}+")
    private val QUERY_SPLIT_PATTERN = Regex("为什么|什么|怎么|怎样|哪里|在哪|哪个|哪些|多少|谁|啥|吗|呢|吧|啊|的|了")
    private val QUERY_STOP_UNIGRAMS = setOf(
        "我", "你", "他", "她", "它", "是", "在", "有", "的", "了",
        "吗", "呢", "吧", "啊", "这", "那", "哪", "个", "很", "也",
        "都", "就", "还", "又", "只", "把", "被", "给", "和", "与",
    )
    private const val MAX_QUERY_TOKENS = 64

    fun indexText(text: String): String = tokenize(text, includeUnigramsInMultiCharRuns = true).joinToString(" ")

    fun queryTokens(text: String): List<String> =
        tokenize(text, includeUnigramsInMultiCharRuns = false, splitQueryStopWords = true).distinct()

    fun toFtsMatchClause(queries: List<String>, extraTerms: List<String> = emptyList()): String? {
        val tokens = LinkedHashSet<String>()
        for (query in queries) {
            for (token in tokenize(query, includeUnigramsInMultiCharRuns = false, splitQueryStopWords = true)) {
                tokens += token
                if (tokens.size >= MAX_QUERY_TOKENS) break
            }
            if (tokens.size >= MAX_QUERY_TOKENS) break
        }
        if (tokens.size < MAX_QUERY_TOKENS) {
            for (extra in extraTerms) {
                for (token in tokenize(extra, includeUnigramsInMultiCharRuns = false, splitQueryStopWords = false)) {
                    tokens += token
                    if (tokens.size >= MAX_QUERY_TOKENS) break
                }
                if (tokens.size >= MAX_QUERY_TOKENS) break
            }
        }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { escapeFtsToken(it) }
    }

    fun bigramJaccard(first: String, second: String): Double {
        val left = normalizedBigrams(first)
        val right = normalizedBigrams(second)
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        var intersection = 0
        for (item in left) {
            if (item in right) intersection++
        }
        val union = left.size + right.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    fun isNoSpaceScript(value: Char): Boolean =
        value in '\u4e00'..'\u9fff' ||
            value in '\u3400'..'\u4dbf' ||
            value in '\uf900'..'\ufaff' ||
            value in '\u3040'..'\u309f' ||
            value in '\u30a0'..'\u30ff' ||
            value in '\u31f0'..'\u31ff' ||
            value in '\uff66'..'\uff9f' ||
            value in '\u0e00'..'\u0e7f' ||
            value in '\u0e80'..'\u0eff' ||
            value in '\u1000'..'\u109f' ||
            value in '\u1780'..'\u17ff'

    private fun tokenize(
        text: String,
        includeUnigramsInMultiCharRuns: Boolean,
        splitQueryStopWords: Boolean = false,
    ): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (normalized.isBlank()) return emptyList()
        val tokens = ArrayList<String>()

        extractSpaceDelimitedTokens(normalized, tokens)

        val segments = if (splitQueryStopWords) QUERY_SPLIT_PATTERN.split(normalized) else listOf(normalized)
        for (segment in segments) {
            extractNoSpaceTokens(
                segment = segment,
                includeUnigramsInMultiCharRuns = includeUnigramsInMultiCharRuns,
                filterChineseStopUnigrams = splitQueryStopWords,
                out = tokens,
            )
        }
        return tokens
    }

    private fun extractSpaceDelimitedTokens(text: String, out: MutableList<String>) {
        var index = 0
        val length = text.length
        while (index < length) {
            val ch = text[index]
            if (!isSpaceScriptWordStart(ch)) {
                index++
                continue
            }
            var end = index + 1
            while (end < length && isSpaceScriptWordPart(text, end)) end++
            val raw = text.substring(index, end).trimEnd('_', '+', '.', '#', '-')
            if (raw.isNotEmpty()) {
                addWordTokenAndVariants(raw, out)
            }
            index = end
        }
    }

    private fun addWordTokenAndVariants(raw: String, out: MutableList<String>) {
        out += raw
        val folded = stripDiacritics(raw)
        if (folded != raw && folded.isNotEmpty()) {
            out += folded
        }
        for (part in raw.split(CONNECTOR_SEPARATORS)) {
            if (part.isEmpty() || part == raw) continue
            if (part.length >= 2 || part.all { it.isDigit() }) {
                out += part
                val foldedPart = stripDiacritics(part)
                if (foldedPart != part && foldedPart.isNotEmpty()) {
                    out += foldedPart
                }
            }
        }
    }

    private fun extractNoSpaceTokens(
        segment: String,
        includeUnigramsInMultiCharRuns: Boolean,
        filterChineseStopUnigrams: Boolean,
        out: MutableList<String>,
    ) {
        var index = 0
        while (index < segment.length) {
            if (!isNoSpaceScript(segment[index])) {
                index++
                continue
            }
            var end = index
            while (end < segment.length && isNoSpaceScript(segment[end])) end++
            val run = segment.substring(index, end)
            if (run.length == 1) {
                if (!filterChineseStopUnigrams || run !in QUERY_STOP_UNIGRAMS) {
                    out += run
                }
            } else {
                for (position in 0 until run.length - 1) {
                    out += run.substring(position, position + 2)
                }
                if (includeUnigramsInMultiCharRuns) {
                    for (position in run.indices) {
                        out += run[position].toString()
                    }
                }
            }
            index = end
        }
    }

    private fun isSpaceScriptWordStart(ch: Char): Boolean =
        Character.isLetterOrDigit(ch) && !isNoSpaceScript(ch)

    private fun isSpaceScriptWordPart(text: String, index: Int): Boolean {
        val ch = text[index]
        if (Character.isLetterOrDigit(ch) && !isNoSpaceScript(ch)) return true
        val type = Character.getType(ch)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) {
            return true
        }
        if (ch == '_' || ch == '+' || ch == '.' || ch == '#' || ch == '-') {
            return index + 1 < text.length && isSpaceScriptWordStart(text[index + 1])
        }
        return false
    }

    private fun stripDiacritics(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return COMBINING_DIACRITICS.replace(decomposed, "")
    }

    private fun normalizedBigrams(text: String): Set<String> {
        val normalized = stripDiacritics(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
        val clean = normalized.filter { Character.isLetterOrDigit(it) || isNoSpaceScript(it) }
        if (clean.isEmpty()) return emptySet()
        if (clean.length == 1) return setOf(clean)
        val result = HashSet<String>(clean.length)
        for (index in 0 until clean.length - 1) {
            result += clean.substring(index, index + 2)
        }
        return result
    }

    private fun escapeFtsToken(token: String): String =
        "\"" + token.replace("\"", "\"\"") + "\""
}
