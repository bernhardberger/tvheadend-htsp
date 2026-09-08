package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class HtspServiceSubscriptionEventTest : HtspServiceLifecycleFixture() {

    @Test
    fun subscriptionEnvelopeClassificationCoversEveryRoutedType() {
        assertEquals(
            setOf(
                "muxpkt",
                "queueStatus",
                "subscriptionStart",
                "subscriptionStop",
                "subscriptionGrace",
                "subscriptionStatus",
                "signalStatus",
                "descrambleInfo",
                "subscriptionSpeed",
                "timeshiftStatus",
                "subscriptionSkip",
            ),
            SUBSCRIPTION_SERVER_METHODS,
        )
    }

    @Test
    fun allSubscriptionMessagesStayOrderedAndOutOfGlobalMetadataEvents() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscriptionEvents = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val metadataEvents = CopyOnWriteArrayList<HtspTransportEvent.ServerMessage>()
                val subscriptionCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(7L).take(11).collect { event -> subscriptionEvents += event }
                }
                val metadataCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { event ->
                        if (event is HtspTransportEvent.ServerMessage) metadataEvents += event
                    }
                }

                server.sendServerMessage("muxpkt", muxPacketFields(1, subscriptionId = 7L))
                server.sendServerMessage(
                    "subscriptionStart",
                    mapOf("subscriptionId" to 7L, "streams" to emptyList<Map<String, Any?>>()),
                )
                server.sendServerMessage("subscriptionSkip", mapOf("subscriptionId" to 7L))
                server.sendServerMessage("subscriptionStatus", statusFields(7L, "running"))
                server.sendServerMessage(
                    "subscriptionGrace",
                    mapOf("subscriptionId" to 7L, "graceTimeout" to 5L),
                )
                server.sendServerMessage(
                    "subscriptionSpeed",
                    mapOf("subscriptionId" to 7L, "speed" to -100L),
                )
                server.sendServerMessage(
                    "timeshiftStatus",
                    mapOf("subscriptionId" to 7L, "full" to 0L, "shift" to -1L),
                )
                server.sendServerMessage(
                    "queueStatus",
                    mapOf(
                        "subscriptionId" to 7L,
                        "packets" to 0L,
                        "bytes" to 0L,
                        "Bdrops" to 0L,
                        "Pdrops" to 0L,
                        "Idrops" to 0L,
                        "errors" to 0xffff_ffffL,
                    ),
                )
                server.sendServerMessage(
                    "signalStatus",
                    mapOf("subscriptionId" to 7L, "feStatus" to "LOCK"),
                )
                server.sendServerMessage(
                    "descrambleInfo",
                    mapOf(
                        "subscriptionId" to 7L,
                        "pid" to 2L,
                        "caid" to 3L,
                        "provid" to 4L,
                        "ecmtime" to 5L,
                        "hops" to 6L,
                    ),
                )
                server.sendServerMessage("channelAdd", mapOf("channelId" to 91L))
                server.sendServerMessage("subscriptionStop", statusFields(7L, "stopped"))

                withTimeout(2_000L) { subscriptionCollector.join() }
                withTimeout(1_000L) {
                    while (metadataEvents.size < 1) delay(1L)
                }
                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Packet::class,
                        HtspSubscriptionEvent.Started::class,
                        HtspSubscriptionEvent.Skipped::class,
                        HtspSubscriptionEvent.Status::class,
                        HtspSubscriptionEvent.Grace::class,
                        HtspSubscriptionEvent.Speed::class,
                        HtspSubscriptionEvent.Timeshift::class,
                        HtspSubscriptionEvent.Queue::class,
                        HtspSubscriptionEvent.Signal::class,
                        HtspSubscriptionEvent.Descramble::class,
                        HtspSubscriptionEvent.Stopped::class,
                    ),
                    subscriptionEvents.map { event -> event::class },
                )
                assertEquals(
                    0xffff_ffffL,
                    subscriptionEvents.filterIsInstance<HtspSubscriptionEvent.Queue>().single().message.errorCount,
                )
                assertEquals(
                    listOf(91L),
                    metadataEvents.map { event -> (event.message as HtspChannelAddMessage).channelId },
                )

                metadataCollector.cancelAndJoin()
                service.disconnect()
            }
        }
    }

    @Test
    fun sameSubscriptionRestartsWithReplacementMetadataAndRetainsClockUntilUnsubscribe() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(emptyMap(), emptyMap()),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(41L).collect { events += it }
                }
                assertTrue(service.subscribe(41L, channelId = 1L, ninetyKhz = 1L) is HtspResult.Ok)

                listOf("MPEG2VIDEO", "H264", "HEVC").forEachIndexed { index, codec ->
                    server.sendServerMessage(
                        "subscriptionStart",
                        mapOf(
                            "subscriptionId" to 41L,
                            "streams" to listOf(mapOf("index" to index.toLong(), "type" to codec)),
                            "sourceinfo" to mapOf("service" to "source-$index"),
                        ),
                    )
                    server.sendServerMessage(
                        "muxpkt",
                        muxPacketFields(
                            payloadByte = index.toByte(),
                            subscriptionId = 41L,
                            presentationTimestamp = 90_000L,
                            duration = 3_600L,
                        ) + ("stream" to index.toLong()),
                    )
                    server.sendServerMessage(
                        "subscriptionStop",
                        mapOf("subscriptionId" to 41L, "status" to "Source reconfigured"),
                    )
                    withTimeout(1_000L) { while (events.size < (index + 1) * 3) delay(1L) }
                    assertTrue(collector.isActive)
                    assertEquals(
                        SubscriptionResources(buffers = 1, clocks = 1, collectedIds = 1),
                        subscriptionResources(service),
                    )
                }

                assertTrue(service.unsubscribe(41L) is HtspResult.Ok)
                withTimeout(1_000L) { collector.join() }
                assertEquals(
                    List(3) {
                        listOf(
                            HtspSubscriptionEvent.Started::class,
                            HtspSubscriptionEvent.Packet::class,
                            HtspSubscriptionEvent.Stopped::class,
                        )
                    }.flatten(),
                    events.map { it::class },
                )
                val starts = events.filterIsInstance<HtspSubscriptionEvent.Started>()
                assertEquals(
                    listOf("MPEG2VIDEO", "H264", "HEVC"),
                    starts.map { requireNotNull(it.message.streams).single().streamType },
                )
                assertEquals(listOf("source-0", "source-1", "source-2"), starts.map { it.message.sourceInfo?.service })
                events.filterIsInstance<HtspSubscriptionEvent.Packet>().forEachIndexed { index, event ->
                    assertEquals(index.toLong(), event.packet.streamIndex)
                    assertEquals(1_000_000L, event.packet.presentationTimeUs)
                    assertEquals(40_000L, event.packet.durationUs)
                }
                assertEquals(
                    SubscriptionResources(buffers = 0, clocks = 0, collectedIds = 1),
                    subscriptionResources(service),
                )
                service.close()
            }
        }
    }

    @Test
    fun collectionIsExclusiveForAnIdDuringAndAfterTerminalCompletion() {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(emptyMap())).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val events = service.subscriptionEvents(8L)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { events.toList() }

                val activeDuplicate = runCatching {
                    service.subscriptionEvents(8L).toList()
                }.exceptionOrNull()
                assertTrue(activeDuplicate is IllegalStateException)

                server.sendServerMessage("subscriptionStop", statusFields(8L, "stopped"))
                assertTrue(service.unsubscribe(8L) is HtspResult.Ok)
                withTimeout(1_000L) { collector.join() }

                val recollection = runCatching { events.toList() }.exceptionOrNull()
                val postTerminalDuplicate = runCatching {
                    service.subscriptionEvents(8L).toList()
                }.exceptionOrNull()
                assertTrue(recollection is IllegalStateException)
                assertTrue(postTerminalDuplicate is IllegalStateException)
                service.disconnect()
            }
        }
    }

    @Test
    fun staleGenerationCollectionCannotConsumeReplacementGenerationId() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    val first = service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                        as HtspConnectOutcome.Connected
                    val staleFlow = service.subscriptionEvents(
                        subscriptionId = 8L,
                        expectedGeneration = first.connection.generation,
                    )

                    val replacement = service.connect(
                        HtspEndpoint("127.0.0.1", replacementServer.port),
                    ) as HtspConnectOutcome.Connected
                    val staleFailure = runCatching { staleFlow.toList() }.exceptionOrNull()
                    assertTrue(staleFailure is kotlinx.coroutines.CancellationException)

                    val replacementEvents = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(
                            subscriptionId = 8L,
                            expectedGeneration = replacement.connection.generation,
                        ).take(1).toList()
                    }
                    replacementServer.sendServerMessage(
                        "subscriptionStop",
                        statusFields(8L, "stopped"),
                    )
                    assertTrue(
                        withTimeout(1_000L) { replacementEvents.await() }.single() is
                            HtspSubscriptionEvent.Stopped,
                    )
                    service.disconnect(replacement.connection.generation)
                }
            }
        }
    }

    @Test
    fun collectionRegistersBeforeRealSubscribeAndReceivesPacketBeforeStarted() {
        assertEquals(8192, SUBSCRIPTION_EVENT_BUFFER_CAPACITY)
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(17L).take(3).collect { event -> events += event }
                }
                val subscribe = async(Dispatchers.IO) {
                    service.subscribe(subscriptionId = 17L, channelId = 1L)
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                assertEquals("subscribe", server.capturedPostHandshakeRequest().method)

                server.sendServerMessage("muxpkt", muxPacketFields(1, subscriptionId = 17L))
                server.sendServerMessage(
                    "subscriptionStart",
                    mapOf("subscriptionId" to 17L, "streams" to emptyList<Map<String, Any?>>()),
                )
                server.replyToCapturedPostHandshakeRequest()
                assertTrue(withTimeout(1_000L) { subscribe.await() } is HtspResult.Ok)
                server.sendServerMessage("subscriptionStop", statusFields(17L, "stopped"))
                withTimeout(1_000L) { collector.join() }

                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Packet::class,
                        HtspSubscriptionEvent.Started::class,
                        HtspSubscriptionEvent.Stopped::class,
                    ),
                    events.map { event -> event::class },
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun subscribeWithoutActiveCollectionIsRejectedBeforeWireAdmission() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))

                val rejected = runCatching {
                    service.subscribe(subscriptionId = 18L, channelId = 1L)
                }.exceptionOrNull()
                assertTrue(rejected is IllegalStateException)
                assertFalse(server.awaitPostHandshakeRequestCount(1, 150L))

                val cleanupScheduler = TestCoroutineScheduler()
                val canceledCollector = launch(
                    context = StandardTestDispatcher(cleanupScheduler),
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    service.subscriptionEvents(17L).toList()
                }
                canceledCollector.cancel()
                val canceledCollectorRejection = runCatching {
                    service.subscribe(subscriptionId = 17L, channelId = 1L)
                }.exceptionOrNull()
                assertTrue(canceledCollectorRejection is IllegalStateException)
                assertFalse(server.awaitPostHandshakeRequestCount(1, 150L))
                cleanupScheduler.runCurrent()

                val events = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(18L).take(1).toList()
                }
                val accepted = async(Dispatchers.IO) {
                    service.subscribe(subscriptionId = 18L, channelId = 1L)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                server.replyToCapturedPostHandshakeRequest()
                assertTrue(withTimeout(1_000L) { accepted.await() } is HtspResult.Ok)
                server.sendServerMessage("subscriptionStop", statusFields(18L, "stopped"))
                assertTrue(withTimeout(1_000L) { events.await() }.single() is HtspSubscriptionEvent.Stopped)
                service.disconnect()
            }
        }
    }

    @Test
    fun subscribeRequestClockConvertsPacketsBeforeAcknowledgementPerId() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null, null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val nativeEvents = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(21L).take(2).toList()
                }
                val ninetyKhzEvents = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(22L).take(2).toList()
                }

                val nativeSubscribe = async(Dispatchers.IO) {
                    service.subscribe(subscriptionId = 21L, channelId = 1L, ninetyKhz = 0L)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                assertEquals(0L, server.postHandshakeRequest(0).fields["90khz"])
                server.sendServerMessage(
                    "muxpkt",
                    muxPacketFields(
                        payloadByte = 1,
                        subscriptionId = 21L,
                        decodingTimestamp = -1_000_000L,
                        presentationTimestamp = 1_000_000L,
                        duration = 40_000L,
                    ),
                )
                server.replyToPostHandshakeRequest(0)
                val nativeResponse = withTimeout(1_000L) { nativeSubscribe.await() } as HtspResult.Ok
                assertEquals(null, nativeResponse.value.ninetyKhz)

                val ninetyKhzSubscribe = async(Dispatchers.IO) {
                    service.subscribe(
                        subscriptionId = 22L,
                        channelId = 1L,
                        ninetyKhz = 7L,
                    )
                }
                assertTrue(server.awaitPostHandshakeRequestCount(2, 1_000L))
                server.sendServerMessage(
                    "muxpkt",
                    muxPacketFields(
                        payloadByte = 2,
                        subscriptionId = 22L,
                        decodingTimestamp = -90_000L,
                        presentationTimestamp = 90_000L,
                        duration = 3_600L,
                    ),
                )
                server.replyToPostHandshakeRequest(1, mapOf("90khz" to 1L))
                val ninetyKhzResponse = withTimeout(1_000L) {
                    ninetyKhzSubscribe.await()
                } as HtspResult.Ok
                assertEquals(true, ninetyKhzResponse.value.ninetyKhz)

                server.sendServerMessage("subscriptionStop", statusFields(21L, "stopped"))
                server.sendServerMessage("subscriptionStop", statusFields(22L, "stopped"))
                val nativePacket = withTimeout(1_000L) { nativeEvents.await() }
                    .filterIsInstance<HtspSubscriptionEvent.Packet>()
                    .single()
                    .packet
                val ninetyKhzPacket = withTimeout(1_000L) { ninetyKhzEvents.await() }
                    .filterIsInstance<HtspSubscriptionEvent.Packet>()
                    .single()
                    .packet

                assertEquals(nativePacket.decodingTimeUs, ninetyKhzPacket.decodingTimeUs)
                assertEquals(nativePacket.presentationTimeUs, ninetyKhzPacket.presentationTimeUs)
                assertEquals(nativePacket.durationUs, ninetyKhzPacket.durationUs)
                service.disconnect()
            }
        }
    }

    @Test
    fun subscriptionIdCannotBeReusedWithinGenerationButResetsOnReplacement() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = emptyMap(),
        ).use { firstServer ->
            FakeHtspServer(
                respondToHello = true,
                captureOnePostHandshakeRequest = true,
            ).use { replacementServer ->
                val service = service()
                runBlocking {
                    service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                    val firstEvents = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(25L).toList()
                    }
                    assertTrue(service.subscribe(25L, 1L) is HtspResult.Ok)
                    val duplicate = runCatching { service.subscribe(25L, 1L) }.exceptionOrNull()
                    assertTrue(duplicate is IllegalStateException)
                    delay(100L)
                    assertEquals(1, firstServer.postHandshakeMethods().size)

                    service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    assertTrue(
                        withTimeout(1_000L) { firstEvents.await() }.last() is
                            HtspSubscriptionEvent.Terminated,
                    )
                    val events = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(25L).take(2).toList()
                    }
                    val replacementSubscribe = async(Dispatchers.IO) {
                        service.subscribe(25L, 1L, ninetyKhz = 1L)
                    }
                    assertTrue(replacementServer.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                    replacementServer.sendServerMessage(
                        "muxpkt",
                        muxPacketFields(
                            payloadByte = 1,
                            subscriptionId = 25L,
                            presentationTimestamp = 90_000L,
                            duration = 3_600L,
                        ),
                    )
                    replacementServer.replyToCapturedPostHandshakeRequest(mapOf("90khz" to 1L))
                    assertTrue(withTimeout(1_000L) { replacementSubscribe.await() } is HtspResult.Ok)
                    replacementServer.sendServerMessage(
                        "subscriptionStop",
                        statusFields(25L, "stopped"),
                    )
                    val packet = withTimeout(1_000L) { events.await() }
                        .filterIsInstance<HtspSubscriptionEvent.Packet>()
                        .single()
                        .packet
                    assertEquals(1_000_000L, packet.presentationTimeUs)
                    assertEquals(40_000L, packet.durationUs)
                    service.disconnect()
                }
            }
        }
    }

    @Test
    fun longZapSequenceRetiresCompletedResourcesWithoutReusingIds() {
        val zapCount = 128
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = List(zapCount * 2) { emptyMap() },
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))

                repeat(zapCount) { index ->
                    val subscriptionId = index + 1L
                    val events = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(subscriptionId).toList()
                    }
                    assertEquals(
                        SubscriptionResources(
                            buffers = 1,
                            clocks = 0,
                            collectedIds = index + 1,
                        ),
                        subscriptionResources(service),
                    )

                    assertTrue(service.subscribe(subscriptionId, channelId = 1L) is HtspResult.Ok)
                    assertEquals(
                        SubscriptionResources(
                            buffers = 1,
                            clocks = 1,
                            collectedIds = index + 1,
                        ),
                        subscriptionResources(service),
                    )

                    server.sendServerMessage(
                        "subscriptionStatus",
                        statusFields(subscriptionId, "running"),
                    )
                    val expectedTypes = if (index % 2 == 0) {
                        server.sendServerMessage(
                            "subscriptionStop",
                            statusFields(subscriptionId, "stopped"),
                        )
                        listOf(
                            HtspSubscriptionEvent.Status::class,
                            HtspSubscriptionEvent.Stopped::class,
                        )
                    } else {
                        listOf(HtspSubscriptionEvent.Status::class)
                    }
                    assertTrue(service.unsubscribe(subscriptionId) is HtspResult.Ok)

                    assertEquals(
                        expectedTypes,
                        withTimeout(1_000L) { events.await() }.map { event -> event::class },
                    )
                    assertEquals(
                        SubscriptionResources(
                            buffers = 0,
                            clocks = 0,
                            collectedIds = index + 1,
                        ),
                        subscriptionResources(service),
                    )
                }

                assertTrue(
                    runCatching { service.subscriptionEvents(1L).toList() }
                        .exceptionOrNull() is IllegalStateException,
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun malformedMuxPacketsProduceOrderedDropMarkersWithoutDroppingControls() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val firstControl = CompletableDeferred<Unit>()
                val releaseCollector = CompletableDeferred<Unit>()
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(19L).take(5).collect { event ->
                        events += event
                        if (event is HtspSubscriptionEvent.Status && events.size == 1) {
                            firstControl.complete(Unit)
                            releaseCollector.await()
                        }
                    }
                }

                server.sendServerMessage("subscriptionStatus", statusFields(19L, "gate"))
                withTimeout(1_000L) { firstControl.await() }
                server.sendServerMessage("muxpkt", muxPacketFields(1, subscriptionId = 19L))
                server.sendServerMessage(
                    "muxpkt",
                    muxPacketFields(2, subscriptionId = 19L) - "payload",
                )
                server.sendServerMessage(
                    "muxpkt",
                    muxPacketFields(3, subscriptionId = 19L) + ("duration" to null),
                )
                server.sendServerMessage("subscriptionStatus", statusFields(19L, "running"))
                server.sendServerMessage("subscriptionStop", statusFields(19L, "stopped"))
                delay(100L)
                releaseCollector.complete(Unit)
                withTimeout(1_000L) { collector.join() }

                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Status::class,
                        HtspSubscriptionEvent.Packet::class,
                        HtspSubscriptionEvent.Dropped::class,
                        HtspSubscriptionEvent.Status::class,
                        HtspSubscriptionEvent.Stopped::class,
                    ),
                    events.map { event -> event::class },
                )
                assertEquals(HtspSubscriptionEvent.Dropped(2L), events[2])
                service.disconnect()
            }
        }
    }

    @Test
    fun malformedSubscriptionControlFailsTransportInsteadOfDisappearing() {
        val malformedFields = listOf(
            mapOf("subscriptionId" to "malformed"),
            statusFields(20L, "running") + ("seq" to "malformed"),
            statusFields(20L, "running") + ("seq" to 999L),
        )
        malformedFields.forEach { fields ->
            FakeHtspServer(respondToHello = true).use { server ->
                val service = service()
                runBlocking {
                    service.connect(HtspEndpoint("127.0.0.1", server.port))
                    val failure = async(start = CoroutineStart.UNDISPATCHED) {
                        service.events.first { event -> event is HtspTransportEvent.ConnectionFailure }
                            as HtspTransportEvent.ConnectionFailure
                    }
                    val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(20L).toList()
                    }

                    server.sendServerMessage("subscriptionStatus", fields)

                    assertEquals(
                        HtspTransportFailureKind.INCOMPATIBLE_SERVER,
                        withTimeout(1_000L) { failure.await() }.failure.kind,
                    )
                    assertEquals(
                        listOf(
                            HtspSubscriptionEvent.Terminated(
                                HtspSubscriptionTermination.MALFORMED_MESSAGE,
                            ),
                        ),
                        withTimeout(1_000L) { subscription.await() },
                    )
                    service.close()
                }
            }
        }
    }

    @Test
    fun invalidFrameTerminatesSubscriptionWithFramingAttribution() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(32L).toList()
                }

                server.sendRaw(byteArrayOf(0, 0, 0, 0))

                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.FRAMING_FAILURE,
                        ),
                    ),
                    withTimeout(1_000L) { subscription.await() },
                )
                service.close()
            }
        }
    }

    @Test
    fun truncatedFrameTerminatesSubscriptionWithFramingAttribution() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(42L).toList()
                }

                server.sendRaw(byteArrayOf(0, 0))
                server.closeClientTransport()

                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.FRAMING_FAILURE,
                        ),
                    ),
                    withTimeout(1_000L) { subscription.await() },
                )
                service.close()
            }
        }
    }

    @Test
    fun readerIoFailureTerminatesSubscriptionWithIoAttribution() {
        val failNextFrame = AtomicBoolean(false)
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service(
                beforeFrameRead = {
                    if (failNextFrame.compareAndSet(true, false)) {
                        throw IOException("synthetic reader failure")
                    }
                },
            )
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(33L).toList()
                }

                failNextFrame.set(true)
                server.sendServerMessage("subscriptionStatus", statusFields(33L, "wake"))

                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Status(
                            HtspSubscriptionStatusMessage(
                                subscriptionId = 33L,
                                status = "wake",
                                subscriptionError = null,
                            ),
                        ),
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.IO_FAILURE,
                        ),
                    ),
                    withTimeout(1_000L) { subscription.await() },
                )
                service.close()
            }
        }
    }

    @Test
    fun unexpectedReaderFailureTerminatesSubscriptionWithBoundedAttribution() {
        val failNextFrame = AtomicBoolean(false)
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service(
                beforeFrameRead = {
                    if (failNextFrame.compareAndSet(true, false)) {
                        throw IllegalStateException("unclassified reader detail")
                    }
                },
            )
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(41L).toList()
                }

                failNextFrame.set(true)
                server.sendServerMessage("subscriptionStatus", statusFields(41L, "wake"))

                val events = withTimeout(1_000L) { subscription.await() }
                assertTrue(events.first() is HtspSubscriptionEvent.Status)
                assertEquals(
                    HtspSubscriptionEvent.Terminated(
                        HtspSubscriptionTermination.INTERNAL_FAILURE,
                    ),
                    events.last(),
                )
                service.close()
            }
        }
    }

    @Test
    fun publicationHookFailureIsContainedAndAttributed() {
        listOf<() -> Throwable>(
            { IllegalStateException("unpublished hook detail") },
            { kotlinx.coroutines.CancellationException("unpublished hook cancellation") },
            { AssertionError("unpublished hook error") },
        ).forEach { hookFailure ->
            FakeHtspServer(respondToHello = true).use { server ->
                val service = service(
                    beforeTypedEventPublication = { event ->
                        if ((event.message as? HtspSubscriptionStatusMessage)?.subscriptionId == 34L) {
                            throw hookFailure()
                        }
                    },
                )
                runBlocking {
                    service.connect(HtspEndpoint("127.0.0.1", server.port))
                    val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(34L).toList()
                    }

                    server.sendServerMessage("subscriptionStatus", statusFields(34L, "running"))

                    assertEquals(
                        listOf(
                            HtspSubscriptionEvent.Terminated(
                                HtspSubscriptionTermination.PUBLICATION_FAILURE,
                            ),
                        ),
                        withTimeout(1_000L) { subscription.await() },
                    )
                    service.close()
                }
            }
        }
    }

    @Test
    fun explicitCloseTerminatesSubscriptionAsLocalRetirement() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(35L).toList()
                }

                service.close()

                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.LOCAL_RETIREMENT,
                        ),
                    ),
                    withTimeout(1_000L) { subscription.await() },
                )
            }
        }
    }

    @Test
    fun explicitDisconnectDrainsThenTerminatesAsLocalRetirement() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(43L).collect { event -> events += event }
                }
                server.sendServerMessage("subscriptionStatus", statusFields(43L, "queued"))
                withTimeout(1_000L) {
                    while (events.none { event -> event is HtspSubscriptionEvent.Status }) delay(1L)
                }

                service.disconnect()

                withTimeout(1_000L) { subscription.join() }
                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Status::class,
                        HtspSubscriptionEvent.Terminated::class,
                    ),
                    events.map { event -> event::class },
                )
                assertEquals(
                    HtspSubscriptionEvent.Terminated(
                        HtspSubscriptionTermination.LOCAL_RETIREMENT,
                    ),
                    events.last(),
                )
                service.close()
            }
        }
    }

    @Test
    fun postSeekBurstDrainsInOrderBeforeAttributedRemoteEof() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(36L).toList()
                }

                server.sendServerMessage("subscriptionSkip", mapOf("subscriptionId" to 36L))
                server.sendServerMessage(
                    "timeshiftStatus",
                    mapOf("subscriptionId" to 36L, "full" to 0L, "shift" to -1L),
                )
                server.sendServerMessage(
                    "muxpkt",
                    muxPacketFields(
                        payloadByte = 1,
                        subscriptionId = 36L,
                        frameType = 73L,
                        presentationTimestamp = 1_000_000L,
                    ),
                )
                server.closeClientTransport()

                val events = withTimeout(1_000L) { subscription.await() }
                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Skipped::class,
                        HtspSubscriptionEvent.Timeshift::class,
                        HtspSubscriptionEvent.Packet::class,
                        HtspSubscriptionEvent.Terminated::class,
                    ),
                    events.map { event -> event::class },
                )
                assertEquals(
                    HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.REMOTE_EOF),
                    events.last(),
                )
                assertEquals(
                    73L,
                    (events[2] as HtspSubscriptionEvent.Packet).packet.frameType,
                )
                service.close()
            }
        }
    }

    @Test
    fun nearLiveSkipEventsCanPrecedeTheRequestAcknowledgement() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                val live = service.connect(HtspEndpoint("127.0.0.1", server.port))
                    as HtspConnectOutcome.Connected
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(37L, live.connection.generation)
                        .collect { event -> events += event }
                }
                val request = async(Dispatchers.IO) {
                    service.subscriptionSkipNearLive(
                        status = HtspTimeshiftStatusMessage(
                            subscriptionId = 37L,
                            full = 0L,
                            shift = 5_000_000L,
                            start = 10_000_000L,
                            end = 20_000_000L,
                        ),
                        clock = SubscriptionTimestampClock.MICROSECONDS,
                        marginSeconds = 3L,
                        expectedGeneration = live.connection.generation,
                    )
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                assertEquals("subscriptionSkip", server.capturedPostHandshakeRequest().method)
                assertEquals(
                    mapOf(
                        "subscriptionId" to 37L,
                        "time" to 17_000_000L,
                        "absolute" to 1L,
                    ),
                    server.capturedPostHandshakeRequest().fields.filterKeys { key ->
                        key != "method" && key != "seq"
                    },
                )

                server.sendServerMessage(
                    "timeshiftStatus",
                    mapOf(
                        "subscriptionId" to 37L,
                        "full" to 0L,
                        "shift" to 3_000_000L,
                        "start" to 10_000_000L,
                        "end" to 20_000_000L,
                    ),
                )
                server.sendServerMessage(
                    "subscriptionSkip",
                    mapOf(
                        "subscriptionId" to 37L,
                        "absolute" to 1L,
                        "time" to 17_000_000L,
                    ),
                )
                withTimeout(1_000L) {
                    while (events.size < 2) delay(1L)
                }
                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Timeshift::class,
                        HtspSubscriptionEvent.Skipped::class,
                    ),
                    events.map { event -> event::class },
                )
                assertFalse(request.isCompleted)

                request.cancel()
                assertTrue(
                    runCatching { request.await() }.exceptionOrNull() is
                        kotlinx.coroutines.CancellationException,
                )
                server.sendServerMessage("subscriptionStop", statusFields(37L, "stopped"))
                service.close()
                withTimeout(1_000L) { collector.join() }
            }
        }
    }

    @Test
    fun speedRepliesRemainCorrelatedAcrossBothAsyncOrderings() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null, null),
        ).use { server ->
            val service = service()
            runBlocking {
                val live = service.connect(HtspEndpoint("127.0.0.1", server.port))
                    as HtspConnectOutcome.Connected
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(37L, live.connection.generation)
                        .collect { event -> events += event }
                }

                listOf(0, 100).forEachIndexed { index, speed ->
                    val request = async(Dispatchers.IO) {
                        service.subscriptionSpeed(
                            subscriptionId = 37L,
                            speed = speed,
                            expectedGeneration = live.connection.generation,
                        )
                    }
                    assertTrue(server.awaitPostHandshakeRequestCount(index + 1, 1_000L))
                    val captured = server.postHandshakeRequest(index)
                    assertEquals("subscriptionSpeed", captured.method)
                    assertEquals(
                        mapOf("subscriptionId" to 37L, "speed" to speed.toLong()),
                        captured.fields.filterKeys { key -> key != "method" && key != "seq" },
                    )

                    val sendObservations = {
                        server.sendServerMessage(
                            "subscriptionSpeed",
                            mapOf("subscriptionId" to 37L, "speed" to speed.toLong()),
                        )
                        server.sendServerMessage(
                            "timeshiftStatus",
                            mapOf("subscriptionId" to 37L, "full" to 0L, "shift" to -1L),
                        )
                    }
                    if (index == 0) {
                        sendObservations()
                        withTimeout(1_000L) {
                            while (events.size < 2) delay(1L)
                        }
                        assertFalse(request.isCompleted)
                        server.replyToPostHandshakeRequestWithoutMethod(index)
                    } else {
                        server.replyToPostHandshakeRequestWithoutMethod(index)
                        sendObservations()
                    }

                    assertTrue(withTimeout(1_000L) { request.await() } is HtspResult.Ok)
                    withTimeout(1_000L) {
                        while (events.size < (index + 1) * 2) delay(1L)
                    }
                }

                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Speed::class,
                        HtspSubscriptionEvent.Timeshift::class,
                        HtspSubscriptionEvent.Speed::class,
                        HtspSubscriptionEvent.Timeshift::class,
                    ),
                    events.map { event -> event::class },
                )
                assertEquals(
                    listOf(0, 100),
                    events.filterIsInstance<HtspSubscriptionEvent.Speed>()
                        .map { event -> event.message.speed },
                )
                assertSame(live.connection.generation, service.liveConnection.value?.generation)

                server.sendServerMessage("subscriptionStop", statusFields(37L, "stopped"))
                service.close()
                withTimeout(1_000L) { collector.join() }
            }
        }
    }

    @Test
    fun subscriptionEnvelopeCannotCompleteAPendingReply() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val failure = async(start = CoroutineStart.UNDISPATCHED) {
                    service.events.first { event -> event is HtspTransportEvent.ConnectionFailure }
                        as HtspTransportEvent.ConnectionFailure
                }
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(31L).toList()
                }
                val pending = async(Dispatchers.IO) {
                    runCatching { service.getSysTime(timeoutMs = 5_000L) }
                }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                val pendingSequence = requireNotNull(server.postHandshakeRequest(0).seq)

                server.sendServerMessage(
                    "subscriptionStatus",
                    statusFields(31L, "running") + ("seq" to pendingSequence.toLong()),
                )

                assertEquals(
                    HtspTransportFailureKind.INCOMPATIBLE_SERVER,
                    withTimeout(1_000L) { failure.await() }.failure.kind,
                )
                assertSame(HtspResult.TransportUnavailable, withTimeout(1_000L) { pending.await() }.getOrThrow())
                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.MALFORMED_MESSAGE,
                        ),
                    ),
                    withTimeout(1_000L) { subscription.await() },
                )
                service.close()
            }
        }
    }

    @Test
    fun timeoutAndCancellationRetainProvisionalClockForLateSuccess() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null, null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))

                val timedOutEvents = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(23L).take(2).toList()
                }
                val timedOut = async(Dispatchers.IO) {
                    service.subscribe(23L, 1L, ninetyKhz = 1L, timeoutMs = 100L)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                assertSameResult(HtspResult.Timeout, withTimeout(1_000L) { timedOut.await() })
                server.replyToPostHandshakeRequest(0, mapOf("90khz" to 1L))
                server.sendServerMessage(
                    "muxpkt",
                    muxPacketFields(
                        payloadByte = 1,
                        subscriptionId = 23L,
                        presentationTimestamp = 90_000L,
                    ),
                )
                server.sendServerMessage("subscriptionStop", statusFields(23L, "stopped"))

                val cancelledEvents = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(24L).take(2).toList()
                }
                val cancelled = async(Dispatchers.IO) {
                    service.subscribe(24L, 1L, ninetyKhz = 1L, timeoutMs = 5_000L)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(2, 1_000L))
                cancelled.cancel()
                assertTrue(
                    runCatching { cancelled.await() }.exceptionOrNull() is
                        kotlinx.coroutines.CancellationException,
                )
                server.replyToPostHandshakeRequest(1, mapOf("90khz" to 1L))
                server.sendServerMessage(
                    "muxpkt",
                    muxPacketFields(
                        payloadByte = 2,
                        subscriptionId = 24L,
                        presentationTimestamp = 90_000L,
                    ),
                )
                server.sendServerMessage("subscriptionStop", statusFields(24L, "stopped"))

                val timedOutPacket = withTimeout(1_000L) { timedOutEvents.await() }
                    .filterIsInstance<HtspSubscriptionEvent.Packet>()
                    .single()
                    .packet
                val cancelledPacket = withTimeout(1_000L) { cancelledEvents.await() }
                    .filterIsInstance<HtspSubscriptionEvent.Packet>()
                    .single()
                    .packet
                assertEquals(1_000_000L, timedOutPacket.presentationTimeUs)
                assertEquals(1_000_000L, cancelledPacket.presentationTimeUs)
                service.disconnect()
            }
        }
    }

    @Test
    fun packetPressureEvictsOnlyPacketsAndKeepsOrderedDropMarkersAndStop() {
        FakeHtspServer(respondToHello = true).use { server ->
            var publishedPacket: HtspMuxPacketMessage? = null
            val service = service(
                beforeTypedEventPublication = { event ->
                    (event.message as? HtspMuxPacketMessage)?.let { packet ->
                        publishedPacket = packet
                    }
                },
                subscriptionEventBufferCapacity = 2,
            )
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val firstControl = CompletableDeferred<Unit>()
                val releaseCollector = CompletableDeferred<Unit>()
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(9L).take(4).collect { event ->
                        events += event
                        if (event is HtspSubscriptionEvent.Status) {
                            firstControl.complete(Unit)
                            releaseCollector.await()
                        }
                    }
                }

                server.sendServerMessage("subscriptionStatus", statusFields(9L, "gate"))
                withTimeout(1_000L) { firstControl.await() }
                repeat(4) { index ->
                    server.sendServerMessage(
                        "muxpkt",
                        muxPacketFields((index + 1).toByte(), subscriptionId = 9L),
                    )
                }
                server.sendServerMessage("subscriptionStop", statusFields(9L, "stopped"))
                delay(100L)
                releaseCollector.complete(Unit)
                withTimeout(1_000L) { collector.join() }

                assertEquals(HtspSubscriptionEvent.Status::class, events[0]::class)
                assertEquals(HtspSubscriptionEvent.Dropped(3L), events[1])
                val payload = (events[2] as HtspSubscriptionEvent.Packet).packet.payload
                val destination = ByteArray(payload.size)
                assertEquals(payload.size, payload.copyInto(destination))
                assertSame(publishedPacket?.payload, payload)
                assertEquals(
                    4.toByte(),
                    destination.single(),
                )
                assertTrue(events[3] is HtspSubscriptionEvent.Stopped)
                service.disconnect()
            }
        }
    }

    @Test
    fun fullControlQueueBackpressuresReaderAndRetainsEveryControlEvent() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service(subscriptionEventBufferCapacity = 1)
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val firstControl = CompletableDeferred<Unit>()
                val releaseCollector = CompletableDeferred<Unit>()
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(10L).take(4).collect { event ->
                        events += event
                        if (event is HtspSubscriptionEvent.Status && events.size == 1) {
                            firstControl.complete(Unit)
                            releaseCollector.await()
                        }
                    }
                }

                server.sendServerMessage("subscriptionStatus", statusFields(10L, "one"))
                withTimeout(1_000L) { firstControl.await() }
                server.sendServerMessage("subscriptionStatus", statusFields(10L, "two"))
                server.sendServerMessage("subscriptionStatus", statusFields(10L, "three"))
                val request = async(Dispatchers.IO) {
                    service.request(
                        method = "controlBackpressureProbe",
                        timeoutMs = 2_000L,
                        disconnectOnTimeout = false,
                    )
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()
                delay(100L)
                assertFalse(request.isCompleted)

                releaseCollector.complete(Unit)
                assertEquals(
                    "controlBackpressureProbe",
                    withTimeout(1_000L) { request.await() }.method,
                )
                server.sendServerMessage("subscriptionStop", statusFields(10L, "stopped"))
                withTimeout(1_000L) { collector.join() }
                assertEquals(
                    listOf("one", "two", "three"),
                    events.filterIsInstance<HtspSubscriptionEvent.Status>()
                        .map { event -> event.message.status },
                )
                assertTrue(events.last() is HtspSubscriptionEvent.Stopped)
                service.disconnect()
            }
        }
    }

    @Test
    fun successfulUnsubscribeAcknowledgementDrainsThenCompletesInReaderOrder() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(11L).collect { event -> events += event }
                }
                server.sendServerMessage("subscriptionStatus", statusFields(11L, "before"))

                val unsubscribe = async(Dispatchers.IO) { service.unsubscribe(11L) }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.sendServerMessage("subscriptionStatus", statusFields(11L, "queued"))
                server.replyToCapturedPostHandshakeRequest()
                server.sendServerMessage("subscriptionStatus", statusFields(11L, "after"))

                assertTrue(withTimeout(1_000L) { unsubscribe.await() } is HtspResult.Ok)
                withTimeout(1_000L) { collector.join() }
                assertEquals(
                    listOf("before", "queued"),
                    events.filterIsInstance<HtspSubscriptionEvent.Status>()
                        .map { event -> event.message.status },
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun stoppedBeforeUnsubscribeAcknowledgementWaitsForTrueRetirement() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val events = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(14L).collect { event -> events += event }
                }
                val unsubscribe = async(Dispatchers.IO) { service.unsubscribe(14L) }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                server.sendServerMessage("subscriptionStop", statusFields(14L, "stopped"))
                withTimeout(1_000L) { while (events.isEmpty()) delay(1L) }
                assertTrue(collector.isActive)
                server.replyToCapturedPostHandshakeRequest()
                assertTrue(withTimeout(1_000L) { unsubscribe.await() } is HtspResult.Ok)
                withTimeout(1_000L) { collector.join() }
                assertEquals(1, events.size)
                assertTrue(events.single() is HtspSubscriptionEvent.Stopped)
                service.disconnect()
            }
        }
    }

    @Test
    fun lateSuccessfulUnsubscribeAfterTimeoutOrCancellationStillCompletesStream() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null, null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))

                val timedOutCollector = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(15L).toList()
                }
                val timedOut = async(Dispatchers.IO) {
                    service.unsubscribe(subscriptionId = 15L, timeoutMs = 100L)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                assertSameResult(HtspResult.Timeout, withTimeout(1_000L) { timedOut.await() })
                server.replyToPostHandshakeRequest(0)
                assertEquals(emptyList<HtspSubscriptionEvent>(), withTimeout(1_000L) {
                    timedOutCollector.await()
                })

                val cancelledCollector = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(16L).toList()
                }
                val cancelled = async(Dispatchers.IO) {
                    service.unsubscribe(subscriptionId = 16L, timeoutMs = 5_000L)
                }
                assertTrue(server.awaitPostHandshakeRequestCount(2, 1_000L))
                cancelled.cancel()
                assertTrue(
                    runCatching { cancelled.await() }.exceptionOrNull() is
                        kotlinx.coroutines.CancellationException,
                )
                server.replyToPostHandshakeRequest(1)
                assertEquals(emptyList<HtspSubscriptionEvent>(), withTimeout(1_000L) {
                    cancelledCollector.await()
                })
                service.disconnect()
            }
        }
    }

    @Test
    fun replacementAndRemoteEofAppendDistinctPayloadFreeTerminalEvents() {
        FakeHtspServer(respondToHello = true).use { firstServer ->
            FakeHtspServer(respondToHello = true).use { replacementServer ->
                val service = service()
                runBlocking {
                    service.connect(HtspEndpoint("127.0.0.1", firstServer.port))
                    val replacedEvents = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                    val replacedCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(12L).collect { event -> replacedEvents += event }
                    }
                    firstServer.sendServerMessage(
                        "subscriptionStop",
                        statusFields(12L, "old"),
                    )
                    withTimeout(1_000L) {
                        while (replacedEvents.isEmpty()) delay(1L)
                    }

                    service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    withTimeout(1_000L) { replacedCollector.join() }
                    assertEquals(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.GENERATION_LOST,
                        ),
                        replacedEvents.last(),
                    )

                    val closedEvents = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                    val closedCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(12L).collect { event -> closedEvents += event }
                    }
                    replacementServer.sendServerMessage(
                        "subscriptionStop",
                        statusFields(12L, "current"),
                    )
                    withTimeout(1_000L) { while (closedEvents.isEmpty()) delay(1L) }
                    replacementServer.closeClientTransport()
                    withTimeout(1_000L) { closedCollector.join() }
                    assertEquals(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.REMOTE_EOF,
                        ),
                        closedEvents.last(),
                    )
                    service.close()
                }
            }
        }
    }

    @Test
    fun collectorCancellationPropagatesAndKeepsGenerationTombstone() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(emptyMap()),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val collector = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(13L).toList()
                }
                assertTrue(service.subscribe(13L, channelId = 1L) is HtspResult.Ok)
                assertEquals(
                    SubscriptionResources(buffers = 1, clocks = 1, collectedIds = 1),
                    subscriptionResources(service),
                )
                collector.cancel()
                assertTrue(runCatching { collector.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertEquals(
                    SubscriptionResources(buffers = 0, clocks = 0, collectedIds = 1),
                    subscriptionResources(service),
                )
                assertTrue(
                    runCatching { service.subscriptionEvents(13L).toList() }
                        .exceptionOrNull() is IllegalStateException,
                )
                service.disconnect()
            }
        }
    }

    @Test
    fun defaultMetadataBurstReachesTwoCollectorsIndependently() {
        assertEquals(1024, METADATA_EVENT_BUFFER_CAPACITY)
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val first = CopyOnWriteArrayList<Long>()
                val second = CopyOnWriteArrayList<Long>()
                val firstCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { event ->
                        val message = (event as? HtspTransportEvent.ServerMessage)?.message
                        if (message is HtspChannelAddMessage) {
                            first += message.channelId
                            delay(1L)
                        }
                    }
                }
                val secondCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { event ->
                        val message = (event as? HtspTransportEvent.ServerMessage)?.message
                        if (message is HtspChannelAddMessage) second += message.channelId
                    }
                }

                repeat(METADATA_EVENT_BUFFER_CAPACITY) { index ->
                    server.sendServerMessage("channelAdd", mapOf("channelId" to index.toLong()))
                }
                withTimeout(5_000L) {
                    while (
                        first.size < METADATA_EVENT_BUFFER_CAPACITY ||
                        second.size < METADATA_EVENT_BUFFER_CAPACITY
                    ) {
                        delay(1L)
                    }
                }
                val expected = (0L until METADATA_EVENT_BUFFER_CAPACITY.toLong()).toList()
                assertEquals(expected, first)
                assertEquals(expected, second)
                firstCollector.cancelAndJoin()
                secondCollector.cancelAndJoin()
                service.disconnect()
            }
        }
    }

    @Test
    fun metadataBeyondInjectedBudgetBackpressuresUntilStalledCollectorAdvances() {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service(metadataEventBufferCapacity = 2)
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val firstReceived = CompletableDeferred<Unit>()
                val releaseStalled = CompletableDeferred<Unit>()
                val stalledEvents = CopyOnWriteArrayList<Long>()
                val normalEvents = CopyOnWriteArrayList<Long>()
                val stalledCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { event ->
                        val message = (event as? HtspTransportEvent.ServerMessage)?.message
                        if (message is HtspChannelAddMessage) {
                            stalledEvents += message.channelId
                            if (stalledEvents.size == 1) {
                                firstReceived.complete(Unit)
                                releaseStalled.await()
                            }
                        }
                    }
                }
                val normalCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { event ->
                        val message = (event as? HtspTransportEvent.ServerMessage)?.message
                        if (message is HtspChannelAddMessage) normalEvents += message.channelId
                    }
                }

                server.sendServerMessage("channelAdd", mapOf("channelId" to 1L))
                withTimeout(1_000L) { firstReceived.await() }
                server.sendServerMessage("channelAdd", mapOf("channelId" to 2L))
                server.sendServerMessage("channelAdd", mapOf("channelId" to 3L))
                server.sendServerMessage("channelAdd", mapOf("channelId" to 4L))
                val request = async(Dispatchers.IO) {
                    service.request(
                        method = "metadataBackpressureProbe",
                        timeoutMs = 2_000L,
                        disconnectOnTimeout = false,
                    )
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()
                delay(100L)
                assertFalse(request.isCompleted)

                releaseStalled.complete(Unit)
                assertEquals(
                    "metadataBackpressureProbe",
                    withTimeout(1_000L) { request.await() }.method,
                )
                withTimeout(1_000L) {
                    while (stalledEvents.size < 4 || normalEvents.size < 4) delay(1L)
                }
                assertEquals(listOf(1L, 2L, 3L, 4L), stalledEvents)
                assertEquals(listOf(1L, 2L, 3L, 4L), normalEvents)
                stalledCollector.cancelAndJoin()
                normalCollector.cancelAndJoin()
                service.disconnect()
            }
        }
    }

    private fun statusFields(subscriptionId: Long, status: String): Map<String, Any?> = mapOf(
        "subscriptionId" to subscriptionId,
        "state" to status,
    )

    private fun subscriptionResources(service: HtspService): SubscriptionResources {
        val generation = service.javaClass.getDeclaredField("protocolGeneration")
            .apply { isAccessible = true }
            .get(service)
        val generationClass = generation.javaClass
        val buffers = generationClass.getDeclaredField("subscriptionStreams")
            .apply { isAccessible = true }
            .get(generation) as Map<*, *>
        val clocks = generationClass.getDeclaredField("subscriptionTimestampClocks")
            .apply { isAccessible = true }
            .get(generation) as Map<*, *>
        val collectedIds = generationClass.getDeclaredField("collectedSubscriptionIds")
            .apply { isAccessible = true }
            .get(generation) as Set<*>
        return SubscriptionResources(
            buffers = buffers.size,
            clocks = clocks.size,
            collectedIds = collectedIds.size,
        )
    }

    private data class SubscriptionResources(
        val buffers: Int,
        val clocks: Int,
        val collectedIds: Int,
    )

    private fun assertSameResult(
        expected: HtspResult<*>,
        actual: HtspResult<*>,
    ) {
        assertTrue(actual === expected)
    }
}
