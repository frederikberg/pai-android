package com.frederikberg.pai.kit.push

import com.frederikberg.pai.kit.api.createPushSubscription
import com.frederikberg.pai.kit.api.deletePushSubscription
import com.frederikberg.pai.kit.api.listPushSubscriptions
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** This phone's own subscription as the server holds it. */
data class PushSubscriptionInfo(
    val id: String,
    val mutedChannels: List<String>,
)

/** What this phone knows about its own subscription: which server holds it, under which id and endpoint. */
@Serializable
data class PushRecord(
    val serverUrl: String,
    val subscriptionId: String,
    val endpoint: String,
)

interface PushRecordStore {
    fun read(): PushRecord?

    fun write(record: PushRecord)

    fun clear()
}

class InMemoryPushRecordStore(
    private var record: PushRecord? = null,
) : PushRecordStore {
    override fun read() = record

    override fun write(record: PushRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}

/** The endpoint and key set the distributor handed over; [pubKey] and [auth] are base64url without padding. */
data class PushEndpointInfo(
    val url: String,
    val pubKey: String,
    val auth: String,
)

/**
 * Keeps the server's list of this phone's push endpoints right. The subscription is a plain Web Push one:
 * the distributor's endpoint plus the key set the connector generated, which the server encrypts to. The
 * server keys a subscription by endpoint and ties it to the account's feed, not to a sign-in, so signing
 * out does not remove it and this phone deletes its own row itself.
 */
class PushRegistrar(
    private val records: PushRecordStore,
) {
    /**
     * Registers [endpoint] with the server, replacing the one this phone registered before if it differs.
     * [mutedChannels] carries the channels already muted, so a refreshed endpoint does not switch them back on.
     */
    suspend fun register(
        api: ApiClient,
        serverUrl: String,
        endpoint: PushEndpointInfo,
        userAgent: String,
        mutedChannels: List<String>?,
    ): PushSubscriptionInfo {
        val previous = records.read()
        val created = api.createPushSubscription(endpoint.url, endpoint.pubKey, endpoint.auth, userAgent, mutedChannels)
        records.write(PushRecord(serverUrl, created.id, endpoint.url))
        if (previous != null && previous.serverUrl == serverUrl && previous.subscriptionId != created.id) {
            deleteQuietly(api, previous.subscriptionId)
        }
        return PushSubscriptionInfo(created.id, created.mutedChannels)
    }

    /**
     * This phone's subscription as the server lists it now, or null when there is none: the server drops
     * one its push service reports gone, or this server never had one.
     */
    suspend fun current(
        api: ApiClient,
        serverUrl: String,
    ): PushSubscriptionInfo? {
        val record = records.read()?.takeIf { it.serverUrl == serverUrl } ?: return null
        val found = api.listPushSubscriptions().firstOrNull { it.id == record.subscriptionId }
        if (found == null) records.clear()
        return found?.let { PushSubscriptionInfo(it.id, it.mutedChannels) }
    }

    /** Removes the subscription from the server and forgets it here. Forgets it here even when the server cannot be reached. */
    suspend fun unregister(api: ApiClient?) {
        val record = records.read() ?: return
        records.clear()
        if (api != null) deleteQuietly(api, record.subscriptionId)
    }

    fun record(): PushRecord? = records.read()

    fun forget() = records.clear()

    private suspend fun deleteQuietly(
        api: ApiClient,
        id: String,
    ) {
        try {
            api.deletePushSubscription(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            // The server removes an endpoint that stops answering; a failed delete leaves nothing worse behind.
        }
    }
}
