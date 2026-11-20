package com.frederikberg.pai.kit.transcript

import com.frederikberg.pai.contract.Message
import com.frederikberg.pai.contract.MessageType
import com.frederikberg.pai.kit.markdown.MarkdownBlock
import com.frederikberg.pai.kit.markdown.MarkdownParser

/** One screenful-sized piece of a body too long to live in the transcript. */
sealed interface FullTextItem {
    data class Markdown(
        val block: MarkdownBlock,
    ) : FullTextItem

    data class Picture(
        val path: String,
    ) : FullTextItem

    data class FileChip(
        val path: String,
    ) : FullTextItem

    /** A run of plain lines, monospace and never wrapped. */
    data class Mono(
        val text: String,
    ) : FullTextItem
}

/**
 * The whole of what a row only shows part of. It is cut into items so the screen that draws it is a
 * list too: a megabyte of tool output is a few thousand items, never one text.
 */
data class FullText(
    val title: String,
    val items: List<FullTextItem>,
) {
    companion object {
        const val LINES_PER_CHUNK = 60
        const val CHARS_PER_CHUNK = 6_000

        /** The full body of the row [rowKey] of [message], or null when the key names nothing of it. */
        fun of(
            message: Message,
            rowKey: String,
        ): FullText? {
            val part = rowKey.removePrefix("m${message.id}").removePrefix(":")
            return when {
                part == "prose" -> message.content?.let { FullText("Reply", prose(it)) }
                part == "thinking" -> message.thinking?.let { FullText("Thinking", mono(it)) }
                part.startsWith("call:") ->
                    message.toolCalls?.firstOrNull { it.id == part.removePrefix("call:") }?.let { call ->
                        val view = ToolCards.view(call)
                        FullText(ToolText.toolName(call.name), mono(view.body ?: view.headline))
                    }
                part.startsWith("diff:") -> diff(message, part.removePrefix("diff:").toIntOrNull())
                part.isEmpty() -> whole(message)
                else -> null
            }
        }

        private fun whole(message: Message): FullText? =
            when (message.type) {
                MessageType.User -> {
                    val text = AttachmentPaths.extract(message.content.orEmpty()).text
                    FullText(MessageRouting.commandParts(message.content.orEmpty()).first.takeIf { message.subtype == "command" } ?: "Message", mono(text))
                }
                MessageType.ToolResult -> message.toolResult?.let { FullText("Result", mono(ToolText.stripAnsi(it.content))) }
                MessageType.System ->
                    FullText(MessageRouting.systemLabel(message.subtype, message.content), mono(ToolText.stripAnsi(message.content.orEmpty())))
                else -> null
            }

        private fun diff(
            message: Message,
            index: Int?,
        ): FullText? {
            val file = index?.let { message.toolResult?.bashEditDiff?.files?.getOrNull(it) } ?: return null
            return FullText(ToolText.abbreviatingHome(file.filePath), mono(file.hunks.joinToString("\n") { it.lines.joinToString("\n") }))
        }

        fun prose(text: String): List<FullTextItem> =
            MessageRouting.segments(text).flatMap { segment ->
                when (segment) {
                    is ReplySegment.Markdown -> segment.blocks.flatMap(::items)
                    is ReplySegment.Picture -> listOf(FullTextItem.Picture(segment.path))
                    is ReplySegment.FileChip -> listOf(FullTextItem.FileChip(segment.path))
                }
            }

        private fun items(block: MarkdownBlock): List<FullTextItem> =
            when (block) {
                is MarkdownBlock.Code -> mono(block.text)
                is MarkdownBlock.Table -> mono(block.lines.joinToString("\n"))
                else -> listOf(FullTextItem.Markdown(block))
            }

        /** Plain text as monospace chunks of bounded size. */
        fun mono(text: String): List<FullTextItem> {
            val chunks = ArrayList<FullTextItem>()
            val current = StringBuilder()
            var lines = 0

            fun flush() {
                if (current.isNotEmpty()) chunks += FullTextItem.Mono(current.toString())
                current.clear()
                lines = 0
            }
            for (line in text.split('\n')) {
                for (piece in line.chunked(CHARS_PER_CHUNK).ifEmpty { listOf("") }) {
                    if (lines >= LINES_PER_CHUNK || current.length + piece.length > CHARS_PER_CHUNK) flush()
                    if (lines > 0) current.append('\n')
                    current.append(piece)
                    lines++
                }
            }
            flush()
            return chunks
        }
    }
}
