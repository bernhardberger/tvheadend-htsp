package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelNowNextMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
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
            HtspTransportEvent.ServerMessage(HtspChannelNowNextMessage(1L, 0L, 0L), generation, 1L)
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
        val event = HtspTransportEvent.ServerMessage(HtspChannelNowNextMessage(1L, 0L, 0L), generation, 1L)
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

    @Test
    fun consecutiveMalformedReportsCoalesceEvenAtCapacityAndReleaseTheirSlotOnPoll() {
        val generation = HtspConnectionGeneration()
        val buffer = HtspMetadataEventBuffer(capacity = 1, byteCapacity = 10L)
        repeat(1_000) { buffer.offer(HtspTransportEvent.MalformedMetadata(generation, 1L), 0L) }
        assertEquals(HtspTransportEvent.MalformedMetadata(generation, 1_000L), buffer.poll())
        assertNull(buffer.poll())
        val completed = HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, generation, 1L)
        buffer.offer(completed, 10L)
        assertEquals(completed, buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun malformedReportsNeverCoalesceAcrossMessagesOrGenerations() {
        val generation = HtspConnectionGeneration()
        val replacement = HtspConnectionGeneration()
        val malformed = HtspTransportEvent.MalformedMetadata(generation, 1L)
        val metadata = HtspTransportEvent.ServerMessage(HtspChannelNowNextMessage(1L, 0L, 0L), generation, 1L)
        val completed = HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, generation, 2L)
        val newer = HtspTransportEvent.MalformedMetadata(replacement, 1L)
        val expected = listOf(malformed, metadata, malformed, completed, malformed, newer)
        val buffer = HtspMetadataEventBuffer(capacity = expected.size, byteCapacity = 10L)
        expected.forEach { buffer.offer(it, 1L) }
        expected.forEach { assertEquals(it, buffer.poll()) }
        assertNull(buffer.poll())
    }

    @Test
    fun malformedReportsConsumeSlotsButRetainNoFrameBytes() {
        val generation = HtspConnectionGeneration()
        val metadata = HtspTransportEvent.ServerMessage(HtspChannelNowNextMessage(1L, 0L, 0L), generation, 1L)
        val malformed = HtspTransportEvent.MalformedMetadata(generation, 1L)
        val buffer = HtspMetadataEventBuffer(capacity = 2, byteCapacity = 10L)
        buffer.offer(metadata, 10L)
        buffer.offer(malformed, 100L)
        // The malformed report fills the second slot despite retaining none of its frame.
        buffer.offer(metadata, 0L)
        assertEquals(metadata, buffer.poll())
        buffer.offer(metadata, 10L)
        assertEquals(malformed, buffer.poll())
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 1L), buffer.poll())
        assertEquals(metadata, buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun overflowCountsSkippedMessagesAndKeepsFirstLossBeforeLaterSyncMarker() {
        val generation = HtspConnectionGeneration()
        val replacement = HtspConnectionGeneration()
        val metadata = HtspTransportEvent.ServerMessage(HtspChannelNowNextMessage(1L, 0L, 0L), generation, 1L)
        val completed = HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, replacement, 1L)
        val buffer = HtspMetadataEventBuffer(capacity = 1, byteCapacity = 10L)
        buffer.offer(metadata, 10L)
        buffer.offer(HtspTransportEvent.MalformedMetadata(generation, 2L), 0L)
        buffer.offer(HtspTransportEvent.MalformedMetadata(generation, 3L), 0L)
        buffer.offer(metadata, 10L)
        buffer.offer(HtspTransportEvent.MalformedMetadata(replacement, 4L), 0L)
        assertEquals(metadata, buffer.poll())
        buffer.offer(completed, 10L)
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 6L), buffer.poll())
        assertEquals(HtspTransportEvent.MetadataOverflow(replacement, 4L), buffer.poll())
        assertEquals(completed, buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun overflowSeparatesMalformedRunsAndCoalescesLossesAtItsOriginalPosition() {
        val generation = HtspConnectionGeneration()
        val malformed = HtspTransportEvent.MalformedMetadata(generation, 1L)
        val completed = HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, generation, 1L)
        val buffer = HtspMetadataEventBuffer(capacity = 1, byteCapacity = 10L)
        buffer.offer(malformed, 0L)
        buffer.offer(completed, 10L)
        buffer.offer(malformed, 0L)
        assertEquals(malformed, buffer.poll())
        buffer.offer(malformed, 0L)
        buffer.offer(malformed, 0L)
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 2L), buffer.poll())
        assertEquals(HtspTransportEvent.MalformedMetadata(generation, 2L), buffer.poll())
        assertNull(buffer.poll())
    }
}
