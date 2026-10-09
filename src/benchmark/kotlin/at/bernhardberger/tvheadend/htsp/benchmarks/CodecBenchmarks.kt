package at.bernhardberger.tvheadend.htsp.benchmarks

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspLogger
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportInputStream
import at.bernhardberger.tvheadend.htsp.messages.HtspMuxPacketMessage
import at.bernhardberger.tvheadend.htsp.requests.FileReadRequest
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.htsp.requests.HtspRequestCodecs
import at.bernhardberger.tvheadend.htsp.requests.SubscribeChannel
import at.bernhardberger.tvheadend.htsp.requests.SubscribeRequest
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.BufferedInputStream
import java.io.InputStream

/** Public only for JMH's generated harness; measures the actual InputStream decoder. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class WireBenchmarks {
    /** Representative transport payload sizes in bytes, injected by JMH. */
    @Param("188", "65536", "1048576")
    public var payloadBytes: Int = 0
    /** Bare bytes or the production buffered transport read stack. */
    @Param("bytes", "transport")
    public lateinit var stream: String
    private lateinit var source: ByteArrayInputStream
    private lateinit var input: InputStream

    /** Builds and validates the fixture outside measurement. */
    @Setup
    public fun setup(): Unit {
        source = muxFrame(payloadBytes).inputStream()
        input = when (stream) {
            "bytes" -> source
            "transport" -> HtspTransportInputStream(
                BufferedInputStream(source, HtspConnectOptions().socketBufferBytes),
                HtspLogger.None,
                Long.MAX_VALUE,
            )
            else -> error("Unknown stream variant")
        }
        repeat(2) {
            beginFrame()
            check((typedFrame(input) as HtspMuxPacketMessage).payload.size == payloadBytes)
            check(source.available() == 0)
        }
    }

    private fun beginFrame() {
        // Every decode consumes exactly the complete fixture, leaving the buffer empty.
        // Reuse the stack as the reader does, rather than allocate a 64 KiB buffer per frame.
        source.reset()
        (input as? HtspTransportInputStream)?.beginFrame()
    }

    /** Includes per-field single-byte header reads and payload allocation. */
    @Benchmark
    public fun wireDecode(blackhole: Blackhole): Unit {
        beginFrame()
        blackhole.consume(HtspCodec.readMessage(input))
    }

    /** Includes wire decoding and the transport's ownership-aware typed packet decoder. */
    @Benchmark
    public fun typedMuxDecode(blackhole: Blackhole): Unit {
        beginFrame()
        blackhole.consume(typedFrame(input))
    }
}

/** Public for JMH; metadata frames include strings, integer fields and nested lists. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class MetadataBenchmarks {
    /** Typed metadata decoder selected by JMH. */
    @Param("channelAdd", "dvrEntryAdd", "eventAdd")
    public lateinit var method: String
    /** Bare bytes or the production buffered transport read stack. */
    @Param("bytes", "transport")
    public lateinit var stream: String
    private lateinit var source: ByteArrayInputStream
    private lateinit var input: InputStream

    /** Validates each fixture so malformed-message fast paths cannot masquerade as decoding. */
    @Setup
    public fun setup(): Unit {
        source = metadataFrame(method).inputStream()
        input = when (stream) {
            "bytes" -> source
            "transport" -> HtspTransportInputStream(
                BufferedInputStream(source, HtspConnectOptions().socketBufferBytes),
                HtspLogger.None,
                Long.MAX_VALUE,
            )
            else -> error("Unknown stream variant")
        }
        repeat(2) {
            beginFrame()
            typedFrame(input)
            check(source.available() == 0)
        }
    }

    private fun beginFrame() {
        // Complete frames leave the reusable buffered stack empty, as in the wire cases.
        source.reset()
        (input as? HtspTransportInputStream)?.beginFrame()
    }

    /** Reads a complete wire frame and consumes the resulting typed metadata. */
    @Benchmark
    public fun decode(blackhole: Blackhole): Unit {
        beginFrame()
        blackhole.consume(typedFrame(input))
    }
}

/** Public for JMH; encodes typed requests through both production codecs. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class RequestBenchmarks {
    /** Request shape selected by JMH. */
    @Param("subscribe", "fileRead")
    public lateinit var method: String
    private lateinit var request: HtspRequest<*>

    /** Allocates immutable request inputs outside measurement. */
    @Setup
    public fun setup(): Unit {
        request = when (method) {
            "subscribe" -> SubscribeRequest(1L, SubscribeChannel.Id(42L), weight = 100L, queueDepthBytes = 1_048_576L)
            "fileRead" -> FileReadRequest(3L, 65_536L, 1_048_576L)
            else -> error("Unknown benchmark request")
        }
    }

    /** Includes request-map creation, wire encoding and output buffer allocation. */
    @Benchmark
    public fun encode(blackhole: Blackhole): Unit {
        val output = ByteArrayOutputStream()
        HtspCodec.writeMessage(output, method, HtspRequestCodecs.encode(request))
        blackhole.consume(output)
    }
}
