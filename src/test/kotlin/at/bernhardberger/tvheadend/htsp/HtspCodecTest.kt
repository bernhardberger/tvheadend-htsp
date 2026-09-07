package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

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
        assertArrayEquals(payload, decoded.rawPayload)
    }

    @Test
    fun replySequencesRetainTheFullUnsignedDomainWithoutNumericAliasing() {
        listOf(0L, 7L, Int.MAX_VALUE.toLong(), 0x8000_0000L, 0xFFFF_FFFFL).forEach { seq ->
            val output = ByteArrayOutputStream()
            HtspCodec.writeMessage(output, "reply", mapOf("seq" to seq))
            val decoded = HtspCodec.readMessage(ByteArrayInputStream(output.toByteArray()))
            assertEquals(seq.toInt(), decoded.seq)
            assertEquals(seq, decoded.fields["seq"])
        }
        listOf(-1L, 0x1_0000_0007L, Long.MAX_VALUE, 7.0, 7.9, "7", true).forEach { seq ->
            val output = ByteArrayOutputStream()
            HtspCodec.writeMessage(output, "reply", mapOf("seq" to seq))
            assertEquals(null, HtspCodec.readMessage(ByteArrayInputStream(output.toByteArray())).seq)
        }
    }

    @Test
    fun oversizedSequenceIntegerCannotAliasItsLowBytes() {
        val frame = byteArrayOf(
            0, 0, 0, 18,
            2, 3, 0, 0, 0, 9, 's'.code.toByte(), 'e'.code.toByte(), 'q'.code.toByte(),
            7, 0, 0, 0, 0, 0, 0, 0, 1,
        )
        val failure = assertThrows(HtspFramingException::class.java) {
            HtspCodec.readMessage(ByteArrayInputStream(frame))
        }
        assertEquals("sequence integer exceeds 64 bits", failure.failure)
    }

    @Test
    fun invalidRootLength_isFramingFailureWithoutTransportPolicy() {
        val failure = assertThrows(HtspFramingException::class.java) {
            HtspCodec.readMessage(ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)))
        }

        assertEquals("invalid root length", failure.failure)
        assertEquals(0, failure.byteOffset)
    }

    @Test
    fun fieldWhoseNameAndDataExceedRoot_isFramingFailureNotEof() {
        val malformed = byteArrayOf(
            0, 0, 0, 8,
            3, 2, 0, 0, 0, 1,
            'a'.code.toByte(), 'b'.code.toByte(),
        )

        val failure = assertThrows(HtspFramingException::class.java) {
            HtspCodec.readMessage(ByteArrayInputStream(malformed))
        }

        assertEquals("field name and data exceed enclosing frame", failure.failure)
        assertEquals(10, failure.byteOffset)
    }
}
