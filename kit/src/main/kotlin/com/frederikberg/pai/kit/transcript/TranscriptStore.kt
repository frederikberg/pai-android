package com.frederikberg.pai.kit.transcript

import com.frederikberg.pai.contract.Message
import com.frederikberg.pai.contract.SseStatusEvent
import com.frederikberg.pai.kit.api.MessageWindow
import com.frederikberg.pai.kit.live.ConnectionState
import com.frederikberg.pai.kit.live.SessionEvent
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TranscriptPhase {
    data object Loading : TranscriptPhase

    data object Ready : TranscriptPhase

    /** The first page could not be read; [message] is in words for the person. */
    data class Failed(
        val message: String,
    ) : TranscriptPhase

    /** The session does not exist any more. */
    data object Gone : TranscriptPhase
}

/** Everything the transcript screen draws. */
data class TranscriptState(
    val phase: TranscriptPhase = TranscriptPhase.Loading,
    val rows: List<TranscriptRow> = emptyList(),
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderFailed: Boolean = false,
    val status: SseStatusEvent? = null,
    val processing: Boolean = false,
    /** The list keeps itself at the live edge. */
    val following: Boolean = true,
    /** Messages that arrived while the reader was away from the live edge. */
    val newCount: Int = 0,
    /** Keys of the cards the reader opened; kept here so a card stays open when its row leaves the screen and returns. */
    val expanded: Set<String> = emptySet(),
    /** Counts up each time the reader asks for the end; the list answers each count with one immediate scroll. */
    val endRequest: Int = 0,
    val link: ConnectionState = ConnectionState.Connected,
    /** The blocker option being answered right now. */
    val answeringKey: String? = null,
    val answerError: String? = null,
)

/**
 * One session's transcript as the screen draws it: a bounded window of its messages turned into rows,
 * kept current by the live stream, with the rules for the reader's place around it.
 *
 * The first page is read over REST and the stream then opens after its newest entry: a stream
 * without a resume point replays the whole history, which a phone has no use for. Older pages are
 * read on demand and joined above. A message arriving while the reader is away from the live edge
 * joins the window below and is counted, never scrolled to.
 */
