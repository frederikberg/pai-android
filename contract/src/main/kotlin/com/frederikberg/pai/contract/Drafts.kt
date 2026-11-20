package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One file added to a draft, uploaded at once so every device shows it. */
@Serializable
data class DraftAttachment(
    val id: String,
    val filename: String,
    val path: String,
    val size: Long,
    @SerialName("content_type") val contentType: String? = null,
    val state: DraftAttachmentState,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Composer text not yet sent, kept on the server so every client shows the same half-written
 * message. [version] is the only field a client orders by; [key] is a session id, or `new` for the
 * session the new-session screen composes, which alone carries the launch choices.
 */
@Serializable
data class Draft(
    val key: String,
    val text: String = "",
    val version: Long,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("session_type") val sessionType: String? = null,
    @SerialName("working_dir") val workingDir: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    @SerialName("no_memory") val noMemory: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("previous_text") val previousText: String = "",
    val attachments: List<DraftAttachment> = emptyList(),
)

/** What a draft write answers: the version the row now stands at, and what it held before. */
@Serializable
data class DraftWriteResult(
    val key: String,
    val version: Long,
    @SerialName("previous_text") val previousText: String = "",
)

/** What a discard answers; [version] is null when there was no row, so nothing was destroyed. */
@Serializable
data class DraftDiscardResult(
    val key: String,
    val version: Long? = null,
    @SerialName("previous_text") val previousText: String = "",
)
