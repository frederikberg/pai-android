package com.frederikberg.pai.kit.stores

import com.frederikberg.pai.contract.UserRole
import com.frederikberg.pai.kit.OidcFixtures
import com.frederikberg.pai.kit.TestServer
import com.frederikberg.pai.kit.auth.AuthMode
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.RequestFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeStoreTest {
    private val server = TestServer()
    private val store =
        MeStore(ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient()))

    @After
    fun stop() = server.close()

    private fun meAs(role: String) =
        TestServer.json(
            """{"identity":"x@example.com","role":"$role","user_id":null,"display_name":"X",
               "allowed_session_ids":[],"namespaces":[]}""",
        )

    @Test
    fun nothingOwnerOnlyIsOfferedUntilTheServerHasSaidWhoThisIs() {
        assertFalse(store.capabilities.value.isOwner)
        assertFalse(store.capabilities.value.seesAlerts)
        assertNull(store.me.value)
    }

    @Test
    fun theOwnerSeesWhatOnlyTheOwnerHas() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))

        runBlocking { store.load() }

        assertTrue(store.capabilities.value.isOwner)
        assertTrue(store.capabilities.value.seesAlerts)
        assertEquals("Owner", store.me.value?.displayName)
    }

    @Test
    fun aGuestSeesNoAlerts() {
        server.script("/api/me", meAs("guest"))

        runBlocking { store.load() }

        assertFalse(store.capabilities.value.isOwner)
        assertFalse(store.capabilities.value.seesAlerts)
    }

    @Test
    fun aRoleThisAppDoesNotKnowIsTreatedAsAGuest() {
        server.script("/api/me", meAs("auditor"))

        runBlocking { store.load() }

        assertEquals(UserRole("auditor"), store.capabilities.value.role)
        assertFalse(store.capabilities.value.isOwner)
    }

    @Test
    fun aFailedReloadKeepsTheLastAnswerSoTheOwnerKeepsTheirScreens() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        runBlocking { store.load() }
        server.script("/api/me", TestServer.jsonError(500, "boom"))

        runBlocking { store.load() }

        assertTrue(store.capabilities.value.isOwner)
        assertEquals("boom", store.errorMessage.value)
    }

    @Test
    fun clearingForgetsWhoWasSignedIn() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        runBlocking { store.load() }

        store.clear()

        assertFalse(store.capabilities.value.isOwner)
        assertNull(store.me.value)
    }
}
