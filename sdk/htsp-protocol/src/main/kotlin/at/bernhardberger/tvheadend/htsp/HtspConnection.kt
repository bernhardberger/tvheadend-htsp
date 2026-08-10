package at.bernhardberger.tvheadend.htsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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

/** Typed protocol request entry point for one underlying HTSP transport owner. */
public class HtspConnection private constructor() {
    private lateinit var transport: HtspRequestTransport

    internal companion object {
        @JvmSynthetic
        internal fun create(transport: HtspRequestTransport): HtspConnection =
            HtspConnection().also { connection -> connection.transport = transport }
    }

    /** The current live generation, or null when no typed request can be admitted. */
    public val generation: HtspConnectionGeneration?
        get() = transport.captureGeneration()?.token

    /**
     * Executes exactly one request against one captured live generation.
     * Cancellation, including generation replacement, is never converted to [HtspResult].
     */
    public suspend fun <R> call(request: HtspRequest<R>): HtspResult<R> {
        currentCoroutineContext().ensureActive()
        val generation = transport.captureGeneration() ?: return HtspResult.TransportUnavailable
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
            HtspResult.ServerError()
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
        if (reply.fields.containsKey("error")) {
            return HtspResult.ServerError(reply.fields["error"] as? String)
        }
        if (reply.fields.containsKey("noaccess")) {
            return when (reply.fields["noaccess"]) {
                0L -> decodeReply(request, reply.fields, protocolVersion)
                1L -> HtspResult.AccessDenied
                else -> HtspResult.ServerError()
            }
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
        HtspResult.ServerError()
    } catch (_: RuntimeException) {
        HtspResult.ServerError()
    }
}

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
    ): HtspWireReply

    fun isCurrent(generation: HtspCapturedGeneration): Boolean
}

internal typealias HtspRequestTransport = `HtspRequestTransport-internal`

internal class `HtspCallTimeoutException-internal` : Exception()

internal typealias HtspCallTimeoutException = `HtspCallTimeoutException-internal`

internal class `HtspProtocolMappingException-internal` : Exception()

internal typealias HtspProtocolMappingException = `HtspProtocolMappingException-internal`
