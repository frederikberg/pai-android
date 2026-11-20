package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One send the server is still expecting a transcript entry for. */
@Serializable
data class PendingSend(
    val id: Long,
    val text: String,
    val held: Boolean = false,
    @SerialName("take_id") val takeId: String? = null,
)

/** The `init` event of a session stream: a page of history and where the live edge stands. */
@Serializable
data class SseInitEvent(
    val entries: List<Message> = emptyList(),
    val cursor: Long? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("session_tokens") val sessionTokens: Long? = null,
)

/** The `batch` event: entries that arrived after the last one the client has. */
@Serializable
data class SseBatchEvent(
    val entries: List<Message> = emptyList(),
    @SerialName("session_tokens") val sessionTokens: Long? = null,
)

/** The `status` event: the session fields that move, and the delivery state of outgoing messages. */
@Serializable
data class SseStatusEvent(
    val status: SessionStatus,
    val state: SessionState? = null,
    val blocker: Blocker? = null,
    @SerialName("turn_state") val turnState: TurnState? = null,
    @SerialName("display_state") val displayState: DisplayState? = null,
    @SerialName("secret_grantable") val secretGrantable: Boolean = false,
    @SerialName("secret_prompt") val secretPrompt: SecretPrompt? = null,
    @SerialName("activity_counts") val activityCounts: ActivityCounts? = null,
    val model: String? = null,
    @SerialName("live_model") val liveModel: String? = null,
    @SerialName("pending_model") val pendingModel: String? = null,
    @SerialName("ultrafast_model") val ultrafastModel: String? = null,
    val thinking: String? = null,
    @SerialName("no_memory") val noMemory: Boolean = false,
    @SerialName("closed_unexpectedly") val closedUnexpectedly: Boolean = false,
    @SerialName("transferred_to_session_id") val transferredToSessionId: String? = null,
    @SerialName("transferred_at") val transferredAt: String? = null,
    val queued: Int = 0,
    @SerialName("queued_texts") val queuedTexts: List<String> = emptyList(),
    @SerialName("pending_sends") val pendingSends: List<PendingSend> = emptyList(),
    @SerialName("last_error") val lastError: String? = null,
)

/** The `processing` event: whether a turn is running and for how long. */
@Serializable
data class SseProcessingEvent(
    val processing: Boolean,
    val elapsed: Int = 0,
)

/** The `arc` event: a spec attached to the session changed. */
@Serializable
data class ArcSseEvent(
    @SerialName("spec_uuid") val specUuid: String,
)

/** The `draft` event: this session's draft changed on another device. */
@Serializable
data class DraftSseEvent(
    val key: String,
)

/** What `POST /api/messages` and `POST /api/session/{id}/message` answer. */
@Serializable
data class PostMessageResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: Long,
    val duplicate: Boolean = false,
    @SerialName("draft_version") val draftVersion: Long? = null,
    val withdrawn: Boolean = false,
    @SerialName("text_in_draft") val textInDraft: Boolean? = null,
    val held: Boolean? = null,
    @SerialName("take_state") val takeState: VoiceTakeState? = null,
)
