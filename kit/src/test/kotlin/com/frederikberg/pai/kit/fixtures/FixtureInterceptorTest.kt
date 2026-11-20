package com.frederikberg.pai.kit.fixtures

import com.frederikberg.pai.contract.ContractJson
import com.frederikberg.pai.contract.MeResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureInterceptorTest {
    private val interceptor = FixtureInterceptor(Fixtures.routes)
    private val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun get(path: String) =
        client
            .newCall(
                Request.Builder().url("http://fixture.invalid$path").build(),
            ).execute()

    @Test
    fun aKnownRouteDecodesIntoItsContractModel() {
        val body = get("/api/me").use { it.body.string() }

        val me = ContractJson.decodeFromString(MeResponse.serializer(), body)

        assertEquals("Alex Example", me.displayName)
    }

    @Test
    fun anUnknownRouteIsA404WithAJsonBody() {
        get("/nope").use { response ->
            assertEquals(404, response.code)
            assertEquals("""{"detail":"Not Found"}""", response.body.string())
        }
    }

    @Test
    fun theQueryStringDoesNotChangeTheRoute() {
        get("/api/me?verbose=1").use { assertEquals(200, it.code) }

        assertEquals(listOf("GET /api/me"), interceptor.requests)
    }
}
