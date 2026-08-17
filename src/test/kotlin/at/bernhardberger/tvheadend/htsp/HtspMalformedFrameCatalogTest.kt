package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspMalformedFrameCatalogTest {

    @Test
    fun rootLengthZero_isInvalidRootFramingFailure() {
        assertFraming(frameWithDeclaredLength(0), "invalid root length", 0)
    }

    @Test
    fun rootLengthAbove32MiB_isInvalidRootFramingFailure() {
        assertFraming(frameWithDeclaredLength(32 * 1024 * 1024 + 1), "invalid root length", 0)
    }

    @Test
    fun eofMidRootHeader_isEof() {
        assertThrows(EOFException::class.java) {
            HtspCodec.readMessage(ByteArrayInputStream(byteArrayOf(0, 0, 0)))
        }
    }

    @Test
    fun eofMidFieldName_isEof() {
        val body = fieldHeader(type = TYPE_STR, nameLength = 2, dataLength = 1) + byteArrayOf('a'.code.toByte())
        assertThrows(EOFException::class.java) {
            HtspCodec.readMessage(ByteArrayInputStream(frame(body, declaredLength = 9)))
        }
    }

    @Test
    fun eofMidFieldData_isEof() {
        val body = fieldHeader(type = TYPE_BIN, nameLength = 1, dataLength = 2) +
            byteArrayOf('b'.code.toByte(), 1)
        assertThrows(EOFException::class.java) {
            HtspCodec.readMessage(ByteArrayInputStream(frame(body, declaredLength = 9)))
        }
    }

    @Test
    fun fieldNameAndDataBeyondEnclosingRoot_isFramingFailure() {
        val body = fieldHeader(type = TYPE_STR, nameLength = 2, dataLength = 1) +
            byteArrayOf('a'.code.toByte(), 'b'.code.toByte())
        assertFraming(
            frame(body),
            "field name and data exceed enclosing frame",
            10,
        )
    }

    @Test
    fun fieldNameAndDataBeyondEnclosingContainer_isFramingFailure() {
        val malformedChild = fieldHeader(type = TYPE_STR, nameLength = 2, dataLength = 1) +
            byteArrayOf('a'.code.toByte(), 'b'.code.toByte())
        val failure = assertThrows(HtspFramingException::class.java) {
            decode(frame(field(TYPE_MAP, "box", malformedChild)))
        }
        assertEquals("field name and data exceed enclosing frame", failure.failure)
        assertEquals(19, failure.byteOffset)
    }

    @Test
    fun nestingBeyond32_isFramingFailure() {
        val failure = assertThrows(HtspFramingException::class.java) {
            decode(frame(nestedMapBody(containerCount = 33)))
        }
        assertEquals("nesting exceeds limit", failure.failure)
        assertTrue(failure.byteOffset > 4)
    }

    @Test
    fun nestingAt32_remainsParseable() {
        val decoded = decode(frame(nestedMapBody(containerCount = 32)))
        assertTrue(decoded.fields.containsKey("level32"))
    }

    @Test
    fun unknownTypeId_decodesExactRawBytes() {
        val raw = byteArrayOf(0, 0x7f, -1)
        val decoded = decode(frame(field(type = 0x7f, name = "raw", data = raw)))
        assertArrayEquals(raw, decoded.fields["raw"] as ByteArray)
    }

    @Test
    fun doubleWrongLength_decodesZeroAndPreservesFollowingFieldAlignment() {
        val body = field(TYPE_DBL, "ratio", byteArrayOf(1, 2, 3)) + field(TYPE_STR, "next", "ok".encodeToByteArray())
        val decoded = decode(frame(body))
        assertEquals(0.0, decoded.fields["ratio"])
        assertEquals("ok", decoded.fields["next"])
    }

    @Test
    fun signed64ZeroLength_decodesZero() {
        val decoded = decode(frame(field(TYPE_S64, "value", byteArrayOf())))
        assertEquals(0L, decoded.fields["value"])
    }

    @Test
    fun signed64LongerThanEight_usesLowEightLittleEndianBytesAndPreservesAlignment() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 99, 100)
        val body = field(TYPE_S64, "value", data) + field(TYPE_STR, "next", "ok".encodeToByteArray())
        val decoded = decode(frame(body))
        assertEquals(0x0807060504030201L, decoded.fields["value"])
        assertEquals("ok", decoded.fields["next"])
    }

    @Test
    fun booleanLengthGreaterThanOne_usesFirstByteAndPreservesNextFrameAlignment() {
        val first = frame(field(TYPE_BOOL, "flag", byteArrayOf(1, 55, 66)))
        val second = loadHtspGoldenFrame("hello.hex")
        val input = ByteArrayInputStream(first + second)
        assertEquals(true, HtspCodec.readMessage(input).fields["flag"])
        assertEquals("hello", HtspCodec.readMessage(input).method)
        assertEquals(0, input.available())
    }

    @Test
    fun oneResidualRootByteAfterCompleteField_isNotSilentlyDrained() {
        val complete = field(TYPE_STR, "value", "ok".encodeToByteArray())
        assertThrows(HtspFramingException::class.java) {
            decode(frame(complete + byteArrayOf(0x55)))
        }.also { failure ->
            assertEquals("field byte exceeds enclosing frame", failure.failure)
            assertEquals(complete.size + 5, failure.byteOffset)
        }
    }

    private fun assertFraming(bytes: ByteArray, expectedFailure: String, expectedOffset: Int) {
        val failure = assertThrows(HtspFramingException::class.java) { decode(bytes) }
        assertEquals(expectedFailure, failure.failure)
        assertEquals(expectedOffset, failure.byteOffset)
    }

    private fun decode(bytes: ByteArray): HtspWireMessage = HtspCodec.readMessage(ByteArrayInputStream(bytes))

    private fun nestedMapBody(containerCount: Int): ByteArray {
        var body = field(TYPE_STR, "leaf", "ok".encodeToByteArray())
        for (level in 1..containerCount) body = field(TYPE_MAP, "level$level", body)
        return body
    }

    private companion object {
        const val TYPE_MAP = 1
        const val TYPE_S64 = 2
        const val TYPE_STR = 3
        const val TYPE_BIN = 4
        const val TYPE_DBL = 6
        const val TYPE_BOOL = 7
    }
}

internal fun frame(body: ByteArray, declaredLength: Int = body.size): ByteArray =
    frameWithDeclaredLength(declaredLength) + body

internal fun frameWithDeclaredLength(declaredLength: Int): ByteArray = byteArrayOf(
    (declaredLength ushr 24).toByte(),
    (declaredLength ushr 16).toByte(),
    (declaredLength ushr 8).toByte(),
    declaredLength.toByte(),
)

internal fun field(type: Int, name: String, data: ByteArray): ByteArray {
    val nameBytes = name.encodeToByteArray()
    return fieldHeader(type, nameBytes.size, data.size) + nameBytes + data
}

internal fun fieldHeader(type: Int, nameLength: Int, dataLength: Int): ByteArray = byteArrayOf(
    type.toByte(),
    nameLength.toByte(),
    (dataLength ushr 24).toByte(),
    (dataLength ushr 16).toByte(),
    (dataLength ushr 8).toByte(),
    dataLength.toByte(),
)
