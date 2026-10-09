package at.bernhardberger.tvheadend.htsp.connection

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import java.util.ArrayDeque

/** Non-thread-safe queue; the owning service serializes every operation. */
internal class HtspSubscriptionEventBuffer(
    private val capacity: Int,
    private val collectorJob: Job? = null,
    private val byteCapacity: Long = 8L * 1024 * 1024,
    private val wakeup: (Channel<Unit>) -> Unit = { it.trySend(Unit) },
) {
    internal enum class OfferResult {
        ACCEPTED,
        IGNORED,
    }

    internal val eventsAvailable = Channel<Unit>(Channel.CONFLATED)

    private var head: Node? = null
    private var tail: Node? = null
    private val packetNodes = ArrayDeque<Node>()
    private var productionSize = 0
    private var productionBytes = 0L
    private var terminal = false
    private var abandoned = false

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(byteCapacity > 0L) { "byteCapacity must be positive" }
    }

    internal fun offer(event: HtspSubscriptionEvent, frameBodyBytes: Long = 0L): OfferResult {
        require(frameBodyBytes >= 0L)
        if (terminal || abandoned) return OfferResult.IGNORED

        while (productionSize >= capacity || productionBytes + frameBodyBytes > byteCapacity) {
            val packet = oldestQueuedPacket()
            if (packet != null) {
                replacePacketWithDropped(packet)
            } else if (event is HtspSubscriptionEvent.Packet) {
                // A single oversized frame is admitted after all other packets are evicted.
                if (productionSize < capacity && frameBodyBytes > byteCapacity) break
                appendDroppedAtTail(1L)
                wakeup(eventsAvailable)
                return OfferResult.ACCEPTED
            } else {
                terminate(HtspSubscriptionTermination.CONSUMER_OVERFLOW)
                return OfferResult.IGNORED
            }
        }

        append(event, isProduction = true, frameBodyBytes = frameBodyBytes)
        wakeup(eventsAvailable)
        return OfferResult.ACCEPTED
    }

    internal fun completeAfterAcknowledgement() {
        if (terminal || abandoned) return
        terminal = true
        wakeup(eventsAvailable)
    }

    internal fun recordDropped(count: Long) {
        require(count > 0L) { "count must be positive" }
        if (terminal || abandoned) return
        appendDroppedAtTail(count)
        wakeup(eventsAvailable)
    }

    internal fun isAccepting(): Boolean =
        !terminal && !abandoned && collectorJob?.isActive != false

    internal fun terminate(reason: HtspSubscriptionTermination) {
        if (terminal || abandoned) return
        append(HtspSubscriptionEvent.Terminated(reason), isProduction = false)
        terminal = true
        wakeup(eventsAvailable)
    }

    internal fun poll(): HtspSubscriptionEvent? {
        val node = head ?: return null
        if (node.event is HtspSubscriptionEvent.Packet) removePacketIndex(node)
        removeNode(node)
        if (node.isProduction) {
            productionSize--
            productionBytes -= node.frameBodyBytes
        }
        return node.event
    }

    internal fun isComplete(): Boolean = (terminal || abandoned) && head == null

    internal fun abandon() {
        if (abandoned) return
        var node = head
        while (node != null) {
            node.queued = false
            node = node.next
        }
        head = null
        tail = null
        packetNodes.clear()
        productionSize = 0
        productionBytes = 0L
        terminal = true
        abandoned = true
        wakeup(eventsAvailable)
    }

    private fun oldestQueuedPacket(): Node? {
        while (packetNodes.isNotEmpty()) {
            val node = packetNodes.removeFirst()
            if (node.queued && node.event is HtspSubscriptionEvent.Packet) return node
        }
        return null
    }

    private fun removePacketIndex(node: Node) {
        while (packetNodes.isNotEmpty()) {
            val indexed = packetNodes.removeFirst()
            if (indexed === node) return
            check(!indexed.queued || indexed.event !is HtspSubscriptionEvent.Packet)
        }
        error("Queued packet is missing from the eviction index")
    }

    private fun replacePacketWithDropped(node: Node) {
        check(node.isProduction && node.event is HtspSubscriptionEvent.Packet)
        node.event = HtspSubscriptionEvent.Dropped(1L)
        node.isProduction = false
        productionSize--
        productionBytes -= node.frameBodyBytes
        node.frameBodyBytes = 0L

        var marker = node
        val previous = marker.previous
        val previousDropped = previous?.event as? HtspSubscriptionEvent.Dropped
        if (previous != null && previousDropped != null) {
            previous.event = HtspSubscriptionEvent.Dropped(
                Math.addExact(previousDropped.count, 1L),
            )
            removeNode(marker)
            marker = previous
        }

        val next = marker.next
        val markerDropped = marker.event as HtspSubscriptionEvent.Dropped
        val nextDropped = next?.event as? HtspSubscriptionEvent.Dropped
        if (next != null && nextDropped != null) {
            marker.event = HtspSubscriptionEvent.Dropped(
                Math.addExact(markerDropped.count, nextDropped.count),
            )
            removeNode(next)
        }
    }

    private fun appendDroppedAtTail(count: Long) {
        val currentTail = tail
        val dropped = currentTail?.event as? HtspSubscriptionEvent.Dropped
        if (currentTail != null && dropped != null) {
            currentTail.event = HtspSubscriptionEvent.Dropped(
                Math.addExact(dropped.count, count),
            )
        } else {
            append(HtspSubscriptionEvent.Dropped(count), isProduction = false)
        }
    }

    private fun append(event: HtspSubscriptionEvent, isProduction: Boolean, frameBodyBytes: Long = 0L) {
        val node = Node(
            event = event,
            isProduction = isProduction,
            previous = tail,
            frameBodyBytes = frameBodyBytes,
        )
        val currentTail = tail
        if (currentTail == null) {
            head = node
        } else {
            currentTail.next = node
        }
        tail = node
        if (isProduction) {
            productionSize++
            productionBytes += frameBodyBytes
            if (event is HtspSubscriptionEvent.Packet) packetNodes.addLast(node)
        }
    }

    private fun removeNode(node: Node) {
        val previous = node.previous
        val next = node.next
        if (previous == null) head = next else previous.next = next
        if (next == null) tail = previous else next.previous = previous
        node.previous = null
        node.next = null
        node.queued = false
    }

    private class Node(
        var event: HtspSubscriptionEvent,
        var isProduction: Boolean,
        var previous: Node? = null,
        var next: Node? = null,
        var queued: Boolean = true,
        var frameBodyBytes: Long = 0L,
    )
}
