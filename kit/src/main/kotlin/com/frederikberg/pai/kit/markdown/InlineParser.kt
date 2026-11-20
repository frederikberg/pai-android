package com.frederikberg.pai.kit.markdown

/**
 * Inline markdown: code spans, bold, italic, strikethrough, links and bare web addresses. A delimiter
 * with nothing to close it stays literal text, so an unmatched `*` never swallows what follows it.
 */
internal object InlineParser {
    fun parse(text: String): List<Span> = merge(parse(text, Span("")))

    private fun parse(
        text: String,
        base: Span,
    ): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                out += base.copy(text = plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    plain.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val run = runLength(text, i, '`')
                    val close = findBacktickRun(text, i + run, run)
                    if (close < 0) {
                        plain.append(text, i, i + run)
                        i += run
                    } else {
                        flush()
                        out += base.copy(text = codeContent(text.substring(i + run, close)), code = true)
                        i = close + run
                    }
                }
                c == '[' -> {
                    val link = linkAt(text, i)
                    if (link == null) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        out += parse(link.label, base.copy(link = link.url))
                        i = link.end
                    }
                }
                c == '*' || c == '_' || c == '~' -> {
                    val styled = styledAt(text, i, c)
                    if (styled == null) {
                        val run = runLength(text, i, c)
                        plain.append(text, i, i + run)
                        i += run
                    } else {
                        flush()
                        out += parse(styled.inner, styled.apply(base))
                        i = styled.end
                    }
                }
                c == 'h' && isUrlStart(text, i) -> {
                    val end = urlEnd(text, i)
                    flush()
                    out += base.copy(text = text.substring(i, end), link = text.substring(i, end))
                    i = end
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    private class Styled(
        val inner: String,
        val end: Int,
        val bold: Boolean,
        val italic: Boolean,
        val strike: Boolean,
    ) {
        fun apply(base: Span) =
            base.copy(bold = base.bold || bold, italic = base.italic || italic, strike = base.strike || strike)
    }

    private fun styledAt(
        text: String,
        start: Int,
        delimiter: Char,
    ): Styled? {
        val run = runLength(text, start, delimiter)
        val length = if (delimiter == '~') (if (run >= 2) 2 else return null) else minOf(run, 3)
        val open = start + length
        if (open >= text.length || text[open].isWhitespace()) return null
        if (delimiter == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return null
        val close = findClosing(text, open, delimiter, length) ?: return null
        val inner = text.substring(open, close)
        if (inner.isEmpty()) return null
        return Styled(
            inner = inner,
            end = close + length,
            bold = delimiter != '~' && length >= 2,
            italic = delimiter != '~' && (length == 1 || length == 3),
            strike = delimiter == '~',
        )
    }

    /** The start of the next run of exactly [length] [delimiter]s that can close what opened before [from]. */
    private fun findClosing(
        text: String,
        from: Int,
        delimiter: Char,
        length: Int,
    ): Int? {
        var j = from
        while (j < text.length) {
            val c = text[j]
            when {
                c == '\\' -> j += 2
                c == '`' -> {
                    val run = runLength(text, j, '`')
                    val close = findBacktickRun(text, j + run, run)
                    j = if (close < 0) j + run else close + run
                }
                c == delimiter -> {
                    val run = runLength(text, j, delimiter)
                    val closes =
                        run == length &&
                            j > from &&
                            !text[j - 1].isWhitespace() &&
                            (delimiter != '_' || j + run >= text.length || !text[j + run].isLetterOrDigit())
                    if (closes) return j
                    j += run
                }
                else -> j++
            }
        }
        return null
    }

    private class LinkAt(
        val label: String,
        val url: String,
        val end: Int,
    )

    private fun linkAt(
        text: String,
        start: Int,
    ): LinkAt? {
        var depth = 0
        var j = start
        while (j < text.length) {
            when (text[j]) {
                '\\' -> j++
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            j++
        }
        if (j >= text.length || j + 1 >= text.length || text[j + 1] != '(') return null
        val urlStart = j + 2
        var k = urlStart
        var parens = 0
        while (k < text.length) {
            val c = text[k]
            if (c == '(') parens++
            if (c == ')') {
                if (parens == 0) break
                parens--
            }
            if (c == '\n') return null
            k++
        }
        if (k >= text.length) return null
        val url = text.substring(urlStart, k).trim().substringBefore(' ')
        if (url.isEmpty()) return null
        return LinkAt(text.substring(start + 1, j), url, k + 1)
    }

    private fun isUrlStart(
        text: String,
        i: Int,
    ): Boolean =
        (text.startsWith("https://", i) || text.startsWith("http://", i)) &&
            (i == 0 || !text[i - 1].isLetterOrDigit()) &&
            urlEnd(text, i) > i + (if (text.startsWith("https://", i)) 8 else 7)

    private fun urlEnd(
        text: String,
        start: Int,
    ): Int {
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"") end++
        while (end > start && text[end - 1] in TRAILING_PUNCTUATION) {
            if (text[end - 1] == ')' && text.substring(start, end).count { it == '(' } >= text.substring(start, end).count { it == ')' }) break
            end--
        }
        return end
    }

    private fun runLength(
        text: String,
        start: Int,
        c: Char,
    ): Int {
        var n = 0
        while (start + n < text.length && text[start + n] == c) n++
        return n
    }

    private fun findBacktickRun(
        text: String,
        from: Int,
        length: Int,
    ): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == '`') {
                val run = runLength(text, j, '`')
                if (run == length) return j
                j += run
            } else {
                j++
            }
        }
        return -1
    }

    private fun codeContent(raw: String): String =
        if (raw.length > 1 && raw.first() == ' ' && raw.last() == ' ' && raw.isNotBlank()) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) {
                out[out.size - 1] = last.copy(text = last.text + span.text)
            } else if (span.text.isNotEmpty()) {
                out += span
            }
        }
        return out
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"
    private const val TRAILING_PUNCTUATION = ".,;:!?)'\""
}
