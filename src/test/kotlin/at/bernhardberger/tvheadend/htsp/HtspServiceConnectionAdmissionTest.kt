package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class HtspServiceConnectionAdmissionTest : HtspServiceLifecycleFixture() {

    @Test
    fun identicalEndpointReusesOnlyTheSameLiveConnectionIdentity() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                val endpoint = HtspEndpoint("127.0.0.1", server.port, "viewer", "secret")
                val first = service.connect(endpoint) as HtspConnectOutcome.Connected
                val reused = service.connect(endpoint) as HtspConnectOutcome.Connected

                assertSame(first.connection.generation, reused.connection.generation)
                assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
                service.disconnect()
            }
        }
    }

    @Test
    fun replacementBetweenTransportInstallAndReaderOwnershipLeavesOnlyReplacementReader() {
        val firstTransportInstalled = CompletableDeferred<Unit>()
        val replacementAdmitted = CompletableDeferred<Unit>()
        val resumeFirst = CompletableDeferred<Unit>()
        val admissions = AtomicInteger()
        val installations = AtomicInteger()
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service(
                    afterConnectionAdmission = {
                        if (admissions.incrementAndGet() == 2) {
                            replacementAdmitted.complete(Unit)
                        }
                    },
                    afterTransportInstallation = {
                        if (installations.incrementAndGet() == 1) {
                            firstTransportInstalled.complete(Unit)
                            resumeFirst.await()
                        }
                    },
                )
                runBlocking {
                    val events = CopyOnWriteArrayList<HtspTransportEvent>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        service.events.collect { events += it }
                    }
                    val stale = async(Dispatchers.IO) {
                        runCatching {
                            service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                        }.exceptionOrNull()
                    }
                    withTimeout(1_000L) { firstTransportInstalled.await() }

                    val replacement = async(Dispatchers.IO) {
                        service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    }
                    withTimeout(1_000L) { replacementAdmitted.await() }
                    runCatching {
                        firstServer.sendServerMessage("channelAdd", mapOf("channelId" to 11L))
                    }
                    resumeFirst.complete(Unit)

                    assertTrue(withTimeout(1_000L) { stale.await() } is CancellationException)
                    val connected = withTimeout(1_000L) { replacement.await() }
                        as HtspConnectOutcome.Connected
                    assertSame(connected.connection, service.liveConnection.value)
                    assertEquals(1, serviceOwnedJobCount(service))

                    replacementServer.sendServerMessage("channelAdd", mapOf("channelId" to 22L))
                    withTimeout(1_000L) {
                        while (events.none { event ->
                                event is HtspTransportEvent.ServerMessage &&
                                    event.message is HtspChannelAddMessage &&
                                    event.message.channelId == 22L
                            }) {
                            delay(1L)
                        }
                    }
                    assertTrue(
                        events.none { event ->
                            event is HtspTransportEvent.ServerMessage &&
                                event.message is HtspChannelAddMessage &&
                                event.message.channelId == 11L
                        },
                    )

                    service.close(connected.connection.generation)
                    collector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun admittedReplacementImmediatelyDetachesOldLiveStateAndNeverRevivesItsToken() {
        val replacementAdmitted = CompletableDeferred<Unit>()
        val resumeReplacement = CompletableDeferred<Unit>()
        val admissions = AtomicInteger()
        FakeHtspServer(respondToHello = true, expectedConnections = 2).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { queuedServer ->
                val service = service(
                    afterConnectionAdmission = {
                        if (admissions.incrementAndGet() == 2) {
                            replacementAdmitted.complete(Unit)
                            resumeReplacement.await()
                        }
                    },
                )
                runBlocking {
                    val old = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val replacement = async(Dispatchers.IO) {
                        runCatching {
                            service.connect(HtspEndpoint("127.0.0.1", queuedServer.port))
                        }.exceptionOrNull()
                    }
                    withTimeout(1_000L) { replacementAdmitted.await() }

                    assertNull(service.liveConnection.value)
                    assertTrue(!service.isCurrent(old))
                    assertNull(service.commitIfCurrent(old) { "revived" })
                    assertNull(service.commitIfLive(old) { it })

                    val newest = service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                        as HtspConnectOutcome.Connected
                    assertTrue(!service.isCurrent(old))
                    assertNull(service.commitIfCurrent(old) { "revived" })
                    assertSame(newest.connection, service.liveConnection.value)

                    resumeReplacement.complete(Unit)
                    assertTrue(withTimeout(1_000L) { replacement.await() } is CancellationException)
                    assertSame(newest.connection, service.liveConnection.value)
                    service.disconnect(newest.connection.generation)
                }
            }
        }
    }

    @Test
    fun staleForcedAdmissionCannotRetireNewerTransportRequestOrEvent() {
        val forcedAdmitted = CompletableDeferred<Unit>()
        val resumeForced = CompletableDeferred<Unit>()
        val admissions = AtomicInteger()
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { forcedServer ->
                FakeHtspServer(
                    respondToHello = true,
                    captureOnePostHandshakeRequest = true,
                ).use { newestServer ->
                    val service = service(
                        afterConnectionAdmission = {
                            if (admissions.incrementAndGet() == 2) {
                                forcedAdmitted.complete(Unit)
                                resumeForced.await()
                            }
                        },
                    )
                    runBlocking {
                        service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                        val forced = async(Dispatchers.IO) {
                            runCatching {
                                service.connect(
                                    HtspEndpoint("127.0.0.1", forcedServer.port),
                                    HtspConnectOptions(forceReconnect = true),
                                )
                            }.exceptionOrNull()
                        }
                        withTimeout(1_000L) { forcedAdmitted.await() }

                        val newest = service.connect(HtspEndpoint("127.0.0.1", newestServer.port))
                            as HtspConnectOutcome.Connected
                        val pendingRequest = async(Dispatchers.IO) {
                            service.request(
                                method = "newestRequest",
                                timeoutMs = 5_000L,
                                disconnectOnTimeout = false,
                            )
                        }
                        assertTrue(newestServer.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                        resumeForced.complete(Unit)
                        assertTrue(withTimeout(1_000L) { forced.await() } is CancellationException)
                        assertSame(newest.connection, service.liveConnection.value)
                        newestServer.replyToCapturedPostHandshakeRequest()
                        assertEquals(
                            "newestRequest",
                            withTimeout(1_000L) { pendingRequest.await() }.method,
                        )

                        val event = async(start = CoroutineStart.UNDISPATCHED) {
                            service.events.first { candidate ->
                                candidate is HtspTransportEvent.ServerMessage &&
                                    candidate.message is HtspChannelAddMessage &&
                                    candidate.message.channelId == 33L
                            }
                        }
                        newestServer.sendServerMessage("channelAdd", mapOf("channelId" to 33L))
                        assertTrue(withTimeout(1_000L) { event.await() } is HtspTransportEvent.ServerMessage)
                        service.disconnect(newest.connection.generation)
                    }
                }
            }
        }
    }

    @Test
    fun changedAddressReconnectsWithoutExplicitForce() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port, "viewer", "secret"),
                    ) as HtspConnectOutcome.Connected
                    val replacement = service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port, "viewer", "secret"),
                    ) as HtspConnectOutcome.Connected

                    assertNotSame(first.connection.generation, replacement.connection.generation)
                    assertEquals(listOf("hello", "authenticate"), replacementServer.handshakeMethods)
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun changedCredentialsReconnectWithoutRenderingCredentialIdentity() {
        FakeHtspServer(respondToHello = true, expectedConnections = 2).use { server ->
            val service = service()
            runBlocking {
                val first = service.connect(
                    HtspEndpoint("127.0.0.1", server.port, "viewer-a", "secret-a"),
                ) as HtspConnectOutcome.Connected
                val replacement = service.connect(
                    HtspEndpoint("127.0.0.1", server.port, "viewer-b", "secret-b"),
                ) as HtspConnectOutcome.Connected

                assertNotSame(first.connection.generation, replacement.connection.generation)
                assertEquals(
                    listOf("hello", "authenticate", "hello", "authenticate"),
                    server.handshakeMethods,
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun replacementConnectCannotBeOverwrittenByCancelledAttemptState() {
        FakeHtspServer(respondToHello = false).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val observed = CopyOnWriteArrayList<ConnectionState>()
                    val collector = launch {
                        service.state.collect { observed += it }
                    }
                    val first = launch(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = firstServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 5_000,
                            soTimeoutMs = 50,
                        )
                    }
                    withTimeout(1_000L) {
                        service.state.first {
                            it is ConnectionState.Connecting && it.port == firstServer.port
                        }
                    }

                    first.cancel()
                    val replacement = async(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = replacementServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 1_000,
                            soTimeoutMs = 50,
                            forceReconnect = true,
                        )
                    }
                    replacement.await()
                    first.join()
                    delay(50L)

                    val replacementStart = observed.indexOfFirst {
                        it is ConnectionState.Connecting && it.port == replacementServer.port
                    }
                    assertTrue(replacementStart >= 0)
                    assertTrue(observed.drop(replacementStart).none { it is ConnectionState.Error })
                    assertEquals(
                        replacementServer.port,
                        (service.state.value as ConnectionState.Connected).port,
                    )
                    service.disconnect()
                    collector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun cancelledReplacementWaitingForConnectOwnerLeavesDisconnectedState() {
        FakeHtspServer(respondToHello = false).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = launch(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = firstServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 5_000,
                            soTimeoutMs = 50,
                        )
                    }
                    withTimeout(1_000L) {
                        service.state.first { it is ConnectionState.Connecting }
                    }
                    val firstAttempt = service.currentConnectionAttemptId()

                    val replacement = launch(Dispatchers.IO) {
                        service.connect(
                            host = "127.0.0.1",
                            port = replacementServer.port,
                            connectTimeoutMs = 1_000,
                            responseTimeoutMs = 1_000,
                            soTimeoutMs = 50,
                            forceReconnect = true,
                        )
                    }
                    withTimeout(1_000L) {
                        while (service.currentConnectionAttemptId() == firstAttempt) delay(1L)
                    }

                    replacement.cancelAndJoin()
                    withTimeout(1_000L) { first.join() }

                    assertTrue(service.state.value is ConnectionState.Disconnected)
                }
            }
        }
    }
}
