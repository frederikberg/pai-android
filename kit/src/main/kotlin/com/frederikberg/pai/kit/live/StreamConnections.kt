package com.frederikberg.pai.kit.live

import com.frederikberg.pai.kit.auth.CredentialRefreshing
import com.frederikberg.pai.kit.net.RequestFactory
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.Collections

/**
 * Builds the app's event streams around one sign-in and keeps track of the open ones, so the app
 * going to the background, coming back, or the network returning reaches every stream at once.
 *
 * [notifications] lives as long as the sign-in; a session stream is opened by the screen that shows
 * the session and closed when the screen goes ([openSession] hands it out already registered).
 */
class StreamConnections(
    private val scope: CoroutineScope,
    private val requests: RequestFactory,
    private val transport: SseTransport,
    private val credentialRefresher: CredentialRefreshing?,
    private val onAuthenticationFailure: () -> Unit,
    private val timing: SseTiming = SseTiming(),
) {
    private val open: MutableSet<TypedStream<*>> = Collections.synchronizedSet(LinkedHashSet())

    @Volatile private var inBackground = false

    val notifications: NotificationStream =
        NotificationStream(scope) { callbacks -> client(callbacks, NotificationStream.PATH, null) }.also { open += it }

    /**
     * A transcript stream for [sessionId]. With [resumeFromEventId] the server sends only what follows
     * that entry; without it a fresh connection replays the whole history, newest page first.
     */
    fun openSession(
        sessionId: String,
        resumeFromEventId: Long? = null,
    ): SessionStream {
        val stream =
            SessionStream(sessionId, scope) { callbacks ->
                client(callbacks, SessionStream.pathOf(sessionId), resumeFromEventId?.toString())
            }
        open += stream
        if (inBackground) stream.pause()
        return stream
    }

    /** Closes a session stream and forgets it. */
    fun closeSession(stream: SessionStream) {
        open -= stream
        stream.close()
    }

    fun pause() {
        inBackground = true
        snapshot().forEach { it.pause() }
    }

    fun resume() {
        inBackground = false
        snapshot().forEach { it.resume() }
    }

    fun networkPathRestored() = snapshot().forEach { it.networkPathRestored() }

    /** Sign-out: every stream closes for good. */
    fun closeAll() {
        snapshot().forEach { it.close() }
        open.clear()
    }

    private fun snapshot(): List<TypedStream<*>> = synchronized(open) { open.toList() }

    private fun client(
        callbacks: SseCallbacks,
        path: String,
        initialEventId: String?,
    ) = SseClient(
        scope = scope,
        requests = requests,
        transport = transport,
        callbacks = callbacks,
        path = path,
        initialEventId = initialEventId,
        credentialRefresher = credentialRefresher,
        onAuthenticationFailure = onAuthenticationFailure,
        timing = timing,
    )

    /** Convenience for the app wiring, where the transport is built from the app's OkHttp client. */
    companion object {
        fun forClient(
            scope: CoroutineScope,
            requests: RequestFactory,
            httpClient: OkHttpClient,
            credentialRefresher: CredentialRefreshing?,
            onAuthenticationFailure: () -> Unit,
        ) = StreamConnections(
            scope,
            requests,
            OkHttpSseTransport(httpClient),
            credentialRefresher,
            onAuthenticationFailure,
        )
    }
}
