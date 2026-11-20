package com.frederikberg.pai.kit.auth

/**
 * Where and as what the app signs in: a public OpenID Connect client (PKCE instead of a secret) at
 * one issuer. The values are compiled in, never fetched, so signing in costs no round trip to ask
 * for them and a server cannot redirect it to another provider.
 */
data class OidcConfiguration(
    val issuer: String,
    val clientId: String,
    /** The address the provider sends the browser back to; the app's own scheme. */
    val redirectUri: String,
    /** Space-separated, as the authorization request carries them. */
    val scopes: String,
)
