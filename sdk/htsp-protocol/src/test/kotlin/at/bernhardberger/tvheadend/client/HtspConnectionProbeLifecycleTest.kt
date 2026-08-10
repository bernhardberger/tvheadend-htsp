package at.bernhardberger.tvheadend.htsp

import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HtspConnectionProbeLifecycleTest {
    @Test
    fun probeCancellationPropagatesUnchangedAfterClose() = runTest {
        val cancellation = CancellationException("probe cancelled")
        var closed = false
        val session = FakeProbeSession(
            connect = { throw cancellation },
            close = { closed = true },
        )

        val thrown = expectFailure { runHtspConnectionProbe(session) }

        assertSame(cancellation, thrown)
        assertTrue(closed)
    }

    @Test
    fun closeCancellationPropagatesUnchanged() = runTest {
        val cancellation = CancellationException("close cancelled")
        val session = FakeProbeSession(close = { throw cancellation })

        val thrown = expectFailure { runHtspConnectionProbe(session) }

        assertSame(cancellation, thrown)
    }

    @Test
    fun ordinaryFailureIsReducedToABoundedKindAndTransportCloses() = runTest {
        val failure = UnknownHostException("missing")
        var closed = false
        val session = FakeProbeSession(
            connect = { throw failure },
            close = { closed = true },
        )

        val result = runHtspConnectionProbe(session) as HtspProbeFailure

        assertEquals(
            HtspTransportFailure(HtspTransportFailureKind.HOST_NOT_FOUND),
            result.failure,
        )
        assertFalse(
            HtspProbeFailure::class.java.declaredFields.any { field ->
                Throwable::class.java.isAssignableFrom(field.type)
            },
        )
        assertTrue(closed)
    }

    @Test
    fun incompatibleVersionAndZeroChannelsUseDedicatedBoundedKinds() = runTest {
        assertEquals(
            HtspTransportFailureKind.INCOMPATIBLE_SERVER,
            (runHtspConnectionProbe(FakeProbeSession(connect = { 18 })) as HtspProbeFailure)
                .failure.kind,
        )
        assertEquals(
            HtspTransportFailureKind.ZERO_CHANNELS,
            (runHtspConnectionProbe(FakeProbeSession(sync = { 0 })) as HtspProbeFailure)
                .failure.kind,
        )
    }

    @Test
    fun ordinaryCloseFailureDoesNotReplaceTheComputedResult() = runTest {
        val result = runHtspConnectionProbe(
            FakeProbeSession(close = { error("close failed") }),
        )

        assertEquals(HtspProbeSuccess(serverVersion = 43, channelCount = 1), result)
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected failure")
    }

    private class FakeProbeSession(
        private val connect: suspend () -> Int = { 43 },
        private val sync: suspend () -> Int = { 1 },
        private val close: suspend () -> Unit = {},
    ) : HtspProbeSession {
        override suspend fun connect(): Int = connect.invoke()
        override suspend fun syncChannelMetadata(): Int = sync.invoke()
        override suspend fun close(): Unit = close.invoke()
    }
}
