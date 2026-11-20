package com.frederikberg.pai.kit.markdown

/**
 * `pai-file: /path` lines in a reply: an agent writes one on a line of its own to show a file it made.
 * The reply is cut at those lines, so a picture or file chip is drawn where the line stood. A marker
 * inside a fenced code block stays text, so a reply can show the marker itself, and a path named twice
 * is drawn twice.
 */
object FileMarkers {
    sealed interface Segment {
        data class Text(
            val text: String,
        ) : Segment

        data class File(
            val path: String,
        ) : Segment
    }

    private val MARKER = Regex("^pai-file: (/\\S.*)$")
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})")

    fun split(text: String): List<Segment> {
        if (!text.contains("pai-file: ")) return listOf(Segment.Text(text))
        val out = ArrayList<Segment>()
        val pending = ArrayList<String>()
        var fence: String? = null

        fun flush() {
            if (pending.isNotEmpty()) out += Segment.Text(pending.joinToString("\n"))
            pending.clear()
        }
        for (line in text.split('\n')) {
            val fenceMatch = FENCE.find(line)
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                fence =
                    when {
                        fence == null -> marker
                        marker[0] == fence[0] && marker.length >= fence.length -> null
                        else -> fence
                    }
            }
            val path = if (fence == null && fenceMatch == null) MARKER.find(line)?.groupValues?.get(1)?.trimEnd() else null
            if (path != null) {
                flush()
                out += Segment.File(path)
            } else {
                pending += line
            }
        }
        flush()
        return out
    }
}
