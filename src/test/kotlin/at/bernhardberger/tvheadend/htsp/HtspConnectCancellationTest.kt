package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.*
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class HtspConnectCancellationTest : HtspServiceLifecycleFixture() {
    @Test
    fun cancelledCallerCannotReuseOrRetireLiveConnection() = runBlocking {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf("time" to 123L, "timezone" to 0L),
        ).use { server ->
            val service = service()
            val endpoint = HtspEndpoint("127.0.0.1", server.port)
            try {
                val connected = service.connect(endpoint) as HtspConnectOutcome.Connected
                for (direct in listOf(false, true)) {
                    var cancelled = false
                    launch {
                        currentCoroutineContext().cancel()
                        try {
                            if (direct) {
                                service.establish(HtspConnectionParameters(endpoint, HtspConnectOptions(), HtspClientIdentity.Default))
                            } else {
                                service.connect(endpoint)
                            }
                        } catch (_: CancellationException) {
                            cancelled = true
                        }
                    }.join()
                    assertTrue(cancelled, "The call itself must propagate cancellation")
                    assertSame(connected.connection, service.currentLiveConnection)
                }
                val reused = service.connect(endpoint) as HtspConnectOutcome.Connected
                assertSame(connected.connection, reused.connection)
                assertTrue(service.getSysTime(timeoutMs = 2_000L) is HtspResult.Ok)
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun cancellationWinsOverGenericFailure() = runBlocking {
        val service = service(afterConnectionAdmission = {
            currentCoroutineContext().cancel()
            throw IOException("fixture failure")
        })
        try {
            var cancelled = false
            launch {
                try {
                    service.connect(HtspEndpoint("127.0.0.1", 1))
                } catch (_: CancellationException) {
                    cancelled = true
                }
            }.join()
            assertTrue(cancelled, "An exception after caller cancellation must not become Failed")
        } finally {
            service.close()
        }
    }

    @Test
    fun alreadyCancelledCallerCannotReceiveClosedFailure() = runBlocking {
        val service = service()
        service.close()
        val endpoint = HtspEndpoint("127.0.0.1", 1)
        var cancelled = false
        launch {
            currentCoroutineContext().cancel()
            try {
                service.connect(endpoint)
            } catch (_: CancellationException) {
                cancelled = true
            }
        }.join()
        assertTrue(cancelled)
        assertEquals(
            HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.CLOSED)),
            service.connect(endpoint),
        )
    }

    @Test
    fun cancellationAfterConnectedSnapshotRetiresOnlyTheNewAttempt() = runBlocking {
        FakeHtspServer(respondToHello = true, expectedConnections = 2).use { server ->
            val snapshotCaptured = CompletableDeferred<Unit>()
            val requestWritten = CompletableDeferred<Unit>()
            val sockets = CopyOnWriteArrayList<Socket>()
            val failures = CopyOnWriteArrayList<HtspTransportEvent.ConnectionFailure>()
            val service = service(
                afterConnectedSnapshot = {
                    if (snapshotCaptured.complete(Unit)) awaitCancellation()
                },
                afterRequestFrameWritten = { method ->
                    if (method == "getSysTime") requestWritten.complete(Unit)
                },
                afterPublicationCurrencyCheck = { event ->
                    if (event is HtspTransportEvent.ConnectionFailure) failures += event
                },
                socketFactory = { Socket().also { sockets += it } },
            )
            try {
                var cancelled = false
                val connecting = launch {
                    try {
                        service.connect(HtspEndpoint("127.0.0.1", server.port))
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                }
                withTimeout(5_000L) {
                    snapshotCaptured.await()
                    val published = service.connectionState.value as HtspConnectionState.Connected
                    val generation = published.connection.generation
                    val reader = serviceReaderJob(service)
                    val termination = async(start = CoroutineStart.UNDISPATCHED) {
                        service.awaitTermination(generation)
                    }
                    val subscription = async(start = CoroutineStart.UNDISPATCHED) {
                        service.subscriptionEvents(1L, generation).toList()
                    }
                    val pending = async {
                        service.getSysTime(timeoutMs = 60_000L, expectedGeneration = generation)
                    }
                    requestWritten.await()
                    assertFalse(termination.isCompleted)
                    connecting.cancelAndJoin()
                    assertTrue(cancelled)
                    assertTrue(sockets.single().isClosed)
                    assertTrue(reader.isCompleted)
                    assertNull(service.currentLiveConnection)
                    assertEquals(HtspConnectionState.Disconnected, service.connectionState.value)
                    assertSame(HtspGenerationTermination.Disconnected, termination.await())
                    assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(generation))
                    assertSame(HtspResult.TransportUnavailable, pending.await())
                    assertEquals(
                        listOf(HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.LOCAL_RETIREMENT)),
                        subscription.await(),
                    )
                    // The retired reader and request have finished: no late failure can follow.
                    assertTrue(failures.isEmpty())
                    val next = service.connect(HtspEndpoint("127.0.0.1", server.port)) as HtspConnectOutcome.Connected
                    assertNotSame(generation, next.connection.generation)
                    assertSame(next.connection, service.currentLiveConnection)
                    assertEquals(next.connection, (service.connectionState.value as HtspConnectionState.Connected).connection)
                    assertFalse(sockets.last().isClosed)
                }
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun cancellationDuringReuseLeavesLiveTransportAndGenerationUntouched() = runBlocking {
        FakeHtspServer(
            respondToHello = true,
            captureOnePostHandshakeRequest = true,
            postHandshakeReplyFields = mapOf("time" to 123L, "timezone" to 0L),
        ).use { server ->
            val cancelOnReuse = AtomicReference<Job?>()
            val socket = object : Socket() {
                override fun isConnected(): Boolean {
                    // Cancel while the reuse predicate checks its existing socket.
                    cancelOnReuse.getAndSet(null)?.cancel()
                    return super.isConnected()
                }
            }
            val service = service(socketFactory = { socket })
            try {
                val endpoint = HtspEndpoint("127.0.0.1", server.port)
                val connected = service.connect(endpoint) as HtspConnectOutcome.Connected
                val state = service.connectionState.value
                val reader = serviceReaderJob(service)
                val termination = async(start = CoroutineStart.UNDISPATCHED) {
                    service.awaitTermination(connected.connection.generation)
                }
                try {
                    val attemptBefore = service.currentConnectionAttemptId()
                    var cancelled = false
                    launch {
                        cancelOnReuse.set(currentCoroutineContext()[Job])
                        try {
                            service.connect(endpoint)
                        } catch (_: CancellationException) {
                            cancelled = true
                        }
                    }.join()
                    assertTrue(cancelled)
                    assertNull(cancelOnReuse.get(), "Cancellation must occur during the second connect")
                    // A fresh attempt would have been admitted with a new ID; reuse admits none.
                    assertEquals(attemptBefore, service.currentConnectionAttemptId())
                    assertSame(state, service.connectionState.value)
                    assertSame(connected.connection, service.currentLiveConnection)
                    assertSame(reader, serviceReaderJob(service))
                    assertTrue(reader.isActive)
                    assertFalse(socket.isClosed)
                    assertFalse(termination.isCompleted)
                    assertTrue(service.getSysTime(timeoutMs = 2_000L) is HtspResult.Ok)
                } finally {
                    termination.cancelAndJoin()
                }
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun activeCallerStillReceivesGenericFailure() = runBlocking {
        val service = service(socketFactory = { throw IOException("fixture failure") })
        try {
            assertEquals(
                HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE)),
                service.connect(HtspEndpoint("127.0.0.1", 1)),
            )
        } finally {
            service.close()
        }
    }
}
