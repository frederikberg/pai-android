@file:OptIn(ExperimentalCoroutinesApi::class)

package com.frederikberg.pai.kit.live

import com.frederikberg.pai.kit.auth.AuthMode
import com.frederikberg.pai.kit.net.RequestFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConnectionsTest {
    private class Rig(
        val streams: StreamConnections,
        val transport: ScriptedTransport,
        val signedOut: MutableList<String>,
    )

    private fun TestScope.rig(): Rig {
        val transport = ScriptedTransport()
        val signedOut = mutableListOf<String>()
        val streams =
            StreamConnections(
                scope = backgroundScope,
                requests = RequestFactory("https://pai.example.com", "Test phone") { AuthMode.None },
                transport = transport,
                credentialRefresher = null,
                onAuthenticationFailure = { signedOut += "signed out" },
                timing =
                    SseTiming(
                        initialBackoffMs = 100,
                        maxBackoffMs = 800,
                        minAttemptIntervalMs = 50,
                        staleTimeoutMs = 60_000,
                    ),
            )
        return Rig(streams, transport, signedOut)
    }

    @Test
    fun aSessionStreamResumesFromTheGivenEntryAndDeliversItsEventsInOrder() =
        runTest {
            val rig = rig()
            val connection = rig.transport.next()
            val stream = rig.streams.openSession("s1", resumeFromEventId = 41)
            val seen = mutableListOf<SessionEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.events.toList(seen) }

            stream.connect()
            runCurrent()
            connection.send("id: 42\nevent: batch\ndata: {\"entries\":[]}\n\n")
            connection.send("event: status\ndata: {\"status\":\"active\"}\n\n")
            runCurrent()

            assertEquals(
                "41",
                rig.transport.requests
                    .single()
                    .header("Last-Event-ID"),
            )
            assertEquals(
                "/api/session/s1/stream",
                rig.transport.requests
                    .single()
                    .url.encodedPath,
            )
            assertEquals(listOf("Batch", "Status"), seen.map { it::class.simpleName })
            assertEquals(42L, (seen.first() as SessionEvent.Batch).eventId)
        }

    @Test
    fun aDeletedSessionIsReportedGoneAndTheStreamStaysClosed() =
        runTest {
            val rig = rig()
            rig.transport.otherwise {
                ScriptedTransport.Connection(
                    410,
                    """{"detail":"Session has been deleted"}""".toByteArray(),
                )
            }
            val stream = rig.streams.openSession("s1")

            stream.connect()
            advanceTimeBy(5_000)

            assertEquals(StreamGone(410, "Session has been deleted"), stream.gone.value)
            assertEquals(1, rig.transport.requests.size)
        }

    @Test
    fun theBackgroundClosesEveryOpenStreamAndComingBackReopensThem() =
        runTest {
            val rig = rig()
            rig.transport.otherwise { ScriptedTransport.Connection() }
            rig.streams.notifications.connect()
            val session = rig.streams.openSession("s1")
            session.connect()
            runCurrent()
            assertEquals(2, rig.transport.live)

            rig.streams.pause()
            runCurrent()
            assertEquals(0, rig.transport.live)

            rig.streams.resume()
            advanceTimeBy(200)
            assertEquals(2, rig.transport.live)
        }

    @Test
    fun aSessionStreamOpenedInTheBackgroundStaysQuietUntilTheAppReturns() =
        runTest {
            val rig = rig()
            rig.transport.otherwise { ScriptedTransport.Connection() }
            rig.streams.pause()
            val stream = rig.streams.openSession("s1")

            stream.connect()
            runCurrent()
            assertTrue(rig.transport.requests.isEmpty())

            rig.streams.resume()
            runCurrent()
            assertEquals(1, rig.transport.requests.size)
        }

    @Test
    fun aClosedSessionStreamIsForgottenAndLeftAloneByTheBackground() =
        runTest {
            val rig = rig()
            rig.transport.otherwise { ScriptedTransport.Connection() }
            val stream = rig.streams.openSession("s1")
            stream.connect()
            runCurrent()

            rig.streams.closeSession(stream)
            rig.streams.resume()
            advanceTimeBy(1_000)

            assertEquals(1, rig.transport.requests.size)
            assertNull(stream.gone.value)
        }

    @Test
    fun theAccountStreamUsesItsOwnRoute() =
        runTest {
            val rig = rig()
            rig.transport.otherwise { ScriptedTransport.Connection() }

            rig.streams.notifications.connect()
            runCurrent()

            assertEquals(
                "/api/notifications/stream",
                rig.transport.requests
                    .single()
                    .url.encodedPath,
            )
        }
}
