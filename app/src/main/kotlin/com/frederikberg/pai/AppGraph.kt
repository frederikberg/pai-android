package com.frederikberg.pai

import android.content.Context
import com.frederikberg.pai.contract.ContractJson
import com.frederikberg.pai.kit.auth.AuthMode
import com.frederikberg.pai.kit.auth.InMemoryTokenStore
import com.frederikberg.pai.kit.auth.OidcTokens
import com.frederikberg.pai.kit.auth.SealedFileTokenStore
import com.frederikberg.pai.kit.fixtures.FixtureInterceptor
import com.frederikberg.pai.kit.fixtures.Fixtures
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.push.NoPushPlatform
import com.frederikberg.pai.kit.push.PushPlatform
import com.frederikberg.pai.kit.session.ConnectionStores
import com.frederikberg.pai.kit.session.PaiEnvironment
import com.frederikberg.pai.kit.session.SessionController
import com.frederikberg.pai.platform.AlwaysOnline
import com.frederikberg.pai.platform.AndroidConnectivity
import com.frederikberg.pai.platform.AndroidKeystoreKey
import com.frederikberg.pai.platform.AppPreferences
import com.frederikberg.pai.platform.DeviceNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File

/**
 * Constructor wiring for everything the screens share. Nothing is injected by a framework: a
 * dependency is a property here, built in [create], and passed down by hand.
 */
class AppGraph private constructor(
    /** True when requests are answered from canned data instead of a server. */
    val fixtureMode: Boolean,
    val json: Json,
    val httpClient: OkHttpClient,
    /** Outlives every screen: the live streams, token renewal and the browser leg of sign-in run here. */
    val appScope: CoroutineScope,
    val preferences: AppPreferences,
    val session: SessionController,
    /** Stores kept for as long as one sign-in lasts. */
    val connectionStores: ConnectionStores,
    /** How this phone receives notifications; none is wired yet. */
    val push: PushPlatform,
) {
    companion object {
        /** The fixture session's server; nothing listens there, the interceptor answers first. */
        private const val FIXTURE_SERVER = "https://fixture.invalid"

        private val FIXTURE_TOKENS = OidcTokens(accessToken = "fixture", issuer = FIXTURE_SERVER, clientId = "fixture")

        fun create(
            context: Context,
            fixtureMode: Boolean,
        ): AppGraph {
            val client =
                OkHttpClient
                    .Builder()
                    .apply { if (fixtureMode) addInterceptor(FixtureInterceptor(Fixtures.routes)) }
                    .build()
            val preferences = AppPreferences(context, fixtureMode)
            val deviceName = DeviceNames.of(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val noCleanup: () -> suspend (ApiClient) -> Unit = { { _ -> } }
            val session =
                if (fixtureMode) {
                    SessionController(
                        scope,
                        client,
                        InMemoryTokenStore(AuthMode.Oidc(FIXTURE_TOKENS)),
                        PaiEnvironment.production.copy(serverUrl = FIXTURE_SERVER),
                        AlwaysOnline,
                        deviceName,
                        noCleanup,
                        liveUpdates = false,
                        revokesAtProvider = false,
                    )
                } else {
                    SessionController(
                        scope,
                        client,
                        SealedFileTokenStore(File(context.filesDir, "auth.bin"), AndroidKeystoreKey::get),
                        PaiEnvironment.production,
                        AndroidConnectivity(context),
                        deviceName,
                        noCleanup,
                    )
                }
            session.restore()
            Timber.i("app graph created, fixtureMode=%s", fixtureMode)
            return AppGraph(
                fixtureMode,
                ContractJson,
                client,
                scope,
                preferences,
                session,
                ConnectionStores(scope, session.state),
                NoPushPlatform,
            )
        }
    }
}
