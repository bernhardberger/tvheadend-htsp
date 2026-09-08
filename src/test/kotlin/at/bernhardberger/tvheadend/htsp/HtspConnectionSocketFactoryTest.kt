package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.HtspCodec
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.htsp.wire.HtspWireMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class HtspConnectionSocketFactoryTest {
    @Test
    fun publicSocketFactoryScriptsConnectHandshakeAndTypedRequestWithoutNetwork() = runBlocking {
        val callerThread = Thread.currentThread()
        val socket = ScriptedSocket(
            expectedHost = "127.0.0.1",
            expectedPort = 9_982,
            responses = listOf(
                loadHtspGoldenFrame("scripted-hello-response.hex"),
                loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                loadHtspGoldenFrame("scripted-get-sys-time-response.hex"),
            ),
        )
        val factoryCalls = AtomicInteger()
        val connection = createHtspConnection(
            ioDispatcher = Dispatchers.IO,
            clientIdentity = HtspClientIdentity(
                clientName = "socket-seam-client",
                clientVersion = "test",
            ),
            socketFactory = {
                factoryCalls.incrementAndGet()
                socket
            },
        )

        try {
            withTimeout(5_000L) {
                val outcome = connection.connect(
                    endpoint = HtspEndpoint("127.0.0.1", 9_982),
                    options = HtspConnectOptions(
                        connectTimeoutMs = 1_234,
                        responseTimeoutMs = 1_000L,
                        socketReadTimeoutMs = 2_345,
                        socketBufferBytes = 1_024,
                    ),
                )
                assertTrue(outcome is HtspConnectOutcome.Connected)
                val live = (outcome as HtspConnectOutcome.Connected).connection
                assertEquals(43, live.protocolVersion)
                assertEquals(true, live.dvrAccess)
                assertEquals(true, live.serverFacts.streaming)
                assertEquals(true, live.serverFacts.dvr)
                assertEquals(
                    HtspResult.Ok(
                        GetSysTimeResponse(
                            unixTimeSeconds = 1_723_456_789L,
                            legacyTimezoneHoursWestOfGmt = -2,
                            gmtOffsetMinutes = 120,
                        ),
                    ),
                    connection.getSysTime(
                        timeoutMs = 1_000L,
                        expectedGeneration = live.generation,
                    ),
                )
            }
        } finally {
            connection.close()
        }

        assertEquals(1, factoryCalls.get())
        assertTrue(socket.expectedAddressObserved)
        assertEquals(1_234, socket.connectTimeoutMs)
        assertEquals(2_345, socket.readTimeoutMs)
        assertTrue(socket.tcpNoDelayEnabled)
        assertTrue(socket.keepAliveEnabled)
        assertTrue(socket.isClosed)
        assertNotSame(callerThread, socket.connectThread)
        assertTrue(socket.writeThreads.none { it === callerThread })

        val requests = socket.requests()
        assertEquals(listOf("hello", "authenticate", "getSysTime"), requests.map { it.method })
        assertEquals(listOf(1, 2, 3), requests.map { it.seq })
        assertEquals(43L, requests[0].fields["htspversion"])
        assertEquals("socket-seam-client", requests[0].fields["clientname"])
        assertEquals(setOf("method", "seq"), requests[1].fields.keys)
        assertEquals(setOf("method", "seq"), requests[2].fields.keys)
    }

    @Test
    fun privateFileReadDispatchTransfersPayloadButRawRequestsKeepTheirRepresentation() = runBlocking {
        val responses = (3L..4L).map { sequence ->
            ByteArrayOutputStream().also { output ->
                HtspCodec.writeMessage(output, "fileRead", mapOf("seq" to sequence, "data" to byteArrayOf(1, 2, 3)))
            }.toByteArray()
        }
        val socket = ScriptedSocket(
            "127.0.0.1", 9_982,
            listOf(
                loadHtspGoldenFrame("scripted-hello-response.hex"),
                loadHtspGoldenFrame("scripted-authenticate-response.hex"),
            ) + responses,
        )
        val service = createHtspConnection(Dispatchers.IO, socketFactory = { socket }) as HtspService
        try {
            assertTrue(service.connect(HtspEndpoint("127.0.0.1", 9_982)) is HtspConnectOutcome.Connected)
            val request = FileReadRequest(1L, 3L)
            val reply = service.dispatch(
                requireNotNull(service.captureGeneration()), request, HtspRequestCodecs.encode(request), 1_000L,
            )
            val binary = reply.fields["data"] as HtspBinary
            val response = HtspRequestCodecs.decode(request, reply.fields, 43)
            assertSame(binary, response.data)
            val raw = service.request("fileRead", mapOf("id" to 1L, "size" to 3L))
            (raw.fields["data"] as ByteArray).fill(99)
            response.data.toByteArray().fill(99)
            assertArrayEquals(byteArrayOf(1, 2, 3), response.data.toByteArray())
        } finally {
            service.close()
        }
    }

    @Test
    fun throwingPublicSocketFactoryReturnsFailureAndRestoresDisconnectedState() = runBlocking {
        val factoryCalls = AtomicInteger()
        val connection = createHtspConnection(
            ioDispatcher = Dispatchers.IO,
            socketFactory = {
                factoryCalls.incrementAndGet()
                throw IOException("Scripted socket construction failure")
            },
        )

        try {
            assertEquals(
                HtspConnectOutcome.Failed(
                    HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
                ),
                connection.connect(HtspEndpoint("127.0.0.1", 9_982)),
            )
            assertSame(HtspConnectionState.Disconnected, connection.connectionState.value)
            assertEquals(1, factoryCalls.get())
        } finally {
            connection.close()
        }
    }

    @Test
    fun failedWriteRetiresSocketBeforeAnotherRequestCanReuseBufferedFrame() = runBlocking {
        val socket = ScriptedSocket(
            expectedHost = "127.0.0.1",
            expectedPort = 9_982,
            responses = listOf(
                loadHtspGoldenFrame("scripted-hello-response.hex"),
                loadHtspGoldenFrame("scripted-authenticate-response.hex"),
            ),
            beforePostHandshakeWrite = { throw IOException("Scripted partial write") },
        )
        val connection = createHtspConnection(Dispatchers.IO, socketFactory = { socket })
        try {
            assertTrue(connection.connect(HtspEndpoint("127.0.0.1", 9_982)) is HtspConnectOutcome.Connected)
            val generation = requireNotNull(connection.liveConnection.value).generation

            assertSame(HtspResult.TransportUnavailable, connection.getSysTime())
            assertTrue(socket.isClosed)
            assertTrue(connection.isCurrent(generation))
            assertNull(connection.liveConnection.value)
            assertSame(HtspResult.TransportUnavailable, connection.getSysTime())
            assertEquals(1, socket.postHandshakeWriteAttempts.get())
            assertEquals(listOf("hello", "authenticate"), socket.requests().map { it.method })
        } finally {
            connection.close()
        }
    }

    @Test
    fun cancellingBlockedConnectClosesItsSocketWithoutBlockingCallerThread() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val socket = ScriptedSocket(
            "127.0.0.1", 9_982, emptyList(),
            beforeConnect = {
                started.countDown()
                check(released.await(3, TimeUnit.SECONDS))
            },
            onClose = released::countDown,
        )
        val connection = createHtspConnection(Dispatchers.IO, socketFactory = { socket })
        try {
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                connection.connect(HtspEndpoint("127.0.0.1", 9_982))
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(!call.isCompleted)
            withTimeout(1_000L) { call.cancelAndJoin() }
            assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
            assertTrue(socket.isClosed)
            assertSame(HtspConnectionState.Disconnected, connection.connectionState.value)
        } finally {
            released.countDown()
            connection.close()
        }
    }

    @Test
    fun blockedWriteAndFlushAbortOnOwnedTimeoutOrCallerCancellation() = runBlocking {
        for (blockFlush in listOf(false, true)) {
            for ((ownedTimeout, callerTimeout) in listOf(false to false, false to true, true to false)) {
                val started = CountDownLatch(1)
                val released = CountDownLatch(1)
                val block = {
                    started.countDown()
                    check(released.await(3, TimeUnit.SECONDS))
                }
                val socket = ScriptedSocket(
                    "127.0.0.1", 9_982,
                    listOf(
                        loadHtspGoldenFrame("scripted-hello-response.hex"),
                        loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                    ),
                    beforePostHandshakeWrite = { if (!blockFlush) block() },
                    beforePostHandshakeFlush = { if (blockFlush) block() },
                    onClose = released::countDown,
                )
                val connection = createHtspConnection(Dispatchers.IO, socketFactory = { socket })
                val states = CopyOnWriteArrayList<HtspConnectionState>()
                val events = CopyOnWriteArrayList<HtspTransportEvent>()
                val stateCollector = launch(Dispatchers.Unconfined) { connection.connectionState.collect(states::add) }
                val eventCollector = launch(Dispatchers.Unconfined) { connection.events.collect(events::add) }
                try {
                    assertTrue(connection.connect(HtspEndpoint("127.0.0.1", 9_982)) is HtspConnectOutcome.Connected)
                    val reader = HtspService::class.java.getDeclaredField("readerJob").run {
                        isAccessible = true
                        get(connection) as Job
                    }
                    val call = async(start = CoroutineStart.UNDISPATCHED) {
                        if (callerTimeout) {
                            withTimeout(100L) { connection.getSysTime(timeoutMs = 2_000L) }
                        } else {
                            connection.getSysTime(timeoutMs = if (ownedTimeout) 100L else 2_000L)
                        }
                    }
                    assertTrue(started.await(1, TimeUnit.SECONDS))
                    if (ownedTimeout) {
                        assertSame(HtspResult.Timeout, withTimeout(1_000L) { call.await() })
                    } else if (callerTimeout) {
                        val failure = withTimeout(1_000L) { runCatching { call.await() }.exceptionOrNull() }
                        assertTrue(failure is TimeoutCancellationException)
                    } else {
                        withTimeout(1_000L) { call.cancelAndJoin() }
                        assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
                    }
                    assertTrue(socket.isClosed)
                    assertNull(connection.liveConnection.value)
                    assertSame(HtspResult.TransportUnavailable, connection.getSysTime())
                    withTimeout(1_000L) { reader.join() }
                    if (!ownedTimeout) {
                        assertTrue(states.none { it is HtspConnectionState.Error })
                        assertTrue(events.none { it is HtspTransportEvent.ConnectionFailure })
                    } else {
                        assertEquals(
                            HtspTransportFailureKind.CONNECTION_TIMEOUT,
                            events.filterIsInstance<HtspTransportEvent.ConnectionFailure>().single().failure.kind,
                        )
                    }
                } finally {
                    released.countDown()
                    connection.close()
                    stateCollector.cancelAndJoin()
                    eventCollector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun queuedOrdinaryAndHandshakeTimeoutsAndCancellationDoNotRetireWriter() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val socket = ScriptedSocket(
            "127.0.0.1", 9_982,
            listOf(
                loadHtspGoldenFrame("scripted-hello-response.hex"),
                loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                loadHtspGoldenFrame("scripted-get-sys-time-response.hex"),
            ),
            beforePostHandshakeWrite = {
                started.countDown()
                check(released.await(3, TimeUnit.SECONDS))
            },
            onClose = released::countDown,
        )
        val connection = createHtspConnection(Dispatchers.IO, socketFactory = { socket })
        try {
            assertTrue(connection.connect(HtspEndpoint("127.0.0.1", 9_982)) is HtspConnectOutcome.Connected)
            val writer = async(start = CoroutineStart.UNDISPATCHED) { connection.getSysTime(timeoutMs = 2_000L) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertSame(HtspResult.Timeout, connection.getSysTime(timeoutMs = 50L))
            assertSame(HtspResult.Timeout, connection.authenticate(timeoutMs = 50L))
            for (handshake in listOf(false, true)) {
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    if (handshake) connection.authenticate() else connection.getSysTime()
                }
                withTimeout(1_000L) { queued.cancelAndJoin() }
            }
            assertTrue(!socket.isClosed)
            assertTrue(connection.liveConnection.value != null)
            assertEquals(1, socket.postHandshakeWriteAttempts.get())
            released.countDown()
            assertTrue(withTimeout(1_000L) { writer.await() } is HtspResult.Ok)
            assertEquals(listOf("hello", "authenticate", "getSysTime"), socket.requests().map { it.method })
        } finally {
            released.countDown()
            connection.close()
        }
    }

    @Test
    fun cancellationDuringWorkerAdmissionDoesNotRetireAnUntouchedSocket() = runBlocking {
        for (ownedTimeout in listOf(false, true)) {
            val armed = AtomicBoolean()
            val workerQueued = CountDownLatch(1)
            val releaseWorker = CountDownLatch(1)
            val lockHeld = CountDownLatch(1)
            val releaseLock = CountDownLatch(1)
            val workerThread = AtomicReference<Thread>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (armed.compareAndSet(true, false)) {
                        Dispatchers.IO.dispatch(context) {
                            workerThread.set(Thread.currentThread())
                            workerQueued.countDown()
                            check(releaseWorker.await(3, TimeUnit.SECONDS))
                            block.run()
                        }
                    } else {
                        Dispatchers.IO.dispatch(context, block)
                    }
                }
            }
            val socket = ScriptedSocket(
                "127.0.0.1", 9_982,
                listOf(
                    loadHtspGoldenFrame("scripted-hello-response.hex"),
                    loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                    ByteArrayOutputStream().also { output ->
                        HtspCodec.writeMessage(output, "getSysTime", mapOf("seq" to 4L, "time" to 1L, "timezone" to 0L))
                    }.toByteArray(),
                ),
            )
            val connection = createHtspConnection(dispatcher, socketFactory = { socket })
            try {
                assertTrue(connection.connect(HtspEndpoint("127.0.0.1", 9_982)) is HtspConnectOutcome.Connected)
                val generation = requireNotNull(connection.liveConnection.value).generation
                armed.set(true)
                val request = async(Dispatchers.IO) {
                    connection.getSysTime(timeoutMs = if (ownedTimeout) 500L else 2_000L)
                }
                assertTrue(workerQueued.await(1, TimeUnit.SECONDS))
                val holder = thread(name = "hold-htsp-worker-admission") {
                    connection.commitIfCurrent(generation) {
                        lockHeld.countDown()
                        check(releaseLock.await(3, TimeUnit.SECONDS))
                    }
                }
                assertTrue(lockHeld.await(1, TimeUnit.SECONDS))
                releaseWorker.countDown()
                withTimeout(1_000L) {
                    while (workerThread.get().state != Thread.State.BLOCKED) delay(1L)
                }
                val cancellation = if (ownedTimeout) null else async(Dispatchers.IO) { request.cancelAndJoin() }
                if (ownedTimeout) delay(600L) else withTimeout(1_000L) {
                    while (!request.isCancelled) delay(1L)
                }
                releaseLock.countDown()
                holder.join(1_000L)
                if (ownedTimeout) {
                    assertSame(HtspResult.Timeout, withTimeout(1_000L) { request.await() })
                } else {
                    withTimeout(1_000L) { cancellation?.await() }
                    assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
                }
                assertTrue(!socket.isClosed)
                assertEquals(listOf("hello", "authenticate"), socket.requests().map { it.method })
                assertTrue(connection.getSysTime(timeoutMs = 1_000L) is HtspResult.Ok)
            } finally {
                releaseWorker.countDown()
                releaseLock.countDown()
                connection.close()
            }
        }
    }

    @Test
    fun expiredAdmissionBudgetCannotStartARequestFrame() = runBlocking {
        for (blockDispatch in listOf(false, true)) {
            val dispatchReached = CountDownLatch(1)
            val lockHeld = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean()
            val socket = ScriptedSocket(
                "127.0.0.1", 9_982,
                listOf(
                    loadHtspGoldenFrame("scripted-hello-response.hex"),
                    loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                    loadHtspGoldenFrame("scripted-get-sys-time-response.hex"),
                ),
            )
            val connection = object : HtspService(ioDispatcher = Dispatchers.IO, socketFactory = { socket }) {
                fun awaitContention(atDispatch: Boolean) {
                    if (blockDispatch == atDispatch && armed.compareAndSet(true, false)) {
                        dispatchReached.countDown()
                        check(lockHeld.await(3, TimeUnit.SECONDS))
                    }
                }

                override suspend fun dispatch(
                    generation: HtspCapturedGeneration,
                    request: HtspRequest<*>,
                    fields: LinkedHashMap<String, Any?>,
                    timeoutMs: Long,
                ): HtspWireReply {
                    awaitContention(true)
                    return super.dispatch(generation, request, fields, timeoutMs)
                }

                override suspend fun requestForConnectionAttempt(
                    expectedConnectionAttemptId: Long,
                    method: String,
                    fields: Map<String, Any?>,
                    timeoutMs: Long,
                    flush: Boolean,
                    disconnectOnTimeout: Boolean,
                    onReplyCommitted: ((HtspWireMessage) -> Unit)?,
                ): HtspWireMessage {
                    awaitContention(false)
                    return super.requestForConnectionAttempt(
                        expectedConnectionAttemptId, method, fields, timeoutMs, flush,
                        disconnectOnTimeout, onReplyCommitted,
                    )
                }
            }
            try {
                assertTrue(connection.connect(HtspEndpoint("127.0.0.1", 9_982)) is HtspConnectOutcome.Connected)
                val generation = requireNotNull(connection.liveConnection.value).generation
                val holder = thread(name = "hold-htsp-admission") {
                    check(dispatchReached.await(3, TimeUnit.SECONDS))
                    connection.commitIfCurrent(generation) {
                        lockHeld.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                }
                armed.set(true)
                val request = async(Dispatchers.IO) { connection.getSysTime(timeoutMs = 100L) }
                assertTrue(lockHeld.await(1, TimeUnit.SECONDS))
                delay(200L)
                release.countDown()
                holder.join(1_000L)
                assertSame(HtspResult.Timeout, withTimeout(1_000L) { request.await() })
                assertEquals(listOf("hello", "authenticate"), socket.requests().map { it.method })
                assertTrue(connection.liveConnection.value != null)
            } finally {
                release.countDown()
                connection.close()
            }
        }
    }

    @Test
    fun timeSpentInWriteQueueIsNotGrantedAgainForReply() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val socket = ScriptedSocket(
            "127.0.0.1", 9_982,
            listOf(
                loadHtspGoldenFrame("scripted-hello-response.hex"),
                loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                loadHtspGoldenFrame("scripted-get-sys-time-response.hex"),
                null,
            ),
            beforePostHandshakeWrite = {
                started.countDown()
                check(released.await(3, TimeUnit.SECONDS))
            },
            onClose = released::countDown,
        )
        val connection = createHtspConnection(Dispatchers.IO, socketFactory = { socket })
        try {
            assertTrue(connection.connect(HtspEndpoint("127.0.0.1", 9_982)) is HtspConnectOutcome.Connected)
            val writer = async(start = CoroutineStart.UNDISPATCHED) { connection.getSysTime(timeoutMs = 2_000L) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val queued = async(start = CoroutineStart.UNDISPATCHED) { connection.getSysTime(timeoutMs = 1_000L) }
            delay(600L)
            released.countDown()
            assertTrue(withTimeout(1_000L) { writer.await() } is HtspResult.Ok)
            assertSame(HtspResult.Timeout, withTimeout(750L) { queued.await() })
            assertEquals(4, socket.requests().size)
            assertTrue(!socket.isClosed)
        } finally {
            released.countDown()
            connection.close()
        }
    }

    @Test
    fun replacementAbortsBlockedConnectWithoutClosingReplacementSocket() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val first = ScriptedSocket(
            "127.0.0.1", 9_982, emptyList(),
            beforeConnect = {
                started.countDown()
                check(released.await(3, TimeUnit.SECONDS))
            },
            onClose = released::countDown,
        )
        val replacement = ScriptedSocket(
            "127.0.0.1", 9_982,
            listOf(
                loadHtspGoldenFrame("scripted-hello-response.hex"),
                loadHtspGoldenFrame("scripted-authenticate-response.hex"),
                loadHtspGoldenFrame("scripted-get-sys-time-response.hex"),
            ),
        )
        val sockets = listOf(first, replacement).iterator()
        val connection = createHtspConnection(Dispatchers.IO, socketFactory = { sockets.next() })
        try {
            val oldCall = async(start = CoroutineStart.UNDISPATCHED) {
                connection.connect(HtspEndpoint("127.0.0.1", 9_982))
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(
                withTimeout(1_000L) {
                    connection.connect(HtspEndpoint("127.0.0.1", 9_982), HtspConnectOptions(forceReconnect = true))
                } is HtspConnectOutcome.Connected,
            )
            assertTrue(runCatching { oldCall.await() }.exceptionOrNull() is CancellationException)
            assertTrue(first.isClosed)
            assertTrue(!replacement.isClosed)
            assertTrue(connection.getSysTime() is HtspResult.Ok)
        } finally {
            released.countDown()
            connection.close()
        }
    }

    @Test
    fun socketReturnedByFactoryAfterCancellationIsClosedWithoutInstallation() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val socket = ScriptedSocket("127.0.0.1", 9_982, emptyList())
        val connection = createHtspConnection(Dispatchers.IO, socketFactory = {
            started.countDown()
            check(released.await(3, TimeUnit.SECONDS))
            socket
        })
        try {
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                connection.connect(HtspEndpoint("127.0.0.1", 9_982))
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            call.cancel()
            released.countDown()
            withTimeout(1_000L) { call.join() }
            assertTrue(socket.isClosed)
            assertNull(socket.connectThread)
            assertNull(connection.liveConnection.value)
        } finally {
            released.countDown()
            connection.close()
        }
    }

    private class ScriptedSocket(
        private val expectedHost: String,
        private val expectedPort: Int,
        private val responses: List<ByteArray?>,
        private val beforePostHandshakeWrite: () -> Unit = {},
        private val beforePostHandshakeFlush: () -> Unit = {},
        private val beforeConnect: () -> Unit = {},
        private val onClose: () -> Unit = {},
    ) : Socket() {
        private val responseInput = PipedInputStream(4_096)
        private val responseOutput = PipedOutputStream(responseInput)
        private val requestBuffer = ByteArrayOutputStream()
        private val requestMessages = CopyOnWriteArrayList<HtspWireMessage>()
        private val responseIndex = AtomicInteger()
        private val connected = AtomicBoolean()
        private val closed = AtomicBoolean()
        val postHandshakeWriteAttempts = AtomicInteger()
        val writeThreads = CopyOnWriteArrayList<Thread>()
        var connectThread: Thread? = null
            private set

        @Volatile
        var expectedAddressObserved: Boolean = false
            private set

        @Volatile
        var connectTimeoutMs: Int? = null
            private set

        @Volatile
        var readTimeoutMs: Int? = null
            private set

        @Volatile
        var tcpNoDelayEnabled: Boolean = false
            private set

        @Volatile
        var keepAliveEnabled: Boolean = false
            private set

        private val requestOutput = object : OutputStream() {
            override fun write(value: Int) {
                synchronized(requestBuffer) {
                    requestBuffer.write(value)
                }
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (closed.get()) throw IOException("Scripted socket is closed")
                writeThreads += Thread.currentThread()
                if (responseIndex.get() >= 2) {
                    postHandshakeWriteAttempts.incrementAndGet()
                    beforePostHandshakeWrite()
                }
                if (closed.get()) throw IOException("Scripted socket is closed")
                synchronized(requestBuffer) {
                    requestBuffer.write(bytes, offset, length)
                }
            }

            override fun flush() {
                if (closed.get()) throw IOException("Scripted socket is closed")
                if (responseIndex.get() >= 2) beforePostHandshakeFlush()
                if (closed.get()) throw IOException("Scripted socket is closed")
                val frame = synchronized(requestBuffer) {
                    if (requestBuffer.size() == 0) return
                    requestBuffer.toByteArray().also { requestBuffer.reset() }
                }
                requestMessages += HtspCodec.readMessage(ByteArrayInputStream(frame))
                val index = responseIndex.getAndIncrement()
                check(index in responses.indices) { "Unexpected scripted request" }
                val response = responses[index] ?: return
                responseOutput.write(response)
                responseOutput.flush()
            }
        }

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            connectThread = Thread.currentThread()
            beforeConnect()
            if (closed.get()) throw IOException("Scripted socket is closed")
            val address = endpoint as? InetSocketAddress
            expectedAddressObserved =
                address?.hostString == expectedHost && address.port == expectedPort
            connectTimeoutMs = timeout
            connected.set(true)
        }

        override fun getInputStream(): InputStream = responseInput

        override fun getOutputStream(): OutputStream = requestOutput

        override fun setTcpNoDelay(on: Boolean) {
            tcpNoDelayEnabled = on
        }

        override fun setKeepAlive(on: Boolean) {
            keepAliveEnabled = on
        }

        override fun setSoTimeout(timeout: Int) {
            readTimeoutMs = timeout
        }

        override fun isConnected(): Boolean = connected.get()

        override fun isClosed(): Boolean = closed.get()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            onClose()
            responseOutput.close()
            responseInput.close()
        }

        fun requests(): List<HtspWireMessage> = requestMessages.toList()
    }
}
