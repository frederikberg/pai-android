package com.frederikberg.pai.kit.composer

import com.frederikberg.pai.contract.BackgroundStatus
import com.frederikberg.pai.kit.api.moveToBackground
import com.frederikberg.pai.kit.api.sendQueuedNow
import com.frederikberg.pai.kit.drafts.ApiAttachmentGateway
import com.frederikberg.pai.kit.drafts.ApiDraftGateway
import com.frederikberg.pai.kit.drafts.AttachmentStore
import com.frederikberg.pai.kit.drafts.DraftGateway
import com.frederikberg.pai.kit.drafts.DraftStore
import com.frederikberg.pai.kit.drafts.NEW_DRAFT_KEY
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What a session action (send now, move to background) came to, for the one line the composer shows. */
sealed interface SessionActionResult {
    data object Done : SessionActionResult

    /** There was nothing to act on. */
    data object NothingToDo : SessionActionResult

    /** The session is in a state that refuses it; [reason] is the server's word. */
    data class Refused(
        val reason: String?,
    ) : SessionActionResult

    data object Unavailable : SessionActionResult

    data class Failed(
        val message: String,
    ) : SessionActionResult
}

/**
 * Everything a composer shares for as long as one sign-in lasts: the drafts, the attachments, the
 * outbox and the session actions, behind the three calls a screen makes (sync, send, pull back).
 */
class ComposerHub(
    private val api: ApiClient,
    val drafts: DraftStore,
    val attachments: AttachmentStore,
    val outbox: Outbox,
    private val draftGateway: DraftGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Reads what an earlier process left in the outbox and sends it. */
    suspend fun start() = outbox.hydrate()

    /**
     * Reads every draft the server holds and lets the stores adopt what is newer than they know. A failed
     * read changes nothing: the next poll tries again.
     */
    suspend fun sync() {
        val requestedAt = clock()
        val rows =
            try {
                draftGateway.list()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
                return
            }
        attachments.adoptRemote(rows, requestedAt)
        drafts.adopt(rows)
    }

    /**
     * Turns the draft of [target] into a queued message and empties the composer at once. Answers false
     * when there is nothing to send. The text is sent as typed apart from surrounding blank space.
     */
    fun send(target: OutboxTarget): Boolean {
        val key = target.draftKey
        val text = drafts.get(key).text.trim()
        val claimed = attachments.claimableIds(key)
        val inline = attachments.inlineFiles(key)
        val superseded = attachments.supersededIds(key)
        if (text.isEmpty() && claimed.isEmpty() && inline.isEmpty()) return false
        outbox.enqueue(target, text, claimed, inline, superseded)
        attachments.clear(key)
        drafts.replaceText(key, "")
        return true
    }

    /** The target the new-session screen sends to, from the choices its draft holds. */
    fun newSessionTarget(noMemory: Boolean): OutboxTarget.NewSession {
        val draft = drafts.get(NEW_DRAFT_KEY)
        return OutboxTarget.NewSession(draft.sessionType, draft.workingDir, draft.model, draft.thinking, noMemory)
    }

    /** Pulls the session's undelivered messages back into its composer. */
    suspend fun pullBack(sessionId: String): PullBackSummary = outbox.pullBackAll(sessionId)

    suspend fun sendQueuedNow(sessionId: String): SessionActionResult =
        action {
            val answer: JsonObject = api.sendQueuedNow(sessionId)
            when (answer["status"]?.jsonPrimitive?.contentOrNull) {
                "sent" -> SessionActionResult.Done
                "nothing_queued" -> SessionActionResult.NothingToDo
                "refused" -> SessionActionResult.Refused(answer["reason"]?.jsonPrimitive?.contentOrNull)
                else -> SessionActionResult.Unavailable
            }
        }

    suspend fun moveToBackground(sessionId: String): SessionActionResult =
        action {
            val answer = api.moveToBackground(sessionId)
            when (answer.status) {
                BackgroundStatus.Moved -> SessionActionResult.Done
                BackgroundStatus.NothingRunning -> SessionActionResult.NothingToDo
                BackgroundStatus.Refused -> SessionActionResult.Refused(answer.reason)
                else -> SessionActionResult.Unavailable
            }
        }

    /** The app is leaving the foreground: unconfirmed edits are written now rather than after their idle time. */
    fun onBackground() = drafts.flushAll()

    /** The app is back or the network returned: queued messages stop waiting out their backoff. */
    fun onForeground() = outbox.pumpAll()

    /** Polls the server's drafts while a composer is on screen; ends when the caller's coroutine does. */
    suspend fun pollWhileShown(intervalMs: Long = POLL_INTERVAL_MS) {
        while (true) {
            sync()
            delay(intervalMs)
        }
    }

    private suspend fun action(block: suspend () -> SessionActionResult): SessionActionResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            SessionActionResult.Failed(e.userMessage)
        }

    companion object {
        const val POLL_INTERVAL_MS = 5_000L

        fun create(
            scope: CoroutineScope,
            api: ApiClient,
            deviceId: String,
            storage: OutboxStorage,
        ): ComposerHub {
            val draftGateway = ApiDraftGateway(api)
            val drafts = DraftStore(scope, draftGateway, deviceId)
            val attachments = AttachmentStore(scope, ApiAttachmentGateway(api))
            val outbox = Outbox(scope, ApiMessageGateway(api), storage, drafts, attachments)
            return ComposerHub(api, drafts, attachments, outbox, draftGateway).also { hub ->
                scope.launch { hub.start() }
            }
        }
    }
}
