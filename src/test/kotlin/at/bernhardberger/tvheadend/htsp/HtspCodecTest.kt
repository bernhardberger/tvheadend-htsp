package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.SequenceInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspCodecTest {

    @Test
    fun framedRoundTrip_preservesSupportedValuesAndMuxPayload() {
        val payload = byteArrayOf(0x47, 0x01, 0x02)
        val output = ByteArrayOutputStream()

        output.write(HtspCodec.encode("muxpkt", mapOf(
                "seq" to 7,
                "signed" to -2L,
                "zero" to 0,
                "title" to "Živě",
                "payload" to payload,
                "enabled" to true,
                "nested" to mapOf("value" to 9),
                "items" to listOf("first", 2),
            )))

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
        assertEquals(-2L, decoded.fields["signed"])
        assertEquals(0L, decoded.fields["zero"])
        assertEquals("Živě", decoded.fields["title"])
        assertEquals(true, decoded.fields["enabled"])
        assertEquals(9L, (decoded.fields["nested"] as Map<*, *>)["value"])
        assertEquals(listOf("first", 2L), decoded.fields["items"])
        assertArrayEquals(payload, decoded.rawPayload)
    }

    @Test
    fun replySequencesRetainTheFullUnsignedDomainWithoutNumericAliasing() {
        listOf(0L, 7L, Int.MAX_VALUE.toLong(), 0x8000_0000L, 0xFFFF_FFFFL).forEach { seq ->
            val output = ByteArrayOutputStream()
            output.write(HtspCodec.encode("reply", mapOf("seq" to seq)))
            val decoded = HtspCodec.readMessage(ByteArrayInputStream(output.toByteArray()))
            assertEquals(seq.toInt(), decoded.seq)
            assertEquals(seq, decoded.fields["seq"])
        }
        listOf(-1L, 0x1_0000_0007L, Long.MAX_VALUE, "7", true).forEach { seq ->
            val output = ByteArrayOutputStream()
            output.write(HtspCodec.encode("reply", mapOf("seq" to seq)))
            assertEquals(null, HtspCodec.readMessage(ByteArrayInputStream(output.toByteArray())).seq)
        }
        listOf(7.0, 7.9).forEach { seq ->
            val bits = seq.toRawBits()
            val rawTypeSix = frame(field(6, "seq", ByteArray(8) { (bits ushr (it * 8)).toByte() }))
            assertEquals(null, HtspCodec.readMessage(rawTypeSix.inputStream()).seq)
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

    @Test
    fun headersAndScalarsUseBulkReadsWithoutReadingIntoTheNextFrame() {
        val bytes = encoded("first", mapOf("value" to -2L, "nested" to listOf(3L, true, false)))
        val next = encoded("second", emptyMap())
        val source = ByteArrayInputStream(bytes + next)
        val input = object : InputStream() {
            override fun read(): Int = error("Complete bulk reads must not fall back to single-byte reads")
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len)
        }
        val first = HtspCodec.readMessage(input)
        assertEquals(-2L, first.fields["value"])
        assertEquals(listOf(3L, true, false), first.fields["nested"])
        assertEquals(next.size, source.available())
        assertEquals("second", HtspCodec.readMessage(input).method)
    }

    @Test
    fun oneByteAndZeroLengthReadsPreserveNestedFieldsAndBinaryOwnership() {
        val payload = ByteArray(188) { it.toByte() }
        val bytes = encoded("muxpkt", mapOf("payload" to payload, "box" to mapOf("items" to listOf(-1L, "Živě"))))
        val source = ByteArrayInputStream(bytes + encoded("muxpkt", mapOf("payload" to byteArrayOf(9))))
        var zeroNext = false
        val input = object : InputStream() {
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                zeroNext = !zeroNext
                return if (zeroNext) 0 else source.read(b, off, minOf(1, len))
            }
        }
        val first = HtspCodec.readMessage(input)
        assertEquals(mapOf("items" to listOf(-1L, "Živě")), first.fields["box"])
        assertTrue(first.rawPayload === first.fields["payload"])
        assertArrayEquals(byteArrayOf(9), HtspCodec.readMessage(input).rawPayload)
        assertArrayEquals(payload, first.rawPayload)
        assertEquals(0, source.available())
    }

    @Test
    fun scratchBoundaryStringsAndMaximumUtf8NameRemainIndependent() {
        val name = "é".repeat(127) + "a"
        val fields = linkedMapOf<String, Any?>(name to "first", "emptyBinary" to byteArrayOf())
        for (size in listOf(0, 255, 256, 257)) fields["text$size"] = "x".repeat(size)
        val decoded = HtspCodec.readMessage(encoded("strings", fields).inputStream())
        assertEquals("first", decoded.fields[name])
        for (size in listOf(0, 255, 256, 257)) assertEquals("x".repeat(size), decoded.fields["text$size"])
        assertArrayEquals(byteArrayOf(), decoded.fields["emptyBinary"] as ByteArray)
    }

    @Test
    fun duplicateMapKeysRemainLastWinsAndListNamesRemainIgnored() {
        val duplicates = field(3, "same", "first".encodeToByteArray()) +
            field(3, "same", "last".encodeToByteArray()) + field(3, "", "ignored".encodeToByteArray())
        val body = duplicates + field(1, "map", duplicates) + field(5, "list", duplicates) +
            field(1, "nested", field(2, "seq", byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 99)))
        val decoded = HtspCodec.readMessage(frame(body).inputStream())
        assertEquals("last", decoded.fields["same"])
        assertEquals(mapOf("same" to "last"), decoded.fields["map"])
        assertEquals(listOf("first", "last", "ignored"), decoded.fields["list"])
        assertEquals(mapOf("seq" to 1L), decoded.fields["nested"])
        assertEquals(setOf("same", "map", "list", "nested"), decoded.fields.keys)
    }

    @Test
    fun shortRootAndNestedHeadersPreserveFramingOffsetsAndPhysicalEofPriority() {
        for (container in listOf(0, 1, 5)) {
            for (declared in 1..5) {
                for (physical in 0..declared) {
                    val prefix = if (container == 0) byteArrayOf() else
                        fieldHeader(container, 1, declared) + byteArrayOf('x'.code.toByte())
                    val bytes = frame(prefix + ByteArray(physical), declaredLength = prefix.size + declared)
                    if (physical < declared) {
                        val failure = assertThrows(EOFException::class.java) { HtspCodec.readMessage(bytes.inputStream()) }
                        assertEquals("EOF while reading bounded HTSP frame", failure.message)
                    } else {
                        val failure = assertThrows(HtspFramingException::class.java) {
                            HtspCodec.readMessage(bytes.inputStream())
                        }
                        assertEquals("field byte exceeds enclosing frame", failure.failure)
                        assertEquals(4 + prefix.size + declared, failure.byteOffset)
                    }
                }
            }
        }
    }

    @Test
    fun bulkReadsKeepRegionSpecificEofMessages() {
        val name = frame(fieldHeader(3, 3, 0) + byteArrayOf(1), declaredLength = 9)
        assertEquals("EOF while reading field name (3 bytes, read=1)", eofMessage(name))
        val text = frame(fieldHeader(3, 0, 3) + byteArrayOf(1), declaredLength = 9)
        assertEquals("EOF while reading string (3 bytes, read=1)", eofMessage(text))
        val binary = frame(fieldHeader(4, 0, 3) + byteArrayOf(1), declaredLength = 9)
        assertEquals("EOF while reading binary (3 bytes, read=1)", eofMessage(binary))
        val integer = frame(fieldHeader(2, 0, 3) + byteArrayOf(1), declaredLength = 9)
        assertEquals("EOF while reading bounded HTSP frame", eofMessage(integer))
        val bool = frame(fieldHeader(7, 0, 3) + byteArrayOf(1), declaredLength = 9)
        assertEquals("EOF while draining boolean length mismatch", eofMessage(bool))
    }

    @Test
    fun invalidFieldLengthIsRejectedBeforeAttemptingItsMissingNameOrBody() {
        val input = frame(fieldHeader(3, 2, 1), declaredLength = 8).inputStream()
        val failure = assertThrows(HtspFramingException::class.java) { HtspCodec.readMessage(input) }
        assertEquals("field name and data exceed enclosing frame", failure.failure)
        assertEquals(10, failure.byteOffset)
    }

    @Test
    fun maximumRootLengthReadsBinaryDirectlyAndOversizeNeverReadsBody() {
        val maximum = 32 * 1024 * 1024
        val payloadSize = maximum - 7
        val prefix = frameWithDeclaredLength(maximum) + fieldHeader(4, 1, payloadSize) + byteArrayOf('b'.code.toByte())
        var remaining = payloadSize
        val zeros = object : InputStream() {
            override fun read(): Int = error("Binary must be read in bulk")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(remaining, len)
                b.fill(0, off, off + count)
                remaining -= count
                return count
            }
        }
        val decoded = HtspCodec.readMessage(SequenceInputStream(prefix.inputStream(), zeros))
        assertEquals(payloadSize, (decoded.fields["b"] as ByteArray).size)
        assertEquals(0, remaining)
        val oversized = SequenceInputStream(frameWithDeclaredLength(maximum + 1).inputStream(), object : InputStream() {
            override fun read(): Int = error("Invalid root length must be rejected before reading its body")
        })
        assertEquals("invalid root length", assertThrows(HtspFramingException::class.java) {
            HtspCodec.readMessage(oversized)
        }.failure)
    }

    private fun encoded(method: String, fields: Map<String, Any?>): ByteArray =
        ByteArrayOutputStream().also { it.write(HtspCodec.encode(method, fields)) }.toByteArray()

    private fun eofMessage(bytes: ByteArray): String? =
        assertThrows(EOFException::class.java) { HtspCodec.readMessage(bytes.inputStream()) }.message
}
