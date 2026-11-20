package com.frederikberg.pai.kit.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorded stream is what the server wrote for one session: an init page, a status, a batch, a ping and a draft signal. */
class StreamEventsTest {
    private fun recording(): List<SseRecord> {
        val bytes =
            checkNotNull(javaClass.getResourceAsStream("/session_stream_recording.sse")) { "missing recording" }
                .readBytes()
        val splitter = LineSplitter()
        val accumulator = SseEventAccumulator()
        return splitter.ingest(bytes).mapNotNull(accumulator::ingest).filter { it.name != SseClient.HEARTBEAT }
    }

    @Test
    fun theRecordingDecodesIntoTheEventsInTheOrderTheServerSentThem() {
        val events = recording().map { SessionEvent.from(it) }

        assertEquals(
            listOf("Init", "Status", "Batch", "Draft"),
            events.map { it::class.simpleName },
        )
    }

    @Test
    fun theInitPageCarriesItsEntriesTheLiveCursorAndTheEventId() {
        val init = SessionEvent.from(recording().first()) as SessionEvent.Init

        assertEquals(2, init.page.entries.size)
        assertEquals("Hello there", init.page.entries[0].content)
        assertEquals(2L, init.page.cursor)
        assertEquals(2L, init.eventId)
        assertEquals(false, init.page.hasMore)
        assertEquals(18_400L, init.page.sessionTokens)
        assertEquals(
            "assistant",
            init.page.entries[1]
                .type.raw,
        )
        assertEquals(
            "Read",
            init.page.entries[1]
                .toolCalls!!
                .single()
                .name,
        )
    }

    @Test
    fun theStatusCarriesTheDisplayStateTheQueueAndAPendingSend() {
        val status = (SessionEvent.from(recording()[1]) as SessionEvent.Status).status

        assertEquals("working", status.displayState!!.raw)
        assertEquals(listOf("one more thing"), status.queuedTexts)
        assertEquals(7L, status.pendingSends.single().id)
        assertEquals("choice_prompt", status.blocker!!.kind.raw)
        assertEquals(
            "1",
            status.blocker!!
                .options
                .first()
                .key,
        )
    }

    @Test
    fun theBatchEventIdIsTheNewestEntry() {
        val batch = SessionEvent.from(recording()[2]) as SessionEvent.Batch

        assertEquals(3L, batch.eventId)
        assertEquals(listOf(3L), batch.batch.entries.map { it.id })
    }

    @Test
    fun anEventNameThisAppDoesNotKnowIsKeptNotDropped() {
        assertEquals(
            SessionEvent.Unrecognised("future"),
            SessionEvent.from(SseRecord(null, "future", "{}")),
        )
    }

    @Test
    fun aKnownEventWithABrokenBodyIsUndecodableNotACrash() {
        val event = SessionEvent.from(SseRecord("1", "batch", """{"entries":"nope"}"""))

        assertTrue(event.toString(), event is SessionEvent.Undecodable)
    }

    @Test
    fun theNotificationStreamsEventsDecode() {
        val notification =
            NotificationEvent.from(
                SseRecord(
                    "n1",
                    "notification",
                    """{"notification":{"id":"n1","kind":"agent","title":"Done","body":"","created_at":"2026-11-20T09:00:00+00:00",
                        "read_at":null,"session_id":"s1","session_title":"Garden","anchor":{"message_id":9},"alert":null,
                        "task_id":null,"task_name":null,"run_slug":null},"unread":3,"in_use":true}""",
                ),
            ) as NotificationEvent.Notification

        assertEquals(3, notification.event.unread)
        assertEquals(
            9L,
            notification.event.notification.anchor!!
                .messageId,
        )

        assertEquals(NotificationEvent.SessionsChanged, NotificationEvent.from(SseRecord(null, "sessions", "{}")))
        assertEquals(NotificationEvent.Read(5), NotificationEvent.from(SseRecord(null, "read", """{"unread":5}""")))
        assertEquals(
            NotificationEvent.OwnerSignal("decisions"),
            NotificationEvent.from(SseRecord(null, "decisions", "")),
        )
        assertEquals(NotificationEvent.Unrecognised("x"), NotificationEvent.from(SseRecord(null, "x", "")))
        assertTrue(NotificationEvent.from(SseRecord(null, "read", "{}")) is NotificationEvent.Undecodable)
    }
}
