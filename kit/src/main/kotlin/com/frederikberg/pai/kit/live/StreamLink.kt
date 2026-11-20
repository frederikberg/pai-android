package com.frederikberg.pai.kit.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a screen's own subtitle reads: "Connecting…" for [Reconnecting], "Offline" for [Disconnected]. */
enum class ConnectionState {
    /** Never connected, or closed on purpose (signed out, a screen left). */
    Disconnected,

    /** The stream retries with capped backoff forever once connected, so losing it always becomes this and never [Disconnected]. */
    Reconnecting,

    Connected,
}

/**
 * One event stream as the app lives with it: an [SseClient] plus the connection state a screen
 * shows. Every record the client hands over reaches [onRecord] in order; the typed streams decode
 * them. [onRefused] fires once when the server says the thing streamed does not exist any more.
 *
 * [clientFactory] builds the stream around the link's own callbacks, so the link never sees
 * transport details.
 */
class StreamLink(
    private val scope: CoroutineScope,
    private val onRecord: (SseRecord) -> Unit,
    private val onRefused: (statusCode: Int, detail: String?) -> Unit = { _, _ -> },
    clientFactory: (SseCallbacks) -> SseClient,
) {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * [connectionState] as a screen should show it: a reconnect that finishes within
     * [RECONNECTING_GRACE_MS] (launch, a return from the background, a proxy recycling the stream)
     * never surfaces, so "Connecting…" means the connection is genuinely struggling.
     */
    private val _visibleConnectionState = MutableStateFlow(ConnectionState.Connected)
    val visibleConnectionState: StateFlow<ConnectionState> = _visibleConnectionState.asStateFlow()

    private val lock = Any()
    private var visibleStateJob: Job? = null

    /** Set by [disconnect]: the stream's own farewell callback must not move the state again. */
    private var closed = false

    private val client: SseClient =
        clientFactory(
            object : SseCallbacks {
                override fun onRecord(record: SseRecord) = this@StreamLink.onRecord(record)

                override fun onConnected() = setConnectionState(ConnectionState.Connected)

                override fun onDisconnected() = streamDisconnected()

                override fun onRefused(
                    statusCode: Int,
                    detail: String?,
                ) = this@StreamLink.onRefused(statusCode, detail)
            },
        )

    /** Set while the app is in the background: a stream asked to connect then waits for [resume]. */
    private var held = false
    private var wantsConnection = false
    private var clientStarted = false

    fun connect() {
        synchronized(lock) {
            wantsConnection = true
            if (held || closed) return
        }
        startClient()
    }

    private fun startClient() {
        synchronized(lock) {
            clientStarted = true
            updateState(ConnectionState.Reconnecting)
        }
        client.connect()
    }

    /** Terminal: closes the stream for good (signed out, or the screen it served is gone). */
    fun disconnect() {
        synchronized(lock) { closed = true }
        client.disconnect()
        synchronized(lock) { updateState(ConnectionState.Disconnected) }
    }

    /** The app went to the background: the stream closes instead of dying unobserved. [resume] replays what was missed. */
    fun pause() {
        synchronized(lock) {
            held = true
            if (closed || !clientStarted || _connectionState.value == ConnectionState.Disconnected) return
            updateState(ConnectionState.Reconnecting, surface = false)
        }
        client.pause()
    }

    /** Back in the foreground: reconnect now, not after whatever backoff had accumulated. */
    fun resume() {
        val firstStart: Boolean
        synchronized(lock) {
            held = false
            if (closed) return
            firstStart = wantsConnection && !clientStarted
            if (clientStarted) updateVisibleState()
        }
        when {
            firstStart -> startClient()
            clientStarted -> client.resume()
        }
    }

    /** The device's network path came back: a stream waiting out its backoff tries now. */
    fun networkPathRestored() {
        if (_connectionState.value == ConnectionState.Reconnecting) client.retryNow()
    }

    private fun setConnectionState(state: ConnectionState) = synchronized(lock) { if (!closed) updateState(state) }

    private fun streamDisconnected() = synchronized(lock) { if (!closed) updateState(ConnectionState.Reconnecting) }

    private fun updateState(
        state: ConnectionState,
        surface: Boolean = true,
    ) {
        _connectionState.value = state
        if (surface) updateVisibleState()
    }

    private fun updateVisibleState() {
        val state = _connectionState.value
        if (state != ConnectionState.Reconnecting) {
            visibleStateJob?.cancel()
            visibleStateJob = null
            _visibleConnectionState.value = state
            return
        }
        // A grace period already running is not restarted by every failed attempt, or a stream retrying faster than the period would never surface.
        if (_visibleConnectionState.value == ConnectionState.Reconnecting || visibleStateJob?.isActive == true) return
        if (_visibleConnectionState.value == ConnectionState.Disconnected) {
            _visibleConnectionState.value = ConnectionState.Connected
        }
        visibleStateJob =
            scope.launch {
                delay(RECONNECTING_GRACE_MS)
                if (_connectionState.value == ConnectionState.Reconnecting) {
                    _visibleConnectionState.value = ConnectionState.Reconnecting
                }
            }
    }

    companion object {
        const val RECONNECTING_GRACE_MS: Long = 3_000
    }
}
