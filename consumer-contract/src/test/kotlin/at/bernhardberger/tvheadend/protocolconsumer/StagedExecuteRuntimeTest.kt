package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionTermination
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimeshiftStatusMessage
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagedExecuteRuntimeTest {
    @Test
    fun correctedTimerecShapeIsAvailableFromTheStagedArtifact() {
        val allChannel = HtspTimerecEntryAddMessage(
            id = "rule",
            enabled = true,
        )
        assertEquals(null, allChannel.name)
        assertEquals(null, allChannel.title)
        assertEquals(null, allChannel.channelId)
        assertEquals(null, allChannel.startMinutesSinceMidnight)
        assertEquals(null, allChannel.stopMinutesSinceMidnight)

        assertEquals(
            0xffff_ffffL,
            allChannel.copy(channelId = 0xffff_ffffL).channelId,
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
            unixTimeSeconds = 1_723_456_789L,
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
                        absolute = 1L,
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
                        full = 0L,
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
