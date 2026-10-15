package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionState
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspServerFacts
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionTermination
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportFailureKind
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimeshiftStatusMessage
import at.bernhardberger.tvheadend.htsp.model.HtspTimerecEntry
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeRequest
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspEmptyResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSeekPosition
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSkipRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionTimestampClock
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import at.bernhardberger.tvheadend.htsp.requests.subscriptionSkipNearLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagedExecuteRuntimeTest {
    @Test
    fun terminalClosedStateAndFailureKindAreAvailableFromTheStagedArtifact(): Unit = runBlocking {
        val connection = createHtspConnection(Dispatchers.Unconfined)
        assertTrue(connection.close())
        assertSame(HtspConnectionState.Closed, connection.connectionState.value)
        assertEquals(
            HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.CLOSED)),
            connection.connect(HtspEndpoint("127.0.0.1", 9982)),
        )
        assertSame(HtspResult.TransportUnavailable, connection.getSysTime())
        assertFalse(connection.disconnect())
        assertFalse(connection.close())
        assertSame(HtspConnectionState.Closed, connection.connectionState.value)
    }

    @Test
    fun connectedSnapshotAndNonNullFailureGenerationSupportConsumerConstructedTokens() {
        val generation = HtspConnectionGeneration()
        val live = HtspLiveConnection(generation, 44, true, HtspServerFacts())
        val state = HtspConnectionState.Connected("fixture", 9982, live)
        assertSame(live, state.connection)
        assertSame(generation, state.connection.generation)
        val failure = HtspTransportEvent.ConnectionFailure(
            HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
            generation,
        )
        val failedGeneration: HtspConnectionGeneration = failure.generation
        assertSame(generation, failedGeneration)
    }

    @Test
    fun correctedTimerecShapeIsAvailableFromTheStagedArtifact() {
        val allChannel = HtspTimerecEntryAddMessage(
            HtspTimerecEntry(
                id = "rule",
                enabled = true,
                startMinutesSinceMidnight = null,
                stopMinutesSinceMidnight = null,
                daysOfWeekMask = 0L,
                priority = 0L,
                retentionDays = 0L,
                removalDays = 0L,
            ),
        )
        assertEquals(null, allChannel.entry.name)
        assertEquals(null, allChannel.entry.title)
        assertEquals(null, allChannel.entry.channelId)
        assertEquals(null, allChannel.entry.startMinutesSinceMidnight)
        assertEquals(null, allChannel.entry.stopMinutesSinceMidnight)

        assertEquals(
            0xffff_ffffL,
            allChannel.copy(entry = allChannel.entry.copy(channelId = 0xffff_ffffL)).entry.channelId,
        )
    }

    @Test
    fun terminalAttributionIsAvailableFromTheStagedArtifact() {
        assertEquals(
            HtspSubscriptionTermination.REMOTE_EOF,
            HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.REMOTE_EOF).reason,
        )
    }

    @Test
    fun convenienceRequestUsesFakeExecuteMember(): Unit = runBlocking {
        val owner = createHtspConnection(Dispatchers.Unconfined)
        val expected = GetSysTimeResponse(
            timeEpochSeconds = 1_723_456_789L,
            legacyTimezoneHoursWestOfGmt = -2,
            gmtOffsetMinutes = 120,
        )
        val connection = object : HtspConnection by owner {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(
                request: HtspRequest<R>,
                timeoutMs: Long,
                expectedGeneration: HtspConnectionGeneration?,
            ): HtspResult<R> {
                assertTrue(request is GetSysTimeRequest)
                assertEquals(5_000L, timeoutMs)
                assertEquals(null, expectedGeneration)
                return HtspResult.Ok(expected) as HtspResult<R>
            }
        }

        try {
            assertEquals(HtspResult.Ok(expected), connection.getSysTime())
        } finally {
            connection.close()
        }
    }

    @Test
    fun nearLiveSkipUsesTheStagedSemanticConvenience(): Unit = runBlocking {
        val owner = createHtspConnection(Dispatchers.Unconfined)
        val generation = HtspConnectionGeneration()
        val connection = object : HtspConnection by owner {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(
                request: HtspRequest<R>,
                timeoutMs: Long,
                expectedGeneration: HtspConnectionGeneration?,
            ): HtspResult<R> {
                assertEquals(
                    SubscriptionSkipRequest(
                        subscriptionId = 9L,
                        position = SubscriptionSeekPosition.Time(17_000_000L),
                        absolute = true,
                    ),
                    request,
                )
                assertEquals(1_234L, timeoutMs)
                assertTrue(expectedGeneration === generation)
                return HtspResult.Ok(HtspEmptyResponse) as HtspResult<R>
            }
        }

        try {
            assertEquals(
                HtspResult.Ok(HtspEmptyResponse),
                connection.subscriptionSkipNearLive(
                    status = HtspTimeshiftStatusMessage(
                        subscriptionId = 9L,
                        full = false,
                        shift = 5_000_000L,
                        start = 10_000_000L,
                        end = 20_000_000L,
                    ),
                    clock = SubscriptionTimestampClock.MICROSECONDS,
                    marginSeconds = 3L,
                    timeoutMs = 1_234L,
                    expectedGeneration = generation,
                ),
            )
        } finally {
            owner.close()
        }
    }
}
