package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspCodecRoundTripPropertyTest {

    @Test
    fun encoderInputBranches_decodeToNormalizedValues() {
        val fields = linkedMapOf<String, Any?>(
            "string" to "value",
            "binary" to byteArrayOf(1, 2),
            "uuid" to HtspWireUuid(ByteArray(16) { (15 - it).toByte() }),
            "boolean" to true,
            "double" to 2.25,
            "float" to 3.5f,
            "int" to 4,
            "long" to 5L,
            "short" to 6.toShort(),
            "byte" to 7.toByte(),
            "fallbackNumber" to FallbackNumber(8),
            "map" to linkedMapOf(9 to "converted key", "skipped" to null),
            "list" to listOf("kept", null, 10),
            "skipped" to null,
        )

        val decoded = roundTrip("branches", fields).fields

        assertWireValueEquals("branches", decoded["method"])
        assertWireValueEquals("value", decoded["string"])
        assertWireValueEquals(byteArrayOf(1, 2), decoded["binary"])
        assertWireValueEquals(fields["uuid"], decoded["uuid"])
        assertWireValueEquals(true, decoded["boolean"])
        assertWireValueEquals(2.25, decoded["double"])
        assertWireValueEquals(3.5, decoded["float"])
        assertWireValueEquals(4L, decoded["int"])
        assertWireValueEquals(5L, decoded["long"])
        assertWireValueEquals(6L, decoded["short"])
        assertWireValueEquals(7L, decoded["byte"])
        assertWireValueEquals(8L, decoded["fallbackNumber"])
        assertWireValueEquals(mapOf("9" to "converted key"), decoded["map"])
        assertWireValueEquals(listOf("kept", 10L), decoded["list"])
        assertTrue("skipped" !in decoded)
    }

    @Test
    fun seededNestedValues_roundTripToNormalizedDomain() {
        val random = Random(0x48545350L)
        repeat(250) { iteration ->
            val fields = linkedMapOf<String, Any?>()
            repeat(1 + random.nextInt(6)) { index -> fields["field$index"] = randomValue(random, depth = 0) }

            val decoded = roundTrip("property", fields).fields
            val expected = linkedMapOf<String, Any?>("method" to "property")
            fields.forEach { (name, value) -> normalize(value)?.let { expected[name] = it } }
            assertWireValueEquals(expected, decoded, "iteration $iteration")
        }
    }

    private fun roundTrip(method: String, fields: Map<String, Any?>): HtspWireMessage {
        val bytes = ByteArrayOutputStream().also { HtspCodec.writeMessage(it, method, fields) }.toByteArray()
        return HtspCodec.readMessage(ByteArrayInputStream(bytes))
    }

    private fun randomValue(random: Random, depth: Int): Any? {
        val scalarKinds = if (depth < 3) 12 else 10
        return when (random.nextInt(scalarKinds)) {
            0 -> null
            1 -> random.nextLong()
            2 -> random.nextInt()
            3 -> random.nextInt().toShort()
            4 -> random.nextInt().toByte()
            5 -> random.nextBoolean()
            6 -> random.nextDouble()
            7 -> random.nextFloat()
            8 -> "text-${random.nextInt(100)}-Ž"
            9 -> ByteArray(random.nextInt(6)).also(random::nextBytes)
            10 -> linkedMapOf("child" to randomValue(random, depth + 1), "null" to null)
            else -> listOf(randomValue(random, depth + 1), null, randomValue(random, depth + 1))
        }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null -> null
        is Int, is Short, is Byte -> (value as Number).toLong()
        is Float -> value.toDouble()
        is Map<*, *> -> value.entries.mapNotNull { (key, child) ->
            normalize(child)?.let { key.toString() to it }
        }.toMap(LinkedHashMap())
        is List<*> -> value.mapNotNull(::normalize)
        else -> value
    }

    private fun assertWireValueEquals(expected: Any?, actual: Any?, context: String = "") {
        when {
            expected is ByteArray && actual is ByteArray ->
                assertTrue(expected.contentEquals(actual), context)
            expected is HtspWireUuid && actual is HtspWireUuid ->
                assertTrue(expected.bytes().contentEquals(actual.bytes()), context)
            expected is Map<*, *> && actual is Map<*, *> -> {
                assertEquals(expected.keys, actual.keys, context)
                expected.keys.forEach { key -> assertWireValueEquals(expected[key], actual[key], "$context/$key") }
            }
            expected is List<*> && actual is List<*> -> {
                assertEquals(expected.size, actual.size, context)
                expected.indices.forEach { index ->
                    assertWireValueEquals(expected[index], actual[index], "$context/$index")
                }
            }
            else -> assertEquals(expected, actual, context)
        }
    }

    private class FallbackNumber(private val value: Long) : Number() {
        override fun toByte(): Byte = value.toByte()
        override fun toDouble(): Double = value.toDouble()
        override fun toFloat(): Float = value.toFloat()
        override fun toInt(): Int = value.toInt()
        override fun toLong(): Long = value
        override fun toShort(): Short = value.toShort()
    }
}
