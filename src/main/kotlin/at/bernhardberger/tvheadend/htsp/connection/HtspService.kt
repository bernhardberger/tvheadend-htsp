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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

@JvmSynthetic
internal const val DVR_PLAY_COUNT_KEEP: Int = Int.MAX_VALUE - 1
private const val HTSP_CHALLENGE_SIZE_BYTES: Int = 32
internal const val METADATA_EVENT_BUFFER_CAPACITY: Int = 1024
internal const val SUBSCRIPTION_EVENT_BUFFER_CAPACITY: Int = 8192

internal class HtspTransportInputStream(
    private val delegate: InputStream,
    private val logger: HtspLogger,
) : InputStream() {
    private var currentFrameBytesRead = 0
    private var currentFrameTimeouts = 0

    fun beginFrame() {
        currentFrameBytesRead = 0
        currentFrameTimeouts = 0
    }

    override fun read(): Int = retryMidFrameTimeout {
        delegate.read().also { value ->
            if (value >= 0) currentFrameBytesRead++
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = retryMidFrameTimeout {
        delegate.read(buffer, offset, length).also { count ->
            if (count > 0) currentFrameBytesRead += count
        }
    }

    override fun close() {
        delegate.close()
    }

    private inline fun retryMidFrameTimeout(read: () -> Int): Int {
        while (true) {
            try {
                return read()
            } catch (timeout: SocketTimeoutException) {
                if (currentFrameBytesRead == 0) throw timeout
                currentFrameTimeouts++
                if (currentFrameTimeouts == 1 || currentFrameTimeouts % 50 == 0) {
                    logger.log(
                        HtspLogLevel.WARNING,
                        "SO_TIMEOUT after partial current HTSP frame; continuing exact read",
                        timeout,
                    )
                }
            }
        }
    }
}

internal class `HtspRequestTimeoutException-internal`(
    val requestMethod: String,
    val timeoutMs: Long,
    cause: Throwable? = null,
) : IOException("HTSP request timed out", cause)

internal typealias HtspRequestTimeoutException = `HtspRequestTimeoutException-internal`

/** Connection lifecycle state exposed as finite typed snapshots. */
public sealed class HtspConnectionState {
    /** No transport is currently connected or connecting. */
    public data object Disconnected : HtspConnectionState()
    /** A connection attempt is in progress for the recorded host and port. */
    public data class Connecting(val host: String, val port: Int) : HtspConnectionState()
    /**
     * @param dvrAccess HTSP `ACCESS_HTSP_RECORDER` from authenticate (version ≥ 26).
     * null when unauthenticated or the field was not returned.
     */
    public data class Connected(
        val host: String,
        val port: Int,
        val htspVersion: Int?,
        val dvrAccess: Boolean? = null,
    ) : HtspConnectionState()
    /** A connection attempt or active transport failed. */
    public data class Error(val throwable: Throwable) : HtspConnectionState()
}

internal open class `HtspService-internal`(
    ioDispatcher: CoroutineDispatcher,
    private val clientIdentity: HtspClientIdentity = HtspClientIdentity.Default,
    private val logger: HtspLogger = HtspLogger.None,
    private val socketFactory: () -> Socket = ::Socket,
    private val afterConnectionAdmission: suspend () -> Unit = {},
    private val afterTransportInstallation: suspend () -> Unit = {},
    private val afterTeardownAdmission: suspend () -> Unit = {},
    private val beforeTypedRecapture: suspend (HtspRequest<*>) -> Unit = {},
    private val beforeTypedEventPublication: (HtspTransportEvent.ServerMessage) -> Unit = {},
    private val metadataEventBufferCapacity: Int = METADATA_EVENT_BUFFER_CAPACITY,
    private val subscriptionEventBufferCapacity: Int = SUBSCRIPTION_EVENT_BUFFER_CAPACITY,
) : HtspRequestTransport, HtspConnection {
    private val _state = MutableStateFlow<HtspConnectionState>(HtspConnectionState.Disconnected)
    override val connectionState: StateFlow<HtspConnectionState> = _state.asStateFlow()

    private val _liveConnection = MutableStateFlow<HtspLiveConnection?>(null)
    override val liveConnection: StateFlow<HtspLiveConnection?> = _liveConnection

    private val _events = MutableSharedFlow<HtspTransportEvent>(
        replay = 0,
        extraBufferCapacity = metadataEventBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: SharedFlow<HtspTransportEvent> = _events

    open fun currentConnectionState(): HtspConnectionState = connectionState.value

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + ioDispatcher)
    private val lifecycle = TerminalLifecycleGate("HTSP service is closed")

    private val pending = ConcurrentHashMap<Int, PendingReq>()
    private val lateReplyObservers = ConcurrentHashMap<Int, (HtspWireMessage) -> Unit>()

    private data class PendingReq(
        val def: CompletableDeferred<HtspWireMessage>,
        val startedAtMs: Long,
        val onReplyCommitted: ((HtspWireMessage) -> Unit)?,
    )

    private val seq = AtomicInteger(1)

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    private val connectionAttemptLock = Any()
    @Volatile
    private var connectionAttempt = 0L

    @Volatile
    private var liveTransportAttempt: Long? = null

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

    @Volatile
    private var initialSyncDef: CompletableDeferred<Unit>? = null

    // ---- health ----
    @Volatile
    private var lastReadAtMs: Long = 0L

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
        htspVersion: Int = 43,

        connectTimeoutMs: Int = 10_000,
        responseTimeoutMs: Long = 5_000,

        soTimeoutMs: Int = 60_000,

        socketBufferBytes: Int = 64 * 1024,

        forceReconnect: Boolean = false
    ) {
        val requestedIdentity = HtspConnectionIdentity(host, port, username, password)
        val attemptId = lifecycle.admit {
            beginConnectionAttemptUnlessReusable(requestedIdentity, forceReconnect)
        } ?: return
        try {
            afterConnectionAdmission()
            connectMutex.withLock {
                ensureCurrentConnectionAttempt(attemptId)
                retireAdmissionTransport(attemptId)
                ensureCurrentConnectionAttempt(attemptId)
                publishConnectionState(
                    attemptId,
                    HtspConnectionState.Connecting(host, port),
                )

                try {
                    val s = lifecycle.admit {
                        ensureCurrentConnectionAttempt(attemptId)
                        socketFactory().also { connectingSocket = it }
                    }
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.soTimeout = soTimeoutMs
                    s.connect(InetSocketAddress(host, port), connectTimeoutMs)

                    val inp = BufferedInputStream(s.getInputStream(), socketBufferBytes)
                    val out = BufferedOutputStream(s.getOutputStream(), socketBufferBytes)

                    installTransport(attemptId, s, inp, out)
                    afterTransportInstallation()
                    lastReadAtMs = System.currentTimeMillis()

                    val reader = synchronized(connectionAttemptLock) {
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
                        htspVersion = htspVersion.toLong(),
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
                    val negotiatedVersion = checkNotNull(
                        negotiatedHtspVersion(
                            requested = helloRequest.htspVersion,
                            server = hello.htspVersion,
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
                        HtspResult.AccessDenied,
                        HtspResult.ConnectionLimit,
                        -> throw IllegalStateException(
                            if (withCredentials) {
                                "HTSP authentication failed (noaccess=1)"
                            } else {
                                "HTSP server requires credentials (noaccess=1)"
                            },
                        )
                        is HtspFailure -> throw IllegalStateException("HTSP authentication failed")
                    }
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

                    if (
                        !publishConnectedState(
                            attemptId = attemptId,
                            state = HtspConnectionState.Connected(
                                host = host,
                                port = port,
                                htspVersion = negotiatedHtspVersion,
                                dvrAccess = dvrAccess,
                            ),
                            serverFacts = serverFacts,
                            connectionIdentity = requestedIdentity,
                        )
                    ) {
                        throw CancellationException("Superseded connection attempt")
                    }

                } catch (cancelled: CancellationException) {
                    disconnectInternal(
                        t = cancelled,
                        attemptId = attemptId,
                        publishState = true,
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
                        )
                        throw superseded
                    }
                    publishConnectionState(attemptId, HtspConnectionState.Error(t))
                    disconnectInternal(
                        t = t,
                        attemptId = attemptId,
                        publishState = true,
                    )
                    throw t
                }
            }
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
        connect(
            host = endpoint.host,
            port = endpoint.port,
            username = endpoint.username,
            password = endpoint.password,
            htspVersion = options.requestedProtocolVersion,
            connectTimeoutMs = options.connectTimeoutMs,
            responseTimeoutMs = options.responseTimeoutMs,
            soTimeoutMs = options.socketReadTimeoutMs,
            socketBufferBytes = options.socketBufferBytes,
            forceReconnect = options.forceReconnect,
        )
        val connection = liveConnection.value
            ?: return HtspConnectOutcome.Failed(
                HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
            )
        HtspConnectOutcome.Connected(connection)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        HtspConnectOutcome.Failed(typedTransportFailure(error))
    }

    internal suspend fun enableAsyncMetadataAndWaitInitialSync(timeoutMs: Long = 30_000) {
        checkOpen()
        val def = CompletableDeferred<Unit>()
        val metadataSocket = synchronized(connectionAttemptLock) {
            if (!isConnectedUnsafe()) throw IllegalStateException("Not connected")
            initialSyncDef = def
            socket
        }

        try {
            val completed = withTimeoutOrNull(timeoutMs) {
                val reply = request(
                    method = "enableAsyncMetadata",
                    fields = emptyMap(),
                    timeoutMs = timeoutMs,
                    flush = true,
                    disconnectOnTimeout = true
                )
                if (reply.int("noaccess") == 1 || reply.fields.containsKey("error")) {
                    throw MetadataPermissionDeniedException()
                }
                def.await()
                true
            }
            if (completed != true) {
                markTransportGone(metadataSocket)
                throw SocketTimeoutException(
                    "HTSP initial metadata sync timed out after ${timeoutMs}ms"
                )
            }
        } finally {
            synchronized(connectionAttemptLock) {
                if (initialSyncDef === def) initialSyncDef = null
            }
        }
    }

    override suspend fun <R> execute(
        request: HtspRequest<R>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspResult<R> = typedRequestCaller.call(request, timeoutMs, expectedGeneration)

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
            val stream = synchronized(connectionAttemptLock) {
                val generation = protocolGeneration
                    ?: error("No live HTSP connection generation")
                if (expectedGeneration != null && generation.token !== expectedGeneration) {
                    throw CancellationException("Stale HTSP connection generation")
                }
                val live = _liveConnection.value
                check(
                    live?.generation === generation.token &&
                        liveTransportAttempt == generation.attemptId &&
                        connectionAttempt == generation.attemptId &&
                        _state.value is HtspConnectionState.Connected
                ) {
                    "No live HTSP connection generation"
                }
                check(subscriptionId !in generation.subscriptionStreams) {
                    "HTSP subscription stream already collected in this generation"
                }
                HtspSubscriptionEventBuffer(
                    capacity = subscriptionEventBufferCapacity,
                    collectorJob = collectorJob,
                ).also { buffer ->
                    generation.subscriptionStreams[subscriptionId] = buffer
                }
            }

            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    var complete = false
                    val event = synchronized(connectionAttemptLock) {
                        stream.poll().also { next ->
                            if (next == null) complete = stream.isComplete()
                        }
                    }
                    when {
                        event != null -> emit(event)
                        complete -> return@flow
                        else -> stream.eventsAvailable.receive()
                    }
                }
            } finally {
                synchronized(connectionAttemptLock) {
                    stream.abandon()
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
        synchronized(connectionAttemptLock) {
            protocolGeneration?.token === generation
        }

    override fun <T> commitIfCurrent(
        generation: HtspConnectionGeneration,
        block: () -> T,
    ): T? = synchronized(connectionAttemptLock) {
        if (protocolGeneration?.token !== generation) return@synchronized null
        block()
    }

    override fun <T> commitIfLive(
        generation: HtspConnectionGeneration,
        block: (HtspLiveConnection) -> T,
    ): T? = synchronized(connectionAttemptLock) {
        val live = _liveConnection.value ?: return@synchronized null
        val current = protocolGeneration ?: return@synchronized null
        if (
            current.token !== generation ||
            live.generation !== generation ||
            liveTransportAttempt != current.attemptId ||
            connectionAttempt != current.attemptId
        ) {
            return@synchronized null
        }
        block(live)
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
    ): HtspWireMessage = requestInternal(
        expectedConnectionAttemptId = expectedConnectionAttemptId,
        method = method,
        fields = fields,
        timeoutMs = timeoutMs,
        flush = flush,
        disconnectOnTimeout = disconnectOnTimeout,
        isRequestAdmitted = isRequestAdmitted,
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
        val admission = lifecycle.admit {
            synchronized(connectionAttemptLock) {
                val transport = if (expectedConnectionAttemptId == null) {
                    socket to output
                } else {
                    if (
                        connectionAttempt != expectedConnectionAttemptId ||
                        liveTransportAttempt != expectedConnectionAttemptId ||
                        isRequestAdmitted?.invoke() == false
                    ) {
                        throw CancellationException("Stale HTSP connection attempt")
                    }
                    socket to output
                }
                val requestOutput = transport.second ?: throw IllegalStateException("Not connected")
                val requestSequence = seq.getAndIncrement()
                val response = CompletableDeferred<HtspWireMessage>()
                pending[requestSequence] = PendingReq(
                    def = response,
                    startedAtMs = System.currentTimeMillis(),
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

        try {
            val msgFields = LinkedHashMap<String, Any?>(fields.size + 1).apply {
                this["seq"] = s
                putAll(fields)
                this["seq"] = s
            }

            writeMutex.withLock {
                HtspCodec.writeMessage(admission.output, method, msgFields)
                if (flush) admission.output.flush()
            }
        } catch (t: Throwable) {
            pending.remove(s)
            def.completeExceptionally(t)
            throw t
        }

        return try {
            val response = withTimeoutOrNull(timeoutMs) { def.await() }
            if (response == null) {
                preserveLateReplyObserver(s)

                if (disconnectOnTimeout) {
                    markTransportGone(admission.socket)
                    throw SocketTimeoutException(
                        "HTSP request '$method' timed out after ${timeoutMs}ms"
                    )
                }

                throw HtspRequestTimeoutException(method, timeoutMs)
            }
            response
        } catch (t: Throwable) {
            preserveLateReplyObserver(s)
            throw t
        }
    }

    private fun preserveLateReplyObserver(requestSequence: Int) {
        synchronized(connectionAttemptLock) {
            val request = pending.remove(requestSequence) ?: return
            request.onReplyCommitted?.let { observer ->
                lateReplyObservers[requestSequence] = observer
            }
        }
    }

    override suspend fun disconnect(
        expectedGeneration: HtspConnectionGeneration?,
    ) = withContext(NonCancellable) {
        val attemptId = try {
            lifecycle.admit { beginTeardownAttempt(expectedGeneration) }
        } catch (closed: IllegalStateException) {
            if (expectedGeneration == null) throw closed
            throw CancellationException("Stale HTSP connection generation")
        }
        afterTeardownAdmission()
        connectMutex.withLock {
            ensureCurrentConnectionAttempt(attemptId)
            disconnectInternal(
                t = CancellationException("Disconnected"),
                attemptId = attemptId,
                publishState = true,
            )
        }
    }

    override suspend fun close(expectedGeneration: HtspConnectionGeneration?) {
        val attemptId = if (expectedGeneration == null) {
            beginClose()
        } else {
            try {
                lifecycle.admit {
                    requireCurrentGeneration(expectedGeneration)
                    beginClose()
                }
            } catch (_: IllegalStateException) {
                throw CancellationException("Stale HTSP connection generation")
            }
        } ?: return
        finishClose(attemptId)
    }

    internal fun beginClose(): Long? = lifecycle.close {
        beginConnectionAttempt(HtspSubscriptionTermination.TRANSPORT_CLOSED)
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
        val pendingMaxSilentMs = responseTimeoutMs * 2
        val framedInput = HtspTransportInputStream(transportInput, logger)
        var messageSequence = 0L
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    framedInput.beginFrame()
                    val msg = HtspCodec.readMessage(framedInput)
                    val currentMessageSequence = ++messageSequence
                    var typedEvent: HtspTransportEvent.ServerMessage? = null
                    val published = withCurrentConnectionAttempt(attemptId) {
                        lastReadAtMs = System.currentTimeMillis()

                        if (
                            "seq" in msg.fields &&
                            msg.method in ASYNCHRONOUS_SERVER_METHODS
                        ) {
                            throw HtspIncompatibleServerException()
                        }

                        // Internal probe latch. SDK metadata workflow observes the typed event.
                        if (msg.seq == null && msg.method == "initialSyncCompleted") {
                            initialSyncDef?.complete(Unit)
                        }

                        val seqNo = msg.seq
                        if (seqNo != null) {
                            val pr = pending.remove(seqNo)
                            if (pr != null) {
                                pr.onReplyCommitted?.invoke(msg)
                                pr.def.complete(msg)
                            } else {
                                lateReplyObservers.remove(seqNo)?.invoke(msg)
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
                    typedEvent?.let { event -> publishTypedServerEvent(attemptId, event) }
                } catch (t: SocketTimeoutException) {
                    val now = System.currentTimeMillis()
                    if (pending.isNotEmpty()) {
                        val silent = now - lastReadAtMs
                        if (silent >= pendingMaxSilentMs) {
                            failAll(
                                SocketTimeoutException("HTSP no incoming data for ${silent}ms with ${pending.size} pending requests"),
                                attemptId,
                            )
                            return
                        }
                    }
                    continue
                }
            }
        } catch (t: NoSuchElementException) {
            failAll(
                EOFException("Broken/EOF HTSP stream").apply { initCause(t) },
                attemptId,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!currentCoroutineContext().isActive) return
            failAll(t, attemptId)
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
    ) {
        val callerJob = currentCoroutineContext()[Job]
        val retirement = synchronized(connectionAttemptLock) {
            if (connectionAttempt != attemptId) return
            terminateSubscriptionStreamsLocked(
                protocolGeneration,
                HtspSubscriptionTermination.TRANSPORT_CLOSED,
            )
            captureCurrentTransportLocked(t)
        }
        withContext(NonCancellable) {
            retirement.pending.forEach { it.def.completeExceptionally(t) }
            retirement.initialSync?.completeExceptionally(t)
            val job = retirement.readerJob
            job?.takeIf { it !== callerJob }?.cancel()

            // Socket reads are blocking. Closing the transport is what makes a
            // cancelled reader observable; joining first can wait forever.
            closeTransportSnapshot(retirement.transport)
            job?.takeIf { it !== callerJob }?.join()
            if (publishState && isCurrentConnectionAttempt(attemptId)) {
                publishConnectionState(attemptId, HtspConnectionState.Disconnected)
            }
        }
    }

    private suspend fun failAll(t: Throwable, attemptId: Long) {
        if (!isCurrentConnectionAttempt(attemptId)) return
        var typedEvent: HtspTransportEvent.ConnectionFailure? = null
        val published = connectMutex.withLock {
            if (!isCurrentConnectionAttempt(attemptId)) return@withLock false
            withCurrentConnectionAttempt(attemptId) {
                _state.value = HtspConnectionState.Error(t)
                typedEvent = HtspTransportEvent.ConnectionFailure(
                    failure = typedTransportFailure(t),
                    generation = protocolGeneration?.token,
                )
            } ?: return@withLock false
            disconnectInternal(
                t = t,
                attemptId = attemptId,
                publishState = true,
            )
            true
        }
        if (!published) return
        typedEvent?.let { publishMetadataEvent(attemptId, it) }
    }

    private suspend fun publishTypedServerEvent(
        attemptId: Long,
        event: HtspTransportEvent.ServerMessage,
    ) {
        beforeTypedEventPublication(event)
        val routed = event.message.toRoutedSubscriptionEvent()
        if (routed == null) {
            publishMetadataEvent(attemptId, event)
        } else {
            publishSubscriptionEvent(attemptId, event.generation, routed)
        }
    }

    private suspend fun publishMetadataEvent(
        attemptId: Long,
        event: HtspTransportEvent,
    ) {
        val committed = synchronized(connectionAttemptLock) {
            connectionAttempt == attemptId && event.matchesCurrentGenerationLocked()
        }
        if (committed) _events.emit(event)
    }

    private suspend fun publishSubscriptionEvent(
        attemptId: Long,
        generationToken: HtspConnectionGeneration,
        routed: RoutedSubscriptionEvent,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = synchronized(connectionAttemptLock) {
                val generation = protocolGeneration
                if (
                    connectionAttempt != attemptId ||
                    generation?.attemptId != attemptId ||
                    generation.token !== generationToken
                ) {
                    return
                }
                val stream = generation.subscriptionStreams[routed.subscriptionId] ?: return
                stream.offer(routed.event)
            }
            when (result) {
                HtspSubscriptionEventBuffer.OfferResult.ACCEPTED,
                HtspSubscriptionEventBuffer.OfferResult.IGNORED,
                -> return
                HtspSubscriptionEventBuffer.OfferResult.WAIT_FOR_SPACE -> {
                    val stream = synchronized(connectionAttemptLock) {
                        protocolGeneration
                            ?.takeIf { generation ->
                                generation.attemptId == attemptId &&
                                    generation.token === generationToken
                            }
                            ?.subscriptionStreams
                            ?.get(routed.subscriptionId)
                    } ?: return
                    stream.spaceAvailable.receive()
                }
            }
        }
    }

    private fun HtspTransportEvent.matchesCurrentGenerationLocked(): Boolean = when (this) {
        is HtspTransportEvent.ServerMessage -> protocolGeneration?.token === generation
        is HtspTransportEvent.ConnectionFailure ->
            generation == null || protocolGeneration?.token === generation
    }

    private fun ensureCurrentConnectionAttempt(attemptId: Long) {
        if (!isCurrentConnectionAttempt(attemptId)) {
            throw CancellationException("Superseded connection attempt")
        }
    }

    private fun beginConnectionAttempt(
        termination: HtspSubscriptionTermination,
    ): Long = synchronized(connectionAttemptLock) {
        admitReplacementGenerationLocked(termination)
    }

    internal fun currentConnectionAttemptId(): Long = connectionAttempt

    override fun captureGeneration(): HtspCapturedGeneration? = synchronized(connectionAttemptLock) {
        val generation = protocolGeneration ?: return@synchronized null
        if (
            liveTransportAttempt != generation.attemptId ||
            connectionAttempt != generation.attemptId ||
            _state.value !is HtspConnectionState.Connected
        ) {
            return@synchronized null
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
        val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
            ?: throw CancellationException("Stale HTSP connection generation")
        synchronized(connectionAttemptLock) {
            if (protocolGeneration !== serviceGeneration) {
                throw CancellationException("Stale HTSP connection generation")
            }
        }
        return try {
            HtspWireReply(
                if (request is SubscribeRequest) {
                    requestForConnectionAttemptIf(
                        expectedConnectionAttemptId = serviceGeneration.attemptId,
                        isRequestAdmitted = {
                            admitSubscribeLocked(serviceGeneration, request)
                        },
                        method = request.method,
                        fields = fields,
                        timeoutMs = timeoutMs,
                        flush = true,
                        disconnectOnTimeout = false,
                    )
                } else {
                    requestForConnectionAttempt(
                        expectedConnectionAttemptId = serviceGeneration.attemptId,
                        method = request.method,
                        fields = fields,
                        timeoutMs = timeoutMs,
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
                }.fields,
            )
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
            if (request.ninetyKhz != null && request.ninetyKhz != 0L) {
                HtspTimestampClock.NINETY_KHZ
            } else {
                HtspTimestampClock.MICROSECONDS
            }
        return true
    }

    override fun isCurrent(generation: HtspCapturedGeneration): Boolean =
        synchronized(connectionAttemptLock) {
            val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
                ?: return@synchronized false
            protocolGeneration === serviceGeneration &&
                generation.token === serviceGeneration.token &&
                liveTransportAttempt == serviceGeneration.attemptId &&
                connectionAttempt == serviceGeneration.attemptId &&
                _state.value is HtspConnectionState.Connected
        }

    override fun retire(generation: HtspCapturedGeneration) {
        val target = synchronized(connectionAttemptLock) {
            val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
                ?: return@synchronized null
            if (
                protocolGeneration !== serviceGeneration ||
                generation.token !== serviceGeneration.token ||
                liveTransportAttempt != serviceGeneration.attemptId ||
                connectionAttempt != serviceGeneration.attemptId
            ) {
                return@synchronized null
            }
            val target = socket
            terminateSubscriptionStreamsLocked(
                serviceGeneration,
                HtspSubscriptionTermination.TRANSPORT_CLOSED,
            )
            liveTransportAttempt = null
            liveServerFacts = null
            liveConnectionIdentity = null
            challenge = null
            negotiatedHtspVersion = null
            _liveConnection.value = null
            _state.value = HtspConnectionState.Disconnected
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
        synchronized(connectionAttemptLock) {
            val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
                ?: throw CancellationException("Stale HTSP connection generation")
            val connectedState = _state.value as? HtspConnectionState.Connected
                ?: throw CancellationException("Stale HTSP connection generation")
            val live = _liveConnection.value
                ?: throw CancellationException("Stale HTSP connection generation")
            if (
                protocolGeneration !== serviceGeneration ||
                generation.token !== serviceGeneration.token ||
                live.generation !== serviceGeneration.token ||
                liveTransportAttempt != serviceGeneration.attemptId ||
                connectionAttempt != serviceGeneration.attemptId
            ) {
                throw CancellationException("Stale HTSP connection generation")
            }

            when {
                request is HelloRequest && result is HtspResult.Ok -> {
                    val hello = result.value as HelloResponse
                    val version = negotiatedHtspVersion(request.htspVersion, hello.htspVersion)
                    val facts = (liveServerFacts ?: HtspServerFacts()).withHelloObservations(hello)
                    challenge = hello.challenge.toByteArray()
                    negotiatedHtspVersion = version
                    liveServerFacts = facts
                    _liveConnection.value = live.copy(
                        protocolVersion = version,
                        serverFacts = facts,
                    )
                    _state.value = connectedState.copy(htspVersion = version)
                }
                request is AuthenticateRequest && result is HtspResult.Ok -> {
                    val auth = result.value as AuthenticateResponse
                    val facts = (liveServerFacts ?: HtspServerFacts())
                        .withAuthenticateObservations(auth)
                    val dvrAccess = if ((negotiatedHtspVersion ?: 0) > 25) auth.dvr else null
                    liveServerFacts = facts
                    _liveConnection.value = live.copy(
                        dvrAccess = dvrAccess,
                        serverFacts = facts,
                    )
                    _state.value = connectedState.copy(dvrAccess = dvrAccess)
                }
                request is AuthenticateRequest && result === HtspResult.AccessDenied -> {
                    val facts = (liveServerFacts ?: HtspServerFacts()).withoutAuthenticateObservations()
                    liveServerFacts = facts
                    _liveConnection.value = live.copy(dvrAccess = null, serverFacts = facts)
                    _state.value = connectedState.copy(dvrAccess = null)
                }
            }
        }
    }

    internal open fun serverFactsForLiveConnectionAttempt(
        expectedConnectionAttemptId: Long,
    ): HtspServerFacts? = synchronized(connectionAttemptLock) {
        if (
            connectionAttempt != expectedConnectionAttemptId ||
            liveTransportAttempt != expectedConnectionAttemptId
        ) {
            return@synchronized null
        }
        liveServerFacts
    }

    internal open fun liveHtspVersionForConnectionAttempt(
        expectedConnectionAttemptId: Long,
    ): Int? = synchronized(connectionAttemptLock) {
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
        _state.value = state
    } != null

    private fun publishConnectedState(
        attemptId: Long,
        state: HtspConnectionState.Connected,
        serverFacts: HtspServerFacts,
        connectionIdentity: HtspConnectionIdentity,
    ): Boolean = synchronized(connectionAttemptLock) {
        if (connectionAttempt != attemptId || liveTransportAttempt != attemptId) {
            return@synchronized false
        }
        liveServerFacts = serverFacts
        liveConnectionIdentity = connectionIdentity
        val generation = checkNotNull(protocolGeneration)
        _liveConnection.value = HtspLiveConnection(
            generation = generation.token,
            protocolVersion = state.htspVersion,
            dvrAccess = state.dvrAccess,
            serverFacts = serverFacts,
        )
        _state.value = state
        true
    }

    internal fun <T> commitIfCurrentConnectionAttempt(
        attemptId: Long,
        block: () -> T,
    ): T? = synchronized(connectionAttemptLock) {
        if (attemptId == 0L) return@synchronized block()
        if (connectionAttempt != attemptId) return@synchronized null
        block()
    }

    internal fun <T> commitIfLiveConnectionAttempt(
        attemptId: Long,
        block: () -> T,
    ): T? = synchronized(connectionAttemptLock) {
        if (connectionAttempt != attemptId || liveTransportAttempt != attemptId) {
            return@synchronized null
        }
        block()
    }

    private fun <T> withCurrentConnectionAttempt(
        attemptId: Long,
        block: () -> T,
    ): T? = commitIfCurrentConnectionAttempt(attemptId, block)

    private fun installTransport(
        attemptId: Long,
        transportSocket: Socket,
        transportInput: InputStream,
        transportOutput: OutputStream,
    ) = synchronized(connectionAttemptLock) {
        ensureCurrentConnectionAttempt(attemptId)
        socket = transportSocket
        connectingSocket = null
        input = transportInput
        output = transportOutput
        liveTransportAttempt = attemptId
        if (protocolGeneration?.attemptId != attemptId) {
            protocolGeneration = ServiceProtocolGeneration(attemptId)
        }
        liveServerFacts = null
        liveConnectionIdentity = null
    }

    private fun markTransportGone(target: Socket?) {
        synchronized(connectionAttemptLock) {
            if (socket === target) {
                terminateSubscriptionStreamsLocked(
                    protocolGeneration,
                    HtspSubscriptionTermination.TRANSPORT_CLOSED,
                )
                liveTransportAttempt = null
                liveServerFacts = null
                liveConnectionIdentity = null
                _liveConnection.value = null
            }
        }
        closeSocket(target)
    }

    private fun closeSocket(target: Socket?) {
        runCatching { target?.close() }
    }

    private suspend fun retireAdmissionTransport(attemptId: Long) {
        val retirement = synchronized(connectionAttemptLock) {
            admissionRetirements.remove(attemptId)
        } ?: return
        val callerJob = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            retirement.pending.forEach { it.def.completeExceptionally(retirement.cancellation) }
            retirement.initialSync?.completeExceptionally(retirement.cancellation)
            retirement.readerJob?.takeIf { it !== callerJob }?.cancel()
            closeTransportSnapshot(retirement.transport)
            retirement.readerJob?.takeIf { it !== callerJob }?.join()
        }
    }

    private fun beginConnectionAttemptUnlessReusable(
        requestedIdentity: HtspConnectionIdentity,
        forceReconnect: Boolean,
    ): Long? = synchronized(connectionAttemptLock) {
        if (!forceReconnect && canReuseLiveConnectionLocked(requestedIdentity)) return@synchronized null
        admitReplacementGenerationLocked(HtspSubscriptionTermination.GENERATION_LOST)
    }

    private fun canReuseLiveConnection(requestedIdentity: HtspConnectionIdentity): Boolean =
        synchronized(connectionAttemptLock) {
            canReuseLiveConnectionLocked(requestedIdentity)
        }

    private fun canReuseLiveConnectionLocked(requestedIdentity: HtspConnectionIdentity): Boolean {
        val generation = protocolGeneration ?: return false
        return liveConnectionIdentity?.matches(requestedIdentity) == true &&
            liveTransportAttempt == generation.attemptId &&
            connectionAttempt == generation.attemptId &&
            _state.value is HtspConnectionState.Connected &&
            isConnectedUnsafe()
    }

    private fun beginTeardownAttempt(expectedGeneration: HtspConnectionGeneration?): Long =
        synchronized(connectionAttemptLock) {
            expectedGeneration?.let(::requireCurrentGenerationLocked)
            connectionAttempt
        }

    private fun requireCurrentGeneration(expectedGeneration: HtspConnectionGeneration) {
        synchronized(connectionAttemptLock) {
            requireCurrentGenerationLocked(expectedGeneration)
        }
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
            initialSync = initialSyncDef,
            readerJob = readerJob,
            transport = detachCurrentTransportLocked(),
        )
        pending.clear()
        lateReplyObservers.clear()
        initialSyncDef = null
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
        liveServerFacts = null
        liveConnectionIdentity = null
        challenge = null
        negotiatedHtspVersion = null
        _liveConnection.value = null
        _state.value = HtspConnectionState.Disconnected
        return snapshot
    }

    private fun closeTransportSnapshot(snapshot: TransportSnapshot) {
        closeSocket(snapshot.connectingSocket)
        closeSocket(snapshot.socket)
        runCatching { snapshot.input?.close() }
        runCatching { snapshot.output?.close() }
    }

    private fun checkOpen() {
        lifecycle.checkOpen()
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
        val initialSync: CompletableDeferred<Unit>?,
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

private data class RoutedSubscriptionEvent(
    val subscriptionId: Long,
    val event: HtspSubscriptionEvent,
)

private sealed interface MalformedSubscriptionMessage {
    data class Packet(val subscriptionId: Long) : MalformedSubscriptionMessage
    data object ControlOrEnvelope : MalformedSubscriptionMessage
}

internal val SUBSCRIPTION_SERVER_METHODS: Set<String> = setOf(
    "muxpkt",
    "queueStatus",
    "subscriptionStart",
    "subscriptionStop",
    "subscriptionGrace",
    "subscriptionStatus",
    "signalStatus",
    "descrambleInfo",
    "subscriptionSpeed",
    "timeshiftStatus",
    "subscriptionSkip",
)

internal val METADATA_SERVER_METHODS: Set<String> = setOf(
    "channelAdd",
    "channelUpdate",
    "channelDelete",
    "tagAdd",
    "tagUpdate",
    "tagDelete",
    "dvrEntryAdd",
    "dvrEntryUpdate",
    "dvrEntryDelete",
    "autorecEntryAdd",
    "autorecEntryUpdate",
    "autorecEntryDelete",
    "timerecEntryAdd",
    "timerecEntryUpdate",
    "timerecEntryDelete",
    "eventAdd",
    "eventUpdate",
    "eventDelete",
    "initialSyncCompleted",
)

private val ASYNCHRONOUS_SERVER_METHODS: Set<String> =
    METADATA_SERVER_METHODS + SUBSCRIPTION_SERVER_METHODS

private fun Map<String, Any?>.malformedSubscriptionMessage(): MalformedSubscriptionMessage? {
    val method = this["method"] as? String ?: return null
    if (method !in SUBSCRIPTION_SERVER_METHODS) return null
    if (method != "muxpkt") return MalformedSubscriptionMessage.ControlOrEnvelope
    val subscriptionId = this["subscriptionId"] as? Long
        ?: return MalformedSubscriptionMessage.ControlOrEnvelope
    return if (subscriptionId in 0L..HTSP_U32_MAX) {
        MalformedSubscriptionMessage.Packet(subscriptionId)
    } else {
        MalformedSubscriptionMessage.ControlOrEnvelope
    }
}

private fun HtspServerMessage.toRoutedSubscriptionEvent(): RoutedSubscriptionEvent? = when (this) {
    is HtspSubscriptionStartMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Started(this))
    is HtspMuxPacketMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Packet(this))
    is HtspSubscriptionSkipMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Skipped(this))
    is HtspSubscriptionStopMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Stopped(this))
    is HtspSubscriptionStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Status(this))
    is HtspSubscriptionGraceMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Grace(this))
    is HtspSubscriptionSpeedMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Speed(this))
    is HtspTimeshiftStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Timeshift(this))
    is HtspQueueStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Queue(this))
    is HtspSignalStatusMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Signal(this))
    is HtspDescrambleInfoMessage ->
        RoutedSubscriptionEvent(subscriptionId, HtspSubscriptionEvent.Descramble(this))
    else -> null
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
    apiVersion = hello.apiVersion.toExistingIntObservation(),
)

private fun HtspServerFacts.withAuthenticateObservations(
    auth: AuthenticateResponse,
): HtspServerFacts = copy(
    admin = auth.admin,
    streaming = auth.streaming,
    dvr = auth.dvr,
    failedDvr = auth.failedDvr,
    anonymous = auth.anonymous,
    limitAll = auth.limitAll.toExistingIntObservation(),
    limitDvr = auth.limitDvr.toExistingIntObservation(),
    limitStreaming = auth.limitStreaming.toExistingIntObservation(),
    uiLevel = auth.uiLevel.toExistingIntObservation(),
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

private fun Long?.toExistingIntObservation(): Int? =
    this?.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()

/**
 * Strict hello/authenticate observation mapping for public [HtspServerFacts].
 *
 * Unlike the permissive [HtspWireMessage] helpers used elsewhere, newly published facts reject
 * string/floating-point coercion, truncation, and non-0/1 boolean synthesis. Missing or
 * malformed values stay unknown (`null`). Empty strings and empty capability lists are kept as
 * observed values. Capability lists are copied into an unmodifiable snapshot.
 */
@JvmSynthetic
internal fun htspServerFactsFromHandshake(
    hello: HtspWireMessage,
    auth: HtspWireMessage,
): HtspServerFacts = HtspServerFacts(
    serverName = observedHtspString(hello, "servername"),
    serverVersion = observedHtspString(hello, "serverversion"),
    webRoot = observedHtspString(hello, "webroot"),
    language = observedHtspString(hello, "language"),
    serverCapabilities = observedHtspStringList(hello, "servercapability"),
    apiVersion = observedHtspU32(hello, "api_version"),
    admin = observedHtspAccessFlag(auth, "admin"),
    streaming = observedHtspAccessFlag(auth, "streaming"),
    dvr = observedHtspAccessFlag(auth, "dvr"),
    failedDvr = observedHtspAccessFlag(auth, "faileddvr"),
    anonymous = observedHtspAccessFlag(auth, "anonymous"),
    limitAll = observedHtspU32(auth, "limitall"),
    limitDvr = observedHtspU32(auth, "limitdvr"),
    limitStreaming = observedHtspU32(auth, "limitstreaming"),
    uiLevel = observedHtspU32(auth, "uilevel"),
    uiLanguage = observedHtspString(auth, "uilanguage"),
)

private fun observedHtspString(message: HtspWireMessage, key: String): String? {
    val value = message.fields[key] ?: return null
    return value as? String
}

private fun observedHtspU32(message: HtspWireMessage, key: String): Int? {
    val value = message.fields[key] ?: return null
    val integral = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> return null
    }
    return integral.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
}

private fun observedHtspAccessFlag(message: HtspWireMessage, key: String): Boolean? {
    val value = message.fields[key] ?: return null
    val integral = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> return null
    }
    return when (integral) {
        0L -> false
        1L -> true
        else -> null
    }
}

private fun observedHtspStringList(message: HtspWireMessage, key: String): List<String>? {
    val value = message.fields[key] ?: return null
    val list = value as? List<*> ?: return null
    if (list.any { element -> element !is String }) return null
    val snapshot = list.map { element -> element as String }
    return Collections.unmodifiableList(snapshot)
}
