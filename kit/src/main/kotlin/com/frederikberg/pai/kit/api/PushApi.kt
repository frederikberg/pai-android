package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.PushChannelsResponse
import com.frederikberg.pai.contract.WebPushSubscriptionCreated
import com.frederikberg.pai.contract.WebPushSubscriptionSummary
import com.frederikberg.pai.contract.WebPushTestResult
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The channels a device can mute pushes for, as this caller is offered them. */
suspend fun ApiClient.getPushChannels(): PushChannelsResponse =
    send(
        PushChannelsResponse.serializer(),
        "/push/channels",
    )

/** The server's public VAPID key, or null while none has been generated (a 404). */
suspend fun ApiClient.getVapidKey(): String? =
    try {
        send(JsonObject.serializer(), "/push/vapid-key")["key"]?.jsonPrimitive?.contentOrNull
    } catch (e: ApiError) {
        if (e.statusCode == 404) null else throw e
    }

/** Makes sure a key exists and answers it; the same key on every later call. */
suspend fun ApiClient.ensureVapidKey(): String =
    checkNotNull(
        send(JsonObject.serializer(), "/push/vapid-key", method = "POST")["key"]?.jsonPrimitive?.contentOrNull,
    ) {
        "the answer carries no key"
    }

/** Registers (or updates, by endpoint) a Web Push endpoint for the caller's own feed. */
suspend fun ApiClient.createPushSubscription(
    endpoint: String,
    p256dh: String,
    auth: String,
    userAgent: String? = null,
    mutedChannels: List<String>? = null,
): WebPushSubscriptionCreated {
    val body: JsonObject =
        buildJsonObject {
            put("endpoint", endpoint)
            put(
                "keys",
                buildJsonObject {
                    put("p256dh", p256dh)
                    put("auth", auth)
                },
            )
            userAgent?.let { put("user_agent", it) }
            mutedChannels?.let { put("muted_channels", JsonArray(it.map(::JsonPrimitive))) }
        }
    return send(
        WebPushSubscriptionCreated.serializer(),
        "/push/subscriptions",
        method = "POST",
        body = encode(JsonObject.serializer(), body),
    )
}

suspend fun ApiClient.listPushSubscriptions(): List<WebPushSubscriptionSummary> =
    send(ListSerializer(WebPushSubscriptionSummary.serializer()), "/push/subscriptions")

/** Replaces which channels one subscription is muted on. */
suspend fun ApiClient.setMutedChannels(
    subscriptionId: String,
    mutedChannels: List<String>,
): List<String> =
    send(
        JsonObject.serializer(),
        "/push/subscriptions/$subscriptionId",
        method = "PATCH",
        body =
            encode(
                JsonObject.serializer(),
                buildJsonObject { put("muted_channels", JsonArray(mutedChannels.map(::JsonPrimitive))) },
            ),
    )["muted_channels"].let { (it as? JsonArray)?.map { channel -> channel.jsonPrimitive.content }.orEmpty() }

suspend fun ApiClient.deletePushSubscription(subscriptionId: String) =
    sendNoContent("/push/subscriptions/$subscriptionId", "DELETE")

/** Sends a test push to the caller's own subscriptions. */
suspend fun ApiClient.sendTestPush(): WebPushTestResult =
    send(WebPushTestResult.serializer(), "/push/test", method = "POST")
