package at.bernhardberger.tvheadend.htsp.benchmarks

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.HtspMuxPacketMessage
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscribeChannel
import at.bernhardberger.tvheadend.htsp.requests.SubscribeRequest
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.io.EOFException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val PACKETS_PER_BATCH = 32

/** Public for JMH; owns a loopback peer, connection and collector for each trial. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public class LoopbackBenchmarks {
    /** Payload size in bytes, injected by JMH. */
    @Param("188", "65536", "1048576")
    public var payloadBytes: Int = 0

    /** Adds a coroutine making real RPCs concurrently with reader dispatch. */
    @Param("false", "true")
    public var rpcContention: Boolean = false

    private var server: LoopbackPeer? = null
    private var connection: HtspConnection? = null
    private var scope: CoroutineScope? = null
    private var collector: Deferred<Unit>? = null
    private var watchdog: ScheduledThreadPoolExecutor? = null
    private var contender: Deferred<Unit>? = null
    private var completedBatches: Channel<HtspMuxPacketMessage>? = null
    private lateinit var bytes: ByteArray

    /** Handshake and subscription setup are excluded from the steady-state measurement. */
    @Setup
    public fun setup(): Unit = runBlocking {
        bytes = muxFrame(payloadBytes)
        try {
            val completedBatches = Channel<HtspMuxPacketMessage>(1).also { this@LoopbackBenchmarks.completedBatches = it }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { this@LoopbackBenchmarks.scope = it }
            watchdog = ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "htsp-benchmark-watchdog").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
            val server = LoopbackPeer().also { this@LoopbackBenchmarks.server = it }
            val connection = createHtspConnection(
                ioDispatcher = Dispatchers.IO,
                eventBufferOptions = HtspEventBufferOptions(subscriptionQueueBytes = 64L * 1024 * 1024),
            ).also { this@LoopbackBenchmarks.connection = it }
            check(connection.connect(HtspEndpoint("127.0.0.1", server.port)) is HtspConnectOutcome.Connected)
            collector = scope.async(start = CoroutineStart.UNDISPATCHED) {
                var count = 0
                connection.subscriptionEvents(1L).collect { event ->
                    check(event is HtspSubscriptionEvent.Packet) { "Unexpected subscription event: $event" }
                    check(event.packet.payload.size == payloadBytes)
                    if (++count % PACKETS_PER_BATCH == 0) completedBatches.send(event.packet)
                }
            }
            check(connection.execute(SubscribeRequest(1L, SubscribeChannel.Id(42L))) is HtspResult.Ok)
            if (rpcContention) contender = scope.async {
                while (isActive) {
                    check(connection.execute(GetSysTimeRequest()) is HtspResult.Ok)
                    delay(1)
                }
            }
        } catch (failure: Throwable) {
            teardown()
            throw failure
        }
    }

    /** Measures a bounded burst through TCP, reader locks, decoding, queues and public Flow. */
    @Benchmark
    @OperationsPerInvocation(PACKETS_PER_BATCH)
    public fun packets(blackhole: Blackhole): Unit = runBlocking {
        val server = checkNotNull(server)
        server.checkHealthy()
        collector?.let { if (it.isCompleted) it.await() }
        contender?.let { if (it.isCompleted) it.await() }
        // Coroutine timeouts cannot interrupt a blocking socket write. An independent
        // watchdog closes the socket without taking outputLock, unblocking sendBatch too.
        val pending = AtomicBoolean(true)
        val deadline = checkNotNull(watchdog).schedule({
            if (pending.getAndSet(false)) server.stop()
        }, 10, TimeUnit.SECONDS)
        var completedNormally = false
        try {
            withTimeout(10_000) {
                server.sendBatch(bytes)
                blackhole.consume(checkNotNull(completedBatches).receive())
            }
            completedNormally = true
        } finally {
            val completedBeforeDeadline = pending.getAndSet(false)
            deadline.cancel(false)
            if (completedNormally) check(completedBeforeDeadline) { "Benchmark batch watchdog expired" }
        }
    }

    /** Cancels owned work and closes both sockets; no threads survive a trial. */
    @TearDown
    public fun teardown(): Unit = runBlocking {
        server?.stop()
        try {
            scope?.coroutineContext?.get(Job)?.cancelAndJoin()
            connection?.close()
        } finally {
            try {
                server?.close()
            } finally {
                completedBatches?.close()
                watchdog?.shutdownNow()
                check(watchdog?.awaitTermination(5, TimeUnit.SECONDS) != false) { "Watchdog did not stop" }
            }
        }
    }
}

/** Dedicated fake peer: never connects to a TVHeadend instance or binds off-loopback. */
private class LoopbackPeer : Closeable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val outputLock = Any()
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closing = false
    @Volatile private var client: Socket? = null
    val port: Int = listener.localPort
    private val worker = thread(name = "htsp-benchmark-peer", isDaemon = true) {
        try {
            listener.accept().use { socket ->
                client = socket
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                val input = socket.getInputStream().buffered()
                while (!closing) {
                    val request = HtspCodec.readMessage(input)
                    val fields = linkedMapOf<String, Any?>("seq" to checkNotNull(request.seq))
                    when (request.method) {
                        "hello" -> fields.putAll(mapOf("htspversion" to 44L, "challenge" to ByteArray(32)))
                        "authenticate", "subscribe" -> Unit
                        "getSysTime" -> fields.putAll(mapOf("time" to 1_800_000_000L, "timezone" to 0L))
                        else -> error("Unexpected benchmark request")
                    }
                    synchronized(outputLock) {
                        HtspCodec.writeMessage(socket.getOutputStream(), checkNotNull(request.method), fields)
                        socket.getOutputStream().flush()
                    }
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
        failure.get()?.let { throw IllegalStateException("Benchmark peer failed", it) }
    }

    fun sendBatch(bytes: ByteArray) {
        synchronized(outputLock) {
            val output = checkNotNull(client).getOutputStream()
            repeat(PACKETS_PER_BATCH) { output.write(bytes) }
            output.flush()
        }
    }

    fun stop() {
        closing = true
        try {
            listener.close()
        } finally {
            client?.close()
        }
    }

    override fun close() {
        stop()
        worker.join(5_000)
        check(!worker.isAlive) { "Benchmark peer did not stop" }
    }
}
