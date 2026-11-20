package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where in the transcript a notification came from. */
@Serializable
data class NotificationAnchor(
    @SerialName("message_id") val messageId: Long,
)

/** The alert half of an alert notification. */
@Serializable
data class NotificationAlert(
    val id: String? = null,
    val key: String,
    val severity: String = "",
    val source: String = "",
    val transition: AlertTransition,
    val active: Boolean = false,
)

/** One row of the feed: an agent push or an alert transition, never both. */
@Serializable
data class PaiNotification(
    val id: String,
    val kind: NotificationKind,
    val title: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_title") val sessionTitle: String? = null,
    val anchor: NotificationAnchor? = null,
    val alert: NotificationAlert? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("task_name") val taskName: String? = null,
    @SerialName("run_slug") val runSlug: String? = null,
)

@Serializable
data class NotificationsResponse(
    val unread: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val notifications: List<PaiNotification> = emptyList(),
)

@Serializable
data class NotificationSummary(
    val unread: Int = 0,
)

/** The `notification` event of the account stream. */
@Serializable
data class SseNotificationEvent(
    val notification: PaiNotification,
    val unread: Int = 0,
    @SerialName("in_use") val inUse: Boolean = false,
)
