package at.bernhardberger.tvheadend.htsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Opaque identity of one live HTSP transport generation. */
public class HtspConnectionGeneration private constructor() {
    override fun toString(): String = "HtspConnectionGeneration"

    internal companion object {
        @JvmSynthetic
        internal fun create(): HtspConnectionGeneration = HtspConnectionGeneration()
    }
}

/** Pinned server-dispatch access metadata. The server remains authoritative. */
public enum class HtspAccess {
    ACCESS_HTSP_STREAMING,
    ACCESS_HTSP_RECORDER,
}

/** A typed request whose raw HTSP envelope and reply mapping remain ABI-hidden. */
public abstract class HtspRequest<R> internal constructor(
    public val method: String,
    public val access: HtspAccess,
    public val minimumProtocolVersion: Int?,
    @Suppress("UNUSED_PARAMETER")
    constructorMarker: HtspRequestConstructorMarker = HtspRequestConstructorMarker,
)

internal object `HtspRequestConstructorMarker-internal`

internal typealias HtspRequestConstructorMarker =
    `HtspRequestConstructorMarker-internal`

/**
 * Small typed connection seam. Raw maps, wire messages, sequences, numeric attempt IDs,
 * decoder outcomes, and implementation exceptions are intentionally absent.
 */
public interface HtspConnection {
    public val liveConnection: StateFlow<HtspLiveConnection?>
    public val events: Flow<HtspTransportEvent>

    /** The separately opted-in raw playback SPI backed by this connection owner. */
    @PlaybackIntegrationApi
    public val playbackTransport: PlaybackHtspTransport

    public suspend fun connect(
        endpoint: HtspEndpoint,
        options: HtspConnectOptions = HtspConnectOptions(),
    ): HtspConnectOutcome

    /**
     * Executes exactly one request against one captured live generation.
     * Cancellation, including generation replacement, is never converted to [HtspResult].
     */
    public suspend fun <R> call(
        request: HtspRequest<R>,
        timeoutMs: Long = 5_000L,
        expectedGeneration: HtspConnectionGeneration? = null,
    ): HtspResult<R>

    public fun isCurrent(generation: HtspConnectionGeneration): Boolean

    public fun <T> commitIfCurrent(
        generation: HtspConnectionGeneration,
        block: () -> T,
    ): T?

    /**
     * Disconnects the expected live generation, or performs owner-global cleanup when null.
     * A stale non-null generation propagates [CancellationException] without mutating transport state.
     */
    public suspend fun disconnect(expectedGeneration: HtspConnectionGeneration? = null)

    /**
     * Terminally closes the expected live generation, or performs owner-global close when null.
     * A stale non-null generation propagates [CancellationException] without closing the owner.
     */
    public suspend fun close(expectedGeneration: HtspConnectionGeneration? = null)
}

/** ABI-hidden owner of the preserved typed request primitive. */
internal class `HtspTypedRequestCaller-internal`(
    private val transport: HtspRequestTransport,
) {
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

        return try {
            val reply = transport.dispatch(
                generation = generation,
                method = request.method,
                fields = HtspRequestCodecs.encode(request),
                timeoutMs = timeoutMs,
            )
            ensureActiveGeneration(generation)
            classifyReply(reply, request, protocolVersion ?: 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HtspCallTimeoutException) {
            ensureActiveGeneration(generation)
            HtspResult.Timeout
        } catch (_: HtspProtocolMappingException) {
            ensureActiveGeneration(generation)
            HtspResult.ServerError
        } catch (_: Exception) {
            ensureActiveGeneration(generation)
            HtspResult.TransportUnavailable
        }
    }

    private suspend fun ensureActiveGeneration(generation: HtspCapturedGeneration) {
        currentCoroutineContext().ensureActive()
        if (!transport.isCurrent(generation)) {
            throw CancellationException("Stale HTSP connection generation")
        }
    }

    private fun <R> classifyReply(
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
        return decodeReply(request, reply.fields, protocolVersion)
    }

    private fun <R> decodeReply(
        request: HtspRequest<R>,
        fields: Map<String, Any?>,
        protocolVersion: Int,
    ): HtspResult<R> = try {
        HtspResult.Ok(HtspRequestCodecs.decode(request, fields, protocolVersion))
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
}

internal typealias HtspRequestTransport = `HtspRequestTransport-internal`

internal class `HtspCallTimeoutException-internal` : Exception()

internal typealias HtspCallTimeoutException = `HtspCallTimeoutException-internal`

internal class `HtspProtocolMappingException-internal` : Exception()

internal typealias HtspProtocolMappingException = `HtspProtocolMappingException-internal`
