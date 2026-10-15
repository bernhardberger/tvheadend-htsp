package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.*
import java.io.IOException
import kotlinx.coroutines.*
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
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service(afterConnectedSnapshot = { currentCoroutineContext().cancel() })
            try {
                var cancelled = false
                launch {
                    try {
                        service.connect(HtspEndpoint("127.0.0.1", server.port))
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                }.join()
                assertTrue(cancelled)
                assertNull(service.currentLiveConnection)
                assertEquals(HtspConnectionState.Disconnected, service.connectionState.value)
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
