package com.frederikberg.pai.kit.session

import com.frederikberg.pai.kit.api.signOutCurrentSignIn
import com.frederikberg.pai.kit.auth.AuthMode
import com.frederikberg.pai.kit.auth.OidcClient
import com.frederikberg.pai.kit.auth.OidcError
import com.frederikberg.pai.kit.auth.OidcTokenHolder
import com.frederikberg.pai.kit.auth.OidcTokens
import com.frederikberg.pai.kit.auth.SignInFlow
import com.frederikberg.pai.kit.auth.TokenRefresher
import com.frederikberg.pai.kit.auth.TokenStore
import com.frederikberg.pai.kit.live.Connectivity
import com.frederikberg.pai.kit.live.NetworkStatus
import com.frederikberg.pai.kit.live.StreamConnections
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import com.frederikberg.pai.kit.net.RequestFactory
import com.frederikberg.pai.kit.stores.MeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.URI

/**
 * Owns the one question the whole app hangs off: is there a credential, and what is built on it.
 *
 * The credential lives in a [TokenStore] (sealed on the device); a restart reads it back and the
 * connection is rebuilt from it, so signing in once is enough until the person signs out or the
 * server refuses the credential for good. The browser leg of the sign-in belongs to the platform:
 * [beginSignIn] hands back the address to open and [completeSignIn] takes what the browser returned.
 */
