package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.Message
import com.frederikberg.pai.contract.MessageFindResult
import com.frederikberg.pai.contract.PostMessageResponse
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.MultipartForm
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which part of a transcript a page of messages is: exactly one of these, never combined. */
sealed interface MessageWindow {
    /** The oldest messages after [afterId], or from the start when null. */
    data class After(
        val afterId: Long,
    ) : MessageWindow

    /** The messages just before [beforeId]: the next older page. */
    data class Before(
        val beforeId: Long,
    ) : MessageWindow

    /** The newest messages. */
    data object Tail : MessageWindow

    /** A window straddling [aroundId]; the server answers 404 when the id is not in this session. */
    data class Around(
        val aroundId: Long,
    ) : MessageWindow
}

/** One page of a session's transcript, ascending by id. */
suspend fun ApiClient.listMessages(
    sessionId: String,
    window: MessageWindow = MessageWindow.Tail,
    limit: Int? = null,
): List<Message> =
    send(
        ListSerializer(Message.serializer()),
        "/session/$sessionId/messages",
        query =
            query(
                "after_id" to (window as? MessageWindow.After)?.afterId,
                "before_id" to (window as? MessageWindow.Before)?.beforeId,
                "tail" to (if (window == MessageWindow.Tail) true else null),
                "around_id" to (window as? MessageWindow.Around)?.aroundId,
                "limit" to limit,
            ),
    )

/** Ids of every message matching a text ([text]) or kind ([kind]) predicate; exactly one of the two. */
suspend fun ApiClient.findMessages(
    sessionId: String,
    text: String? = null,
    kind: String? = null,
    afterId: Long? = null,
    limit: Int? = null,
): MessageFindResult =
    send(
        MessageFindResult.serializer(),
        "/session/$sessionId/messages/find",
        query = query("q" to text, "kind" to kind, "after_id" to afterId, "limit" to limit),
    )

/** An image or file a send carries inline. */
class MessageFile(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/** What a brand-new session launches with; ignored by the server once a send names an existing session. */
class NewSessionChoices(
    val sessionType: String? = null,
    val workingDir: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    val noMemory: Boolean = false,
)

/**
 * Sends a message to [sessionId], or creates the session from it when [sessionId] is null
 * (`POST /api/messages`, multipart). [clientMessageId] makes the send idempotent: the server answers
 * the send it already made for an id it has seen, so a retry reuses the id and never mints a new one.
 * [draftAttachmentIds] are the files already on the draft that this send claims, exactly those.
 */
suspend fun ApiClient.postMessage(
    clientMessageId: String,
    text: String,
    sessionId: String? = null,
    draftAttachmentIds: List<String> = emptyList(),
    files: List<MessageFile> = emptyList(),
    newSession: NewSessionChoices = NewSessionChoices(),
): PostMessageResponse {
    val form =
        MultipartForm
            .Builder()
            .field("message", text)
            .field("client_message_id", clientMessageId)
            .apply {
                sessionId?.let { field("session_id", it) }
                draftAttachmentIds.forEach { field("draft_attachment_ids", it) }
                files.forEach { file("files", it.filename, it.contentType, it.bytes) }
                newSession.sessionType?.let { field("session_type", it) }
                newSession.workingDir?.let { field("working_dir", it) }
                newSession.agent?.let { field("agent", it) }
                newSession.model?.let { field("model", it) }
                newSession.thinking?.let { field("thinking", it) }
                if (newSession.noMemory) field("no_memory", "true")
            }.build()
    return send(
        PostMessageResponse.serializer(),
        "/messages",
        method = "POST",
        body = form.bytes,
        contentType = form.contentType,
    )
}

/** The first message of a new session, JSON form; answers the new session's id. */
suspend fun ApiClient.startSession(
    message: String,
    choices: NewSessionChoices = NewSessionChoices(),
): String {
    val body: JsonObject =
        buildJsonObject {
            put("message", message)
            choices.sessionType?.let { put("session_type", it) }
            choices.workingDir?.let { put("working_dir", it) }
            choices.agent?.let { put("agent", it) }
            choices.model?.let { put("model", it) }
            choices.thinking?.let { put("thinking", it) }
            if (choices.noMemory) put("no_memory", true)
        }
    val answer =
        send(JsonObject.serializer(), "/session/new", method = "POST", body = encode(JsonObject.serializer(), body))
    return checkNotNull(answer["session_id"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }) {
        "the answer names no session"
    }
}
