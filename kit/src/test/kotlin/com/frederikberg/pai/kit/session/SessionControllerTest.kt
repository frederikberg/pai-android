package com.frederikberg.pai.kit.session

import com.frederikberg.pai.kit.OidcFixtures
import com.frederikberg.pai.kit.TestServer
import com.frederikberg.pai.kit.api.getMe
import com.frederikberg.pai.kit.auth.AuthMode
import com.frederikberg.pai.kit.auth.InMemoryTokenStore
import com.frederikberg.pai.kit.auth.OidcConfiguration
import com.frederikberg.pai.kit.auth.TokenStore
import com.frederikberg.pai.kit.live.Connectivity
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionControllerTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tokens = InMemoryTokenStore()
    private val callbacks = "pai://oauth/callback"

    private val connectivity =
        object : Connectivity {
            override val isOnline = true

            override fun observe(listener: (online: Boolean) -> Unit) = Unit
        }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun environment() =
        PaiEnvironment(
            serverUrl = server.baseUrl,
            signIn =
                OidcConfiguration(
                    issuer = server.baseUrl,
                    clientId = OidcFixtures.CLIENT_ID,
                    redirectUri = callbacks,
                    scopes = "openid",
                ),
        )

    private fun controller(store: TokenStore = tokens) =
        SessionController(
            scope,
            OkHttpClient(),
            store,
            environment(),
            connectivity,
            "Test phone",
            { cleanup },
            liveUpdates = false,
        )

    /** What the controller is told to undo on the server at sign-out besides ending the sign-in. */
    private var cleanup: suspend (ApiClient) -> Unit = { _ -> }

    /** The whole browser round trip, ending signed in; [revocable] makes the provider name a revocation endpoint. */
    private fun signIn(
        controller: SessionController,
        revocable: Boolean = false,
    ) {
        serveProvider()
        if (revocable) server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server, revocable = true))
        val url = runBlocking { controller.beginSignIn() }!!
        runBlocking { controller.completeSignIn("$callbacks?code=c&state=${stateOf(url)}") }
    }

    private fun signedInConnection(controller: SessionController) =
        (controller.state.value as SessionState.SignedIn).connection

    private fun serveProvider() {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server))
        server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("access-1", "refresh-1"))
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
    }

    private fun stateOf(authorizationUrl: String) = authorizationUrl.toHttpUrl().queryParameter("state")!!

    @Test
    fun nothingStoredStartsSignedOut() {
        val controller = controller()
        controller.restore()

        assertEquals(SessionState.SignedOut(), controller.state.value)
    }

    @Test
    fun aStoredCredentialBringsTheConnectionBackAfterARestart() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        tokens.write(AuthMode.Oidc(OidcFixtures.tokens(server.baseUrl, access = "stored")))
        val controller = controller()
        controller.restore()

        val connection = signedInConnection(controller)
        assertEquals("Owner", runBlocking { connection.api.getMe() }.displayName)
        assertEquals("Bearer stored", server.requestsTo("/api/me").last().headers["Authorization"])
    }

    @Test
    fun theWholeBrowserRoundTripEndsSignedInWithTheTokensStored() {
        serveProvider()
        val controller = controller()
        val url = runBlocking { controller.beginSignIn() }!!
        assertEquals(SignInStatus.WaitingForBrowser, controller.signInStatus.value)

        runBlocking { controller.completeSignIn("$callbacks?code=the-code&state=${stateOf(url)}") }

        signedInConnection(controller)
        val stored = tokens.read() as AuthMode.Oidc
        assertEquals("access-1", stored.tokens.accessToken)
        val body = OidcFixtures.bodyOf(server.requestsTo(OidcFixtures.TOKEN).single())
        assertTrue(body, "code=the-code" in body)
    }

    @Test
    fun aCallbackFromAnotherAttemptNeverSpendsTheCode() {
        serveProvider()
        val controller = controller()
        runBlocking { controller.beginSignIn() }

        runBlocking { controller.completeSignIn("$callbacks?code=stolen&state=another") }

        assertTrue(controller.signInStatus.value is SignInStatus.Failed)
        assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
        assertNull(tokens.read())
    }

    @Test
    fun aCallbackOnAnotherAddressIsRefusedBeforeAnythingIsSpent() {
        serveProvider()
        val controller = controller()
        val url = runBlocking { controller.beginSignIn() }!!

        runBlocking { controller.completeSignIn("pai://other/callback?code=c&state=${stateOf(url)}") }

        assertTrue(controller.signInStatus.value is SignInStatus.Failed)
        assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
    }

    @Test
    fun aCallbackWithNoAttemptInProgressSaysSoInsteadOfDoingNothing() {
        val controller = controller()
        runBlocking { controller.completeSignIn("$callbacks?code=c&state=s") }

        assertTrue(controller.signInStatus.value is SignInStatus.Failed)
    }

    @Test
    fun leavingTheBrowserWithoutFinishingForgetsTheAttempt() {
        serveProvider()
        val controller = controller()
        val url = runBlocking { controller.beginSignIn() }!!
        controller.cancelSignIn()

        runBlocking { controller.completeSignIn("$callbacks?code=c&state=${stateOf(url)}") }

        assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
    }

    @Test
    fun aCredentialTheServerRefusesEndsTheSessionAndSaysSo() {
        val controller = controller()
        signIn(controller)
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "token_expired"))

        runCatching { runBlocking { connection.api.getMe() } }

        val out = controller.state.value as SessionState.SignedOut
        assertEquals("token_expired", out.rejection?.detail)
        assertNull(tokens.read())
    }

    /** Every request in flight when the credential dies reports its own refusal; the session ends once, cleanly. */
    @Test
    fun manyRequestsRefusedTogetherEndTheSessionOnce() {
        val controller = controller()
        signIn(controller)
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "token_expired"))
        val states = mutableListOf<SessionState>()
        val watcher = scope.launch { controller.state.collect { states += it } }

        val failures =
            runBlocking {
                (1..40)
                    .map { async(Dispatchers.Default) { runCatching { connection.api.getMe() }.exceptionOrNull() } }
                    .awaitAll()
            }

        assertTrue(failures.joinToString { "$it" }, failures.all { it is ApiError })
        assertEquals(SessionState.SignedOut(Rejection("token_expired")), controller.state.value)
        assertNull(tokens.read())
        watcher.cancel()
        assertEquals(1, states.count { it is SessionState.SignedOut })
    }

    /** A refusal that arrives while another is still ending the session must not return before the sign-in screen is showing. */
    @Test
    fun aSecondRefusalReturnsOnlyOnceTheSessionHasEnded() {
        val clearing = CountDownLatch(1)
        val slowStore =
            object : TokenStore by tokens {
                override fun clear() {
                    clearing.countDown()
                    Thread.sleep(400)
                    tokens.clear()
                }
            }
        val controller = controller(slowStore)
        signIn(controller)
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "token_expired"))

        val first = scope.async { runCatching { connection.api.getMe() } }
        assertTrue(clearing.await(5, TimeUnit.SECONDS))
        runCatching { runBlocking { connection.api.getMe() } }

        assertTrue(controller.state.value is SessionState.SignedOut)
        runBlocking { first.await() }
    }

    @Test
    fun everyRequestCarriesTheDeviceName() {
        val controller = controller()
        signIn(controller)

        runBlocking { signedInConnection(controller).api.getMe() }

        assertEquals("Test phone", server.requestsTo("/api/me").last().headers["X-Device-Name"])
    }

    /** A revoked device would only get another token for the same device from a renewal, so none is asked for. */
    @Test
    fun aRevokedDeviceIsSignedOutWithoutRenewingTheToken() {
        val controller = controller()
        signIn(controller)
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "device_revoked"))
        val tokenRequests = server.requestsTo(OidcFixtures.TOKEN).size

        runCatching { runBlocking { connection.api.getMe() } }

        val out = controller.state.value as SessionState.SignedOut
        assertEquals(true, out.rejection?.deviceRevoked)
        assertNull(tokens.read())
        assertEquals(tokenRequests, server.requestsTo(OidcFixtures.TOKEN).size)
    }

    @Test
    fun signingOutAsksTheProviderToRevokeTheRefreshToken() {
        server.script(OidcFixtures.REVOKE, TestServer.status(200))
        val controller = controller()
        signIn(controller, revocable = true)

        controller.signOut()

        val body = awaitBody(OidcFixtures.REVOKE)
        assertTrue(body, "token=refresh-1" in body)
        assertTrue(body, "token_type_hint=refresh_token" in body)
        assertTrue(body, "client_id=${OidcFixtures.CLIENT_ID}" in body)
        assertNull(tokens.read())
    }

    @Test
    fun signingOutDoesNotWaitForAProviderThatIsNotThere() {
        val controller = controller()
        signIn(controller, revocable = true)
        server.close()

        controller.signOut()

        assertEquals(SessionState.SignedOut(), controller.state.value)
        assertNull(tokens.read())
    }

    @Test
    fun aProviderWithoutRevocationIsNotAskedToRevokeAnything() {
        val controller = controller()
        signIn(controller)

        controller.signOut()

        Thread.sleep(300)
        assertTrue(server.requestsTo(OidcFixtures.REVOKE).isEmpty())
    }

    private fun awaitRequests(
        path: String,
        count: Int = 1,
    ) {
        repeat(100) {
            if (server.requestsTo(path).size >= count) return
            Thread.sleep(50)
        }
        error("no request to $path")
    }

    private fun signInRow(revoked: Boolean) =
        """{"id":"mine","kind":"app","name":"Test phone","first_seen_at":"2026-11-14T08:00:00Z",
        |"last_seen_at":"2026-11-14T08:00:00Z","revoked_at":${if (revoked) "\"2026-11-14T09:00:00Z\"" else "null"},
        |"active":${!revoked},"current":true}
        """.trimMargin()

    @Test
    fun signingOutEndsThePhonesOwnSignInAfterTheCleanup() {
        val controller = controller()
        signIn(controller)
        val steps = java.util.concurrent.CopyOnWriteArrayList<String>()
        cleanup = { _ -> steps += "cleanup" }
        server.script("/api/sign-ins/current/sign-out", TestServer.json(signInRow(revoked = true)))

        controller.signOut()

        awaitRequests("/api/sign-ins/current/sign-out")
        assertEquals(listOf("cleanup"), steps)
        assertEquals("POST", server.requestsTo("/api/sign-ins/current/sign-out").single().method)
        assertNull(tokens.read())
        assertEquals(SessionState.SignedOut(), controller.state.value)
    }

    @Test
    fun theSignInIsEndedWithTheCredentialStillGoodAndBeforeTheProviderIsAsked() {
        val controller = controller()
        signIn(controller, revocable = true)
        server.script(OidcFixtures.REVOKE, TestServer.status(200))
        server.script("/api/sign-ins/current/sign-out", TestServer.json(signInRow(revoked = true)))

        controller.signOut()

        awaitRequests(OidcFixtures.REVOKE)
        val order = server.requests.map { it.url.encodedPath }
        val ended = order.indexOf("/api/sign-ins/current/sign-out")
        assertTrue(order.toString(), ended in 0 until order.indexOf(OidcFixtures.REVOKE))
        assertEquals(
            "Bearer access-1",
            server.requestsTo("/api/sign-ins/current/sign-out").single().headers["Authorization"],
        )
    }

    @Test
    fun theCleanupStillRunsWhenTheServerRefusesToEndTheSignIn() {
        val controller = controller()
        signIn(controller)
        val ran =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        cleanup = { _ -> ran.set(true) }
        server.script("/api/sign-ins/current/sign-out", TestServer.jsonError(500, "boom"))

        controller.signOut()

        awaitRequests("/api/sign-ins/current/sign-out")
        assertTrue(ran.get())
        assertEquals(SessionState.SignedOut(), controller.state.value)
    }

    @Test
    fun signingOutOfflineSignsOutAtOnce() {
        val controller = controller()
        signIn(controller)
        server.close()
        val started = System.nanoTime()

        controller.signOut()

        assertTrue("sign-out waited for the network", System.nanoTime() - started < 1_000_000_000L)
        assertEquals(SessionState.SignedOut(), controller.state.value)
        assertNull(tokens.read())
    }

    private fun awaitBody(path: String): String {
        repeat(100) {
            server.requestsTo(path).firstOrNull()?.let { return OidcFixtures.bodyOf(it) }
            Thread.sleep(50)
        }
        error("no request to $path")
    }

    @Test
    fun signingOutForgetsTheCredential() {
        val controller = controller()
        signIn(controller)

        controller.signOut()

        assertEquals(SessionState.SignedOut(), controller.state.value)
        assertNull(tokens.read())
    }
}
