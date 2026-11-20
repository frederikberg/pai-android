package com.frederikberg.pai.kit.live

import com.frederikberg.pai.contract.ContractJson
import com.frederikberg.pai.contract.SseNotificationEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One record of the account-wide stream, decoded. The stream carries no history: a client reads the
 * feed and the session list over REST when it connects and uses the stream only for what follows.
 */
sealed interface NotificationEvent {
    /** The session list changed; the client runs its incremental sync (`GET /api/sessions?since=`). The event carries nothing. */
    data object SessionsChanged : NotificationEvent

    /** A new feed row, with the unread count after it and whether the person is using a client right now. */
    data class Notification(
        val event: SseNotificationEvent,
    ) : NotificationEvent

    /** Read state changed somewhere; [unread] is the count now. */
    data class Read(
        val unread: Int,
    ) : NotificationEvent

    /** `desktop_open`, `secret_pregrant` and `decisions` are signals for the owner's own tools; this app has no use for them. */
    data class OwnerSignal(
        val name: String,
    ) : NotificationEvent

    data class Unrecognised(
        val name: String,
    ) : NotificationEvent

    data class Undecodable(
        val name: String,
        val reason: String,
    ) : NotificationEvent

    companion object {
        private val OWNER_SIGNALS = setOf("desktop_open", "secret_pregrant", "decisions")

        fun from(
            record: SseRecord,
            json: Json = ContractJson,
        ): NotificationEvent =
            try {
                when (record.name) {
                    "sessions" -> SessionsChanged
                    "notification" ->
                        Notification(
                            json.decodeFromString(SseNotificationEvent.serializer(), record.data),
                        )
                    "read" ->
                        readOf(json.parseToJsonElement(record.data).jsonObject)
                            ?: Undecodable(record.name, "no unread count")
                    in OWNER_SIGNALS -> OwnerSignal(record.name)
                    else -> Unrecognised(record.name)
                }
            } catch (e: SerializationException) {
                Undecodable(record.name, e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                Undecodable(record.name, e.message.orEmpty())
            }

        private fun readOf(body: JsonObject): Read? = body["unread"]?.jsonPrimitive?.intOrNull?.let(::Read)
    }
}
