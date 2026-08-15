package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtspGoldenCorpusTest {

    @Test
    fun hello_matchesHandDerivedFrameAndDecodes() {
        val frame = loadHtspGoldenFrame("hello.hex")

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x11,
                0x03, 0x06, 0x00, 0x00, 0x00, 0x05,
                0x6d, 0x65, 0x74, 0x68, 0x6f, 0x64,
                0x68, 0x65, 0x6c, 0x6c, 0x6f,
            ),
            frame,
        )
        assertEquals("hello", HtspCodec.readMessage(ByteArrayInputStream(frame)).method)
        assertArrayEquals(frame, encode("hello", emptyMap()))
    }

    @Test
    fun booleanFalse_decodeOnlyPinsPinnedZeroLengthEncoding() {
        val frame = loadHtspGoldenFrame("boolean-false.hex")
        val decoded = HtspCodec.readMessage(ByteArrayInputStream(frame))

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x1e,
                0x03, 0x06, 0x00, 0x00, 0x00, 0x05,
                0x6d, 0x65, 0x74, 0x68, 0x6f, 0x64,
                0x68, 0x65, 0x6c, 0x6c, 0x6f,
                0x07, 0x07, 0x00, 0x00, 0x00, 0x00,
                0x65, 0x6e, 0x61, 0x62, 0x6c, 0x65, 0x64,
            ),
            frame,
        )
        assertEquals(0x1e, frame.size - 4)
        assertEquals("hello", decoded.method)
        assertEquals(false, decoded.fields["enabled"])
    }

    @Test
    fun scalarTypes_pinS64Utf8BinaryDoubleBooleanAndUuidBytes() {
        val frame = loadHtspGoldenFrame("scalar-types.hex")
        val decoded = HtspCodec.readMessage(ByteArrayInputStream(frame))

        assertEquals(0xA7, frame.size - 4)
        assertEquals("types", decoded.method)
        assertEquals(0L, decoded.fields["zero"])
        assertEquals(0x1234L, decoded.fields["positive"])
        assertEquals(-2L, decoded.fields["negative"])
        assertEquals("Živě", decoded.fields["text"])
        assertEquals("", decoded.fields["empty"])
        assertArrayEquals(byteArrayOf(0, -1, 0x47), decoded.bin("binary"))
        assertEquals(1.5, decoded.fields["double"])
        assertEquals(true, decoded.fields["enabled"])
        assertArrayEquals(ByteArray(16) { it.toByte() }, (decoded.fields["uuid"] as HtspWireUuid).bytes())
        assertArrayEquals(frame, encode(decoded.method!!, decoded.fields - "method"))
    }

    @Test
    fun nestedContainers_pinEmptyValuesNamelessListFieldsAndDepth() {
        val frame = loadHtspGoldenFrame("nested.hex")
        val decoded = HtspCodec.readMessage(ByteArrayInputStream(frame))

        assertEquals(0x5F, frame.size - 4)
        assertEquals("nested", decoded.method)
        assertEquals(emptyMap<String, Any?>(), decoded.fields["emptyMap"])
        assertEquals(emptyList<Any?>(), decoded.fields["emptyList"])
        assertEquals(
            mapOf("items" to listOf("one", mapOf("depth" to 3L))),
            decoded.fields["nest"],
        )
        assertArrayEquals(frame, encode(decoded.method!!, decoded.fields - "method"))
    }

    @Test
    fun muxpkt_pinsRawPayloadFastPathAndHelpers() {
        val frame = loadHtspGoldenFrame("muxpkt.hex")
        val decoded = HtspCodec.readMessage(ByteArrayInputStream(frame))

        assertEquals(0x2C, frame.size - 4)
        assertEquals("muxpkt", decoded.method)
        assertEquals(7, decoded.seq)
        assertTrue(HtspCodec.isMuxPkt(decoded))
        assertArrayEquals(byteArrayOf(0x47, 1, 2), decoded.rawPayload)
        assertTrue(decoded.rawPayload === HtspCodec.tsPayload(decoded))
        assertArrayEquals(frame, encode(decoded.method!!, decoded.fields - "method"))

        val ordinary = HtspCodec.readMessage(ByteArrayInputStream(loadHtspGoldenFrame("hello.hex")))
        assertFalse(HtspCodec.isMuxPkt(ordinary))
        assertEquals(null, ordinary.rawPayload)
        assertEquals(null, HtspCodec.tsPayload(ordinary))
    }

    private fun encode(method: String, fields: Map<String, Any?>): ByteArray =
        ByteArrayOutputStream().also { HtspCodec.writeMessage(it, method, fields) }.toByteArray()
}
