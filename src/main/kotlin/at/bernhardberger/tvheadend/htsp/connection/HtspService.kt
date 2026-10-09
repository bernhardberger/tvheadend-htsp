package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

private class HtspEventPublicationException : Exception("HTSP event publication failed")

internal open class `HtspService-internal`(
    private val ioDispatcher: CoroutineDispatcher,
    private val clientIdentity: HtspClientIdentity = HtspClientIdentity.Default,
    private val logger: HtspLogger = HtspLogger.None,
    private val socketFactory: () -> Socket = ::Socket,
    private val afterConnectionAdmission: suspend () -> Unit = {},
    private val afterTransportInstallation: suspend () -> Unit = {},
    private val afterTeardownAdmission: suspend () -> Unit = {},
    private val beforeTypedRecapture: suspend (HtspRequest<*>) -> Unit = {},
    private val beforeTypedEventPublication: (HtspTransportEvent.ServerMessage) -> Unit = {},
    private val beforeFrameRead: () -> Unit = {},
    private val afterPublicationCurrencyCheck: (HtspTransportEvent) -> Unit = {},
    private val afterConnectedSnapshot: suspend () -> Unit = {},
    private val afterAuthenticationAcknowledgement: suspend () -> Unit = {},
    private val beforeLateReplyExpiry: suspend () -> Unit = {},
    private val afterRequestFrameWritten: (String) -> Unit = {},
    beforeStatePublication: (HtspConnectionState) -> Unit = {},
    private val eventBufferOptions: HtspEventBufferOptions = HtspEventBufferOptions(),
    private val metadataEventBufferCapacity: Int = eventBufferOptions.metadataQueueEvents,
    private val subscriptionEventBufferCapacity: Int = eventBufferOptions.subscriptionQueueEvents,
    private val nanoTime: () -> Long = System::nanoTime,
    private val requestNanoTime: () -> Long = System::nanoTime,
) : HtspRequestTransport, HtspConnection {
    private val attemptMonitor = HtspAttemptMonitor(beforeStatePublication)
    override val connectionState: StateFlow<HtspConnectionState> = attemptMonitor.connectionState
    override val liveConnection: StateFlow<HtspLiveConnection?> = attemptMonitor.liveConnection

    private val metadataCollectors = mutableSetOf<HtspMetadataEventBuffer>()
    override val events: Flow<HtspTransportEvent> = flow {
        val buffer = HtspMetadataEventBuffer(metadataEventBufferCapacity, eventBufferOptions.metadataQueueBytes, attemptMonitor::queueWakeup)
        attemptMonitor.withAttemptLock { metadataCollectors.add(buffer) }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val event = attemptMonitor.withAttemptLock { buffer.poll() }
                if (event == null) withContext(ioDispatcher) { buffer.eventsAvailable.receive() } else emit(event)
            }
        } finally {
            attemptMonitor.withAttemptLock { metadataCollectors.remove(buffer) }
        }
    }

    open fun currentConnectionState(): HtspConnectionState = attemptMonitor.withAttemptLock { attemptMonitor.stateLocked().state }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + ioDispatcher)
    private val lifecycle = TerminalLifecycleGate("HTSP service is closed")

    private val pending = ConcurrentHashMap<Int, PendingReq>()
    private data class LateReplyObserver(
        val observer: (HtspWireMessage) -> Unit,
        val expiry: Job,
        val deadlineNanos: Long,
    )
    private val lateReplyObservers = ConcurrentHashMap<Int, LateReplyObserver>()

    private data class PendingReq(
        val def: CompletableDeferred<HtspWireMessage>,
        val onReplyCommitted: ((HtspWireMessage) -> Unit)?,
        @Volatile var sentAtNanos: Long? = null,
    )

    private val seq = AtomicInteger(1)

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    @Volatile
    private var connectionAttempt = 0L

    @Volatile
    private var liveTransportAttempt: Long? = null

    private var transportRetirement: HtspSubscriptionTermination? = null

    private var protocolGeneration: ServiceProtocolGeneration? = null

    private val typedRequestCaller = HtspTypedRequestCaller(this)

    private var liveServerFacts: HtspServerFacts? = null

    private var liveConnectionIdentity: HtspConnectionIdentity? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var connectingSocket: Socket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var challenge: ByteArray? = null

    @Volatile
    private var negotiatedHtspVersion: Int? = null

    // ---- health ----
    @Volatile
    private var lastReadAtNanos: Long = 0L

    init {
        require(metadataEventBufferCapacity > 0) {
            "metadataEventBufferCapacity must be positive"
        }
        require(subscriptionEventBufferCapacity > 0) {
            "subscriptionEventBufferCapacity must be positive"
        }
    }

    open suspend fun connect(
        host: String,
        port: Int,
        username: String? = null,
        password: String? = null,
        clientName: String = clientIdentity.clientName,
        clientVersion: String = clientIdentity.clientVersion,
        protocolVersion: Int = 44,

        connectTimeoutMs: Int = 10_000,
        responseTimeoutMs: Long = 5_000,

        soTimeoutMs: Int = 60_000,

        socketBufferBytes: Int = 64 * 1024,

        forceReconnect: Boolean = false
    ): HtspLiveConnection {
        val requestedIdentity = HtspConnectionIdentity(host, port, username, password)
        val (admittedAttempt, reused) = attemptMonitor.withAttemptLock {
            lifecycle.admit {
                if (!forceReconnect && canReuseLiveConnectionLocked(requestedIdentity)) {
                    null to checkNotNull(attemptMonitor.stateLocked().live)
                } else {
                    val attempt = admitReplacementGenerationLocked(HtspSubscriptionTermination.GENERATION_LOST)
                    attemptMonitor.recordStateLocked(HtspConnectionState.Connecting(host, port))
                    attempt to null
                }
            }
        }
        val attemptId = admittedAttempt ?: return checkNotNull(reused)
        try {
            afterConnectionAdmission()
            retireAdmissionTransport(attemptId)
            val connected = connectMutex.withLock {
                ensureCurrentConnectionAttempt(attemptId)
                publishConnectionState(
                    attemptId,
                    HtspConnectionState.Connecting(host, port),
                )

                try {
                    val candidate = AtomicReference<Socket?>()
                    val (inp, out) = blockingSocketIo(onCancellation = { closeSocket(candidate.get()) }) {
                        val created = socketFactory().also(candidate::set)
                        try {
                            ensureActive()
                            attemptMonitor.withAttemptLock {
                                lifecycle.admit {
                                    ensureCurrentConnectionAttempt(attemptId)
                                    connectingSocket = created
                                }
                            }
                            created.tcpNoDelay = true
                            created.keepAlive = true
                            created.soTimeout = soTimeoutMs
                            val address = InetSocketAddress(host, port)
                            ensureActive()
                            created.connect(address, connectTimeoutMs)
                            BufferedInputStream(created.getInputStream(), socketBufferBytes) to
                                BufferedOutputStream(created.getOutputStream(), socketBufferBytes)
                        } catch (failure: Throwable) {
                            closeSocket(created)
                            throw failure
                        }
                    }
                    val s = checkNotNull(candidate.get())

                    installTransport(attemptId, s, inp, out)
                    afterTransportInstallation()
                    lastReadAtNanos = nanoTime()

                    val reader = attemptMonitor.withAttemptLock {
                        ensureCurrentConnectionAttempt(attemptId)
                        if (liveTransportAttempt != attemptId || readerJob != null) {
                            throw CancellationException("Superseded connection attempt")
                        }
                        scope.launch(start = CoroutineStart.LAZY) {
                            readerLoop(
                                transportInput = inp,
                                responseTimeoutMs = responseTimeoutMs,
                                attemptId = attemptId,
                            )
                        }.also { ownedReader ->
                            readerJob = ownedReader
                        }
                    }
                    reader.start()

                    val helloRequest = HelloRequest(
                        protocolVersion = protocolVersion.toLong(),
                        clientName = clientName,
                    )
                    val hello = when (
                        val result = requestTypedHandshake(
                            request = helloRequest,
                            timeoutMs = responseTimeoutMs,
                            protocolVersion = 0,
                        )
                    ) {
                        is HtspResult.Ok -> result.value
                        is HtspFailure -> throw IllegalStateException("HTSP hello failed")
                    }
                    if (hello.protocolVersion < MINIMUM_HTSP_PROTOCOL_VERSION) {
                        throw HtspUnsupportedServerVersionException()
                    }
                    val negotiatedVersion = checkNotNull(
                        negotiatedHtspVersion(
                            requested = helloRequest.protocolVersion,
                            server = hello.protocolVersion,
                        ),
                    )
                    val sessionChallenge = hello.challenge.toByteArray()
                    challenge = sessionChallenge
                    negotiatedHtspVersion = negotiatedVersion

                    val user = username?.trim().orEmpty()
                    val pass = password.orEmpty()

                    // Always call authenticate, even without credentials: the server leaves
                    // address-based anonymous rights untouched when the message carries no
                    // username, and the reply is the only place HTSP reports our rights.
                    val withCredentials =
                        HtspAuthenticationPolicy.shouldAuthenticate(username, password) &&
                            challenge != null
                    val authEnvelopeFields = if (withCredentials) {
                        mapOf<String, Any?>(
                            "username" to user,
                            "digest" to makeDigest(pass, challenge!!),
                        )
                    } else {
                        emptyMap()
                    }
                    val auth = when (
                        val result = requestTypedHandshake(
                            request = AuthenticateRequest(),
                            envelopeFields = authEnvelopeFields,
                            timeoutMs = responseTimeoutMs,
                            protocolVersion = negotiatedVersion,
                        )
                    ) {
                        is HtspResult.Ok -> result.value
                        is HtspFailure -> throw HtspAuthenticationRejectedException()
                    }
                    afterAuthenticationAcknowledgement()
                    // HTSP ≥ 26 includes ACCESS_HTSP_RECORDER as "dvr".
                    val dvrAccess =
                        if (negotiatedVersion > 25) {
                            auth.dvr
                        } else {
                            null
                        }
                    val serverFacts = HtspServerFacts()
                        .withHelloObservations(hello)
                        .withAuthenticateObservations(auth)

                    publishConnectedState(
                            attemptId = attemptId,
                            state = HtspConnectionState.Connected(
                                host = host,
                                port = port,
                                protocolVersion = negotiatedHtspVersion,
                                dvrAccess = dvrAccess,
                            ),
                            serverFacts = serverFacts,
                            connectionIdentity = requestedIdentity,
                        ) ?: attemptMonitor.withAttemptLock {
                            ensureCurrentConnectionAttempt(attemptId)
                            throw RecordedConnectFailure(
                                (attemptMonitor.stateLocked().state as? HtspConnectionState.Error)?.failure
                                    ?: HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
                            )
                        }

                } catch (cancelled: CancellationException) {
                    disconnectInternal(
                        t = cancelled,
                        attemptId = attemptId,
                        publishState = true,
                        termination = HtspSubscriptionTermination.LOCAL_RETIREMENT,
                    )
                    throw cancelled
                } catch (t: Throwable) {
                    if (!isCurrentConnectionAttempt(attemptId)) {
                        val superseded = CancellationException("Superseded connection attempt")
                        superseded.initCause(t)
                        disconnectInternal(
                            t = superseded,
                            attemptId = attemptId,
                            publishState = false,
                            termination = HtspSubscriptionTermination.GENERATION_LOST,
                        )
                        throw superseded
                    }
                    disconnectInternal(
                        t = t,
                        attemptId = attemptId,
                        publishState = false,
                        termination = HtspSubscriptionTermination.INTERNAL_FAILURE,
                    )
                    withCurrentConnectionAttempt(attemptId) {
                        if (attemptMonitor.stateLocked().state !is HtspConnectionState.Error) {
                            val failure = typedTransportFailure(t)
                            attemptMonitor.recordStateLocked(HtspConnectionState.Error(failure))
                        }
                    }
                    throw t
                }
            }
            afterConnectedSnapshot()
            return connected
        } catch (cancelled: CancellationException) {
            retireAdmissionTransport(attemptId)
            publishConnectionState(attemptId, HtspConnectionState.Disconnected)
            throw cancelled
        }
    }

    override suspend fun connect(
        endpoint: HtspEndpoint,
        options: HtspConnectOptions,
    ): HtspConnectOutcome = try {
        val connection = connect(
            host = endpoint.host,
            port = endpoint.port,
            username = endpoint.username,
            password = endpoint.password,
            protocolVersion = options.requestedProtocolVersion,
            connectTimeoutMs = options.connectTimeoutMs.toInt(),
            responseTimeoutMs = options.responseTimeoutMs,
            soTimeoutMs = options.socketReadTimeoutMs.toInt(),
            socketBufferBytes = options.socketBufferBytes,
            forceReconnect = options.forceReconnect,
        )
        HtspConnectOutcome.Connected(connection)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.SUPERSEDED))
    } catch (error: RecordedConnectFailure) {
        HtspConnectOutcome.Failed(error.failure)
    } catch (error: Exception) {
        HtspConnectOutcome.Failed(typedTransportFailure(error))
    }

    override suspend fun <R> execute(
        request: HtspRequest<R>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspResult<R> {
        val result = typedRequestCaller.call(request, timeoutMs, expectedGeneration)
        currentCoroutineContext().ensureActive()
        return result
    }

    private class RecordedConnectFailure(val failure: HtspTransportFailure) : IOException()

    override fun subscriptionEvents(
        subscriptionId: Long,
    ): Flow<HtspSubscriptionEvent> = subscriptionEventsForGeneration(subscriptionId, expectedGeneration = null)

    override fun subscriptionEvents(
        subscriptionId: Long,
        expectedGeneration: HtspConnectionGeneration,
    ): Flow<HtspSubscriptionEvent> =
        subscriptionEventsForGeneration(subscriptionId, expectedGeneration)

    private fun subscriptionEventsForGeneration(
        subscriptionId: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): Flow<HtspSubscriptionEvent> {
        requireU32("subscriptionId", subscriptionId)
        val collected = AtomicBoolean(false)
        return flow {
            check(collected.compareAndSet(false, true)) {
                "HTSP subscription stream may be collected only once"
            }
            val collectorContext = currentCoroutineContext()
            collectorContext.ensureActive()
            val collectorJob = collectorContext[Job]
            val registration = attemptMonitor.withAttemptLock {
                val generation = protocolGeneration
                    ?: return@withAttemptLock null
                if (expectedGeneration != null && generation.token !== expectedGeneration) {
                    return@withAttemptLock null
                }
                val live = attemptMonitor.stateLocked().live
                if (!(
                    live?.generation === generation.token &&
                        liveTransportAttempt == generation.attemptId &&
                        connectionAttempt == generation.attemptId &&
                        attemptMonitor.stateLocked().state is HtspConnectionState.Connected
                )) return@withAttemptLock null
                check(generation.collectedSubscriptionIds.add(subscriptionId)) {
                    "HTSP subscription stream already collected in this generation"
                }
                val stream = HtspSubscriptionEventBuffer(
                    capacity = subscriptionEventBufferCapacity,
                    collectorJob = collectorJob,
                    byteCapacity = eventBufferOptions.subscriptionQueueBytes,
                    wakeup = attemptMonitor::queueWakeup,
                )
                generation.subscriptionStreams[subscriptionId] = stream
                generation to stream
            }
            if (registration == null) {
                emit(HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.GENERATION_LOST))
                return@flow
            }
            val (generation, stream) = registration

            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    var complete = false
                    val event = attemptMonitor.withAttemptLock {
                        stream.poll().also { next ->
                            if (next == null) complete = stream.isComplete()
                        }
                    }
                    when {
                        event != null -> emit(event)
                        complete -> return@flow
                        else -> withContext(ioDispatcher) { stream.eventsAvailable.receive() }
                    }
                }
            } finally {
                attemptMonitor.withAttemptLock {
                    stream.abandon()
                    if (generation.subscriptionStreams[subscriptionId] === stream) {
                        generation.subscriptionStreams.remove(subscriptionId)
                        generation.subscriptionTimestampClocks.remove(subscriptionId)
                    }
                }
            }
        }
    }

    private suspend fun <R> requestTypedHandshake(
        request: HtspRequest<R>,
        envelopeFields: Map<String, Any?> = emptyMap(),
        timeoutMs: Long,
        protocolVersion: Int,
    ): HtspResult<R> {
        val fields = HtspRequestCodecs.encode(request).apply { putAll(envelopeFields) }
        val reply = request(
            method = request.method,
            fields = fields,
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = true,
        )
        return classifyHtspReply(HtspWireReply(reply.fields), request, protocolVersion)
    }

    override fun isCurrent(generation: HtspConnectionGeneration): Boolean =
        attemptMonitor.withAttemptLock {
            protocolGeneration?.token === generation
        }

    open suspend fun request(
        method: String,
        fields: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 5_000,
        flush: Boolean = true,
        disconnectOnTimeout: Boolean = true
    ): HtspWireMessage = requestInternal(
        expectedConnectionAttemptId = null,
        method = method,
        fields = fields,
        timeoutMs = timeoutMs,
        flush = flush,
        disconnectOnTimeout = disconnectOnTimeout,
    )

    internal open suspend fun requestForConnectionAttempt(
        expectedConnectionAttemptId: Long,
        method: String,
        fields: Map<String, Any?>,
        timeoutMs: Long = 5_000,
        flush: Boolean = true,
        disconnectOnTimeout: Boolean = true,
        onReplyCommitted: ((HtspWireMessage) -> Unit)? = null,
    ): HtspWireMessage = requestInternal(
        expectedConnectionAttemptId = expectedConnectionAttemptId,
        method = method,
        fields = fields,
        timeoutMs = timeoutMs,
        flush = flush,
        disconnectOnTimeout = disconnectOnTimeout,
        onReplyCommitted = onReplyCommitted,
    )

    /**
     * Admits a module-internal request only while both the transport attempt and
     * the caller's additional generation predicate are current.
     */
    internal open suspend fun requestForConnectionAttemptIf(
        expectedConnectionAttemptId: Long,
        isRequestAdmitted: () -> Boolean,
        method: String,
        fields: Map<String, Any?>,
        timeoutMs: Long = 5_000,
        flush: Boolean = true,
        disconnectOnTimeout: Boolean = true,
        onReplyCommitted: ((HtspWireMessage) -> Unit)? = null,
    ): HtspWireMessage = requestInternal(
        expectedConnectionAttemptId = expectedConnectionAttemptId,
        method = method,
        fields = fields,
        timeoutMs = timeoutMs,
        flush = flush,
        disconnectOnTimeout = disconnectOnTimeout,
        isRequestAdmitted = isRequestAdmitted,
        onReplyCommitted = onReplyCommitted,
    )

    private suspend fun requestInternal(
        expectedConnectionAttemptId: Long?,
        method: String,
        fields: Map<String, Any?>,
        timeoutMs: Long,
        flush: Boolean,
        disconnectOnTimeout: Boolean,
        isRequestAdmitted: (() -> Boolean)? = null,
        onReplyCommitted: ((HtspWireMessage) -> Unit)? = null,
    ): HtspWireMessage {
        val startedAtNanos = requestNanoTime()
        val requestContext = currentCoroutineContext()
        fun remainingMs(): Long = timeoutMs - (requestNanoTime() - startedAtNanos) / 1_000_000L
        val admission = attemptMonitor.withAttemptLock {
            lifecycle.admit {
                requestContext.ensureActive()
                if (remainingMs() <= 0L) throw HtspRequestTimeoutException(method, timeoutMs)
                val transport = if (expectedConnectionAttemptId == null) {
                    socket to output
                } else {
                    if (connectionAttempt != expectedConnectionAttemptId) {
                        throw CancellationException("Stale HTSP connection attempt")
                    }
                    if (liveTransportAttempt != expectedConnectionAttemptId) {
                        throw IOException("HTSP transport unavailable")
                    }
                    if (isRequestAdmitted?.invoke() == false) {
                        throw CancellationException("Stale HTSP connection attempt")
                    }
                    socket to output
                }
                val requestOutput = transport.second ?: throw IOException("HTSP transport unavailable")
                if (liveTransportAttempt == null) throw IOException("HTSP transport unavailable")
                val requestSequence = seq.getAndIncrement()
                val response = CompletableDeferred<HtspWireMessage>()
                pending[requestSequence] = PendingReq(
                    def = response,
                    onReplyCommitted = onReplyCommitted,
                )
                RequestAdmission(
                    sequence = requestSequence,
                    response = response,
                    socket = transport.first,
                    output = requestOutput,
                )
            }
        }
        val s = admission.sequence
        val def = admission.response
        val frameStarted = AtomicBoolean()
        val frameComplete = AtomicBoolean()
        var writeAborted = false
        val isHandshake = method == "hello" || method == "authenticate"

        return try {
            val response = withTimeoutOrNull(remainingMs()) {
                val msgFields = LinkedHashMap<String, Any?>(fields.size + 1).apply {
                    this["seq"] = s.toLong() and 0xFFFF_FFFFL
                    putAll(fields)
                    this["seq"] = s.toLong() and 0xFFFF_FFFFL
                }
                writeMutex.withLock {
                    blockingSocketIo(onCancellation = { cause ->
                        val retire = attemptMonitor.withAttemptLock {
                            writeAborted = true
                            frameStarted.get() && (!frameComplete.get() || isHandshake)
                        }
                        if (retire) {
                            markTransportGone(
                                admission.socket,
                                if (cause is TimeoutCancellationException && requestContext.isActive) {
                                    HtspSubscriptionTermination.TIMEOUT
                                } else {
                                    HtspSubscriptionTermination.LOCAL_RETIREMENT
                                },
                            )
                        }
                    }) {
                        attemptMonitor.withAttemptLock {
                            if (writeAborted) throw CancellationException("HTSP write cancelled before admission")
                            ensureActive()
                            requestContext.ensureActive()
                            if (remainingMs() <= 0L) throw HtspRequestTimeoutException(method, timeoutMs)
                            if (socket !== admission.socket || liveTransportAttempt == null) {
                                throw IOException("HTSP transport unavailable")
                            }
                            frameStarted.set(true)
                        }
                        try {
                            HtspCodec.writeMessage(admission.output, method, msgFields)
                            if (flush) admission.output.flush()
                            frameComplete.set(true)
                            afterRequestFrameWritten(method)
                            pending[s]?.sentAtNanos = nanoTime()
                        } catch (failure: IOException) {
                            // A failed write may have sent a prefix; never reuse this stream.
                            markTransportGone(admission.socket, HtspSubscriptionTermination.IO_FAILURE)
                            throw failure
                        }
                    }
                }
                def.await()
            }
            if (response == null) {
                if ((disconnectOnTimeout || isHandshake) && frameStarted.get()) {
                    markTransportGone(admission.socket, HtspSubscriptionTermination.TIMEOUT)
                }
                if (disconnectOnTimeout) {
                    throw SocketTimeoutException(
                        "HTSP request '$method' timed out after ${timeoutMs}ms"
                    )
                }

                throw HtspRequestTimeoutException(method, timeoutMs)
            }
            response
        } catch (t: Throwable) {
            if (t is CancellationException && isHandshake && frameStarted.get()) {
                markTransportGone(admission.socket, HtspSubscriptionTermination.LOCAL_RETIREMENT)
            }
            if (frameComplete.get() && remainingMs() > 0L) {
                preserveLateReplyObserver(s, admission.socket, startedAtNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
            } else {
                pending.remove(s)
            }
            throw t
        }
    }

    private fun preserveLateReplyObserver(requestSequence: Int, requestSocket: Socket?, deadlineNanos: Long) {
        attemptMonitor.withAttemptLock {
            val request = pending.remove(requestSequence) ?: return
            if (socket === requestSocket && liveTransportAttempt != null) {
                request.onReplyCommitted?.let { observer ->
                    val expiry = scope.launch(start = CoroutineStart.LAZY) {
                        beforeLateReplyExpiry()
                        val remainingNanos = deadlineNanos - requestNanoTime()
                        if (remainingNanos > 0L) kotlinx.coroutines.delay((remainingNanos - 1L) / 1_000_000L + 1L)
                        attemptMonitor.withAttemptLock { lateReplyObservers.remove(requestSequence) }
                    }
                    lateReplyObservers[requestSequence] = LateReplyObserver(observer, expiry, deadlineNanos)
                    expiry.start()
                }
            }
        }
    }

    private suspend fun <T> blockingSocketIo(
        onCancellation: (Throwable?) -> Unit,
        block: CoroutineScope.() -> T,
    ): T = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val phase = AtomicReference(SocketIoPhase.QUEUED)
            continuation.invokeOnCancellation { cause ->
                if (phase.getAndSet(SocketIoPhase.CANCELLED) == SocketIoPhase.ACTIVE) {
                    onCancellation(cause)
                }
            }
            launch(ioDispatcher) {
                if (!phase.compareAndSet(SocketIoPhase.QUEUED, SocketIoPhase.ACTIVE)) return@launch
                val result = runCatching {
                    ensureActive()
                    block()
                }
                phase.compareAndSet(SocketIoPhase.ACTIVE, SocketIoPhase.COMPLETE)
                continuation.resumeWith(result)
            }
        }
    }

    private enum class SocketIoPhase { QUEUED, ACTIVE, COMPLETE, CANCELLED }

    override suspend fun disconnect(
        expectedGeneration: HtspConnectionGeneration?,
    ): Boolean {
      val changed = withContext(NonCancellable) {
        val attemptId = try {
            attemptMonitor.withAttemptLock { lifecycle.admit { beginTeardownAttempt(expectedGeneration) } }
        } catch (_: IllegalStateException) { null }
          catch (_: CancellationException) { null }
        if (attemptId == null) return@withContext false
        afterTeardownAdmission()
        val retirement = attemptMonitor.withAttemptLock {
            if (connectionAttempt != attemptId ||
                (expectedGeneration != null && protocolGeneration?.token !== expectedGeneration) ||
                (attemptMonitor.stateLocked().state is HtspConnectionState.Disconnected && socket == null &&
                    connectingSocket == null && !admissionRetirements.containsKey(attemptId))) {
                return@withAttemptLock null
            }
            ++connectionAttempt
            terminateSubscriptionStreamsLocked(protocolGeneration, HtspSubscriptionTermination.LOCAL_RETIREMENT)
            attemptMonitor.recordStateLocked(HtspConnectionState.Disconnected)
            captureCurrentTransportLocked(CancellationException("Disconnected"))
        } ?: return@withContext false
        retireAdmissionTransport(attemptId)
        finishTransportRetirement(retirement)
        true
      }
      currentCoroutineContext().ensureActive()
      return changed
    }

    override suspend fun close(expectedGeneration: HtspConnectionGeneration?): Boolean {
        val attemptId = if (expectedGeneration == null) {
            beginClose()
        } else {
            try {
                attemptMonitor.withAttemptLock {
                    lifecycle.admit {
                        if (protocolGeneration?.token === expectedGeneration) beginClose() else null
                    }
                }
            } catch (_: IllegalStateException) { null }
        }
        if (attemptId != null) finishClose(attemptId)
        currentCoroutineContext().ensureActive()
        return attemptId != null
    }

    internal fun beginClose(): Long? = attemptMonitor.withAttemptLock {
        lifecycle.close { beginConnectionAttempt(HtspSubscriptionTermination.LOCAL_RETIREMENT) }
    }

    internal suspend fun finishClose(attemptId: Long) {
        withContext(NonCancellable) {
            retireAdmissionTransport(attemptId)
            try {
                connectMutex.withLock {
                    disconnectInternal(
                        t = CancellationException("HTSP service closed"),
                        attemptId = attemptId,
                        publishState = true,
                        termination = HtspSubscriptionTermination.LOCAL_RETIREMENT,
                    )
                }
            } finally {
                serviceJob.cancelAndJoin()
            }
        }
    }

    private suspend fun readerLoop(
        transportInput: InputStream,
        responseTimeoutMs: Long,
        attemptId: Long,
    ) {
        val pendingMaxSilentMs = if (responseTimeoutMs > Long.MAX_VALUE / 2) Long.MAX_VALUE else responseTimeoutMs * 2
        val framedInput = HtspTransportInputStream(transportInput, logger, pendingMaxSilentMs, nanoTime)
        var messageSequence = 0L
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    framedInput.beginFrame()
                    beforeFrameRead()
                    val msg = HtspCodec.readMessage(framedInput)
                    val currentMessageSequence = ++messageSequence
                    var typedEvent: HtspTransportEvent.ServerMessage? = null
                    val published = withCurrentConnectionAttempt(attemptId) {
                        lastReadAtNanos = nanoTime()

                        if (
                            "seq" in msg.fields &&
                            (msg.seq == null || msg.method in ASYNCHRONOUS_SERVER_METHODS)
                        ) {
                            throw HtspIncompatibleServerException()
                        }

                        // Internal probe latch. SDK metadata workflow observes the typed event.

                        val seqNo = msg.seq
                        if (seqNo != null) {
                            val pr = pending.remove(seqNo)
                            if (pr != null) {
                                pr.onReplyCommitted?.invoke(msg)
                                attemptMonitor.queueReply(pr.def, msg)
                            } else {
                                lateReplyObservers.remove(seqNo)?.let {
                                    it.expiry.cancel()
                                    if (requestNanoTime() - it.deadlineNanos < 0L) it.observer(msg)
                                }
                            }
                            // HTSP async messages never carry seq. A reply whose waiter
                            // already timed out or was cancelled must not enter event flows.
                            return@withCurrentConnectionAttempt
                        }

                        val currentGeneration = protocolGeneration
                        val decoded = decodeHtspServerMessage(msg) { subscriptionId ->
                            currentGeneration?.subscriptionTimestampClocks?.get(subscriptionId)
                                ?: HtspTimestampClock.MICROSECONDS
                        }
                        if (currentGeneration != null) {
                            when (decoded) {
                                is HtspServerMessageDecoded -> {
                                    typedEvent = HtspTransportEvent.ServerMessage(
                                        message = decoded.message,
                                        generation = currentGeneration.token,
                                        messageSequence = currentMessageSequence,
                                    )
                                }
                                HtspServerMessageMalformedKnownMessage -> {
                                    when (val malformed = msg.fields.malformedSubscriptionMessage()) {
                                        is MalformedSubscriptionMessage.Packet ->
                                            currentGeneration.subscriptionStreams[malformed.subscriptionId]
                                                ?.recordDropped(1L)
                                        MalformedSubscriptionMessage.ControlOrEnvelope ->
                                            throw HtspIncompatibleServerException()
                                        null -> throw HtspIncompatibleServerException()
                                    }
                                }
                                HtspServerMessageUnknownMethod -> Unit
                            }
                        }
                    } != null
                    if (!published) return
                    typedEvent?.let { event ->
                        publishTypedServerEvent(attemptId, event, (framedInput.frameBytesRead() - 4).toLong())
                    }
                } catch (t: SocketTimeoutException) {
                    val now = nanoTime()
                    val silentMs = (now - lastReadAtNanos) / 1_000_000L
                    val partialFrameExpired = framedInput.frameBytesRead() > 0
                    val pendingExpired = silentMs >= pendingMaxSilentMs && pending.values.any { request ->
                        request.sentAtNanos?.let { sent -> (now - sent) / 1_000_000L >= pendingMaxSilentMs } == true
                    }
                    if (partialFrameExpired || pendingExpired) {
                        failAll(
                            failure = t,
                            attemptId = attemptId,
                            termination = HtspSubscriptionTermination.TIMEOUT,
                        )
                        return
                    }
                    continue
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!currentCoroutineContext().isActive) return
            failAll(
                failure = t,
                attemptId = attemptId,
                termination = readerTermination(t, framedInput.frameBytesRead()),
            )
        }
    }

    private fun makeDigest(password: String, challenge: ByteArray): ByteArray {
        val p = password.toByteArray(UTF_8)
        val all = ByteArray(p.size + challenge.size)
        System.arraycopy(p, 0, all, 0, p.size)
        System.arraycopy(challenge, 0, all, p.size, challenge.size)
        return MessageDigest.getInstance("SHA-1").digest(all)
    }

    private fun isConnectedUnsafe(): Boolean {
        val sj = readerJob
        val s = socket
        return sj?.isActive == true &&
                output != null &&
                s?.isConnected == true && !s.isClosed
    }

    private suspend fun disconnectInternal(
        t: Throwable,
        attemptId: Long,
        publishState: Boolean,
        termination: HtspSubscriptionTermination,
    ) {
        val retirement = attemptMonitor.withAttemptLock {
            if (connectionAttempt != attemptId) return
            terminateSubscriptionStreamsLocked(
                protocolGeneration,
                termination,
            )
            captureCurrentTransportLocked(t)
        }
        finishTransportRetirement(retirement)
        if (publishState && isCurrentConnectionAttempt(attemptId)) {
            publishConnectionState(attemptId, HtspConnectionState.Disconnected)
        }
    }

    private suspend fun finishTransportRetirement(retirement: AdmissionRetirement) {
        val callerJob = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            retirement.pending.forEach { it.def.completeExceptionally(retirement.cancellation) }
            val job = retirement.readerJob
            job?.takeIf { it !== callerJob }?.cancel()

            // Socket reads are blocking. Closing the transport is what makes a
            // cancelled reader observable; joining first can wait forever.
            closeTransportSnapshot(retirement.transport)
            job?.takeIf { it !== callerJob }?.join()
        }
    }

    private suspend fun failAll(
        failure: Throwable,
        attemptId: Long,
        termination: HtspSubscriptionTermination,
    ) {
        if (!isCurrentConnectionAttempt(attemptId)) return
        val retirement = attemptMonitor.withAttemptLock {
            if (!isCurrentConnectionAttempt(attemptId) || socket == null) return
            var typedEvent: HtspTransportEvent.ConnectionFailure? = null
                // A direct hello failure or disconnect may already own the detached transport.
                if (transportRetirement != HtspSubscriptionTermination.LOCAL_RETIREMENT) {
                    val typedFailure = (attemptMonitor.stateLocked().state as? HtspConnectionState.Error)?.failure
                        ?: if (transportRetirement == HtspSubscriptionTermination.TIMEOUT) {
                        HtspTransportFailure(HtspTransportFailureKind.CONNECTION_TIMEOUT)
                    } else {
                        typedTransportFailure(failure)
                    }
                    attemptMonitor.recordStateLocked(HtspConnectionState.Error(typedFailure))
                    if (protocolGeneration?.established == true) typedEvent = HtspTransportEvent.ConnectionFailure(
                        failure = typedFailure,
                        generation = protocolGeneration?.token,
                    )
                }
            terminateSubscriptionStreamsLocked(protocolGeneration, termination)
            captureCurrentTransportLocked(failure).also {
                typedEvent?.let { event ->
                    publishMetadataEvent(attemptId, event)
                }
            }
        }
        finishTransportRetirement(retirement)
    }

    private suspend fun publishTypedServerEvent(
        attemptId: Long,
        event: HtspTransportEvent.ServerMessage,
        frameBodyBytes: Long,
    ) {
        try {
            beforeTypedEventPublication(event)
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            throw HtspEventPublicationException()
        }
        val routed = event.message.toRoutedSubscriptionEvent()
        if (routed == null) {
            publishMetadataEvent(attemptId, event, frameBodyBytes)
        } else {
            publishSubscriptionEvent(attemptId, event.generation, routed, frameBodyBytes)
        }
    }

    private fun publishMetadataEvent(
        attemptId: Long,
        event: HtspTransportEvent,
        frameBodyBytes: Long = 0L,
    ) {
        attemptMonitor.withAttemptLock {
            if (connectionAttempt == attemptId && event.matchesCurrentGenerationLocked()) {
                afterPublicationCurrencyCheck(event)
                metadataCollectors.forEach { it.offer(event, frameBodyBytes) }
            }
        }
    }

    private fun publishSubscriptionEvent(
        attemptId: Long,
        generationToken: HtspConnectionGeneration,
        routed: RoutedSubscriptionEvent,
        frameBodyBytes: Long,
    ) {
        attemptMonitor.withAttemptLock {
                val generation = protocolGeneration
                if (
                    connectionAttempt != attemptId ||
                    generation?.attemptId != attemptId ||
                    generation.token !== generationToken
                ) {
                    return
                }
                val stream = generation.subscriptionStreams[routed.subscriptionId] ?: return
                stream.offer(routed.event, frameBodyBytes)
        }
    }

    private fun HtspTransportEvent.matchesCurrentGenerationLocked(): Boolean = when (this) {
        is HtspTransportEvent.ServerMessage -> protocolGeneration?.token === generation
        is HtspTransportEvent.ConnectionFailure ->
            generation == null || protocolGeneration?.token === generation
        else -> false
    }

    private fun ensureCurrentConnectionAttempt(attemptId: Long) {
        if (!isCurrentConnectionAttempt(attemptId)) {
            throw CancellationException("Superseded connection attempt")
        }
    }

    private fun beginConnectionAttempt(
        termination: HtspSubscriptionTermination,
    ): Long = attemptMonitor.withAttemptLock {
        admitReplacementGenerationLocked(termination)
    }

    internal fun currentConnectionAttemptId(): Long = connectionAttempt

    override fun captureGeneration(): HtspCapturedGeneration? = attemptMonitor.withAttemptLock {
        val generation = protocolGeneration ?: return@withAttemptLock null
        if (
            liveTransportAttempt != generation.attemptId ||
            connectionAttempt != generation.attemptId ||
            attemptMonitor.stateLocked().state !is HtspConnectionState.Connected
        ) {
            return@withAttemptLock null
        }
        HtspCapturedGeneration(
            token = generation.token,
            protocolVersion = negotiatedHtspVersion,
            transportKey = generation,
        )
    }

    override suspend fun dispatch(
        generation: HtspCapturedGeneration,
        request: HtspRequest<*>,
        fields: LinkedHashMap<String, Any?>,
        timeoutMs: Long,
    ): HtspWireReply {
        val startedAtNanos = System.nanoTime()
        val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
            ?: throw CancellationException("Stale HTSP connection generation")
        attemptMonitor.withAttemptLock {
            if (protocolGeneration !== serviceGeneration) {
                throw CancellationException("Stale HTSP connection generation")
            }
        }
        return try {
            val remainingMs = timeoutMs - (System.nanoTime() - startedAtNanos) / 1_000_000L
            if (remainingMs <= 0L) throw HtspCallTimeoutException()
            val reply = if (request is SubscribeRequest) {
                requestForConnectionAttemptIf(
                    expectedConnectionAttemptId = serviceGeneration.attemptId,
                    isRequestAdmitted = {
                        admitSubscribeLocked(serviceGeneration, request)
                    },
                    method = request.method,
                    fields = fields,
                    timeoutMs = remainingMs,
                    flush = true,
                    disconnectOnTimeout = false,
                    onReplyCommitted = { reply ->
                        // Only an explicit server refusal ends the stream; a reply that merely fails
                        // local decoding may belong to a subscription the server did create.
                        val refused = reply.fields["error"] is String || reply.fields["noaccess"] == 1L
                        if (refused && protocolGeneration === serviceGeneration) {
                            serviceGeneration.subscriptionStreams[request.subscriptionId]
                                ?.terminate(HtspSubscriptionTermination.SUBSCRIBE_REJECTED)
                        }
                    },
                )
            } else if (request is FileReadRequest) {
                // Only this private, observer-free path owns the decoded file payload.
                requestInternal(
                    expectedConnectionAttemptId = serviceGeneration.attemptId,
                    method = request.method,
                    fields = fields,
                    timeoutMs = remainingMs,
                    flush = true,
                    disconnectOnTimeout = false,
                )
            } else {
                requestForConnectionAttempt(
                    expectedConnectionAttemptId = serviceGeneration.attemptId,
                    method = request.method,
                    fields = fields,
                    timeoutMs = remainingMs,
                    flush = true,
                    disconnectOnTimeout = false,
                    onReplyCommitted = if (request is UnsubscribeRequest) {
                        { reply ->
                            val result = classifyHtspReply(
                                HtspWireReply(reply.fields),
                                request,
                                generation.protocolVersion ?: 0,
                            )
                            if (
                                result is HtspResult.Ok &&
                                protocolGeneration === serviceGeneration
                            ) {
                                serviceGeneration.subscriptionStreams[request.subscriptionId]
                                    ?.completeAfterAcknowledgement()
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            val payload = if (request is FileReadRequest) reply.fields["data"] as? ByteArray else null
            val replyFields = if (payload == null) reply.fields else LinkedHashMap(reply.fields).apply {
                this["data"] = HtspBinary.takeOwnership(payload)
            }
            HtspWireReply(replyFields)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HtspRequestTimeoutException) {
            throw HtspCallTimeoutException()
        }
    }

    private fun admitSubscribeLocked(
        generation: ServiceProtocolGeneration,
        request: SubscribeRequest,
    ): Boolean {
        if (protocolGeneration !== generation) return false
        val stream = generation.subscriptionStreams[request.subscriptionId]
        if (stream == null || !stream.isAccepting()) {
            throw HtspRequestAdmissionException(
                "Subscription event collection must be active before subscribe",
            )
        }
        if (request.subscriptionId in generation.subscriptionTimestampClocks) {
            throw HtspRequestAdmissionException(
                "Subscription ID already used in current connection generation",
            )
        }
        generation.subscriptionTimestampClocks[request.subscriptionId] =
            if (request.ninetyKhz == true) {
                HtspTimestampClock.NINETY_KHZ
            } else {
                HtspTimestampClock.MICROSECONDS
            }
        return true
    }

    override fun isCurrent(generation: HtspCapturedGeneration): Boolean =
        attemptMonitor.withAttemptLock {
            val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
                ?: return@withAttemptLock false
            protocolGeneration === serviceGeneration &&
                generation.token === serviceGeneration.token &&
                connectionAttempt == serviceGeneration.attemptId
        }

    override fun retire(
        generation: HtspCapturedGeneration,
        termination: HtspSubscriptionTermination,
    ) {
        val target = attemptMonitor.withAttemptLock {
            val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
                ?: return@withAttemptLock null
            if (
                protocolGeneration !== serviceGeneration ||
                generation.token !== serviceGeneration.token ||
                liveTransportAttempt != serviceGeneration.attemptId ||
                connectionAttempt != serviceGeneration.attemptId
            ) {
                return@withAttemptLock null
            }
            val target = socket
            terminateSubscriptionStreamsLocked(
                serviceGeneration,
                termination,
            )
            transportRetirement = termination
            liveTransportAttempt = null
            liveServerFacts = null
            liveConnectionIdentity = null
            challenge = null
            negotiatedHtspVersion = null
            attemptMonitor.recordStateLocked(HtspConnectionState.Disconnected)
            target
        }
        closeSocket(target)
    }

    override suspend fun <R> recapture(
        generation: HtspCapturedGeneration,
        request: HtspRequest<R>,
        result: HtspResult<R>,
    ) {
        beforeTypedRecapture(request)
        currentCoroutineContext().ensureActive()
        if (request !is HelloRequest && request !is AuthenticateRequest) return
        var unsupportedFailure: HtspUnsupportedServerVersionException? = null
        var unsupportedRetirement: AdmissionRetirement? = null
        attemptMonitor.withAttemptLock {
            val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
                ?: return
            if (
                protocolGeneration !== serviceGeneration ||
                generation.token !== serviceGeneration.token ||
                connectionAttempt != serviceGeneration.attemptId
            ) {
                return
            }
            val connectedState = attemptMonitor.stateLocked().state as? HtspConnectionState.Connected
                ?: return
            val live = attemptMonitor.stateLocked().live
                ?: return
            if (live.generation !== serviceGeneration.token || liveTransportAttempt != serviceGeneration.attemptId) {
                return
            }

            when {
                request is HelloRequest && result is HtspResult.Ok -> {
                    val hello = result.value as HelloResponse
                    if (hello.protocolVersion < MINIMUM_HTSP_PROTOCOL_VERSION) {
                        // Claim while the live-generation checks above still hold. No teardown
                        // can intervene between detecting the reply and detaching this transport.
                        val failure = HtspUnsupportedServerVersionException()
                        val typedFailure = typedTransportFailure(failure)
                        terminateSubscriptionStreamsLocked(protocolGeneration, HtspSubscriptionTermination.INTERNAL_FAILURE)
                        attemptMonitor.recordStateLocked(HtspConnectionState.Error(typedFailure))
                        val event = HtspTransportEvent.ConnectionFailure(typedFailure, serviceGeneration.token)
                        publishMetadataEvent(serviceGeneration.attemptId, event)
                        unsupportedRetirement = captureCurrentTransportLocked(failure)
                        unsupportedFailure = failure
                        return@withAttemptLock
                    }
                    val version = negotiatedHtspVersion(request.protocolVersion, hello.protocolVersion)
                    val facts = (liveServerFacts ?: HtspServerFacts()).withHelloObservations(hello)
                    challenge = hello.challenge.toByteArray()
                    negotiatedHtspVersion = version
                    liveServerFacts = facts
                    attemptMonitor.recordStateLocked(
                        connectedState.copy(protocolVersion = version),
                        live.copy(protocolVersion = version, serverFacts = facts),
                    )
                }
                request is AuthenticateRequest && result is HtspResult.Ok -> {
                    val auth = result.value as AuthenticateResponse
                    val facts = (liveServerFacts ?: HtspServerFacts())
                        .withAuthenticateObservations(auth)
                    val dvrAccess = if ((negotiatedHtspVersion ?: 0) > 25) auth.dvr else null
                    liveServerFacts = facts
                    attemptMonitor.recordStateLocked(
                        connectedState.copy(dvrAccess = dvrAccess),
                        live.copy(dvrAccess = dvrAccess, serverFacts = facts),
                    )
                }
                request is AuthenticateRequest && result === HtspResult.AccessDenied -> {
                    val facts = (liveServerFacts ?: HtspServerFacts()).withoutAuthenticateObservations()
                    liveServerFacts = facts
                    attemptMonitor.recordStateLocked(connectedState.copy(dvrAccess = null), live.copy(dvrAccess = null, serverFacts = facts))
                }
            }
        }
        unsupportedRetirement?.let { finishTransportRetirement(it) }
        unsupportedFailure?.let { throw it }
    }

    internal open fun serverFactsForLiveConnectionAttempt(
        expectedConnectionAttemptId: Long,
    ): HtspServerFacts? = attemptMonitor.withAttemptLock {
        if (
            connectionAttempt != expectedConnectionAttemptId ||
            liveTransportAttempt != expectedConnectionAttemptId
        ) {
            return@withAttemptLock null
        }
        liveServerFacts
    }

    internal open fun liveHtspVersionForConnectionAttempt(
        expectedConnectionAttemptId: Long,
    ): Int? = attemptMonitor.withAttemptLock {
        if (
            connectionAttempt != expectedConnectionAttemptId ||
            liveTransportAttempt != expectedConnectionAttemptId
        ) {
            throw CancellationException("Stale HTSP connection attempt")
        }
        negotiatedHtspVersion
    }

    private fun isCurrentConnectionAttempt(attemptId: Long): Boolean = connectionAttempt == attemptId

    private fun publishConnectionState(
        attemptId: Long,
        state: HtspConnectionState,
    ): Boolean = withCurrentConnectionAttempt(attemptId) {
        attemptMonitor.recordStateLocked(state)
    } != null

    private fun publishConnectedState(
        attemptId: Long,
        state: HtspConnectionState.Connected,
        serverFacts: HtspServerFacts,
        connectionIdentity: HtspConnectionIdentity,
    ): HtspLiveConnection? = attemptMonitor.withAttemptLock {
        if (connectionAttempt != attemptId || liveTransportAttempt != attemptId) {
            return@withAttemptLock null
        }
        liveServerFacts = serverFacts
        liveConnectionIdentity = connectionIdentity
        val generation = checkNotNull(protocolGeneration)
        generation.established = true
        val live = HtspLiveConnection(
            generation = generation.token,
            protocolVersion = state.protocolVersion,
            dvrAccess = state.dvrAccess,
            serverFacts = serverFacts,
        )
        attemptMonitor.recordStateLocked(state, live)
        live
    }

    private fun <T> withCurrentConnectionAttempt(
        attemptId: Long,
        block: () -> T,
    ): T? = attemptMonitor.withAttemptLock {
        if (attemptId == 0L) return@withAttemptLock block()
        if (connectionAttempt != attemptId) return@withAttemptLock null
        block()
    }

    private fun installTransport(
        attemptId: Long,
        transportSocket: Socket,
        transportInput: InputStream,
        transportOutput: OutputStream,
    ) = attemptMonitor.withAttemptLock {
        ensureCurrentConnectionAttempt(attemptId)
        socket = transportSocket
        connectingSocket = null
        input = transportInput
        output = transportOutput
        liveTransportAttempt = attemptId
        transportRetirement = null
        if (protocolGeneration?.attemptId != attemptId) {
            protocolGeneration = ServiceProtocolGeneration(attemptId)
        }
        liveServerFacts = null
        liveConnectionIdentity = null
    }

    private fun markTransportGone(
        target: Socket?,
        termination: HtspSubscriptionTermination,
    ) {
        attemptMonitor.withAttemptLock {
            if (socket === target && liveTransportAttempt != null) {
                terminateSubscriptionStreamsLocked(
                    protocolGeneration,
                    termination,
                )
                transportRetirement = termination
                liveTransportAttempt = null
                liveServerFacts = null
                liveConnectionIdentity = null
                val state = when (termination) {
                    HtspSubscriptionTermination.LOCAL_RETIREMENT -> HtspConnectionState.Disconnected
                    HtspSubscriptionTermination.TIMEOUT -> HtspConnectionState.Error(
                        HtspTransportFailure(HtspTransportFailureKind.CONNECTION_TIMEOUT),
                    )
                    else -> HtspConnectionState.Error(
                        HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
                    )
                }
                attemptMonitor.recordStateLocked(state)
            }
        }
        closeSocket(target)
    }

    private fun closeSocket(target: Socket?) {
        runCatching { target?.close() }
    }

    private suspend fun retireAdmissionTransport(attemptId: Long) {
        val retirements = attemptMonitor.withAttemptLock {
            // An older admission may still be suspended before reclamation. Its detached
            // socket must not strand a reader when a replacement or close overtakes it.
            admissionRetirements.keys.filter { it <= attemptId }.mapNotNull { admissionRetirements.remove(it) }
        }
        retirements.forEach { finishTransportRetirement(it) }
    }

    private fun canReuseLiveConnectionLocked(requestedIdentity: HtspConnectionIdentity): Boolean {
        val generation = protocolGeneration ?: return false
        return liveConnectionIdentity?.matches(requestedIdentity) == true &&
            liveTransportAttempt == generation.attemptId &&
            connectionAttempt == generation.attemptId &&
            attemptMonitor.stateLocked().state is HtspConnectionState.Connected &&
            isConnectedUnsafe()
    }

    private fun beginTeardownAttempt(expectedGeneration: HtspConnectionGeneration?): Long =
        attemptMonitor.withAttemptLock {
            expectedGeneration?.let(::requireCurrentGenerationLocked)
            connectionAttempt
        }

    private fun requireCurrentGenerationLocked(expectedGeneration: HtspConnectionGeneration) {
        if (protocolGeneration?.token !== expectedGeneration) {
            throw CancellationException("Stale HTSP connection generation")
        }
    }

    private fun admitReplacementGenerationLocked(
        termination: HtspSubscriptionTermination,
    ): Long {
        val attemptId = ++connectionAttempt
        terminateSubscriptionStreamsLocked(protocolGeneration, termination)
        val cancellation = CancellationException("Superseded connection attempt")
        val retirement = captureCurrentTransportLocked(cancellation)
        attemptMonitor.recordStateLocked(HtspConnectionState.Disconnected)
        protocolGeneration = ServiceProtocolGeneration(attemptId)
        admissionRetirements[attemptId] = retirement
        return attemptId
    }

    private fun terminateSubscriptionStreamsLocked(
        generation: ServiceProtocolGeneration?,
        termination: HtspSubscriptionTermination,
    ) {
        generation?.subscriptionStreams?.values?.forEach { stream ->
            stream.terminate(termination)
        }
    }

    private fun captureCurrentTransportLocked(
        cancellation: Throwable,
    ): AdmissionRetirement {
        val retirement = AdmissionRetirement(
            cancellation = cancellation,
            pending = pending.values.toList(),
            readerJob = readerJob,
            transport = detachCurrentTransportLocked(),
        )
        pending.clear()
        lateReplyObservers.values.forEach { it.expiry.cancel() }
        lateReplyObservers.clear()
        readerJob = null
        return retirement
    }

    private val admissionRetirements = mutableMapOf<Long, AdmissionRetirement>()

    private fun detachCurrentTransportLocked(): TransportSnapshot {
        val snapshot = TransportSnapshot(connectingSocket, socket, input, output)
        connectingSocket = null
        socket = null
        input = null
        output = null
        liveTransportAttempt = null
        transportRetirement = null
        liveServerFacts = null
        liveConnectionIdentity = null
        challenge = null
        negotiatedHtspVersion = null
        attemptMonitor.recordStateLocked(
            attemptMonitor.stateLocked().state.takeUnless { it is HtspConnectionState.Connected } ?: HtspConnectionState.Disconnected,
        )
        return snapshot
    }

    private fun closeTransportSnapshot(snapshot: TransportSnapshot) {
        closeSocket(snapshot.connectingSocket)
        closeSocket(snapshot.socket)
        runCatching { snapshot.input?.close() }
        runCatching { snapshot.output?.close() }
    }

    private data class TransportSnapshot(
        val connectingSocket: Socket?,
        val socket: Socket?,
        val input: InputStream?,
        val output: OutputStream?,
    )

    private data class AdmissionRetirement(
        val cancellation: Throwable,
        val pending: List<PendingReq>,
        val readerJob: Job?,
        val transport: TransportSnapshot,
    )

    private data class RequestAdmission(
        val sequence: Int,
        val response: CompletableDeferred<HtspWireMessage>,
        val socket: Socket?,
        val output: OutputStream,
    )

    private class ServiceProtocolGeneration(
        val attemptId: Long,
        val token: HtspConnectionGeneration = HtspConnectionGeneration(),
    ) {
        var established = false
        val collectedSubscriptionIds = mutableSetOf<Long>()
        val subscriptionStreams = mutableMapOf<Long, HtspSubscriptionEventBuffer>()
        val subscriptionTimestampClocks = mutableMapOf<Long, HtspTimestampClock>()
    }

    private class HtspConnectionIdentity(
        private val host: String,
        private val port: Int,
        private val username: String?,
        private val password: String?,
    ) {
        fun matches(other: HtspConnectionIdentity): Boolean =
            host == other.host &&
                port == other.port &&
                username == other.username &&
                password == other.password
    }
}

private fun readerTermination(
    failure: Throwable,
    frameBytesRead: Int,
): HtspSubscriptionTermination = when (failure) {
    is HtspEventPublicationException -> HtspSubscriptionTermination.PUBLICATION_FAILURE
    is HtspIncompatibleServerException -> HtspSubscriptionTermination.MALFORMED_MESSAGE
    is HtspFramingException -> HtspSubscriptionTermination.FRAMING_FAILURE
    is EOFException -> if (frameBytesRead == 0) {
        HtspSubscriptionTermination.REMOTE_EOF
    } else {
        HtspSubscriptionTermination.FRAMING_FAILURE
    }
    is IOException -> HtspSubscriptionTermination.IO_FAILURE
    else -> HtspSubscriptionTermination.INTERNAL_FAILURE
}

internal typealias HtspService = `HtspService-internal`

private fun negotiatedHtspVersion(requested: Long, server: Long): Int? =
    min(requested, server).takeIf { version -> version <= Int.MAX_VALUE.toLong() }?.toInt()

private fun HtspServerFacts.withHelloObservations(hello: HelloResponse): HtspServerFacts = copy(
    serverName = hello.serverName,
    serverVersion = hello.serverVersion,
    webRoot = hello.webRoot,
    language = hello.language,
    serverCapabilities = hello.serverCapabilities,
    apiVersion = hello.apiVersion,
)

private fun HtspServerFacts.withAuthenticateObservations(
    auth: AuthenticateResponse,
): HtspServerFacts = copy(
    admin = auth.admin,
    streaming = auth.streaming,
    dvr = auth.dvr,
    failedDvr = auth.failedDvr,
    anonymous = auth.anonymous,
    limitAll = auth.limitAll,
    limitDvr = auth.limitDvr,
    limitStreaming = auth.limitStreaming,
    uiLevel = auth.uiLevel,
    uiLanguage = auth.uiLanguage,
)

private fun HtspServerFacts.withoutAuthenticateObservations(): HtspServerFacts = copy(
    admin = null,
    streaming = null,
    dvr = null,
    failedDvr = null,
    anonymous = null,
    limitAll = null,
    limitDvr = null,
    limitStreaming = null,
    uiLevel = null,
    uiLanguage = null,
)