class TranscriptStore(
    val sessionId: String,
    private val remote: TranscriptRemote,
    private val scope: CoroutineScope,
    private val labels: TimeLabels = TimeLabels(),
    private val onStatus: (SseStatusEvent) -> Unit = {},
) {
    private val lock = Any()
    private var window = TranscriptWindow()
    private val latch = FollowLatch()
    private val rowCache = HashMap<Long, List<TranscriptRow>>()
    private var started = false
    private var job: Job? = null
    private var olderJob: Job? = null

    /** Messages that arrived below the reader while following was off. */
    private var pendingNew = 0

    private var viewport: ViewportAnchor? = null

    private val _state = MutableStateFlow(TranscriptState())
    val state: StateFlow<TranscriptState> = _state.asStateFlow()

    /** Reads the first page and opens the stream. Calling it again while it runs changes nothing. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        job = scope.launch { open() }
    }

    /** After a failed first page. */
    fun retry() {
        synchronized(lock) {
            if (_state.value.phase !is TranscriptPhase.Failed) return
            started = true
        }
        publish { it.copy(phase = TranscriptPhase.Loading) }
        job?.cancel()
        job = scope.launch { open() }
    }

    private suspend fun open() {
        val tail =
            try {
                remote.page(sessionId, MessageWindow.Tail, PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.statusCode == 404 || e.statusCode == 410) {
                    publish { it.copy(phase = TranscriptPhase.Gone) }
                } else {
                    publish { it.copy(phase = TranscriptPhase.Failed(e.userMessage)) }
                }
                return
            }
        val resume =
            synchronized(lock) {
                window = TranscriptWindow.opened(tail, PAGE_SIZE)
                window.newestId
            }
        publish { it.copy(phase = TranscriptPhase.Ready) }
        remote.signals(sessionId, resume).collect { handle(it, freshStream = resume == null) }
    }

    private fun handle(
        signal: TranscriptSignal,
        freshStream: Boolean,
    ) {
        when (signal) {
            is TranscriptSignal.Link -> publish(rebuild = false) { it.copy(link = signal.state) }
            is TranscriptSignal.Gone -> publish { it.copy(phase = TranscriptPhase.Gone) }
            is TranscriptSignal.Event -> handle(signal.event, freshStream)
        }
    }

    private fun handle(
        event: SessionEvent,
        freshStream: Boolean,
    ) {
        when (event) {
            is SessionEvent.Init -> onInit(event.page.entries, freshStream)
            is SessionEvent.Batch -> arrived(event.batch.entries, opening = false)
            is SessionEvent.Status -> {
                publish { it.copy(status = event.status) }
                onStatus(event.status)
            }
            is SessionEvent.Processing -> publish(rebuild = false) { it.copy(processing = event.processing.processing) }
            else -> Unit
        }
    }

    private fun onInit(
        entries: List<Message>,
        freshStream: Boolean,
    ) {
        val (empty, newest) = synchronized(lock) { window.messages.isEmpty() to window.newestId }
        // A stream without a resume point sends its newest page and then the whole history, older
        // pages last: only what follows the window is part of it.
        if (freshStream && newest != null && entries.isNotEmpty() && entries.all { it.id <= newest }) {
            synchronized(lock) { window = window.copy(hasOlder = true) }
            publish { it }
            return
        }
        arrived(entries, opening = freshStream && empty)
    }

    private fun arrived(
        entries: List<Message>,
        opening: Boolean,
    ) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            if (opening) {
                window = TranscriptWindow.opened(entries, PAGE_SIZE)
            } else {
                val (next, added) = window.arrived(entries)
                entries.forEach { rowCache.remove(it.id) }
                window = next
                val shownNew = entries.takeLast(added).count(MessageRouting::isShown)
                if (!latch.following && shownNew > 0) pendingNew += shownNew
            }
            if (latch.following && window.messages.size > LIVE_WINDOW_LIMIT) trimLocked()
        }
        publish { it }
    }

    private fun trimLocked() {
        window = window.trimmedToNewest(LIVE_WINDOW_LIMIT)
        val keep = window.messages.mapTo(HashSet()) { it.id }
        rowCache.keys.retainAll(keep)
    }

    /** Reads the page before the oldest held message and joins it above. */
    fun loadOlder() {
        val before =
            synchronized(lock) {
                if (!window.hasOlder || olderJob?.isActive == true) return
                window.oldestId ?: return
            }
        publish(rebuild = false) { it.copy(loadingOlder = true, olderFailed = false) }
        olderJob =
            scope.launch {
                try {
                    val page = remote.page(sessionId, MessageWindow.Before(before), PAGE_SIZE)
                    synchronized(lock) { window = window.withOlder(page, PAGE_SIZE) }
                    publish { it.copy(loadingOlder = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    publish(rebuild = false) { it.copy(loadingOlder = false, olderFailed = true) }
                }
            }
    }

    fun toggleExpanded(key: String) = publish { it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key) }

    /** A finger went down on the list, or a fling the reader started is carrying it. */
    fun readerBeganMoving() = synchronized(lock) { latch.readerBeganMoving() }

    fun readerStoppedMoving() = synchronized(lock) { latch.readerStoppedMoving() }

    /** Called before the app writes the scroll position itself. */
    fun appWillScroll() = synchronized(lock) { latch.appWillScroll() }

    /** A scroll sample; it re-arms or releases following only while the reader's own motion produced it. */
    fun scrolled(atEnd: Boolean) {
        val changed = synchronized(lock) { latch.sample(atEnd) }
        if (!changed) return
        synchronized(lock) {
            if (latch.following) {
                pendingNew = 0
                if (window.messages.size > LIVE_WINDOW_LIMIT) trimLocked()
            }
        }
        publish { it }
    }

    /** The reader asked for the end: following resumes and the list scrolls there once. */
    fun jumpToEnd() {
        synchronized(lock) {
            latch.engage()
            pendingNew = 0
            if (window.messages.size > LIVE_WINDOW_LIMIT) trimLocked()
        }
        publish { it.copy(endRequest = it.endRequest + 1) }
    }

    /** Where the reader is, reported by the screen as it changes, so returning to the transcript finds the same place. */
    fun reportViewport(anchor: ViewportAnchor?) = synchronized(lock) { viewport = anchor }

    /**
     * The place to return to, by the identity of the row that was at the top; null when the reader was
     * at the end or the row has left the window, which means the end. A place away from the end also
     * means following stays off until the reader brings it back.
     */
    fun restoredViewport(): ViewportAnchor? {
        val saved =
            synchronized(lock) {
                val anchor = viewport
                val inWindow = anchor != null && !anchor.atEnd && _state.value.rows.any { it.key == anchor.rowKey }
                if (inWindow) latch.restoredAwayFromEnd() else latch.engage()
                anchor.takeIf { inWindow }
            }
        publish(rebuild = false) { it }
        return saved
    }

    fun answerBlocker(key: String) {
        if (_state.value.answeringKey != null) return
        publish(rebuild = false) { it.copy(answeringKey = key, answerError = null) }
        scope.launch {
            try {
                remote.answerBlocker(sessionId, key)
                publish(rebuild = false) { it.copy(answeringKey = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                publish(rebuild = false) { it.copy(answeringKey = null, answerError = e.userMessage) }
            }
        }
    }

    fun clearAnswerError() = publish(rebuild = false) { it.copy(answerError = null) }

    /** The whole body behind a row's "show more", or null once its message has left the window. */
    fun fullText(rowKey: String): FullText? {
        val id = rowKey.removePrefix("m").substringBefore(':').toLongOrNull() ?: return null
        val message = synchronized(lock) { window.messages.firstOrNull { it.id == id } } ?: return null
        return FullText.of(message, rowKey)
    }

    /** A file the session's machine holds, for the pictures and chips in rows. */
    suspend fun attachment(path: String) = remote.attachment(sessionId, path)

    private fun publish(
        rebuild: Boolean = true,
        change: (TranscriptState) -> TranscriptState,
    ) {
        synchronized(lock) {
            val changed = change(_state.value)
            val next =
                if (rebuild) {
                    changed.copy(
                        rows = rows(changed.status),
                        hasOlder = window.hasOlder,
                        following = latch.following,
                        newCount = pendingNew,
                    )
                } else {
                    changed.copy(following = latch.following, newCount = pendingNew)
                }
            _state.value = next
        }
    }

    private fun rows(status: SseStatusEvent?): List<TranscriptRow> {
        val out = ArrayList<TranscriptRow>(window.messages.size + 4)
        var previousLabel: String? = null
        for (message in window.messages) {
            if (!MessageRouting.isShown(message)) continue
            val built = rowCache.getOrPut(message.id) { MessageRouting.rows(message, labels) }
            val first = built.firstOrNull() ?: continue
            val label = first.timeLabel
            out += if (label != null && label == previousLabel) listOf(first.withTimeLabel(null)) + built.drop(1) else built
            if (label != null) previousLabel = label
        }
        out += pendingRows(status)
        return out
    }

    private fun pendingRows(status: SseStatusEvent?): List<TranscriptRow> {
        if (status == null) return emptyList()
        val confirmed = window.messages.mapNotNullTo(HashSet()) { it.outboxId }
        val sends = status.pendingSends.filter { it.id !in confirmed }
        val rows = sends.map { TranscriptRow.Pending("p${it.id}", Bounded.of(it.text, RowBounds.USER_LINES), it.held) }
        // Queued texts carry no id; the ones a pending send already names are the same send.
        val named = sends.mapTo(HashSet()) { it.text }
        val queued =
            status.queuedTexts.withIndex().filter { it.value !in named }.map { (index, text) ->
                TranscriptRow.Pending("q$index", Bounded.of(text, RowBounds.USER_LINES), held = false)
            }
        return rows + queued
    }

    companion object {
        const val PAGE_SIZE = 100

        /** The window is trimmed to its newest page this far past, only while the reader follows the live edge. */
        const val LIVE_WINDOW_LIMIT = 600
    }
}
