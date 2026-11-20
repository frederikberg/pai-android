package com.frederikberg.pai.kit.transcript

import com.frederikberg.pai.contract.Message

/**
 * The stretch of a transcript held in memory: ascending by id, always ending at the newest message
 * the stream has delivered, so an arrival can never land in a window with a gap above it. History
 * older than the window is read a page at a time and joined above; the far end is trimmed only while
 * the reader follows the live edge.
 */
data class TranscriptWindow(
    val messages: List<Message> = emptyList(),
    /** Messages older than the first one exist and are not held. */
    val hasOlder: Boolean = false,
) {
    val newestId: Long? get() = messages.lastOrNull()?.id
    val oldestId: Long? get() = messages.firstOrNull()?.id

    /** The window after [entries] arrived, and how many of them were new. A known id is replaced in place; an older unknown one is not part of this stretch. */
    fun arrived(entries: List<Message>): Pair<TranscriptWindow, Int> {
        if (entries.isEmpty()) return this to 0
        val newest = newestId ?: Long.MIN_VALUE
        val byId = messages.associateBy { it.id }
        val replaced = if (entries.any { it.id in byId }) messages.map { current -> entries.lastOrNull { it.id == current.id } ?: current } else messages
        val fresh = entries.filter { it.id > newest }.distinctBy { it.id }.sortedBy { it.id }
        return copy(messages = replaced + fresh) to fresh.size
    }

    /** The window with an older page joined above it. Only ids below the first are taken, so a page fetched against a cursor that has moved cannot duplicate or reorder anything. */
    fun withOlder(
        page: List<Message>,
        pageSize: Int,
    ): TranscriptWindow {
        val oldest = oldestId ?: Long.MAX_VALUE
        val older = page.filter { it.id < oldest }.distinctBy { it.id }.sortedBy { it.id }
        return copy(messages = older + messages, hasOlder = page.size >= pageSize)
    }

    /** The newest [limit] messages; what was cut is older history, read again on demand. */
    fun trimmedToNewest(limit: Int): TranscriptWindow =
        if (messages.size <= limit) this else copy(messages = messages.takeLast(limit), hasOlder = true)

    companion object {
        /** A window opened from the newest page; a full page means older history probably exists. */
        fun opened(
            tail: List<Message>,
            pageSize: Int,
        ): TranscriptWindow = TranscriptWindow(tail.sortedBy { it.id }, hasOlder = tail.size >= pageSize)
    }
}
