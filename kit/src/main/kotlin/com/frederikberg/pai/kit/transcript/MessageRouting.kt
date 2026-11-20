package com.frederikberg.pai.kit.transcript

import com.frederikberg.pai.contract.BashEditDiff
import com.frederikberg.pai.contract.Message
import com.frederikberg.pai.contract.MessageType
import com.frederikberg.pai.kit.markdown.FileMarkers
import com.frederikberg.pai.kit.markdown.MarkdownParser

/**
 * Turns a message into the rows that draw it, routed by `type` and `subtype`. A subtype this app does
 * not know becomes a system card, never a bubble of the person's own words: a `user` entry with a
 * subtype is machinery the framework wrote, and captioning it with the start of the person's text
 * would read as their voice.
 */
object MessageRouting {
    /** A turn with nothing to show draws nothing. */
    fun isShown(message: Message): Boolean {
        if (message.type != MessageType.Assistant) return true
        return !message.content.isNullOrBlank() || !message.toolCalls.isNullOrEmpty() || !message.thinking.isNullOrBlank()
    }

    fun rows(
        message: Message,
        labels: TimeLabels,
    ): List<TranscriptRow> {
        val key = "m${message.id}"
        val time = labels.clock(message.timestamp)
        return when {
            message.type == MessageType.System && message.subtype == "compact" ->
                listOf(TranscriptRow.Divider(key, "Conversation compacted"))
            message.type == MessageType.System -> listOf(system(key, time, message, message.subtype, message.content.orEmpty()))
            message.type == MessageType.ToolResult && message.toolResult != null -> toolResult(key, time, message)
            message.type == MessageType.User -> user(key, time, message)
            message.type == MessageType.Assistant -> assistant(key, time, message)
            else -> emptyList()
        }
    }

    private fun user(
        key: String,
        time: String?,
        message: Message,
    ): List<TranscriptRow> {
        val content = message.content.orEmpty()
        return when (message.subtype) {
            null -> {
                val legacy = legacyLocalCommand(content)
                when {
                    legacy == null -> bubble(key, time, BubbleKind.Own, null, content)
                    legacy.first == "caveat" || legacy.second.isEmpty() -> emptyList()
                    else -> listOf(system(key, time, message, "command_output", legacy.second))
                }
            }
            "resent" -> bubble(key, time, BubbleKind.Resent, "Resent", content)
            "agent_message" -> {
                val (sender, body) = splitLabeled(content)
                val report = body.ifEmpty { content }
                listOf(
                    TranscriptRow.Card(
                        key,
                        time,
                        CardKind.AgentReport,
                        Tone.Normal,
                        sender,
                        ToolText.firstLine(report),
                        markdownBody(report),
                    ),
                )
            }
            "pai_message" -> bubble(key, time, BubbleKind.Relayed, relayLabel(message), content)
            "command" -> {
                val (name, args) = commandParts(content)
                bubble(key, time, BubbleKind.Command, name, args)
            }
            else -> listOf(system(key, time, message, message.subtype, content))
        }
    }

    private fun bubble(
        key: String,
        time: String?,
        kind: BubbleKind,
        label: String?,
        content: String,
    ): List<TranscriptRow> {
        val (text, paths) = AttachmentPaths.extract(content).let { it.text to it.paths }
        if (text.isEmpty() && paths.isEmpty() && label == null) return emptyList()
        return listOf(
            TranscriptRow.Bubble(
                key,
                time,
                kind,
                label,
                Bounded.of(text, RowBounds.USER_LINES),
                paths.map(AttachmentPaths::ref),
            ),
        )
    }

    private fun relayLabel(message: Message): String {
        val meta = message.originMeta.orEmpty()
        val from = meta["from"].orEmpty().ifEmpty { "Another session" }
        return when {
            message.origin == "agent" && !meta["group"].isNullOrEmpty() -> "$from · ${meta["group"]}"
            message.origin == "magic" && meta["seat"]?.all { it.isDigit() } == true && meta["seat"]!!.isNotEmpty() ->
                "$from · seat ${meta["seat"]}"
            else -> from
        }
    }

