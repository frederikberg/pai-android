package com.frederikberg.pai.kit.push

/** What only the phone can do for notifications: talk to the distributor app that delivers them. */
interface PushPlatform {
    /** Whether a distributor app is installed. */
    fun hasDistributor(): Boolean

    /** Asks the chosen distributor for an endpoint, signing for the server's VAPID public key. */
    fun register(
        vapidPublicKey: String,
        label: String,
    )

    fun unregister()
}

/** Fixture runs, and builds that deliver no notifications, have no distributor to talk to. */
object NoPushPlatform : PushPlatform {
    override fun hasDistributor() = false

    override fun register(
        vapidPublicKey: String,
        label: String,
    ) = Unit

    override fun unregister() = Unit
}
