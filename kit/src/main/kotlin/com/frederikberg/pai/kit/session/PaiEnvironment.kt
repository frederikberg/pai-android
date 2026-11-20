package com.frederikberg.pai.kit.session

import com.frederikberg.pai.kit.auth.OidcConfiguration

/**
 * The one backend this app talks to and the identity provider it signs in with, compiled in. The
 * client is a public Zitadel native application, so its id is no secret; the redirect is the app's
 * own scheme, which the manifest registers for the callback activity.
 */
data class PaiEnvironment(
    val serverUrl: String,
    val signIn: OidcConfiguration,
) {
    companion object {
        /** The address the person-facing PAI Cloud answers on; every API route lives under its `/api`. */
        const val SERVER_URL = "https://pai.frederikberg.com"

        /** The scheme, host and path the provider redirects the browser back to. */
        const val REDIRECT_URI = "pai://oauth/callback"

        /**
         * The roles scope puts the person's project roles into the access token; the audience scope
         * makes it valid at MailVerdict too, because PAI Cloud forwards the caller's own bearer there
         * for the MailVerdict assistant.
         */
        const val SCOPES =
            "openid profile email offline_access urn:zitadel:iam:org:projects:roles " +
                "urn:zitadel:iam:org:project:id:398140807388856625:aud"

        val production =
            PaiEnvironment(
                serverUrl = SERVER_URL,
                signIn =
                    OidcConfiguration(
                        issuer = "https://login.bergrunde.net",
                        clientId = "399443595255349553",
                        redirectUri = REDIRECT_URI,
                        scopes = SCOPES,
                    ),
            )
    }
}
