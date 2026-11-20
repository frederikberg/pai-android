package com.frederikberg.pai.kit.drafts

import com.frederikberg.pai.contract.Draft
import com.frederikberg.pai.contract.DraftWriteResult
import com.frederikberg.pai.kit.api.DraftChoices
import com.frederikberg.pai.kit.api.listDrafts
import com.frederikberg.pai.kit.api.putDraft
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The draft key of the composer on the new-session screen; a session's own draft is keyed by its id. */
const val NEW_DRAFT_KEY = "new"

/** The session types whose models are named by wire id rather than alias, each with a default of its own. */
private val API_SESSION_KINDS = setOf("ultrafast", "mailverdict")

/** One composer's unsent state as this phone holds it. */
data class DraftEntry(
    val text: String = "",
    /** Launch choices; only the [NEW_DRAFT_KEY] draft carries them. */
    val sessionType: String? = null,
    val workingDir: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    /**
     * The server `version` this entry was last reconciled with, or null when it never has been.
     * Ordered, never compared for equality: a row is adopted only when its version is strictly greater,
     * which is what stops an answer the server produced before this phone's own write but the network
     * delivered after it from replacing what is on screen.
     */
    val knownVersion: Long? = null,
    /** A local edit the server has not confirmed. While set, nothing from the server replaces [text]. */
    val dirty: Boolean = false,
    /** What the server row held before its current version, offered as "restore earlier version". */
    val previousText: String? = null,
)

/** What the draft store needs from the server. */
interface DraftGateway {
    suspend fun list(): List<Draft>

    suspend fun put(
        key: String,
        entry: DraftEntry,
        deviceId: String,
    ): DraftWriteResult
}

class ApiDraftGateway(
    private val api: ApiClient,
) : DraftGateway {
    override suspend fun list(): List<Draft> = api.listDrafts()

    override suspend fun put(
        key: String,
        entry: DraftEntry,
        deviceId: String,
    ): DraftWriteResult =
        api.putDraft(
            key,
            entry.text,
            deviceId,
            DraftChoices(entry.sessionType, entry.workingDir, entry.model, entry.thinking),
        )
}

data class DraftTiming(
    /**
     * Idle time before a typed edit is written. Slow on purpose: drafts across devices are "pick up
     * where I left off", and every request saved on a poor link is one fewer answer that can arrive
     * out of order. Leaving a composer or the app writes at once.
     */
    val debounceMs: Long = 2_000,
    val retryBaseMs: Long = 2_000,
    val retryMaxMs: Long = 30_000,
)

/**
 * Every composer's unsent text and launch choices, held here and written to the server.
 *
 * The rules that keep typing safe:
 * - A key with a write pending (debounce armed, retry armed, a request in the air) or with an edit the
 *   server has not confirmed is never replaced by what a sync reads; only the server-owned
 *   `previousText` is taken.
 * - Server rows are adopted by version order, strictly greater, never by a field merely differing.
 * - At most one write per key is in flight; an edit arriving meanwhile writes once more when it
 *   settles, carrying whatever the text is by then.
 * - [revision] moves only when the text or the choices change from outside the keyboard (a sync, a
 *   pulled-back send, a restore), never on typing: a field that re-read itself per keystroke would lose
 *   characters to the round trip.
 */
