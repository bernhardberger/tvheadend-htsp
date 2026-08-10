package at.bernhardberger.tvheadend.htsp

import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
    fun ordinaryFailureIsRetainedExactlyAndTransportCloses() = runTest {
        val failure = UnknownHostException("missing")
        var closed = false
        val session = FakeProbeSession(
            connect = { throw failure },
            close = { closed = true },
        )

        val result = runHtspConnectionProbe(session) as HtspProbeFailure

        assertSame(failure, result.error)
        assertTrue(closed)
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
