package com.frederikberg.pai.kit.markdown

/** One run of text inside a paragraph with the one style it carries; [link] is the target of a link. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** A piece of a message the renderer draws as one unit. Math is not interpreted: it stays the text it was written as. */
sealed interface MarkdownBlock {
    data class Paragraph(
        val spans: List<Span>,
    ) : MarkdownBlock

    data class Heading(
        val level: Int,
        val spans: List<Span>,
    ) : MarkdownBlock

    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<List<MarkdownBlock>>,
    ) : MarkdownBlock

    data class Quote(
        val blocks: List<MarkdownBlock>,
    ) : MarkdownBlock

    /** A fenced code block; never wrapped, so it scrolls sideways. */
    data class Code(
        val language: String?,
        val text: String,
    ) : MarkdownBlock

    /** A table drawn as monospace text, one source line per entry, never wrapped. */
    data class Table(
        val lines: List<String>,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock
}
