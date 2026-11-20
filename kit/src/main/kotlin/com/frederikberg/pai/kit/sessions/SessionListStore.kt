package com.frederikberg.pai.kit.sessions

import com.frederikberg.pai.contract.Session
import com.frederikberg.pai.contract.SseStatusEvent
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** When the list renews its row positions. A burst of changes settles after [settleMs] of quiet, a list that never goes quiet after [settleMaxMs]. */
data class SettleTiming(
    val settleMs: Long = 2_500,
    val settleMaxMs: Long = 10_000,
    /** Touching or scrolling the list counts as using it for this long. */
    val interactionIdleMs: Long = 1_000,
    /** Once the list is free again, the pending settle runs after this pause. */
    val releaseMs: Long = 500,
    /** A sync starts this far before its cursor, to catch a row that committed after the cursor passed its stamp. */
    val syncOverlapMs: Long = 3_000,
)

sealed interface SessionListPhase {
    data object Loading : SessionListPhase

    data object Ready : SessionListPhase

    data class Failed(
        val message: String,
    ) : SessionListPhase
}

data class SessionListState(
    val phase: SessionListPhase = SessionListPhase.Loading,
    /** The rows the filter keeps, in their held order. */
    val rows: List<Session> = emptyList(),
    val filter: String = "",
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val refreshError: String? = null,
)

/**
 * The session list: the server's browse list paged in, kept live by incremental syncs that the
 * notification stream asks for, and ordered by a held placement ([SessionOrder]).
 *
 * A row's content changes the moment the server says so. Its position changes only when the list
 * settles, which it does not while the reader touches it or is scrolled away from the top; the
 * reader's own actions (a pin) settle at once.
 */
