package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One tool invocation inside an assistant entry; [input] is the tool's own argument object. */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject = JsonObject(emptyMap()),
)

/** One hunk of a recorded Bash diff; `lines` carry a leading space, `-` or `+`. */
@Serializable
data class BashEditDiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String> = emptyList(),
)

@Serializable
data class BashEditDiffFile(
    val filePath: String,
    val created: Boolean = false,
    val deleted: Boolean = false,
    val hunks: List<BashEditDiffHunk> = emptyList(),
)

/** Claude Code's own object, field names unchanged. */
@Serializable
data class BashEditDiff(
    val files: List<BashEditDiffFile> = emptyList(),
    val moreFiles: Int = 0,
    val changedFiles: List<String>? = null,
    val unavailable: Boolean = false,
    val shared: Boolean = false,
    val skipped: Boolean = false,
)

@Serializable
data class ToolResult(
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("tool_name") val toolName: String = "",
    val content: String = "",
    @SerialName("is_error") val isError: Boolean = false,
    @SerialName("bash_edit_diff") val bashEditDiff: BashEditDiff? = null,
)

@Serializable
data class HookSummary(
    @SerialName("hook_names") val hookNames: List<String> = emptyList(),
    @SerialName("has_errors") val hasErrors: Boolean = false,
    val errors: List<String> = emptyList(),
    @SerialName("prevented_continuation") val preventedContinuation: Boolean = false,
)

/** Token counts of one assistant entry; the server may send further counters, which are ignored. */
@Serializable
data class TokenUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long? = null,
)

/** One transcript entry. */
@Serializable
data class Message(
    val id: Long,
    @SerialName("session_id") val sessionId: String,
    val type: MessageType,
    val subtype: String? = null,
    @SerialName("outbox_id") val outboxId: Long? = null,
    val timestamp: String? = null,
    val content: String? = null,
    val thinking: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_result") val toolResult: ToolResult? = null,
    @SerialName("hook_summary") val hookSummary: HookSummary? = null,
    val tokens: TokenUsage? = null,
    val origin: String? = null,
    @SerialName("origin_meta") val originMeta: Map<String, String>? = null,
    @SerialName("notification_marker") val notificationMarker: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `GET /api/session/{id}/messages/find`: ids of every message matching a text or kind predicate. */
@Serializable
data class MessageFindResult(
    @SerialName("message_ids") val messageIds: List<Long> = emptyList(),
    val total: Int = 0,
    @SerialName("as_of_id") val asOfId: Long? = null,
    val capped: Boolean = false,
)
