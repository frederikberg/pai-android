package com.frederikberg.pai.kit.push

import com.frederikberg.pai.contract.PushChannelInfo
import com.frederikberg.pai.kit.api.deletePushSubscription
import com.frederikberg.pai.kit.api.ensureVapidKey
import com.frederikberg.pai.kit.api.getPushChannels
import com.frederikberg.pai.kit.api.getVapidKey
import com.frederikberg.pai.kit.api.sendTestPush
import com.frederikberg.pai.kit.api.setMutedChannels
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** What the phone's side of push is doing, for the screen that explains and controls it. */
sealed interface PushStatus {
    data object Loading : PushStatus

    /** The server could not be asked at all. */
    data class LoadFailed(
        val message: String,
    ) : PushStatus

    /** No distributor app is installed: the one thing the person has to do first. */
    data object NoDistributor : PushStatus

    data object Off : PushStatus

    /** Asked the distributor for an endpoint; its answer arrives through [PushController.onNewEndpoint]. */
    data object Registering : PushStatus

    data class Registered(
        val subscription: PushSubscriptionInfo,
    ) : PushStatus

    data class Failed(
        val message: String,
    ) : PushStatus
}

/** What a test push or a settings change came to, for the line the screen shows. */
sealed interface PushNotice {
    /** The server handed the test to at least one device. */
    data object TestSent : PushNotice

    /** The server had nobody to deliver the test to, or none accepted it. */
    data object TestNotDelivered : PushNotice

    data class Error(
        val message: String,
    ) : PushNotice
}

/** The connection a controller works through while someone is signed in. */
class PushSession(
    val api: ApiClient,
    val serverUrl: String,
)

/**
 * The state machine behind notifications: turning them on asks the server for its VAPID key and the
 * distributor for an endpoint, the endpoint goes to the server as a subscription, and the server then
 * pushes to the distributor. It lives as long as the app, because a distributor delivers an endpoint to a
 * process that may have just started with no screen open.
 */
class PushController(
    private val registrar: PushRegistrar,
    private val platform: PushPlatform,
    private val session: () -> PushSession?,
    private val userAgent: String,
    private val registrationTimeoutMs: Long = REGISTRATION_TIMEOUT_MS,
) {
    private val _status = MutableStateFlow<PushStatus>(PushStatus.Loading)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    private val _channels = MutableStateFlow<List<PushChannelInfo>>(emptyList())

    /** The channels this person may mute, as the server offers them to them. */
    val channels: StateFlow<List<PushChannelInfo>> = _channels.asStateFlow()

    private val _notice = MutableStateFlow<PushNotice?>(null)
    val notice: StateFlow<PushNotice?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    /** Works out where things stand and re-asserts a registration that exists, so a changed endpoint is caught. */
    suspend fun refresh() {
        val current = session() ?: return
        try {
            _channels.value = current.api.getPushChannels().channels
            val subscription = registrar.current(current.api, current.serverUrl)
            if (!platform.hasDistributor()) {
                _status.value = if (subscription != null) PushStatus.Off else PushStatus.NoDistributor
                return
            }
            if (subscription != null) {
                _status.value = PushStatus.Registered(subscription)
                current.api.getVapidKey()?.let { platform.register(it, userAgent) }
                return
            }
            _status.value = PushStatus.Off
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _status.value = PushStatus.LoadFailed(e.userMessage)
        }
    }

    /** The person turned notifications on, with a distributor already chosen. */
    suspend fun enable() {
        val current = session() ?: return
        if (!platform.hasDistributor()) {
            _status.value = PushStatus.NoDistributor
            return
        }
        _status.value = PushStatus.Registering
        try {
            platform.register(current.api.ensureVapidKey(), userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _status.value = PushStatus.Failed(e.userMessage)
            return
        }
        // A distributor that never answers would leave the screen on "Setting up" for good.
        withTimeoutOrNull(registrationTimeoutMs) { _status.first { it != PushStatus.Registering } }
        _status.update { if (it == PushStatus.Registering) PushStatus.Failed(REGISTRATION_TIMEOUT_MESSAGE) else it }
    }

    /** The distributor's answer to a registration: hand the endpoint to the server. */
    suspend fun onNewEndpoint(endpoint: PushEndpointInfo) {
        val current = session() ?: return
        val muted = (_status.value as? PushStatus.Registered)?.subscription?.mutedChannels
        try {
            val subscription = registrar.register(current.api, current.serverUrl, endpoint, userAgent, muted)
            _status.value = PushStatus.Registered(subscription)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _status.value = PushStatus.Failed(e.userMessage)
        }
    }

    fun onRegistrationFailed(reason: String) {
        _status.value = PushStatus.Failed(reason)
    }

    /** The distributor dropped this app: the server's copy is useless, so it goes too. */
    suspend fun onUnregistered() {
        registrar.unregister(session()?.api)
        _status.update { if (it is PushStatus.Registered || it == PushStatus.Registering) PushStatus.Off else it }
    }

    suspend fun disable() {
        platform.unregister()
        registrar.unregister(session()?.api)
        _status.value = PushStatus.Off
    }

    /** Mutes or unmutes one channel for this phone's subscription. */
    suspend fun setMuted(
        channelId: String,
        muted: Boolean,
    ) {
        val current = session() ?: return
        val subscription = (_status.value as? PushStatus.Registered)?.subscription ?: return
        val channels = if (muted) (subscription.mutedChannels + channelId).distinct() else subscription.mutedChannels - channelId
        try {
            val now = current.api.setMutedChannels(subscription.id, channels)
            _status.value = PushStatus.Registered(subscription.copy(mutedChannels = now))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _notice.value = PushNotice.Error(e.userMessage)
        }
    }

    suspend fun sendTest() {
        val current = session() ?: return
        _notice.value =
            try {
                val result = current.api.sendTestPush()
                if (result.delivered > 0) PushNotice.TestSent else PushNotice.TestNotDelivered
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                PushNotice.Error(e.userMessage)
            }
    }

    /**
     * What removes this phone's subscription from the server, to run while the sign-in still works. The
     * subscription's id is captured now, so it can run after [onSignedOut] has made the phone forget it.
     */
    fun ownSubscriptionRemoval(): suspend (ApiClient) -> Unit {
        val id = registrar.record()?.subscriptionId ?: return { _ -> }
        return { api ->
            try {
                api.deletePushSubscription(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
                // The server drops a subscription whose endpoint stops answering.
            }
        }
    }

    /** Sign-out: nothing can be told to the server any more; the phone stops listening and forgets. */
    fun onSignedOut() {
        if (registrar.record() == null && _status.value == PushStatus.Loading) return
        platform.unregister()
        registrar.forget()
        _status.value = PushStatus.Loading
        _channels.value = emptyList()
    }

    companion object {
        const val REGISTRATION_TIMEOUT_MS = 30_000L
        const val REGISTRATION_TIMEOUT_MESSAGE = "The helper app did not answer. Open it once, then try again."
    }
}