class SessionListStore(
    private val remote: SessionsRemote,
    private val scope: CoroutineScope,
    private val timing: SettleTiming = SettleTiming(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val order = SessionOrder()
    private val sessions = HashMap<String, Session>()
    private var cursor: String? = null
    private var lastSync: String? = null
    private var started = false
    private var syncing = false
    private var syncAgain = false
    private var firstPageLoaded = false

    private var settleJob: Job? = null
    private var interactionJob: Job? = null
    private var firstUnsettledAt: Long? = null
    private var unsettled = false
    private var interacting = false
    private var awayFromTop = false

    private val _state = MutableStateFlow(SessionListState())
    val state: StateFlow<SessionListState> = _state.asStateFlow()

    /** Reads the first page and starts listening for changes. Calling it again changes nothing. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch { remote.syncRequests().collect { requestSync() } }
        scope.launch { loadFirstPage() }
    }

    /** Pull to refresh, or retry after a failed first load. */
    fun refresh() {
        scope.launch { if (firstPageLoaded) sync() else loadFirstPage() }
    }

    private suspend fun loadFirstPage() {
        publish { it.copy(phase = SessionListPhase.Loading) }
        try {
            val page = remote.firstPage(PAGE_SIZE)
            synchronized(lock) {
                sessions.clear()
                page.sessions.filter(SessionRows::isListed).forEach { sessions[it.id] = it }
                order.placeFresh(sessions.values)
                cursor = page.nextCursor
                lastSync = maxUpdatedAt(page.sessions) ?: Instant.now().toString()
                firstPageLoaded = true
            }
            publish { it.copy(phase = SessionListPhase.Ready, refreshError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            publish { it.copy(phase = SessionListPhase.Failed(e.userMessage)) }
        }
    }

    fun loadMore() {
        val next =
            synchronized(lock) {
                if (_state.value.loadingMore) return
                cursor ?: return
            }
        publish { it.copy(loadingMore = true) }
        scope.launch {
            try {
                val page = remote.nextPage(next, PAGE_SIZE)
                synchronized(lock) {
                    val appended = page.sessions.filter { SessionRows.isListed(it) && it.id !in sessions }
                    appended.forEach { sessions[it.id] = it }
                    order.place(appended)
                    cursor = page.nextCursor
                }
                publish { it.copy(loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                publish { it.copy(loadingMore = false) }
            }
        }
    }

    /** A request that arrives while a sync runs asks for another one behind it: the running one may have been sent before the change. */
    fun requestSync() {
        synchronized(lock) {
            if (!firstPageLoaded) return
            if (syncing) {
                syncAgain = true
                return
            }
            syncing = true
        }
        scope.launch {
            try {
                do {
                    synchronized(lock) { syncAgain = false }
                    sync()
                } while (synchronized(lock) { syncAgain })
            } finally {
                synchronized(lock) { syncing = false }
            }
        }
    }

    private suspend fun sync() {
        val since = synchronized(lock) { lastSync } ?: return
        try {
            var from = overlapped(since)
            var newest = since
            val changed = ArrayList<Session>()
            while (true) {
                val page = remote.changedSince(from, PAGE_SIZE)
                if (page.isEmpty()) break
                changed += page
                val pageMax = maxUpdatedAt(page)
                if (pageMax != null && micros(pageMax) > micros(newest)) newest = pageMax
                if (page.size < PAGE_SIZE || pageMax == null) break
                from = pageMax
            }
            merge(changed, newest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // The cursor has not moved: the next request asks again.
            publish(rebuild = false) { it.copy(refreshError = e.userMessage) }
        }
    }

    private fun merge(
        changed: List<Session>,
        newest: String,
    ) {
        var touched = false
        var reorders = false
        synchronized(lock) {
            for (incoming in changed) {
                if (incoming.status.raw == "deleted" || !SessionRows.isListed(incoming)) {
                    touched = (sessions.remove(incoming.id) != null) || touched
                    order.forget(incoming.id)
                    continue
                }
                val held = sessions[incoming.id]
                if (held != null && isStale(held, incoming)) continue
                // A row new to this list waits at the bottom for the next settle; a known row keeps its place however its content moved.
                if (held == null) order.placeLast(incoming.id)
                // State fields are pushed live over the open session's stream and a sync can lag behind them.
                val merged =
                    incoming.copy(
                        state = incoming.state ?: held?.state,
                        blocker = incoming.blocker ?: held?.blocker,
                        turnState = incoming.turnState ?: held?.turnState,
                        displayState = incoming.displayState ?: held?.displayState,
                    )
                sessions[incoming.id] = merged
                touched = true
                if (held == null || order.isMisplaced(merged)) reorders = true
            }
            lastSync = newest
        }
        if (reorders) markUnsettled()
        if (touched) publish { it.copy(refreshError = null) } else publish(rebuild = false) { it.copy(refreshError = null) }
    }

    /** A status the open session's stream pushed: its dot, blocker and counts follow at once; its position is not touched. */
    fun applyStatus(
        id: String,
        status: SseStatusEvent,
    ) {
        val applied =
            synchronized(lock) {
                val held = sessions[id] ?: return
                sessions[id] =
                    held.copy(
                        state = status.state ?: held.state,
                        blocker = status.blocker,
                        turnState = status.turnState ?: held.turnState,
                        displayState = status.displayState ?: held.displayState,
                        activityCounts = status.activityCounts ?: held.activityCounts,
                    )
                true
            }
        if (applied) publish { it }
    }

    /** The row for [id], for a screen that shows one session's title. */
    fun session(id: String): Session? = synchronized(lock) { sessions[id] }

    fun setFilter(text: String) {
        if (text == _state.value.filter) return
        publish { it.copy(filter = text) }
    }

    fun setPinned(
        id: String,
        pinned: Boolean,
    ) {
        scope.launch {
            try {
                val updated = remote.setPinned(id, pinned)
                synchronized(lock) { sessions[id] = updated }
                settleNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                publish(rebuild = false) { it.copy(refreshError = e.userMessage) }
            }
        }
    }

    // --- When positions are renewed -----------------------------------------------------------

    private fun held() = interacting || awayFromTop

    private fun markUnsettled() {
        synchronized(lock) {
            val now = clock()
            val first = firstUnsettledAt ?: now.also { firstUnsettledAt = it }
            unsettled = true
            schedule(maxOf(0L, minOf(timing.settleMs, first + timing.settleMaxMs - now)))
        }
    }

    private fun schedule(delayMs: Long) {
        settleJob?.cancel()
        settleJob =
            scope.launch {
                delay(delayMs)
                if (synchronized(lock) { !held() }) settleNow()
            }
    }

    /** The reader acted: every placement is renewed now, pending changes included. */
    fun settleNow() {
        synchronized(lock) {
            settleJob?.cancel()
            settleJob = null
            unsettled = false
            firstUnsettledAt = null
            order.place(sessions.values)
        }
        publish { it }
    }

    private fun release() {
        synchronized(lock) { if (unsettled && !held()) schedule(timing.releaseMs) }
    }

    /** The list is being used (a touch, a scroll): the order is held until it has been idle a moment. */
    fun noteInteraction() {
        synchronized(lock) {
            interacting = true
            interactionJob?.cancel()
            interactionJob =
                scope.launch {
                    delay(timing.interactionIdleMs)
                    synchronized(lock) { interacting = false }
                    release()
                }
        }
    }

    /** Whether the list is scrolled away from its top; the rows in view stay where they are until it returns. */
    fun setAwayFromTop(away: Boolean) {
        synchronized(lock) {
            if (awayFromTop == away) return
            awayFromTop = away
        }
        if (!away) release()
    }

    /** The list left the screen: nothing is being looked at, so nothing is held. */
    fun releaseHold() {
        synchronized(lock) {
            interactionJob?.cancel()
            interactionJob = null
            interacting = false
            awayFromTop = false
        }
        release()
    }

    private fun publish(
        rebuild: Boolean = true,
        change: (SessionListState) -> SessionListState,
    ) {
        synchronized(lock) {
            val changed = change(_state.value)
            _state.value =
                if (rebuild) {
                    changed.copy(rows = ordered(changed.filter), hasMore = cursor != null)
                } else {
                    changed
                }
        }
    }

    private fun ordered(filter: String): List<Session> =
        sessions.values.sortedWith(order.comparator).filter { SessionRows.matches(it, filter) }

    private fun maxUpdatedAt(rows: List<Session>): String? = rows.mapNotNull { it.updatedAt }.maxByOrNull(::micros)

    /** Whether [incoming] is a version of [held] no newer than the one already held: an answer that was in flight when something newer landed. */
    private fun isStale(
        held: Session,
        incoming: Session,
    ): Boolean {
        val a = held.updatedAt ?: return false
        val b = incoming.updatedAt ?: return false
        return micros(b) <= micros(a)
    }

    private fun overlapped(cursor: String): String =
        try {
            OffsetDateTime.parse(cursor).toInstant().minusMillis(timing.syncOverlapMs).toString()
        } catch (_: DateTimeParseException) {
            cursor
        }

    /** Microsecond precision of a server timestamp; the stamps differ below the millisecond. */
    private fun micros(iso: String): Long =
        try {
            val at = OffsetDateTime.parse(iso).toInstant()
            at.epochSecond * 1_000_000 + at.nano / 1_000
        } catch (_: DateTimeParseException) {
            0L
        }

    companion object {
        const val PAGE_SIZE = 100
    }
}
