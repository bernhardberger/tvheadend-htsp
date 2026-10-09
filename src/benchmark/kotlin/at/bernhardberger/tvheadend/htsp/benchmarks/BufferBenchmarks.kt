package at.bernhardberger.tvheadend.htsp.benchmarks

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

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

/** Public for JMH; isolates the real reader dispatch from TCP and coroutine scheduling noise. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class ReaderDispatchBenchmarks {
    /** Small TS and larger video payloads, injected by JMH. */
    @Param("188", "65536")
    public var payloadBytes: Int = 0

    private lateinit var service: HtspService
    private lateinit var monitor: HtspAttemptMonitor
    private lateinit var buffer: HtspSubscriptionEventBuffer
    private lateinit var reader: Method
    private lateinit var input: ByteArrayInputStream
    private var attempt = 0L
    private var delivered = 0
    private var blackhole: Blackhole? = null
    private val continuation = object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) = error("The in-memory reader must not suspend")
    }

    /** Reflection is setup-only except one reader invocation per 256 packets, on both revisions. */
    @Setup
    public fun setup(): Unit {
        service = HtspService(Dispatchers.Unconfined, beforeFrameRead = {
            // Model exactly one collector poll per packet without a scheduler handoff.
            val event = monitor.withAttemptLock { buffer.poll() }
            if (event != null) {
                check(event is HtspSubscriptionEvent.Packet)
                check(event.packet.payload.size == payloadBytes)
                delivered++
                blackhole?.consume(event)
            }
        })
        val type = service.javaClass
        monitor = type.getDeclaredField("attemptMonitor").apply { isAccessible = true }.get(service) as HtspAttemptMonitor
        attempt = type.getDeclaredMethod("beginConnectionAttempt", HtspSubscriptionTermination::class.java)
            .apply { isAccessible = true }.invoke(service, HtspSubscriptionTermination.GENERATION_LOST) as Long
        val generation = checkNotNull(type.getDeclaredField("protocolGeneration").apply { isAccessible = true }.get(service))
        generation.javaClass.getDeclaredField("subscriptionTimestampClocks").apply { isAccessible = true }
            .set(generation, linkedMapOf(1L to HtspTimestampClock.NINETY_KHZ))
        @Suppress("UNCHECKED_CAST")
        val streams = generation.javaClass.getDeclaredField("subscriptionStreams").apply { isAccessible = true }
            .get(generation) as MutableMap<Long, HtspSubscriptionEventBuffer>
        buffer = HtspSubscriptionEventBuffer(8, byteCapacity = 1024 * 1024, wakeup = monitor::queueWakeup)
        monitor.withAttemptLock { streams[1L] = buffer }
        reader = type.getDeclaredMethod("readerLoop", InputStream::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Continuation::class.java).apply { isAccessible = true }
        val frame = muxFrame(payloadBytes)
        input = ByteArrayInputStream(ByteArray(frame.size * 256) { frame[it % frame.size] })
        dispatch()
    }

    /** Includes framing, typed decode, currency check, enqueue, wakeup and a locked collector poll. */
    @Benchmark
    @OperationsPerInvocation(256)
    public fun packets(blackhole: Blackhole): Unit {
        this.blackhole = blackhole
        dispatch()
    }

    private fun dispatch() {
        delivered = 0
        input.reset()
        // EOF ends this synthetic reader; no socket exists, so failAll has nothing to retire.
        check(reader.invoke(service, input, 5_000L, attempt, continuation) == Unit)
        check(delivered == 256)
    }

    /** Releases the service-owned lifecycle scope after the trial. */
    @TearDown
    public fun teardown(): Unit = runBlocking { service.close() }
}