    private fun system(
        key: String,
        time: String?,
        message: Message,
        subtype: String?,
        content: String,
    ): TranscriptRow {
        val label = systemLabel(subtype, content)
        val hooks = if (subtype == "hook") message.hookSummary else null
        if (hooks != null) {
            val problem = hooks.hasErrors || hooks.preventedContinuation
            val body =
                (hooks.errors + listOfNotNull("prevented continuation".takeIf { hooks.preventedContinuation }))
                    .joinToString("\n")
            return TranscriptRow.Card(
                key,
                time,
                CardKind.Hooks,
                if (problem) Tone.Error else Tone.Normal,
                label,
                hooks.hookNames.joinToString(", ").ifEmpty { "none ran" },
                body.takeIf { it.isNotEmpty() }?.let { CardBody.Plain(Bounded.of(it, RowBounds.CARD_LINES)) },
            )
        }
        val text = if (subtype == "command_output") ToolText.stripAnsi(content) else content
        return when (subtype) {
            "duration", "interrupt" ->
                TranscriptRow.Card(
                    key,
                    time,
                    CardKind.System,
                    if (subtype == "interrupt") Tone.Warn else Tone.Normal,
                    label,
                    ToolText.firstLine(text),
                    null,
                )
            "compact_summary" ->
                TranscriptRow.Card(key, time, CardKind.Compaction, Tone.Normal, label, ToolText.firstLine(text), markdownBody(text))
            else ->
                TranscriptRow.Card(key, time, CardKind.System, Tone.Normal, label, ToolText.firstLine(text), plainBody(text))
        }
    }

    private fun toolResult(
        key: String,
        time: String?,
        message: Message,
    ): List<TranscriptRow> {
        val result = message.toolResult!!
        val rows = ArrayList<TranscriptRow>()
        val notice = if (message.notificationMarker != null) NotifyReply.parse(result.content) else null
        if (notice != null) {
            rows +=
                TranscriptRow.Card(
                    key,
                    time,
                    CardKind.Notified,
                    Tone.Normal,
                    "Notified",
                    notice.title,
                    notice.body.takeIf { it.isNotEmpty() }?.let { CardBody.Plain(Bounded.of(it, RowBounds.CARD_LINES)) },
                )
        } else {
            val raw = if (result.toolName.equals("read", ignoreCase = true)) ToolText.stripLineNumbers(result.content) else result.content
            val text = ToolText.stripAnsi(raw)
            val tool = ToolText.toolName(result.toolName.ifEmpty { "Unknown" })
            rows +=
                TranscriptRow.Card(
                    key,
                    time,
                    CardKind.ToolResult,
                    if (result.isError) Tone.Error else Tone.Normal,
                    if (result.isError) "$tool failed" else null,
                    ToolText.firstLine(text).ifEmpty { "(no output)" },
                    plainBody(text),
                )
        }
        result.bashEditDiff?.let { rows += diffRows(key, it) }
        return rows
    }

    private fun diffRows(
        key: String,
        diff: BashEditDiff,
    ): List<TranscriptRow> {
        if (diff.unavailable || diff.skipped || diff.shared) return emptyList()
        val rows =
            diff.files.mapIndexed { index, file ->
                val added = file.hunks.sumOf { hunk -> hunk.lines.count { it.startsWith("+") } }
                val removed = file.hunks.sumOf { hunk -> hunk.lines.count { it.startsWith("-") } }
                val body = file.hunks.joinToString("\n") { it.lines.joinToString("\n") }
                TranscriptRow.Card(
                    "$key:diff:$index",
                    null,
                    CardKind.Diff,
                    Tone.Normal,
                    if (file.created) "Created" else if (file.deleted) "Deleted" else "Edited",
                    "${ToolText.abbreviatingHome(file.filePath)} · +$added −$removed",
                    plainBody(body),
                )
            }
        return if (diff.moreFiles > 0) {
            rows +
                TranscriptRow.Card(
                    "$key:diff:more",
                    null,
                    CardKind.Diff,
                    Tone.Normal,
                    null,
                    "${diff.moreFiles} more file${if (diff.moreFiles == 1) "" else "s"} changed",
                    null,
                )
        } else {
            rows
        }
    }

