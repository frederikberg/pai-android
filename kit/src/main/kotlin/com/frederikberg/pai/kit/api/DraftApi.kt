package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.Draft
import com.frederikberg.pai.contract.DraftAttachment
import com.frederikberg.pai.contract.DraftDiscardResult
import com.frederikberg.pai.contract.DraftWriteResult
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.MultipartForm
import com.frederikberg.pai.kit.net.RawBody
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

private fun draftPath(key: String) = "/drafts/" + URLEncoder.encode(key, Charsets.UTF_8).replace("+", "%20")

/** Every draft of the caller, text and attachments. */
suspend fun ApiClient.listDrafts(): List<Draft> = send(ListSerializer(Draft.serializer()), "/drafts")

/** The launch choices a `new` draft carries beside its text; null leaves a choice unset. */
class DraftChoices(
    val sessionType: String? = null,
    val workingDir: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    val noMemory: Boolean? = null,
)

/**
 * Writes a draft unconditionally; the server keeps the last writer and answers the version the row
 * now stands at. An empty [text] writes an empty row, it never deletes one.
 */
suspend fun ApiClient.putDraft(
    key: String,
    text: String,
    deviceId: String,
    choices: DraftChoices = DraftChoices(),
): DraftWriteResult {
    val body: JsonObject =
        buildJsonObject {
            put("text", text)
            put("device_id", deviceId)
            choices.sessionType?.let { put("session_type", it) }
            choices.workingDir?.let { put("working_dir", it) }
            choices.model?.let { put("model", it) }
            choices.thinking?.let { put("thinking", it) }
            choices.noMemory?.let { put("no_memory", it) }
        }
    return send(
        DraftWriteResult.serializer(),
        draftPath(key),
        method = "PUT",
        body = encode(JsonObject.serializer(), body),
    )
}

/** Discards a draft with its attachments; answers what it destroyed so it can be offered back. */
suspend fun ApiClient.discardDraft(key: String): DraftDiscardResult =
    send(DraftDiscardResult.serializer(), draftPath(key), method = "DELETE")

/** Uploads one file onto a draft at once. [attachmentId] is minted by the caller, so a retry is the same row. */
suspend fun ApiClient.addDraftAttachment(
    key: String,
    attachmentId: String,
    filename: String,
    contentType: String,
    bytes: ByteArray,
): DraftAttachment {
    val form =
        MultipartForm
            .Builder()
            .field("id", attachmentId)
            .file("file", filename, contentType, bytes)
            .build()
    return send(
        DraftAttachment.serializer(),
        draftPath(key) + "/attachments",
        method = "POST",
        body = form.bytes,
        contentType = form.contentType,
    )
}

/** Removes one attachment (a tombstone, so a remove on one device beats a slow add on another). */
suspend fun ApiClient.removeDraftAttachment(
    key: String,
    attachmentId: String,
) = sendNoContent(draftPath(key) + "/attachments/" + attachmentId, "DELETE")

/** The bytes of a stored attachment, for a thumbnail or a viewer. */
suspend fun ApiClient.getDraftAttachmentBytes(
    key: String,
    attachmentId: String,
): RawBody = sendRaw(draftPath(key) + "/attachments/" + attachmentId)
