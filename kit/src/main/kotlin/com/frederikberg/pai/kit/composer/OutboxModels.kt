package com.frederikberg.pai.kit.composer

import com.frederikberg.pai.contract.PendingSend
import com.frederikberg.pai.contract.SseStatusEvent
import com.frederikberg.pai.kit.drafts.NEW_DRAFT_KEY
import com.frederikberg.pai.kit.drafts.PickedFile

/** Where a queued message is going; carried on the entry, so leaving the screen while it is queued costs nothing. */
sealed interface OutboxTarget {
    /** What entries of one target share: they go out strictly in order, and two targets never wait on each other. */
    val key: String

    /** The draft the message came from, so "put back in the composer" knows where to put it. */
    val draftKey: String

    data class Session(
        val sessionId: String,
    ) : OutboxTarget {
        override val key get() = "session:$sessionId"
        override val draftKey get() = sessionId
    }

    /** A session the message itself creates, with the launch choices of the new-session screen. */
    data class NewSession(
        val sessionType: String?,
        val workingDir: String?,
        val model: String?,
        val thinking: String?,
        val noMemory: Boolean,
    ) : OutboxTarget {
        override val key get() = "new"
        override val draftKey get() = NEW_DRAFT_KEY
    }
}

enum class OutboxState { Queued, Sending, Sent, Failed }

/** What the server answered once it took the message over. */
data class OutboxResult(
    val sessionId: String,
    val messageId: Long,
    val atMs: Long,
)

/**
 * One message on its way to the server. [id] is the `client_message_id` the server honours: minted once,
 * when Send is pressed, and never again, because a retry under a fresh id is a duplicate message.
 */
data class OutboxEntry(
    val id: String,
    val target: OutboxTarget,
    val text: String,
    /** Files already uploaded onto the draft: the send claims exactly these. */
    val draftAttachmentIds: List<String>,
    /** Files whose bytes never reached the server, travelling with the send. */
    val inline: List<PickedFile>,
    /** The draft uploads [inline] stands in for; each may still land, so the send names them for the server to drop. */
    val supersededUploadIds: List<String>,
    val state: OutboxState,
    val attempts: Int,
    /** The worker never picks an entry up before this (epoch ms). */
    val nextAttemptAtMs: Long,
    val lastError: String?,
    val createdAtMs: Long,
    val result: OutboxResult?,
) {
    val draftKey: String get() = target.draftKey

    /** Every draft row this entry accounts for, whichever way its file travels. */
    val namedRows: List<String> get() = draftAttachmentIds + supersededUploadIds

    /**
     * Whether a request carrying this entry may have reached the server: one is in the air, or an earlier
     * one failed without an answer. The only entries that cannot are those no request was ever issued for.
     */
    val mayHaveLeft: Boolean get() = state == OutboxState.Sending || attempts > 0
}

/** Where the outbox keeps entries across a process death. */
interface OutboxStorage {
    suspend fun loadAll(): List<OutboxEntry>

    suspend fun put(entry: OutboxEntry)

    suspend fun remove(id: String)

    /** Sign-out: a message belongs to the account it was written under. */
    suspend fun clear()
}

class InMemoryOutboxStorage : OutboxStorage {
    private val entries = LinkedHashMap<String, OutboxEntry>()

    @Synchronized
    override suspend fun loadAll() = entries.values.toList()

    @Synchronized
    override suspend fun put(entry: OutboxEntry) {
        entries[entry.id] = entry
    }

    @Synchronized
    override suspend fun remove(id: String) {
        entries.remove(id)
    }

    @Synchronized
    override suspend fun clear() = entries.clear()
}

/** Which sends of a session the server lists as accepted and not yet delivered, from its `status` event. */
data class SessionDelivery(
    val queuedTexts: List<String> = emptyList(),
    val pendingSends: List<PendingSend> = emptyList(),
    val lastError: String? = null,
) {
    val hasQueued: Boolean get() = queuedTexts.isNotEmpty() || pendingSends.isNotEmpty()

    companion object {
        fun of(status: SseStatusEvent) =
            SessionDelivery(status.queuedTexts, status.pendingSends, status.lastError)
    }
}

/** One bubble of the strip above the composer. */
sealed interface PendingBubble {
    val text: String

    /** A message this phone still owns: on its way, waiting for the network, or refused. */
    data class Local(
        val entry: OutboxEntry,
    ) : PendingBubble {
        override val text get() = entry.text
    }

    /** A message the server holds for the session; [queued] is Claude's own queue, otherwise the server's outbox. */
    data class AtServer(
        override val text: String,
        val queued: Boolean,
    ) : PendingBubble
}

/**
 * What the strip above a session's composer draws, oldest first: this phone's own messages that the
 * server has not listed itself yet, then the server's lists. An entry the server already lists is drawn
 * from the server's row only, never twice.
 */
fun pendingBubbles(
    entries: List<OutboxEntry>,
    delivery: SessionDelivery,
): List<PendingBubble> {
    val listed = delivery.pendingSends.map { it.id }.toSet()
    val own =
        entries
            .filter { it.result == null || it.result.messageId !in listed }
            .map { PendingBubble.Local(it) }
    val atServer =
        delivery.pendingSends.map { PendingBubble.AtServer(it.text, queued = false) } +
            delivery.queuedTexts.map { PendingBubble.AtServer(it, queued = true) }
    return own + atServer
}
