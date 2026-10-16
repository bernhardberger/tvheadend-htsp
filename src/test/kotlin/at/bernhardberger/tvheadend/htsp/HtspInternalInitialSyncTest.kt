package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

internal class HtspInternalInitialSyncTest : HtspServiceLifecycleFixture() {
    @Test
    fun oldGenerationMarkerCannotCompleteNewInternalObserver() {
        val old = HtspConnectionGeneration()
        val newer = HtspConnectionGeneration()
        val observer = HtspInitialSyncObserver(newer)
        val monitor = HtspAttemptMonitor {}
        monitor.withAttemptLock {
            assertFalse(observer.offer(HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, old, 1L)))
            assertFalse(observer.completed)
            assertFalse(observer.failed)
            assertTrue(observer.offer(HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, newer, 1L)))
            assertTrue(observer.completed)
        }
    }

    @Test
    fun internalObserverRetainsMarkerBeforeAcknowledgementWithoutCollectingEvents() = runBlocking {
        FakeHtspServer(respondToHello = true, captureOnePostHandshakeRequest = true).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val sync = async(start = CoroutineStart.UNDISPATCHED) { service.enableAsyncMetadataAwaitingInitialSync() }
            try {
                assertEquals(0, service.metadataCollectorCount())
                assertEquals(1, service.initialSyncObserverCount())
                assertTrue(server.postHandshakeRequestReceived.await(2, TimeUnit.SECONDS))
                server.sendServerMessage("initialSyncCompleted")
                assertFalse(sync.isCompleted)
                server.replyToCapturedPostHandshakeRequest()
                assertEquals(HtspResult.Ok(Unit), withTimeout(2_000L) { sync.await() })
                assertEquals(0, service.initialSyncObserverCount())
            } finally {
                sync.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun internalObserverReturnsMalformedAcknowledgementAndTimeoutAndAlwaysUnregisters() = runBlocking {
        for ((reply, expected) in listOf(mapOf("noaccess" to "bad") to HtspResult.MalformedReply, emptyMap<String, Any?>() to HtspResult.Timeout)) {
            FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(reply)).use { server ->
                val service = service()
                service.connect(HtspEndpoint("127.0.0.1", server.port))
                try {
                    assertSame(expected, service.enableAsyncMetadataAwaitingInitialSync(timeoutMs = 250L))
                    assertEquals(0, service.metadataCollectorCount())
                    assertEquals(0, service.initialSyncObserverCount())
                } finally {
                    service.close()
                }
            }
        }
    }

    @Test
    fun internalObserverFailsOnTransportLossAndGenerationReplacement() = runBlocking {
        for (replace in listOf(false, true)) {
            FakeHtspServer(respondToHello = true, captureOnePostHandshakeRequest = true).use { server ->
                FakeHtspServer(respondToHello = true).use { replacement ->
                    val service = service()
                    service.connect(HtspEndpoint("127.0.0.1", server.port))
                    val sync = async(start = CoroutineStart.UNDISPATCHED) { service.enableAsyncMetadataAwaitingInitialSync() }
                    try {
                        assertTrue(server.postHandshakeRequestReceived.await(2, TimeUnit.SECONDS))
                        if (replace) service.connect(HtspEndpoint("127.0.0.1", replacement.port)) else server.closeClientTransport()
                        assertSame(HtspResult.TransportUnavailable, withTimeout(2_000L) { sync.await() })
                        assertEquals(0, service.initialSyncObserverCount())
                    } finally {
                        sync.cancelAndJoin()
                        service.close()
                    }
                }
            }
        }
    }

    @Test
    fun callerCancellationPropagatesAndUnregistersInternalObserver() = runBlocking {
        FakeHtspServer(respondToHello = true, captureOnePostHandshakeRequest = true).use { server ->
            val service = service()
            service.connect(HtspEndpoint("127.0.0.1", server.port))
            val cancelled = CompletableDeferred<Unit>()
            val sync = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    service.enableAsyncMetadataAwaitingInitialSync()
                    fail<Unit>("Cancellation must not return an outcome")
                } catch (expected: CancellationException) {
                    cancelled.complete(Unit)
                    throw expected
                }
            }
            try {
                assertEquals(1, service.initialSyncObserverCount())
                sync.cancelAndJoin()
                assertTrue(cancelled.isCompleted)
                assertEquals(0, service.initialSyncObserverCount())
                assertEquals(0, service.metadataCollectorCount())
            } finally {
                sync.cancelAndJoin()
                service.close()
            }
        }
    }
}
