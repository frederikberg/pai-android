package com.frederikberg.pai.kit.session

import com.frederikberg.pai.kit.live.NetworkStatus
import com.frederikberg.pai.kit.live.Reachability
import com.frederikberg.pai.kit.live.StreamConnections
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import com.frederikberg.pai.kit.stores.MeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The credential was refused or the device was revoked; [detail] is what the server said, if anything. */
data class Rejection(
    val detail: String?,
) {
    /** The server signed this device out from its list of devices, as opposed to refusing a credential. */
    val deviceRevoked: Boolean get() =
        detail == ApiError.DEVICE_REVOKED_DETAIL ||
            detail == ApiError.DEVICE_UNRECOGNISED_DETAIL
}

/** Whether the app has a credential to work with. */
sealed interface SessionState {
    /** [rejection] is set when a stored credential was refused, so the screen can say so instead of reading as a first launch. */
    data class SignedOut(
        val rejection: Rejection? = null,
    ) : SessionState

    data class SignedIn(
        val connection: Connection,
    ) : SessionState
}

/** Where a sign-in attempt stands, for the sign-in screen. */
sealed interface SignInStatus {
    data object Idle : SignInStatus

    /** Reading the provider's configuration before the browser opens. */
    data object Working : SignInStatus

    /** The browser is open on the provider's page; nothing comes back until the person finishes there. */
    data object WaitingForBrowser : SignInStatus

    /** The browser returned; the code is being traded for a token. */
    data object Exchanging : SignInStatus

    /** [message] is in words for the person. */
    data class Failed(
        val message: String,
    ) : SignInStatus
}

/**
 * Everything that exists only while signed in: one API client and the event streams, shared by every
 * screen. A new sign-in builds a new connection and the old one is closed first, so no second stream
 * is left reading nothing.
 */
class Connection(
    val serverUrl: String,
    val api: ApiClient,
    /** The account-wide stream and the openers of per-session streams. */
    val streams: StreamConnections,
    val me: MeStore,
    networkStatus: NetworkStatus,
    scope: CoroutineScope,
) {
    /** Whether the server can be reached, as every screen shows it. */
    val reachability: StateFlow<Reachability> =
        combine(networkStatus.pathAvailable, streams.notifications.visibleConnectionState) { path, stream ->
            Reachability.of(path, stream)
        }.stateIn(scope, SharingStarted.Eagerly, Reachability.Online)
}
