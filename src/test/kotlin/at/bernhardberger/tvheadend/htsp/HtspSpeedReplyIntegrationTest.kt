package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import at.bernhardberger.tvheadend.htsp.wire.HtspWireMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class HtspSpeedReplyIntegrationTest {
    @Test
    @Timeout(value = 2L, unit = TimeUnit.MINUTES)
    fun terrestrialSpeedRepliesRemainCorrelatedAcrossAsyncEvents() = runBlocking {
        verifySpeedReplies(LiveChannelPath.TERRESTRIAL)
    }

    @Test
    @Timeout(value = 2L, unit = TimeUnit.MINUTES)
    fun motorvisionSpeedRepliesRemainCorrelatedAcrossAsyncEvents() = runBlocking {
        verifySpeedReplies(LiveChannelPath.MOTORVISION)
    }

    private suspend fun verifySpeedReplies(path: LiveChannelPath): Unit = coroutineScope {
        val upstream = liveEndpoint()
        SpeedReplyTraceProxy(upstream.host, upstream.port).use { proxy ->
            val connection = createHtspConnection(Dispatchers.IO)
            var generation: HtspConnectionGeneration? = null
            var metadataJob: Job? = null
            var subscriptionJob: Job? = null
            var subscribed = false

            try {
                val outcome = connection.connect(
                    HtspEndpoint(
                        host = LOOPBACK_HOST,
                        port = proxy.port,
                        username = upstream.username,
                        password = upstream.password,
                    ),
                )
                assertTrue(outcome is HtspConnectOutcome.Connected, "Pinned live server must connect")
                val live = (outcome as HtspConnectOutcome.Connected).connection
                generation = live.generation
                assertEquals(
                    PINNED_SERVER_VERSION,
                    live.serverFacts.serverVersion,
                    "Live regression must run against the pinned affected server",
                )

                val metadata = LiveMetadataObservation(live.generation)
                metadataJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    connection.events.collect(metadata::accept)
                }
                assertTrue(
                    connection.enableAsyncMetadataAwaitingInitialSync(
                        timeoutMs = METADATA_TIMEOUT_MS,
                        expectedGeneration = live.generation,
                    ) is HtspResult.Ok,
                    "Pinned live server metadata must synchronize",
                )
                metadata.awaitInitialSync()
                val channelId = when (path) {
                    LiveChannelPath.TERRESTRIAL -> metadata.terrestrialChannelId()
                    LiveChannelPath.MOTORVISION -> metadata.motorvisionChannelId()
                }
                    ?: throw AssertionError("The selected live channel path is unavailable")

                val observation = LiveSubscriptionObservation()
                subscriptionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    connection.subscriptionEvents(SUBSCRIPTION_ID, live.generation)
                        .collect(observation::accept)
                }
                val subscribe = connection.subscribe(
                    subscriptionId = SUBSCRIPTION_ID,
                    channelId = channelId,
                    timeshiftPeriodSeconds = TIMESHIFT_PERIOD_SECONDS,
                    expectedGeneration = live.generation,
                )
                assertTrue(subscribe is HtspResult.Ok, "Live timeshift subscription must be accepted")
                assertEquals(
                    TIMESHIFT_PERIOD_SECONDS,
                    (subscribe as HtspResult.Ok).value.timeshiftPeriodSeconds,
                    "Pinned server must grant the requested timeshift period",
                )
                subscribed = true
                val started = observation.awaitStarted()
                assertTrue(
                    started.sourceInfo?.networkType == path.expectedNetworkType,
                    "Selected live channel must use the expected source path",
                )

                val pauseResult = connection.subscriptionSpeed(
                    subscriptionId = SUBSCRIPTION_ID,
                    speed = PAUSED_SPEED,
                    timeoutMs = SPEED_REPLY_TIMEOUT_MS,
                    expectedGeneration = live.generation,
                )
                val pauseTrace = proxy.awaitTrace(PAUSED_SPEED)
                val pauseEventObserved = observation.awaitSpeed(PAUSED_SPEED)
                val pauseTimeshiftObserved = observation.awaitTimeshiftAfterSpeed(PAUSED_SPEED)

                val resumeResult = if (connection.liveConnection.value?.generation === live.generation) {
                    connection.subscriptionSpeed(
                        subscriptionId = SUBSCRIPTION_ID,
                        speed = NORMAL_SPEED,
                        timeoutMs = SPEED_REPLY_TIMEOUT_MS,
                        expectedGeneration = live.generation,
                    )
                } else {
                    null
                }
                val resumeTrace = proxy.awaitTrace(NORMAL_SPEED)
                val resumeEventObserved = observation.awaitSpeed(NORMAL_SPEED)
                val resumeTimeshiftObserved = observation.awaitTimeshiftAfterSpeed(NORMAL_SPEED)

                println(
                    "${path.name.lowercase()}: " +
                        "${pauseTrace?.describe()}; ${resumeTrace?.describe()}",
                )
                assertSpeedExchange(
                    PAUSED_SPEED,
                    pauseResult,
                    pauseTrace,
                    pauseEventObserved,
                    pauseTimeshiftObserved,
                    observation.describe(),
                )
                assertSpeedExchange(
                    NORMAL_SPEED,
                    resumeResult,
                    resumeTrace,
                    resumeEventObserved,
                    resumeTimeshiftObserved,
                    observation.describe(),
                )
                assertSame(
                    live.generation,
                    connection.liveConnection.value?.generation,
                    "Speed controls must preserve the live generation",
                )
                assertTrue(connection.isCurrent(live.generation), "Speed-control generation must remain current")
                assertEquals(0, metadata.connectionFailures(), "Speed controls must not fail the transport")
            } finally {
                withContext(NonCancellable) {
                    val currentGeneration = generation
                    var cleanupAcknowledged = !subscribed
                    var streamDrained = !subscribed || subscriptionJob == null
                    try {
                        if (
                            subscribed &&
                            currentGeneration != null &&
                            connection.liveConnection.value?.generation === currentGeneration
                        ) {
                            cleanupAcknowledged = connection.unsubscribe(
                                subscriptionId = SUBSCRIPTION_ID,
                                expectedGeneration = currentGeneration,
                            ) is HtspResult.Ok
                        }
                        val collector = subscriptionJob
                        if (subscribed && collector != null) {
                            streamDrained = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                                collector.join()
                                true
                            } == true
                            if (!streamDrained) collector.cancelAndJoin()
                        }
                    } finally {
                        subscriptionJob?.cancelAndJoin()
                        metadataJob?.cancelAndJoin()
                        connection.close()
                    }
                    assertTrue(cleanupAcknowledged, "Live subscription cleanup must be acknowledged")
                    assertTrue(streamDrained, "Live subscription stream must drain during cleanup")
                }
            }
        }
    }

    private fun assertSpeedExchange(
        speed: Int,
        result: HtspResult<*>?,
        trace: SpeedTraceSnapshot?,
        eventObserved: Boolean,
        typedTimeshift: HtspTimeshiftStatusMessage?,
        observation: String,
    ) {
        val description = trace?.describe().orEmpty()
        assertTrue(result is HtspResult.Ok, "Speed $speed RPC must be acknowledged; $description")
        assertTrue(trace != null, "Speed $speed request must cross the trace proxy")
        checkNotNull(trace)
        assertEquals(
            EXPECTED_SPEED_REQUEST_FIELDS,
            trace.requestFields,
            "Speed $speed request must use the exact HTSP request shape",
        )
        assertEquals(
            EXPECTED_EMPTY_REPLY_FIELDS,
            trace.replyFields,
            "Speed $speed response must be the sequenced empty HTSP reply; $description",
        )
        assertNull(trace.replyMethod, "Speed $speed reply must not masquerade as an async event")
        assertTrue(trace.replyOrder != null, "Speed $speed sequenced reply must be observed; $description")
        assertTrue(trace.speedEventOrder != null, "Speed $speed async event must be observed; $description")
        assertEquals(
            EXPECTED_SPEED_EVENT_FIELDS,
            trace.speedEventFields,
            "Speed $speed event must use the exact unsequenced HTSP event shape; $description",
        )
        assertTrue(trace.speedEventSequenceAbsent, "Speed $speed async event must not contain a sequence")
        assertTrue(trace.timeshiftEventOrder != null, "Speed $speed exchange must observe timeshift ordering")
        assertTrue(trace.timeshiftEventSequenceAbsent, "Timeshift events must not contain a sequence")
        val timeshiftFields = trace.timeshiftEventFields.orEmpty()
        assertTrue(
            timeshiftFields.containsAll(EXPECTED_TIMESHIFT_EVENT_REQUIRED_FIELDS),
            "Speed $speed timeshift event must contain every required field; $description",
        )
        assertTrue(
            EXPECTED_TIMESHIFT_EVENT_ALLOWED_FIELDS.containsAll(timeshiftFields),
            "Speed $speed timeshift event must contain only pinned-server fields; $description",
        )
        assertTrue(
            checkNotNull(trace.timeshiftEventOrder) > checkNotNull(trace.speedEventOrder),
            "Speed $speed event must precede its traced timeshift observation; $description",
        )
        assertTrue(
            eventObserved,
            "Speed $speed async event must reach the typed subscription stream; $observation; $description",
        )
        assertTrue(
            typedTimeshift != null,
            "Speed $speed timeshift event must reach the typed subscription stream; $observation; $description",
        )
        assertTrue(
            typedTimeshift?.full == trace.timeshiftFull && typedTimeshift?.shift == trace.timeshiftShift,
            "Speed $speed typed timeshift values must match the traced wire values",
        )
    }

    private fun liveEndpoint(): HtspEndpoint {
        assumeTrue(
            System.getenv(ENABLE_ENVIRONMENT_VARIABLE) == "true",
            "Pinned-server speed-reply verification is opt-in",
        )
        val port = requiredEnvironmentVariable(PORT_ENVIRONMENT_VARIABLE)
            .toIntOrNull()
            ?.takeIf { value -> value in 1..65_535 }
            ?: throw AssertionError("Pinned-server HTSP port is invalid")
        return HtspEndpoint(
            host = requiredEnvironmentVariable(HOST_ENVIRONMENT_VARIABLE),
            port = port,
            username = requiredEnvironmentVariable(USERNAME_ENVIRONMENT_VARIABLE),
            password = requiredEnvironmentVariable(PASSWORD_ENVIRONMENT_VARIABLE),
        )
    }

    private fun requiredEnvironmentVariable(name: String): String =
        System.getenv(name)?.takeIf(String::isNotEmpty)
            ?: throw AssertionError("Pinned-server live verification is incompletely provisioned")

    private enum class LiveChannelPath(
        val expectedNetworkType: String,
    ) {
        TERRESTRIAL("DVB-S"),
        MOTORVISION("IPTV"),
    }

    private companion object {
        const val ENABLE_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_SPEED_REPLY_TEST"
        const val HOST_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_HOST"
        const val PORT_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_PORT"
        const val USERNAME_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_USERNAME"
        const val PASSWORD_ENVIRONMENT_VARIABLE = "TVHEADEND_HTSP_LIVE_PASSWORD"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val PINNED_SERVER_VERSION = "4.3-2735~gfcd987f0b"
        const val MOTORVISION_NAME = "Motorvision TV"
        const val SUBSCRIPTION_ID = 1L
        const val TIMESHIFT_PERIOD_SECONDS = 1_800L
        const val PAUSED_SPEED = 0
        const val NORMAL_SPEED = 100
        const val METADATA_TIMEOUT_MS = 30_000L
        const val EVENT_TIMEOUT_MS = 10_000L
        const val SPEED_REPLY_TIMEOUT_MS = 10_000L
        const val CLEANUP_TIMEOUT_MS = 5_000L
        val EXPECTED_SPEED_REQUEST_FIELDS = setOf("method", "seq", "subscriptionId", "speed")
        val EXPECTED_EMPTY_REPLY_FIELDS = setOf("seq")
        val EXPECTED_SPEED_EVENT_FIELDS = setOf("method", "subscriptionId", "speed")
        val EXPECTED_TIMESHIFT_EVENT_REQUIRED_FIELDS = setOf("method", "subscriptionId", "full", "shift")
        val EXPECTED_TIMESHIFT_EVENT_ALLOWED_FIELDS = EXPECTED_TIMESHIFT_EVENT_REQUIRED_FIELDS + setOf("start", "end")
    }

    private class LiveMetadataObservation(
        private val generation: HtspConnectionGeneration,
    ) {
        private val lock = Any()
        private val changed = Channel<Unit>(Channel.CONFLATED)
        private val channels = mutableMapOf<Long, String?>()
        private val completedDvrChannels = mutableMapOf<Long, Long>()
        private var initialSync = false
        private var failures = 0

        fun accept(event: HtspTransportEvent) {
            when (event) {
                is HtspTransportEvent.ServerMessage -> {
                    if (event.generation !== generation) return
                    synchronized(lock) {
                        when (val message = event.message) {
                            is HtspChannelAddMessage -> channels[message.channelId] = message.channelName
                            is HtspChannelUpdateMessage -> if (
                                message.channelId !in channels || message.channelName != null
                            ) {
                                channels[message.channelId] = message.channelName
                            }
                            is HtspChannelDeleteMessage -> channels.remove(message.channelId)
                            is HtspDvrEntryAddMessage -> recordCompletedDvrChannel(
                                message.channelId,
                                message.state,
                                message.stop,
                                message.files,
                            )
                            is HtspDvrEntryUpdateMessage -> recordCompletedDvrChannel(
                                message.channelId,
                                message.state,
                                message.stop,
                                message.files,
                            )
                            HtspInitialSyncCompletedMessage -> initialSync = true
                            else -> Unit
                        }
                    }
                    changed.trySend(Unit)
                }
                is HtspTransportEvent.ConnectionFailure -> {
                    if (event.generation === generation) {
                        synchronized(lock) { failures += 1 }
                        changed.trySend(Unit)
                    }
                }
            }
        }

        suspend fun awaitInitialSync() {
            val observed = withTimeoutOrNull(METADATA_TIMEOUT_MS) {
                while (!synchronized(lock) { initialSync }) changed.receive()
                true
            } == true
            assertTrue(observed, "Metadata collector must observe initial sync")
        }

        fun terrestrialChannelId(): Long? = synchronized(lock) {
            completedDvrChannels
                .filterKeys { channelId ->
                    channelId in channels &&
                        !channels[channelId].orEmpty().contains("motorvision", ignoreCase = true)
                }
                .maxByOrNull { entry -> entry.value }
                ?.key
        }

        fun motorvisionChannelId(): Long? = synchronized(lock) {
            channels.entries
                .singleOrNull { entry -> entry.value == MOTORVISION_NAME }
                ?.key
        }

        fun connectionFailures(): Int = synchronized(lock) { failures }

        private fun recordCompletedDvrChannel(
            channelId: Long?,
            state: String?,
            stop: Long?,
            files: List<HtspDvrRecordingFile>?,
        ) {
            if (
                channelId != null &&
                state == "completed" &&
                stop != null &&
                files.orEmpty().any { file -> file.sizeBytes?.let { size -> size > 0L } == true }
            ) {
                completedDvrChannels.merge(channelId, stop, ::maxOf)
            }
        }

    }

    private class LiveSubscriptionObservation {
        private val lock = Any()
        private val changed = Channel<Unit>(Channel.CONFLATED)
        private var started: HtspSubscriptionStartMessage? = null
        private val speeds = mutableSetOf<Int>()
        private val timeshiftsAfterSpeed = mutableMapOf<Int, HtspTimeshiftStatusMessage>()
        private var speedAwaitingTimeshift: Int? = null
        private var stopped = false
        private var terminated = false

        fun accept(event: HtspSubscriptionEvent) {
            synchronized(lock) {
                when (event) {
                    is HtspSubscriptionEvent.Started -> {
                        started = event.message
                    }
                    is HtspSubscriptionEvent.Speed -> {
                        speeds += event.message.speed
                        speedAwaitingTimeshift = event.message.speed
                    }
                    is HtspSubscriptionEvent.Timeshift -> {
                        speedAwaitingTimeshift?.let { speed ->
                            timeshiftsAfterSpeed.putIfAbsent(speed, event.message)
                            speedAwaitingTimeshift = null
                        }
                    }
                    is HtspSubscriptionEvent.Stopped -> stopped = true
                    is HtspSubscriptionEvent.Terminated -> terminated = true
                    else -> Unit
                }
            }
            changed.trySend(Unit)
        }

        suspend fun awaitStarted(): HtspSubscriptionStartMessage {
            val observed = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                while (synchronized(lock) { started == null }) changed.receive()
                true
            } == true
            assertTrue(observed, "Live subscription must start")
            return synchronized(lock) { checkNotNull(started) }
        }

        suspend fun awaitSpeed(speed: Int): Boolean = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
            while (!synchronized(lock) { speed in speeds }) changed.receive()
            true
        } == true

        suspend fun awaitTimeshiftAfterSpeed(speed: Int): HtspTimeshiftStatusMessage? {
            val observed = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                while (!synchronized(lock) { timeshiftsAfterSpeed.containsKey(speed) }) changed.receive()
                true
            } == true
            return if (observed) synchronized(lock) { timeshiftsAfterSpeed[speed] } else null
        }

        fun describe(): String = synchronized(lock) {
            "typedSpeeds=${speeds.sorted()} typedTimeshifts=${timeshiftsAfterSpeed.keys.sorted()} " +
                "stopped=$stopped terminated=$terminated"
        }
    }

    private data class MutableSpeedTrace(
        val speed: Int,
        val requestFields: Set<String>,
        val subscriptionId: Long,
        var replyFields: Set<String>? = null,
        var replyMethod: String? = null,
        var replyOrder: Long? = null,
        var speedEventOrder: Long? = null,
        var speedEventFields: Set<String>? = null,
        var speedEventSequenceAbsent: Boolean = false,
        var timeshiftEventOrder: Long? = null,
        var timeshiftEventSequenceAbsent: Boolean = false,
        var timeshiftEventFields: Set<String>? = null,
        var timeshiftFull: Long? = null,
        var timeshiftShift: Long? = null,
    )

    private data class SpeedTraceSnapshot(
        val speed: Int,
        val requestFields: Set<String>,
        val replyFields: Set<String>?,
        val replyMethod: String?,
        val replyOrder: Long?,
        val speedEventOrder: Long?,
        val speedEventFields: Set<String>?,
        val speedEventSequenceAbsent: Boolean,
        val timeshiftEventOrder: Long?,
        val timeshiftEventSequenceAbsent: Boolean,
        val timeshiftEventFields: Set<String>?,
        val timeshiftFull: Long?,
        val timeshiftShift: Long?,
    ) {
        fun describe(): String {
            val events = listOfNotNull(
                replyOrder?.let { order -> order to "reply" },
                speedEventOrder?.let { order -> order to "speed-event" },
                timeshiftEventOrder?.let { order -> order to "timeshift-event" },
            ).sortedBy(Pair<Long, String>::first).joinToString(separator = ">") { event -> event.second }
            return "speed=$speed request=${requestFields.sorted()} reply=${replyFields?.sorted()} " +
                "speedEvent=${speedEventFields?.sorted()} " +
                "timeshiftEvent=${timeshiftEventFields?.sorted()} events=$events"
        }
    }

    private class SpeedReplyTraceProxy(
        upstreamHost: String,
        upstreamPort: Int,
    ) : Closeable {
        private val lock = Any()
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
        private val tracesBySequence = mutableMapOf<Int, MutableSpeedTrace>()
        private var serverOrder = 0L
        @Volatile
        private var clientSocket: Socket? = null
        @Volatile
        private var upstreamSocket: Socket? = null
        @Volatile
        private var closing = false
        private val proxyThread = thread(
            start = true,
            isDaemon = true,
            name = "htsp-speed-reply-trace",
        ) {
            runCatching {
                val client = serverSocket.accept().also { socket ->
                    clientSocket = socket
                    socket.tcpNoDelay = true
                }
                val upstream = Socket().also { socket ->
                    upstreamSocket = socket
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(upstreamHost, upstreamPort), CONNECT_TIMEOUT_MS)
                }
                val requestThread = thread(
                    start = true,
                    isDaemon = true,
                    name = "htsp-speed-reply-trace-requests",
                ) {
                    runCatching {
                        forwardFrames(client.getInputStream(), upstream.getOutputStream(), ::recordRequest)
                    }
                }
                forwardFrames(upstream.getInputStream(), client.getOutputStream(), ::recordServerMessage)
                requestThread.join()
            }
        }

        val port: Int = serverSocket.localPort

        suspend fun awaitTrace(speed: Int): SpeedTraceSnapshot? {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EVENT_TIMEOUT_MS)
            var snapshot: SpeedTraceSnapshot?
            do {
                snapshot = snapshot(speed)
                if (
                    snapshot?.replyOrder != null &&
                    snapshot.speedEventOrder != null &&
                    snapshot.timeshiftEventOrder != null
                ) {
                    return snapshot
                }
                delay(10L)
            } while (System.nanoTime() < deadline)
            return snapshot
        }

        private fun snapshot(speed: Int): SpeedTraceSnapshot? = synchronized(lock) {
            tracesBySequence.values.lastOrNull { trace -> trace.speed == speed }?.let { trace ->
                SpeedTraceSnapshot(
                    speed = trace.speed,
                    requestFields = trace.requestFields,
                    replyFields = trace.replyFields,
                    replyMethod = trace.replyMethod,
                    replyOrder = trace.replyOrder,
                    speedEventOrder = trace.speedEventOrder,
                    speedEventFields = trace.speedEventFields,
                    speedEventSequenceAbsent = trace.speedEventSequenceAbsent,
                    timeshiftEventOrder = trace.timeshiftEventOrder,
                    timeshiftEventSequenceAbsent = trace.timeshiftEventSequenceAbsent,
                    timeshiftEventFields = trace.timeshiftEventFields,
                    timeshiftFull = trace.timeshiftFull,
                    timeshiftShift = trace.timeshiftShift,
                )
            }
        }

        private fun recordRequest(message: HtspWireMessage) {
            if (message.method != "subscriptionSpeed") return
            val sequence = message.seq ?: return
            val speed = (message.fields["speed"] as? Number)?.toInt() ?: return
            val subscriptionId = (message.fields["subscriptionId"] as? Number)?.toLong() ?: return
            synchronized(lock) {
                tracesBySequence[sequence] = MutableSpeedTrace(
                    speed = speed,
                    requestFields = message.fields.keys.toSet(),
                    subscriptionId = subscriptionId,
                )
            }
        }

        private fun recordServerMessage(message: HtspWireMessage) {
            synchronized(lock) {
                val response = message.seq?.let(tracesBySequence::get)
                if (response != null) {
                    serverOrder += 1L
                    response.replyFields = message.fields.keys.toSet()
                    response.replyMethod = message.method
                    response.replyOrder = serverOrder
                    return
                }

                val subscriptionId = (message.fields["subscriptionId"] as? Number)?.toLong() ?: return
                val trace = tracesBySequence.values.lastOrNull { candidate ->
                    candidate.subscriptionId == subscriptionId
                } ?: return
                when (message.method) {
                    "subscriptionSpeed" -> {
                        val speed = (message.fields["speed"] as? Number)?.toInt()
                        if (speed == trace.speed && trace.speedEventOrder == null) {
                            serverOrder += 1L
                            trace.speedEventOrder = serverOrder
                            trace.speedEventFields = message.fields.keys.toSet()
                            trace.speedEventSequenceAbsent = message.seq == null
                        }
                    }
                    "timeshiftStatus" -> if (
                        trace.speedEventOrder != null && trace.timeshiftEventOrder == null
                    ) {
                        serverOrder += 1L
                        trace.timeshiftEventOrder = serverOrder
                        trace.timeshiftEventSequenceAbsent = message.seq == null
                        trace.timeshiftEventFields = message.fields.keys.toSet()
                        trace.timeshiftFull = (message.fields["full"] as? Number)?.toLong()
                        trace.timeshiftShift = (message.fields["shift"] as? Number)?.toLong()
                    }
                }
            }
        }

        private fun forwardFrames(
            input: InputStream,
            output: OutputStream,
            observer: (HtspWireMessage) -> Unit,
        ) {
            while (!closing) {
                val frame = readFrame(input) ?: return
                observer(HtspCodec.readMessage(ByteArrayInputStream(frame)))
                output.write(frame)
                output.flush()
            }
        }

        private fun readFrame(input: InputStream): ByteArray? {
            val header = ByteArray(Int.SIZE_BYTES)
            val first = input.read()
            if (first < 0) return null
            header[0] = first.toByte()
            readFully(input, header, offset = 1)
            val payloadSize = ByteBuffer.wrap(header).int
            require(payloadSize in 0..MAX_FRAME_BYTES) { "HTSP trace frame size is invalid" }
            val payload = ByteArray(payloadSize)
            readFully(input, payload, offset = 0)
            return header + payload
        }

        private fun readFully(input: InputStream, target: ByteArray, offset: Int) {
            var position = offset
            while (position < target.size) {
                val count = input.read(target, position, target.size - position)
                if (count < 0) throw EOFException("HTSP trace frame ended early")
                position += count
            }
        }

        override fun close() {
            closing = true
            runCatching { clientSocket?.close() }
            runCatching { upstreamSocket?.close() }
            runCatching { serverSocket.close() }
            proxyThread.join(1_000L)
        }

        private companion object {
            const val CONNECT_TIMEOUT_MS = 10_000
            const val MAX_FRAME_BYTES = 16 * 1_024 * 1_024
        }
    }
}
