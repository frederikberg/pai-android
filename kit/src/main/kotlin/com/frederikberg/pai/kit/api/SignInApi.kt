package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.SignIn
import com.frederikberg.pai.kit.net.ApiClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The caller's own sign-ins, most recently seen first; the one making the request has `current` set. */
suspend fun ApiClient.listSignIns(): List<SignIn> = send(ListSerializer(SignIn.serializer()), "/sign-ins")

/**
 * Ends the sign-in this very request runs under, which also drops the push tokens it registered:
 * what the app calls at sign-out, while it still holds its token.
 */
suspend fun ApiClient.signOutCurrentSignIn(): SignIn =
    send(
        SignIn.serializer(),
        "/sign-ins/current/sign-out",
        method = "POST",
    )

/** Signs one of the caller's own devices out; its next request is answered `device_revoked`. */
suspend fun ApiClient.revokeSignIn(signInId: String): SignIn =
    send(SignIn.serializer(), "/sign-ins/$signInId/revoke", method = "POST")

/** Ends every app and browser sign-in of the caller, this one included; answers how many. */
suspend fun ApiClient.signOutEverywhere(): Int =
    send(
        JsonObject.serializer(),
        "/sign-ins/sign-out-everywhere",
        method = "POST",
        body = encode(JsonObject.serializer(), JsonObject(emptyMap())),
    )["revoked"]?.jsonPrimitive?.intOrNull ?: 0