    private fun assistant(
        key: String,
        time: String?,
        message: Message,
    ): List<TranscriptRow> {
        val rows = ArrayList<TranscriptRow>()
        message.thinking?.takeIf { it.isNotBlank() }?.let { thinking ->
            val redacted = thinking.trim() == "[extended thinking]"
            rows +=
                TranscriptRow.Card(
                    "$key:thinking",
                    null,
                    CardKind.Thinking,
                    Tone.Normal,
                    "Thinking",
                    if (redacted) "not shown" else ToolText.firstLine(thinking),
                    if (redacted) null else plainBody(thinking),
                )
        }
        message.toolCalls.orEmpty().forEach { call ->
            val view = ToolCards.view(call)
            rows +=
                TranscriptRow.Card(
                    "$key:call:${call.id}",
                    null,
                    CardKind.ToolCall,
                    Tone.Normal,
                    ToolText.toolName(call.name),
                    view.headline,
                    view.body?.let { CardBody.Plain(Bounded.of(it, RowBounds.CARD_LINES)) },
                )
        }
        message.content?.takeIf { it.isNotBlank() }?.let { content ->
            val bounded = Bounded.of(content, RowBounds.PROSE_LINES)
            rows += TranscriptRow.Prose("$key:prose", null, segments(bounded.shown), bounded.cut)
        }
        // The message's time belongs to its first row, drawn once.
        return rows.firstOrNull()?.let { listOf(it.withTimeLabel(time)) + rows.drop(1) } ?: rows
    }

    /** A reply's markdown and the files it offers, in the order they stand. */
    fun segments(text: String): List<ReplySegment> =
        FileMarkers.split(text).mapNotNull { segment ->
            when (segment) {
                is FileMarkers.Segment.Text ->
                    segment.text.takeIf { it.isNotBlank() }?.let { ReplySegment.Markdown(MarkdownParser.parse(it)) }
                is FileMarkers.Segment.File ->
                    if (AttachmentPaths.isPicture(segment.path)) ReplySegment.Picture(segment.path) else ReplySegment.FileChip(segment.path)
            }
        }

    private fun plainBody(text: String): CardBody.Plain? {
        val headline = ToolText.firstLine(text)
        if (text.isBlank() || text.trim() == headline) return null
        return CardBody.Plain(Bounded.of(text, RowBounds.CARD_LINES))
    }

    private fun markdownBody(text: String): CardBody.Markdown? {
        if (text.isBlank()) return null
        val bounded = Bounded.of(text, RowBounds.CARD_LINES)
        return CardBody.Markdown(MarkdownParser.parse(bounded.shown), bounded.cut)
    }

    fun systemLabel(
        subtype: String?,
        content: String?,
    ): String =
        when (subtype) {
            "skill" -> "Skill"
            "context" -> "Context"
            "command" -> "Command"
            "command_output" -> "Output"
            "image" -> "Image"
            "compact" -> "Compacted"
            "compact_summary" -> "Compaction summary"
            "compact_context" -> "Restored"
            "hook" -> "Hooks"
            "duration" -> "Duration"
            "interrupt" -> "Interrupted"
            "notification" -> "Task notification"
            "scheduled" -> "Scheduled"
            "pai_message" -> "Relayed message"
            else -> content?.takeIf { it.isNotEmpty() }?.take(60) ?: "System"
        }

    /** Content stored as "{label}\n\n{body}" split back apart: a sender and its report, a command and its arguments. */
    fun splitLabeled(content: String): Pair<String, String> {
        val separator = content.indexOf("\n\n")
        return if (separator < 0) content to "" else content.substring(0, separator) to content.substring(separator + 2)
    }

    private val COMMAND_NAME = Regex("<command-name>([\\s\\S]*?)</command-name>")
    private val COMMAND_ARGS = Regex("<command-args>([\\s\\S]*?)</command-args>")
    private val LOCAL_COMMAND = Regex("^<local-command-(\\w+)>([\\s\\S]*)</local-command-\\1>$")

    /** The name and arguments of a command row, whichever shape it was stored in: the clean one, or Claude Code's raw tag wrapper. */
    fun commandParts(content: String): Pair<String, String> {
        if (content.startsWith("<")) {
            return (COMMAND_NAME.find(content)?.groupValues?.get(1)?.trim().orEmpty()) to
                (COMMAND_ARGS.find(content)?.groupValues?.get(1)?.trim().orEmpty())
        }
        return splitLabeled(content)
    }

    /** A plain user message that is really a `<local-command-*>` wrapper: its kind and the text inside. */
    private fun legacyLocalCommand(content: String): Pair<String, String>? =
        LOCAL_COMMAND.matchEntire(content.trim())?.let { it.groupValues[1] to it.groupValues[2].trim() }
}
