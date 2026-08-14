package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque identity of one current HTSP transport generation, whether live or gone. */
public class HtspConnectionGeneration private constructor() {
    override fun toString(): String = "HtspConnectionGeneration"

    internal companion object {
        @JvmSynthetic
        internal fun create(): HtspConnectionGeneration = HtspConnectionGeneration()
    }
}

/**
 * Small typed connection seam. Raw maps, wire messages, sequences, numeric attempt IDs,
 * decoder outcomes, and implementation exceptions are intentionally absent.
 */
public interface HtspConnection {
    public val liveConnection: StateFlow<HtspLiveConnection?>
    public val events: Flow<HtspTransportEvent>

    public suspend fun connect(
        endpoint: HtspEndpoint,
        options: HtspConnectOptions = HtspConnectOptions(),
    ): HtspConnectOutcome

    public fun isCurrent(generation: HtspConnectionGeneration): Boolean

    public fun <T> commitIfCurrent(
        generation: HtspConnectionGeneration,
        block: () -> T,
    ): T?

    public fun <T> commitIfLive(
        generation: HtspConnectionGeneration,
        block: (HtspLiveConnection) -> T,
    ): T?

    /**
     * Disconnects the expected current generation, or performs owner-global cleanup when null.
     * A current generation remains eligible after transport loss. A stale non-null generation
     * propagates [CancellationException] without mutating transport state.
     */
    public suspend fun disconnect(expectedGeneration: HtspConnectionGeneration? = null)

    /**
     * Terminally closes the expected current generation, or performs owner-global close when null.
     * A current generation remains eligible after transport loss. A stale non-null generation
     * propagates [CancellationException] without closing the owner.
     */
    public suspend fun close(expectedGeneration: HtspConnectionGeneration? = null)
}

