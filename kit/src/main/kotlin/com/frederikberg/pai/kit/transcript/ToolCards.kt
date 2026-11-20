package com.frederikberg.pai.kit.transcript

import com.frederikberg.pai.contract.ToolCall
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What a tool call shows: a one-line [headline] and, when the call has more to say, the [body] a reader can open. */
data class ToolCallView(
    val headline: String,
    val body: String?,
)

/**
 * A call drawn as what it asked for rather than as its name: a command, a path with a line count, an
 * agent's identity. An unmapped call (every MCP tool, whatever is added next) lists its arguments as
 * sorted `key: value` lines so the two clients show them in the same order.
 */
object ToolCards {
    fun view(call: ToolCall): ToolCallView {
        val input = call.input
        fun string(key: String): String? = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun number(key: String): Int? = (input[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toInt()
        val name = call.name.lowercase()
        return when {
            name == "bash" && string("command") != null -> {
                val command = string("command")!!
                ToolCallView(ToolText.firstLine(command), command.takeIf { it.contains('\n') || it.length > 80 })
            }
            name == "read" && string("file_path") != null ->
                inline(
                    listOfNotNull(
                        string("file_path"),
                        number("offset")?.let { "from line $it" },
                        number("limit")?.let { "$it lines" },
                    ).joinToString(" "),
                )
            (name == "edit" || name == "multiedit") && string("file_path") != null -> edit(string("file_path")!!, string("old_string"), string("new_string"))
            name == "write" && string("file_path") != null -> {
                val content = string("content").orEmpty()
                val lines = content.split('\n').size
                ToolCallView(
                    "${ToolText.abbreviatingHome(string("file_path")!!)} · $lines line${if (lines == 1) "" else "s"}",
                    content.takeIf { it.isNotEmpty() },
                )
            }
            name == "agent" || name == "task" -> {
                val headline =
                    listOfNotNull(string("name"), string("subagent_type"), string("model"))
                        .filter { it.isNotEmpty() }
                        .joinToString(" · ")
                        .ifEmpty { "agent" }
                val body = listOfNotNull(string("description"), string("prompt")).filter { it.isNotEmpty() }.joinToString("\n\n")
                ToolCallView(headline, body.takeIf { it.isNotEmpty() })
            }
            name == "grep" ->
                inline(
                    listOfNotNull(
                        string("pattern")?.let { "/$it/" },
                        string("path")?.let { "in $it" },
                        string("glob")?.let { "($it)" },
                    ).joinToString(" "),
                )
            name == "glob" ->
                inline(listOfNotNull(string("pattern"), string("path")?.let { "in $it" }).joinToString(" "))
            name == "websearch" && string("query") != null -> inline("\"${string("query")}\"")
            name == "webfetch" && string("url") != null -> inline(string("url")!!)
            name == "skill" && string("skill") != null ->
                inline(string("args")?.let { "/${string("skill")} $it" } ?: "/${string("skill")}")
            name == "taskstop" -> inline(string("task_id") ?: string("shell_id") ?: "")
            name == "croncreate" ->
                inline(listOfNotNull(string("cron"), string("prompt")?.let { "\"$it\"" }).joinToString(" · "))
            name == "crondelete" -> inline(string("id") ?: "")
            name == "listagents" || name == "cronlist" -> inline("")
            else -> {
                val lines = input.entries.sortedBy { it.key }.map { (key, value) -> "$key: ${render(value)}" }
                ToolCallView(lines.firstOrNull().orEmpty(), lines.joinToString("\n").takeIf { lines.size > 1 })
            }
        }
    }

    private fun inline(text: String) = ToolCallView(text, null)

    private fun edit(
        path: String,
        old: String?,
        new: String?,
    ): ToolCallView {
        val diff =
            listOfNotNull(
                old?.takeIf { it.isNotEmpty() }?.lines()?.joinToString("\n") { "- $it" },
                new?.takeIf { it.isNotEmpty() }?.lines()?.joinToString("\n") { "+ $it" },
            ).joinToString("\n")
        val changed = (old?.lines()?.size ?: 0) + (new?.lines()?.size ?: 0)
        val suffix = if (old != null && new != null) " · $changed line${if (changed == 1) "" else "s"}" else ""
        return ToolCallView(ToolText.abbreviatingHome(path) + suffix, diff.takeIf { it.isNotEmpty() })
    }

    private fun render(value: JsonElement): String =
        when (value) {
            is JsonNull -> "null"
            is JsonPrimitive -> if (value.isString) value.content else value.contentOrNull ?: value.toString()
            else -> value.toString()
        }
}
