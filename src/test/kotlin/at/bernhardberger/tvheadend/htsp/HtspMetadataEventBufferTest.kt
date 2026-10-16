package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelNowNextMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class HtspMetadataEventBufferTest {
    @Test
    fun chunksKeepOrderAcrossBoundaries() {
        val generation = HtspConnectionGeneration()
        val expected = (1L..600L).map { metadata(generation, it) }
        val buffer = HtspMetadataEventBuffer(600, 600L)
        expected.forEach { buffer.offer(it, 1L) }
        val received = mutableListOf<HtspTransportEvent>()
        for (size in listOf(256, 256, 88)) {
            val chunk = buffer.drain(256)
            assertEquals(size, chunk.size)
            chunk.forEach {
                received += buffer.eventForEmission(it)
                buffer.emitting()
            }
        }
        assertEquals(expected, received)
        assertEquals(emptyList<HtspMetadataEventBuffer.Entry>(), buffer.drain(256))
    }

    @Test
    fun drainedButUnemittedEntriesRetainBothBudgetsAndOverflowPosition() {
        val generation = HtspConnectionGeneration()
        // Independently exhaust event count, then bytes, while the whole queue is in flight.
        for ((capacity, byteCapacity) in listOf(2 to 100L, 100 to 10L)) {
            val buffer = HtspMetadataEventBuffer(capacity, byteCapacity)
            buffer.offer(metadata(generation, 1L), 5L)
            buffer.offer(metadata(generation, 2L), 5L)
            val chunk = buffer.drain(256)
            buffer.offer(metadata(generation, 3L), 5L)
            assertEquals(metadata(generation, 1L), buffer.eventForEmission(chunk[0]))
            buffer.emitting()
            buffer.offer(metadata(generation, 4L), 5L)
            buffer.offer(metadata(generation, 5L), 5L)
            assertEquals(metadata(generation, 2L), buffer.eventForEmission(chunk[1]))
            buffer.emitting()
            val remaining = buffer.drain(256)
            assertEquals(listOf(HtspTransportEvent.MetadataOverflow(generation, 2L), metadata(generation, 4L)),
                remaining.map(buffer::eventForEmission))
            remaining.forEach { buffer.emitting() }
        }
    }

    @Test
    fun overflowDrainedIntoChunkStillCoalescesUntilItsEmission() {
        val generation = HtspConnectionGeneration()
        val buffer = HtspMetadataEventBuffer(1, 10L)
        buffer.offer(metadata(generation, 1L), 10L)
        buffer.offer(metadata(generation, 2L), 10L)
        val chunk = buffer.drain(256)
        buffer.offer(metadata(generation, 3L), 10L)
        buffer.eventForEmission(chunk[0])
        buffer.emitting()
        buffer.offer(metadata(generation, 4L), 10L)
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 2L), buffer.eventForEmission(chunk[1]))
        buffer.emitting()
        assertEquals(listOf(metadata(generation, 4L)), buffer.drain(256).map(buffer::eventForEmission))
    }

    @Test
    fun malformedCoalescingStopsAtDrainedTailAndStillChargesItsSlot() {
        val generation = HtspConnectionGeneration()
        for (capacity in listOf(1, 2)) {
            val buffer = HtspMetadataEventBuffer(capacity, 10L)
            buffer.offer(HtspTransportEvent.MalformedMetadata(generation, 1L), 0L)
            val chunk = buffer.drain(256)
            repeat(2) { buffer.offer(HtspTransportEvent.MalformedMetadata(generation, 1L), 0L) }
            assertEquals(HtspTransportEvent.MalformedMetadata(generation, 1L), buffer.eventForEmission(chunk.single()))
            buffer.emitting()
            val expected = if (capacity == 1) HtspTransportEvent.MetadataOverflow(generation, 2L)
                else HtspTransportEvent.MalformedMetadata(generation, 2L)
            assertEquals(listOf(expected), buffer.drain(256).map(buffer::eventForEmission))
        }
    }

    @Test
    fun onlyEmptyToNonemptyTransitionsWakeTheCollector() {
        val generation = HtspConnectionGeneration()
        var wakeups = 0
        val buffer = HtspMetadataEventBuffer(1, 10L) { wakeups++ }
        repeat(10) { buffer.offer(metadata(generation, it.toLong()), 1L) }
        assertEquals(1, wakeups)
        val chunk = buffer.drain(256)
        // An in-flight overflow can absorb a drop without putting anything in the queue.
        buffer.offer(metadata(generation, 10L), 1L)
        assertEquals(1, wakeups)
        chunk.forEach { buffer.eventForEmission(it); buffer.emitting() }
        buffer.offer(metadata(generation, 11L), 1L)
        assertEquals(2, wakeups)
    }

    @Test
    fun transitionWakeupsLoseNoEventsWhenOffersRaceDrainingToEmpty() = runBlocking {
        withTimeout(10_000L) {
            val generation = HtspConnectionGeneration()
            val monitor = HtspAttemptMonitor {}
            val buffer = HtspMetadataEventBuffer(32, 32L, monitor::queueWakeup)
            val drained = Channel<Unit>(Channel.RENDEZVOUS)
            val producer = launch(Dispatchers.Default) {
                repeat(2_000) { batch ->
                    repeat(16) { offset ->
                        monitor.withAttemptLock { buffer.offer(metadata(generation, batch * 16L + offset), 1L) }
                        if (offset % 3 == 0) yield()
                    }
                    drained.receive()
                }
            }
            var received = 0L
            while (received < 32_000L) {
                val chunk = monitor.withAttemptLock { buffer.drain(7) }
                if (chunk.isEmpty()) {
                    yield() // Exercise offer between the locked empty check and receive.
                    buffer.eventsAvailable.receive()
                } else {
                    for (entry in chunk) {
                        assertEquals(metadata(generation, received++), entry.event)
                        buffer.emitting()
                        if (received % 16 == 0L) drained.send(Unit)
                    }
                }
            }
            producer.join()
            assertEquals(emptyList<HtspMetadataEventBuffer.Entry>(), monitor.withAttemptLock { buffer.drain(7) })
        }
    }

    private fun metadata(generation: HtspConnectionGeneration, sequence: Long) =
        HtspTransportEvent.ServerMessage(HtspChannelNowNextMessage(sequence, 0L, 0L), generation, sequence + 1L)

    private fun emitNext(buffer: HtspMetadataEventBuffer): HtspTransportEvent? =
        buffer.drain(1).singleOrNull()?.let { entry ->
            buffer.eventForEmission(entry).also { buffer.emitting() }
        }

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
        val chunk = buffer.drain(256)
        assertEquals(3, chunk.size)
        // Both generations' markers are in flight; only the newer marker absorbs new losses.
        repeat(4) { buffer.offer(event(newer), 10L) }
        assertEquals(event(old), buffer.eventForEmission(chunk.removeFirst()))
        buffer.emitting()
        assertEquals(HtspTransportEvent.MetadataOverflow(old, 2L), buffer.eventForEmission(chunk.removeFirst()))
        buffer.emitting()
        assertEquals(HtspTransportEvent.MetadataOverflow(newer, 7L), buffer.eventForEmission(chunk.removeFirst()))
        buffer.emitting()
        assertEquals(emptyList<HtspMetadataEventBuffer.Entry>(), buffer.drain(256))
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
        assertEquals(event, emitNext(buffer))
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 2L), emitNext(buffer))
        assertEquals(failure, emitNext(buffer))
        buffer.offer(event, 10L)
        assertEquals(event, emitNext(buffer))
        assertNull(emitNext(buffer))
    }

    @Test
    fun consecutiveMalformedReportsCoalesceEvenAtCapacityAndReleaseTheirSlotOnEmission() {
        val generation = HtspConnectionGeneration()
        val buffer = HtspMetadataEventBuffer(capacity = 1, byteCapacity = 10L)
        repeat(1_000) { buffer.offer(HtspTransportEvent.MalformedMetadata(generation, 1L), 0L) }
        assertEquals(HtspTransportEvent.MalformedMetadata(generation, 1_000L), emitNext(buffer))
        assertNull(emitNext(buffer))
        val completed = HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, generation, 1L)
        buffer.offer(completed, 10L)
        assertEquals(completed, emitNext(buffer))
        assertNull(emitNext(buffer))
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
        expected.forEach { assertEquals(it, emitNext(buffer)) }
        assertNull(emitNext(buffer))
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
        assertEquals(metadata, emitNext(buffer))
        buffer.offer(metadata, 10L)
        assertEquals(malformed, emitNext(buffer))
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 1L), emitNext(buffer))
        assertEquals(metadata, emitNext(buffer))
        assertNull(emitNext(buffer))
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
        assertEquals(metadata, emitNext(buffer))
        buffer.offer(completed, 10L)
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 6L), emitNext(buffer))
        assertEquals(HtspTransportEvent.MetadataOverflow(replacement, 4L), emitNext(buffer))
        assertEquals(completed, emitNext(buffer))
        assertNull(emitNext(buffer))
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
        assertEquals(malformed, emitNext(buffer))
        buffer.offer(malformed, 0L)
        buffer.offer(malformed, 0L)
        assertEquals(HtspTransportEvent.MetadataOverflow(generation, 2L), emitNext(buffer))
        assertEquals(HtspTransportEvent.MalformedMetadata(generation, 2L), emitNext(buffer))
        assertNull(emitNext(buffer))
    }
}
