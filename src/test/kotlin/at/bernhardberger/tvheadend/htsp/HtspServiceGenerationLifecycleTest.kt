package at.bernhardberger.tvheadend.htsp

import org.junit.jupiter.api.Assertions.assertFalse

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

                    assertFalse(service.disconnect(stale))
                    assertSame(current, service.currentLiveConnection?.generation)
                    assertEquals(
                        replacementServer.port,
                        (service.connectionState.value as HtspConnectionState.Connected).port,
                    )

                    service.disconnect(current)
                    assertNull(service.currentLiveConnection)

                    service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    service.disconnect()
                    assertNull(service.currentLiveConnection)
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
                        service.disconnect(stale)
                    }
                    withTimeout(1_000L) { teardownAdmitted.await() }

                    val replacement = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val pendingRequest = async(Dispatchers.IO) {
                        service.request(
                            method = "replacementRequest",
                            policy = HtspReplyPolicy(timeoutMs = 5_000L, retireOnTimeout = false),
                        )
                    }
                    assertTrue(
                        replacementServer.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS),
                    )

                    resumeTeardown.complete(Unit)
                    val failure = withTimeout(1_000L) { disconnect.await() }

                    assertFalse(failure)
                    assertSame(replacement, service.currentLiveConnection?.generation)
                    assertEquals(
                        replacementServer.port,
                        (service.connectionState.value as HtspConnectionState.Connected).port,
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
                                event.message.channel.channelId == 44L
                        }
                    }
                    replacementServer.sendServerMessage("channelAdd", channelFixture(44L))
                    assertTrue(
                        withTimeout(1_000L) { replacementEvent.await() } is
                            HtspTransportEvent.ServerMessage,
                    )

                    service.disconnect()
                    assertNull(service.currentLiveConnection)
                    assertTrue(service.connectionState.value is HtspConnectionState.Disconnected)
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

                    assertFalse(service.close(stale))
                    assertSame(current, service.currentLiveConnection?.generation)
                    assertTrue(service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)) is HtspConnectOutcome.Connected)

                    assertTrue(service.close(current))
                    assertEquals(HtspConnectionState.Closed, service.connectionState.value)
                    assertEquals(
                        HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.CLOSED)),
                        service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)),
                    )
                    assertFalse(service.disconnect())
                    assertFalse(service.close())
                    assertEquals(HtspConnectionState.Closed, service.connectionState.value)
                }
            }
        }

        val ownerGlobal = service()
        runBlocking {
            assertTrue(ownerGlobal.close())
            assertEquals(HtspConnectionState.Closed, ownerGlobal.connectionState.value)
            assertEquals(
                HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.CLOSED)),
                ownerGlobal.connect(HtspEndpoint("127.0.0.1", 9982)),
            )
            assertSame(HtspResult.TransportUnavailable, ownerGlobal.getProfiles())
            assertFalse(ownerGlobal.disconnect())
            assertFalse(ownerGlobal.close())
            assertEquals(HtspConnectionState.Closed, ownerGlobal.connectionState.value)
        }
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
                    val liveSnapshot = service.currentLiveConnection
                    assertSame(first, liveSnapshot)
                    assertTrue(service.isCurrent(generation))
                    assertTrue(service.isCurrent(generation))

                    firstServer.closeClientTransport()
                    withTimeout(1_000L) {
                        service.connectionState.first { it !is HtspConnectionState.Connected }
                    }

                    assertNull(service.currentLiveConnection)
                    assertTrue(service.isCurrent(generation))
                    assertTrue(service.isCurrent(generation))
                    assertNull(service.currentLiveConnection)

                    val replacement = (service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected).connection
                    assertTrue(!service.isCurrent(generation))
                    assertTrue(!service.isCurrent(generation))
                    assertTrue(service.isCurrent(replacement.generation))
                    assertSame(
                        replacement,
                        service.currentLiveConnection,
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
                assertNull(service.currentLiveConnection)
                assertTrue(service.isCurrent(generation))
                assertTrue(service.isCurrent(generation))
                assertNull(service.currentLiveConnection)

                val replacement = (service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected).connection
                assertTrue(!service.isCurrent(generation))
                assertTrue(!service.isCurrent(generation))
                assertNotSame(generation, replacement.generation)
                assertTrue(service.isCurrent(replacement.generation))
                assertSame(
                    replacement,
                    service.currentLiveConnection,
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
                assertNull(service.currentLiveConnection)
                assertTrue(!service.isCurrent(first))
                assertTrue(!service.isCurrent(first))
                assertNull(service.currentLiveConnection)
                assertSame(HtspResult.TransportUnavailable, service.getProfiles(expectedGeneration = first))
                assertSame(HtspResult.TransportUnavailable, service.getProfiles())
                assertFalse(service.disconnect(first))
                assertFalse(service.close(first))
                assertNull(service.currentLiveConnection)

                service.disconnect()
                assertNull(service.currentLiveConnection)
                assertTrue(!service.isCurrent(first))

                val later = (service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected).connection
                assertTrue(!service.isCurrent(first))
                assertNotSame(first, later.generation)
                assertTrue(service.isCurrent(later.generation))
                assertSame(later, service.currentLiveConnection)
                service.disconnect()
            }
        }
    }

    @Test
    fun concurrentLossAndReplacementExposeOnlyOwnedSnapshots() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = (service.connect(
                        HtspEndpoint("127.0.0.1", firstServer.port),
                    ) as HtspConnectOutcome.Connected).connection.generation
                    val firstConnection = service.currentLiveConnection
                    val snapshots = CopyOnWriteArrayList<HtspLiveConnection>()
                    val stopProbing = CountDownLatch(1)
                    val probeStarted = CountDownLatch(1)
                    val probe = thread(name = "generation-live-probe") {
                        probeStarted.countDown()
                        while (!stopProbing.await(0, TimeUnit.MILLISECONDS)) {
                            (service.connectionState.value as? HtspConnectionState.Connected)?.connection?.let { live ->
                                snapshots.addIfAbsent(live)
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

                    assertTrue(snapshots.all { it === firstConnection || it === replacement })
                    assertTrue(!service.isCurrent(first))
                    assertTrue(service.currentLiveConnection?.generation !== first)
                    assertSame(
                        replacement,
                        service.currentLiveConnection,
                    )
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun staleTeardownReturnsFalseAndCurrentGoneGenerationRemainsEligible() {
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

                    assertFalse(service.disconnect(stale))
                    assertSame(current, service.currentLiveConnection?.generation)

                    assertFalse(service.close(stale))
                    assertSame(current, service.currentLiveConnection?.generation)

                    assertTrue(service.disconnect(current))
                    assertNull(service.currentLiveConnection)
                    assertTrue(service.isCurrent(current))
                    assertTrue(service.isCurrent(current))
                    assertNull(service.currentLiveConnection)

                    assertFalse(service.disconnect(current))
                    assertNull(service.currentLiveConnection)
                    assertTrue(service.isCurrent(current))

                    assertTrue(service.close(current))
                    assertEquals(HtspConnectionState.Closed, service.connectionState.value)
                    assertTrue(
                        service.connect(HtspEndpoint("127.0.0.1", replacementServer.port)) is
                            HtspConnectOutcome.Failed,
                    )
                }
            }
        }
    }

    @Test
    fun transportLossLeavesCurrentGenerationEligibleForTerminalClose() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                val endpoint = HtspEndpoint("127.0.0.1", server.port)
                val generation = (service.connect(endpoint) as HtspConnectOutcome.Connected).connection.generation
                server.closeClientTransport()
                withTimeout(1_000L) { service.connectionState.first { it !is HtspConnectionState.Connected } }

                assertTrue(service.isCurrent(generation))
                assertTrue(service.close(generation))
                assertEquals(HtspConnectionState.Closed, service.connectionState.value)
                assertFalse(service.close(generation))
                val failed = service.connect(endpoint) as HtspConnectOutcome.Failed
                assertEquals(HtspTransportFailureKind.CLOSED, failed.failure.kind)
                assertEquals(HtspConnectionState.Closed, service.connectionState.value)
            }
        }
    }

    @Test
    fun connectedSuppliesExactSnapshotAndCurrencyRejectsForeignGeneration() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                val connected = service.connect(
                    HtspEndpoint("127.0.0.1", server.port),
                ) as HtspConnectOutcome.Connected
                val live = requireNotNull(service.currentLiveConnection)
                assertSame(connected.connection, live)
                assertSame(live, service.currentLiveConnection)
                assertSame(
                    live.generation,
                    service.currentLiveConnection?.generation,
                )
                assertTrue(service.isCurrent(live.generation))

                val foreign = HtspConnectionGeneration()
                assertTrue(!service.isCurrent(foreign))
                assertTrue(service.currentLiveConnection?.generation !== foreign)
                service.disconnect(live.generation)
                assertNull(service.currentLiveConnection)
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
                    service.connectionState.collect { observed += it }
                }
                val connection = launch(Dispatchers.IO) {
                    service.establish(HtspConnectionParameters(
                        HtspEndpoint(host = "127.0.0.1", port = server.port),
                        HtspConnectOptions(connectTimeoutMs = 1_000, responseTimeoutMs = 5_000, socketReadTimeoutMs = 50),
                        HtspClientIdentity.Default,
                    ))
                }
                withTimeout(1_000L) {
                    service.connectionState.first { it is HtspConnectionState.Connecting }
                }

                connection.cancelAndJoin()
                delay(50L)

                assertTrue(observed.none { it is HtspConnectionState.Error })
                assertTrue(service.connectionState.value is HtspConnectionState.Disconnected)
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun failedCurrentTransportBecomesGoneWithoutWaitingForANewAttempt() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.establish(HtspConnectionParameters(
                    HtspEndpoint(host = "127.0.0.1", port = server.port),
                    HtspConnectOptions(connectTimeoutMs = 1_000, responseTimeoutMs = 1_000, socketReadTimeoutMs = 50),
                    HtspClientIdentity.Default,
                ))
                val attemptId = service.currentConnectionAttemptId()

                server.closeClientTransport()
                withTimeout(1_000L) {
                    while (service.currentLiveConnection != null) {
                        delay(1L)
                    }
                }

                assertNull(service.currentLiveConnection)
                assertEquals(attemptId, service.currentConnectionAttemptId())
            }
        }
    }
}
