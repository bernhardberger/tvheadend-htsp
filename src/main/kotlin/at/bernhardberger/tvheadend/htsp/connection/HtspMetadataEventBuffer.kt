package at.bernhardberger.tvheadend.htsp.connection

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/** Every operation is serialized by the owning service's connectionAttemptLock. */
internal class HtspMetadataEventBuffer(
    private val capacity: Int,
    private val byteCapacity: Long,
    private val wakeup: (Channel<Unit>) -> Unit = { it.trySend(Unit) },
) {
    internal val eventsAvailable = Channel<Unit>(Channel.CONFLATED)
    private val queue = ArrayDeque<Entry>()
    private var size = 0
    private var bytes = 0L
    private var overflow: Entry? = null

    internal fun offer(event: HtspTransportEvent, frameBodyBytes: Long) {
        if (event is HtspTransportEvent.ServerMessage &&
            (size >= capacity || bytes + frameBodyBytes > byteCapacity)
        ) {
            val pending = overflow
            val marker = pending?.event as? HtspTransportEvent.MetadataOverflow
            if (marker != null && marker.generation === event.generation) {
                pending.event = marker.copy(droppedCount = Math.addExact(marker.droppedCount, 1L))
            } else {
                overflow = Entry(HtspTransportEvent.MetadataOverflow(event.generation, 1L), 0L).also(queue::addLast)
            }
        } else {
            val retainedBytes = if (event is HtspTransportEvent.ServerMessage) frameBodyBytes else 0L
            queue.addLast(Entry(event, retainedBytes))
            if (event is HtspTransportEvent.ServerMessage) {
                size++
                bytes += retainedBytes
            }
        }
        wakeup(eventsAvailable)
    }

    internal fun poll(): HtspTransportEvent? {
        val entry = queue.pollFirst() ?: return null
        if (entry === overflow) overflow = null
        if (entry.event is HtspTransportEvent.ServerMessage) {
            size--
            bytes -= entry.bytes
        }
        return entry.event
    }

    private class Entry(var event: HtspTransportEvent, val bytes: Long)
}
