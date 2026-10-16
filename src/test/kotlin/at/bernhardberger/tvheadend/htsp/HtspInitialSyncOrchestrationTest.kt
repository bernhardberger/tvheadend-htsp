package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspEventDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class HtspInitialSyncOrchestrationTest {
    @Test
    fun customConnectionDefaultsToSingletonBatchesAndValidatesBeforeCollection() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        for (invalid in listOf(0, -1, Int.MIN_VALUE, 4097, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { connection.eventBatches(invalid) }
        }
        val flow = connection.eventBatches(4096)
        assertEquals(0, connection.eventSource.subscriptionCount.value)
        val expected = listOf(
            HtspTransportEvent.MalformedMetadata(generation, 2L),
            HtspTransportEvent.MetadataOverflow(generation, 1L),
            HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, HtspConnectionGeneration(), 1L),
        )
        val collector = async(start = CoroutineStart.UNDISPATCHED) { flow.take(expected.size).toList() }
        assertEquals(1, connection.eventSource.subscriptionCount.value)
        expected.forEach { connection.eventSource.emit(it) }
        assertEquals(expected.map { listOf(it) }, collector.await())
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun customConnectionStillFailsOnItsObserverOverflowOrConnectionFailure() = runTest {
        val generation = HtspConnectionGeneration()
        for (event in listOf(
            HtspTransportEvent.MetadataOverflow(generation, 1L),
            HtspTransportEvent.ConnectionFailure(HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE), generation),
        )) {
            val connection = ScriptedConnection(generation)
            connection.executeScript = { _, _, _ ->
                assertEquals(1, eventSource.subscriptionCount.value)
                eventSource.emit(event)
                emitInitialSync(generation)
                HtspResult.Ok(HtspEmptyResponse)
            }
            assertSame(HtspResult.TransportUnavailable, connection.enableAsyncMetadataAwaitingInitialSync())
            assertEquals(0, connection.eventSource.subscriptionCount.value)
        }
    }

    @Test
    fun sameTickMarkerCannotBeMissedAndAcknowledgementParametersArePreserved() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { request, timeoutMs, expectedGeneration ->
            assertEquals(1, eventSource.subscriptionCount.value)
            assertEquals(
                EnableAsyncMetadataRequest(
                    epg = true,
                    lastUpdateEpochSeconds = Long.MIN_VALUE,
                    epgMaxTimeEpochSeconds = Long.MAX_VALUE,
                    language = "eng",
                ),
                request,
            )
            assertEquals(30_000L, timeoutMs)
            assertSame(generation, expectedGeneration)
            emitInitialSync(generation)
            HtspResult.Ok(HtspEmptyResponse)
        }

        assertEquals(
            HtspResult.Ok(Unit),
            connection.enableAsyncMetadataAwaitingInitialSync(
                epg = true,
                lastUpdateEpochSeconds = Long.MIN_VALUE,
                epgMaxTimeEpochSeconds = Long.MAX_VALUE,
                language = "eng",
                expectedGeneration = generation,
            ),
        )
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun markerNeverOverridesAnUnsuccessfulAcknowledgement() = runTest {
        val failures = listOf<HtspFailure>(
            HtspResult.ServerError(),
            HtspResult.MalformedReply,
            HtspResult.AccessDenied,
            HtspResult.ConnectionLimit,
            HtspResult.Timeout,
            HtspResult.TransportUnavailable,
            HtspResult.NotSupported,
        )

        failures.forEach { failure ->
            val generation = HtspConnectionGeneration()
            val connection = ScriptedConnection(generation)
            connection.executeScript = { _, _, _ ->
                emitInitialSync(generation)
                failure
            }

            assertSame(failure, connection.enableAsyncMetadataAwaitingInitialSync())
            assertEquals(0, connection.eventSource.subscriptionCount.value)
        }
    }

    @Test
    fun overallLimitTimesOutBeforeALateMarkerAndRemovesItsObserver() = runTest {
        val connection = ScriptedConnection(HtspConnectionGeneration())
        connection.executeScript = { _, _, _ ->
            delay(75L)
            HtspResult.Ok(HtspEmptyResponse)
        }
        val lateMarker = launch {
            delay(125L)
            connection.emitInitialSync(connection.currentGeneration)
        }

        assertSame(
            HtspResult.Timeout,
            connection.enableAsyncMetadataAwaitingInitialSync(timeoutMs = 100L),
        )
        assertEquals(0, connection.eventSource.subscriptionCount.value)
        lateMarker.cancelAndJoin()
    }

    @Test
    fun replacementReturnsUnavailableAndReplacementMarkerCannotSatisfyTheWait() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        val markerSent = CompletableDeferred<Unit>()
        val acknowledgement = CompletableDeferred<Unit>()
        connection.executeScript = { _, _, _ ->
            emitInitialSync(generation)
            markerSent.complete(Unit)
            acknowledgement.await()
            HtspResult.Ok(HtspEmptyResponse)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync()
        }

        markerSent.await()
        val replacement = HtspConnectionGeneration()
        connection.replaceWithDelayedObservation(replacement)
        acknowledgement.complete(Unit)
        val failure = result.await()
        connection.emitInitialSync(replacement)

        assertSame(HtspResult.TransportUnavailable, failure)
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun connectedReplacementAloneCancelsPendingSyncAcknowledgement() = runTest {
        val connection = ScriptedConnection(HtspConnectionGeneration())
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        connection.executeScript = { _, _, _ ->
            requestStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                requestCancelled.complete(Unit)
            }
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync()
        }

        requestStarted.await()
        connection.replace(HtspConnectionGeneration())

        assertSame(HtspResult.TransportUnavailable, result.await())
        assertTrue(requestCancelled.isCompleted)
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun transportLossReturnsTypedFailureAndCallerCancellationPropagates() = runTest {
        val transportLoss = ScriptedConnection(HtspConnectionGeneration())
        val requestStarted = CompletableDeferred<Unit>()
        transportLoss.executeScript = { _, _, _ ->
            requestStarted.complete(Unit)
            awaitCancellation()
        }
        val transportResult = async(start = CoroutineStart.UNDISPATCHED) {
            transportLoss.enableAsyncMetadataAwaitingInitialSync()
        }
        requestStarted.await()
        transportLoss.loseTransport()
        assertSame(HtspResult.TransportUnavailable, transportResult.await())
        assertEquals(0, transportLoss.eventSource.subscriptionCount.value)

        val cancellation = ScriptedConnection(HtspConnectionGeneration())
        cancellation.executeScript = { _, _, _ -> HtspResult.Ok(HtspEmptyResponse) }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            cancellation.enableAsyncMetadataAwaitingInitialSync()
        }
        assertEquals(1, cancellation.eventSource.subscriptionCount.value)
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        assertEquals(0, cancellation.eventSource.subscriptionCount.value)
    }

    @Test
    fun cancellationAlreadyRequestedBeforeEntryStillPropagates() = runTest {
        val connection = ScriptedConnection(HtspConnectionGeneration())
        connection.loseTransport()
        val observed = CompletableDeferred<Throwable>()
        val caller = launch {
            currentCoroutineContext().cancel()
            try {
                connection.enableAsyncMetadataAwaitingInitialSync()
            } catch (cancelled: CancellationException) {
                observed.complete(cancelled)
                throw cancelled
            }
        }

        caller.join()
        assertTrue(observed.await() is CancellationException)
    }

    @Test
    fun markerBeforeAcknowledgementWaitsForTheAcknowledgement() = runTest {
        val generation = HtspConnectionGeneration()
        val acknowledgement = CompletableDeferred<Unit>()
        val markerSent = CompletableDeferred<Unit>()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { _, _, _ ->
            emitInitialSync(generation)
            markerSent.complete(Unit)
            acknowledgement.await()
            HtspResult.Ok(HtspEmptyResponse)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync()
        }

        markerSent.await()
        assertFalse(result.isCompleted)
        acknowledgement.complete(Unit)
        assertEquals(HtspResult.Ok(Unit), result.await())
    }

    @Test
    fun malformedMetadataBeforeMarkerStillWaitsForSuccessfulAcknowledgement() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        val requestStarted = CompletableDeferred<Unit>()
        val acknowledgement = CompletableDeferred<Unit>()
        connection.executeScript = { _, _, _ ->
            requestStarted.complete(Unit)
            acknowledgement.await()
            HtspResult.Ok(HtspEmptyResponse)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync()
        }

        requestStarted.await()
        connection.eventSource.emit(HtspTransportEvent.MalformedMetadata(generation, 2L))
        connection.emitInitialSync(generation)

        assertFalse(result.isCompleted)
        acknowledgement.complete(Unit)
        assertEquals(HtspResult.Ok(Unit), result.await())
        assertSame(generation, connection.currentLiveConnection?.generation)
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun malformedMetadataBeforeMarkerAfterSuccessfulAcknowledgementStillCompletesSync() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { _, _, _ -> HtspResult.Ok(HtspEmptyResponse) }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync()
        }

        connection.eventSource.emit(HtspTransportEvent.MalformedMetadata(generation, 1L))
        assertFalse(result.isCompleted)
        connection.emitInitialSync(generation)

        assertEquals(HtspResult.Ok(Unit), result.await())
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun malformedMetadataAfterMarkerDoesNotInvalidateSyncWhileAcknowledgementIsPending() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        val reportsSent = CompletableDeferred<Unit>()
        val acknowledgement = CompletableDeferred<Unit>()
        connection.executeScript = { _, _, _ ->
            emitInitialSync(generation)
            eventSource.emit(HtspTransportEvent.MalformedMetadata(generation, 3L))
            reportsSent.complete(Unit)
            acknowledgement.await()
            HtspResult.Ok(HtspEmptyResponse)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync()
        }

        reportsSent.await()
        assertFalse(result.isCompleted)
        acknowledgement.complete(Unit)
        assertEquals(HtspResult.Ok(Unit), result.await())
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun malformedMetadataForAnotherGenerationCannotInvalidateCurrentSync() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { _, _, _ ->
            eventSource.emit(HtspTransportEvent.MalformedMetadata(HtspConnectionGeneration(), 1L))
            emitInitialSync(generation)
            HtspResult.Ok(HtspEmptyResponse)
        }

        assertEquals(HtspResult.Ok(Unit), connection.enableAsyncMetadataAwaitingInitialSync())
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun customSlowSyncKeepsWaitingWhileMetadataArrivesWithinTheIdleLimit() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { _, timeoutMs, _ ->
            assertEquals(1_000L, timeoutMs)
            HtspResult.Ok(HtspEmptyResponse)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync(idleTimeoutMs = 1_000L)
        }

        repeat(10) { index ->
            delay(999L)
            if (index % 2 == 0) {
                connection.emitMetadata(generation)
            } else {
                connection.eventSource.emit(HtspTransportEvent.MalformedMetadata(generation, 1L))
            }
            assertFalse(result.isCompleted)
        }
        connection.emitInitialSync(generation)

        assertEquals(HtspResult.Ok(Unit), result.await())
        assertEquals(9_990L, currentTime)
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun customSilenceTimesOutExactlyOneIdleLimitAfterTheLastProgress() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { _, _, _ ->
            delay(300L)
            HtspResult.Ok(HtspEmptyResponse)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            connection.enableAsyncMetadataAwaitingInitialSync(idleTimeoutMs = 1_000L)
        }

        delay(900L)
        connection.emitMetadata(generation)
        delay(999L)
        assertFalse(result.isCompleted)

        assertSame(HtspResult.Timeout, result.await())
        assertEquals(1_900L, currentTime)
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun customAcknowledgementIsBoundedByTheIdleLimitAndCountsAsProgress() = runTest {
        val missing = ScriptedConnection(HtspConnectionGeneration())
        missing.executeScript = { _, timeoutMs, _ ->
            assertEquals(1_000L, timeoutMs)
            awaitCancellation()
        }
        assertSame(HtspResult.Timeout, missing.enableAsyncMetadataAwaitingInitialSync(idleTimeoutMs = 1_000L))
        assertEquals(1_000L, currentTime)

        val capped = ScriptedConnection(HtspConnectionGeneration())
        capped.executeScript = { _, timeoutMs, _ ->
            assertEquals(400L, timeoutMs)
            awaitCancellation()
        }
        assertSame(HtspResult.Timeout, capped.enableAsyncMetadataAwaitingInitialSync(timeoutMs = 400L, idleTimeoutMs = 1_000L))
        assertEquals(1_400L, currentTime)

        val late = ScriptedConnection(HtspConnectionGeneration())
        late.executeScript = { _, _, _ ->
            delay(999L)
            HtspResult.Ok(HtspEmptyResponse)
        }
        assertSame(HtspResult.Timeout, late.enableAsyncMetadataAwaitingInitialSync(idleTimeoutMs = 1_000L))
        assertEquals(1_400L + 1_999L, currentTime)
    }

    @Test
    fun overallLimitStillTimesOutDespiteSteadyProgress() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { _, _, _ -> HtspResult.Ok(HtspEmptyResponse) }
        val progress = launch {
            while (true) {
                delay(500L)
                connection.emitMetadata(generation)
            }
        }

        assertSame(
            HtspResult.Timeout,
            connection.enableAsyncMetadataAwaitingInitialSync(timeoutMs = 2_500L, idleTimeoutMs = 1_000L),
        )
        assertEquals(2_500L, currentTime)
        progress.cancelAndJoin()
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun otherGenerationEventsAreNotProgress() = runTest {
        val generation = HtspConnectionGeneration()
        val stale = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { _, _, _ -> HtspResult.Ok(HtspEmptyResponse) }
        val progress = launch {
            while (true) {
                delay(300L)
                connection.emitMetadata(stale)
                connection.eventSource.emit(HtspTransportEvent.MalformedMetadata(stale, 1L))
            }
        }

        assertSame(HtspResult.Timeout, connection.enableAsyncMetadataAwaitingInitialSync(idleTimeoutMs = 1_000L))
        assertEquals(1_000L, currentTime)
        progress.cancelAndJoin()
    }

    @Test
    fun nonPositiveLimitsAreRejectedBeforeAnyWork() = runTest {
        val connection = ScriptedConnection(HtspConnectionGeneration())
        for (invalid in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertTrue(
                runCatching { connection.enableAsyncMetadataAwaitingInitialSync(idleTimeoutMs = invalid) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
            assertTrue(
                runCatching { connection.enableAsyncMetadataAwaitingInitialSync(timeoutMs = invalid) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
        }
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ScriptedConnection(
        generation: HtspConnectionGeneration,
    ) : HtspConnection {
        val eventSource = MutableSharedFlow<HtspTransportEvent>()
        private var currentLive: HtspLiveConnection? = liveConnection(generation)
        private val observedState = MutableStateFlow<HtspConnectionState>(connectedState())
        var currentGeneration: HtspConnectionGeneration = generation
            private set
        private var messageSequence = 0L

        lateinit var executeScript: suspend ScriptedConnection.(
            EnableAsyncMetadataRequest,
            Long,
            HtspConnectionGeneration?,
        ) -> HtspResult<HtspEmptyResponse>

        override val connectionState: StateFlow<HtspConnectionState> = object : StateFlow<HtspConnectionState> {
            override val value: HtspConnectionState
                get() = connectedState()

            override val replayCache: List<HtspConnectionState>
                get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<HtspConnectionState>): Nothing =
                observedState.collect(collector)
        }
        override val events: Flow<HtspTransportEvent> = eventSource

        override fun subscriptionEvents(subscriptionId: Long): Flow<HtspSubscriptionEvent> = emptyFlow()

        override fun subscriptionEvents(
            subscriptionId: Long,
            expectedGeneration: HtspConnectionGeneration,
        ): Flow<HtspSubscriptionEvent> = emptyFlow()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(
            request: HtspRequest<R>,
            timeoutMs: Long,
            expectedGeneration: HtspConnectionGeneration?,
        ): HtspResult<R> {
            check(request is EnableAsyncMetadataRequest)
            return executeScript(request, timeoutMs, expectedGeneration) as HtspResult<R>
        }

        override suspend fun connect(
            endpoint: HtspEndpoint,
            options: HtspConnectOptions,
        ): HtspConnectOutcome = HtspConnectOutcome.Connected(checkNotNull(currentLive))

        override fun isCurrent(generation: HtspConnectionGeneration): Boolean =
            currentGeneration === generation

        override suspend fun disconnect(expectedGeneration: HtspConnectionGeneration?): Boolean {
            if ((expectedGeneration != null && expectedGeneration !== currentGeneration) || currentLive == null) return false
            updateLive(null)
            return true
        }

        private var closed = false

        override suspend fun close(expectedGeneration: HtspConnectionGeneration?): Boolean {
            if (closed || (expectedGeneration != null && expectedGeneration !== currentGeneration)) return false
            closed = true
            updateLive(null)
            return true
        }

        suspend fun emitInitialSync(generation: HtspConnectionGeneration) {
            eventSource.emit(
                HtspTransportEvent.ServerMessage(
                    message = HtspInitialSyncCompletedMessage,
                    generation = generation,
                    messageSequence = ++messageSequence,
                ),
            )
        }

        suspend fun emitMetadata(generation: HtspConnectionGeneration) {
            eventSource.emit(
                HtspTransportEvent.ServerMessage(
                    message = HtspEventDeleteMessage(eventId = messageSequence + 1L),
                    generation = generation,
                    messageSequence = ++messageSequence,
                ),
            )
        }

        fun replaceWithDelayedObservation(generation: HtspConnectionGeneration) {
            currentGeneration = generation
            currentLive = liveConnection(generation)
        }

        fun replace(generation: HtspConnectionGeneration) {
            replaceWithDelayedObservation(generation)
            observedState.value = connectedState()
        }

        fun loseTransport() {
            updateLive(null)
        }

        private fun updateLive(connection: HtspLiveConnection?) {
            currentLive = connection
            observedState.value = connectedState()
        }

        private fun connectedState(): HtspConnectionState = currentLive?.let {
            HtspConnectionState.Connected("fixture", 9982, it)
        } ?: if (closed) HtspConnectionState.Closed else HtspConnectionState.Disconnected

        private fun liveConnection(generation: HtspConnectionGeneration): HtspLiveConnection =
            HtspLiveConnection(
                generation = generation,
                protocolVersion = 43,
                dvrAccess = true,
                serverFacts = HtspServerFacts(),
            )
    }
}
