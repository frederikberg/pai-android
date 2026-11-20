package com.frederikberg.pai.kit.transcript

import com.frederikberg.pai.kit.markdown.MarkdownBlock

/** Which of the person's own voices a bubble is. */
enum class BubbleKind {
    /** What the person typed. */
    Own,

    /** The agent's second, answered copy of a prompt typed while a turn was still running. */
    Resent,

    /** A prompt another session relayed in; the label names who sent it. */
    Relayed,

    /** A slash command or skill invocation; the label is its name. */
    Command,
}

/** What a card is about; the screen picks its glyph from this. */
enum class CardKind {
    ToolCall,
    ToolResult,
    Thinking,
    System,
    Hooks,
    AgentReport,
    Notified,
    Compaction,
    Diff,
}

enum class Tone { Normal, Error, Warn }

/** What a card opens to: plain text, or markdown for the kinds that are prose rather than output. */
sealed interface CardBody {
    val cut: Boolean

    data class Plain(
        val text: Bounded,
    ) : CardBody {
        override val cut: Boolean get() = text.cut
    }

    data class Markdown(
        val blocks: List<MarkdownBlock>,
        override val cut: Boolean,
    ) : CardBody
}

/** A piece of a reply: markdown, or a file the agent offered with a `pai-file:` line. */
sealed interface ReplySegment {
    data class Markdown(
        val blocks: List<MarkdownBlock>,
    ) : ReplySegment

    data class Picture(
        val path: String,
    ) : ReplySegment

    data class FileChip(
        val path: String,
    ) : ReplySegment
}

/**
 * One row of the transcript. A message is one or several rows (an assistant turn is its thinking, each
 * tool call and its reply), and every row has a key that is stable for as long as its message exists,
 * so the list holds a reader's place by identity.
 */
sealed interface TranscriptRow {
    val key: String

    /** `HH:mm`, present only on the first row of a message that starts a new minute. */
    val timeLabel: String?

    data class Bubble(
        override val key: String,
        override val timeLabel: String?,
        val kind: BubbleKind,
        val label: String?,
        val text: Bounded,
        val attachments: List<AttachmentRef>,
    ) : TranscriptRow

    data class Prose(
        override val key: String,
        override val timeLabel: String?,
        val segments: List<ReplySegment>,
        /** The reply is longer than the slice drawn; the full text has its own screen. */
        val cut: Boolean,
    ) : TranscriptRow

    /** A one-line header that opens to its [body] when there is one. */
    data class Card(
        override val key: String,
        override val timeLabel: String?,
        val kind: CardKind,
        val tone: Tone,
        val label: String?,
        val headline: String,
        val body: CardBody?,
    ) : TranscriptRow

    /** A boundary in the conversation rather than something anyone said. */
    data class Divider(
        override val key: String,
        val label: String,
    ) : TranscriptRow {
        override val timeLabel: String? get() = null
    }

    /** A send the server has accepted and not yet answered with a transcript entry. */
    data class Pending(
        override val key: String,
        val text: Bounded,
        val held: Boolean,
    ) : TranscriptRow {
        override val timeLabel: String? get() = null
    }

    fun withTimeLabel(label: String?): TranscriptRow =
        when (this) {
            is Bubble -> copy(timeLabel = label)
            is Prose -> copy(timeLabel = label)
            is Card -> copy(timeLabel = label)
            is Divider, is Pending -> this
        }
}

/** The row at the top of a viewport and how far into it, plus whether the list was at its end: what a return to the list restores by identity. */
data class ViewportAnchor(
    val rowKey: String,
    val offsetPx: Int,
    val atEnd: Boolean,
)
