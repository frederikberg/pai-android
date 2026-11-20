package com.frederikberg.pai.kit.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** The thing a stream was opened for does not exist any more: [statusCode] is 404 or 410, [detail] what the server said. */
data class StreamGone(
    val statusCode: Int,
    val detail: String?,
)

/**
 * What a typed stream has in common: decoded events in the order they arrived, and the connection
 * state around them.
 *
 * Events are queued without limit and delivered to whoever collects [events]; nothing is replayed, so
 * a store collects first and calls [connect] after. Dropping an event of a transcript would leave a
 * hole nobody could see, which is why the queue has no bound.
 */
abstract class TypedStream<E : Any>(
    scope: CoroutineScope,
    private val decode: (SseRecord) -> E,
    clientFactory: (SseCallbacks) -> SseClient,
) {
    private val _events = MutableSharedFlow<E>(extraBufferCapacity = EVENT_BUFFER)

    /** Every decoded event, in order. */
    val events: SharedFlow<E> = _events.asSharedFlow()

    private val deliveries = Channel<E>(Channel.UNLIMITED)

    private val _gone = MutableStateFlow<StreamGone?>(null)

    /** Set once when the server says the streamed thing does not exist; the stream then stays closed. */
    val gone: StateFlow<StreamGone?> = _gone

    private val link =
        StreamLink(
            scope = scope,
            onRecord = { deliveries.trySend(decode(it)) },
            onRefused = { status, detail -> _gone.value = StreamGone(status, detail) },
            clientFactory = clientFactory,
        )

    val connectionState: StateFlow<ConnectionState> get() = link.connectionState

    /** [connectionState] after the grace period: what a screen should show. */
    val visibleConnectionState: StateFlow<ConnectionState> get() = link.visibleConnectionState

    init {
        // One forwarder keeps deliveries in the order they were decided, whichever thread decided them.
        scope.launch { for (event in deliveries) _events.emit(event) }
    }

    fun connect() = link.connect()

    /** Terminal: closes the stream for good. */
    fun close() {
        link.disconnect()
        deliveries.close()
    }

    internal fun pause() = link.pause()

    internal fun resume() = link.resume()

    internal fun networkPathRestored() = link.networkPathRestored()

    private companion object {
        const val EVENT_BUFFER = 256
    }
}

/** The account-wide stream: the live session list and the notification feed. */
class NotificationStream(
    scope: CoroutineScope,
    clientFactory: (SseCallbacks) -> SseClient,
) : TypedStream<NotificationEvent>(scope, { record -> NotificationEvent.from(record) }, clientFactory) {
    companion object {
        const val PATH = "/notifications/stream"
    }
}

/** One session's transcript stream. */
class SessionStream(
    val sessionId: String,
    scope: CoroutineScope,
    clientFactory: (SseCallbacks) -> SseClient,
) : TypedStream<SessionEvent>(scope, { record -> SessionEvent.from(record) }, clientFactory) {
    companion object {
        fun pathOf(sessionId: String) = "/session/$sessionId/stream"
    }
}
