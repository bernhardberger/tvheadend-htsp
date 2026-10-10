package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspAttemptMonitor
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionState
import at.bernhardberger.tvheadend.htsp.wire.HtspWireMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HtspAttemptMonitorTest {
    @Test
    fun `nested sections defer state replies and deduplicated wakeups until nonlocal return`() {
        val effects = mutableListOf<String>()
        val wakeup = Channel<Unit>(2)
        val reply = CompletableDeferred<HtspWireMessage>()
        val message = HtspWireMessage(mapOf("method" to "reply", "seq" to 1L))
        val connecting = HtspConnectionState.Connecting("test", 1)
        lateinit var attempt: HtspAttemptMonitor
        attempt = HtspAttemptMonitor {
            assertThrows(IllegalStateException::class.java) { attempt.stateLocked() }
            assertFalse(reply.isCompleted)
            assertTrue(wakeup.tryReceive().isFailure)
            effects.add("state")
        }
        reply.invokeOnCompletion {
            assertEquals(connecting, attempt.connectionState.value)
            assertTrue(wakeup.tryReceive().isFailure)
            effects.add("reply")
        }

        fun returnFromNestedSection(): String = attempt.withAttemptLock {
            attempt.recordStateLocked(connecting)
            attempt.queueWakeup(wakeup)
            attempt.withAttemptLock {
                attempt.queueReply(reply, message)
                attempt.queueWakeup(wakeup)
                assertTrue(effects.isEmpty())
                assertFalse(reply.isCompleted)
                assertTrue(wakeup.tryReceive().isFailure)
                assertEquals(HtspConnectionState.Disconnected, attempt.connectionState.value)
                return "returned"
            }
        }

        assertEquals("returned", returnFromNestedSection())
        assertEquals(listOf("state", "reply"), effects)
        assertEquals(Unit, wakeup.tryReceive().getOrThrow())
        assertTrue(wakeup.tryReceive().isFailure)
    }

    @Test
    fun `exceptional section exit still publishes each deferred effect`() {
        val attempt = HtspAttemptMonitor {}
        val wakeup = Channel<Unit>(1)
        val reply = CompletableDeferred<HtspWireMessage>()
        val failure = IllegalStateException("test failure")
        val connecting = HtspConnectionState.Connecting("test", 1)

        val thrown = assertThrows(IllegalStateException::class.java) {
            attempt.withAttemptLock {
                attempt.recordStateLocked(connecting)
                attempt.queueReply(reply, HtspWireMessage(mapOf("method" to "reply", "seq" to 1L)))
                attempt.queueWakeup(wakeup)
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(connecting, attempt.connectionState.value)
        assertTrue(reply.isCompleted)
        assertEquals(Unit, wakeup.tryReceive().getOrThrow())
        assertThrows(IllegalStateException::class.java) { attempt.stateLocked() }
    }

    @Test
    fun `publication failure still completes replies and wakeups without leaking pending effects`() {
        val failure = IllegalStateException("test publication failure")
        var publications = 0
        val attempt = HtspAttemptMonitor { publications++; throw failure }
        val wakeup = Channel<Unit>(1)
        val reply = CompletableDeferred<HtspWireMessage>()

        val thrown = assertThrows(IllegalStateException::class.java) {
            attempt.withAttemptLock {
                attempt.recordStateLocked(HtspConnectionState.Connecting("test", 1))
                attempt.queueReply(reply, HtspWireMessage(mapOf("method" to "reply", "seq" to 1L)))
                attempt.queueWakeup(wakeup)
            }
        }

        assertSame(failure, thrown)
        assertTrue(reply.isCompleted)
        assertEquals(Unit, wakeup.tryReceive().getOrThrow())
        attempt.withAttemptLock { }
        assertEquals(1, publications)
        assertTrue(wakeup.tryReceive().isFailure)
    }
}
