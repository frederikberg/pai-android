package com.frederikberg.pai.kit.stores

import com.frederikberg.pai.contract.MeResponse
import com.frederikberg.pai.contract.UserRole

/**
 * What the signed-in person may see, read from the one place that knows: `GET /api/me`. Until the
 * answer arrives nothing owner-only is offered, and a role this app does not know is a guest's: the
 * server refuses the owner's routes to a guest anyway, so showing less is the safe misreading.
 */
data class Capabilities(
    val role: UserRole?,
) {
    /** The owner reaches every namespace and the surfaces only they have. */
    val isOwner: Boolean get() = role == UserRole.Owner

    /** Alert notifications and their push channel exist for the owner only. */
    val seesAlerts: Boolean get() = isOwner

    companion object {
        /** Before the first answer: the least a person can have. */
        val Unknown = Capabilities(null)

        fun of(me: MeResponse?): Capabilities = if (me == null) Unknown else Capabilities(me.role)
    }
}
