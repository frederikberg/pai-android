package com.frederikberg.pai.kit.transcript

import com.frederikberg.pai.contract.AnswerBlockerResponse
import com.frederikberg.pai.contract.Message
import com.frederikberg.pai.kit.api.MessageWindow
import com.frederikberg.pai.kit.api.answerBlocker
import com.frederikberg.pai.kit.api.listMessages
import com.frederikberg.pai.kit.live.ConnectionState
import com.frederikberg.pai.kit.live.SessionEvent
import com.frederikberg.pai.kit.live.StreamConnections
import com.frederikberg.pai.kit.live.StreamGone
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.RawBody
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** What a session's live stream says, flattened so a store reads one flow. */
sealed interface TranscriptSignal {
    data class Event(
        val event: SessionEvent,
    ) : TranscriptSignal

    data class Link(
        val state: ConnectionState,
    ) : TranscriptSignal

    data class Gone(
        val gone: StreamGone,
    ) : TranscriptSignal
}

/** Everything a transcript reads from or tells the server; tests stand a fake in its place. */
interface TranscriptRemote {
    suspend fun page(
        sessionId: String,
        window: MessageWindow,
        limit: Int,
    ): List<Message>

    /**
     * The live stream of [sessionId], resuming after [resumeFromEventId] when given. Without a resume
     * point the server replays the whole history, so the store passes the newest id it already read.
     */
    fun signals(
        sessionId: String,
        resumeFromEventId: Long?,
    ): Flow<TranscriptSignal>

    suspend fun answerBlocker(
        sessionId: String,
        key: String,
    ): AnswerBlockerResponse

    /** A file the session's machine holds, fetched with the bearer. */
    suspend fun attachment(
        sessionId: String,
        path: String,
    ): RawBody
}

class ApiTranscriptRemote(
    private val api: ApiClient,
    private val streams: StreamConnections,
) : TranscriptRemote {
    override suspend fun page(
        sessionId: String,
        window: MessageWindow,
        limit: Int,
    ) = api.listMessages(sessionId, window, limit)

    override fun signals(
        sessionId: String,
        resumeFromEventId: Long?,
    ): Flow<TranscriptSignal> =
        channelFlow {
            val stream = streams.openSession(sessionId, resumeFromEventId)
            // Collectors first: the events flow replays nothing to a late subscriber.
            launch(start = CoroutineStart.UNDISPATCHED) {
                stream.events.collect { send(TranscriptSignal.Event(it)) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                stream.visibleConnectionState.collect { send(TranscriptSignal.Link(it)) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                stream.gone.filterNotNull().collect { send(TranscriptSignal.Gone(it)) }
            }
            stream.connect()
            awaitClose { streams.closeSession(stream) }
        }

    override suspend fun answerBlocker(
        sessionId: String,
        key: String,
    ) = api.answerBlocker(sessionId, key)

    override suspend fun attachment(
        sessionId: String,
        path: String,
    ) = api.getSessionAttachment(sessionId, path)
}

/** `GET /api/session/{id}/attachment?path=`: the bytes of a file named by a path on the session's machine. */
suspend fun ApiClient.getSessionAttachment(
    sessionId: String,
    path: String,
): RawBody = sendRaw("/session/$sessionId/attachment", listOf("path" to path))
