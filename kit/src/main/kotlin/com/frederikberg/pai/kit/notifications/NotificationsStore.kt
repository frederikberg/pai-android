package com.frederikberg.pai.kit.notifications

import com.frederikberg.pai.contract.PaiNotification
import com.frederikberg.pai.kit.api.ReadTarget
import com.frederikberg.pai.kit.api.listNotifications
import com.frederikberg.pai.kit.api.markNotificationsRead
import com.frederikberg.pai.kit.api.markNotificationsUnread
import com.frederikberg.pai.kit.live.NotificationEvent
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The feed as a screen draws it. */
sealed interface NotificationsState {
    data object Loading : NotificationsState

    data class Failed(
        val message: String,
    ) : NotificationsState

    /** [rows] newest first; [hasMore] says older rows exist; [loadingMore] a page of them is on its way. */
    data class Loaded(
        val rows: List<PaiNotification>,
        val hasMore: Boolean,
        val loadingMore: Boolean = false,
    ) : NotificationsState
}

/**
 * The notification feed: a first page, older pages on demand, an unread-only view, and the live stream
 * folded in. Which rows are read is the server's to say, so a read from another device reloads the
 * rows already on screen rather than guessing.
 */
class NotificationsStore(
    private val api: ApiClient,
    private val pageSize: Int = PAGE_SIZE,
) {
    private val _state = MutableStateFlow<NotificationsState>(NotificationsState.Loading)
    val state: StateFlow<NotificationsState> = _state.asStateFlow()

    private val _unreadOnly = MutableStateFlow(false)
    val unreadOnly: StateFlow<Boolean> = _unreadOnly.asStateFlow()

    /** The unread count across the whole feed, whichever rows are loaded. */
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** What the last action that failed said, in words; null when there is nothing to say. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    /** Reads the first page again under the current view. Rows already shown stay if the read fails. */
    suspend fun load() = reload(pageSize)

    suspend fun setUnreadOnly(only: Boolean) {
        if (_unreadOnly.value == only) return
        _unreadOnly.value = only
        // A different view is a different list, which starts from its own first page.
        _state.value = NotificationsState.Loading
        reload(pageSize)
    }

    /** Appends the next older page; ignored while one is under way or when there is none. */
    suspend fun loadMore() {
        val current = _state.value as? NotificationsState.Loaded ?: return
        if (!current.hasMore || current.loadingMore || current.rows.isEmpty()) return
        _state.value = current.copy(loadingMore = true)
        try {
            val page = api.listNotifications(pageSize, current.rows.last().id, unreadOnly = _unreadOnly.value)
            _unread.value = page.unread
            _state.update { state ->
                val loaded = state as? NotificationsState.Loaded ?: return@update state
                val known = loaded.rows.map { it.id }.toSet()
                loaded.copy(
                    rows = loaded.rows + page.notifications.filter { it.id !in known },
                    hasMore = page.hasMore,
                    loadingMore = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _notice.value = e.userMessage
            _state.update { (it as? NotificationsState.Loaded)?.copy(loadingMore = false) ?: it }
        }
    }

    suspend fun markRead(ids: List<String>) = mark(ids, read = true)

    suspend fun markUnread(id: String) = mark(listOf(id), read = false)

    /** Marks every unread row of the feed read, rows beyond the loaded ones included. */
    suspend fun markAllRead() {
        try {
            api.markNotificationsRead(ReadTarget.All)
            reload(loadedCount())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _notice.value = e.userMessage
        }
    }

    /** Folds one record of the account stream in. */
    suspend fun onEvent(event: NotificationEvent) {
        when (event) {
            is NotificationEvent.Notification -> {
                _unread.value = event.event.unread
                val row = event.event.notification
                _state.update { state ->
                    val loaded = state as? NotificationsState.Loaded ?: return@update state
                    val skip = loaded.rows.any { it.id == row.id } || (_unreadOnly.value && row.readAt != null)
                    if (skip) return@update state
                    loaded.copy(rows = listOf(row) + loaded.rows)
                }
            }
            is NotificationEvent.Read -> {
                _unread.value = event.unread
                reload(loadedCount())
            }
            else -> Unit
        }
    }

    /** Applies [events] until the flow ends; the caller's scope bounds it. */
    suspend fun follow(events: Flow<NotificationEvent>) = events.collect { onEvent(it) }

    private fun loadedCount(): Int =
        (_state.value as? NotificationsState.Loaded)?.rows?.size?.coerceAtLeast(pageSize) ?: pageSize

    private suspend fun reload(limit: Int) {
        try {
            val page = api.listNotifications(limit.coerceAtMost(MAX_RELOAD), unreadOnly = _unreadOnly.value)
            _unread.value = page.unread
            _state.value = NotificationsState.Loaded(page.notifications, page.hasMore)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // A feed already on screen stays; only a first load has nothing to fall back to.
            if (_state.value is NotificationsState.Loaded) {
                _notice.value = e.userMessage
            } else {
                _state.value = NotificationsState.Failed(e.userMessage)
            }
        }
    }

    private suspend fun mark(
        ids: List<String>,
        read: Boolean,
    ) {
        if (ids.isEmpty()) return
        try {
            if (read) api.markNotificationsRead(ReadTarget.Ids(ids)) else api.markNotificationsUnread(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _notice.value = e.userMessage
            return
        }
        reload(loadedCount())
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val MAX_RELOAD = 200
    }
}
