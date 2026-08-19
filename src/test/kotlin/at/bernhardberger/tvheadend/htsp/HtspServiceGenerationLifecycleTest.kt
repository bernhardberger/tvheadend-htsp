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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class HtspServiceGenerationLifecycleTest : HtspServiceLifecycleFixture() {

    @Test
    fun staleDisconnectCannotRetireReplacementButCurrentAndOwnerGlobalDisconnectCan() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true, expectedConnections = 2).use { replacementServer ->
                val service = service()
                runBlocking {
                    val stale = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val current = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation

                    val failure = runCatching { service.disconnect(stale) }.exceptionOrNull()
                    assertTrue(failure is CancellationException)
                    assertSame(current, service.liveConnection.value?.generation)
                    assertEquals(replacementServer.port, (service.state.value as HtspConnectionState.Connected).port)

                    service.disconnect(current)
                    assertNull(service.liveConnection.value)

                    service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    service.disconnect()
                    assertNull(service.liveConnection.value)
                }
            }
        }
    }

    @Test
    fun admittedDisconnectCannotRetireReplacementEstablishedBeforeTransportOwnership() {
        val teardownAdmitted = CompletableDeferred<Unit>()
        val resumeTeardown = CompletableDeferred<Unit>()
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(
                respondToHello = true,
                captureOnePostHandshakeRequest = true,
            ).use { replacementServer ->
                val service = service(
                    afterTeardownAdmission = {
                        teardownAdmitted.complete(Unit)
                        resumeTeardown.await()
                    },
                )
                runBlocking {
                    val stale = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val disconnect = async(Dispatchers.IO) {
                        runCatching { service.disconnect(stale) }.exceptionOrNull()
                    }
                    withTimeout(1_000L) { teardownAdmitted.await() }

                    val replacement = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val pendingRequest = async(Dispatchers.IO) {
                        service.request(
                            method = "replacementRequest",
                            timeoutMs = 5_000L,
                            disconnectOnTimeout = false,
                        )
                    }
                    assertTrue(
                        replacementServer.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS),
                    )

                    resumeTeardown.complete(Unit)
                    val failure = withTimeout(1_000L) { disconnect.await() }

                    assertTrue(failure is CancellationException)
                    assertSame(replacement, service.liveConnection.value?.generation)
                    assertEquals(
                        replacementServer.port,
                        (service.state.value as HtspConnectionState.Connected).port,
                    )
                    replacementServer.replyToCapturedPostHandshakeRequest()
                    assertEquals(
                        "replacementRequest",
                        withTimeout(1_000L) { pendingRequest.await() }.method,
                    )

                    val replacementEvent = async(start = CoroutineStart.UNDISPATCHED) {
                        service.events.first { event ->
                            event is HtspTransportEvent.ServerMessage &&
                                event.message is HtspChannelAddMessage &&
                                event.message.channelId == 44L
                        }
                    }
                    replacementServer.sendServerMessage("channelAdd", mapOf("channelId" to 44L))
                    assertTrue(
                        withTimeout(1_000L) { replacementEvent.await() } is
                            HtspTransportEvent.ServerMessage,
                    )

                    service.disconnect()
                    assertNull(service.liveConnection.value)
                    assertTrue(service.state.value is HtspConnectionState.Disconnected)
                }
            }
        }
    }

    @Test
    fun staleCloseCannotRetireOrTerminallyCloseReplacementButCurrentAndOwnerGlobalCloseCan() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val stale = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val current = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation

                    val failure = runCatching { service.close(stale) }.exceptionOrNull()
                    assertTrue(failure is CancellationException)
                    assertSame(current, service.liveConnection.value?.generation)
                    assertTrue(service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)) is HtspConnectOutcome.Connected)

                    service.close(current)
                    assertNull(service.liveConnection.value)
                    assertTrue(
                        service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)) is
                            HtspConnectOutcome.Failed,
                    )
                }
            }
        }

        val ownerGlobal = service()
        runBlocking { ownerGlobal.close() }
        assertTrue(runBlocking {
            ownerGlobal.connect(HtspEndpoint("127.0.0.1", 9982)) is HtspConnectOutcome.Failed
        })
    }

    @Test
    fun currentGenerationSurvivesGoneUntilReplacementInvalidatesIt() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection
                    val generation = first.generation
                    val liveSnapshot = service.commitIfLive(generation) { live -> live }
                    assertSame(first, liveSnapshot)
                    assertTrue(service.isCurrent(generation))
                    assertEquals("live", service.commitIfCurrent(generation) { "live" })

                    firstServer.closeClientTransport()
                    withTimeout(1_000L) {
                        service.liveConnection.first { snapshot -> snapshot == null }
                    }

                    assertNull(service.liveConnection.value)
                    assertTrue(service.isCurrent(generation))
                    assertEquals("gone", service.commitIfCurrent(generation) { "gone" })
                    assertNull(service.commitIfLive(generation) { it })

                    val replacement = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection
                    assertTrue(!service.isCurrent(generation))
                    assertNull(service.commitIfCurrent(generation) { "replaced" })
                    assertNull(service.commitIfLive(generation) { it })
                    assertTrue(service.isCurrent(replacement.generation))
                    assertSame(
                        replacement,
                        service.commitIfLive(replacement.generation) { live -> live },
                    )
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun expectedDisconnectLeavesCurrentGoneGenerationUntilNewerAttemptReplacesIt() {
        FakeHtspServer(respondToHello = true, expectedConnections = 2).use { server ->
            val service = service()
            runBlocking {
                val generation = (service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected).connection.generation

                service.disconnect(generation)
                assertNull(service.liveConnection.value)
                assertTrue(service.isCurrent(generation))
                assertEquals("gone", service.commitIfCurrent(generation) { "gone" })
                assertNull(service.commitIfLive(generation) { it })

                val replacement = (service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected).connection
                assertTrue(!service.isCurrent(generation))
                assertNull(service.commitIfCurrent(generation) { "replaced" })
                assertNull(service.commitIfLive(generation) { it })
                assertNotSame(generation, replacement.generation)
                assertTrue(service.isCurrent(replacement.generation))
                assertSame(
                    replacement,
                    service.commitIfLive(replacement.generation) { live -> live },
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun failedReplacementLeavesReplacementGoneAndDoesNotReviveOldGeneration() {
        FakeHtspServer(respondToHello = true, expectedConnections = 2).use { server ->
            val refusedPort = ServerSocket(0).use { closed -> closed.localPort }
            val service = service()
            runBlocking {
                val first = (service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected).connection.generation

                val failed = service.connect(
                    HtspEndpoint("127.0.0.1", refusedPort),
                    HtspConnectOptions(connectTimeoutMs = 200),
                )
                assertTrue(failed is HtspConnectOutcome.Failed)
                assertNull(service.liveConnection.value)
                assertTrue(!service.isCurrent(first))
                assertNull(service.commitIfCurrent(first) { "revived" })
                assertNull(service.commitIfLive(first) { it })
                val staleDisconnect = runCatching { service.disconnect(first) }.exceptionOrNull()
                assertTrue(staleDisconnect is CancellationException)
                assertNull(service.liveConnection.value)

                service.disconnect()
                assertNull(service.liveConnection.value)
                assertTrue(!service.isCurrent(first))

                val later = (service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected).connection
                assertTrue(!service.isCurrent(first))
                assertNotSame(first, later.generation)
                assertTrue(service.isCurrent(later.generation))
                assertSame(later, service.commitIfLive(later.generation) { live -> live })
                service.disconnect()
            }
        }
    }

    @Test
    fun concurrentLossAndReplacementLinearizeWithoutStaleLiveCommit() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val staleSnapshots = CopyOnWriteArrayList<HtspLiveConnection>()
                    val stopProbing = CountDownLatch(1)
                    val probeStarted = CountDownLatch(1)
                    val probe = thread(name = "generation-live-probe") {
                        probeStarted.countDown()
                        while (!stopProbing.await(0, TimeUnit.MILLISECONDS)) {
                            service.commitIfLive(first) { live ->
                                if (live.generation !== first) {
                                    staleSnapshots += live
                                }
                            }
                        }
                    }
                    assertTrue(probeStarted.await(1, TimeUnit.SECONDS))

                    firstServer.closeClientTransport()
                    val replacement = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection
                    stopProbing.countDown()
                    probe.join(1_000L)

                    assertTrue(staleSnapshots.isEmpty())
                    assertTrue(!service.isCurrent(first))
                    assertNull(service.commitIfLive(first) { it })
                    assertSame(
                        replacement,
                        service.commitIfLive(replacement.generation) { live -> live },
                    )
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun staleTeardownCancelsAndCurrentGoneGenerationRemainsEligible() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true, expectedConnections = 2).use { replacementServer ->
                val service = service()
                runBlocking {
                    val stale = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val current = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation

                    val staleDisconnect = runCatching { service.disconnect(stale) }.exceptionOrNull()
                    assertTrue(staleDisconnect is CancellationException)
                    assertSame(current, service.liveConnection.value?.generation)

                    val staleClose = runCatching { service.close(stale) }.exceptionOrNull()
                    assertTrue(staleClose is CancellationException)
                    assertSame(current, service.liveConnection.value?.generation)

                    service.disconnect(current)
                    assertNull(service.liveConnection.value)
                    assertTrue(service.isCurrent(current))
                    assertEquals("gone", service.commitIfCurrent(current) { "gone" })
                    assertNull(service.commitIfLive(current) { it })

                    service.disconnect(current)
                    assertNull(service.liveConnection.value)
                    assertTrue(service.isCurrent(current))

                    service.close(current)
                    assertNull(service.liveConnection.value)
                    assertTrue(
                        service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)) is
                            HtspConnectOutcome.Failed,
                    )
                }
            }
        }
    }

    @Test
    fun commitIfLiveSuppliesExactSnapshotAndRejectsStaleGeneration() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                val connected = service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected
                val live = requireNotNull(service.liveConnection.value)
                assertSame(connected.connection, live)
                assertSame(live, service.commitIfLive(live.generation) { snapshot -> snapshot })
                assertSame(
                    live.generation,
                    service.commitIfLive(live.generation) { snapshot -> snapshot.generation },
                )
                assertTrue(service.isCurrent(live.generation))
                assertSame(
                    live.generation,
                    service.commitIfCurrent(live.generation) { live.generation },
                )

                val foreign = HtspConnectionGeneration()
                assertTrue(!service.isCurrent(foreign))
                assertNull(service.commitIfCurrent(foreign) { "foreign" })
                assertNull(service.commitIfLive(foreign) { it })
                service.disconnect(live.generation)
                assertNull(service.commitIfLive(live.generation) { it })
                assertTrue(service.isCurrent(live.generation))
            }
        }
    }

    @Test
    fun cancelledConnectPropagatesWithoutPublishingTransportError() {
        FakeHtspServer(respondToHello = false).use { server ->
            val service = service()
            runBlocking {
                val observed = CopyOnWriteArrayList<HtspConnectionState>()
                val collector = launch {
                    service.state.collect { observed += it }
                }
                val connection = launch(Dispatchers.IO) {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 5_000,
                        soTimeoutMs = 50,
                    )
                }
                withTimeout(1_000L) {
                    service.state.first { it is HtspConnectionState.Connecting }
                }

                connection.cancelAndJoin()
                delay(50L)

                assertTrue(observed.none { it is HtspConnectionState.Error })
                assertTrue(service.state.value is HtspConnectionState.Disconnected)
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun failedCurrentTransportBecomesGoneWithoutWaitingForANewAttempt() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val attemptId = service.currentConnectionAttemptId()

                server.closeClientTransport()
                withTimeout(1_000L) {
                    while (service.liveConnection.value != null) {
                        delay(1L)
                    }
                }

                assertNull(service.liveConnection.value)
                assertEquals(attemptId, service.currentConnectionAttemptId())
            }
        }
    }
}