class SessionController(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val tokenStore: TokenStore,
    private val environment: PaiEnvironment,
    private val connectivity: Connectivity,
    /** What the server's list of signed-in devices calls this phone; sent with every request. */
    private val deviceName: String,
    /**
     * Asked at the start of a sign-out for what to undo on the server for this phone besides its sign-in
     * row (its push subscription); the answer runs while the credential still works.
     */
    private val deviceCleanup: () -> suspend (ApiClient) -> Unit,
    /** False for fixture mode, which has no event stream to open. */
    private val liveUpdates: Boolean = true,
    /** False for fixture mode, which has no server or provider to tell about a sign-out. */
    private val revokesAtProvider: Boolean = true,
    private val oidc: OidcClient = OidcClient(httpClient),
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _signInStatus = MutableStateFlow<SignInStatus>(SignInStatus.Idle)
    val signInStatus: StateFlow<SignInStatus> = _signInStatus.asStateFlow()

    private val networkStatus = NetworkStatus(scope, connectivity)
    private val signInFlow = SignInFlow(oidc)

    /** A sign-in whose browser leg is open: held until the browser comes back, never persisted. */
    private val lock = Any()
    private var pending: SignInFlow.Pending? = null

    @Volatile
    private var current: Connection? = null

    init {
        connectivity.observe { online -> if (online) current?.streams?.networkPathRestored() }
    }

    /** Brings the connection up from the stored credential, if there is one. */
    fun restore() {
        val mode = tokenStore.read() ?: return
        if (mode == AuthMode.None) return
        runCatching { connect(mode) }
    }

    /**
     * Starts the sign-in and returns the address the browser opens, or null after setting
     * [signInStatus] to the reason it could not start.
     */
    suspend fun beginSignIn(): String? {
        _signInStatus.value = SignInStatus.Working
        return try {
            val started = signInFlow.begin(environment.signIn)
            synchronized(lock) { pending = started }
            _signInStatus.value = SignInStatus.WaitingForBrowser
            started.authorizationUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcError) {
            fail(e.message.orEmpty())
        }
    }

    /** [callbackUrl] is the full redirect the browser ended on. */
    suspend fun completeSignIn(callbackUrl: String) {
        val attempt = synchronized(lock) { pending.also { pending = null } }
        if (attempt == null) {
            fail("The sign-in did not complete. Start it again.")
            return
        }
        if (!RedirectUri.matches(attempt.redirectUri, callbackUrl)) {
            fail("The sign-in answer did not belong to this app.")
            return
        }
        _signInStatus.value = SignInStatus.Exchanging
        try {
            val tokens = signInFlow.complete(attempt, callbackUrl)
            val mode = AuthMode.Oidc(tokens)
            tokenStore.write(mode)
            connect(mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcError) {
            fail(e.message.orEmpty())
        }
    }

    /** The device has no browser that can show the sign-in page. */
    fun reportBrowserUnavailable() {
        synchronized(lock) { pending = null }
        fail("No browser is available to sign in with. Install one and try again.")
    }

    /** The person left the browser without finishing. */
    fun cancelSignIn() {
        synchronized(lock) { pending = null }
        _signInStatus.value = SignInStatus.Idle
    }

    /**
     * Forgets the credential on this phone at once. The connection's own client keeps the credential in
     * memory a little longer for the clean-up, which is best effort and bounded and never delays the
     * sign-out: the phone's push subscription is removed and its sign-in ended on the server (which
     * also drops its push tokens), then the provider is asked to end the sign-in so the stored refresh
     * token is no longer good anywhere. An unreachable server leaves the sign-in to be ended from the
     * list of signed-in devices and the refresh token to expire.
     */
    fun signOut() {
        val tokens = (tokenStore.read() as? AuthMode.Oidc)?.tokens
        val connection = current
        val cleanup = if (connection != null && revokesAtProvider) deviceCleanup() else null
        closeCurrent()
        tokenStore.clear()
        if (connection != null && cleanup != null) {
            scope.launch {
                releaseDevice(connection.api, cleanup)
                if (tokens != null) revokeAtProvider(tokens)
            }
        } else if (tokens != null && revokesAtProvider) {
            scope.launch { revokeAtProvider(tokens) }
        }
        _signInStatus.value = SignInStatus.Idle
        _state.value = SessionState.SignedOut()
    }

    /** The app left the foreground: every stream closes instead of dying unobserved. */
    fun onBackground() {
        current?.streams?.pause()
    }

    /** The app is back: the streams reconnect at once and replay what they missed, and the role is read again. */
    fun onForeground() {
        val connection = current ?: return
        connection.streams.resume()
        scope.launch { connection.me.load() }
    }

    private suspend fun releaseDevice(
        api: ApiClient,
        cleanup: suspend (ApiClient) -> Unit,
    ) {
        try {
            withTimeout(RELEASE_TIMEOUT_MS) {
                cleanup(api)
                api.signOutCurrentSignIn()
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (e: ApiError) {
            // Nothing to retry against and nobody to tell: the person has already left.
        }
    }

    private suspend fun revokeAtProvider(tokens: OidcTokens) {
        try {
            withTimeout(REVOKE_TIMEOUT_MS) { oidc.revoke(tokens, oidc.discover(tokens.issuer)) }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (e: OidcError) {
            // Nothing to retry against and nobody to tell: the person has already left.
        }
    }

    private fun fail(message: String): Nothing? {
        _signInStatus.value = SignInStatus.Failed(message)
        return null
    }

    private fun connect(mode: AuthMode) {
        // Oidc sessions keep their token set in one holder the request factory reads at send time, so a
        // renewed access token reaches the next request; the refresher stores each renewal first.
        val holder = (mode as? AuthMode.Oidc)?.let { OidcTokenHolder(it.tokens) }
        val refresher =
            holder?.let {
                TokenRefresher(
                    scope = scope,
                    holder = it,
                    client = oidc,
                    persist = { tokens -> tokenStore.write(AuthMode.Oidc(tokens)) },
                    reload = { (tokenStore.read() as? AuthMode.Oidc)?.tokens },
                )
            }
        val factory = RequestFactory(environment.serverUrl, deviceName) { holder?.authMode ?: mode }

        lateinit var connection: Connection
        val rejected = { detail: String? -> handleRejection(connection, detail) }
        val api =
            ApiClient(
                factory,
                httpClient,
                onAuthenticationFailure = { error -> rejected(error.userMessage) },
                credentialRefresher = refresher,
            )
        val streams =
            StreamConnections.forClient(
                scope = scope,
                requests = factory,
                httpClient = httpClient,
                credentialRefresher = refresher,
                onAuthenticationFailure = { rejected("Your sign-in has ended.") },
            )
        val me = MeStore(api)
        connection = Connection(environment.serverUrl, api, streams, me, networkStatus, scope)

        synchronized(lock) {
            closeCurrent()
            current = connection
        }
        if (liveUpdates) streams.notifications.connect()
        scope.launch { me.load() }
        _signInStatus.value = SignInStatus.Idle
        _state.value = SessionState.SignedIn(connection)
    }

    /**
     * The server refused the credential for good: back to the sign-in screen, saying so. Every
     * request in flight reports its own refusal, so this runs many times at once; it ends the session
     * once and the later callers return only after the signed-out state is visible.
     */
    private fun handleRejection(
        connection: Connection,
        detail: String?,
    ): Unit =
        synchronized(lock) {
            if (current !== connection) return
            closeCurrent()
            tokenStore.clear()
            _state.value = SessionState.SignedOut(Rejection(detail))
        }

    private fun closeCurrent() =
        synchronized(lock) {
            current?.streams?.closeAll()
            current?.me?.clear()
            current = null
        }
}

private const val REVOKE_TIMEOUT_MS = 10_000L

private const val RELEASE_TIMEOUT_MS = 6_000L

/** Compares the redirect the browser ended on with the one the sign-in asked for: scheme, host and path, never the query. */
object RedirectUri {
    fun matches(
        expected: String,
        actual: String,
    ): Boolean {
        val want = runCatching { URI(expected) }.getOrNull() ?: return false
        val got = runCatching { URI(actual) }.getOrNull() ?: return false
        return want.scheme.equals(got.scheme, ignoreCase = true) &&
            want.rawAuthority.equals(got.rawAuthority, ignoreCase = true) &&
            want.rawPath.orEmpty() == got.rawPath.orEmpty()
    }
}
