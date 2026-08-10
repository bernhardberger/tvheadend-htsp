package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.HtspAuthenticationPolicy
import at.bernhardberger.tvheadend.htsp.HtspCallTimeoutException
import at.bernhardberger.tvheadend.htsp.HtspCapturedGeneration
import at.bernhardberger.tvheadend.htsp.HtspConnection
import at.bernhardberger.tvheadend.htsp.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.HtspRequestTransport
import at.bernhardberger.tvheadend.htsp.HtspWireReply
import at.bernhardberger.tvheadend.htsp.MetadataPermissionDeniedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

@JvmSynthetic
internal const val DVR_PLAY_COUNT_KEEP: Int = Int.MAX_VALUE - 1
private const val HTSP_CHALLENGE_SIZE_BYTES: Int = 32

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
    private val afterTeardownAdmission: suspend () -> Unit = {},
) : PlaybackHtspTransport, HtspRequestTransport, HtspClientTransport {
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

    internal val typedConnection: HtspConnection = HtspConnection.create(this)

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
        if (forceReconnect) supersedeCurrentTransport()
        try {
            connectMutex.withLock {
                ensureCurrentConnectionAttempt(attemptId)
                if (!forceReconnect && canReuseLiveConnection(requestedIdentity)) {
                    restorePreviousConnectionAttempt(attemptId)
                    return
                }

                disconnectInternal(
                    t = CancellationException("Reconnect"),
                    attemptId = attemptId,
                    publishState = false,
                )
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
                    lastReadAtMs = System.currentTimeMillis()

                    if (readerJob != null) {
                        throw IllegalStateException("Reader job already running")
                    }
                    readerJob = scope.launch {
                        readerLoop(
                            responseTimeoutMs = responseTimeoutMs,
                            attemptId = attemptId,
                        )
                    }

                    val hello = request(
                        method = "hello",
                        fields = mapOf(
                            "htspversion" to htspVersion,
                            "clientname" to clientName,
                            "clientversion" to clientVersion
                        ),
                        timeoutMs = responseTimeoutMs,
                        flush = true,
                        disconnectOnTimeout = true
                    )

                    val serverMax = hello.int("htspversion")
                        ?: throw IllegalStateException("HTSP hello reply is missing htspversion")
                    val negotiatedVersion = min(htspVersion, serverMax)
                    val sessionChallenge = hello.bin("challenge")
                    if (sessionChallenge?.size != HTSP_CHALLENGE_SIZE_BYTES) {
                        throw IllegalStateException("HTSP hello reply has an invalid challenge")
                    }
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
                    val authFields = if (withCredentials) {
                        mapOf("username" to user, "digest" to makeDigest(pass, challenge!!))
                    } else {
                        emptyMap()
                    }
                    val auth = request(
                        method = "authenticate",
                        fields = authFields,
                        timeoutMs = responseTimeoutMs,
                        flush = true,
                        disconnectOnTimeout = true
                    )
                    if (auth.int("noaccess") == 1) {
                        throw IllegalStateException(
                            if (withCredentials) {
                                "HTSP authentication failed (noaccess=1)"
                            } else {
                                "HTSP server requires credentials (noaccess=1)"
                            }
                        )
                    }
                    if (auth.fields.containsKey("error")) {
                        throw IllegalStateException("HTSP authentication failed")
                    }
                    // HTSP ≥ 26 includes ACCESS_HTSP_RECORDER as "dvr".
                    val dvrAccess =
                        if (negotiatedHtspVersion != null && negotiatedHtspVersion!! > 25) {
                            auth.int("dvr")?.let { it == 1 }
                        } else {
                            null
                        }
                    val serverFacts = htspServerFactsFromHandshake(hello = hello, auth = auth)

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

    open suspend fun enableAsyncMetadataAndWaitInitialSync(timeoutMs: Long = 30_000) {
        checkOpen()
        if (!isConnectedUnsafe()) throw IllegalStateException("Not connected")

        val def = CompletableDeferred<Unit>()
        val metadataSocket = socket
        initialSyncDef = def

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
            if (initialSyncDef === def) initialSyncDef = null
        }
    }

    override suspend fun synchronizeMetadata(timeoutMs: Long): HtspResult<Unit> = try {
        enableAsyncMetadataAndWaitInitialSync(timeoutMs)
        HtspResult.Ok(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: MetadataPermissionDeniedException) {
        HtspResult.AccessDenied
    } catch (_: SocketTimeoutException) {
        HtspResult.Timeout
    } catch (_: HtspRequestTimeoutException) {
        HtspResult.Timeout
    } catch (_: Exception) {
        HtspResult.TransportUnavailable
    }

    override suspend fun <R> call(
        request: HtspRequest<R>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspResult<R> = typedConnection.call(request, timeoutMs, expectedGeneration)

    override suspend fun executeDvrMutation(
        request: HtspDvrMutationRequest,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspDvrMutationOutcome {
        val typedRequest = request as HtspRequest<*>
        val reply = boundedDvrReply(typedRequest, timeoutMs, expectedGeneration)
            ?: return HtspDvrMutationOutcome.TransportUnavailable
        return classifyDvrMutationReply(reply)
    }

    override suspend fun getDvrConfigurations(
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspDvrConfigurationsOutcome {
        val request = GetDvrConfigsRequest()
        val reply = boundedDvrReply(request, timeoutMs, expectedGeneration)
            ?: return HtspDvrConfigurationsOutcome.TransportUnavailable
        if (reply.str("boundedFailure") == "timeout") {
            return HtspDvrConfigurationsOutcome.Timeout
        }
        if (reply.int("noaccess") == 1) {
            return if (reply.int("connlimit") == 1) {
                HtspDvrConfigurationsOutcome.ConnectionLimit
            } else {
                HtspDvrConfigurationsOutcome.PermissionDenied
            }
        }
        val error = reply.str("error")?.lowercase()
        if (error != null) {
            return if (error.isUnknownMethodError()) {
                HtspDvrConfigurationsOutcome.NotSupported
            } else {
                HtspDvrConfigurationsOutcome.Rejected
            }
        }
        return try {
            val response = HtspRequestCodecs.decode(
                request,
                reply.fields,
                negotiatedHtspVersion ?: 0,
            )
            HtspDvrConfigurationsOutcome.Success(response.configurations)
        } catch (_: RuntimeException) {
            HtspDvrConfigurationsOutcome.Rejected
        }
    }

    private suspend fun boundedDvrReply(
        request: HtspRequest<*>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspMessage? {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        currentCoroutineContext().ensureActive()
        val generation = synchronized(connectionAttemptLock) {
            val current = protocolGeneration ?: return@synchronized null
            if (expectedGeneration != null && current.token !== expectedGeneration) {
                throw CancellationException("Stale HTSP connection generation")
            }
            current
        } ?: return null
        val minimumVersion = request.minimumProtocolVersion
        val version = negotiatedHtspVersion
        if (minimumVersion != null && (version == null || version < minimumVersion)) {
            return HtspMessage(method = null, seq = null, fields = mapOf("error" to "method not found"))
        }
        return try {
            requestForConnectionAttempt(
                expectedConnectionAttemptId = generation.attemptId,
                method = request.method,
                fields = HtspRequestCodecs.encode(request),
                timeoutMs = timeoutMs,
                disconnectOnTimeout = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HtspRequestTimeoutException) {
            HtspMessage(method = null, seq = null, fields = mapOf("boundedFailure" to "timeout"))
        } catch (_: Exception) {
            null
        }
    }

    private fun classifyDvrMutationReply(reply: HtspMessage): HtspDvrMutationOutcome {
        if (reply.str("boundedFailure") == "timeout") return HtspDvrMutationOutcome.Timeout
        if (reply.int("noaccess") == 1) {
            return if (reply.int("connlimit") == 1) {
                HtspDvrMutationOutcome.ConnectionLimit
            } else {
                HtspDvrMutationOutcome.PermissionDenied
            }
        }
        val error = reply.str("error")?.lowercase()
        if (error != null) {
            return when {
                error.isUnknownMethodError() -> HtspDvrMutationOutcome.NotSupported
                error.isPermissionError() -> HtspDvrMutationOutcome.PermissionDenied
                error.isConflictError() -> HtspDvrMutationOutcome.Conflict
                else -> HtspDvrMutationOutcome.Rejected
            }
        }
        return if (reply.int("success") == 1) {
            HtspDvrMutationOutcome.Accepted(reply.long("id") ?: reply.long("dvrId"))
        } else {
            HtspDvrMutationOutcome.Rejected
        }
    }

    override fun isCurrent(generation: HtspConnectionGeneration): Boolean =
        typedConnection.generation === generation

    override fun <T> commitIfCurrent(
        generation: HtspConnectionGeneration,
        block: () -> T,
    ): T? = synchronized(connectionAttemptLock) {
        if (protocolGeneration?.token !== generation) return@synchronized null
        block()
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
            val requestSequence = seq.getAndIncrement()
            val response = CompletableDeferred<HtspMessage>()
            pending[requestSequence] = PendingReq(response, System.currentTimeMillis())
            val transport = if (expectedConnectionAttemptId == null) {
                socket to output
            } else {
                commitIfLiveConnectionAttempt(expectedConnectionAttemptId) {
                    if (isRequestAdmitted?.invoke() == false) null else socket to output
                } ?: run {
                    pending.remove(requestSequence)
                    throw CancellationException("Stale HTSP connection attempt")
                }
            }
            val requestOutput = transport.second ?: run {
                pending.remove(requestSequence)
                throw IllegalStateException("Not connected")
            }
            RequestAdmission(
                sequence = requestSequence,
                response = response,
                socket = transport.first,
                output = requestOutput,
            )
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
            supersedeCurrentTransport()
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
                    requireLiveGeneration(expectedGeneration)
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
            supersedeCurrentTransport()
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

    private suspend fun readerLoop(responseTimeoutMs: Long, attemptId: Long) {
        val inp = input ?: return

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
                    val msg = HtspCodec.readMessage(inp, logger)
                    val currentMessageSequence = ++messageSequence
                    var controlEvent: HtspControlEvent.ServerMessage? = null
                    var typedEvent: HtspTransportEvent.ServerMessage? = null
                    val published = withCurrentConnectionAttempt(attemptId) {
                        lastReadAtMs = System.currentTimeMillis()

                        // Special-cased latch
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
                            val generation = protocolGeneration?.token
                            val decoded = decodeHtspServerMessage(msg.fields)
                            if (generation != null && decoded is HtspServerMessageDecoded) {
                                typedEvent = HtspTransportEvent.ServerMessage(
                                    message = decoded.message,
                                    generation = generation,
                                )
                            }
                        }
                    } != null
                    if (!published) return
                    controlEvent?.let { controlEventStream.emit(it) }
                    typedEvent?.let { _events.emit(it) }
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
        withContext(NonCancellable) {
            val defs = pending.values.toList()
            pending.clear()
            defs.forEach { it.def.completeExceptionally(t) }

            initialSyncDef?.completeExceptionally(t)
            initialSyncDef = null

            val job = readerJob
            readerJob = null
            if (job != null && job !== callerJob) job.cancel()

            // Socket reads are blocking. Closing the transport is what makes a
            // cancelled reader observable; joining first can wait forever.
            closeTransport()
            if (job != null && job !== callerJob) job.join()

            challenge = null
            negotiatedHtspVersion = null
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
            val event = withCurrentConnectionAttempt(attemptId) {
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
            event
        } ?: return
        controlEventStream.emit(event)
        typedEvent?.let { _events.emit(it) }
    }

    private fun ensureCurrentConnectionAttempt(attemptId: Long) {
        if (!isCurrentConnectionAttempt(attemptId)) {
            throw CancellationException("Superseded connection attempt")
        }
    }

    private fun beginConnectionAttempt(): Long = synchronized(connectionAttemptLock) {
        ++connectionAttempt
    }

    private fun restorePreviousConnectionAttempt(attemptId: Long) {
        synchronized(connectionAttemptLock) {
            if (connectionAttempt == attemptId) connectionAttempt--
        }
    }

    override fun currentConnectionAttemptId(): Long = connectionAttempt

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

    override fun currentMuxSequenceForConnectionAttempt(attemptId: Long): Long? =
        synchronized(connectionAttemptLock) {
            if (connectionAttempt == attemptId && muxCursorAttempt == attemptId) {
                muxCursorSequence
            } else {
                null
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
        protocolGeneration = ServiceProtocolGeneration(attemptId)
        liveServerFacts = null
        liveConnectionIdentity = null
    }

    private fun closeTransport() {
        val (
            currentConnectingSocket,
            currentSocket,
            currentInput,
            currentOutput,
        ) = synchronized(connectionAttemptLock) {
            val snapshot = TransportSnapshot(connectingSocket, socket, input, output)
            connectingSocket = null
            socket = null
            input = null
            output = null
            liveTransportAttempt = null
            protocolGeneration = null
            liveServerFacts = null
            liveConnectionIdentity = null
            _liveConnection.value = null
            snapshot
        }

        closeSocket(currentConnectingSocket)
        closeSocket(currentSocket)
        runCatching { currentInput?.close() }
        runCatching { currentOutput?.close() }
    }

    private fun markTransportGone(target: Socket?) {
        synchronized(connectionAttemptLock) {
            if (socket === target) {
                liveTransportAttempt = null
                liveServerFacts = null
                liveConnectionIdentity = null
            }
        }
        closeSocket(target)
    }

    private fun closeSocket(target: Socket?) {
        runCatching { target?.close() }
    }

    private fun supersedeCurrentTransport() {
        val cancellation = CancellationException("Superseded connection attempt")
        val defs = pending.values.toList()
        pending.clear()
        defs.forEach { it.def.completeExceptionally(cancellation) }
        initialSyncDef?.completeExceptionally(cancellation)
        closeTransport()
    }

    private fun beginConnectionAttemptUnlessReusable(
        requestedIdentity: HtspConnectionIdentity,
        forceReconnect: Boolean,
    ): Long? = synchronized(connectionAttemptLock) {
        if (!forceReconnect && canReuseLiveConnectionLocked(requestedIdentity)) return@synchronized null
        ++connectionAttempt
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
            expectedGeneration?.let(::requireLiveGenerationLocked)
            ++connectionAttempt
        }

    private fun requireLiveGeneration(expectedGeneration: HtspConnectionGeneration) {
        synchronized(connectionAttemptLock) {
            requireLiveGenerationLocked(expectedGeneration)
        }
    }

    private fun requireLiveGenerationLocked(expectedGeneration: HtspConnectionGeneration) {
        val generation = protocolGeneration
        if (
            generation?.token !== expectedGeneration ||
            liveTransportAttempt != generation.attemptId ||
            connectionAttempt != generation.attemptId ||
            _state.value !is ConnectionState.Connected
        ) {
            throw CancellationException("Stale HTSP connection generation")
        }
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

private fun String.isUnknownMethodError(): Boolean =
    "method not found" in this || "unknown method" in this

private fun String.isPermissionError(): Boolean =
    "permission" in this || "access denied" in this || "not allowed" in this

private fun String.isConflictError(): Boolean =
    "conflict" in this || "no free" in this || "tuner" in this

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
