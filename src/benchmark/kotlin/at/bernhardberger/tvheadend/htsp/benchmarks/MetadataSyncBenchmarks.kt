package at.bernhardberger.tvheadend.htsp.benchmarks

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadata
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadataAwaitingInitialSync
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val CHANNEL_COUNT = 143
private const val EVENT_COUNT = 33_100

/** One operation is a complete 33,244-message initial sync, including its marker. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class MetadataSyncBenchmarks {
    /**
     * Compare single-event and batch handoff, with either acknowledgement or initial-sync waiting.
     * `batchesWithWatcher` adds a second batch collector that keeps only connection failures.
     */
    @Param("events", "syncHelper", "batches", "batchesSyncHelper", "batchesWithWatcher")
    public var mode: String = "events"

    private var peer: MetadataSyncPeer? = null
    private var connection: HtspConnection? = null

    /** Encode once and validate a full typed replay outside measurement. */
    @Setup(Level.Trial)
    public fun setup(): Unit = runBlocking {
        try {
            val peer = MetadataSyncPeer(metadataSyncBytes()).also { this@MetadataSyncBenchmarks.peer = it }
            val connection = createHtspConnection(
                ioDispatcher = Dispatchers.IO,
                // Admit an entire replay even if the collector is temporarily descheduled.
                eventBufferOptions = HtspEventBufferOptions(
                    metadataQueueEvents = CHANNEL_COUNT + EVENT_COUNT + 1,
                    metadataQueueBytes = 64L * 1024 * 1024,
                ),
            ).also { this@MetadataSyncBenchmarks.connection = it }
            check(connection.connect(HtspEndpoint("127.0.0.1", peer.port)) is HtspConnectOutcome.Connected)
            replay(null)
        } catch (failure: Throwable) {
            teardown()
            throw failure
        }
    }

    /** Includes request, reader dispatch, typed decoding, collector handoff and consumption. */
    @Benchmark
    public fun initialSync(blackhole: Blackhole): Unit = runBlocking { replay(blackhole) }

    private suspend fun replay(blackhole: Blackhole?) {
        val peer = checkNotNull(peer)
        val connection = checkNotNull(connection)
        peer.checkHealthy()
        withTimeout(30_000) {
            coroutineScope {
                val watcher = if (mode == "batchesWithWatcher") {
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        connection.eventBatches().collect { batch ->
                            for (event in batch) if (event is HtspTransportEvent.ConnectionFailure) blackhole?.consume(event)
                        }
                    }
                } else null
                val consumer = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    var channels = 0
                    var events = 0
                    fun consume(event: HtspTransportEvent): Boolean {
                        blackhole?.consume(event)
                        check(event is HtspTransportEvent.ServerMessage) { "Unexpected metadata event: $event" }
                        when (event.message) {
                            is HtspChannelAddMessage -> channels++
                            is HtspEventAddMessage -> events++
                            HtspInitialSyncCompletedMessage -> {
                                check(channels == CHANNEL_COUNT && events == EVENT_COUNT) {
                                    "Incomplete typed replay: $channels channels, $events events"
                                }
                                return false
                            }
                            else -> error("Unexpected metadata message: ${event.message}")
                        }
                        return true
                    }
                    if (mode.startsWith("batches")) {
                        connection.eventBatches().takeWhile { batch ->
                            var more = true
                            for (event in batch) if (!consume(event)) more = false
                            more
                        }.collect()
                    } else {
                        connection.events.takeWhile { consume(it) }.collect()
                    }
                }
                val result = when (mode) {
                    "events", "batches", "batchesWithWatcher" -> connection.enableAsyncMetadata(epg = true, timeoutMs = 30_000)
                    "syncHelper", "batchesSyncHelper" -> connection.enableAsyncMetadataAwaitingInitialSync(epg = true, timeoutMs = 30_000)
                    else -> error("Unknown metadata benchmark mode: $mode")
                }
                check(result is HtspResult.Ok) { "Metadata request failed: $result" }
                consumer.await()
                watcher?.cancelAndJoin()
            }
        }
        peer.checkHealthy()
    }

    /** Closing the peer interrupts any outstanding socket write before joining its thread. */
    @TearDown(Level.Trial)
    public fun teardown(): Unit = runBlocking {
        try {
            peer?.close()
        } finally {
            connection?.close()
        }
    }
}

