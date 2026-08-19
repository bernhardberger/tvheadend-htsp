package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.messages.HtspMuxPacketMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageDecoded
import at.bernhardberger.tvheadend.htsp.messages.HtspTimestampClock
import at.bernhardberger.tvheadend.htsp.messages.decodeHtspServerMessage
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory

internal class HtspBinaryTest {
    @Volatile
    private var allocationSink: ByteArray? = null

    @Test
    fun boundedCopyHandlesCapacityOffsetsAndEmptyValues() {
        val binary = HtspBinary(byteArrayOf(1, 2, 3))
        val exact = ByteArray(binary.size)
        assertEquals(3, binary.copyInto(exact))
        assertArrayEquals(byteArrayOf(1, 2, 3), exact)

        val short = ByteArray(2)
        assertEquals(2, binary.copyInto(short))
        assertArrayEquals(byteArrayOf(1, 2), short)

        val offset = byteArrayOf(9, 9, 9, 9, 9)
        assertEquals(3, binary.copyInto(offset, destinationOffset = 1))
        assertArrayEquals(byteArrayOf(9, 1, 2, 3, 9), offset)
        assertEquals(0, binary.copyInto(offset, destinationOffset = offset.size))

        val empty = HtspBinary(ByteArray(0))
        assertEquals(0, empty.size)
        assertEquals(0, empty.copyInto(ByteArray(0)))
        assertEquals(0, empty.copyInto(ByteArray(1), destinationOffset = 1))

        assertThrows(IndexOutOfBoundsException::class.java) {
            binary.copyInto(ByteArray(3), destinationOffset = -1)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            binary.copyInto(ByteArray(3), destinationOffset = 4)
        }
    }

    @Test
    fun typedPacketPlaybackPathAllocatesOnlyOwnedAndFinalPayloadBuffers() {
        val payloadSize = 1024 * 1024
        val iterations = 32
        val frame = encodePacket(payloadSize)
        repeat(8) {
            val packet = decodeOwnedPacket(frame)
            allocationSink = ByteArray(packet.payload.size).also { destination ->
                packet.payload.copyInto(destination)
            }
        }

        val platformBean = ManagementFactory.getThreadMXBean()
        assertTrue(platformBean is ThreadMXBean)
        val allocationBean = platformBean as ThreadMXBean
        assertTrue(allocationBean.isThreadAllocatedMemorySupported)
        val allocationWasEnabled = allocationBean.isThreadAllocatedMemoryEnabled
        if (!allocationWasEnabled) {
            allocationBean.isThreadAllocatedMemoryEnabled = true
        }

        try {
            val threadId = Thread.currentThread().id
            val allocatedBefore = allocationBean.getThreadAllocatedBytes(threadId)
            var copied = 0L
            var checksum = 0
            repeat(iterations) { index ->
                val packet = decodeOwnedPacket(frame)
                val destination = ByteArray(packet.payload.size)
                copied += packet.payload.copyInto(destination)
                checksum += destination[index]
                allocationSink = destination
            }
            val allocatedBytes = allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore

            val payloadBytes = payloadSize.toLong() * iterations
            val ownedAndFinalBuffers = payloadBytes * 2
            assertEquals(payloadBytes, copied)
            assertTrue(checksum > 0)
            assertTrue(
                allocatedBytes < ownedAndFinalBuffers + payloadBytes / 2,
                "typed packet path allocated $allocatedBytes bytes for $payloadBytes payload bytes",
            )
        } finally {
            allocationSink = null
            if (!allocationWasEnabled) {
                allocationBean.isThreadAllocatedMemoryEnabled = false
            }
        }
    }

    @Test
    fun standaloneMapDecodeStillSnapshotsCodecPayload() {
        val message = HtspCodec.readMessage(ByteArrayInputStream(encodePacket(payloadSize = 3)))
        val packet =
            (decodeHtspServerMessage(message.fields) as HtspServerMessageDecoded)
                .message as HtspMuxPacketMessage

        message.rawPayload!![0] = 99

        assertArrayEquals(byteArrayOf(0, 1, 2), packet.payload.toByteArray())
    }

    private fun decodeOwnedPacket(frame: ByteArray): HtspMuxPacketMessage {
        val message = HtspCodec.readMessage(ByteArrayInputStream(frame))
        return (
            decodeHtspServerMessage(message) { HtspTimestampClock.MICROSECONDS }
                as HtspServerMessageDecoded
        ).message as HtspMuxPacketMessage
    }

    private fun encodePacket(payloadSize: Int): ByteArray =
        ByteArrayOutputStream().also { output ->
            HtspCodec.writeMessage(
                output = output,
                method = "muxpkt",
                fields = mapOf(
                    "subscriptionId" to 1L,
                    "stream" to 0L,
                    "duration" to 40L,
                    "payload" to ByteArray(payloadSize) { index -> index.toByte() },
                ),
            )
        }.toByteArray()
}
