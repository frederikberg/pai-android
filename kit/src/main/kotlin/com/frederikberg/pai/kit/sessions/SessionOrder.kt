package com.frederikberg.pai.kit.sessions

import com.frederikberg.pai.contract.Session
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Where the list's rows sit, kept apart from what they say. A row's content follows the server live;
 * its position is the placement it was last given, renewed in one step after the list has been quiet,
 * so a busy session moves up once its activity settles and no row ever moves under a finger.
 */
class SessionOrder {
    private class Placement(
        val pinnedAt: String?,
        val activity: String,
    )

    private val placed = HashMap<String, Placement>()

    private fun live(session: Session) = Placement(session.pinnedAt, session.lastActivityAt ?: session.createdAt.orEmpty())

    /** Pinned first, in the order they were pinned, then newest activity first; a row never placed sorts by what it says now. */
    val comparator: Comparator<Session> =
        Comparator { a, b ->
            val pa = placed[a.id] ?: live(a)
            val pb = placed[b.id] ?: live(b)
            val pinnedFirst = (if (pb.pinnedAt != null) 1 else 0) - (if (pa.pinnedAt != null) 1 else 0)
            when {
                pinnedFirst != 0 -> pinnedFirst
                pa.pinnedAt != null && pb.pinnedAt != null -> instant(pa.pinnedAt).compareTo(instant(pb.pinnedAt))
                pb.activity != pa.activity -> pb.activity.compareTo(pa.activity)
                else -> a.id.compareTo(b.id)
            }
        }

    /** Gives rows the placement they currently say they have. */
    fun place(rows: Iterable<Session>) = rows.forEach { placed[it.id] = live(it) }

    /** Drops every placement and gives these rows theirs: a fresh load starts from what the server says. */
    fun placeFresh(rows: Iterable<Session>) {
        placed.clear()
        place(rows)
    }

    /** A row that arrived on a sync sits at the bottom until the next settle, so it does not push the rows above it. */
    fun placeLast(id: String) {
        placed[id] = Placement(null, "")
    }

    /** Whether a row's live values differ from where it is placed. */
    fun isMisplaced(session: Session): Boolean {
        val placement = placed[session.id] ?: return false
        val now = live(session)
        return placement.pinnedAt != now.pinnedAt || placement.activity != now.activity
    }

    fun forget(id: String) {
        placed.remove(id)
    }

    private fun instant(iso: String?): Long =
        try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
}
