package at.bernhardberger.tvheadend.htsp.connection

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/** Every operation is serialized by the owning service's [HtspAttemptMonitor.withAttemptLock]. */
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
        val retainedBytes = if (event is HtspTransportEvent.ServerMessage) frameBodyBytes else 0L
        val tail = queue.peekLast()
        val malformed = tail?.event as? HtspTransportEvent.MalformedMetadata
        when {
            event is HtspTransportEvent.MalformedMetadata && malformed?.generation === event.generation -> {
                tail.event = malformed.copy(count = Math.addExact(malformed.count, event.count))
            }
            event.isBudgeted() && (size >= capacity || bytes + retainedBytes > byteCapacity) -> {
                recordOverflow(
                    checkNotNull(event.generation),
                    if (event is HtspTransportEvent.MalformedMetadata) event.count else 1L,
                )
            }
            else -> {
                queue.addLast(Entry(event, retainedBytes))
                if (event.isBudgeted()) {
                    size++
                    bytes += retainedBytes
                }
            }
        }
        wakeup(eventsAvailable)
    }

    private fun recordOverflow(generation: HtspConnectionGeneration, count: Long) {
        val pending = overflow
        val marker = pending?.event as? HtspTransportEvent.MetadataOverflow
        if (marker != null && marker.generation === generation) {
            pending.event = marker.copy(droppedCount = Math.addExact(marker.droppedCount, count))
        } else {
            overflow = Entry(HtspTransportEvent.MetadataOverflow(generation, count), 0L).also(queue::addLast)
        }
    }

    internal fun poll(): HtspTransportEvent? {
        val entry = queue.pollFirst() ?: return null
        if (entry === overflow) overflow = null
        if (entry.event.isBudgeted()) {
            size--
            bytes -= entry.bytes
        }
        return entry.event
    }

    private fun HtspTransportEvent.isBudgeted(): Boolean =
        this is HtspTransportEvent.ServerMessage || this is HtspTransportEvent.MalformedMetadata

    private class Entry(var event: HtspTransportEvent, val bytes: Long)
}
