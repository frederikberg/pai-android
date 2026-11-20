package com.frederikberg.pai.kit.sessions

import com.frederikberg.pai.contract.Session
import com.frederikberg.pai.kit.api.SessionsPage
import com.frederikberg.pai.kit.api.listSessions
import com.frederikberg.pai.kit.api.sessionsChangedSince
import com.frederikberg.pai.kit.api.setSessionPinned
import com.frederikberg.pai.kit.live.ConnectionState
import com.frederikberg.pai.kit.live.NotificationEvent
import com.frederikberg.pai.kit.live.StreamConnections
import com.frederikberg.pai.kit.net.ApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Everything the session list reads from or tells the server; tests stand a fake in its place. */
interface SessionsRemote {
    suspend fun firstPage(limit: Int): SessionsPage

    suspend fun nextPage(
        cursor: String,
        limit: Int,
    ): SessionsPage

    suspend fun changedSince(
        since: String,
        limit: Int,
    ): List<Session>

    suspend fun setPinned(
        id: String,
        pinned: Boolean,
    ): Session

    /** Emits whenever the list should be synced: the server said it changed, or the stream came back and may have missed something. */
    fun syncRequests(): Flow<Unit>
}

class ApiSessionsRemote(
    private val api: ApiClient,
    private val streams: StreamConnections,
) : SessionsRemote {
    override suspend fun firstPage(limit: Int) = api.listSessions(limit = limit)

    override suspend fun nextPage(
        cursor: String,
        limit: Int,
    ) = api.listSessions(cursor = cursor, limit = limit)

    override suspend fun changedSince(
        since: String,
        limit: Int,
    ) = api.sessionsChangedSince(since, limit)

    override suspend fun setPinned(
        id: String,
        pinned: Boolean,
    ) = api.setSessionPinned(id, pinned)

    override fun syncRequests(): Flow<Unit> =
        merge(
            streams.notifications.events.filter { it is NotificationEvent.SessionsChanged }.map { },
            streams.notifications.connectionState.filter { it == ConnectionState.Connected }.map { },
        )
}
