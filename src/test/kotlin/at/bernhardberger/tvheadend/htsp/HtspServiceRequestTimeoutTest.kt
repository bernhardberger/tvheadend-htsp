package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class HtspServiceRequestTimeoutTest : HtspServiceLifecycleFixture() {

    @Test
    fun eofDuringTypedRequestReturnsTransportUnavailableForCurrentGeneration() = runBlocking {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
        ).use { server ->
            val service = service()
            try {
                service.connect("127.0.0.1", server.port)
                val generation = requireNotNull(service.liveConnection.value).generation
                val events = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(47L, generation).toList()
                }
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    service.getProfiles(timeoutMs = 2_000L, expectedGeneration = generation)
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                server.closeClientTransport()

                assertSame(HtspResult.TransportUnavailable, withTimeout(1_000L) { request.await() })
                assertTrue(service.isCurrent(generation))
                assertNull(service.liveConnection.value)
                assertSame(HtspResult.TransportUnavailable, service.getProfiles(expectedGeneration = generation))
                assertEquals(
                    listOf(HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.REMOTE_EOF)),
                    withTimeout(1_000L) { events.await() },
                )
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun completedOrdinaryReplySurvivesSameGenerationEofBeforeRecapture() = runBlocking {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = emptyMap(),
        ).use { server ->
            val recaptureReached = CompletableDeferred<Unit>()
            val resumeRecapture = CompletableDeferred<Unit>()
            val service = service(beforeTypedRecapture = { request ->
                if (request is GetProfilesRequest) {
                    recaptureReached.complete(Unit)
                    resumeRecapture.await()
                }
            })
            try {
                service.connect("127.0.0.1", server.port)
                val generation = requireNotNull(service.liveConnection.value).generation
                val failure = async(start = CoroutineStart.UNDISPATCHED) {
                    service.events.first { it is HtspTransportEvent.ConnectionFailure }
                }
                val request = async { service.getProfiles(expectedGeneration = generation) }
                withTimeout(1_000L) { recaptureReached.await() }
                server.closeClientTransport()
                withTimeout(1_000L) { failure.await() }
                assertNull(service.liveConnection.value)
                resumeRecapture.complete(Unit)
                assertEquals(HtspResult.Ok(GetProfilesResponse(null)), withTimeout(1_000L) { request.await() })
                assertTrue(service.isCurrent(generation))
            } finally {
                resumeRecapture.complete(Unit)
                service.close()
            }
        }
    }

    @Test
    fun watchdogDoesNotChargePreviousIdleTimeOrOverflowLargeTimeout() = runBlocking {
        for (responseTimeoutMs in listOf(100L, Long.MAX_VALUE)) {
            FakeHtspServer(
                respondToHello = true,
                captureOnePostHandshakeRequest = true,
            ).use { server ->
                val clock = AtomicLong()
                val readCycles = AtomicInteger()
                val service = service(nanoTime = clock::get, beforeFrameRead = { readCycles.incrementAndGet() })
                try {
                    service.connect(
                        "127.0.0.1", server.port,
                        responseTimeoutMs = responseTimeoutMs, soTimeoutMs = 25,
                    )
                    clock.set(10_000_000_000L)
                    var previousCycle = readCycles.get()
                    withTimeout(1_000L) {
                        while (readCycles.get() == previousCycle) delay(1L)
                    }
                    val request = async(start = CoroutineStart.UNDISPATCHED) {
                        service.getProfiles(timeoutMs = 2_000L)
                    }
                    assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                    clock.addAndGet(199_000_000L)
                    previousCycle = readCycles.get()
                    withTimeout(1_000L) {
                        while (readCycles.get() == previousCycle) delay(1L)
                    }
                    assertTrue(service.connectionState.value is HtspConnectionState.Connected)
                    assertTrue(!request.isCompleted)
                    server.replyToCapturedPostHandshakeRequest()
                    assertEquals(HtspResult.Ok(GetProfilesResponse(null)), withTimeout(1_000L) { request.await() })
                } finally {
                    service.close()
                }
            }
        }
    }

    @Test
    fun watchdogDoesNotChargeTimeQueuedOnIoDispatcher() = runBlocking {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            FakeHtspServer(respondToHello = true, captureOnePostHandshakeRequest = true).use { server ->
                val clock = AtomicLong()
                val readCycles = AtomicInteger()
                val service = service(
                    ioDispatcher = dispatcher,
                    nanoTime = clock::get,
                    beforeFrameRead = { readCycles.incrementAndGet() },
                )
                val occupied = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    service.connect("127.0.0.1", server.port, responseTimeoutMs = 100L, soTimeoutMs = 25)
                    val blocker = launch(dispatcher) {
                        occupied.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                    assertTrue(occupied.await(1, TimeUnit.SECONDS))
                    val request = async(start = CoroutineStart.UNDISPATCHED) {
                        service.getProfiles(timeoutMs = 2_000L)
                    }
                    clock.set(300_000_000L)
                    val previousCycle = readCycles.get()
                    withTimeout(1_000L) {
                        while (readCycles.get() == previousCycle && service.liveConnection.value != null) delay(1L)
                    }
                    assertNotNull(service.liveConnection.value)
                    assertTrue(!request.isCompleted)
                    assertEquals(emptyList<String>(), server.postHandshakeMethods())
                    release.countDown()
                    blocker.join()
                    assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                    server.replyToCapturedPostHandshakeRequest()
                    assertTrue(withTimeout(1_000L) { request.await() } is HtspResult.Ok)
                } finally {
                    release.countDown()
                    service.close()
                }
            }
        }
    }

    @Test
    fun persistentPartialFrameRetiresTransportWithAndWithoutPendingRequest() = runBlocking {
        for (withPendingRequest in listOf(false, true)) {
            FakeHtspServer(
                respondToHello = true,
                captureOnePostHandshakeRequest = withPendingRequest,
            ).use { server ->
                val service = service()
                try {
                    service.connect("127.0.0.1", server.port, responseTimeoutMs = 100L, soTimeoutMs = 25)
                    val events = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(48L).toList()
                    }
                    val failure = async(start = CoroutineStart.UNDISPATCHED) {
                        service.events.first { it is HtspTransportEvent.ConnectionFailure }
                    }
                    val request = if (withPendingRequest) {
                        async(start = CoroutineStart.UNDISPATCHED) { service.getProfiles(timeoutMs = 2_000L) }
                    } else {
                        null
                    }
                    if (withPendingRequest) {
                        assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))
                    }
                    server.sendRaw(byteArrayOf(0))

                    assertEquals(
                        listOf(HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.TIMEOUT)),
                        withTimeout(1_500L) { events.await() },
                    )
                    val transportFailure = withTimeout(1_000L) { failure.await() } as HtspTransportEvent.ConnectionFailure
                    assertEquals(HtspTransportFailureKind.CONNECTION_TIMEOUT, transportFailure.failure.kind)
                    assertNull(service.liveConnection.value)
                    request?.let { assertSame(HtspResult.TransportUnavailable, withTimeout(1_000L) { it.await() }) }
                } finally {
                    service.close()
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
                assertTrue(service.connectionState.value is HtspConnectionState.Connected)
                service.disconnect()
            }
        }
    }

    @Test
    fun pendingRequestIdleWatchdogFailsTransportBeforeRequestTimeout() {
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
                    responseTimeoutMs = 100,
                    soTimeoutMs = 25,
                )
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(37L).toList()
                }

                val request = async(Dispatchers.IO) {
                    runCatching {
                        service.request(
                            method = "silentWatchdogProbe",
                            timeoutMs = 2_000L,
                            disconnectOnTimeout = false,
                        )
                    }.exceptionOrNull()
                }
                assertTrue(server.postHandshakeRequestReceived.await(1, TimeUnit.SECONDS))

                val failure = withTimeout(1_000L) { request.await() }
                assertTrue(failure is SocketTimeoutException)
                assertTrue(failure !is HtspRequestTimeoutException)
                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.TIMEOUT,
                        ),
                    ),
                    withTimeout(1_000L) { subscription.await() },
                )
                withTimeout(1_000L) {
                    service.connectionState.first { state -> state is HtspConnectionState.Disconnected }
                }
                assertNull(service.liveConnection.value)
            }
        }
    }

    @Test
    fun noPendingIdleTimeoutCyclesKeepConnectionLiveAndResponsive() {
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
                    // Wider response bounds tolerate slow CI scheduling while 25 ms socket timeouts still exercise idle cycles.
                    responseTimeoutMs = 500,
                    soTimeoutMs = 25,
                )

                val leftConnected = withTimeoutOrNull(350L) {
                    service.connectionState.first { state -> state !is HtspConnectionState.Connected }
                }
                assertNull(leftConnected)
                assertTrue(service.connectionState.value is HtspConnectionState.Connected)

                val request = async(Dispatchers.IO) {
                    service.request(
                        method = "afterIdleProbe",
                        timeoutMs = 2_000L,
                        disconnectOnTimeout = false,
                    )
                }
                assertTrue(server.postHandshakeRequestReceived.await(2, TimeUnit.SECONDS))
                server.replyToCapturedPostHandshakeRequest()
                assertEquals("afterIdleProbe", withTimeout(2_000L) { request.await() }.method)
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
                assertTrue(service.connectionState.value is HtspConnectionState.Connected)
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
                val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                    service.subscriptionEvents(40L).toList()
                }

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
                assertEquals(
                    listOf(
                        HtspSubscriptionEvent.Terminated(
                            HtspSubscriptionTermination.TIMEOUT,
                        ),
                    ),
                    withTimeout(1_000L) { subscription.await() },
                )
                withTimeout(1_000L) {
                    service.connectionState.first { it is HtspConnectionState.Disconnected }
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
                assertTrue(service.connectionState.value is HtspConnectionState.Connected)
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
}
