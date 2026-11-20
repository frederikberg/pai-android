package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.NotificationSummary
import com.frederikberg.pai.contract.NotificationsResponse
import com.frederikberg.pai.contract.PaiNotification
import com.frederikberg.pai.kit.net.ApiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The feed, newest first. [beforeId] pages backwards by id; [unreadOnly] keeps rows not yet read;
 * [kind] narrows to `agent` or `alert` (alerts are the owner's).
 */
suspend fun ApiClient.listNotifications(
    limit: Int? = null,
    beforeId: String? = null,
    kind: String? = null,
    unreadOnly: Boolean = false,
): NotificationsResponse =
    send(
        NotificationsResponse.serializer(),
        "/notifications",
        query =
            query(
                "limit" to limit,
                "before_id" to beforeId,
                "kind" to kind,
                "unread" to (if (unreadOnly) "only" else null),
            ),
    )

/** The cheap poll that heals a missed stream event. */
suspend fun ApiClient.getNotificationSummary(): NotificationSummary =
    send(NotificationSummary.serializer(), "/notifications/summary")

suspend fun ApiClient.getNotification(id: String): PaiNotification =
    send(PaiNotification.serializer(), "/notifications/$id")

/** What marking read names: specific rows, every unread row, or every unread row of one session. */
sealed interface ReadTarget {
    data class Ids(
        val ids: List<String>,
    ) : ReadTarget

    data object All : ReadTarget

    data class OfSession(
        val sessionId: String,
    ) : ReadTarget
}

/** Marks notifications read; answers how many rows changed. */
suspend fun ApiClient.markNotificationsRead(target: ReadTarget): Int {
    val body: JsonObject =
        when (target) {
            is ReadTarget.Ids -> buildJsonObject { put("ids", JsonArray(target.ids.map(::JsonPrimitive))) }
            ReadTarget.All -> buildJsonObject { put("all", true) }
            is ReadTarget.OfSession -> buildJsonObject { put("session_id", target.sessionId) }
        }
    return markedCount(
        send(
            JsonObject.serializer(),
            "/notifications/read",
            method = "POST",
            body = encode(JsonObject.serializer(), body),
        ),
    )
}

/** Marks specific read notifications unread again; answers how many rows changed. */
suspend fun ApiClient.markNotificationsUnread(ids: List<String>): Int =
    markedCount(
        send(
            JsonObject.serializer(),
            "/notifications/unread",
            method = "POST",
            body = encode(JsonObject.serializer(), buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }),
        ),
    )

private fun markedCount(answer: JsonObject): Int = answer["marked"]?.jsonPrimitive?.intOrNull ?: 0
