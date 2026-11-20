package com.frederikberg.pai.kit.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectUriTest {
    private val expected = "pai://oauth/callback"

    @Test
    fun theRedirectWithItsAnswerMatches() {
        assertTrue(RedirectUri.matches(expected, "pai://oauth/callback?code=c&state=s"))
    }

    @Test
    fun schemeAndHostIgnoreCase() {
        assertTrue(RedirectUri.matches(expected, "Pai://OAUTH/callback?code=c"))
    }

    @Test
    fun anotherSchemeHostOrPathDoesNot() {
        assertFalse(RedirectUri.matches(expected, "https://auth/callback?code=c"))
        assertFalse(RedirectUri.matches(expected, "pai://evil/callback?code=c"))
        assertFalse(RedirectUri.matches(expected, "pai://oauth/other?code=c"))
        assertFalse(RedirectUri.matches(expected, "pai://oauth/callback/extra?code=c"))
    }

    @Test
    fun somethingThatIsNotAUriDoesNot() {
        assertFalse(RedirectUri.matches(expected, "not a uri"))
    }
}
