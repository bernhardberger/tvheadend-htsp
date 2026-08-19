package at.bernhardberger.tvheadend.htsp.connection

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

/** Non-thread-safe queue; the owning service serializes every operation. */
internal class HtspSubscriptionEventBuffer(
    private val capacity: Int,
) {
    internal enum class OfferResult {
        ACCEPTED,
        IGNORED,
        WAIT_FOR_SPACE,
    }

    internal val eventsAvailable = Channel<Unit>(Channel.CONFLATED)
    internal val spaceAvailable = Channel<Unit>(Channel.CONFLATED)

    private var head: Node? = null
    private var tail: Node? = null
    private val packetNodes = ArrayDeque<Node>()
    private var productionSize = 0
    private var terminal = false
    private var abandoned = false

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    internal fun offer(event: HtspSubscriptionEvent): OfferResult {
        if (terminal || abandoned) return OfferResult.IGNORED

        if (productionSize >= capacity) {
            val packet = oldestQueuedPacket()
            if (packet != null) {
                replacePacketWithDropped(packet)
            } else if (event is HtspSubscriptionEvent.Packet) {
                appendDroppedAtTail(1L)
                eventsAvailable.trySend(Unit)
                return OfferResult.ACCEPTED
            } else {
                return OfferResult.WAIT_FOR_SPACE
            }
        }

        append(event, isProduction = true)
        if (event is HtspSubscriptionEvent.Stopped) {
            terminal = true
            spaceAvailable.trySend(Unit)
        }
        eventsAvailable.trySend(Unit)
        return OfferResult.ACCEPTED
    }

    internal fun completeAfterAcknowledgement() {
        if (terminal || abandoned) return
        terminal = true
        eventsAvailable.trySend(Unit)
        spaceAvailable.trySend(Unit)
    }

    internal fun terminate(reason: HtspSubscriptionTermination) {
        if (terminal || abandoned) return
        append(HtspSubscriptionEvent.Terminated(reason), isProduction = false)
        terminal = true
        eventsAvailable.trySend(Unit)
        spaceAvailable.trySend(Unit)
    }

    internal fun poll(): HtspSubscriptionEvent? {
        val node = head ?: return null
        if (node.event is HtspSubscriptionEvent.Packet) removePacketIndex(node)
        removeNode(node)
        if (node.isProduction) {
            productionSize--
            spaceAvailable.trySend(Unit)
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
        terminal = true
        abandoned = true
        eventsAvailable.trySend(Unit)
        spaceAvailable.trySend(Unit)
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

    private fun append(event: HtspSubscriptionEvent, isProduction: Boolean) {
        val node = Node(
            event = event,
            isProduction = isProduction,
            previous = tail,
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
    )
}
