package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class HtspMetadataEventBufferTest {
    @Test
    fun reconnectLossesKeepSeparateOrderedGenerationMarkers() {
        val old = HtspConnectionGeneration()
        val newer = HtspConnectionGeneration()
        val buffer = HtspMetadataEventBuffer(1, 10L)
        fun event(generation: HtspConnectionGeneration) =
            HtspTransportEvent.ServerMessage(HtspChannelAddMessage(channelId = 1L), generation, 1L)
        buffer.offer(event(old), 10L)
        repeat(2) { buffer.offer(event(old), 10L) }
        repeat(3) { buffer.offer(event(newer), 10L) }
        assertEquals(event(old), buffer.poll())
        assertEquals(HtspTransportEvent.MetadataOverflow(old, 2L), buffer.poll())
        assertEquals(HtspTransportEvent.MetadataOverflow(newer, 3L), buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun bytesLimitIndependentlyOfCountAndMarkersAreUnbudgeted() {
        val generation = HtspConnectionGeneration()
        val event = HtspTransportEvent.ServerMessage(HtspChannelAddMessage(channelId = 1L), generation, 1L)
        val failure = HtspTransportEvent.ConnectionFailure(
            HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE), generation,
        )
        val buffer = HtspMetadataEventBuffer(capacity = 10, byteCapacity = 10L)
        buffer.offer(event, 6L)
        buffer.offer(event, 6L)
        buffer.offer(event, 11L)
        buffer.offer(failure, 0L)
        assertEquals(event, buffer.poll())
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 2L), buffer.poll())
        assertEquals(failure, buffer.poll())
        buffer.offer(event, 10L)
        assertEquals(event, buffer.poll())
        assertNull(buffer.poll())
    }
}
