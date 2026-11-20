package com.frederikberg.pai.kit.drafts

import com.frederikberg.pai.contract.Draft
import com.frederikberg.pai.contract.DraftAttachment
import com.frederikberg.pai.contract.DraftAttachmentState
import com.frederikberg.pai.kit.api.addDraftAttachment
import com.frederikberg.pai.kit.api.getDraftAttachmentBytes
import com.frederikberg.pai.kit.api.removeDraftAttachment
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import com.frederikberg.pai.kit.net.RawBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** A file this phone picked, bytes in hand: already compressed when it is a picture. */
class PickedFile(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/** Where a file this phone picked stands between the picker and the message that carries it. */
enum class StagedState {
    /** Bytes on their way to the draft. */
    Uploading,

    /** On the server too, so a send leaves the bytes behind and names the row. */
    Uploaded,

    /** The upload did not land; the bytes travel inline with the message instead and nothing is lost. */
    Failed,
}

class StagedAttachment(
    /** Minted here and given to the upload, so it is also the id of the server row this file becomes. */
    val id: String,
    val file: PickedFile,
    val state: StagedState,
    /** The draft-attachment row the upload became, set only by its answer. */
    val remoteId: String?,
    /** When that upload landed; a server list fetched before this moment cannot know the row. */
    val uploadedAtMs: Long?,
)

/** What the composer draws: a chip per entry, whichever half it came from. */
sealed interface ComposerAttachment {
    val id: String

    /** Picked on this phone. */
    class Staged(
        val staged: StagedAttachment,
    ) : ComposerAttachment {
        override val id: String get() = staged.id
    }

    /** Another device put it on this draft; no bytes here until fetched. */
    class Remote(
        val row: DraftAttachment,
    ) : ComposerAttachment {
        override val id: String get() = row.id
    }
}

data class AttachmentsState(
    val staged: Map<String, List<StagedAttachment>> = emptyMap(),
    val remote: Map<String, List<DraftAttachment>> = emptyMap(),
    /** Row ids a queued or sent message already names: the server lists them until the send claims them, and neither a poll nor a late answer may put the chip back. */
    val withheld: Map<String, Set<String>> = emptyMap(),
) {
    /** Every chip of [key], this phone's own first; a remote row one of those uploads already became is dropped. */
    fun chips(key: String): List<ComposerAttachment> {
        val mine = staged[key].orEmpty()
        val own = mine.flatMap { listOfNotNull(it.id, it.remoteId) }.toSet()
        return mine.map { ComposerAttachment.Staged(it) } +
            remote[key].orEmpty().filter { it.id !in own }.map { ComposerAttachment.Remote(it) }
    }
}

/** What the attachment store needs from the server. */
interface AttachmentGateway {
    suspend fun upload(
        key: String,
        id: String,
        file: PickedFile,
    ): DraftAttachment

    suspend fun remove(
        key: String,
        id: String,
    )

    suspend fun bytes(
        key: String,
        id: String,
    ): RawBody
}

class ApiAttachmentGateway(
    private val api: ApiClient,
) : AttachmentGateway {
    override suspend fun upload(
        key: String,
        id: String,
        file: PickedFile,
    ) = api.addDraftAttachment(key, id, file.filename, file.contentType, file.bytes)

    override suspend fun remove(
        key: String,
        id: String,
    ) = api.removeDraftAttachment(key, id)

    override suspend fun bytes(
        key: String,
        id: String,
    ) = api.getDraftAttachmentBytes(key, id)
}

/**
 * Everything that happens to a file between being picked and arriving with a message: bytes held on
 * the phone that picked them, a row on the server another device can see, and the claim a send makes.
 * A file is uploaded the moment it is picked, so a picture added here shows on the other device while
 * the message is still being written; a failed upload is not fatal, the bytes simply travel inline.
 */
class AttachmentStore(
    private val scope: CoroutineScope,
    private val gateway: AttachmentGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(AttachmentsState())
    val state: StateFlow<AttachmentsState> = _state.asStateFlow()

    fun addFiles(
        key: String,
        files: List<PickedFile>,
    ) {
        if (files.isEmpty()) return
        val staged = files.map { StagedAttachment(newId(), it, StagedState.Uploading, null, null) }
        _state.update { it.copy(staged = it.staged + (key to it.staged[key].orEmpty() + staged)) }
        staged.forEach { item -> scope.launch { upload(key, item) } }
    }

    /** Tries a failed upload again with the bytes still held here. */
    fun retryUpload(
        key: String,
        id: String,
    ) {
        val item = _state.value.staged[key].orEmpty().firstOrNull { it.id == id && it.state == StagedState.Failed } ?: return
        replace(key, id) { StagedAttachment(it.id, it.file, StagedState.Uploading, null, null) }
        scope.launch { upload(key, item) }
    }

    private suspend fun upload(
        key: String,
        item: StagedAttachment,
    ) {
        val uploaded: DraftAttachment? =
            try {
                gateway.upload(key, item.id, item.file)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
                // Not fatal: the bytes are still here, so the send carries them inline.
                null
            }
        // Removed or sent while the upload was in the air: leave it gone.
        replace(key, item.id) {
            if (uploaded != null) {
                StagedAttachment(it.id, it.file, StagedState.Uploaded, uploaded.id, clock())
            } else {
                StagedAttachment(it.id, it.file, StagedState.Failed, null, null)
            }
        }
    }

    private fun replace(
        key: String,
        id: String,
        change: (StagedAttachment) -> StagedAttachment,
    ) = _state.update { state ->
        val list = state.staged[key] ?: return@update state
        if (list.none { it.id == id }) return@update state
        state.copy(staged = state.staged + (key to list.map { if (it.id == id) change(it) else it }))
    }

    fun remove(
        key: String,
        id: String,
    ) {
        val state = _state.value
        val item = state.staged[key].orEmpty().firstOrNull { it.id == id }
        if (item != null) {
            _state.update { it.copy(staged = it.staged + (key to it.staged[key].orEmpty().filter { x -> x.id != id })) }
            // A file still uploading is dropped locally only: its upload may land after this, leaving a
            // row the next send claims or the server sweeps.
            item.remoteId?.let { remoteId -> scope.launch { ignoringRefusal { gateway.remove(key, remoteId) } } }
            return
        }
        _state.update { it.copy(remote = it.remote + (key to it.remote[key].orEmpty().filter { row -> row.id != id })) }
        scope.launch { ignoringRefusal { gateway.remove(key, id) } }
    }

    /** The draft became a message: nothing of it stays in the composer. */
    fun clear(key: String) = _state.update { it.copy(staged = it.staged - key, remote = it.remote - key) }

    /** A message now carries these rows: out of the composer, ignored by [adoptRemote] until released. Nothing is deleted on the server. */
    fun withhold(
        key: String,
        ids: List<String>,
    ) {
        if (ids.isEmpty()) return
        _state.update { state ->
            val held = state.withheld[key].orEmpty() + ids
            val rows = state.remote[key]
            state.copy(
                withheld = state.withheld + (key to held),
                remote = if (rows == null) state.remote else state.remote + (key to rows.filter { it.id !in held }),
            )
        }
    }

    /** The message that carried these rows was abandoned unsent; the server still holds them, so the next poll lists them again. */
    fun release(
        key: String,
        ids: List<String>,
    ) = _state.update { state ->
        val left = state.withheld[key].orEmpty() - ids.toSet()
        state.copy(withheld = if (left.isEmpty()) state.withheld - key else state.withheld + (key to left))
    }

    /**
     * Takes the draft list a sync read. [requestedAtMs] is when that request left: a file of ours whose
     * upload landed earlier and that the server no longer lists was claimed by a send from another
     * device (or removed there), so it goes here too.
     */
    fun adoptRemote(
        drafts: List<Draft>,
        requestedAtMs: Long,
    ) = _state.update { state ->
        val staged = state.staged.toMutableMap()
        for (draft in drafts) {
            val list = staged[draft.key] ?: continue
            val live = draft.attachments.map { it.id }.toSet()
            val kept = list.filter { it.remoteId == null || it.remoteId in live || (it.uploadedAtMs ?: 0) >= requestedAtMs }
            if (kept.size == list.size) continue
            if (kept.isEmpty()) staged.remove(draft.key) else staged[draft.key] = kept
        }
        val remote = HashMap<String, List<DraftAttachment>>()
        for (draft in drafts) {
            val held = state.withheld[draft.key].orEmpty()
            // `unclaimed` is the server saying a send named the row and could not move it: the one state
            // that must stay visible after a send.
            val listed = draft.attachments.filter { it.id !in held || it.state == DraftAttachmentState.Unclaimed }
            if (listed.isNotEmpty()) remote[draft.key] = listed
        }
        state.copy(staged = staged, remote = remote)
    }

    /** The bytes of a row another device put on the draft, for its thumbnail. */
    suspend fun bytesOf(
        key: String,
        id: String,
    ): RawBody? =
        try {
            gateway.bytes(key, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            null
        }

    /** The bytes that still have to travel with the message: everything not already on the server. */
    fun inlineFiles(key: String): List<PickedFile> =
        _state.value.staged[key].orEmpty().filter { it.state != StagedState.Uploaded }.map { it.file }

    /**
     * The server-side ids a send claims: this draft's own uploaded files and the rows other devices
     * put there, nothing else. Named explicitly so a photo added for the next message while an earlier
     * one is still queued cannot be pulled into the earlier one.
     */
    fun claimableIds(key: String): List<String> {
        val state = _state.value
        val staged = state.staged[key].orEmpty()
        val own = staged.flatMap { listOfNotNull(it.id, it.remoteId) }.toSet()
        val mine = staged.mapNotNull { it.remoteId }
        val others =
            state.remote[key]
                .orEmpty()
                .filter { (it.state == DraftAttachmentState.Stored || it.state == DraftAttachmentState.Unclaimed) && it.id !in own }
                .map { it.id }
        return mine + others
    }

    /**
     * The uploads [inlineFiles] stands in for. Each is still in the air, or failed here and may have
     * landed all the same, so the send names them for the server to drop whatever arrives; left alone,
     * such a row would outlive the send as a chip for a file already delivered.
     */
    fun supersededIds(key: String): List<String> =
        _state.value.staged[key].orEmpty().filter { it.state != StagedState.Uploaded }.map { it.id }

    fun hasAny(key: String): Boolean =
        _state.value.staged[key].orEmpty().isNotEmpty() || _state.value.remote[key].orEmpty().isNotEmpty()

    private suspend fun ignoringRefusal(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            // The row is swept by the server once nothing claims it.
        }
    }
}
