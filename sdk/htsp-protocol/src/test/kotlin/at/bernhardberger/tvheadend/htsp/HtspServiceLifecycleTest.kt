package at.bernhardberger.tvheadend.htsp
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class HtspServiceLifecycleTest {

    @Test
    fun typedPlaybackMessagesPublishExactlyOnceInSharedReaderOrder() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val typedEvents = CopyOnWriteArrayList<HtspTransportEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { typedEvents += it }
                }

                server.sendServerMessage("subscriptionStatus", mapOf("subscriptionId" to 1L))
                server.sendServerMessage(
                    "descrambleInfo",
                    mapOf(
                        "subscriptionId" to 1L,
                        "pid" to 2L,
                        "caid" to 3L,
                        "provid" to 4L,
                        "ecmtime" to 5L,
                        "hops" to 6L,
                    ),
                )
                server.sendServerMessage(
                    "muxpkt",
                    mapOf(
                        "subscriptionId" to 1L,
                        "frametype" to 1L,
                        "stream" to 0L,
                        "duration" to 40L,
                        "payload" to byteArrayOf(1, 2, 3),
                    ),
                )

                withTimeout(1_000L) {
                    while (typedEvents.filterIsInstance<HtspTransportEvent.ServerMessage>().size < 3) {
                        delay(1L)
                    }
                }
                val messages = typedEvents.filterIsInstance<HtspTransportEvent.ServerMessage>()
                assertEquals(
                    listOf(
                        HtspSubscriptionStatusMessage::class,
                        HtspDescrambleInfoMessage::class,
                        HtspMuxPacketMessage::class,
                    ),
                    messages.map { event -> event.message::class },
                )
                assertEquals(3, messages.map { event -> event.messageSequence }.distinct().size)
                assertTrue(messages.zipWithNext().all { (first, second) ->
                    first.messageSequence < second.messageSequence
                })
                collector.cancelAndJoin()
                service.disconnect()
            }
        }
    }

    @Test
    fun blockedTypedMuxCollectorDoesNotBlockRpcReplyProgress() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val firstTypedMuxReceived = CompletableDeferred<Unit>()
                val releaseTypedCollector = CompletableDeferred<Unit>()
                val typedCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { event ->
                        if (event is HtspTransportEvent.ServerMessage &&
                            event.message is HtspMuxPacketMessage
                        ) {
                            firstTypedMuxReceived.complete(Unit)
                            releaseTypedCollector.await()
                        }
                    }
                }

                server.sendServerMessage("muxpkt", muxPacketFields(payloadByte = 1.toByte()))
                withTimeout(1_000L) { firstTypedMuxReceived.await() }
                server.sendServerMessage("muxpkt", muxPacketFields(payloadByte = 2.toByte()))
                val request = async(Dispatchers.IO) {
                    service.request(
                        method = "blockedCollectorProbe",
                        timeoutMs = 1_000L,
                        disconnectOnTimeout = false,
                    )
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()

                assertEquals(
                    "blockedCollectorProbe",
                    withTimeout(1_000L) { request.await() }.method,
                )

                releaseTypedCollector.complete(Unit)
                typedCollector.cancelAndJoin()
                service.disconnect()
            }
        }
    }

    @Test
    fun replacementAdmissionRejectsDecodedOldTypedEventAtPublicationCommit() {
        val oldEventDecoded = CountDownLatch(1)
        val releaseOldPublication = CountDownLatch(1)
        val replacementAdmitted = CompletableDeferred<Unit>()
        val admissions = AtomicInteger()
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service(
                    afterConnectionAdmission = {
                        if (admissions.incrementAndGet() == 2) replacementAdmitted.complete(Unit)
                    },
                    beforeTypedEventPublication = { event ->
                        val subscriptionId =
                            (event.message as? HtspSubscriptionStatusMessage)?.subscriptionId
                        if (subscriptionId == 61L) {
                            oldEventDecoded.countDown()
                            assertTrue(releaseOldPublication.await(1, TimeUnit.SECONDS))
                        }
                    },
                )
                runBlocking {
                    val events = CopyOnWriteArrayList<HtspTransportEvent.ServerMessage>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        service.events.collect { event ->
                            if (event is HtspTransportEvent.ServerMessage) events += event
                        }
                    }
                    val old = service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                        as HtspConnectOutcome.Connected
                    firstServer.sendServerMessage(
                        "subscriptionStatus",
                        mapOf("subscriptionId" to 61L),
                    )
                    assertTrue(oldEventDecoded.await(1, TimeUnit.SECONDS))

                    val replacement = async(Dispatchers.IO) {
                        service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    }
                    withTimeout(1_000L) { replacementAdmitted.await() }
                    releaseOldPublication.countDown()
                    val current = withTimeout(1_000L) { replacement.await() }
                        as HtspConnectOutcome.Connected
                    replacementServer.sendServerMessage(
                        "subscriptionStatus",
                        mapOf("subscriptionId" to 62L),
                    )
                    withTimeout(1_000L) {
                        while (events.none { event ->
                                (event.message as? HtspSubscriptionStatusMessage)
                                    ?.subscriptionId == 62L
                            }) {
                            delay(1L)
                        }
                    }

                    assertTrue(events.none { event -> event.generation === old.connection.generation })
                    assertTrue(events.any { event -> event.generation === current.connection.generation })
                    collector.cancelAndJoin()
                    service.disconnect(current.connection.generation)
                }
            }
        }
    }

    @Test
    fun typedServerEventRejectsNonPositiveReaderSequence() {
        val generation = HtspConnectionGeneration.create()
        val message = HtspSubscriptionStatusMessage(
            subscriptionId = 1L,
            status = null,
            subscriptionError = null,
        )

        assertTrue(
            runCatching {
                HtspTransportEvent.ServerMessage(message, generation, messageSequence = 0L)
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun typedReaderSequenceResetsForReplacementAndOldReaderCannotPublish() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val events = CopyOnWriteArrayList<HtspTransportEvent.ServerMessage>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        service.events.collect { event ->
                            if (event is HtspTransportEvent.ServerMessage) events += event
                        }
                    }
                    val first = service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                        as HtspConnectOutcome.Connected
                    firstServer.sendServerMessage(
                        "subscriptionStatus",
                        mapOf("subscriptionId" to 51L),
                    )
                    withTimeout(1_000L) { while (events.size < 1) delay(1L) }

                    val replacement = service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected
                    runCatching {
                        firstServer.sendServerMessage(
                            "subscriptionStatus",
                            mapOf("subscriptionId" to 52L),
                        )
                    }
                    replacementServer.sendServerMessage(
                        "subscriptionStatus",
                        mapOf("subscriptionId" to 53L),
                    )
                    withTimeout(1_000L) { while (events.size < 2) delay(1L) }
                    delay(50L)

                    assertSame(first.connection.generation, events[0].generation)
                    assertSame(replacement.connection.generation, events[1].generation)
                    assertEquals(events[0].messageSequence, events[1].messageSequence)
                    assertTrue(events[0].messageSequence > 0L)
                    assertTrue(events.none { event ->
                        (event.message as? HtspSubscriptionStatusMessage)?.subscriptionId == 52L
                    })
                    collector.cancelAndJoin()
                    service.disconnect(replacement.connection.generation)
                }
            }
        }
    }

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
                    assertEquals(replacementServer.port, (service.state.value as ConnectionState.Connected).port)

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
                        (service.state.value as ConnectionState.Connected).port,
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
                    assertTrue(service.state.value is ConnectionState.Disconnected)
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

                val foreign = HtspConnectionGeneration.create()
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
                val observed = CopyOnWriteArrayList<ConnectionState>()
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
                    service.state.first { it is ConnectionState.Connecting }
                }

                connection.cancelAndJoin()
                delay(50L)

                assertTrue(observed.none { it is ConnectionState.Error })
                assertTrue(service.state.value is ConnectionState.Disconnected)
                collector.cancelAndJoin()
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

    @Test
    fun supersededReaderCannotPublishOldServerMessage() {
        val releaseOldAuthentication = CountDownLatch(1)
        FakeHtspServer(
            respondToHello = true,
            authenticateResponseGate = releaseOldAuthentication,
        ).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val events = CopyOnWriteArrayList<HtspTransportEvent>()
                    val collector = launch {
                        service.events.collect { events += it }
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
                    assertTrue(firstServer.authenticateRequestReceived.await(1, TimeUnit.SECONDS))
                    val firstAttempt = service.currentConnectionAttemptId()

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
                    withTimeout(1_000L) {
                        while (service.currentConnectionAttemptId() == firstAttempt) delay(1L)
                    }

                    firstServer.sendServerMessage("channelAdd", mapOf("channelId" to 55L))
                    delay(50L)
                    assertTrue(
                        events.none { event ->
                            event is HtspTransportEvent.ServerMessage &&
                                event.message is HtspChannelAddMessage &&
                                event.message.channelId == 55L
                        }
                    )

                    first.cancel()
                    releaseOldAuthentication.countDown()
                    first.join()
                    replacement.await()
                    service.disconnect()
                    collector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun staleSubscriptionCommandCannotUseReplacementTransport() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    service.connect(
                        host = "127.0.0.1",
                        port = firstServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                    val staleAttempt = service.currentConnectionAttemptId()
                    service.connect(
                        host = "127.0.0.1",
                        port = replacementServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                        forceReconnect = true,
                    )

                    val failure = runCatching {
                        service.requestForConnectionAttempt(
                            expectedConnectionAttemptId = staleAttempt,
                            method = "subscriptionSpeed",
                            fields = mapOf("subscriptionId" to 1, "speed" to 0),
                        )
                    }.exceptionOrNull()

                    assertTrue(failure is kotlinx.coroutines.CancellationException)
                    assertEquals(
                        listOf("hello", "authenticate"),
                        replacementServer.handshakeMethods,
                    )
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun connectFailureCompletesWhenServerDoesNotAnswerHello() {
        FakeHtspServer(respondToHello = false).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Throwable?> {
                    runBlocking {
                        runCatching {
                            service().connect(
                                host = "127.0.0.1",
                                port = server.port,
                                connectTimeoutMs = 1_000,
                                responseTimeoutMs = 100,
                                soTimeoutMs = 50,
                            )
                        }.exceptionOrNull()
                    }
                }.get(2, TimeUnit.SECONDS)

                assertNotNull(result)
            } finally {
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun disconnectCompletesWhileReaderIsIdle() {
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
            }

            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Boolean> {
                    runBlocking {
                        service.disconnect()
                        true
                    }
                }.get(2, TimeUnit.SECONDS)

                assertTrue(result)
            } finally {
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun optionalRequestTimeoutLeavesSharedConnectionOpen() {
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

                val failure = runCatching {
                    service.request(
                        method = "getEvents",
                        timeoutMs = 100,
                        disconnectOnTimeout = false,
                    )
                }.exceptionOrNull()

                assertNotNull(failure)
                assertTrue(failure is HtspRequestTimeoutException)
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun lateReplyAfterOptionalRequestTimeoutIsNotPublishedAsControlEvent() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val failure = runCatching {
                    service.request(
                        method = "getEvents",
                        timeoutMs = 100,
                        disconnectOnTimeout = false,
                    )
                }.exceptionOrNull()
                assertTrue(failure is HtspRequestTimeoutException)
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                val unexpectedTypedEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(250L) { service.events.first() }
                }
                server.replyToCapturedPostHandshakeRequest()

                assertNull(unexpectedTypedEvent.await())
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun helloWithoutServerVersionIsRejectedBeforeAuthentication() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf("challenge" to ByteArray(32)),
        ).use { server ->
            val service = service()

            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertNotNull(failure)
            assertEquals(listOf("hello"), server.handshakeMethods)
        }
    }

    @Test
    fun helloWithMalformedChallengeIsRejectedBeforeAuthentication() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(31),
            ),
        ).use { server ->
            val service = service()

            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertNotNull(failure)
            assertEquals(listOf("hello"), server.handshakeMethods)
        }
    }

    @Test
    fun authenticationErrorReplyDoesNotEstablishAConnection() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("error" to "server-provided detail"),
        ).use { server ->
            val service = service()

            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        username = "viewer",
                        password = "secret",
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertTrue(requireNotNull(failure).message.orEmpty().contains("authentication failed"))
            assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
        }
    }

    @Test
    fun credentialAuthenticationUsesExactPasswordUtf8BytesThenSessionChallenge() {
        val sessionChallenge = ByteArray(32) { index -> index.toByte() }
        val password = "  sëcret\t"
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to sessionChallenge,
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "viewer",
                    password = password,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val hello = requireNotNull(server.handshakeFields["hello"])
                assertEquals(43L, hello["htspversion"])
                assertEquals("Kotlin HTSP client", hello["clientname"])
                assertTrue(!hello.containsKey("clientversion"))
                val auth = requireNotNull(server.handshakeFields["authenticate"])
                assertEquals("viewer", auth["username"])
                assertArrayEquals(
                    MessageDigest.getInstance("SHA-1").digest(
                        password.toByteArray() + sessionChallenge,
                    ),
                    auth["digest"] as ByteArray,
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun callerTimeoutIsNotConvertedToRequestTimeout() {
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

                val failure = runCatching {
                    withTimeout(50L) {
                        service.request(
                            method = "getEvents",
                            timeoutMs = 500L,
                            disconnectOnTimeout = false,
                        )
                    }
                }.exceptionOrNull()

                assertTrue(failure is TimeoutCancellationException)
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun missingInitialSyncMarkerIsTransportTimeoutNotCallerCancellation() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val sync = async(Dispatchers.IO) {
                    try {
                        service.enableAsyncMetadataAndWaitInitialSync(timeoutMs = 250L)
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()

                val failure = sync.await()
                assertTrue(failure is SocketTimeoutException)
                assertTrue(failure !is TimeoutCancellationException)
                withTimeout(1_000L) {
                    service.state.first { it is ConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun callerTimeoutDuringInitialSyncStillPropagatesWithoutClosingTransport() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val sync = async(Dispatchers.IO) {
                    try {
                        withTimeout(250L) {
                            service.enableAsyncMetadataAndWaitInitialSync(timeoutMs = 5_000L)
                        }
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()

                assertTrue(sync.await() is TimeoutCancellationException)
                assertTrue(service.state.value is ConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun cancelledWrittenRequestLeavesAttemptLiveUntilTransportDisconnects() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
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
                val request = launch(Dispatchers.IO) {
                    service.requestForConnectionAttempt(
                        expectedConnectionAttemptId = attemptId,
                        method = "subscribe",
                        fields = mapOf("subscriptionId" to 42),
                        timeoutMs = 5_000L,
                    )
                }

                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                request.cancelAndJoin()

                assertNotNull(service.liveConnection.value)
                assertEquals(attemptId, service.currentConnectionAttemptId())
                service.disconnect()
                assertNull(service.liveConnection.value)
                assertEquals(attemptId, service.currentConnectionAttemptId())
            }
        }
    }

    @Test
    fun subscriptionFilterRequestPreservesCommandFieldOrderThroughCodec() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
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
                val update = async(Dispatchers.IO) {
                    service.requestForConnectionAttempt(
                        expectedConnectionAttemptId = attemptId,
                        method = "subscriptionFilterStream",
                        fields = mapOf(
                            "subscriptionId" to 23,
                            "enable" to listOf(4, 1, 4),
                            "disable" to listOf(7, 2),
                        ),
                    )
                }

                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                val request = server.capturedPostHandshakeRequest()
                assertEquals("subscriptionFilterStream", request.method)
                assertNotNull(request.seq)
                assertEquals(
                    listOf("method", "seq", "subscriptionId", "enable", "disable"),
                    request.fields.keys.toList(),
                )
                assertEquals(23L, request.fields["subscriptionId"])
                assertEquals(listOf(4L, 1L, 4L), request.fields["enable"])
                assertEquals(listOf(7L, 2L), request.fields["disable"])

                server.replyToCapturedPostHandshakeRequest()
                assertEquals("subscriptionFilterStream", update.await().method)
                service.disconnect()
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

    @Test
    fun anonymousConnectStillAuthenticatesAndReadsDvrRight() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("dvr" to 1, "streaming" to 1),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val state = service.state.value as ConnectionState.Connected
                assertEquals(true, state.dvrAccess)
                assertEquals(listOf("hello", "authenticate"), server.handshakeMethods)
                // No credentials configured: authenticate must stay bare so the server
                // keeps the address-based anonymous rights.
                assertNull(server.handshakeFields["authenticate"]?.get("username"))
                service.disconnect()
            }
        }
    }

    @Test
    fun executingHelloRecapturesCurrentGenerationWithoutDisturbingAccessOrFutureVersionGates() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("dvr" to 1, "streaming" to 1),
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf(
                "htspversion" to 44L,
                "challenge" to ByteArray(32) { index -> (index + 1).toByte() },
                "servername" to "recaptured-server",
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation

                assertEquals(
                    HtspResult.Ok(
                        HelloResponse(
                            htspVersion = 44L,
                            serverName = "recaptured-server",
                            serverVersion = null,
                            challenge = HtspBinary(ByteArray(32) { index -> (index + 1).toByte() }),
                            webRoot = null,
                            language = null,
                            serverCapabilities = null,
                            apiVersion = null,
                        ),
                    ),
                    service.execute(
                        request = HelloRequest(
                            htspVersion = 2L,
                            clientName = "recapture-client",
                        ),
                        timeoutMs = 1_000L,
                        expectedGeneration = generation,
                    ),
                )

                assertEquals(
                    mapOf("htspversion" to 2L, "clientname" to "recapture-client"),
                    server.capturedPostHandshakeRequest().fields.filterKeys { key ->
                        key != "method" && key != "seq"
                    },
                )
                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertEquals(2, live.protocolVersion)
                assertEquals("recaptured-server", live.serverFacts.serverName)
                assertEquals(true, live.serverFacts.dvr)
                assertEquals(true, live.dvrAccess)
                val state = service.state.value as ConnectionState.Connected
                assertEquals(2, state.htspVersion)
                assertEquals(true, state.dvrAccess)
                assertSame(HtspResult.NotSupported, service.getSysTime(expectedGeneration = generation))
                service.disconnect()
            }
        }
    }

    @Test
    fun executingAuthenticateRecapturesOnlyAccessFactsOnTheCurrentGeneration() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servername" to "stable-server",
            ),
            authFields = mapOf("admin" to 0, "dvr" to 0, "streaming" to 1),
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf(
                "noaccess" to 0L,
                "admin" to 1L,
                "dvr" to 1L,
                "streaming" to 1L,
                "limitdvr" to 7L,
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val before = requireNotNull(service.liveConnection.value)

                assertEquals(
                    HtspResult.Ok(
                        AuthenticateResponse(
                            noAccess = false,
                            admin = true,
                            streaming = true,
                            dvr = true,
                            failedDvr = null,
                            anonymous = null,
                            limitAll = null,
                            limitDvr = 7L,
                            limitStreaming = null,
                            uiLevel = null,
                            uiLanguage = null,
                        ),
                    ),
                    service.execute(
                        request = AuthenticateRequest(),
                        timeoutMs = 1_000L,
                        expectedGeneration = before.generation,
                    ),
                )

                assertEquals(
                    emptyMap<String, Any?>(),
                    server.capturedPostHandshakeRequest().fields.filterKeys { key ->
                        key != "method" && key != "seq"
                    },
                )
                val after = requireNotNull(service.liveConnection.value)
                assertSame(before.generation, after.generation)
                assertEquals(before.protocolVersion, after.protocolVersion)
                assertEquals("stable-server", after.serverFacts.serverName)
                assertEquals(true, after.serverFacts.admin)
                assertEquals(true, after.serverFacts.dvr)
                assertEquals(7, after.serverFacts.limitDvr)
                assertEquals(true, after.dvrAccess)
                assertEquals(true, (service.state.value as ConnectionState.Connected).dvrAccess)
                service.disconnect()
            }
        }
    }

    @Test
    fun deniedDirectAuthenticateClearsStaleAccessWithoutClearingHelloFacts() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servername" to "preserved-server",
            ),
            authFields = mapOf("admin" to 1, "dvr" to 1, "streaming" to 1),
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf("noaccess" to 1L),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val before = requireNotNull(service.liveConnection.value)

                assertSame(
                    HtspResult.AccessDenied,
                    service.authenticate(1_000L, before.generation),
                )

                val after = requireNotNull(service.liveConnection.value)
                assertSame(before.generation, after.generation)
                assertEquals(before.protocolVersion, after.protocolVersion)
                assertEquals("preserved-server", after.serverFacts.serverName)
                assertNull(after.serverFacts.admin)
                assertNull(after.serverFacts.streaming)
                assertNull(after.serverFacts.dvr)
                assertNull(after.dvrAccess)
                assertNull((service.state.value as ConnectionState.Connected).dvrAccess)
                service.disconnect()
            }
        }
    }

    @Test
    fun directHelloDoesNotCancelAnUnrelatedInFlightRequest() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            additionalPostHandshakeReplyFields = listOf(
                mapOf(
                    "htspversion" to 44L,
                    "challenge" to ByteArray(32),
                ),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val ordinary = async(Dispatchers.IO) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                assertTrue(
                    service.hello(43L, "parallel-client", 1_000L, generation) is HtspResult.Ok,
                )
                assertTrue(!ordinary.isCompleted)
                server.replyToCapturedPostHandshakeRequest()
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                assertSame(generation, service.liveConnection.value?.generation)
                service.disconnect()
            }
        }
    }

    @Test
    fun directAuthenticateDoesNotCancelAnUnrelatedInFlightRequest() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            additionalPostHandshakeReplyFields = listOf(
                mapOf(
                    "admin" to 0L,
                    "streaming" to 1L,
                    "dvr" to 1L,
                ),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val ordinary = async(Dispatchers.IO) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                assertTrue(
                    service.authenticate(timeoutMs = 1_000L, expectedGeneration = generation) is
                        HtspResult.Ok,
                )
                assertTrue(!ordinary.isCompleted)
                server.replyToCapturedPostHandshakeRequest()
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                assertSame(generation, service.liveConnection.value?.generation)
                service.disconnect()
            }
        }
    }

    @Test
    fun overlappingDirectHelloCommitsInDispatchOrderWithoutBlockingOrdinaryRequests() {
        val firstRecaptureReached = CompletableDeferred<Unit>()
        val resumeFirstRecapture = CompletableDeferred<Unit>()
        val helloRecaptures = AtomicInteger()
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf(
                    "htspversion" to 2L,
                    "challenge" to ByteArray(32) { 1 },
                    "servername" to "first-direct-hello",
                ),
                null,
                mapOf(
                    "htspversion" to 3L,
                    "challenge" to ByteArray(32) { 2 },
                    "servername" to "second-direct-hello",
                ),
            ),
        ).use { server ->
            val service = service(
                beforeTypedRecapture = { request ->
                    if (request is HelloRequest && helloRecaptures.incrementAndGet() == 1) {
                        firstRecaptureReached.complete(Unit)
                        resumeFirstRecapture.await()
                    }
                },
            )
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val first = async(Dispatchers.IO) {
                    service.hello(2L, "first-client", 5_000L, generation)
                }
                withTimeout(1_000L) { firstRecaptureReached.await() }

                val ordinary = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(2, 1_000L))
                assertEquals("getProfiles", server.postHandshakeRequest(1).method)
                val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.hello(3L, "second-client", 5_000L, generation)
                }
                assertTrue(!server.awaitPostHandshakeRequestCount(3, 150L))
                assertTrue(!ordinary.isCompleted)
                assertTrue(!second.isCompleted)

                resumeFirstRecapture.complete(Unit)
                assertTrue(first.await() is HtspResult.Ok)
                assertTrue(server.awaitPostHandshakeRequestCount(3, 1_000L))
                assertEquals(
                    listOf("hello", "getProfiles", "hello"),
                    server.postHandshakeMethods(),
                )
                assertTrue(second.await() is HtspResult.Ok)

                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertEquals(3, live.protocolVersion)
                assertEquals("second-direct-hello", live.serverFacts.serverName)
                assertTrue(!ordinary.isCompleted)
                server.replyToPostHandshakeRequest(1)
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun overlappingDirectAuthenticateCommitsInDispatchOrderWithoutBlockingOrdinaryRequests() {
        val firstRecaptureReached = CompletableDeferred<Unit>()
        val resumeFirstRecapture = CompletableDeferred<Unit>()
        val authenticateRecaptures = AtomicInteger()
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf("admin" to 1L, "streaming" to 1L, "dvr" to 0L),
                null,
                mapOf("admin" to 0L, "streaming" to 1L, "dvr" to 1L, "limitdvr" to 9L),
            ),
        ).use { server ->
            val service = service(
                beforeTypedRecapture = { request ->
                    if (
                        request is AuthenticateRequest &&
                        authenticateRecaptures.incrementAndGet() == 1
                    ) {
                        firstRecaptureReached.complete(Unit)
                        resumeFirstRecapture.await()
                    }
                },
            )
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val first = async(Dispatchers.IO) {
                    service.authenticate(5_000L, generation)
                }
                withTimeout(1_000L) { firstRecaptureReached.await() }

                val ordinary = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.getProfiles(timeoutMs = 5_000L, expectedGeneration = generation)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(2, 1_000L))
                assertEquals("getProfiles", server.postHandshakeRequest(1).method)
                val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.authenticate(5_000L, generation)
                }
                assertTrue(!server.awaitPostHandshakeRequestCount(3, 150L))
                assertTrue(!ordinary.isCompleted)
                assertTrue(!second.isCompleted)

                resumeFirstRecapture.complete(Unit)
                assertTrue(first.await() is HtspResult.Ok)
                assertTrue(server.awaitPostHandshakeRequestCount(3, 1_000L))
                assertEquals(
                    listOf("authenticate", "getProfiles", "authenticate"),
                    server.postHandshakeMethods(),
                )
                assertTrue(second.await() is HtspResult.Ok)

                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertEquals(false, live.serverFacts.admin)
                assertEquals(true, live.serverFacts.dvr)
                assertEquals(9, live.serverFacts.limitDvr)
                assertEquals(true, live.dvrAccess)
                assertTrue(!ordinary.isCompleted)
                server.replyToPostHandshakeRequest(1)
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    withTimeout(1_000L) { ordinary.await() },
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun cancelledHandshakeWaitingForSerializationDoesNotRetireItsGeneration() {
        val firstRecaptureReached = CompletableDeferred<Unit>()
        val resumeFirstRecapture = CompletableDeferred<Unit>()
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf("htspversion" to 44L, "challenge" to ByteArray(32)),
                emptyMap(),
            ),
        ).use { server ->
            val service = service(
                beforeTypedRecapture = { request ->
                    if (request is HelloRequest && !firstRecaptureReached.isCompleted) {
                        firstRecaptureReached.complete(Unit)
                        resumeFirstRecapture.await()
                    }
                },
            )
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val first = async(Dispatchers.IO) {
                    service.hello(44L, "first-client", 5_000L, generation)
                }
                withTimeout(1_000L) { firstRecaptureReached.await() }
                val waiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    service.authenticate(5_000L, generation)
                }

                waiting.cancel()
                val cancellation = runCatching { waiting.await() }.exceptionOrNull()
                assertTrue(cancellation is CancellationException)
                assertTrue(!server.awaitPostHandshakeRequestCount(2, 150L))
                assertTrue(service.isCurrent(generation))

                resumeFirstRecapture.complete(Unit)
                assertTrue(first.await() is HtspResult.Ok)
                assertEquals(
                    HtspResult.Ok(GetProfilesResponse(null)),
                    service.getProfiles(1_000L, generation),
                )
                assertTrue(service.isCurrent(generation))
                service.disconnect()
            }
        }
    }

    @Test
    fun timedOutWrittenDirectHelloRetiresOnlyItsCapturedGeneration() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation

                assertSame(HtspResult.Timeout, service.hello(44L, "timeout-client", 100L, generation))
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                assertTrue(service.isCurrent(generation))
                assertNull(service.liveConnection.value)
                assertNull(service.commitIfLive(generation) { it })
                assertSame(HtspResult.TransportUnavailable, service.getProfiles())
                assertEquals(1, server.postHandshakeMethods().size)
                withTimeout(1_000L) {
                    service.state.first { state -> state is ConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun cancelledWrittenDirectHelloRetiresOnlyItsCapturedGeneration() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation
                val observedCancellation = CompletableDeferred<Throwable>()
                val call = launch(Dispatchers.IO) {
                    try {
                        service.hello(44L, "cancelled-client", 5_000L, generation)
                    } catch (failure: Throwable) {
                        observedCancellation.complete(failure)
                        throw failure
                    }
                }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))

                call.cancel()
                call.join()
                assertTrue(withTimeout(1_000L) { observedCancellation.await() } is CancellationException)
                assertTrue(service.isCurrent(generation))
                assertNull(service.liveConnection.value)
                assertNull(service.commitIfLive(generation) { it })
                assertSame(HtspResult.TransportUnavailable, service.getProfiles())
                assertEquals(1, server.postHandshakeMethods().size)
                withTimeout(1_000L) {
                    service.state.first { state -> state is ConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun malformedWrittenDirectHelloRetiresGenerationInsteadOfKeepingStaleChallenge() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf("htspversion" to 44L, "challenge" to ByteArray(31)),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation

                assertSame(HtspResult.ServerError, service.hello(44L, "malformed-client", 1_000L, generation))
                assertTrue(service.isCurrent(generation))
                assertNull(service.liveConnection.value)
                assertNull(service.commitIfLive(generation) { it })
                assertSame(HtspResult.TransportUnavailable, service.getProfiles())
                withTimeout(1_000L) {
                    service.state.first { state -> state is ConnectionState.Disconnected }
                }
            }
        }
    }

    @Test
    fun directHelloAboveIntRangeKeepsExactResponseButMakesVersionPreflightUnknown() {
        val recapturedChallenge = ByteArray(32) { index -> (index + 3).toByte() }
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(
                mapOf(
                    "htspversion" to 0xffff_ffffL,
                    "challenge" to recapturedChallenge,
                    "servername" to "u32-server",
                ),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val generation = requireNotNull(service.liveConnection.value).generation

                val result = service.hello(
                    htspVersion = 0xffff_ffffL,
                    clientName = "u32-client",
                    timeoutMs = 1_000L,
                    expectedGeneration = generation,
                )
                assertTrue(result is HtspResult.Ok)
                assertEquals(0xffff_ffffL, (result as HtspResult.Ok).value.htspVersion)
                assertEquals(HtspBinary(recapturedChallenge), result.value.challenge)
                val live = requireNotNull(service.liveConnection.value)
                assertSame(generation, live.generation)
                assertNull(live.protocolVersion)
                assertEquals("u32-server", live.serverFacts.serverName)
                assertNull((service.state.value as ConnectionState.Connected).htspVersion)
                assertSame(HtspResult.NotSupported, service.getSysTime(expectedGeneration = generation))
                assertEquals(listOf("hello"), server.postHandshakeMethods())
                service.disconnect()
            }
        }
    }

    @Test
    fun staleHelloRecaptureCannotMutateAReplacementGeneration() {
        val recaptureReached = CompletableDeferred<Unit>()
        val resumeRecapture = CompletableDeferred<Unit>()
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf(
                "htspversion" to 2L,
                "challenge" to ByteArray(32),
                "servername" to "stale-server",
            ),
        ).use { firstServer ->
            FakeHtspServer(
                respondToHello = true,
                helloReplyFields = mapOf(
                    "htspversion" to 43,
                    "challenge" to ByteArray(32),
                    "servername" to "replacement-server",
                ),
            ).use { replacementServer ->
                val service = service(
                    beforeTypedRecapture = { request ->
                        if (request is HelloRequest) {
                            recaptureReached.complete(Unit)
                            resumeRecapture.await()
                        }
                    },
                )
                runBlocking {
                    service.connect(
                        host = "127.0.0.1",
                        port = firstServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                    val staleGeneration = requireNotNull(service.liveConnection.value).generation
                    val staleHello = async(Dispatchers.IO) {
                        runCatching {
                            service.hello(2L, "stale-client", 1_000L, staleGeneration)
                        }.exceptionOrNull()
                    }
                    withTimeout(1_000L) { recaptureReached.await() }

                    service.connect(
                        host = "127.0.0.1",
                        port = replacementServer.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                    val replacement = requireNotNull(service.liveConnection.value)
                    resumeRecapture.complete(Unit)

                    assertTrue(withTimeout(1_000L) { staleHello.await() } is CancellationException)
                    val current = requireNotNull(service.liveConnection.value)
                    assertSame(replacement.generation, current.generation)
                    assertEquals(43, current.protocolVersion)
                    assertEquals("replacement-server", current.serverFacts.serverName)
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun transportStateOmitsServerFacts() {
        val connectedClass = ConnectionState.Connected::class.java

        assertTrue(connectedClass.declaredMethods.none { method -> method.name == "getServerFacts" })
        assertTrue(connectedClass.declaredFields.none { field -> field.name == "serverFacts" })
    }

    @Test
    fun negativeU32HandshakeFactsStayUnknown() {
        val facts = htspServerFactsFromHandshake(
            hello = HtspWireMessage(
                method = "hello",
                seq = 1,
                fields = mapOf("api_version" to (-1).toByte()),
            ),
            auth = HtspWireMessage(
                method = "authenticate",
                seq = 2,
                fields = mapOf(
                    "limitall" to (-1).toShort(),
                    "limitdvr" to -1,
                    "limitstreaming" to -1L,
                    "uilevel" to (-1).toByte(),
                ),
            ),
        )

        assertNull(facts.apiVersion)
        assertNull(facts.limitAll)
        assertNull(facts.limitDvr)
        assertNull(facts.limitStreaming)
        assertNull(facts.uiLevel)
    }

    @Test
    fun successfulHandshakePublishesStrictOptionalServerFactsWithoutSecrets() {
        val mutableCapabilities = mutableListOf("timeshift", "htsp")
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 44,
                "challenge" to ByteArray(32) { index -> index.toByte() },
                "servername" to "tvh-fixture",
                "serverversion" to "4.3-fixture",
                "webroot" to "/tvheadend",
                "language" to "en_US",
                "servercapability" to mutableCapabilities,
                "api_version" to 19,
            ),
            authFields = mapOf(
                "admin" to 1,
                "streaming" to 1,
                "dvr" to 1,
                "faileddvr" to 0,
                "anonymous" to 0,
                "limitall" to 0,
                "limitdvr" to 2,
                "limitstreaming" to 5,
                "uilevel" to 1,
                "uilanguage" to "de_DE",
                // Secrets and non-fact fields must never surface through the internal handoff.
                "noaccess" to 0,
                "digest" to ByteArray(20),
                "username" to "should-not-publish",
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    username = "viewer",
                    password = "secret",
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val state = service.state.value as ConnectionState.Connected
                val attemptId = service.currentConnectionAttemptId()
                val facts = requireNotNull(service.serverFactsForLiveConnectionAttempt(attemptId))
                assertEquals("tvh-fixture", facts.serverName)
                assertEquals("4.3-fixture", facts.serverVersion)
                assertEquals("/tvheadend", facts.webRoot)
                assertEquals("en_US", facts.language)
                assertEquals(listOf("timeshift", "htsp"), facts.serverCapabilities)
                assertEquals(19, facts.apiVersion)
                assertEquals(true, facts.admin)
                assertEquals(true, facts.streaming)
                assertEquals(true, facts.dvr)
                assertEquals(false, facts.failedDvr)
                assertEquals(false, facts.anonymous)
                assertEquals(0, facts.limitAll)
                assertEquals(2, facts.limitDvr)
                assertEquals(5, facts.limitStreaming)
                assertEquals(1, facts.uiLevel)
                assertEquals("de_DE", facts.uiLanguage)
                // Existing DVR-capability derivation remains independent of the observation.
                assertEquals(true, state.dvrAccess)

                mutableCapabilities += "mutated-after-decode"
                assertEquals(listOf("timeshift", "htsp"), facts.serverCapabilities)

                // Public facts expose only safe identity/access observations.
                assertEquals(
                    HtspServerFacts(
                        serverName = "tvh-fixture",
                        serverVersion = "4.3-fixture",
                        webRoot = "/tvheadend",
                        language = "en_US",
                        serverCapabilities = listOf("timeshift", "htsp"),
                        apiVersion = 19,
                        admin = true,
                        streaming = true,
                        dvr = true,
                        failedDvr = false,
                        anonymous = false,
                        limitAll = 0,
                        limitDvr = 2,
                        limitStreaming = 5,
                        uiLevel = 1,
                        uiLanguage = "de_DE",
                    ),
                    facts,
                )
                val serialized = facts.toString()
                assertTrue(!serialized.contains("viewer"))
                assertTrue(!serialized.contains("secret"))
                assertTrue(!serialized.contains("should-not-publish"))
                assertTrue(!serialized.contains("challenge"))
                assertTrue(!serialized.contains("digest"))

                service.disconnect()
                assertTrue(service.state.value !is ConnectionState.Connected)
                assertNull(service.serverFactsForLiveConnectionAttempt(attemptId))
            }
        }
    }

    @Test
    fun omittedAndMalformedHandshakeFieldsStayExplicitlyUnknown() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servername" to "",
                "serverversion" to 12,
                "webroot" to listOf("/not-a-string"),
                "language" to null,
                "servercapability" to emptyList<Any?>(),
                "api_version" to "19",
            ),
            authFields = mapOf(
                "admin" to 0,
                "streaming" to 2,
                "dvr" to "1",
                "faileddvr" to 1L,
                "anonymous" to true,
                "limitall" to -1,
                "limitdvr" to 1.5,
                "limitstreaming" to Long.MAX_VALUE,
                "uilevel" to "high",
                "uilanguage" to ByteArray(2),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val facts = requireNotNull(
                    service.serverFactsForLiveConnectionAttempt(service.currentConnectionAttemptId()),
                )
                // Empty string is an observed wire value, not unknown.
                assertEquals("", facts.serverName)
                assertNull(facts.serverVersion)
                assertNull(facts.webRoot)
                assertNull(facts.language)
                // Empty capability list is distinct from unknown/absent.
                assertEquals(emptyList<String>(), facts.serverCapabilities)
                assertNull(facts.apiVersion)
                assertEquals(false, facts.admin)
                assertNull(facts.streaming)
                assertNull(facts.dvr)
                assertEquals(true, facts.failedDvr)
                assertNull(facts.anonymous)
                assertNull(facts.limitAll)
                assertNull(facts.limitDvr)
                assertNull(facts.limitStreaming)
                assertNull(facts.uiLevel)
                assertNull(facts.uiLanguage)
                service.disconnect()
            }
        }
    }

    @Test
    fun mixedTypeServerCapabilityListStaysUnknown() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
                "servercapability" to listOf("ok", 3),
            ),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )
                val facts = requireNotNull(
                    service.serverFactsForLiveConnectionAttempt(service.currentConnectionAttemptId()),
                )
                assertNull(facts.serverCapabilities)
                service.disconnect()
            }
        }
    }

    @Test
    fun absentOptionalHandshakeFieldsPublishUnknownFactsNotSyntheticDefaults() {
        FakeHtspServer(
            respondToHello = true,
            helloReplyFields = mapOf(
                "htspversion" to 43,
                "challenge" to ByteArray(32),
            ),
            authFields = emptyMap(),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(
                    host = "127.0.0.1",
                    port = server.port,
                    connectTimeoutMs = 1_000,
                    responseTimeoutMs = 1_000,
                    soTimeoutMs = 50,
                )

                val facts = requireNotNull(
                    service.serverFactsForLiveConnectionAttempt(service.currentConnectionAttemptId()),
                )
                assertEquals(HtspServerFacts(), facts)
                service.disconnect()
            }
        }
    }

    @Test
    fun anonymousConnectFailsWhenServerGrantsNoAccess() {
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("noaccess" to 1),
        ).use { server ->
            val service = service()
            val failure = runBlocking {
                runCatching {
                    service.connect(
                        host = "127.0.0.1",
                        port = server.port,
                        connectTimeoutMs = 1_000,
                        responseTimeoutMs = 1_000,
                        soTimeoutMs = 50,
                    )
                }.exceptionOrNull()
            }

            assertNotNull(failure)
            assertTrue(requireNotNull(failure).message.orEmpty().contains("noaccess=1"))
        }
    }

    private fun service(
        afterConnectionAdmission: suspend () -> Unit = {},
        afterTransportInstallation: suspend () -> Unit = {},
        afterTeardownAdmission: suspend () -> Unit = {},
        beforeTypedRecapture: suspend (HtspRequest<*>) -> Unit = {},
        beforeTypedEventPublication: (HtspTransportEvent.ServerMessage) -> Unit = {},
    ) = HtspService(
        ioDispatcher = Dispatchers.IO,
        afterConnectionAdmission = afterConnectionAdmission,
        afterTransportInstallation = afterTransportInstallation,
        afterTeardownAdmission = afterTeardownAdmission,
        beforeTypedRecapture = beforeTypedRecapture,
        beforeTypedEventPublication = beforeTypedEventPublication,
    )

    private fun muxPacketFields(payloadByte: Byte): Map<String, Any?> = mapOf(
        "subscriptionId" to 1L,
        "frametype" to 1L,
        "stream" to 0L,
        "duration" to 40L,
        "payload" to byteArrayOf(payloadByte),
    )

    private fun serviceOwnedJobCount(service: HtspService): Int =
        service.javaClass.getDeclaredField("serviceJob").let { field ->
            field.isAccessible = true
            (field.get(service) as kotlinx.coroutines.Job).children.count()
        }

    private class FakeHtspServer(
        private val respondToHello: Boolean,
        private val authFields: Map<String, Any?> = emptyMap(),
        private val helloReplyFields: Map<String, Any?> = mapOf(
            "htspversion" to 43,
            "challenge" to ByteArray(32),
        ),
        private val authenticateResponseGate: CountDownLatch? = null,
        private val captureOnePostHandshakeRequest: Boolean = false,
        private val postHandshakeReplyFields: Map<String, Any?>? = null,
        private val additionalPostHandshakeReplyFields: List<Map<String, Any?>> = emptyList(),
        private val postHandshakeReplyPlan: List<Map<String, Any?>?>? = null,
        private val expectedConnections: Int = 1,
    ) : Closeable {
        private val serverSocket = ServerSocket(0)
        private val stop = CountDownLatch(1)
        @Volatile
        private var clientSocket: Socket? = null
        private val clientSockets = CopyOnWriteArrayList<Socket>()
        @Volatile
        private var postHandshakeRequest: HtspWireMessage? = null
        private val postHandshakeRequests = CopyOnWriteArrayList<HtspWireMessage>()
        val authenticateRequestReceived = CountDownLatch(1)
        val postHandshakeRequestReceived = CountDownLatch(1)
        /** Methods the client sent during the handshake, in order. */
        val handshakeMethods = mutableListOf<String>()
        val handshakeFields = mutableMapOf<String, Map<String, Any?>>()
        private val serverThread = thread(
            start = true,
            isDaemon = true,
            name = "fake-htsp-server",
        ) {
            runCatching {
                repeat(expectedConnections) {
                    val client = serverSocket.accept()
                    clientSocket = client
                    clientSockets += client
                    if (respondToHello) {
                        // The client always sends hello then authenticate, with or without
                        // credentials; anything after that is left unanswered on purpose.
                        repeat(2) {
                            val request = HtspCodec.readMessage(client.getInputStream())
                            val method = requireNotNull(request.method)
                            handshakeMethods += method
                            handshakeFields[method] = request.fields
                            if (method == "authenticate") {
                                authenticateRequestReceived.countDown()
                                authenticateResponseGate?.await()
                            }
                            val fields = mutableMapOf<String, Any?>(
                                "seq" to requireNotNull(request.seq),
                            )
                            if (method == "authenticate") {
                                fields += authFields
                            } else {
                                fields += helloReplyFields
                            }
                            HtspCodec.writeMessage(
                                output = client.getOutputStream(),
                                method = method,
                                fields = fields,
                            )
                            client.getOutputStream().flush()
                        }
                        if (postHandshakeReplyPlan != null) {
                            postHandshakeReplyPlan.forEach { replyFields ->
                                val request = HtspCodec.readMessage(client.getInputStream())
                                postHandshakeRequests += request
                                if (postHandshakeRequest == null) postHandshakeRequest = request
                                postHandshakeRequestReceived.countDown()
                                replyFields?.let { replyToPostHandshakeRequest(request, it) }
                            }
                        } else if (captureOnePostHandshakeRequest) {
                            postHandshakeRequest = HtspCodec.readMessage(client.getInputStream())
                            postHandshakeRequests += checkNotNull(postHandshakeRequest)
                            postHandshakeRequestReceived.countDown()
                            postHandshakeReplyFields?.let(::replyToCapturedPostHandshakeRequest)
                            additionalPostHandshakeReplyFields.forEach { replyFields ->
                                val additionalRequest = HtspCodec.readMessage(client.getInputStream())
                                replyToPostHandshakeRequest(additionalRequest, replyFields)
                            }
                        }
                    }
                }
                stop.await()
            }
        }

        val port: Int = serverSocket.localPort

        fun sendServerMessage(method: String, fields: Map<String, Any?> = emptyMap()) {
            val output = checkNotNull(clientSocket).getOutputStream()
            HtspCodec.writeMessage(
                output = output,
                method = method,
                fields = fields,
            )
            output.flush()
        }

        fun replyToCapturedPostHandshakeRequest(
            replyFields: Map<String, Any?> = emptyMap(),
        ) {
            val request = checkNotNull(postHandshakeRequest)
            replyToPostHandshakeRequest(request, replyFields)
        }

        fun awaitPostHandshakeRequestCount(count: Int, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (postHandshakeRequests.size < count && System.nanoTime() < deadline) {
                Thread.sleep(1L)
            }
            return postHandshakeRequests.size >= count
        }

        fun postHandshakeRequest(index: Int): HtspWireMessage = postHandshakeRequests[index]

        fun postHandshakeMethods(): List<String> =
            postHandshakeRequests.map { request -> requireNotNull(request.method) }

        fun replyToPostHandshakeRequest(
            index: Int,
            replyFields: Map<String, Any?> = emptyMap(),
        ) {
            replyToPostHandshakeRequest(postHandshakeRequests[index], replyFields)
        }

        private fun replyToPostHandshakeRequest(
            request: HtspWireMessage,
            replyFields: Map<String, Any?>,
        ) {
            val output = checkNotNull(clientSocket).getOutputStream()
            HtspCodec.writeMessage(
                output = output,
                method = requireNotNull(request.method),
                fields = mapOf("seq" to requireNotNull(request.seq)) + replyFields,
            )
            output.flush()
        }

        fun capturedPostHandshakeRequest(): HtspWireMessage = checkNotNull(postHandshakeRequest)

        fun closeClientTransport() {
            runCatching { clientSocket?.close() }
        }

        override fun close() {
            stop.countDown()
            clientSockets.forEach { client -> runCatching { client.close() } }
            runCatching { serverSocket.close() }
            serverThread.join(1_000)
        }
    }
}
