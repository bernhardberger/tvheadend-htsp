package at.bernhardberger.tvheadend.htsp.connection

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/**
 * Queue and accounting operations are serialized by [HtspAttemptMonitor.withAttemptLock].
 * The collector owns the drained chunk and calls [emitting] outside the lock. It also reads
 * [Entry.isOverflow] and non-overflow [Entry.event] there: non-overflow entries are never
 * mutated after drain. Overflow snapshots still require the lock via [eventForEmission].
 * Malformed reports coalesce only with the queued tail, never with a drained entry;
 * a subsequent report needs its own slot and may overflow while that entry is in flight.
 */
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
    // Accounting only: the collector exclusively owns drained payload references.
    // Negative values mark unbudgeted entries; zero includes budgeted malformed reports.
    private var inFlightBytes = LongArray(0)
    @Volatile private var emittedCount = 0
    private var releasedCount = 0

    internal fun offer(event: HtspTransportEvent, frameBodyBytes: Long) {
        releaseEmitted()
        val wasEmpty = queue.isEmpty()
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
        // A conflated signal survives the gap between the locked empty check and receive.
        // Offers and drains are serialized; another signal is needed only after a new transition.
        if (wasEmpty && queue.isNotEmpty()) wakeup(eventsAvailable)
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

    /** Moves a bounded chunk without releasing its charges, optionally stopping at a generation change. */
    internal fun drain(limit: Int, singleGeneration: Boolean = false): kotlin.collections.ArrayDeque<Entry> {
        require(limit > 0)
        releaseEmitted()
        check(releasedCount == inFlightBytes.size)
        val count = if (singleGeneration) generationPrefixSize(limit) else minOf(limit, queue.size)
        val chunk = kotlin.collections.ArrayDeque<Entry>(count)
        inFlightBytes = LongArray(count) {
            val entry = queue.removeFirst()
            chunk.addLast(entry)
            if (entry.event.isBudgeted()) entry.bytes else -1L
        }
        emittedCount = 0
        releasedCount = 0
        return chunk
    }

    private fun generationPrefixSize(limit: Int): Int {
        val generation = queue.peekFirst()?.event?.generation
        var count = 0
        for (entry in queue) {
            if (count == limit || entry.event.generation !== generation) break
            count++
        }
        return count
    }

    /** Only overflow entries remain mutable after draining; snapshot them under the attempt lock. */
    internal fun eventForEmission(entry: Entry): HtspTransportEvent {
        if (entry === overflow) overflow = null
        return entry.event
    }

    /**
     * Releases a prefix immediately before handing it to emit, not after delivery.
     * The single collector calls this outside the lock; any remaining entries stay charged.
     */
    internal fun emitting(count: Int = 1) {
        emittedCount += count
    }

    private fun releaseEmitted() {
        val completed = emittedCount
        while (releasedCount < completed) {
            val retainedBytes = inFlightBytes[releasedCount++]
            if (retainedBytes >= 0L) {
                size--
                bytes -= retainedBytes
            }
        }
    }

    private fun HtspTransportEvent.isBudgeted(): Boolean =
        this is HtspTransportEvent.ServerMessage || this is HtspTransportEvent.MalformedMetadata

    internal class Entry(var event: HtspTransportEvent, val bytes: Long) {
        val isOverflow = event is HtspTransportEvent.MetadataOverflow
    }
}
