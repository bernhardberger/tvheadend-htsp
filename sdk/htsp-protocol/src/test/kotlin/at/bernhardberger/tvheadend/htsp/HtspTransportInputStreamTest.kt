package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HtspTransportInputStreamTest {

    @Test
    fun timeoutBeforeCurrentFrameBytes_propagatesWithoutRetryOrLog() {
        val entries = mutableListOf<LogEntry>()
        val source = TimeoutBeforeBytesInputStream()
        val input = HtspTransportInputStream(source, logger(entries))
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
