package com.frederikberg.pai.kit.auth

import com.frederikberg.pai.contract.ContractJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateTest {
    /** What a key-store-backed [TokenStore] writes and reads back: every mode survives its own encoding, refresh token included. */
    @Test
    fun everyAuthModeSurvivesItsJsonEncoding() {
        val modes =
            listOf(
                AuthMode.None,
                AuthMode.Oidc(OidcTokens("a", "r", 1_700_000_000_000, "https://i", "c")),
                AuthMode.Oidc(OidcTokens("a", null, null, "https://i", "c")),
            )
        for (mode in modes) {
            val text = ContractJson.encodeToString(AuthMode.serializer(), mode)
            assertEquals(mode, ContractJson.decodeFromString(AuthMode.serializer(), text))
        }
    }

    @Test
    fun theTokenSetIsMaskedWhenPrinted() {
        val printed = OidcTokens("secret-access", "secret-refresh", null, "https://i", "c").toString()
        assertFalse(printed, "secret-access" in printed || "secret-refresh" in printed)
    }

    @Test
    fun expiryHonoursTheSkew() {
        fun tokens(expiresAtMs: Long?) = OidcTokens("a", null, expiresAtMs, "i", "c")
        assertTrue(tokens(1_000 + 59_000).isExpired(nowMs = 1_000))
        assertFalse(tokens(1_000 + 61_000).isExpired(nowMs = 1_000))
        assertFalse(
            "a token with no stated lifetime is used until the server refuses it",
            tokens(null).isExpired(nowMs = 1_000),
        )
    }
}
