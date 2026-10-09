package at.bernhardberger.tvheadend.htsp.benchmarks

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

/** Public for JMH; queues are thread-confined, just as when the service holds its lock. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class BufferBenchmarks {
    private lateinit var subscription: HtspSubscriptionEventBuffer
    private lateinit var metadata: HtspMetadataEventBuffer
    private lateinit var overflowingMetadata: HtspMetadataEventBuffer
    private lateinit var packet: HtspSubscriptionEvent.Packet
    private lateinit var control: HtspSubscriptionEvent
    private lateinit var channel: HtspTransportEvent.ServerMessage

    /** Builds reusable events and bounded queues outside measurement. */
    @Setup
    public fun setup(): Unit {
        subscription = HtspSubscriptionEventBuffer(capacity = 8, byteCapacity = 1024)
        metadata = HtspMetadataEventBuffer(capacity = 8, byteCapacity = 4096)
        overflowingMetadata = HtspMetadataEventBuffer(capacity = 8, byteCapacity = 1024)
        packet = HtspSubscriptionEvent.Packet(typedFrame(muxFrame(188)) as HtspMuxPacketMessage)
        control = HtspSubscriptionEvent.Status(HtspSubscriptionStatusMessage(1L, "Running", null))
        channel = HtspTransportEvent.ServerMessage(
            message = typedFrame(metadataFrame("channelAdd")),
            generation = HtspConnectionGeneration(),
            messageSequence = 1L,
        )
        for (packetCount in listOf(1, 8)) {
            val results = mutableListOf<Any?>()
            subscriptionBatch(packetCount) { results.add(it) }
            val offers = results.filterIsInstance<HtspSubscriptionEventBuffer.OfferResult>()
            check(offers.size == packetCount + 1)
            check(offers.all { it == HtspSubscriptionEventBuffer.OfferResult.ACCEPTED })
            val events = results.filterIsInstance<HtspSubscriptionEvent>()
            if (packetCount == 1) {
                check(events == listOf(packet, control))
            } else {
                check(events == listOf(HtspSubscriptionEvent.Dropped(6), packet, packet, control))
            }
            check(subscription.isAccepting() && !subscription.isComplete())
            check(subscription.poll() == null)
        }
        val retained = mutableListOf<Any?>()
        metadataBatch(metadata) { retained.add(it) }
        check(retained.filterIsInstance<HtspTransportEvent>() == List(8) { channel })
        check(metadata.poll() == null)
        val overflowed = mutableListOf<Any?>()
        metadataBatch(overflowingMetadata) { overflowed.add(it) }
        check(overflowed.filterIsInstance<HtspTransportEvent>() == listOf(
            channel, channel, HtspTransportEvent.MetadataOverflow(channel.generation, 6),
        ))
        check(overflowingMetadata.poll() == null)
    }

    /** One packet and one control, including the real conflated-channel wakeups. */
    @Benchmark
    public fun subscriptionOfferPoll(blackhole: Blackhole): Unit {
        subscriptionBatch(1) { blackhole.consume(it) }
    }

    /** Byte-budget pressure replaces old packets with coalesced loss markers. */
    @Benchmark
    public fun subscriptionEviction(blackhole: Blackhole): Unit {
        subscriptionBatch(8) { blackhole.consume(it) }
    }

    /** Enqueues and drains eight metadata events without overflow. */
    @Benchmark
    public fun metadataEnqueueDrain(blackhole: Blackhole): Unit {
        metadataBatch(metadata) { blackhole.consume(it) }
    }

    /** Retains two metadata events and coalesces six dropped events under a byte budget. */
    @Benchmark
    public fun metadataOverflowCoalesce(blackhole: Blackhole): Unit {
        metadataBatch(overflowingMetadata) { blackhole.consume(it) }
    }

    private inline fun subscriptionBatch(packetCount: Int, consume: (Any?) -> Unit) {
        repeat(packetCount) { consume(subscription.offer(packet, 350)) }
        consume(subscription.offer(control, 64))
        while (true) consume(subscription.poll() ?: break)
        consume(subscription.eventsAvailable.tryReceive())
    }

    private inline fun metadataBatch(buffer: HtspMetadataEventBuffer, consume: (Any?) -> Unit) {
        repeat(8) { buffer.offer(channel, 350) }
        while (true) consume(buffer.poll() ?: break)
        consume(buffer.eventsAvailable.tryReceive())
    }
}
