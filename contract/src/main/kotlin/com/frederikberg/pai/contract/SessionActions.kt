package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /api/session/{id}/cancel`; the status is always `cancelled`. */
@Serializable
data class CancelResponse(
    val status: String,
)

@Serializable
data class CloseResponse(
    val status: CloseStatus,
    val detail: String? = null,
)

/** `POST /api/session/{id}/resume`; [session] is the session once the launch has been driven. */
@Serializable
data class ResumeResponse(
    val status: ResumeStatus,
    val detail: String? = null,
    val session: Session? = null,
)

@Serializable
data class DeleteResponse(
    val status: DeleteStatus,
)

@Serializable
data class AnswerBlockerResponse(
    val status: AnswerBlockerStatus,
    val detail: String? = null,
)

/** `POST /api/session/{id}/background`. */
@Serializable
data class MoveToBackgroundResponse(
    val status: BackgroundStatus,
    val moved: List<String> = emptyList(),
    @SerialName("not_moved") val notMoved: List<String> = emptyList(),
    val reason: String? = null,
)

/** A send pulled back out of the queue; [clientMessageId] ties it to the sender's own entry. */
@Serializable
data class WithdrawnSend(
    val id: Long,
    val text: String,
    val held: Boolean = false,
    @SerialName("take_id") val takeId: String? = null,
    @SerialName("client_message_id") val clientMessageId: String? = null,
)

/** What pulling the queued sends back into the composer came to. */
@Serializable
data class WithdrawPendingResponse(
    val withdrawn: List<WithdrawnSend> = emptyList(),
    @SerialName("already_delivered") val alreadyDelivered: List<Long> = emptyList(),
    val unresolved: List<Long> = emptyList(),
    @SerialName("withdrawn_client_ids") val withdrawnClientIds: List<String> = emptyList(),
    @SerialName("delivered_client_ids") val deliveredClientIds: List<String> = emptyList(),
    @SerialName("draft_version") val draftVersion: Long,
)
