package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.wire.*

import java.io.EOFException
import java.io.InputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HtspCodecDeterministicFuzzTest {

    @Test
    fun goldenFrameMutations_haveOnlyBoundedCodecOutcomes() {
        val random = Random(MUTATION_SEED)
        GOLDEN_FRAMES.forEach { resource ->
            val golden = loadHtspGoldenFrame(resource)
            repeat(MUTATION_ITERATIONS_PER_FRAME) { iteration ->
                val mode = MutationMode.entries[iteration % MutationMode.entries.size]
                val candidate = mutate(golden, mode, random, iteration)
                classifyBounded(
                    candidate,
                    "mutation/$resource/$mode",
                    MUTATION_SEED,
                    iteration,
                    expectedUnread = if (mode == MutationMode.EXTENSION) candidate.size - golden.size else null,
                )
            }
        }
    }

    @Test
    fun generatedSmallValidLengthBodies_haveOnlyBoundedCodecOutcomes() {
        val random = Random(GENERATION_SEED)
        repeat(GENERATION_ITERATIONS) { iteration ->
            val body = ByteArray(1 + random.nextInt(MAX_GENERATED_BODY_BYTES)).also(random::nextBytes)
            classifyBounded(frame(body), "generation", GENERATION_SEED, iteration)
        }
    }

    @Test
    fun physicalExtensionBeyondDeclaredRoot_remainsUnread() {
        val body = field(type = 3, name = "value", data = "ok".encodeToByteArray())
        val extension = byteArrayOf(9, 8, 7, 6)
        val input = CountingGuardInputStream(frame(body) + extension, 4L + body.size)

        val decoded = HtspCodec.readMessage(input)

        assertEquals("ok", decoded.fields["value"])
        assertEquals(4L + body.size, input.consumed)
        assertEquals(extension.size, input.available())
    }

    @Test
    fun singleByteReadAtPhysicalDeclaredBoundary_throwsGuardFailure() {
        val input = CountingGuardInputStream(byteArrayOf(1), boundary = 1L)
        assertEquals(1, input.read())

        val failure = assertThrows(IllegalStateException::class.java) { input.read() }

        assertEquals("codec attempted to consume beyond declared root boundary 1", failure.message)
    }

    @Test
    fun positiveLengthBulkReadAtPhysicalDeclaredBoundary_throwsGuardFailure() {
        val input = CountingGuardInputStream(byteArrayOf(1), boundary = 1L)
        assertEquals(1, input.read(ByteArray(1), 0, 1))

        val failure = assertThrows(IllegalStateException::class.java) {
            input.read(ByteArray(1), 0, 1)
        }

        assertEquals("codec attempted to consume beyond declared root boundary 1", failure.message)
    }

    private fun mutate(golden: ByteArray, mode: MutationMode, random: Random, iteration: Int): ByteArray = when (mode) {
        MutationMode.BYTE_FLIP -> golden.copyOf().also { bytes ->
            val index = random.nextInt(bytes.size)
            bytes[index] = (bytes[index].toInt() xor (1 shl random.nextInt(8))).toByte()
        }
        MutationMode.TRUNCATION -> golden.copyOf(random.nextInt(golden.size + 1))
        MutationMode.EXTENSION -> golden + ByteArray(1 + random.nextInt(12)).also(random::nextBytes)
        MutationMode.ROOT_LENGTH -> golden.copyOf().also { bytes ->
            writeU32(bytes, 0, TARGET_LENGTHS[iteration % TARGET_LENGTHS.size])
        }
        MutationMode.FIRST_FIELD_LENGTH -> golden.copyOf().also { bytes ->
            check(bytes.size >= 10)
            writeU32(bytes, 6, TARGET_LENGTHS[iteration % TARGET_LENGTHS.size])
        }
    }

    private fun classifyBounded(
        frame: ByteArray,
        mode: String,
        seed: Long,
        iteration: Int,
        expectedUnread: Int? = null,
    ) {
        val boundary = declaredBoundary(frame)
        val input = CountingGuardInputStream(frame, boundary)
        val diagnostic = "mode=$mode seed=$seed iteration=$iteration frame=${frame.toHex()}"
        try {
            HtspCodec.readMessage(input)
        } catch (_: EOFException) {
            // Accepted finite truncation outcome.
        } catch (_: HtspFramingException) {
            // Accepted finite malformed-framing outcome.
        } catch (unexpected: Throwable) {
            fail("Unexpected ${unexpected::class.java.name}; $diagnostic")
        }
        assertTrue("Read beyond declared root; $diagnostic", input.consumed <= boundary)
        expectedUnread?.let { assertEquals("Extension was consumed; $diagnostic", it, input.available()) }
    }

    private fun declaredBoundary(bytes: ByteArray): Long {
        if (bytes.size < 4) return 4L
        val declared =
            ((bytes[0].toLong() and 0xff) shl 24) or
                ((bytes[1].toLong() and 0xff) shl 16) or
                ((bytes[2].toLong() and 0xff) shl 8) or
                (bytes[3].toLong() and 0xff)
        return 4L + declared
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private enum class MutationMode {
        BYTE_FLIP,
        TRUNCATION,
        EXTENSION,
        ROOT_LENGTH,
        FIRST_FIELD_LENGTH,
    }

    private class CountingGuardInputStream(
        private val bytes: ByteArray,
        private val boundary: Long,
    ) : InputStream() {
        private var position = 0
        var consumed: Long = 0
            private set

        override fun read(): Int {
            check(consumed < boundary) { "codec attempted to consume beyond declared root boundary $boundary" }
            if (position >= bytes.size) return -1
            consumed++
            return bytes[position++].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            check(consumed < boundary) { "codec attempted to consume beyond declared root boundary $boundary" }
            if (position >= bytes.size) return -1
            val permitted = (boundary - consumed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val count = minOf(length, bytes.size - position, permitted)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            consumed += count
            return count
        }

        override fun available(): Int = bytes.size - position
    }

    private companion object {
        const val MUTATION_SEED = 0x43335f4d55544154L
        const val GENERATION_SEED = 0x43335f47454e4552L
        const val MUTATION_ITERATIONS_PER_FRAME = 100
        const val GENERATION_ITERATIONS = 300
        const val MAX_GENERATED_BODY_BYTES = 128
        val GOLDEN_FRAMES = listOf(
            "hello.hex",
            "boolean-false.hex",
            "scalar-types.hex",
            "muxpkt.hex",
            "nested.hex",
        )
        val TARGET_LENGTHS = longArrayOf(0, 1, 7, 31, 255, 32L * 1024 * 1024, 32L * 1024 * 1024 + 1, 0xffff_ffffL)
    }
}
