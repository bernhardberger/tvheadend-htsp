package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.HtspAuthenticationPolicy
import at.bernhardberger.tvheadend.htsp.HtspCallTimeoutException
import at.bernhardberger.tvheadend.htsp.HtspCapturedGeneration
import at.bernhardberger.tvheadend.htsp.HtspConnection
import at.bernhardberger.tvheadend.htsp.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.HtspRequestTransport
import at.bernhardberger.tvheadend.htsp.HtspTypedRequestCapability
import at.bernhardberger.tvheadend.htsp.HtspTypedRequestCaller
import at.bernhardberger.tvheadend.htsp.HtspWireReply
import at.bernhardberger.tvheadend.htsp.MetadataPermissionDeniedException
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
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
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

@JvmSynthetic
internal const val DVR_PLAY_COUNT_KEEP: Int = Int.MAX_VALUE - 1
private const val HTSP_CHALLENGE_SIZE_BYTES: Int = 32
private const val TYPED_EVENT_QUEUE_CAPACITY: Int = 8192

internal class `HtspRequestTimeoutException-internal`(
    val requestMethod: String,
    val timeoutMs: Long,
    cause: Throwable? = null,
) : IOException("HTSP request timed out", cause)

internal typealias HtspRequestTimeoutException = `HtspRequestTimeoutException-internal`

public sealed class ConnectionState {
    public data object Disconnected : ConnectionState()
    public data class Connecting(val host: String, val port: Int) : ConnectionState()
    /**
     * @param dvrAccess HTSP `ACCESS_HTSP_RECORDER` from authenticate (version ≥ 26).
     * null when unauthenticated or the field was not returned.
     */
    public data class Connected(
        val host: String,
        val port: Int,
        val htspVersion: Int?,
        val dvrAccess: Boolean? = null,
    ) : ConnectionState()
    public data class Error(val throwable: Throwable) : ConnectionState()
}

