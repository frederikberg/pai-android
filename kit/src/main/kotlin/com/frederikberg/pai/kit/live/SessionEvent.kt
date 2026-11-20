package com.frederikberg.pai.kit.live

import com.frederikberg.pai.contract.ArcSseEvent
import com.frederikberg.pai.contract.ContractJson
import com.frederikberg.pai.contract.DraftSseEvent
import com.frederikberg.pai.contract.SseBatchEvent
import com.frederikberg.pai.contract.SseInitEvent
import com.frederikberg.pai.contract.SseProcessingEvent
import com.frederikberg.pai.contract.SseStatusEvent
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One record of a session's transcript stream, decoded. The event names are the server's:
 * `init`, `batch`, `status`, `processing`, `arc`, `draft`; the idle `ping` never gets this far.
 */
sealed interface SessionEvent {
    /** A page of history. A fresh connection delivers the newest page first, then older ones in descending pages, all under the same [eventId]. */
    data class Init(
        val page: SseInitEvent,
        val eventId: Long?,
    ) : SessionEvent

    /** Entries newer than the last one delivered; [eventId] is the newest entry's id. */
    data class Batch(
        val batch: SseBatchEvent,
        val eventId: Long?,
    ) : SessionEvent

    data class Status(
        val status: SseStatusEvent,
    ) : SessionEvent

    data class Processing(
        val processing: SseProcessingEvent,
    ) : SessionEvent

    /** A spec attached to the session changed; the transcript has no use for it. */
    data class Arc(
        val event: ArcSseEvent,
    ) : SessionEvent

    /** This session's draft changed on another device. */
    data class Draft(
        val event: DraftSseEvent,
    ) : SessionEvent

    /** An event name this app does not know: a newer server, ignored by whoever listens. */
    data class Unrecognised(
        val name: String,
    ) : SessionEvent

    /** A known event whose body did not decode; [reason] is for the log, never for the person. */
    data class Undecodable(
        val name: String,
        val reason: String,
    ) : SessionEvent

    companion object {
        fun from(
            record: SseRecord,
            json: Json = ContractJson,
        ): SessionEvent {
            val eventId = record.id?.toLongOrNull()

            fun <T> decode(
                strategy: DeserializationStrategy<T>,
                wrap: (T) -> SessionEvent,
            ): SessionEvent =
                try {
                    wrap(json.decodeFromString(strategy, record.data))
                } catch (e: SerializationException) {
                    Undecodable(record.name, e.message.orEmpty())
                } catch (e: IllegalArgumentException) {
                    Undecodable(record.name, e.message.orEmpty())
                }
            return when (record.name) {
                "init" -> decode(SseInitEvent.serializer()) { Init(it, eventId) }
                "batch" -> decode(SseBatchEvent.serializer()) { Batch(it, eventId) }
                "status" -> decode(SseStatusEvent.serializer(), ::Status)
                "processing" -> decode(SseProcessingEvent.serializer(), ::Processing)
                "arc" -> decode(ArcSseEvent.serializer(), ::Arc)
                "draft" -> decode(DraftSseEvent.serializer(), ::Draft)
                else -> Unrecognised(record.name)
            }
        }
    }
}
