package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspTransportInputStreamTest {

    @Test
    fun timeoutBeforeCurrentFrameBytes_propagatesWithoutRetryOrLog() {
        val entries = mutableListOf<LogEntry>()
        val source = TimeoutBeforeBytesInputStream()
        val input = HtspTransportInputStream(source, logger(entries), timeoutGraceMs = 100L)
        input.beginFrame()

        assertThrows(SocketTimeoutException::class.java) {
            HtspCodec.readMessage(input)
        }

        assertEquals(1, source.readCalls)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun timeoutAfterCurrentFrameBytes_retriesLogsAndPreservesExactAlignment() {
        val encodedFrames = ByteArrayOutputStream().also { output ->
            HtspCodec.writeMessage(output, "hello", mapOf("seq" to 3))
            HtspCodec.writeMessage(output, "authenticate", mapOf("seq" to 4))
        }.toByteArray()
        val entries = mutableListOf<LogEntry>()
        val input = HtspTransportInputStream(
            PartialThenTimeoutInputStream(encodedFrames, partialByteCount = 2),
            logger(entries),
            timeoutGraceMs = 100L,
        )
        input.beginFrame()

        val first = HtspCodec.readMessage(input)
        input.beginFrame()
        val second = HtspCodec.readMessage(input)

        assertEquals("hello", first.method)
        assertEquals(3, first.seq)
        assertEquals("authenticate", second.method)
        assertEquals(4, second.seq)
        assertEquals(1, entries.size)
        assertEquals(HtspLogLevel.WARNING, entries.single().level)
        assertTrue(entries.single().message.contains("current HTSP frame"))
        assertTrue(entries.single().cause is SocketTimeoutException)
    }

    @Test
    fun persistentPartialTimeoutExpiresAtGraceBoundary() {
        val source = TimedInputStream(listOf(0 to 0L, null to 0L, null to 99L, null to 100L))
        val input = HtspTransportInputStream(source, HtspLogger.None, 100L) { source.nowMs * 1_000_000L }
        input.beginFrame()
        assertEquals(0, input.read())
        assertThrows(SocketTimeoutException::class.java) { input.read() }
        assertEquals(4, source.readCalls)
        assertEquals(1, input.frameBytesRead())
    }

    @Test
    fun successfulByteProgressResetsTimeoutGrace() {
        val source = TimedInputStream(
            listOf(1 to 0L, null to 0L, 2 to 90L, null to 95L, null to 190L, 3 to 190L),
        )
        val input = HtspTransportInputStream(source, HtspLogger.None, 100L) { source.nowMs * 1_000_000L }
        input.beginFrame()
        assertEquals(1, input.read())
        assertEquals(2, input.read())
        assertEquals(3, input.read())
        assertEquals(6, source.readCalls)
    }

    @Test
    fun firstPartialTimeoutStillRetriesWhenSocketTimeoutExceedsGrace() {
        val source = TimedInputStream(listOf(0 to 0L, null to 1_000L, null to 2_000L))
        val input = HtspTransportInputStream(source, HtspLogger.None, 100L) { source.nowMs * 1_000_000L }
        input.beginFrame()
        assertEquals(0, input.read())
        assertThrows(SocketTimeoutException::class.java) { input.read() }
        assertEquals(3, source.readCalls)
    }

    private class TimedInputStream(private val reads: List<Pair<Int?, Long>>) : InputStream() {
        var nowMs = 0L
            private set
        var readCalls = 0
            private set

        override fun read(): Int {
            val (byte, time) = reads[readCalls++]
            nowMs = time
            return byte ?: throw SocketTimeoutException("Scripted timeout")
        }
    }

    private fun logger(entries: MutableList<LogEntry>) =
        HtspLogger { level, message, cause -> entries += LogEntry(level, message, cause) }

    private data class LogEntry(
        val level: HtspLogLevel,
        val message: String,
        val cause: Throwable?,
    )

    private class TimeoutBeforeBytesInputStream : InputStream() {
        var readCalls: Int = 0
            private set

        override fun read(): Int {
            readCalls++
            throw SocketTimeoutException("expected idle timeout")
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            throw SocketTimeoutException("expected idle timeout")
        }
    }

    private class PartialThenTimeoutInputStream(
        bytes: ByteArray,
        private val partialByteCount: Int,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var returnedPartial = false
        private var timeoutPending = true

        override fun read(): Int {
            if (returnedPartial && timeoutPending) {
                timeoutPending = false
                throw SocketTimeoutException("expected mid-frame timeout")
            }
            returnedPartial = true
            return delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!returnedPartial) {
                returnedPartial = true
                return delegate.read(buffer, offset, minOf(length, partialByteCount))
            }
            if (timeoutPending) {
                timeoutPending = false
                throw SocketTimeoutException("expected mid-frame timeout")
            }
            return delegate.read(buffer, offset, length)
        }
    }
}