private fun metadataSyncBytes(): ByteArray = ByteArrayOutputStream().use { output ->
    repeat(CHANNEL_COUNT) { index ->
        val id = index + 1L
        output.write(HtspCodec.encode("channelAdd", linkedMapOf(
            "channelId" to id, "channelIdStr" to id.toString(16).padStart(32, '0'),
            "channelNumber" to id, "channelNumberMinor" to 0L, "channelName" to "Benchmark Channel $id",
            "channelIcon" to "imagecache/$id", "eventId" to id, "nextEventId" to id + CHANNEL_COUNT,
            "services" to listOf(mapOf("name" to "Service $id", "type" to "HDTV", "content" to 1L,
                "caid" to 0L, "caname" to "", "providername" to "Benchmark Broadcast")),
            "tags" to listOf(1L, 2L),
        )))
    }
    val summary = "The team explores a changing landscape, meeting local residents and discovering the stories behind familiar places."
    val description = "$summary Along the way, archive footage and expert interviews reveal how the region developed and what its future may hold."
    repeat(EVENT_COUNT) { index ->
        val id = index + 1L
        val start = 1_800_000_000L + (index / CHANNEL_COUNT) * 1800L
        output.write(HtspCodec.encode("eventAdd", linkedMapOf(
            "eventId" to id, "channelId" to index % CHANNEL_COUNT + 1L,
            "start" to start, "stop" to start + 1800L,
            "title" to "Discovering the landscape: episode $id", "subtitle" to "A journey through the region",
            "summary" to summary, "description" to description,
            "contentType" to 0x90L, "ageRating" to 6L, "starRating" to 4L,
            "seasonNumber" to 2L, "episodeNumber" to index % 12 + 1L,
            "category" to listOf("Documentary", "Travel"), "keyword" to listOf("landscape", "history"),
            "nextEventId" to if (id + CHANNEL_COUNT <= EVENT_COUNT) id + CHANNEL_COUNT else 0L,
        )))
    }
    output.write(HtspCodec.encode("initialSyncCompleted", emptyMap()))
    output.toByteArray()
}

/** Repeated enables deliberately replay the fixture on the same established connection. */
private class MetadataSyncPeer(private val bytes: ByteArray) : Closeable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closing = false
    @Volatile private var client: Socket? = null
    val port: Int = listener.localPort
    private val worker = thread(name = "htsp-metadata-benchmark-peer", isDaemon = true) {
        try {
            listener.accept().use { socket ->
                client = socket
                socket.tcpNoDelay = true
                val input = socket.getInputStream().buffered()
                val output = socket.getOutputStream()
                while (!closing) {
                    val request = HtspCodec.readMessage(input)
                    val fields = linkedMapOf<String, Any?>("seq" to checkNotNull(request.seq))
                    when (request.method) {
                        "hello" -> fields.putAll(mapOf("htspversion" to 44L, "challenge" to ByteArray(32)))
                        "authenticate", "enableAsyncMetadata" -> Unit
                        else -> error("Unexpected metadata benchmark request: ${request.method}")
                    }
                    output.write(HtspCodec.encode(checkNotNull(request.method), fields))
                    if (request.method == "enableAsyncMetadata") output.write(bytes)
                    output.flush()
                }
            }
        } catch (exception: SocketException) {
            if (!closing) failure.set(exception)
        } catch (exception: EOFException) {
            if (!closing) failure.set(exception)
        } catch (exception: Exception) {
            failure.set(exception)
        }
    }

    fun checkHealthy() {
        failure.get()?.let { throw IllegalStateException("Metadata benchmark peer failed", it) }
    }

    override fun close() {
        closing = true
        try {
            listener.close()
        } finally {
            client?.close()
        }
        worker.join(5_000)
        check(!worker.isAlive) { "Metadata benchmark peer did not stop" }
    }
}
