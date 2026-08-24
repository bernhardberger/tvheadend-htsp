package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspTimeshiftStatusMessage
import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HtspNearLiveSkipTest {
    @Test
    fun nearLiveSkipUsesObservedEndSelectedClockAndAttemptFence() = runTest {
        val owner = createHtspConnection(Dispatchers.Unconfined)
        val generation = HtspConnectionGeneration()
        val requests = mutableListOf<HtspRequest<*>>()
        val timeouts = mutableListOf<Long>()
        val generations = mutableListOf<HtspConnectionGeneration?>()
        val connection = object : HtspConnection by owner {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(
                request: HtspRequest<R>,
                timeoutMs: Long,
                expectedGeneration: HtspConnectionGeneration?,
            ): HtspResult<R> {
                requests += request
                timeouts += timeoutMs
                generations += expectedGeneration
                return HtspResult.Ok(HtspEmptyResponse) as HtspResult<R>
            }
        }

        try {
            assertEquals(
                HtspResult.Ok(HtspEmptyResponse),
                connection.subscriptionSkipNearLive(
                    status = status(start = 10_000_000L, end = 20_000_000L),
                    clock = SubscriptionTimestampClock.MICROSECONDS,
                    marginSeconds = 3L,
                    timeoutMs = 1_234L,
                    expectedGeneration = generation,
                ),
            )
            assertEquals(
                HtspResult.Ok(HtspEmptyResponse),
                connection.subscriptionSkipNearLive(
                    status = status(start = 900_000L, end = 1_800_000L),
                    clock = SubscriptionTimestampClock.NINETY_KHZ,
                    marginSeconds = 3L,
                ),
            )

            assertEquals(
                listOf(
                    SubscriptionSkipRequest(
                        subscriptionId = 7L,
                        position = SubscriptionSeekPosition.Time(17_000_000L),
                        absolute = 1L,
                    ),
                    SubscriptionSkipRequest(
                        subscriptionId = 7L,
                        position = SubscriptionSeekPosition.Time(1_530_000L),
                        absolute = 1L,
                    ),
                ),
                requests,
            )
            assertEquals(listOf(1_234L, 5_000L), timeouts)
            assertSame(generation, generations[0])
            assertSame(null, generations[1])
        } finally {
            owner.close()
        }
    }

    @Test
    fun conversionSafeBoundaryIsAcceptedForBothTimestampClocks() = runTest {
        val owner = createHtspConnection(Dispatchers.Unconfined)
        val targets = mutableListOf<Long>()
        val connection = object : HtspConnection by owner {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(
                request: HtspRequest<R>,
                timeoutMs: Long,
                expectedGeneration: HtspConnectionGeneration?,
            ): HtspResult<R> {
                val skip = request as SubscriptionSkipRequest
                targets += (skip.position as SubscriptionSeekPosition.Time).time
                return HtspResult.Ok(HtspEmptyResponse) as HtspResult<R>
            }
        }
        val nativeBoundary = Long.MAX_VALUE / NINETY_KHZ_UNITS_PER_SECOND
        val ninetyKhzBoundary = Long.MAX_VALUE / MICROSECONDS_PER_SECOND

        try {
            connection.subscriptionSkipNearLive(
                status = status(
                    start = nativeBoundary,
                    end = nativeBoundary + MICROSECONDS_PER_SECOND,
                ),
                clock = SubscriptionTimestampClock.MICROSECONDS,
                marginSeconds = 1L,
            )
            connection.subscriptionSkipNearLive(
                status = status(
                    start = ninetyKhzBoundary,
                    end = ninetyKhzBoundary + NINETY_KHZ_UNITS_PER_SECOND,
                ),
                clock = SubscriptionTimestampClock.NINETY_KHZ,
                marginSeconds = 1L,
            )

            assertEquals(listOf(nativeBoundary, ninetyKhzBoundary), targets)
        } finally {
            owner.close()
        }
    }

    @Test
    fun invalidOrUnsafeNearLiveTargetsFailBeforeDispatch() = runTest {
        val owner = createHtspConnection(Dispatchers.Unconfined)
        var dispatches = 0
        val connection = object : HtspConnection by owner {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(
                request: HtspRequest<R>,
                timeoutMs: Long,
                expectedGeneration: HtspConnectionGeneration?,
            ): HtspResult<R> {
                dispatches += 1
                return HtspResult.Ok(HtspEmptyResponse) as HtspResult<R>
            }
        }
        val nativeBoundary = Long.MAX_VALUE / NINETY_KHZ_UNITS_PER_SECOND
        val ninetyKhzBoundary = Long.MAX_VALUE / MICROSECONDS_PER_SECOND

        suspend fun assertRejected(
            status: HtspTimeshiftStatusMessage,
            clock: SubscriptionTimestampClock = SubscriptionTimestampClock.MICROSECONDS,
            marginSeconds: Long = 1L,
        ) {
            val failure = runCatching {
                connection.subscriptionSkipNearLive(status, clock, marginSeconds)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }

        try {
            assertRejected(status(start = null, end = 20_000_000L))
            assertRejected(status(start = 0L, end = null))
            assertRejected(status(start = 0L, end = 20_000_000L), marginSeconds = 0L)
            assertRejected(status(start = 0L, end = 20_000_000L), marginSeconds = -1L)
            assertRejected(
                status(start = 0L, end = Long.MAX_VALUE),
                marginSeconds = Long.MAX_VALUE / MICROSECONDS_PER_SECOND + 1L,
            )
            assertRejected(status(start = Long.MIN_VALUE, end = Long.MIN_VALUE))
            assertRejected(status(start = 18_000_000L, end = 20_000_000L), marginSeconds = 3L)
            assertRejected(
                status(
                    start = 0L,
                    end = nativeBoundary + MICROSECONDS_PER_SECOND + 1L,
                ),
            )
            assertRejected(
                status(
                    start = 0L,
                    end = ninetyKhzBoundary + NINETY_KHZ_UNITS_PER_SECOND + 1L,
                ),
                clock = SubscriptionTimestampClock.NINETY_KHZ,
            )
            assertEquals(0, dispatches)
        } finally {
            owner.close()
        }
    }

    @Test
    fun requestFailureAndCancellationPropagateWithoutFallbackOrRetry() = runTest {
        val owner = createHtspConnection(Dispatchers.Unconfined)
        val cancellation = CancellationException("synthetic near-live cancellation")
        var dispatches = 0
        var cancel = false
        val connection = object : HtspConnection by owner {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <R> execute(
                request: HtspRequest<R>,
                timeoutMs: Long,
                expectedGeneration: HtspConnectionGeneration?,
            ): HtspResult<R> {
                dispatches += 1
                assertTrue(request is SubscriptionSkipRequest)
                if (cancel) throw cancellation
                return HtspResult.ServerError as HtspResult<R>
            }
        }

        try {
            assertSame(
                HtspResult.ServerError,
                connection.subscriptionSkipNearLive(
                    status(10_000_000L, 20_000_000L),
                    SubscriptionTimestampClock.MICROSECONDS,
                    marginSeconds = 3L,
                ),
            )
            cancel = true
            val failure = runCatching {
                connection.subscriptionSkipNearLive(
                    status(10_000_000L, 20_000_000L),
                    SubscriptionTimestampClock.MICROSECONDS,
                    marginSeconds = 3L,
                )
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertEquals(2, dispatches)
        } finally {
            owner.close()
        }
    }

    private fun status(start: Long?, end: Long?): HtspTimeshiftStatusMessage =
        HtspTimeshiftStatusMessage(
            subscriptionId = 7L,
            full = 0L,
            shift = 0L,
            start = start,
            end = end,
        )

    private companion object {
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val NINETY_KHZ_UNITS_PER_SECOND = 90_000L
    }
}
