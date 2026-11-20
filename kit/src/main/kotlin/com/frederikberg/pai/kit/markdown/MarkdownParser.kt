package com.frederikberg.pai.kit.markdown

/**
 * Block-level markdown, parsed line by line into [MarkdownBlock]s a renderer can lay out without a
 * web view or any asynchronous work. A single newline inside a paragraph is a line break, the way the
 * web client draws it. Tables are kept as monospace lines, math as the text it was written as, and
 * raw HTML as plain text.
 */
object MarkdownParser {
    fun parse(source: String): List<MarkdownBlock> = blocks(source.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

    private val HEADING = Regex("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")
    private val RULE = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val FENCE = Regex("^( {0,3})(`{3,}|~{3,})[ \\t]*([^`]*)$")
    private val LIST_ITEM = Regex("^( *)([-*+]|\\d{1,9}[.)])([ \\t]+)(.*)$")
    private val TABLE_SEPARATOR = Regex("^[ \\t]*\\|?[ \\t]*:?-{2,}:?[ \\t]*(\\|[ \\t]*:?-{2,}:?[ \\t]*)*\\|?[ \\t]*$")

    private fun blocks(lines: List<String>): List<MarkdownBlock> {
        val out = ArrayList<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i++
                FENCE.matches(line) -> i = fenced(lines, i, out)
                HEADING.matches(line) -> {
                    val match = HEADING.matchEntire(line)!!
                    out += MarkdownBlock.Heading(match.groupValues[1].length, InlineParser.parse(match.groupValues[2]))
                    i++
                }
                RULE.matches(line) -> {
                    out += MarkdownBlock.Rule
                    i++
                }
                line.trimStart().startsWith(">") -> i = quote(lines, i, out)
                isTableStart(lines, i) -> i = table(lines, i, out)
                LIST_ITEM.matches(line) -> i = list(lines, i, out)
                else -> i = paragraph(lines, i, out)
            }
        }
        return out
    }

    private fun fenced(
        lines: List<String>,
        start: Int,
        out: MutableList<MarkdownBlock>,
    ): Int {
        val open = FENCE.matchEntire(lines[start])!!
        val indent = open.groupValues[1].length
        val fence = open.groupValues[2]
        val language = open.groupValues[3].trim().substringBefore(' ').ifEmpty { null }
        val body = ArrayList<String>()
        var i = start + 1
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isNotEmpty() && trimmed.all { it == fence[0] } && trimmed.length >= fence.length) {
                i++
                break
            }
            body += lines[i].let { line -> line.drop(minOf(indent, line.length - line.trimStart().length)) }
            i++
        }
        out += MarkdownBlock.Code(language, body.joinToString("\n"))
        return i
    }

    private fun quote(
        lines: List<String>,
        start: Int,
        out: MutableList<MarkdownBlock>,
    ): Int {
        val inner = ArrayList<String>()
        var i = start
        while (i < lines.size && lines[i].trimStart().startsWith(">")) {
            inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
            i++
        }
        out += MarkdownBlock.Quote(blocks(inner))
        return i
    }

    private fun isTableStart(
        lines: List<String>,
        i: Int,
    ): Boolean =
        i + 1 < lines.size &&
            '|' in lines[i] &&
            TABLE_SEPARATOR.matches(lines[i + 1]) &&
            '-' in lines[i + 1]

    private fun table(
        lines: List<String>,
        start: Int,
        out: MutableList<MarkdownBlock>,
    ): Int {
        var i = start
        val rows = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) {
            rows += lines[i].trimEnd()
            i++
        }
        out += MarkdownBlock.Table(rows)
        return i
    }

    private fun list(
        lines: List<String>,
        start: Int,
        out: MutableList<MarkdownBlock>,
    ): Int {
        val first = LIST_ITEM.matchEntire(lines[start])!!
        val baseIndent = first.groupValues[1].length
        val ordered = first.groupValues[2].first().isDigit()
        val startNumber = if (ordered) first.groupValues[2].dropLast(1).toInt() else 1
        val items = ArrayList<List<MarkdownBlock>>()
        var i = start
        while (i < lines.size) {
            val item = LIST_ITEM.matchEntire(lines[i]) ?: break
            val indent = item.groupValues[1].length
            if (indent != baseIndent || item.groupValues[2].first().isDigit() != ordered) break
            val contentIndent = indent + item.groupValues[2].length + item.groupValues[3].length.coerceAtMost(4)
            val body = arrayListOf(item.groupValues[4])
            i++
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) {
                    // A blank line continues the item only when what follows is indented under it.
                    val following = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                    if (following != null && leadingSpaces(following) >= contentIndent) {
                        body += ""
                        i++
                        continue
                    }
                    break
                }
                val nextIndent = leadingSpaces(next)
                if (nextIndent >= contentIndent) {
                    body += next.drop(contentIndent)
                    i++
                } else if (LIST_ITEM.matches(next) || startsBlock(lines, i)) {
                    break
                } else if (body.last().isNotBlank()) {
                    body += next.trimStart()
                    i++
                } else {
                    break
                }
            }
            items += blocks(body)
            // Blank lines between items of one list do not end it.
            var peek = i
            while (peek < lines.size && lines[peek].isBlank()) peek++
            val nextItem = lines.getOrNull(peek)?.let { LIST_ITEM.matchEntire(it) }
            if (nextItem != null && nextItem.groupValues[1].length == baseIndent) i = peek
        }
        out += MarkdownBlock.ListBlock(ordered, startNumber, items)
        return i
    }

    private fun paragraph(
        lines: List<String>,
        start: Int,
        out: MutableList<MarkdownBlock>,
    ): Int {
        val body = ArrayList<String>()
        var i = start
        while (i < lines.size && lines[i].isNotBlank() && (i == start || !interruptsParagraph(lines, i))) {
            body += lines[i].trim()
            i++
        }
        out += MarkdownBlock.Paragraph(InlineParser.parse(body.joinToString("\n")))
        return i
    }

    /** A bullet, or a numbered item that starts at 1, may begin in the middle of a paragraph. */
    private fun interruptsParagraph(
        lines: List<String>,
        i: Int,
    ): Boolean {
        if (startsBlock(lines, i)) return true
        val item = LIST_ITEM.matchEntire(lines[i]) ?: return false
        val marker = item.groupValues[2]
        return !marker.first().isDigit() || marker.dropLast(1).toInt() == 1
    }

    private fun startsBlock(
        lines: List<String>,
        i: Int,
    ): Boolean {
        val line = lines[i]
        return FENCE.matches(line) ||
            HEADING.matches(line) ||
            RULE.matches(line) ||
            line.trimStart().startsWith(">") ||
            isTableStart(lines, i)
    }

    private fun leadingSpaces(line: String): Int = line.length - line.trimStart(' ').length
}
