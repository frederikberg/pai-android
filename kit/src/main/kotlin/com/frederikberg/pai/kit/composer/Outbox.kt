package com.frederikberg.pai.kit.composer

import com.frederikberg.pai.contract.PostMessageResponse
import com.frederikberg.pai.contract.WithdrawPendingResponse
import com.frederikberg.pai.kit.api.MessageFile
import com.frederikberg.pai.kit.api.NewSessionChoices
import com.frederikberg.pai.kit.api.postMessage
import com.frederikberg.pai.kit.api.withdrawPending
import com.frederikberg.pai.kit.drafts.AttachmentStore
import com.frederikberg.pai.kit.drafts.DraftStore
import com.frederikberg.pai.kit.drafts.PickedFile
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** What the outbox needs from the server. */
interface MessageGateway {
    suspend fun post(entry: OutboxEntry): PostMessageResponse

    suspend fun withdrawPending(
        sessionId: String,
        clientMessageIds: List<String>,
    ): WithdrawPendingResponse
}

class ApiMessageGateway(
    private val api: ApiClient,
) : MessageGateway {
    override suspend fun post(entry: OutboxEntry): PostMessageResponse {
        val session = entry.target as? OutboxTarget.Session
        val created = entry.target as? OutboxTarget.NewSession
        return api.postMessage(
            clientMessageId = entry.id,
            text = entry.text,
            sessionId = session?.sessionId,
            draftAttachmentIds = entry.draftAttachmentIds,
            supersededDraftAttachmentIds = entry.supersededUploadIds,
            files = entry.inline.map { MessageFile(it.filename, it.contentType, it.bytes) },
            newSession =
                NewSessionChoices(
                    sessionType = created?.sessionType,
                    workingDir = created?.workingDir,
                    model = created?.model,
                    thinking = created?.thinking,
                    noMemory = created?.noMemory ?: false,
                ),
        )
    }

    override suspend fun withdrawPending(
        sessionId: String,
        clientMessageIds: List<String>,
    ) = api.withdrawPending(sessionId, clientMessageIds)
}

/** What one undo came to, for a caller that wants to say so. */
data class PullBackSummary(
    /** Messages that went back into the composer. */
    val restored: Int,
    /** Messages Claude already has. */
    val delivered: Int,
    /** Messages that could not be settled and were left as they were. */
    val undecided: Int,
    /** The server could not be asked, so nothing that may have left was touched. */
    val unreachable: Boolean,
)

data class OutboxTiming(
    val backoffBaseMs: Long = 1_000,
    val backoffMaxMs: Long = 60_000,
    /** How long an accepted message stays as a bubble before the server's list is trusted to have dropped it. */
    val settleMs: Long = 5_000,
)

/**
 * Messages from Send to the server: each one is queued under its own `client_message_id`, sent strictly in
 * order per target, retried with backoff while the failure is the link rather than the message, and kept
 * on disk so a process death between Send and delivery loses nothing.
 *
 * What a message is allowed to be afterwards is exactly one of: back in the composer, or delivered. An
 * entry is treated as purely local only when no request carrying it was ever issued; one that may have
 * left is named to the server by its id, which withdraws it if it arrived and refuses it if it arrives
 * later, and the server's answer decides.
 */
