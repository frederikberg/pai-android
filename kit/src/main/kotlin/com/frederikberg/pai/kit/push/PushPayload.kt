package com.frederikberg.pai.kit.push

import com.frederikberg.pai.contract.ContractJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.URI

/** What the server sends with every Web Push: the notification's text, where tapping it leads, and a tag unique to the notification. */
@Serializable
data class PushPayload(
    val title: String? = null,
    val body: String? = null,
    val url: String? = null,
    val tag: String? = null,
) {
    val target: PushTarget get() = PushTarget.of(url)

    companion object {
        /** Anything that is not the expected JSON still shows as a notification, as a bare title. */
        fun parse(content: ByteArray): PushPayload =
            try {
                ContractJson.decodeFromString(serializer(), content.decodeToString())
            } catch (_: SerializationException) {
                PushPayload()
            } catch (_: IllegalArgumentException) {
                PushPayload()
            }
    }
}

/** Where tapping a notification leads. */
sealed interface PushTarget {
    /** A session, and the notification row that raised it when the link names one. */
    data class Session(
        val sessionId: String,
        val notificationId: String?,
    ) : PushTarget

    /** The feed: a notification that belongs to no session, or one whose link is not understood. */
    data object Notifications : PushTarget

    companion object {
        /** The server's links are `/session/<id>?n=<notification>` and `/apps/notifications?a=<notification>`. */
        fun of(url: String?): PushTarget {
            val uri = runCatching { URI(url ?: return Notifications) }.getOrNull() ?: return Notifications
            val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            if (segments.size == 2 && segments[0] == "session") {
                return Session(segments[1], queryValue(uri.rawQuery, "n"))
            }
            return Notifications
        }

        private fun queryValue(
            query: String?,
            name: String,
        ): String? =
            query
                ?.split('&')
                ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
                ?.firstOrNull { it.first == name }
                ?.second
                ?.takeIf { it.isNotEmpty() }
    }
}
