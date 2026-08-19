package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HtspSubscriptionEventBufferTest {

    @Test
    fun packetEvictionCreatesAndCoalescesMarkerAtExactOrderedPosition() {
        val buffer = HtspSubscriptionEventBuffer(capacity = 2)

        assertAccepted(buffer.offer(packet(1)))
        assertAccepted(buffer.offer(packet(2)))
        assertAccepted(buffer.offer(packet(3)))
        assertAccepted(buffer.offer(packet(4)))

        assertEquals(HtspSubscriptionEvent.Dropped(2L), buffer.poll())
        assertEquals(packet(3), buffer.poll())
        assertEquals(packet(4), buffer.poll())
        assertNull(buffer.poll())
        assertFalse(buffer.isComplete())
    }

    @Test
    fun decodedPacketDropsAreOrderedCoalescedAndOutsideProductionCapacity() {
        val buffer = HtspSubscriptionEventBuffer(capacity = 1)
        val first = status("first")
        val second = status("second")

        assertAccepted(buffer.offer(first))
        buffer.recordDropped(1L)
        buffer.recordDropped(1L)
        assertEquals(
            HtspSubscriptionEventBuffer.OfferResult.WAIT_FOR_SPACE,
            buffer.offer(second),
        )

        assertEquals(first, buffer.poll())
        assertAccepted(buffer.offer(second))
        assertEquals(HtspSubscriptionEvent.Dropped(2L), buffer.poll())
        assertEquals(second, buffer.poll())
    }

    @Test
    fun cancelledCollectorStopsAdmissionBeforeItsCleanupRuns() {
        val collectorJob = Job()
        val buffer = HtspSubscriptionEventBuffer(capacity = 1, collectorJob = collectorJob)

        assertTrue(buffer.isAccepting())
        collectorJob.cancel()
        assertFalse(buffer.isAccepting())
    }

    @Test
    fun fullControlQueueDropsIncomingPacketsButBackpressuresIncomingControl() {
        val buffer = HtspSubscriptionEventBuffer(capacity = 2)
        val first = status("first")
        val second = status("second")
        val stopped = HtspSubscriptionEvent.Stopped(
            HtspSubscriptionStopMessage(
                subscriptionId = 1L,
                status = "stopped",
                subscriptionError = null,
            ),
        )

        assertAccepted(buffer.offer(first))
        assertAccepted(buffer.offer(second))
        assertAccepted(buffer.offer(packet(1)))
        assertEquals(
            HtspSubscriptionEventBuffer.OfferResult.WAIT_FOR_SPACE,
            buffer.offer(stopped),
        )
        assertEquals(first, buffer.poll())
        assertAccepted(buffer.offer(stopped))

        assertEquals(second, buffer.poll())
        assertEquals(HtspSubscriptionEvent.Dropped(1L), buffer.poll())
        assertEquals(stopped, buffer.poll())
        assertTrue(buffer.isComplete())
        assertNull(buffer.poll())
    }

    @Test
    fun terminationAndAcknowledgementCompleteOnlyAfterCommittedEventsDrain() {
        val terminated = HtspSubscriptionEventBuffer(capacity = 2)
        val status = status("queued")
        assertAccepted(terminated.offer(status))
        terminated.terminate(HtspSubscriptionTermination.TRANSPORT_CLOSED)

        assertEquals(status, terminated.poll())
        assertEquals(
            HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.TRANSPORT_CLOSED),
            terminated.poll(),
        )
        assertTrue(terminated.isComplete())

        val acknowledged = HtspSubscriptionEventBuffer(capacity = 2)
        assertAccepted(acknowledged.offer(status))
        acknowledged.completeAfterAcknowledgement()
        assertEquals(status, acknowledged.poll())
        assertNull(acknowledged.poll())
        assertTrue(acknowledged.isComplete())
    }

    @Test
    fun sustainedUnsaturatedConsumptionReleasesPacketEvictionIndexEntries() {
        val buffer = HtspSubscriptionEventBuffer(capacity = 2)

        repeat(10_000) { marker ->
            val packet = packet(marker)
            assertAccepted(buffer.offer(packet))
            assertEquals(packet, buffer.poll())
        }

        val packetNodesField = HtspSubscriptionEventBuffer::class.java
            .getDeclaredField("packetNodes")
            .apply { isAccessible = true }
        val packetNodes = packetNodesField.get(buffer) as java.util.ArrayDeque<*>
        assertEquals(0, packetNodes.size)
    }

    private fun packet(marker: Int): HtspSubscriptionEvent.Packet =
        HtspSubscriptionEvent.Packet(
            HtspMuxPacketMessage(
                subscriptionId = 1L,
                frameType = 73L,
                streamIndex = 0L,
                decodingTimeUs = null,
                presentationTimeUs = null,
                durationUs = 40L,
                payload = HtspBinary(byteArrayOf(marker.toByte())),
            ),
        )

    private fun status(value: String): HtspSubscriptionEvent.Status =
        HtspSubscriptionEvent.Status(
            HtspSubscriptionStatusMessage(
                subscriptionId = 1L,
                status = value,
                subscriptionError = null,
            ),
        )

    private fun assertAccepted(result: HtspSubscriptionEventBuffer.OfferResult) {
        assertEquals(HtspSubscriptionEventBuffer.OfferResult.ACCEPTED, result)
    }
}