class Outbox(
    private val scope: CoroutineScope,
    private val gateway: MessageGateway,
    private val storage: OutboxStorage,
    private val drafts: DraftStore,
    private val attachments: AttachmentStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timing: OutboxTiming = OutboxTiming(),
) {
    private val lock = Any()
    private val _entries = MutableStateFlow<List<OutboxEntry>>(emptyList())
    val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()

    /** Target keys with a send loop running, so two never run for one target. */
    private val running = HashSet<String>()

    /** Entries an undo asked the server about and has not heard back on: no request carrying one goes out meanwhile. */
    private val withdrawing = HashSet<String>()

    /** Ids of this session's entries the server has listed as pending at least once. */
    private val listedByServer = HashSet<String>()
    private val persistence = Mutex()
    private var wake: Job? = null

    /** Reads what an earlier process left and sends it. An entry found mid-flight was interrupted, and the send is idempotent under its own id. */
    suspend fun hydrate() {
        val stored = runCatching { storage.loadAll() }.getOrDefault(emptyList())
        val revived = stored.map { if (it.state == OutboxState.Sending) it.copy(state = OutboxState.Queued) else it }
        _entries.update { (it + revived.filter { e -> it.none { x -> x.id == e.id } }).sortedBy { e -> e.createdAtMs } }
        revived.forEach { attachments.withhold(it.draftKey, it.namedRows) }
        pump()
    }

    fun enqueue(
        target: OutboxTarget,
        text: String,
        draftAttachmentIds: List<String>,
        inline: List<PickedFile>,
        supersededUploadIds: List<String>,
    ): OutboxEntry {
        val entry =
            OutboxEntry(
                id = newId(),
                target = target,
                text = text,
                draftAttachmentIds = draftAttachmentIds,
                inline = inline,
                supersededUploadIds = supersededUploadIds,
                state = OutboxState.Queued,
                attempts = 0,
                nextAttemptAtMs = 0,
                lastError = null,
                createdAtMs = clock(),
                result = null,
            )
        _entries.update { it + entry }
        attachments.withhold(entry.draftKey, entry.namedRows)
        // On disk before the first request, so there is no instant at which the message exists nowhere.
        scope.launch {
            persist(entry.id)
            pump()
        }
        return entry
    }

    /** Sends a failed entry again under its own id; never mints a new one. */
    fun retry(id: String) {
        val entry = find(id) ?: return
        if (entry.state == OutboxState.Sending) return
        patch(id) { it.copy(state = OutboxState.Queued, nextAttemptAtMs = 0, attempts = 0, lastError = null) }
        pump()
    }

    fun discard(id: String) = drop(id)

    /** Gives a failed entry's text and files back to the composer it came from. */
    fun putBackInComposer(id: String) {
        val entry = find(id) ?: return
        drafts.prependText(entry.draftKey, entry.text)
        restageFiles(entry)
        drop(id)
    }

    /**
     * Pulls every message of [sessionId] that has not been delivered back into its composer, oldest first:
     * entries no request was ever issued for locally, the rest through the server.
     */
    suspend fun pullBackAll(sessionId: String): PullBackSummary {
        val eligible =
            entriesFor(_entries.value, sessionId)
                .filter { it.result == null && it.state != OutboxState.Sent }
                .sortedBy { it.createdAtMs }
        val neverLeft = eligible.filter { !it.mayHaveLeft }
        val mayBeOnServer = eligible.filter { it.mayHaveLeft }

        // Dropped before anything is awaited, so the pump cannot issue a request for one of these meanwhile.
        val neverLeftTexts = neverLeft.map { it.text }
        neverLeft.forEach {
            restageFiles(it)
            drop(it.id)
        }
        synchronized(lock) { withdrawing += mayBeOnServer.map { it.id } }

        val response: WithdrawPendingResponse? =
            try {
                gateway.withdrawPending(sessionId, mayBeOnServer.map { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
                // Nothing is known about those entries, so none is touched: each stays owed its delivery or
                // its undo, and the person can ask again.
                null
            } finally {
            synchronized(lock) { withdrawing -= mayBeOnServer.map { it.id }.toSet() }
        }

        val serverRows = response?.withdrawn.orEmpty()
        val withdrawnIds = response?.withdrawnClientIds.orEmpty().toSet()
        val deliveredIds = response?.deliveredClientIds.orEmpty().toSet()
        val rowIds = serverRows.mapNotNull { it.clientMessageId }.toSet()
        val present = _entries.value.map { it.id }.toSet()
        // The server never saw these: a tombstone now refuses them, and their files are still this phone's.
        val tombstoned = mayBeOnServer.filter { it.id in withdrawnIds && it.id !in rowIds && it.id in present }
        val delivered = mayBeOnServer.filter { it.id in deliveredIds }
        tombstoned.forEach(::restageFiles)
        mayBeOnServer.filter { it.id in withdrawnIds || it.id in deliveredIds }.forEach { drop(it.id) }

        val pulledBack = (serverRows.map { it.text } + tombstoned.map { it.text } + neverLeftTexts).filter { it.isNotEmpty() }
        if (pulledBack.isNotEmpty()) drafts.prependText(sessionId, pulledBack.joinToString("\n"))

        val restored = serverRows.size + tombstoned.size + neverLeft.size
        return PullBackSummary(
            restored = restored,
            delivered = maxOf(delivered.size, response?.alreadyDelivered?.size ?: 0),
            undecided = if (response == null) mayBeOnServer.size else response.unresolved.size,
            unreachable = response == null && mayBeOnServer.isNotEmpty(),
        )
    }

    /**
     * The server's own list of [sessionId]'s accepted-but-undelivered sends. An accepted message stays a
     * bubble until the server has listed it and then stopped listing it (delivered), or has not listed it
     * for [OutboxTiming.settleMs] after it was accepted.
     */
    fun observePending(
        sessionId: String,
        pendingIds: Set<Long>,
    ) {
        val now = clock()
        for (entry in entriesFor(_entries.value, sessionId)) {
            val result = entry.result ?: continue
            if (result.messageId in pendingIds) {
                synchronized(lock) { listedByServer += entry.id }
            } else if (synchronized(lock) { entry.id in listedByServer } || now - result.atMs >= timing.settleMs) {
                drop(entry.id)
            }
        }
    }

    /** The link is back or the app is in front again: whatever a backoff was waiting for has probably happened. */
    fun pumpAll() {
        _entries.value
            .filter { it.state == OutboxState.Queued && it.nextAttemptAtMs > 0 }
            .forEach { entry -> patch(entry.id) { it.copy(nextAttemptAtMs = 0) } }
        pump()
    }

    private fun find(id: String) = _entries.value.firstOrNull { it.id == id }

    private fun patch(
        id: String,
        change: (OutboxEntry) -> OutboxEntry,
    ) {
        var changed = false
        _entries.update { list ->
            changed = list.any { it.id == id }
            list.map { if (it.id == id) change(it) else it }
        }
        if (changed) scope.launch { persist(id) }
    }

    /** Writes the newest state of an entry; reading it at write time keeps a slow older write from landing last. */
    private suspend fun persist(id: String) =
        persistence.withLock {
            val entry = find(id)
            runCatching { if (entry == null) storage.remove(id) else storage.put(entry) }
        }

    private fun drop(id: String) {
        val entry = find(id) ?: return
        // A message that never reached the server gives its files back to the composer; one that did has claimed them.
        if (entry.result == null) attachments.release(entry.draftKey, entry.namedRows)
        _entries.update { list -> list.filter { it.id != id } }
        synchronized(lock) { listedByServer -= id }
        scope.launch { persist(id) }
    }

    /** Puts the files of a message that never reached the server back into its composer. */
    private fun restageFiles(entry: OutboxEntry) {
        entry.supersededUploadIds.forEach { attachments.remove(entry.draftKey, it) }
        attachments.addFiles(entry.draftKey, entry.inline)
    }

    private fun headDue(
        key: String,
        now: Long,
    ): OutboxEntry? {
        val head =
            _entries.value
                .filter { it.target.key == key && it.state != OutboxState.Sent }
                .minByOrNull { it.createdAtMs } ?: return null
        // The head blocks the entries behind it: within one session, order is the message.
        if (head.state != OutboxState.Queued || head.nextAttemptAtMs > now) return null
        if (synchronized(lock) { head.id in withdrawing }) return null
        return head
    }

    private fun pump() {
        _entries.value
            .filter { it.state != OutboxState.Sent }
            .map { it.target.key }
            .toSet()
            .forEach { key -> scope.launch { pumpTarget(key) } }
        scheduleWake()
    }

    private suspend fun pumpTarget(key: String) {
        while (true) {
            if (!synchronized(lock) { running.add(key) }) return
            try {
                while (true) sendOne(headDue(key, clock()) ?: break)
            } finally {
                synchronized(lock) { running -= key }
            }
            // An entry queued while the loop was winding down found the key taken and left: look once more.
            if (headDue(key, clock()) == null) break
        }
        scheduleWake()
    }

    private fun scheduleWake() {
        val now = clock()
        val next =
            _entries.value
                .filter { it.state == OutboxState.Queued && it.nextAttemptAtMs > now }
                .minOfOrNull { it.nextAttemptAtMs - now }
        synchronized(lock) {
            wake?.cancel()
            wake =
                next?.let {
                    scope.launch {
                        delay(it)
                        pump()
                    }
                }
        }
    }

    private suspend fun sendOne(entry: OutboxEntry) {
        if (find(entry.id) == null) return
        patch(entry.id) { it.copy(state = OutboxState.Sending) }
        try {
            val answer = gateway.post(entry)
            val stillHere = find(entry.id) != null
            if (answer.withdrawn) {
                // The server refused it: pulled back before it could be delivered. Unless an undo already
                // took the entry, the text comes back here unless the server's draft already holds it.
                if (stillHere) {
                    if (answer.textInDraft == true) drop(entry.id) else putBackInComposer(entry.id)
                }
                return
            }
            // An entry an undo has since taken is no longer this send's to settle.
            if (!stillHere) return
            patch(entry.id) {
                it.copy(
                    state = OutboxState.Sent,
                    lastError = null,
                    result = OutboxResult(answer.sessionId, answer.messageId, clock()),
                )
            }
            // Recording the version the send left the draft at makes this phone's next poll a no-op. Never a
            // second clear: by now the next message may be half typed into the same composer.
            drafts.recordServerVersion(entry.draftKey, answer.draftVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (!isRetryable(e)) {
                patch(entry.id) { it.copy(state = OutboxState.Failed, lastError = e.userMessage) }
                return
            }
            val attempts = entry.attempts + 1
            patch(entry.id) {
                it.copy(
                    state = OutboxState.Queued,
                    attempts = attempts,
                    nextAttemptAtMs = clock() + backoff(attempts),
                    lastError = e.userMessage,
                )
            }
        }
    }

    private fun backoff(attempts: Int): Long =
        minOf(timing.backoffBaseMs * (1L shl (attempts - 1).coerceIn(0, 20)), timing.backoffMaxMs)

    companion object {
        /**
         * Whether a failure is the link or the message. A refusal the server repeats (an empty body, a
         * session that is gone, a text over the ceiling) must stop and ask, because retrying it forever is
         * indistinguishable from a message silently never arriving.
         */
        fun isRetryable(error: ApiError): Boolean =
            when (error) {
                is ApiError.Transport, is ApiError.Decoding -> true
                is ApiError.ProxyRequiresBrowserLogin -> false
                else -> error.statusCode?.let { it >= 500 || it == 408 || it == 425 || it == 429 } ?: true
            }

        /** Every entry the session's strip draws a bubble for, including one that created the session, which carries its id only in its result. */
        fun entriesFor(
            entries: List<OutboxEntry>,
            sessionId: String?,
        ): List<OutboxEntry> =
            if (sessionId == null) {
                entries.filter { it.target is OutboxTarget.NewSession && it.result == null }
            } else {
                entries.filter { (it.target as? OutboxTarget.Session)?.sessionId == sessionId || it.result?.sessionId == sessionId }
            }
    }
}
