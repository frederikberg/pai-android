package com.frederikberg.pai.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a device can opt out of: a notification's kind decides the channel its push travels on. */
@Serializable
data class PushChannelInfo(
    val id: String,
    val label: String,
    val description: String = "",
    val kinds: List<NotificationKind> = emptyList(),
)

@Serializable
data class PushChannelsResponse(
    val channels: List<PushChannelInfo> = emptyList(),
)

/** One subscribed browser or distributor endpoint; the endpoint and its keys never travel back. */
@Serializable
data class WebPushSubscriptionSummary(
    val id: String,
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("muted_channels") val mutedChannels: List<String> = emptyList(),
)

/** What `POST /api/push/subscriptions` answers. */
@Serializable
data class WebPushSubscriptionCreated(
    val id: String,
    @SerialName("muted_channels") val mutedChannels: List<String> = emptyList(),
)

@Serializable
data class WebPushTestResult(
    val delivered: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
)
