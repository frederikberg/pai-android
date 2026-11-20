package com.frederikberg.pai.kit.api

import com.frederikberg.pai.contract.AnswerBlockerResponse
import com.frederikberg.pai.contract.CancelResponse
import com.frederikberg.pai.contract.CloseResponse
import com.frederikberg.pai.contract.ContractJson
import com.frederikberg.pai.contract.DeleteResponse
import com.frederikberg.pai.contract.MoveToBackgroundResponse
import com.frederikberg.pai.contract.ResumeResponse
import com.frederikberg.pai.contract.Session
import com.frederikberg.pai.contract.SessionSearchRanking
import com.frederikberg.pai.contract.WithdrawPendingResponse
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.ApiError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One page of the browse list; [nextCursor] is opaque and goes back verbatim, null once there is no further page. */
class SessionsPage(
    val sessions: List<Session>,
    val nextCursor: String?,
)

/** What `kind=` asks `GET /api/sessions` for: everything the session list shows, decided on the server. */
const val SESSION_KIND_LISTED = "listed"

/**
 * The browse list, newest activity first. Filters are ignored by the incremental
 * mode ([sessionsChangedSince]), which answers a different question.
 */
suspend fun ApiClient.listSessions(
    cursor: String? = null,
    limit: Int? = null,
    kind: String? = SESSION_KIND_LISTED,
    query: String? = null,
    agent: String? = null,
    parent: String? = null,
    activeSince: String? = null,
    activeUntil: String? = null,
): SessionsPage {
    val answer =
        sendForAnswer(
            ListSerializer(Session.serializer()),
            "/sessions",
            query =
                query(
                    "cursor" to cursor,
                    "limit" to limit,
                    "kind" to kind,
                    "q" to query,
                    "agent" to agent,
                    "parent" to parent,
                    "active_since" to activeSince,
                    "active_until" to activeUntil,
                ),
        )
    return SessionsPage(answer.body, answer.headers["X-Next-Cursor"]?.takeIf { it.isNotEmpty() })
}

/** Incremental sync: the sessions that changed after [since] (ISO 8601), oldest change first. */
suspend fun ApiClient.sessionsChangedSince(
    since: String,
    limit: Int? = null,
): List<Session> =
    send(
        ListSerializer(Session.serializer()),
        "/sessions",
        query =
            query(
                "since" to since,
                "limit" to limit,
            ),
    )

enum class SessionSearchMode(
    val wire: String,
) {
    Fuzzy("fuzzy"),
    Semantic("semantic"),
}

/** One search result: the session and how it matched. */
class SessionSearchHit(
    val session: Session,
    val ranking: SessionSearchRanking,
)

/** Search results, and whether the list was cut to the requested limit. */
class SessionSearchPage(
    val hits: List<SessionSearchHit>,
    val truncated: Boolean,
)

suspend fun ApiClient.searchSessions(
    text: String,
    mode: SessionSearchMode = SessionSearchMode.Fuzzy,
    limit: Int? = null,
    since: String? = null,
    until: String? = null,
): SessionSearchPage {
    val answer =
        sendForAnswer(
            JsonArray.serializer(),
            "/sessions/search",
            query = query("q" to text, "mode" to mode.wire, "limit" to limit, "since" to since, "until" to until),
        )
    val hits =
        try {
            answer.body.map { element: JsonElement ->
                SessionSearchHit(
                    ContractJson.decodeFromJsonElement(Session.serializer(), element),
                    ContractJson.decodeFromJsonElement(SessionSearchRanking.serializer(), element),
                )
            }
        } catch (e: SerializationException) {
            throw ApiError.Decoding(e.message.orEmpty(), e)
        }
    return SessionSearchPage(hits, answer.headers["X-Search-Truncated"] == "true")
}

private suspend fun ApiClient.patchSession(
    sessionId: String,
    fields: JsonObject,
): Session =
    send(Session.serializer(), "/session/$sessionId", method = "PATCH", body = encode(JsonObject.serializer(), fields))

/** Naming a session by hand also locks the name against the server's own naming. */
suspend fun ApiClient.renameSession(
    sessionId: String,
    title: String,
): Session = patchSession(sessionId, buildJsonObject { put("title", title) })

suspend fun ApiClient.setSessionPinned(
    sessionId: String,
    pinned: Boolean,
): Session = patchSession(sessionId, buildJsonObject { put("pinned", pinned) })

/** Stops a running turn. */
suspend fun ApiClient.cancelSession(sessionId: String): CancelResponse =
    send(CancelResponse.serializer(), "/session/$sessionId/cancel", method = "POST")

/**
 * Pulls every still-queued send back into the session's server-side draft. [clientMessageIds] names
 * sends this device still holds that may already have left it; the answer says which are withdrawn
 * and which Claude already has.
 */
suspend fun ApiClient.withdrawPending(
    sessionId: String,
    clientMessageIds: List<String> = emptyList(),
): WithdrawPendingResponse =
    send(
        WithdrawPendingResponse.serializer(),
        "/session/$sessionId/withdraw-pending",
        method = "POST",
        body =
            encode(
                JsonObject.serializer(),
                buildJsonObject { put("client_message_ids", JsonArray(clientMessageIds.map(::JsonPrimitive))) },
            ),
    )

/** Makes Claude take the messages it holds in its queue now. The answer is `{status, …}`, read by the caller. */
suspend fun ApiClient.sendQueuedNow(sessionId: String): JsonObject =
    send(JsonObject.serializer(), "/session/$sessionId/send-now", method = "POST")

suspend fun ApiClient.moveToBackground(sessionId: String): MoveToBackgroundResponse =
    send(MoveToBackgroundResponse.serializer(), "/session/$sessionId/background", method = "POST")

suspend fun ApiClient.closeSession(sessionId: String): CloseResponse =
    send(CloseResponse.serializer(), "/session/$sessionId/close", method = "POST")

suspend fun ApiClient.resumeSession(sessionId: String): ResumeResponse =
    send(ResumeResponse.serializer(), "/session/$sessionId/resume", method = "POST")

suspend fun ApiClient.deleteSession(sessionId: String): DeleteResponse =
    send(DeleteResponse.serializer(), "/session/$sessionId", method = "DELETE")

/** Answers the prompt a session is parked on, by the key of one of its options. */
suspend fun ApiClient.answerBlocker(
    sessionId: String,
    key: String,
): AnswerBlockerResponse =
    send(
        AnswerBlockerResponse.serializer(),
        "/session/$sessionId/blocker/answer",
        method = "POST",
        body = encode(JsonObject.serializer(), buildJsonObject { put("key", key) }),
    )