@PlaybackIntegrationApi
public enum class HtspConnectionAttemptStatus {
    LIVE,
    GONE,
    REPLACED,
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
) : PlaybackHtspTransport, HtspRequestTransport, HtspConnection, HtspTypedRequestCapability {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _liveConnection = MutableStateFlow<HtspLiveConnection?>(null)
    override val liveConnection: StateFlow<HtspLiveConnection?> = _liveConnection

    private val _events = MutableSharedFlow<HtspTransportEvent>()
    override val events: SharedFlow<HtspTransportEvent> = _events

    @PlaybackIntegrationApi
    override val playbackTransport: PlaybackHtspTransport
        get() = this

    open fun currentConnectionState(): ConnectionState = state.value

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + ioDispatcher)
    private val lifecycle = TerminalLifecycleGate("HTSP service is closed")
    private val typedEventQueue = ArrayDeque<HtspTransportEvent>()
    private val typedEventAvailable = Channel<Unit>(Channel.CONFLATED)
    private val typedEventSpaceAvailable = Channel<Unit>(Channel.CONFLATED)
    private var typedEventPublisherJob: Job? = null

    private val controlEventStream = HtspEventStream()
    override val controlEvents: Flow<HtspControlEvent> = controlEventStream.events.filter { event ->
        event.connectionAttemptId == 0L ||
            isCurrentConnectionAttempt(event.connectionAttemptId)
    }

    private val _muxEvents = MutableSharedFlow<HtspMuxEvent>(
        extraBufferCapacity = 8192,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val muxEvents: SharedFlow<HtspMuxEvent> = _muxEvents

    private val pending = ConcurrentHashMap<Int, PendingReq>()

    private data class PendingReq(
        val def: CompletableDeferred<HtspMessage>,
        val startedAtMs: Long
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
    private var muxCursorAttempt = 0L

    @Volatile
    private var muxCursorSequence = 0L

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
                    ConnectionState.Connecting(host, port),
                )

                val s = lifecycle.admit {
                    ensureCurrentConnectionAttempt(attemptId)
                    socketFactory().also { connectingSocket = it }
                }
                try {
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
                            state = ConnectionState.Connected(
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
                    publishConnectionState(attemptId, ConnectionState.Error(t))
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
            publishConnectionState(attemptId, ConnectionState.Disconnected)
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

    override suspend fun <R> callTypedRequest(
        request: HtspRequest<R>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspResult<R> = typedRequestCaller.call(request, timeoutMs, expectedGeneration)

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
    ): HtspMessage = requestInternal(
        expectedConnectionAttemptId = null,
        method = method,
        fields = fields,
        timeoutMs = timeoutMs,
        flush = flush,
        disconnectOnTimeout = disconnectOnTimeout,
    )

    override suspend fun startSubscription(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        channelId: Int,
        timeshiftPeriodSec: Int,
        profile: String?,
    ): PlaybackSubscriptionStart {
        val response = requestForConnectionAttempt(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "subscribe",
            fields = mapOf(
                "subscriptionId" to subscriptionId,
                "channelId" to channelId,
                "timeshiftPeriod" to timeshiftPeriodSec,
                "profile" to profile,
            ),
        )
        response.requireSuccessfulSubscriptionReply("subscribe")
        return PlaybackSubscriptionStart(
            availableTimeshiftPeriodSec = response.int("timeshiftPeriod"),
        )
    }

    override suspend fun stopSubscription(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
    ) {
        requestForConnectionAttempt(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "unsubscribe",
            fields = mapOf("subscriptionId" to subscriptionId),
        ).requireSuccessfulSubscriptionReply("unsubscribe")
    }

    override suspend fun setSubscriptionWeight(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        weight: Int,
    ) {
        currentCoroutineContext().ensureActive()
        val htspVersion = liveHtspVersionForConnectionAttempt(expectedConnectionAttemptId)
        if (htspVersion == null || htspVersion < 5) {
            throw UnsupportedOperationException(
                "HTSP subscriptionChangeWeight requires protocol version 5 or newer",
            )
        }
        require(subscriptionId >= 0) { "subscriptionId must be non-negative" }
        require(weight >= 0) { "weight must be non-negative" }

        val response = requestForConnectionAttempt(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "subscriptionChangeWeight",
            fields = mapOf(
                "subscriptionId" to subscriptionId,
                "weight" to weight,
            ),
        )
        currentCoroutineContext().ensureActive()
        commitIfLiveConnectionAttempt(expectedConnectionAttemptId) {
            response.requireSuccessfulSubscriptionWeightReply()
        } ?: throw CancellationException("Stale HTSP connection attempt")
    }

    override suspend fun updateSubscriptionStreamFilter(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        enabledStreamIndices: List<Int>,
        disabledStreamIndices: List<Int>,
    ) {
        currentCoroutineContext().ensureActive()
        val htspVersion = liveHtspVersionForConnectionAttempt(expectedConnectionAttemptId)
        if (htspVersion == null || htspVersion < 12) {
            throw UnsupportedOperationException(
                "HTSP subscriptionFilterStream requires protocol version 12 or newer",
            )
        }
        require(subscriptionId >= 0) { "subscriptionId must be non-negative" }
        val enabledSnapshot = enabledStreamIndices.toList()
        val disabledSnapshot = disabledStreamIndices.toList()
        require(enabledSnapshot.all { streamIndex -> streamIndex >= 0 }) {
            "enabledStreamIndices must contain only non-negative values"
        }
        require(disabledSnapshot.all { streamIndex -> streamIndex >= 0 }) {
            "disabledStreamIndices must contain only non-negative values"
        }

        val response = requestForConnectionAttempt(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "subscriptionFilterStream",
            fields = buildMap {
                put("subscriptionId", subscriptionId)
                if (enabledSnapshot.isNotEmpty()) put("enable", enabledSnapshot)
                if (disabledSnapshot.isNotEmpty()) put("disable", disabledSnapshot)
            },
        )
        currentCoroutineContext().ensureActive()
        commitIfLiveConnectionAttempt(expectedConnectionAttemptId) {
            response.requireSuccessfulSubscriptionFilterReply()
        } ?: throw CancellationException("Stale HTSP connection attempt")
    }

    override suspend fun returnSubscriptionToLive(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
    ) {
        currentCoroutineContext().ensureActive()
        val htspVersion = liveHtspVersionForConnectionAttempt(expectedConnectionAttemptId)
        if (htspVersion == null || htspVersion < 9) {
            throw UnsupportedOperationException(
                "HTSP subscriptionLive requires protocol version 9 or newer",
            )
        }
        require(subscriptionId >= 0) { "subscriptionId must be non-negative" }

        val response = requestForConnectionAttempt(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "subscriptionLive",
            fields = mapOf("subscriptionId" to subscriptionId),
        )
        currentCoroutineContext().ensureActive()
        commitIfLiveConnectionAttempt(expectedConnectionAttemptId) {
            response.requireSuccessfulSubscriptionLiveReply()
        } ?: throw CancellationException("Stale HTSP connection attempt")
    }

    override suspend fun setSubscriptionSpeed(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        speed: Int,
    ) {
        requestForConnectionAttempt(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "subscriptionSpeed",
            fields = mapOf(
                "subscriptionId" to subscriptionId,
                "speed" to speed,
            ),
        ).requireSuccessfulSubscriptionReply("subscriptionSpeed")
    }

    override suspend fun seekSubscription(
        expectedConnectionAttemptId: Long,
        subscriptionId: Int,
        timeUs: Long,
        absolute: Boolean,
    ) {
        requestForConnectionAttempt(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "subscriptionSeek",
            fields = mapOf(
                "subscriptionId" to subscriptionId,
                "time" to timeUs,
                "absolute" to if (absolute) 1 else 0,
            ),
        ).requireSuccessfulSubscriptionReply("subscriptionSeek")
    }

    private fun HtspMessage.requireSuccessfulSubscriptionReply(method: String) {
        if (int("noaccess") == 1 || fields.containsKey("error")) {
            throw IOException("HTSP $method failed")
        }
    }

    private fun HtspMessage.requireSuccessfulSubscriptionWeightReply() {
        if (
            fields.containsKey("error") ||
            (fields.containsKey("noaccess") && fields["noaccess"] != 0L)
        ) {
            throw IOException("HTSP subscriptionChangeWeight failed")
        }
    }

    private fun HtspMessage.requireSuccessfulSubscriptionFilterReply() {
        if (
            fields.containsKey("error") ||
            (fields.containsKey("noaccess") && fields["noaccess"] != 0L)
        ) {
            throw IOException("HTSP subscriptionFilterStream failed")
        }
    }

    private fun HtspMessage.requireSuccessfulSubscriptionLiveReply() {
        if (
            fields.containsKey("error") ||
            (fields.containsKey("noaccess") && fields["noaccess"] != 0L)
        ) {
            throw IOException("HTSP subscriptionLive failed")
        }
    }

    internal open suspend fun requestForConnectionAttempt(
        expectedConnectionAttemptId: Long,
        method: String,
        fields: Map<String, Any?>,
        timeoutMs: Long = 5_000,
        flush: Boolean = true,
        disconnectOnTimeout: Boolean = true,
    ): HtspMessage = requestInternal(
        expectedConnectionAttemptId = expectedConnectionAttemptId,
        method = method,
        fields = fields,
        timeoutMs = timeoutMs,
        flush = flush,
        disconnectOnTimeout = disconnectOnTimeout,
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
    ): HtspMessage = requestInternal(
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
    ): HtspMessage {
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
                val response = CompletableDeferred<HtspMessage>()
                pending[requestSequence] = PendingReq(response, System.currentTimeMillis())
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
                pending.remove(s)

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
            pending.remove(s)
            throw t
        }
    }

    override suspend fun fileOpen(
        path: String,
        timeoutMs: Long,
        expectedConnectionAttemptId: Long?,
    ): Int {
        val p = if (path.startsWith("/")) path else "/$path"
        val msg = fileRequest(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "fileOpen",
            fields = mapOf("file" to p),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false
        )
        msg.requireSuccessfulFileReply("fileOpen")
        return msg.int("id")
            ?: throw IOException("HTSP fileOpen reply missing id")
    }

    override suspend fun fileRead(
        id: Int,
        size: Int,
        timeoutMs: Long,
        expectedConnectionAttemptId: Long?,
    ): ByteArray {
        val msg = fileRequest(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "fileRead",
            fields = mapOf("id" to id, "size" to size),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false
        )
        msg.requireSuccessfulFileReply("fileRead")
        return msg.bin("data")
            ?: throw IOException("HTSP fileRead reply missing data")
    }

    override suspend fun fileSeek(
        id: Int,
        offset: Long,
        whence: String,
        timeoutMs: Long,
        expectedConnectionAttemptId: Long?,
    ): Long {
        val msg = fileRequest(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "fileSeek",
            fields = mapOf("id" to id, "offset" to offset, "whence" to whence),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false,
        )
        msg.requireSuccessfulFileReply("fileSeek")
        return msg.long("offset") ?: offset
    }

    override suspend fun fileStat(
        id: Int,
        timeoutMs: Long,
        expectedConnectionAttemptId: Long?,
    ): Long? {
        val msg = fileRequest(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "fileStat",
            fields = mapOf("id" to id),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false,
        )
        msg.requireSuccessfulFileReply("fileStat")
        return fileStatSize(msg)
    }

    private fun fileStatSize(msg: HtspMessage): Long? {
        if (!msg.fields.containsKey("size")) return null
        val size = when (val value = msg.fields["size"]) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IOException("HTSP fileStat reply invalid size")
        }
        if (size < 0) throw IOException("HTSP fileStat reply invalid size")
        return size
    }

    override suspend fun fileClose(
        id: Int,
        timeoutMs: Long,
        expectedConnectionAttemptId: Long?,
    ) {
        fileRequest(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "fileClose",
            fields = mapOf("id" to id),
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false
        ).requireSuccessfulFileReply("fileClose")
    }

    override suspend fun fileCloseRecording(
        id: Int,
        htspVersion: Int?,
        timeoutMs: Long,
        expectedConnectionAttemptId: Long?,
    ) {
        val fields = if (htspVersion != null && htspVersion >= 27) {
            mapOf(
                "id" to id,
                "playcount" to DVR_PLAY_COUNT_KEEP,
            )
        } else {
            mapOf("id" to id)
        }
        fileRequest(
            expectedConnectionAttemptId = expectedConnectionAttemptId,
            method = "fileClose",
            fields = fields,
            timeoutMs = timeoutMs,
            flush = true,
            disconnectOnTimeout = false,
        ).requireSuccessfulFileReply("fileClose")
    }

    private fun HtspMessage.requireSuccessfulFileReply(method: String) {
        if (int("noaccess") == 1 || fields.containsKey("error")) {
            throw IOException("HTSP $method failed")
        }
    }

    private suspend fun fileRequest(
        expectedConnectionAttemptId: Long?,
        method: String,
        fields: Map<String, Any?>,
        timeoutMs: Long,
        flush: Boolean,
        disconnectOnTimeout: Boolean,
    ): HtspMessage = if (expectedConnectionAttemptId == null) {
        request(method, fields, timeoutMs, flush, disconnectOnTimeout)
    } else {
        requestForConnectionAttempt(
            expectedConnectionAttemptId,
            method,
            fields,
            timeoutMs,
            flush,
            disconnectOnTimeout,
        )
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

    internal fun beginClose(): Long? = lifecycle.close { beginConnectionAttempt() }

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
        var messageSequence = 0L
        var muxSequence = 0L
        if (
            withCurrentConnectionAttempt(attemptId) {
                muxCursorAttempt = attemptId
                muxCursorSequence = 0L
            } == null
        ) return
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    val msg = HtspCodec.readMessage(transportInput, logger)
                    val currentMessageSequence = ++messageSequence
                    var controlEvent: HtspControlEvent.ServerMessage? = null
                    var typedEvent: HtspTransportEvent.ServerMessage? = null
                    val published = withCurrentConnectionAttempt(attemptId) {
                        lastReadAtMs = System.currentTimeMillis()

                        // Internal probe latch. SDK metadata workflow observes the typed event.
                        if (msg.seq == null && msg.method == "initialSyncCompleted") {
                            initialSyncDef?.complete(Unit)
                        }

                        val seqNo = msg.seq
                        if (seqNo != null) {
                            val pr = pending.remove(seqNo)
                            if (pr != null) {
                                pr.def.complete(msg)
                            }
                            // HTSP async messages never carry seq. A reply whose waiter
                            // already timed out or was cancelled must not enter event flows.
                            return@withCurrentConnectionAttempt
                        }

                        if (msg.method == "muxpkt") {
                            val currentMuxSequence = ++muxSequence
                            muxCursorSequence = currentMuxSequence
                            _muxEvents.tryEmit(
                                HtspMuxEvent(
                                    msg = msg,
                                    connectionAttemptId = attemptId,
                                    messageSequence = currentMessageSequence,
                                    muxSequence = currentMuxSequence,
                                )
                            )
                        } else {
                            controlEvent = HtspControlEvent.ServerMessage(
                                msg = msg,
                                connectionAttemptId = attemptId,
                                messageSequence = currentMessageSequence,
                            )
                        }

                        val generation = protocolGeneration?.token
                        val decoded = decodeHtspServerMessage(msg.fields)
                        if (generation != null && decoded is HtspServerMessageDecoded) {
                            typedEvent = HtspTransportEvent.ServerMessage(
                                message = decoded.message,
                                generation = generation,
                                messageSequence = currentMessageSequence,
                            )
                        }
                    } != null
                    if (!published) return
                    controlEvent?.let { controlEventStream.emit(it) }
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
                publishConnectionState(attemptId, ConnectionState.Disconnected)
            }
        }
    }

    private suspend fun failAll(t: Throwable, attemptId: Long) {
        if (!isCurrentConnectionAttempt(attemptId)) return
        var typedEvent: HtspTransportEvent.ConnectionFailure? = null
        val event = connectMutex.withLock<HtspControlEvent.ConnectionError?> {
            if (!isCurrentConnectionAttempt(attemptId)) return@withLock null
            val publication = withCurrentConnectionAttempt(attemptId) {
                _state.value = ConnectionState.Error(t)
                typedEvent = HtspTransportEvent.ConnectionFailure(
                    failure = typedTransportFailure(t),
                    generation = protocolGeneration?.token,
                )
                HtspControlEvent.ConnectionError(
                    error = t,
                    connectionAttemptId = attemptId,
                )
            } ?: return@withLock null
            disconnectInternal(
                t = t,
                attemptId = attemptId,
                publishState = true,
            )
            publication
        } ?: return
        controlEventStream.emit(event)
        typedEvent?.let { publishTypedEvent(attemptId, it) }
    }

    private suspend fun publishTypedServerEvent(
        attemptId: Long,
        event: HtspTransportEvent.ServerMessage,
    ) {
        beforeTypedEventPublication(event)
        publishTypedEvent(attemptId, event)
    }

    private suspend fun publishTypedEvent(
        attemptId: Long,
        event: HtspTransportEvent,
    ) {
        while (currentCoroutineContext().isActive) {
            val result = synchronized(connectionAttemptLock) {
                if (connectionAttempt != attemptId || !event.matchesCurrentGenerationLocked()) {
                    return
                }
                enqueueTypedEventLocked(event)
            }
            when (result) {
                TypedEventEnqueueResult.ACCEPTED,
                TypedEventEnqueueResult.DROPPED_MUX,
                -> return
                TypedEventEnqueueResult.WAIT_FOR_ORDINARY_SPACE -> typedEventSpaceAvailable.receive()
            }
        }
    }

    /** Called only while [connectionAttemptLock] is held. */
    private fun enqueueTypedEventLocked(event: HtspTransportEvent): TypedEventEnqueueResult {
        if (typedEventQueue.size >= TYPED_EVENT_QUEUE_CAPACITY) {
            val oldestMux = typedEventQueue.iterator().let { iterator ->
                var removed = false
                while (iterator.hasNext()) {
                    if (iterator.next().isTypedMuxEvent()) {
                        iterator.remove()
                        removed = true
                        break
                    }
                }
                removed
            }
            if (!oldestMux) {
                return if (event.isTypedMuxEvent()) {
                    TypedEventEnqueueResult.DROPPED_MUX
                } else {
                    TypedEventEnqueueResult.WAIT_FOR_ORDINARY_SPACE
                }
            }
        }
        typedEventQueue.addLast(event)
        if (typedEventPublisherJob == null) {
            typedEventPublisherJob = scope.launch { publishTypedEvents() }
        }
        typedEventAvailable.trySend(Unit)
        return TypedEventEnqueueResult.ACCEPTED
    }

    private suspend fun publishTypedEvents() {
        for (ignored in typedEventAvailable) {
            while (currentCoroutineContext().isActive) {
                val event = synchronized(connectionAttemptLock) {
                    typedEventQueue.pollFirst()
                } ?: break
                typedEventSpaceAvailable.trySend(Unit)
                _events.emit(event)
            }
        }
    }

    private fun HtspTransportEvent.matchesCurrentGenerationLocked(): Boolean = when (this) {
        is HtspTransportEvent.ServerMessage -> protocolGeneration?.token === generation
        is HtspTransportEvent.ConnectionFailure ->
            generation == null || protocolGeneration?.token === generation
    }

    private fun HtspTransportEvent.isTypedMuxEvent(): Boolean =
        this is HtspTransportEvent.ServerMessage && message is HtspMuxPacketMessage

    private enum class TypedEventEnqueueResult {
        ACCEPTED,
        DROPPED_MUX,
        WAIT_FOR_ORDINARY_SPACE,
    }

    private fun ensureCurrentConnectionAttempt(attemptId: Long) {
        if (!isCurrentConnectionAttempt(attemptId)) {
            throw CancellationException("Superseded connection attempt")
        }
    }

    private fun beginConnectionAttempt(): Long = synchronized(connectionAttemptLock) {
        admitReplacementGenerationLocked()
    }

    override fun currentConnectionAttemptId(): Long = connectionAttempt

    override fun currentMuxSequenceForConnectionAttempt(attemptId: Long): Long? =
        synchronized(connectionAttemptLock) {
            if (connectionAttempt == attemptId && muxCursorAttempt == attemptId) {
                muxCursorSequence
            } else {
                null
            }
        }

    override fun captureGeneration(): HtspCapturedGeneration? = synchronized(connectionAttemptLock) {
        val generation = protocolGeneration ?: return@synchronized null
        if (
            liveTransportAttempt != generation.attemptId ||
            connectionAttempt != generation.attemptId ||
            _state.value !is ConnectionState.Connected
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
        method: String,
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
                requestForConnectionAttempt(
                    expectedConnectionAttemptId = serviceGeneration.attemptId,
                    method = method,
                    fields = fields,
                    timeoutMs = timeoutMs,
                    flush = true,
                    disconnectOnTimeout = false,
                ).fields,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HtspRequestTimeoutException) {
            throw HtspCallTimeoutException()
        }
    }

    override fun isCurrent(generation: HtspCapturedGeneration): Boolean =
        synchronized(connectionAttemptLock) {
            val serviceGeneration = generation.transportKey as? ServiceProtocolGeneration
                ?: return@synchronized false
            protocolGeneration === serviceGeneration &&
                generation.token === serviceGeneration.token &&
                liveTransportAttempt == serviceGeneration.attemptId &&
                connectionAttempt == serviceGeneration.attemptId &&
                _state.value is ConnectionState.Connected
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
            liveTransportAttempt = null
            liveServerFacts = null
            liveConnectionIdentity = null
            challenge = null
            negotiatedHtspVersion = null
            _liveConnection.value = null
            _state.value = ConnectionState.Disconnected
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
            val connectedState = _state.value as? ConnectionState.Connected
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

    override fun isCurrentConnectionAttemptId(attemptId: Long): Boolean =
        isCurrentConnectionAttempt(attemptId)

    override fun connectionAttemptStatus(attemptId: Long): HtspConnectionAttemptStatus =
        synchronized(connectionAttemptLock) {
            when {
                connectionAttempt != attemptId -> HtspConnectionAttemptStatus.REPLACED
                liveTransportAttempt == attemptId -> HtspConnectionAttemptStatus.LIVE
                else -> HtspConnectionAttemptStatus.GONE
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
        state: ConnectionState,
    ): Boolean = withCurrentConnectionAttempt(attemptId) {
        _state.value = state
    } != null

    private fun publishConnectedState(
        attemptId: Long,
        state: ConnectionState.Connected,
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

    override fun <T> commitIfCurrentConnectionAttempt(
        attemptId: Long,
        block: () -> T,
    ): T? = synchronized(connectionAttemptLock) {
        if (attemptId == 0L) return@synchronized block()
        if (connectionAttempt != attemptId) return@synchronized null
        block()
    }

    override fun <T> commitIfLiveConnectionAttempt(
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
        admitReplacementGenerationLocked()
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
            _state.value is ConnectionState.Connected &&
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

    private fun admitReplacementGenerationLocked(): Long {
        val attemptId = ++connectionAttempt
        protocolGeneration = ServiceProtocolGeneration(attemptId)
        val cancellation = CancellationException("Superseded connection attempt")
        admissionRetirements[attemptId] = captureCurrentTransportLocked(cancellation)
        return attemptId
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
        _state.value = ConnectionState.Disconnected
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
        val response: CompletableDeferred<HtspMessage>,
        val socket: Socket?,
        val output: OutputStream,
    )

    private class ServiceProtocolGeneration(
        val attemptId: Long,
        val token: HtspConnectionGeneration = HtspConnectionGeneration.create(),
    )

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
 * Unlike the permissive [HtspMessage] helpers used elsewhere, newly published facts reject
 * string/floating-point coercion, truncation, and non-0/1 boolean synthesis. Missing or
 * malformed values stay unknown (`null`). Empty strings and empty capability lists are kept as
 * observed values. Capability lists are copied into an unmodifiable snapshot.
 */
@JvmSynthetic
internal fun htspServerFactsFromHandshake(
    hello: HtspMessage,
    auth: HtspMessage,
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

private fun observedHtspString(message: HtspMessage, key: String): String? {
    val value = message.fields[key] ?: return null
    return value as? String
}

private fun observedHtspU32(message: HtspMessage, key: String): Int? {
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

private fun observedHtspAccessFlag(message: HtspMessage, key: String): Boolean? {
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

private fun observedHtspStringList(message: HtspMessage, key: String): List<String>? {
    val value = message.fields[key] ?: return null
    val list = value as? List<*> ?: return null
    if (list.any { element -> element !is String }) return null
    val snapshot = list.map { element -> element as String }
    return Collections.unmodifiableList(snapshot)
}
