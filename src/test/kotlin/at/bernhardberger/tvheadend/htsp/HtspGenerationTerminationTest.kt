package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.htsp.requests.hello
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class HtspGenerationTerminationTest : HtspServiceLifecycleFixture() {
    @Test
    fun metadataTokenFromFailedHandshakeEndsWithoutFailureEvent() = runBlocking {
        checkHandshakeMetadataTermination(closeDuringHandshake = false)
    }

    @Test
    fun metadataTokenFromHandshakeEndsWhenOwnerCloses() = runBlocking {
        checkHandshakeMetadataTermination(closeDuringHandshake = true)
    }

    private suspend fun checkHandshakeMetadataTermination(closeDuringHandshake: Boolean) = kotlinx.coroutines.coroutineScope {
        val authenticateGate = CountDownLatch(1)
        FakeHtspServer(
            respondToHello = true,
            authFields = mapOf("noaccess" to 1L),
            authenticateResponseGate = authenticateGate,
        ).use { server ->
            val service = service()
            val metadata = async(start = CoroutineStart.UNDISPATCHED) {
                service.events.first { it is HtspTransportEvent.ServerMessage } as HtspTransportEvent.ServerMessage
            }
            val failure = async(start = CoroutineStart.UNDISPATCHED) {
                service.events.first { it is HtspTransportEvent.ConnectionFailure }
            }
            try {
                val connecting = async(Dispatchers.IO) { service.connect(HtspEndpoint("127.0.0.1", server.port)) }
                assertTrue(server.authenticateRequestReceived.await(5, TimeUnit.SECONDS))
                server.sendServerMessage("initialSyncCompleted")
                withTimeout(5_000L) {
                    val generation = metadata.await().generation
                    assertFalse(service.connectionState.value is HtspConnectionState.Connected)
                    val termination = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                    assertFalse(termination.isCompleted)
                    if (closeDuringHandshake) service.close() else authenticateGate.countDown()
                    assertFalse(connecting.await() is HtspConnectOutcome.Connected)
                    assertSame(HtspGenerationTermination.Disconnected, termination.await())
                    assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(generation))
                    assertFalse(failure.isCompleted)
                }
            } finally {
                authenticateGate.countDown()
                metadata.cancelAndJoin()
                failure.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun failureIsLatchedBeforeAndAfterEndAndSurvivesLateDisconnect() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                val failureEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    service.events.first { it is HtspTransportEvent.ConnectionFailure } as HtspTransportEvent.ConnectionFailure
                }
                val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    service.awaitTermination(generation).also {
                        assertFalse(Thread.holdsLock(service.attemptLockForTest()))
                    }
                }
                assertFalse(waiting.isCompleted)
                server.closeClientTransport()
                withTimeout(5_000L) {
                    val event = failureEvent.await()
                    val result = waiting.await() as HtspGenerationTermination.Failed
                    assertSame(generation, event.generation)
                    assertSame(event.failure, result.failure)
                    assertSame(result, service.awaitTermination(generation))
                    assertTrue(service.disconnect(generation))
                    assertSame(result, service.awaitTermination(generation))
                }
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun requestTimeoutLatchesThePublishedConnectionTimeoutFailure() = runBlocking {
        FakeHtspServer(respondToHello = true, captureOnePostHandshakeRequest = true).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                val failureEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    service.events.first { it is HtspTransportEvent.ConnectionFailure } as HtspTransportEvent.ConnectionFailure
                }
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                assertTrue(retiringRequestTimesOut(service))
                withTimeout(5_000L) {
                    val event = failureEvent.await()
                    val result = waiting.await() as HtspGenerationTermination.Failed
                    assertSame(generation, event.generation)
                    assertEquals(HtspTransportFailureKind.CONNECTION_TIMEOUT, result.failure.kind)
                    assertSame(event.failure, result.failure)
                    assertSame(result, service.awaitTermination(generation))
                }
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun ownerDisconnectBetweenTimeoutRetirementAndFailAllLatchesWithoutFailureEvent() = runBlocking {
        FakeHtspServer(respondToHello = true, captureOnePostHandshakeRequest = true).use { server ->
            val timeoutRecorded = CompletableDeferred<Unit>()
            val releaseTimeout = CountDownLatch(1)
            val service = service(beforeStatePublication = { state ->
                if (state is HtspConnectionState.Error && state.failure.kind == HtspTransportFailureKind.CONNECTION_TIMEOUT) {
                    // markTransportGone has retired the live transport, but has not closed
                    // its socket yet. This existing hook runs outside both monitor locks.
                    timeoutRecorded.complete(Unit)
                    check(releaseTimeout.await(5, TimeUnit.SECONDS))
                }
            })
            val failureEvent = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                service.events.first { it is HtspTransportEvent.ConnectionFailure }
            }
            try {
                val generation = service.connectedGeneration(server)
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                val request = async(Dispatchers.IO) { retiringRequestTimesOut(service) }
                withTimeout(5_000L) {
                    timeoutRecorded.await()
                    assertTrue(service.currentConnectionState() is HtspConnectionState.Error)
                    assertSame(generation, service.currentLiveConnection?.generation)
                    assertFalse(waiting.isCompleted)
                    assertTrue(service.disconnect(generation))
                    assertSame(HtspGenerationTermination.Disconnected, waiting.await())
                    releaseTimeout.countDown()
                    assertTrue(request.await())
                    assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(generation))
                    // disconnect joined the reader; the request also finished. Neither
                    // can publish a late failure for the retired attempt.
                    assertFalse(failureEvent.isCompleted)
                }
            } finally {
                releaseTimeout.countDown()
                failureEvent.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun terminationDoesNotWaitForEarlierMetadataToDrain() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            val generation = service.connectedGeneration(server)
            val received = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val failureEvent = async(start = CoroutineStart.UNDISPATCHED) {
                service.events.first {
                    if (it is HtspTransportEvent.ServerMessage) {
                        received.complete(Unit)
                        release.await()
                    }
                    it is HtspTransportEvent.ConnectionFailure
                } as HtspTransportEvent.ConnectionFailure
            }
            try {
                server.sendServerMessage("initialSyncCompleted")
                withTimeout(5_000L) {
                    received.await()
                    server.closeClientTransport()
                    val result = service.awaitTermination(generation) as HtspGenerationTermination.Failed
                    assertFalse(failureEvent.isCompleted)
                    release.complete(Unit)
                    assertSame(result.failure, failureEvent.await().failure)
                }
            } finally {
                failureEvent.cancelAndJoin()
                service.close()
            }
        }
    }

    @Test
    fun unsupportedDirectHelloLatchesThePublishedFailure() = runBlocking {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(mapOf("htspversion" to 35L, "challenge" to ByteArray(32))),
        ).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                val event = async(start = CoroutineStart.UNDISPATCHED) {
                    service.events.first { it is HtspTransportEvent.ConnectionFailure } as HtspTransportEvent.ConnectionFailure
                }
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                assertSame(HtspResult.TransportUnavailable, service.hello(44L, "client", 1_000L, generation))
                withTimeout(5_000L) {
                    val result = waiting.await() as HtspGenerationTermination.Failed
                    assertEquals(HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION, result.failure.kind)
                    assertSame(event.await().failure, result.failure)
                    assertSame(result, service.awaitTermination(generation))
                }
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun disconnectEndsTransportButKeepsGenerationIdentity() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                assertTrue(service.disconnect(generation))
                assertTrue(service.isCurrent(generation))
                assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { waiting.await() })
                assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(generation))
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun closeCompletesPendingAndLateAwaits() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                assertTrue(service.close(generation))
                assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { waiting.await() })
                assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(generation))
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun differentEndpointEndsOldGenerationWhileReuseLeavesItLive() = runBlocking {
        FakeHtspServer(respondToHello = true).use { first ->
            FakeHtspServer(respondToHello = true).use { second ->
                val service = service()
                try {
                    val old = service.connectedGeneration(first)
                    val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(old) }
                    assertSame(old, service.connectedGeneration(first))
                    assertFalse(waiting.isCompleted)
                    val replacement = service.connectedGeneration(second)
                    assertNotSame(old, replacement)
                    assertFalse(service.isCurrent(old))
                    assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { waiting.await() })
                    assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(old))
                    val liveWait = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(replacement) }
                    assertFalse(liveWait.isCompleted)
                    service.close()
                    assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { liveWait.await() })
                } finally {
                    service.close()
                }
            }
        }
    }

    @Test
    fun failedReplacementStillEndsOldGenerationWithoutFailure() = runBlocking {
        FakeHtspServer(respondToHello = true).use { first ->
            FakeHtspServer(respondToHello = true, helloReplyFields = mapOf("htspversion" to 35L)).use { second ->
                val service = service()
                try {
                    val old = service.connectedGeneration(first)
                    assertTrue(service.connect(HtspEndpoint("127.0.0.1", second.port)) is HtspConnectOutcome.Failed)
                    assertFalse(service.isCurrent(old))
                    assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { service.awaitTermination(old) })
                } finally {
                    service.close()
                }
            }
        }
    }

    @Test
    fun foreignTokensFailBeforeSuspending() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            val other = service()
            try {
                val foreign = other.connectedGeneration(server)
                for (generation in listOf(HtspConnectionGeneration(), foreign)) {
                    var rejected = false
                    val call = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            service.awaitTermination(generation)
                            fail<Unit>("Foreign generation was accepted")
                        } catch (_: IllegalArgumentException) {
                            rejected = true
                        }
                    }
                    assertTrue(rejected, "Ownership must be checked before the first suspension")
                    assertTrue(call.isCompleted)
                }
            } finally {
                service.close()
                other.close()
            }
        }
    }

    @Test
    fun cancellingOneAwaitLeavesTheLatchAndOtherWaitersUsable() = runBlocking {
        FakeHtspServer(respondToHello = true).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                val survivor = async(start = CoroutineStart.UNDISPATCHED) { service.awaitTermination(generation) }
                cancelled.cancelAndJoin()
                assertTrue(cancelled.isCancelled)
                assertEquals(0, service.metadataCollectorCount())
                assertFalse(survivor.isCompleted)
                service.disconnect()
                assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { survivor.await() })
                assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(generation))
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun malformedDirectHelloEndsWithoutFailureWhileIdentityRemainsCurrent() = runBlocking {
        FakeHtspServer(
            respondToHello = true,
            postHandshakeReplyPlan = listOf(mapOf("htspversion" to 44L, "challenge" to ByteArray(31))),
        ).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                assertSame(HtspResult.MalformedReply, service.hello(44L, "client", 1_000L, generation))
                assertTrue(service.isCurrent(generation))
                assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { service.awaitTermination(generation) })
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun cancellingWrittenDirectHelloEndsWithoutFailure() = runBlocking {
        FakeHtspServer(respondToHello = true, postHandshakeReplyPlan = listOf(null)).use { server ->
            val service = service()
            try {
                val generation = service.connectedGeneration(server)
                val call = async(Dispatchers.IO) { service.hello(44L, "client", 5_000L, generation) }
                assertTrue(server.awaitPostHandshakeRequestCount(1, 1_000L))
                call.cancelAndJoin()
                assertTrue(service.isCurrent(generation))
                assertSame(HtspGenerationTermination.Disconnected, withTimeout(5_000L) { service.awaitTermination(generation) })
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun defaultImplementationUsesCurrentStateAndDoesNotLatchOrCheckOwnership() = runBlocking {
        val generation = HtspConnectionGeneration()
        val fake = FakeConnection()
        val failure = HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE)
        val endings = listOf(
            HtspConnectionState.Error(failure),
            HtspConnectionState.Disconnected,
            HtspConnectionState.Closed,
            HtspConnectionState.Connecting("host", 9982),
            connectedState(HtspConnectionGeneration()),
        )
        for (ending in endings) {
            fake.connectionState.value = connectedState(generation)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { fake.awaitTermination(generation) }
            assertFalse(waiting.isCompleted)
            fake.connectionState.value = ending
            val expected = if (ending is HtspConnectionState.Error) HtspGenerationTermination.Failed(failure)
            else HtspGenerationTermination.Disconnected
            assertEquals(expected, withTimeout(5_000L) { waiting.await() })
            assertEquals(expected, fake.awaitTermination(generation))
        }
        fake.connectionState.value = HtspConnectionState.Error(failure)
        assertEquals(HtspGenerationTermination.Failed(failure), fake.awaitTermination(HtspConnectionGeneration()))
    }

    @Test
    fun replacedEndedGenerationIsNotRetainedByTheService() = runBlocking {
        FakeHtspServer(respondToHello = true).use { first ->
            FakeHtspServer(respondToHello = true).use { replacement ->
                val service = service()
                try {
                    val reference = endedGenerationReference(service, first)
                    service.connectedGeneration(replacement)
                    withTimeout(5_000L) {
                        // Assumes the test JVM honours System.gc() (no DisableExplicitGC or Epsilon).
                        repeat(50) {
                            if (reference.get() != null) { System.gc(); delay(20L) }
                        }
                        assertNull(reference.get())
                    }
                } finally {
                    service.close()
                }
            }
        }
    }

    private suspend fun HtspService.connectedGeneration(server: FakeHtspServer): HtspConnectionGeneration =
        (connect(HtspEndpoint("127.0.0.1", server.port)) as HtspConnectOutcome.Connected).connection.generation

    private suspend fun retiringRequestTimesOut(service: HtspService): Boolean = try {
        service.request("getProfiles", policy = HtspReplyPolicy(timeoutMs = 1_000L, retireOnTimeout = true))
        false
    } catch (_: SocketTimeoutException) {
        true
    }

    private suspend fun endedGenerationReference(service: HtspService, server: FakeHtspServer): WeakReference<HtspConnectionGeneration> {
        val generation = service.connectedGeneration(server)
        service.disconnect()
        assertSame(HtspGenerationTermination.Disconnected, service.awaitTermination(generation))
        return WeakReference(generation)
    }

    private fun connectedState(generation: HtspConnectionGeneration) = HtspConnectionState.Connected(
        "host", 9982, HtspLiveConnection(generation, 44L, null, HtspServerFacts()),
    )

    private class FakeConnection : HtspConnection {
        override val connectionState = MutableStateFlow<HtspConnectionState>(HtspConnectionState.Disconnected)
        override val events = emptyFlow<HtspTransportEvent>()
        override fun subscriptionEvents(subscriptionId: Long) = emptyFlow<HtspSubscriptionEvent>()
        override fun subscriptionEvents(subscriptionId: Long, expectedGeneration: HtspConnectionGeneration) =
            emptyFlow<HtspSubscriptionEvent>()
        override suspend fun <R> execute(request: HtspRequest<R>, timeoutMs: Long, expectedGeneration: HtspConnectionGeneration?): HtspResult<R> =
            HtspResult.TransportUnavailable
        override suspend fun connect(endpoint: HtspEndpoint, options: HtspConnectOptions): HtspConnectOutcome = error("Unused")
        override fun isCurrent(generation: HtspConnectionGeneration) = false
        override suspend fun disconnect(expectedGeneration: HtspConnectionGeneration?) = false
        override suspend fun close(expectedGeneration: HtspConnectionGeneration?) = false
    }
}
