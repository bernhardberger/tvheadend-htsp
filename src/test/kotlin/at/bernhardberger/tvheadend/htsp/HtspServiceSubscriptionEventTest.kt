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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

internal class HtspServiceSubscriptionEventTest : HtspServiceLifecycleFixture() {

    @Test
    fun allSubscriptionMessagesStayOrderedAndOutOfGlobalMetadataEvents() {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val subscriptionEvents = CopyOnWriteArrayList<HtspSubscriptionEvent>()
                val metadataEvents = CopyOnWriteArrayList<HtspTransportEvent.ServerMessage>()
                val subscriptionCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(7L).collect { event -> subscriptionEvents += event }
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
                    listOf(91L),
                    metadataEvents.map { event -> (event.message as HtspChannelAddMessage).channelId },
                )

                metadataCollector.cancelAndJoin()
                service.disconnect()
            }
        }
    }

    @Test
    fun collectionIsExclusiveForAnIdDuringAndAfterTerminalCompletion() {
        FakeHtspServer(respondToHello = true).use { server ->
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
                    service.subscriptionEvents(17L).collect { event -> events += event }
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
    fun subscribeRequestClockConvertsPacketsBeforeAcknowledgementPerId() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null, null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val nativeEvents = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(21L).toList()
                }
                val ninetyKhzEvents = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(22L).toList()
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
                    assertTrue(service.subscribe(25L, 1L) is HtspResult.Ok)
                    val duplicate = runCatching { service.subscribe(25L, 1L) }.exceptionOrNull()
                    assertTrue(duplicate is IllegalStateException)
                    delay(100L)
                    assertEquals(1, firstServer.postHandshakeMethods().size)

                    service.connect(HtspEndpoint("127.0.0.1", replacementServer.port))
                    val events = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(25L).toList()
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
    fun timeoutAndCancellationRetainProvisionalClockForLateSuccess() {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(null, null),
        ).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))

                val timedOutEvents = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(23L).toList()
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
                    service.subscriptionEvents(24L).toList()
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
                    service.subscriptionEvents(9L).collect { event ->
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
                    service.subscriptionEvents(10L).collect { event ->
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
    fun stoppedBeforeUnsubscribeAcknowledgementRemainsTheSingleTerminalTransition() {
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
                withTimeout(1_000L) { collector.join() }
                server.replyToCapturedPostHandshakeRequest()
                assertTrue(withTimeout(1_000L) { unsubscribe.await() } is HtspResult.Ok)
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
    fun replacementAndTransportLossAppendDistinctPayloadFreeTerminalEvents() {
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
                        "subscriptionStatus",
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
                        "subscriptionStatus",
                        statusFields(12L, "current"),
                    )
                    withTimeout(1_000L) { while (closedEvents.isEmpty()) delay(1L) }
                    replacementServer.closeClientTransport()
                    withTimeout(1_000L) { closedCollector.join() }
                    assertEquals(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.TRANSPORT_CLOSED,
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
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            runBlocking {
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                val collector = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(13L).toList()
                }
                collector.cancel()
                assertTrue(runCatching { collector.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
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

    private fun assertSameResult(
        expected: HtspResult<*>,
        actual: HtspResult<*>,
    ) {
        assertTrue(actual === expected)
    }
}
