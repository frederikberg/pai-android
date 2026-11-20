package com.frederikberg.pai.kit.transcript

/**
 * How much of a body a row shows. Rows hold arbitrary text, so each is bounded twice: its source is
 * sliced before anything is laid out ([Bounded.of]), and the screen clips what is drawn to a number of
 * visual lines. A slice alone does nothing for height, since one source line wraps to several visual
 * ones on a phone; a clip alone lays out text only to throw it away.
 *
 * Whatever is hidden is reachable: the row offers the full text in its own screen.
 */
object RowBounds {
    /** A reply, the one thing a reader should not have to open to finish, shows this many visual lines. */
    const val PROSE_LINES = 40

    /** A message the person sent. */
    const val USER_LINES = 20

    /** A card's body once the reader has opened it. */
    const val CARD_LINES = 24

    /** Most visual lines a source line is credited with when slicing; wider screens than any phone's. */
    const val MAX_CHARS_PER_VISUAL_LINE = 200

    /** A picture inside a reply or a message is drawn in a box of this height, loaded or not. */
    const val PICTURE_HEIGHT_DP = 160
}

/** A body cut to what a row can show; [cut] is its own fact because the visual clip cannot be inferred from the text left. */
data class Bounded(
    val shown: String,
    val cut: Boolean,
) {
    companion object {
        /** The first [lines] source lines of [text], and no more than [lines] full-width visual lines of characters. */
        fun of(
            text: String,
            lines: Int,
        ): Bounded {
            val charLimit = lines * RowBounds.MAX_CHARS_PER_VISUAL_LINE
            var position = -1
            var seen = 0
            while (seen < lines) {
                position = text.indexOf('\n', position + 1)
                if (position < 0) break
                seen++
            }
            val byLines = if (position < 0) text else text.substring(0, position)
            val sliced = if (byLines.length > charLimit) byLines.substring(0, charLimit) else byLines
            return Bounded(sliced, cut = sliced.length < text.length && text.substring(sliced.length).isNotBlank())
        }
    }
}
