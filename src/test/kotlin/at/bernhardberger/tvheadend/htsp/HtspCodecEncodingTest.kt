package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HtspCodecEncodingTest {
    @Test
    fun signedIntegers_useMinimalUnsignedBitWidthIncludingSignAndByteBoundaries() {
        val cases = listOf<Pair<Number, String>>(
            0L to "",
            1.toByte() to "01",
            127L to "7f",
            128L to "80",
            255L to "ff",
            256.toShort() to "00 01",
            32767L to "ff 7f",
            32768L to "00 80",
            65535L to "ff ff",
            65536 to "00 00 01",
            (1L shl 24) to "00 00 00 01",
            (1L shl 32) to "00 00 00 00 01",
            (1L shl 40) to "00 00 00 00 00 01",
            (1L shl 48) to "00 00 00 00 00 00 01",
            (1L shl 56) to "00 00 00 00 00 00 00 01",
            Long.MAX_VALUE to "ff ff ff ff ff ff ff 7f",
            Long.MIN_VALUE to "00 00 00 00 00 00 00 80",
            -1L to "ff ff ff ff ff ff ff ff",
            -128L to "80 ff ff ff ff ff ff ff",
            -129L to "7f ff ff ff ff ff ff ff",
            -256L to "00 ff ff ff ff ff ff ff",
            BigInteger("4660") to "34 12",
        )
        cases.forEach { (value, payload) -> assertScalar(2, payload, value) }
    }

    @Test
    fun floatingPoint_rejectsDoubleAndFloatAsUnsupportedIncludingNestedValues() {
        listOf<Number>(
            0.0, -0.0, 1.5, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.fromBits(0x7ff8_1234_5678_9abcL), Double.MIN_VALUE,
            0.0f, -0.0f, 1.5f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.MIN_VALUE,
        ).forEach { value ->
            listOf(value, mapOf("nested" to value), listOf(value)).forEach { fieldValue ->
                val failure = assertThrows(IllegalStateException::class.java) {
                    encode(mapOf("v" to fieldValue))
                }
                assertEquals("Unsupported HTSP field type: ${value::class.java.name}", failure.message)
            }
        }
    }

    @Test
    fun localFalse_hasOneByteAndEmptyBinaryHasNone() {
        assertScalar(7, "00", false)
        assertScalar(7, "01", true)
        assertScalar(4, "", byteArrayOf())
    }

    @Test
    fun containers_skipNullsPreserveOrderAndCollapseStringifiedMapKeys() {
        val encoded = encode(
            linkedMapOf(
                "n" to null,
                "b" to listOf(null, 0L, false, null),
                "a" to linkedMapOf("skip" to null, 1 to "old", "1" to "new"),
                "method" to "x",
            ),
        )
        assertArrayEquals(
            hex(
                "00 00 00 32 " +
                    "03 06 00 00 00 01 6d 65 74 68 6f 64 78 " +
                    "05 01 00 00 00 0d 62 02 00 00 00 00 00 07 00 00 00 00 01 00 " +
                    "01 01 00 00 00 0a 61 03 01 00 00 00 03 31 6e 65 77",
            ),
            encoded,
        )
        assertArrayEquals(
            hex("00 00 00 07 02 01 00 00 00 00 76"),
            encode(linkedMapOf("method" to null, "v" to 0L)),
        )
    }

    private fun assertScalar(type: Int, expectedPayload: String, value: Any) {
        val payload = hex(expectedPayload)
        val expected = byteArrayOf(0, 0, 0, (20 + payload.size).toByte()) +
            hex("03 06 00 00 00 01 6d 65 74 68 6f 64 65") +
            byteArrayOf(type.toByte(), 1, 0, 0, 0, payload.size.toByte(), 0x76) + payload
        assertArrayEquals(expected, encode(mapOf("v" to value)))
    }

    private fun encode(fields: Map<String, Any?>): ByteArray =
        ByteArrayOutputStream().also { it.write(HtspCodec.encode("e", fields)) }.toByteArray()

    private fun hex(text: String): ByteArray =
        text.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
