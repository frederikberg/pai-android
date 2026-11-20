package com.frederikberg.pai.kit

import com.frederikberg.pai.kit.auth.AuthMode
import com.frederikberg.pai.kit.auth.OidcConfiguration
import com.frederikberg.pai.kit.auth.OidcTokens
import mockwebserver3.MockResponse

/** One provider and one API served by the same [TestServer]; the issuer is the server itself. */
object OidcFixtures {
    const val CLIENT_ID = "native-client"
    const val DISCOVERY = "/.well-known/openid-configuration"
    const val TOKEN = "/oauth/v2/token"
    const val REVOKE = "/oauth/v2/revoke"

    fun config(issuer: String) =
        OidcConfiguration(
            issuer = issuer,
            clientId = CLIENT_ID,
            redirectUri = "pai://oauth/callback",
            scopes = "openid profile email offline_access",
        )

    fun tokens(
        issuer: String,
        access: String = "access-1",
        refresh: String? = "refresh-1",
        expiresAtMs: Long? = null,
    ) = OidcTokens(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMs = expiresAtMs,
        issuer = issuer,
        clientId = CLIENT_ID,
    )

    /** A signed-in mode whose access token is [access], for tests that only look at what a request carries. */
    fun mode(access: String = "t") = AuthMode.Oidc(tokens("https://idp.invalid", access = access, refresh = null))

    fun discovery(
        server: TestServer,
        issuer: String = server.baseUrl,
        revocable: Boolean = false,
    ): MockResponse {
        val revocation = if (revocable) ""","revocation_endpoint":"${server.baseUrl}$REVOKE"""" else ""
        return TestServer.json(
            """{"issuer":"$issuer","authorization_endpoint":"${server.baseUrl}/oauth/v2/authorize","token_endpoint":"${server.baseUrl}$TOKEN"$revocation}""",
        )
    }

    fun tokenAnswer(
        access: String,
        refresh: String?,
        expiresIn: Long = 3600,
        delayMs: Long = 0,
    ): MockResponse {
        val refreshField = refresh?.let { ""","refresh_token":"$it"""" }.orEmpty()
        return TestServer.json("""{"access_token":"$access"$refreshField,"expires_in":$expiresIn}""", delayMs = delayMs)
    }

    fun tokenRefusal(
        code: Int = 400,
        error: String = "invalid_grant",
    ) = TestServer.json("""{"error":"$error"}""", code)

    const val ME =
        """{"identity":"owner@example.com","role":"owner","user_id":"00000000-0000-0000-0000-000000000001",
            "display_name":"Owner","allowed_session_ids":[],"namespaces":[]}"""

    fun bodyOf(request: mockwebserver3.RecordedRequest): String = request.body?.utf8().orEmpty()
}
