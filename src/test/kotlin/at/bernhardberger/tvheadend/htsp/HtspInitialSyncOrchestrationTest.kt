package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HtspInitialSyncOrchestrationTest {
    @Test
    fun sameTickMarkerCannotBeMissedAndAcknowledgementParametersArePreserved() = runTest {
        val generation = HtspConnectionGeneration()
        val connection = ScriptedConnection(generation)
        connection.executeScript = { request, timeoutMs, expectedGeneration ->
            assertEquals(1, eventSource.subscriptionCount.value)
            assertEquals(
                EnableAsyncMetadataRequest(
                    epg = 1L,
                    lastUpdate = Long.MIN_VALUE,
                    epgMaxTime = Long.MAX_VALUE,
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
                epg = 1L,
                lastUpdate = Long.MIN_VALUE,
                epgMaxTime = Long.MAX_VALUE,
                language = "eng",
                expectedGeneration = generation,
            ),
        )
        assertEquals(0, connection.eventSource.subscriptionCount.value)
    }

    @Test
    fun markerNeverOverridesAnUnsuccessfulAcknowledgement() = runTest {
        val failures = listOf<HtspFailure>(
            HtspResult.ServerError,
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
    fun markerWaitUsesOneDeadlineAndRemovesItsObserver() = runTest {
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
    fun replacementCancelsTheWaitAndReplacementMarkerCannotSatisfyIt() = runTest {
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
        val failure = try {
            result.await()
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        connection.emitInitialSync(replacement)

        assertTrue(failure is CancellationException)
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

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ScriptedConnection(
        generation: HtspConnectionGeneration,
    ) : HtspConnection {
        val eventSource = MutableSharedFlow<HtspTransportEvent>()
        private var currentLive: HtspLiveConnection? = liveConnection(generation)
        private val observedLive = MutableStateFlow(currentLive)
        var currentGeneration: HtspConnectionGeneration = generation
            private set
        private var messageSequence = 0L

        lateinit var executeScript: suspend ScriptedConnection.(
            EnableAsyncMetadataRequest,
            Long,
            HtspConnectionGeneration?,
        ) -> HtspResult<HtspEmptyResponse>

        override val liveConnection: StateFlow<HtspLiveConnection?> = object : StateFlow<HtspLiveConnection?> {
            override val value: HtspLiveConnection?
                get() = currentLive

            override val replayCache: List<HtspLiveConnection?>
                get() = listOf(currentLive)

            override suspend fun collect(collector: FlowCollector<HtspLiveConnection?>): Nothing =
                observedLive.collect(collector)
        }
        override val events: Flow<HtspTransportEvent> = eventSource

        override fun subscriptionEvents(subscriptionId: Long): Flow<HtspSubscriptionEvent> = emptyFlow()

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

        override fun <T> commitIfCurrent(
            generation: HtspConnectionGeneration,
            block: () -> T,
        ): T? = if (currentGeneration === generation) block() else null

        override fun <T> commitIfLive(
            generation: HtspConnectionGeneration,
            block: (HtspLiveConnection) -> T,
        ): T? = currentLive
            ?.takeIf { connection -> connection.generation === generation }
            ?.let(block)

        override suspend fun disconnect(expectedGeneration: HtspConnectionGeneration?) {
            updateLive(null)
        }

        override suspend fun close(expectedGeneration: HtspConnectionGeneration?) {
            updateLive(null)
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

        fun replaceWithDelayedObservation(generation: HtspConnectionGeneration) {
            currentGeneration = generation
            currentLive = liveConnection(generation)
        }

        fun loseTransport() {
            updateLive(null)
        }

        private fun updateLive(connection: HtspLiveConnection?) {
            currentLive = connection
            observedLive.value = connection
        }

        private fun liveConnection(generation: HtspConnectionGeneration): HtspLiveConnection =
            HtspLiveConnection(
                generation = generation,
                protocolVersion = 43,
                dvrAccess = true,
                serverFacts = HtspServerFacts(),
            )
    }
}