class DraftStore(
    private val scope: CoroutineScope,
    private val gateway: DraftGateway,
    private val deviceId: String,
    private val timing: DraftTiming = DraftTiming(),
) {
    private val lock = Any()
    private val _drafts = MutableStateFlow<Map<String, DraftEntry>>(emptyMap())
    val drafts: StateFlow<Map<String, DraftEntry>> = _drafts.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _flushFailing = MutableStateFlow<Set<String>>(emptySet())

    /** Keys whose writes the server refuses (a text over its ceiling): retrying cannot help until the text changes. */
    val flushFailing: StateFlow<Set<String>> = _flushFailing.asStateFlow()

    private val debounce = HashMap<String, Job>()
    private val retry = HashMap<String, Job>()
    private val retryAttempts = HashMap<String, Int>()
    private val inFlight = HashSet<String>()
    private val wantsFlush = HashSet<String>()

    /** Bumped on every local change to a key, so a write can tell whether the text it carried is still the text on screen. */
    private val localRevision = HashMap<String, Int>()

    fun get(key: String): DraftEntry = _drafts.value[key] ?: DraftEntry()

    /** Typing. Leaves [revision] alone. */
    fun setText(
        key: String,
        text: String,
    ) {
        if (get(key).text == text) return
        edit(key) { it.copy(text = text) }
    }

    /** A text change the field must show (a send cleared it, a withdrawn message came back). */
    fun replaceText(
        key: String,
        text: String,
    ) {
        setText(key, text)
        bumpRevision()
    }

    /** Puts [text] in front of what is typed, separated by a blank line, as a pulled-back message returns. */
    fun prependText(
        key: String,
        text: String,
    ) {
        if (text.isEmpty()) return
        val current = get(key).text
        replaceText(key, if (current.isEmpty()) text else "$text\n\n$current")
    }

    /** A launch choice that makes a working directory moot: the directory only belongs to the custom type. */
    fun selectSessionType(id: String?) {
        val previous = get(NEW_DRAFT_KEY).sessionType
        // Models of an API session kind are named by wire id and each kind has its own default, so a
        // choice never crosses from one of them to another type.
        val crossesApiKind = id != previous && (id in API_SESSION_KINDS || previous in API_SESSION_KINDS)
        edit(NEW_DRAFT_KEY) {
            it.copy(
                sessionType = id,
                workingDir = null,
                model = if (crossesApiKind) null else it.model,
                thinking = if (crossesApiKind) null else it.thinking,
            )
        }
        bumpRevision()
    }

    /** A chosen directory is what makes a session custom, so the two move together. */
    fun selectWorkingDir(path: String?) {
        edit(NEW_DRAFT_KEY) { it.copy(workingDir = path, sessionType = if (path != null) "custom" else null) }
        bumpRevision()
    }

    /** The effort levels a model accepts belong to that model, so a level chosen for the previous one is cleared. */
    fun selectModel(id: String?) {
        edit(NEW_DRAFT_KEY) { it.copy(model = id, thinking = null) }
        bumpRevision()
    }

    fun selectThinking(level: String?) {
        edit(NEW_DRAFT_KEY) { it.copy(thinking = level) }
        bumpRevision()
    }

    /** Writes a key now, so leaving a composer cannot strand an edit mid-debounce. */
    fun flush(key: String) {
        scope.launch { write(key) }
    }

    /** Writes every key with an unconfirmed edit: the app is going to the background. */
    fun flushAll() {
        _drafts.value.filterValues { it.dirty }.keys.forEach(::flush)
    }

    /** Notes the version the server row now stands at without touching the text. Monotonic. */
    fun recordServerVersion(
        key: String,
        version: Long?,
    ) {
        if (version == null) return
        _drafts.update { map ->
            val current = map[key] ?: return@update map
            if ((current.knownVersion ?: -1) >= version) map else map + (key to current.copy(knownVersion = version))
        }
    }

    /** Takes what the server lists: the adoption rule above, applied to every row. */
    fun adopt(rows: List<Draft>) {
        var changedFromOutside = false
        _drafts.update { current ->
            changedFromOutside = false
            val next = current.toMutableMap()
            for (row in rows) {
                val local = next[row.key]
                if (row.version <= (local?.knownVersion ?: -1)) continue
                if (local != null && (local.dirty || busy(row.key))) {
                    if (local.previousText != row.previousText) next[row.key] = local.copy(previousText = row.previousText)
                    continue
                }
                val adopted =
                    DraftEntry(
                        text = row.text,
                        sessionType = row.sessionType,
                        workingDir = row.workingDir,
                        model = row.model,
                        thinking = row.thinking,
                        knownVersion = row.version,
                        dirty = false,
                        previousText = row.previousText,
                    )
                if (local == null) {
                    changedFromOutside = adopted.text.isNotEmpty() || adopted.hasChoices()
                } else if (local.copy(knownVersion = null, previousText = null, dirty = false) !=
                    adopted.copy(knownVersion = null, previousText = null)
                ) {
                    changedFromOutside = true
                }
                next[row.key] = adopted
            }
            next
        }
        if (changedFromOutside) bumpRevision()
    }

    private fun DraftEntry.hasChoices() = sessionType != null || workingDir != null || model != null || thinking != null

    private fun bumpRevision() = _revision.update { it + 1 }

    private fun edit(
        key: String,
        change: (DraftEntry) -> DraftEntry,
    ) {
        _drafts.update { map -> map + (key to change(map[key] ?: DraftEntry()).copy(dirty = true)) }
        synchronized(lock) {
            localRevision[key] = (localRevision[key] ?: 0) + 1
            // A retry armed for the previous text is superseded by this edit's own debounce; leaving it
            // would put an older text on the wire behind the newer one.
            retry.remove(key)?.cancel()
            retryAttempts.remove(key)
            _flushFailing.update { it - key }
            debounce.remove(key)?.cancel()
            debounce[key] =
                scope.launch {
                    delay(timing.debounceMs)
                    val me = currentCoroutineContext()[Job]
                    synchronized(lock) { if (debounce[key] === me) debounce.remove(key) }
                    write(key)
                }
        }
    }

    /** A write is under way for [key] in any sense: debounce armed, retry armed, or a request in the air. */
    private fun busy(key: String): Boolean = synchronized(lock) { key in debounce || key in retry || key in inFlight }

    private class Claim(
        val entry: DraftEntry,
        val revision: Int,
    )

    private suspend fun write(key: String) {
        val claim: Claim? =
            synchronized(lock) {
                debounce.remove(key)?.cancel()
                retry.remove(key)?.cancel()
                if (key in inFlight) {
                    wantsFlush += key
                    null
                } else {
                    _drafts.value[key]?.let { entry ->
                        inFlight += key
                        Claim(entry, localRevision[key] ?: 0)
                    }
                }
            }
        if (claim == null) return
        try {
            val result = gateway.put(key, claim.entry, deviceId)
            val newerEditExists = synchronized(lock) { (localRevision[key] ?: 0) != claim.revision }
            _drafts.update { map ->
                val current = map[key] ?: return@update map
                map +
                    (
                        key to
                            current.copy(
                                knownVersion = maxOf(result.version, current.knownVersion ?: -1),
                                // What this write replaced. Only the writer ever learns it: its own next
                                // poll is a no-op, so no row read can tell it what it overwrote.
                                previousText = result.previousText,
                                dirty = newerEditExists,
                            )
                    )
            }
            synchronized(lock) { retryAttempts.remove(key) }
            _flushFailing.update { it - key }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (refusedForGood(e)) {
                synchronized(lock) { wantsFlush.remove(key) }
                _flushFailing.update { it + key }
            } else {
                armRetry(key)
            }
        } finally {
            val again =
                synchronized(lock) {
                    inFlight -= key
                    wantsFlush.remove(key)
                }
            if (again) write(key)
        }
    }

    /** A refusal the server repeats (a text over its ceiling): retrying forever would hide that nothing is saved. */
    private fun refusedForGood(error: ApiError): Boolean = error.statusCode?.let { it in 400..499 } == true

    private fun armRetry(key: String) {
        synchronized(lock) {
            val attempt = (retryAttempts[key] ?: 0) + 1
            retryAttempts[key] = attempt
            val wait = minOf(timing.retryBaseMs * (1L shl (attempt - 1).coerceAtMost(20)), timing.retryMaxMs)
            retry[key] =
                scope.launch {
                    delay(wait)
                    val me = currentCoroutineContext()[Job]
                    synchronized(lock) { if (retry[key] === me) retry.remove(key) }
                    write(key)
                }
        }
    }
}
