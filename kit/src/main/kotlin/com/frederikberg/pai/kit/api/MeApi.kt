package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.MeResponse
import com.frederikberg.pai.contract.UserSettings
import com.frederikberg.pai.kit.net.ApiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The caller's identity, role and namespaces. */
suspend fun ApiClient.getMe(): MeResponse = send(MeResponse.serializer(), "/me")

suspend fun ApiClient.getUserSettings(): UserSettings = send(UserSettings.serializer(), "/settings/me")

/** Writes any subset of the caller's settings; a key left out stays as it was. */
suspend fun ApiClient.updateUserSettings(fields: JsonObject): UserSettings =
    send(
        UserSettings.serializer(),
        "/settings/me",
        method = "PUT",
        body = encode(JsonObject.serializer(), fields),
    )

/** Whether an agent's notification to this person may also go out by e-mail. */
suspend fun ApiClient.setEmailNotifications(enabled: Boolean): UserSettings =
    updateUserSettings(JsonObject(mapOf("email_notifications" to JsonPrimitive(enabled))))
