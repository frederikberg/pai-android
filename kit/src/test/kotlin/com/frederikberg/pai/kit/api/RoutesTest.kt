package com.frederikberg.pai.kit.api

import com.frederikberg.pai.kit.auth.AuthMode
import com.frederikberg.pai.kit.net.ApiClient
import com.frederikberg.pai.kit.net.RequestFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Every route the client names is one a guest may call, with the method the server lists it under.
 * The table is the vendored copy of the server's own; the calls fail at the network on purpose, the
 * request is what is under test.
 */
class RoutesTest {
    private val sent = mutableListOf<Request>()

    private val api =
        ApiClient(
            RequestFactory("https://pai.example.com", "Test phone") { AuthMode.None },
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    sent += chain.request()
                    throw IOException("recorded")
                }.build(),
        )

    private val guestRoutes: List<Pair<String, Regex>> =
        File("../contract/src/test/resources/api-contract/guest-routes.txt")
            .also { assertTrue("run from the kit module: ${it.absolutePath}", it.exists()) }
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val (method, path) = line.split(' ', limit = 2)
                method to
                    Regex(path.split('/').joinToString("/") { if (it.startsWith("{")) "[^/]+" else Regex.escape(it) })
            }

    private fun call(block: suspend ApiClient.() -> Unit): Request {
        sent.clear()
        runCatching { runBlocking { api.block() } }
        return sent.last()
    }

    private val sid = "00000000-0000-4000-8000-0000000000a1"

    private val calls: Map<String, suspend ApiClient.() -> Unit> =
        mapOf(
            "getMe" to { getMe() },
            "getUserSettings" to { getUserSettings() },
            "updateUserSettings" to { setEmailNotifications(true) },
            "listSignIns" to { listSignIns() },
            "signOutCurrentSignIn" to { signOutCurrentSignIn() },
            "revokeSignIn" to { revokeSignIn("s1") },
            "signOutEverywhere" to { signOutEverywhere() },
            "listSessions" to { listSessions(cursor = "c", limit = 50) },
            "sessionsChangedSince" to { sessionsChangedSince("2026-11-20T00:00:00+00:00") },
            "searchSessions" to { searchSessions("train") },
            "renameSession" to { renameSession(sid, "x") },
            "setSessionPinned" to { setSessionPinned(sid, true) },
            "cancelSession" to { cancelSession(sid) },
            "withdrawPending" to { withdrawPending(sid, listOf("c1")) },
            "sendQueuedNow" to { sendQueuedNow(sid) },
            "moveToBackground" to { moveToBackground(sid) },
            "closeSession" to { closeSession(sid) },
            "resumeSession" to { resumeSession(sid) },
            "deleteSession" to { deleteSession(sid) },
            "answerBlocker" to { answerBlocker(sid, "1") },
            "listMessages" to { listMessages(sid, MessageWindow.Tail, 100) },
            "findMessages" to { findMessages(sid, text = "x") },
            "postMessage" to { postMessage("m1", "hi", sessionId = sid) },
            "startSession" to { startSession("hi") },
            "getSessionTypes" to { getSessionTypes() },
            "getSessionModels" to { getSessionModels() },
            "getSkillsOfSession" to { getSkillsOfSession(sid) },
            "getUsage" to { getUsage() },
            "listDrafts" to { listDrafts() },
            "putDraft" to { putDraft(sid, "t", "dev") },
            "discardDraft" to { discardDraft(sid) },
            "addDraftAttachment" to { addDraftAttachment(sid, "a1", "p.png", "image/png", ByteArray(3)) },
            "removeDraftAttachment" to { removeDraftAttachment(sid, "a1") },
            "getDraftAttachmentBytes" to { getDraftAttachmentBytes(sid, "a1") },
            "listNotifications" to { listNotifications(limit = 10) },
            "getNotificationSummary" to { getNotificationSummary() },
            "getNotification" to { getNotification("n1") },
            "markNotificationsRead" to { markNotificationsRead(ReadTarget.All) },
            "markNotificationsUnread" to { markNotificationsUnread(listOf("n1")) },
            "getPushChannels" to { getPushChannels() },
            "getVapidKey" to { getVapidKey() },
            "ensureVapidKey" to { ensureVapidKey() },
            "createPushSubscription" to { createPushSubscription("https://push.example.com/x", "k", "a") },
            "listPushSubscriptions" to { listPushSubscriptions() },
            "setMutedChannels" to { setMutedChannels("p1", listOf("agent")) },
            "deletePushSubscription" to { deletePushSubscription("p1") },
            "sendTestPush" to { sendTestPush() },
        )

    @Test
    fun everyRouteTheClientNamesIsOneAGuestMayCallWithThatMethod() {
        calls.forEach { (name, block) ->
            val request = call(block)
            val allowed =
                guestRoutes.any { (method, pattern) ->
                    method == request.method &&
                        pattern.matches(request.url.encodedPath)
                }
            assertTrue("$name: ${request.method} ${request.url.encodedPath} is not in the guest route table", allowed)
        }
    }

    @Test
    fun theClientNamesEveryRouteItSendsUnderTheApiPrefix() {
        calls.forEach { (name, block) -> assertTrue(name, call(block).url.encodedPath.startsWith("/api/")) }
    }

    @Test
    fun aMessagePageNamesExactlyOneWindow() {
        val query = call { listMessages(sid, MessageWindow.Before(40), 25) }.url
        assertEquals("40", query.queryParameter("before_id"))
        assertEquals("25", query.queryParameter("limit"))
        assertEquals(null, query.queryParameter("tail"))
        assertEquals(null, query.queryParameter("after_id"))

        assertEquals("true", call { listMessages(sid, MessageWindow.Tail) }.url.queryParameter("tail"))
        assertEquals("7", call { listMessages(sid, MessageWindow.Around(7)) }.url.queryParameter("around_id"))
    }

    @Test
    fun theSessionListAsksForTheServersListedSetByDefault() {
        assertEquals("listed", call { listSessions() }.url.queryParameter("kind"))
    }

    @Test
    fun aSendIsMultipartAndCarriesItsClientMessageId() {
        val request = call { postMessage("m-42", "hello", sessionId = sid, draftAttachmentIds = listOf("a1", "a2")) }
        val body =
            okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

        assertTrue(
            request.body!!
                .contentType()
                .toString()
                .startsWith("multipart/form-data; boundary="),
        )
        assertTrue(body, "name=\"client_message_id\"\r\n\r\nm-42" in body)
        assertTrue(body, "name=\"session_id\"\r\n\r\n$sid" in body)
        assertEquals(2, Regex("name=\"draft_attachment_ids\"").findAll(body).count())
    }

    @Test
    fun aDraftKeyIsEscapedInThePath() {
        assertEquals("/api/drafts/a%20b", call { putDraft("a b", "t", "dev") }.url.encodedPath)
    }

    @Test
    fun theDeviceNameRidesEveryRequest() {
        assertEquals("Test phone", call { getMe() }.header("X-Device-Name"))
    }
}