/** ABI-hidden capability implemented only by the factory-owned protocol connection. */
internal interface `HtspTypedRequestCapability-internal` {
    suspend fun <R> callTypedRequest(
        request: HtspRequest<R>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspResult<R>
}

internal typealias HtspTypedRequestCapability = `HtspTypedRequestCapability-internal`

/**
 * Executes one already-constructed request from the finite typed HTSP catalog.
 * Cancellation, generation fencing, version preflight, and typed failure classification are
 * preserved by the connection's internal request capability.
 */
public suspend fun <R> HtspConnection.execute(
    request: HtspRequest<R>,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<R> = call(request, timeoutMs, expectedGeneration)

/** Internal bridge shared by public typed execution and generated convenience extensions. */
@JvmSynthetic
internal suspend fun <R> HtspConnection.call(
    request: HtspRequest<R>,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<R> {
    currentCoroutineContext().ensureActive()
    return (this as? HtspTypedRequestCapability)?.callTypedRequest(
        request = request,
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    ) ?: HtspResult.TransportUnavailable
}

/** ABI-hidden owner of the preserved typed request primitive. */
internal class `HtspTypedRequestCaller-internal`(
    private val transport: HtspRequestTransport,
) {
    private val handshakeMutex = Mutex()

    val generation: HtspConnectionGeneration?
        get() = transport.captureGeneration()?.token

    suspend fun <R> call(
        request: HtspRequest<R>,
        timeoutMs: Long = 5_000L,
        expectedGeneration: HtspConnectionGeneration? = null,
    ): HtspResult<R> {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        currentCoroutineContext().ensureActive()
        val generation = transport.captureGeneration() ?: return HtspResult.TransportUnavailable
        if (expectedGeneration != null && generation.token !== expectedGeneration) {
            throw CancellationException("Stale HTSP connection generation")
        }
        val minimumVersion = request.minimumProtocolVersion
        val protocolVersion = generation.protocolVersion
        if (minimumVersion != null && (protocolVersion == null || protocolVersion < minimumVersion)) {
            return HtspResult.NotSupported
        }

        return if (request.isDirectHandshake()) {
            handshakeMutex.withLock {
                ensureActiveGeneration(generation)
                callCaptured(request, timeoutMs, generation, protocolVersion, isHandshake = true)
            }
        } else {
            callCaptured(request, timeoutMs, generation, protocolVersion, isHandshake = false)
        }
    }

    private suspend fun <R> callCaptured(
        request: HtspRequest<R>,
        timeoutMs: Long,
        generation: HtspCapturedGeneration,
        protocolVersion: Int?,
        isHandshake: Boolean,
    ): HtspResult<R> {
        var dispatchStarted = false
        return try {
            dispatchStarted = true
            val reply = transport.dispatch(
                generation = generation,
                method = request.method,
                fields = HtspRequestCodecs.encode(request),
                timeoutMs = timeoutMs,
            )
            ensureActiveGeneration(generation)
            classifyHtspReply(reply, request, protocolVersion ?: 0).also { result ->
                transport.recapture(generation, request, result)
                if (request is HelloRequest && result !is HtspResult.Ok) {
                    transport.retire(generation)
                }
            }
        } catch (cancelled: CancellationException) {
            if (isHandshake && dispatchStarted) transport.retire(generation)
            throw cancelled
        } catch (_: HtspCallTimeoutException) {
            ensureCurrentGeneration(generation)
            if (isHandshake) transport.retire(generation)
            currentCoroutineContext().ensureActive()
            HtspResult.Timeout
        } catch (_: HtspProtocolMappingException) {
            ensureActiveGeneration(generation)
            HtspResult.ServerError
        } catch (_: Exception) {
            ensureActiveGeneration(generation)
            HtspResult.TransportUnavailable
        }
    }

    private fun HtspRequest<*>.isDirectHandshake(): Boolean =
        this is HelloRequest || this is AuthenticateRequest

    private suspend fun ensureActiveGeneration(generation: HtspCapturedGeneration) {
        currentCoroutineContext().ensureActive()
        ensureCurrentGeneration(generation)
    }

    private fun ensureCurrentGeneration(generation: HtspCapturedGeneration) {
        if (!transport.isCurrent(generation)) {
            throw CancellationException("Stale HTSP connection generation")
        }
    }

}

internal fun <R> classifyHtspReply(
    reply: HtspWireReply,
    request: HtspRequest<R>,
    protocolVersion: Int,
): HtspResult<R> {
    if (reply.fields.containsKey("noaccess")) {
        val noAccess = reply.fields["noaccess"]
        if (noAccess !is Long) return HtspResult.ServerError
        when (noAccess) {
            0L -> Unit
            1L -> {
                if (!reply.fields.containsKey("connlimit")) return HtspResult.AccessDenied
                val connectionLimit = reply.fields["connlimit"]
                if (connectionLimit !is Long) return HtspResult.ServerError
                return if (connectionLimit == 1L) {
                    HtspResult.ConnectionLimit
                } else {
                    HtspResult.AccessDenied
                }
            }
            else -> return HtspResult.ServerError
        }
    }
    if (reply.fields.containsKey("error")) {
        val error = reply.fields["error"] as? String ?: return HtspResult.ServerError
        if (error.lowercase().isUnknownMethodError()) return HtspResult.NotSupported
        if (request !is HtspDvrMutationRequest) return HtspResult.ServerError
    }
    return try {
        HtspResult.Ok(HtspRequestCodecs.decode(request, reply.fields, protocolVersion))
    } catch (_: HtspProtocolMappingException) {
        HtspResult.ServerError
    } catch (_: RuntimeException) {
        HtspResult.ServerError
    }
}

private fun String.isUnknownMethodError(): Boolean =
    "method not found" in this || "unknown method" in this

internal typealias HtspTypedRequestCaller = `HtspTypedRequestCaller-internal`

/** HTSP credential-field admission; the authenticate request itself is always sent. */
internal object `HtspAuthenticationPolicy-internal` {
    fun shouldAuthenticate(username: String?, password: String?): Boolean =
        !username?.trim().isNullOrEmpty() && !password?.trim().isNullOrEmpty()
}

internal typealias HtspAuthenticationPolicy = `HtspAuthenticationPolicy-internal`

/** Exact internal marker for an explicitly denied initial metadata exchange. */
internal class `MetadataPermissionDeniedException-internal` :
    IllegalStateException("HTSP metadata permission denied")

internal typealias MetadataPermissionDeniedException =
    `MetadataPermissionDeniedException-internal`

internal data class `HtspCapturedGeneration-internal`(
    val token: HtspConnectionGeneration,
    val protocolVersion: Int?,
    val transportKey: Any,
)

internal typealias HtspCapturedGeneration = `HtspCapturedGeneration-internal`

internal data class `HtspWireReply-internal`(val fields: Map<String, Any?>)

internal typealias HtspWireReply = `HtspWireReply-internal`

internal interface `HtspRequestTransport-internal` {
    fun captureGeneration(): HtspCapturedGeneration?

    suspend fun dispatch(
        generation: HtspCapturedGeneration,
        method: String,
        fields: LinkedHashMap<String, Any?>,
        timeoutMs: Long,
    ): HtspWireReply

    fun isCurrent(generation: HtspCapturedGeneration): Boolean

    /** Makes only the exact captured generation immediately non-admissible. */
    fun retire(generation: HtspCapturedGeneration) = Unit

    suspend fun <R> recapture(
        generation: HtspCapturedGeneration,
        request: HtspRequest<R>,
        result: HtspResult<R>,
    ) = Unit
}

internal typealias HtspRequestTransport = `HtspRequestTransport-internal`

internal class `HtspCallTimeoutException-internal` : Exception()

internal typealias HtspCallTimeoutException = `HtspCallTimeoutException-internal`

internal class `HtspProtocolMappingException-internal` : Exception()

internal typealias HtspProtocolMappingException = `HtspProtocolMappingException-internal`
