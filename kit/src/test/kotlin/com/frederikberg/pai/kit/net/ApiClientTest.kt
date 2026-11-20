package com.frederikberg.pai.kit.net

import com.frederikberg.pai.kit.OidcFixtures
import com.frederikberg.pai.kit.TestServer
import com.frederikberg.pai.kit.api.cancelSession
import com.frederikberg.pai.kit.api.getMe
import com.frederikberg.pai.kit.api.listSessions
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class ApiClientTest {
    private val server = TestServer()

    @After
    fun stop() = server.close()

    private fun client(
        base: String = server.baseUrl,
        retry: ReadRetryPolicy = ReadRetryPolicy(delaysMs = listOf(1, 1)),
    ) = ApiClient(RequestFactory(base, "Test phone") { OidcFixtures.mode("t") }, OkHttpClient(), readRetry = retry)

    private fun <T> failure(block: suspend () -> T): ApiError =
        assertThrows(ApiError::class.java) {
            runBlocking { block() }
        }

    @Test
    fun aReadIsAskedAgainWhenTheProxyCannotReachTheBackend() =
        runBlocking {
            server.script("/api/me", TestServer.status(502), TestServer.status(504), TestServer.json(OidcFixtures.ME))
            assertEquals("Owner", client().getMe().displayName)
            assertEquals(3, server.requests.size)
        }

    @Test
    fun aReadGivesUpAfterItsRetriesAndSaysWhatTheServerSaid() {
        server.script("/api/me", TestServer.status(502))
        val error = failure { client().getMe() }
        assertEquals(502, error.statusCode)
        assertEquals(3, server.requests.size)
    }

    /** A write that may have landed is never repeated here: the action queue knows more than this client does. */
    @Test
    fun aWriteIsNeverRepeated() {
        server.script("/api/session/s1/cancel", TestServer.status(502))
        failure { client().cancelSession("s1") }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun a503IsNotRetriedBecauseTheServerSaidItOnPurpose() {
        server.script("/api/me", TestServer.jsonError(503, "semantic_unavailable"))
        val error = failure { client().getMe() }
        assertEquals("semantic_unavailable", error.userMessage)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aRedirectIsNotFollowedAndReadsAsALoginProxy() {
        server.script(
            "/api/me",
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", "${server.baseUrl}/login")
                .build(),
        )
        server.script("/login", TestServer.json("{}"))
        failure { client().getMe() }.also { assertTrue(it is ApiError.ProxyRequiresBrowserLogin) }
        assertTrue(server.requestsTo("/login").isEmpty())
    }

    @Test
    fun anHtmlAnswerWithStatus200ReadsAsALoginProxy() {
        server.script(
            "/api/me",
            MockResponse
                .Builder()
                .code(
                    200,
                ).addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html>")
                .build(),
        )
        assertTrue(failure { client().getMe() } is ApiError.ProxyRequiresBrowserLogin)
    }

    @Test
    fun anAnswerOfTheWrongShapeIsADecodingErrorThatKeepsItsTechnicalDetailApart() {
        server.script("/api/me", TestServer.json("""{"user_id":"u"}"""))
        val error = failure { client().getMe() } as ApiError.Decoding
        assertTrue(error.technicalDetail.isNotBlank())
        assertEquals("The server's response could not be read.", error.userMessage)
    }

    @Test
    fun aServerThatIsNotThereIsATransportErrorNotACrash() {
        val freePort = ServerSocket(0).use { it.localPort }
        val error = failure { client("http://127.0.0.1:$freePort", ReadRetryPolicy(delaysMs = emptyList())).getMe() }
        assertTrue(error is ApiError.Transport)
        assertEquals("Could not reach the server.", error.userMessage)
    }

    @Test
    fun aRequestCarriesTheBearerAndAsksForJson() =
        runBlocking {
            server.script("/api/me", TestServer.json(OidcFixtures.ME))
            client().getMe()
            val request = server.requests.single()
            assertEquals("Bearer t", request.headers["Authorization"])
            assertEquals("application/json", request.headers["Accept"])
        }

    @Test
    fun aSessionPageCarriesTheCursorOfTheNextOneFromItsHeader() =
        runBlocking {
            server.script(
                "/api/sessions",
                TestServer
                    .json("[]")
                    .newBuilder()
                    .addHeader("X-Next-Cursor", "abc")
                    .build(),
            )
            val page = client().listSessions(limit = 1)
            assertEquals("abc", page.nextCursor)
            assertEquals(
                "1",
                server.requests
                    .single()
                    .url
                    .queryParameter("limit"),
            )

            server.script("/api/sessions", TestServer.json("[]"))
            assertNull(client().listSessions().nextCursor)
        }

    @Test
    fun theFilenameOfADownloadComesFromContentDisposition() {
        assertEquals("plain.pdf", ApiClient.filenameFromContentDisposition("""attachment; filename="plain.pdf""""))
        assertEquals("bare.txt", ApiClient.filenameFromContentDisposition("attachment; filename=bare.txt"))
        // The RFC 5987 form the server always sends beside the plain one wins, and decodes.
        assertEquals(
            "Rechnung ä+.pdf",
            ApiClient.filenameFromContentDisposition(
                """attachment; filename="Rechnung _.pdf"; filename*=UTF-8''Rechnung%20%C3%A4%2B.pdf""",
            ),
        )
        assertNull(ApiClient.filenameFromContentDisposition("inline"))
        assertNull(ApiClient.filenameFromContentDisposition(null))
    }
}
