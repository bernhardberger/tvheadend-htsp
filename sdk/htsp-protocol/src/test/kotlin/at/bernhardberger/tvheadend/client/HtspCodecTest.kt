package at.bernhardberger.tvheadend.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HtspCodecTest {

    @Test
    fun framedRoundTrip_preservesSupportedValuesAndMuxPayload() {
        val payload = byteArrayOf(0x47, 0x01, 0x02)
        val output = ByteArrayOutputStream()

        HtspCodec.writeMessage(
            output = output,
            method = "muxpkt",
            fields = mapOf(
                "seq" to 7,
                "signed" to -2L,
                "zero" to 0,
                "title" to "Živě",
                "payload" to payload,
                "enabled" to true,
                "ratio" to 1.5,
                "nested" to mapOf("value" to 9),
                "items" to listOf("first", 2),
            ),
        )

        val framed = output.toByteArray()
        val declaredLength =
            ((framed[0].toInt() and 0xff) shl 24) or
                ((framed[1].toInt() and 0xff) shl 16) or
                ((framed[2].toInt() and 0xff) shl 8) or
                (framed[3].toInt() and 0xff)
        assertEquals(framed.size - 4, declaredLength)

        val decoded = HtspCodec.readMessage(ByteArrayInputStream(framed))

        assertEquals("muxpkt", decoded.method)
        assertEquals(7, decoded.seq)
        assertEquals(-2L, decoded.long("signed"))
        assertEquals(0, decoded.int("zero"))
        assertEquals("Živě", decoded.str("title"))
        assertEquals(true, decoded.bool("enabled"))
        assertEquals(1.5, decoded.fields["ratio"])
        assertEquals(9L, decoded.map("nested")?.get("value"))
        assertEquals(listOf("first", 2L), decoded.list("items"))
        assertArrayEquals(payload, HtspCodec.tsPayload(decoded))
        assertTrue(HtspCodec.isMuxPkt(decoded))
    }

    @Test
    fun softSocketTimeout_isLoggedAndReadingContinuesWithoutLosingFraming() {
        val encoded = ByteArrayOutputStream().also { output ->
            HtspCodec.writeMessage(output, "hello", mapOf("seq" to 3))
        }.toByteArray()
        val entries = mutableListOf<LogEntry>()
        val logger = HtspLogger { level, message, cause ->
            entries += LogEntry(level, message, cause)
        }

        val decoded = HtspCodec.readMessage(
            input = TimeoutOnceInputStream(ByteArrayInputStream(encoded)),
            logger = logger,
        )

        assertEquals("hello", decoded.method)
        assertEquals(3, decoded.seq)
        assertEquals(1, entries.size)
        assertEquals(HtspLogLevel.WARNING, entries.single().level)
        assertTrue(entries.single().message.contains("rootLen"))
        assertTrue(entries.single().cause is SocketTimeoutException)
    }

    @Test
    fun invalidRootLength_isLoggedAndRejected() {
        val entries = mutableListOf<LogEntry>()
        val logger = HtspLogger { level, message, cause ->
            entries += LogEntry(level, message, cause)
        }

        assertThrows(IllegalStateException::class.java) {
            HtspCodec.readMessage(
                input = ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)),
                logger = logger,
            )
        }

        assertEquals(HtspLogLevel.ERROR, entries.single().level)
        assertTrue(entries.single().message.contains("invalid root length"))
        assertEquals(null, entries.single().cause)
    }

    private data class LogEntry(
        val level: HtspLogLevel,
        val message: String,
        val cause: Throwable?,
    )

    private class TimeoutOnceInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        private var timeoutPending = true

        override fun read(): Int {
            throwTimeoutOnce()
            return delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            throwTimeoutOnce()
            return delegate.read(buffer, offset, length)
        }

        private fun throwTimeoutOnce() {
            if (timeoutPending) {
                timeoutPending = false
                throw SocketTimeoutException("expected test timeout")
            }
        }
    }
}
