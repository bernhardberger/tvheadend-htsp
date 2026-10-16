package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspEventDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun onlyOwnGenerationMetadataStampsIdleProgress() {
        val clock = AtomicLong(5_000_000_000L)
        val own = HtspConnectionGeneration()
        val stale = HtspConnectionGeneration()
        val observer = HtspInitialSyncObserver(own, clock::get)
        val monitor = HtspAttemptMonitor {}
        assertEquals(1_000L, observer.remainingIdleMs(1_000L))
        clock.addAndGet(600_000_000L)
        monitor.withAttemptLock {
            assertFalse(observer.offer(HtspTransportEvent.ServerMessage(HtspEventDeleteMessage(1L), stale, 1L)))
            assertFalse(observer.offer(HtspTransportEvent.MalformedMetadata(stale, 1L)))
        }
        assertEquals(400L, observer.remainingIdleMs(1_000L))
        clock.addAndGet(400_000_000L)
        assertEquals(0L, observer.remainingIdleMs(1_000L))
        monitor.withAttemptLock {
            assertFalse(observer.offer(HtspTransportEvent.MalformedMetadata(own, 1L)))
        }
        assertEquals(1_000L, observer.remainingIdleMs(1_000L))
        clock.addAndGet(999_999_999L)
        assertEquals(1L, observer.remainingIdleMs(1_000L))
        monitor.withAttemptLock {
            assertFalse(observer.offer(HtspTransportEvent.ServerMessage(HtspEventDeleteMessage(1L), own, 1L)))
        }
        assertEquals(1_000L, observer.remainingIdleMs(1_000L))
        assertEquals(Long.MAX_VALUE / 1_000_000L + 1L, observer.remainingIdleMs(Long.MAX_VALUE))
    }

    @Test
    fun internalSlowSyncWithSteadyMetadataProgressReturnsOk() {
        VirtualSync(idleTimeoutMs = 1_000L).use { sync ->
            sync.acknowledgeWithMetadata()
            repeat(5) { index ->
                sync.advance(900L)
                sync.sendMetadata(malformed = index == 2)
                assertFalse(sync.result.isCompleted)
            }
            sync.server.sendServerMessage("initialSyncCompleted")
            assertEquals(HtspResult.Ok(Unit), sync.awaitResult())
            assertEquals(4_500L, sync.scheduler.currentTime)
        }
    }

    @Test
    fun internalWaitTimesOutOneIdleLimitAfterTheAcknowledgement() {
        VirtualSync(idleTimeoutMs = 1_000L).use { sync ->
            sync.advance(900L)
            sync.acknowledge()
            sync.advance(999L)
            assertFalse(sync.result.isCompleted)
            sync.advance(1L)
            assertSame(HtspResult.Timeout, sync.awaitResult())
            assertEquals(1_900L, sync.scheduler.currentTime)
            assertEquals(0, sync.service.initialSyncObserverCount())
        }
    }

    @Test
    fun internalWaitTimesOutWhenTheAcknowledgementMissesTheIdleLimit() {
        VirtualSync(idleTimeoutMs = 1_000L).use { sync ->
            sync.advance(999L)
            assertFalse(sync.result.isCompleted)
            sync.advance(1L)
            assertSame(HtspResult.Timeout, sync.awaitResult())
            assertEquals(1_000L, sync.scheduler.currentTime)
        }
    }

    @Test
    fun internalWaitEndsAfterOneIdleLimitEvenWhenTheProgressClockStandsStill() {
        VirtualSync(idleTimeoutMs = 1_000L, frozenNanoTime = 7L).use { sync ->
            sync.advance(300L)
            sync.acknowledge()
            sync.advance(999L)
            assertFalse(sync.result.isCompleted)
            sync.advance(1L)
            assertSame(HtspResult.Timeout, sync.awaitResult())
            assertEquals(1_300L, sync.scheduler.currentTime)
            assertEquals(0, sync.service.initialSyncObserverCount())
        }
        VirtualSync(idleTimeoutMs = 1_000L, frozenNanoTime = 7L).use { sync ->
            sync.advance(999L)
            assertFalse(sync.result.isCompleted)
            sync.advance(1L)
            assertSame(HtspResult.Timeout, sync.awaitResult())
            assertEquals(1_000L, sync.scheduler.currentTime)
            assertEquals(0, sync.service.initialSyncObserverCount())
        }
    }

    @Test
    fun internalOverallLimitTimesOutDespiteSteadyProgress() {
        VirtualSync(idleTimeoutMs = 1_000L, timeoutMs = 2_000L).use { sync ->
            sync.acknowledgeWithMetadata()
            repeat(2) {
                sync.advance(900L)
                sync.sendMetadata()
                assertFalse(sync.result.isCompleted)
            }
            sync.advance(200L)
            assertSame(HtspResult.Timeout, sync.awaitResult())
            assertEquals(2_000L, sync.scheduler.currentTime)
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

    /** Drives the helper on virtual time that also serves as the service's progress clock. */
    private inner class VirtualSync(
        idleTimeoutMs: Long,
        timeoutMs: Long? = null,
        frozenNanoTime: Long? = null,
    ) : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        private val published = AtomicInteger()
        val server = FakeHtspServer(respondToHello = true, captureOnePostHandshakeRequest = true)
        val service = service(
            nanoTime = { frozenNanoTime ?: (scheduler.currentTime * 1_000_000L) },
            afterPublicationCurrencyCheck = { published.incrementAndGet() },
        )
        private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val result: Deferred<HtspResult<Unit>>

        init {
            runBlocking { service.connect(HtspEndpoint("127.0.0.1", server.port)) }
            result = scope.async {
                service.enableAsyncMetadataAwaitingInitialSync(timeoutMs = timeoutMs, idleTimeoutMs = idleTimeoutMs)
            }
            scheduler.runCurrent()
            assertEquals(1, service.initialSyncObserverCount())
            assertTrue(server.postHandshakeRequestReceived.await(2, TimeUnit.SECONDS))
        }

        fun advance(ms: Long) {
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }

        /** Replies to `enableAsyncMetadata` and lets the helper observe it at the current virtual time. */
        fun acknowledge() {
            val progress = service.initialSyncProgressCount()
            server.replyToCapturedPostHandshakeRequest()
            // The helper stamps the acknowledgement only after it has consumed the reply.
            awaitReal {
                scheduler.runCurrent()
                service.initialSyncProgressCount() > progress
            }
        }

        fun acknowledgeWithMetadata() {
            acknowledge()
            sendMetadata()
        }

        /** Sends one metadata frame and waits until the reader has stamped it at the current virtual time. */
        fun sendMetadata(malformed: Boolean = false) {
            val expected = published.get() + 1
            server.sendServerMessage("channelAdd", if (malformed) mapOf("channelId" to "malformed") else channelFixture(1L))
            awaitReal { published.get() >= expected }
            service.initialSyncObserverCount() // Waits for the reader to leave the attempt lock.
            scheduler.runCurrent()
        }

        fun awaitResult(): HtspResult<Unit> {
            awaitReal {
                scheduler.runCurrent()
                result.isCompleted
            }
            return result.getCompleted()
        }

        private fun awaitReal(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) {
                check(System.nanoTime() - deadline < 0L) { "Condition not reached" }
                Thread.sleep(1L)
            }
        }

        override fun close() {
            scope.cancel()
            scheduler.runCurrent()
            runBlocking { service.close() }
            server.close()
        }
    }
}
