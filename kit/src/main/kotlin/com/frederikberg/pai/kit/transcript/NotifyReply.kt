package com.frederikberg.pai.kit.transcript

/**
 * A `notify` tool's own reply read as the notification it describes: the reply is block-style YAML
 * whose `title` and `body` lines are plain, single-quoted or double-quoted scalars, possibly folded
 * onto indented continuation lines. This is not a YAML parser; anything it cannot reconstruct
 * losslessly (an unterminated quote, an escape it does not know) is `null`, and the caller shows the
 * reply as an ordinary tool result.
 */
object NotifyReply {
    data class Notice(
        val title: String,
        val body: String,
    )

    fun parse(content: String): Notice? {
        val title = scalar("title", content) ?: return null
        val body = scalar("body", content) ?: return null
        return Notice(title, body)
    }

    private fun scalar(
        key: String,
        content: String,
    ): String? {
        val match = Regex("^${Regex.escape(key)}: (.*)$", RegexOption.MULTILINE).find(content) ?: return null
        val line = match.groupValues[1]
        val valueStart = match.range.last + 1 - line.length
        if (line.isEmpty()) return ""
        return when (content[valueStart]) {
            '\'' -> singleQuoted(content, valueStart + 1)
            '"' -> doubleQuoted(content, valueStart + 1)
            else -> plain(content, line, valueStart + line.length)
        }
    }

    private fun plain(
        content: String,
        firstLine: String,
        lineEnd: Int,
    ): String {
        val out = StringBuilder(firstLine)
        var index = lineEnd
        while (index < content.length && content[index] == '\n') {
            val afterBreak = index + 1
            if (afterBreak >= content.length || (content[afterBreak] != ' ' && content[afterBreak] != '\t')) break
            var cursor = afterBreak
            while (cursor < content.length && (content[cursor] == ' ' || content[cursor] == '\t')) cursor++
            val lineStart = cursor
            while (cursor < content.length && content[cursor] != '\n') cursor++
            out.append(' ').append(content, lineStart, cursor)
            index = cursor
        }
        return out.toString()
    }

    private fun singleQuoted(
        content: String,
        start: Int,
    ): String? {
        val out = StringBuilder()
        var index = start
        while (index < content.length) {
            val c = content[index]
            when {
                c == '\'' && content.getOrNull(index + 1) == '\'' -> {
                    out.append('\'')
                    index += 2
                }
                c == '\'' -> return out.toString()
                c == '\n' -> index = fold(content, index, out)
                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return null
    }

    private val ESCAPES =
        mapOf(
            '0' to "\u0000", 'a' to "\u0007", 'b' to "\b", 't' to "\t", 'n' to "\n", 'v' to "\u000B",
            'f' to "\u000C", 'r' to "\r", 'e' to "\u001B", ' ' to " ", '"' to "\"", '\\' to "\\",
            '/' to "/", 'N' to "\u0085", '_' to " ", 'L' to " ", 'P' to " ",
        )
    private val HEX_LENGTHS = mapOf('x' to 2, 'u' to 4, 'U' to 8)

    private fun doubleQuoted(
        content: String,
        start: Int,
    ): String? {
        val out = StringBuilder()
        var index = start
        while (index < content.length) {
            val c = content[index]
            if (c == '"') return out.toString()
            if (c == '\n') {
                index = fold(content, index, out)
            } else if (c != '\\') {
                out.append(c)
                index++
            } else {
                val escapeStart = index + 1
                if (escapeStart >= content.length) return null
                val e = content[escapeStart]
                when {
                    e == '\n' -> {
                        var cursor = escapeStart + 1
                        while (cursor < content.length && (content[cursor] == ' ' || content[cursor] == '\t')) cursor++
                        index = cursor
                    }
                    e in ESCAPES -> {
                        out.append(ESCAPES.getValue(e))
                        index = escapeStart + 1
                    }
                    e in HEX_LENGTHS -> {
                        val length = HEX_LENGTHS.getValue(e)
                        val hex = content.substring(escapeStart + 1, minOf(content.length, escapeStart + 1 + length))
                        val code = hex.takeIf { it.length == length }?.toIntOrNull(16) ?: return null
                        if (code > 0x10FFFF) return null
                        out.appendCodePoint(code)
                        index = escapeStart + 1 + length
                    }
                    else -> return null
                }
            }
        }
        return null
    }

    /** A run of line breaks plus the next line's indentation: one break is a space, `k` breaks are `k - 1` newlines. */
    private fun fold(
        content: String,
        index: Int,
        out: StringBuilder,
    ): Int {
        var cursor = index
        var breaks = 0
        while (cursor < content.length && content[cursor] == '\n') {
            breaks++
            cursor++
            while (cursor < content.length && (content[cursor] == ' ' || content[cursor] == '\t')) cursor++
        }
        out.append(if (breaks == 1) " " else "\n".repeat(breaks - 1))
        return cursor
    }
}
