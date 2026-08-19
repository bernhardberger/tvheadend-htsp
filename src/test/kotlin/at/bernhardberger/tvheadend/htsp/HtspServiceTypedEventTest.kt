package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class HtspServiceTypedEventTest : HtspServiceLifecycleFixture() {

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
        val generation = HtspConnectionGeneration()
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
                assertTrue(service.state.value is HtspConnectionState.Connected)
                service.disconnect()
            }
        }
    }
}
