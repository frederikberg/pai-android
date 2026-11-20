package com.frederikberg.pai.kit.sessions

import com.frederikberg.pai.contract.DisplayState
import com.frederikberg.pai.contract.Session
import com.frederikberg.pai.contract.SessionKind
import com.frederikberg.pai.kit.transcript.TimeLabels

/** What a session's dot shows. The server computes the display state once and nothing here re-derives it. */
enum class Dot { Starting, Working, Done, Blocked, Error, Closed }

/** A session as one row of the list says it. */
data class SessionRowData(
    val id: String,
    val title: String,
    val dot: Dot,
    val subtitle: String,
    val time: String,
    val pinned: Boolean,
    val agents: Int,
    val tasks: Int,
    val tokens: String?,
)

object SessionRows {
    /** A payload without the field reads as closed: a closed session never paints green. */
    fun dot(session: Session): Dot =
        when (session.displayState) {
            DisplayState.Starting -> Dot.Starting
            DisplayState.Working -> Dot.Working
            DisplayState.Done -> Dot.Done
            DisplayState.Blocked -> Dot.Blocked
            DisplayState.Error -> Dot.Error
            else -> Dot.Closed
        }

    private fun label(dot: Dot): String =
        when (dot) {
            Dot.Starting -> "Starting…"
            Dot.Working -> "Working…"
            Dot.Done -> "Done"
            Dot.Blocked -> "Waiting on you"
            Dot.Error -> "Needs attention"
            Dot.Closed -> "Closed"
        }

    /** Kinds with no process of their own are never typed into. */
    private fun hasNoProcess(kind: SessionKind?) =
        kind == SessionKind.Subagent || kind == SessionKind.Supervisor || kind == SessionKind.Computer

    /** PAI has a live process for it exactly when its state is anything but closed. */
    fun isDrivable(session: Session): Boolean = !hasNoProcess(session.kind) && session.state != null && session.state.raw != "closed"

    fun subtitle(session: Session): String {
        when (session.kind) {
            SessionKind.Subagent -> return "Subagent"
            SessionKind.Supervisor -> return "Supervisor"
            SessionKind.Computer -> return "Spoken with Computer"
        }
        if (session.displayState == null) return if (isDrivable(session)) "" else "Not driven by PAI"
        val dot = dot(session)
        val text = label(dot)
        return if (!isDrivable(session) && dot != Dot.Closed) "$text · not driven by PAI" else text
    }

    /** A guest's session has no memory project; the folder it was started in stands in, unless it is the namespace's own home. */
    fun projectLabel(session: Session): String? {
        session.projectName?.takeIf { it.isNotEmpty() }?.let { return it }
        val namespace = session.namespace
        val directory = session.workingDir
        if (namespace.isNullOrEmpty() || directory.isNullOrEmpty()) return null
        if (directory.endsWith("/$namespace") || directory.endsWith("/$namespace/work")) return null
        return directory.trimEnd('/').substringAfterLast('/').ifEmpty { null }
    }

    fun title(session: Session): String {
        val own =
            if (session.kind == SessionKind.Subagent) {
                session.subagentName ?: session.subagentType ?: session.title ?: session.initialMessage ?: "Session"
            } else {
                session.title ?: session.initialMessage ?: "Session"
            }
        return projectLabel(session)?.let { "$it : $own" } ?: own
    }

    fun tokens(count: Long): String? =
        when {
            count <= 0 -> null
            count >= 1_000_000 -> "%.1fm".format(java.util.Locale.ROOT, count / 1_000_000.0)
            count >= 1_000 -> "${Math.round(count / 1_000.0)}k"
            else -> count.toString()
        }

    fun of(
        session: Session,
        labels: TimeLabels,
    ) = SessionRowData(
        id = session.id,
        title = title(session),
        dot = dot(session),
        subtitle = subtitle(session),
        time = labels.session(session.lastActivityAt ?: session.createdAt),
        pinned = session.pinnedAt != null,
        agents = session.activityCounts?.agents ?: 0,
        tasks = session.activityCounts?.tasks ?: 0,
        tokens = tokens(session.sessionTokens),
    )

    /** Whether a session is a row of the list at all: a subagent's or supervisor's transcript is opened from its conversation. */
    fun isListed(session: Session): Boolean = session.kind != SessionKind.Subagent && session.kind != SessionKind.Supervisor

    /** The filter's match: words of the text against the title, project and first message, or an id prefix. */
    fun matches(
        session: Session,
        query: String,
    ): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        if (needle.length >= 4 && session.id.lowercase().startsWith(needle)) return true
        val haystack = listOfNotNull(title(session), session.initialMessage, session.workingDir).joinToString("\n").lowercase()
        return needle.split(Regex("\\s+")).all { it in haystack }
    }
}
