package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspEventBufferOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class HtspEventBufferOptionsTest {
    @Test
    fun defaultsAndInclusiveBounds() {
        val options = HtspEventBufferOptions()
        assertEquals(8_388_608L, options.metadataQueueBytes)
        assertEquals(8_388_608L, options.subscriptionQueueBytes)
        assertEquals(8192, options.metadataQueueEvents)
        assertEquals(8192, options.subscriptionQueueEvents)
        for (bytes in listOf(1_048_576L, 1_073_741_824L)) {
            for (events in listOf(256, 1_048_576)) {
                assertEquals(
                    HtspEventBufferOptions(bytes, events, bytes, events),
                    options.copy(bytes, events, bytes, events),
                )
            }
        }
    }

    @Test
    fun constructorsAndCopyRejectEachOutOfBoundsBudget() {
        val options = HtspEventBufferOptions()
        for (bytes in listOf(Long.MIN_VALUE, 1_048_575L, 1_073_741_825L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { HtspEventBufferOptions(metadataQueueBytes = bytes) }
            assertThrows(IllegalArgumentException::class.java) { HtspEventBufferOptions(subscriptionQueueBytes = bytes) }
            assertThrows(IllegalArgumentException::class.java) { options.copy(metadataQueueBytes = bytes) }
            assertThrows(IllegalArgumentException::class.java) { options.copy(subscriptionQueueBytes = bytes) }
        }
        for (events in listOf(Int.MIN_VALUE, 255, 1_048_577, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { HtspEventBufferOptions(metadataQueueEvents = events) }
            assertThrows(IllegalArgumentException::class.java) { HtspEventBufferOptions(subscriptionQueueEvents = events) }
            assertThrows(IllegalArgumentException::class.java) { options.copy(metadataQueueEvents = events) }
            assertThrows(IllegalArgumentException::class.java) { options.copy(subscriptionQueueEvents = events) }
        }
    }
}
