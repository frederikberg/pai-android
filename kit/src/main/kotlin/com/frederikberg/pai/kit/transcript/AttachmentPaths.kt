package com.frederikberg.pai.kit.transcript

/** A file a message carries, referenced by its path on the session's machine until it is fetched. */
data class AttachmentRef(
    val path: String,
    val filename: String,
    /** Drawn as a picture where the phone can decode it; any other file is a chip. */
    val isImage: Boolean,
)

/**
 * The attachment paths a message carries in its own text: the server appends the attached files after
 * a blank line, optionally behind a label. What makes a token an attachment is where it points (one
 * file inside one session directory under `.claude/attachments/`), never what it is called.
 */
object AttachmentPaths {
    private val ATTACHMENT_PATH = Regex("\\.claude/attachments/[^/\\s]+/[^/\\s]+$")
    private const val LABEL = "Attachments referenced in this message:"
    private val PICTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

    data class Parsed(
        val text: String,
        val paths: List<String>,
    )

    fun extract(content: String): Parsed {
        val trimmed = content.trim()
        val lastBreak = trimmed.lastIndexOf("\n\n")
        var candidate = if (lastBreak < 0) trimmed else trimmed.substring(lastBreak + 2).trim()
        if (candidate.startsWith("$LABEL\n")) candidate = candidate.removePrefix(LABEL)
        val tokens = candidate.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || !tokens.all { ATTACHMENT_PATH.containsMatchIn(it) }) return Parsed(trimmed, emptyList())
        val text = if (lastBreak < 0) "" else trimmed.substring(0, lastBreak).trim()
        return Parsed(text, tokens)
    }

    fun filename(path: String): String = path.substringAfterLast('/')

    /** The types the phone draws in place; HEIC is left to its chip because older Android versions cannot decode it. */
    fun isPicture(path: String): Boolean = filename(path).substringAfterLast('.', "").lowercase() in PICTURE_EXTENSIONS

    fun ref(path: String) = AttachmentRef(path, filename(path), isPicture(path))
}
