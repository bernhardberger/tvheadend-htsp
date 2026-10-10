package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** Opaque identity of one current HTSP transport generation, whether live or gone. */
public class HtspConnectionGeneration {
    override fun toString(): String = "HtspConnectionGeneration"
}

/**
 * Small typed connection seam. Raw maps, wire messages, sequences, numeric attempt IDs,
 * decoder outcomes, and implementation exceptions are intentionally absent.
 *
 * Custom implementations, such as test fakes or interceptors, are supported. Members
 * added in minor releases always come with a default implementation. An implementation
 * that delegates with Kotlin `by` uses that default, not the delegate, until recompiled.
 */
public interface HtspConnection {
    /**
     * Current lifecycle state; StateFlow may conflate transitions. A transport Error stays
     * until connect/disconnect/close. A failed connect reports through its outcome and
     * this state only; [HtspTransportEvent.ConnectionFailure] is the authoritative signal
     * for unsolicited failures of live generations.
     */
    public val connectionState: StateFlow<HtspConnectionState>

    public val liveConnection: StateFlow<HtspLiveConnection?>

    /**
     * Metadata and connection failures with replay zero and one bounded queue per collector.
     * Queue saturation never blocks the reader; consumers on an immediate dispatcher such as
     * `Dispatchers.Unconfined` may run on library threads and must not block. Immutable
     * decoded events may be shared across queues. A full queue drops newest metadata and reports [HtspTransportEvent.MetadataOverflow]
     * before later admitted events; connection failures and overflow markers are unbudgeted.
     * At most one pending overflow marker per generation coalesces only that generation's losses.
     * Budget defaults may change; pass [HtspEventBufferOptions] for fixed limits.
     * Recovery (including reconnect and full sync with backoff) belongs to the consumer.
     */
    public val events: Flow<HtspTransportEvent>

    /**
     * Returns a cold ordered stream for one client-selected unsigned-u32 subscription id.
     * Collection registers the id and must start before `subscribe` is executed (for example,
     * launch with CoroutineStart.UNDISPATCHED). A nonlive generation emits
     * Terminated(GENERATION_LOST) and completes. Exactly one
     * collection is allowed for the id in the current connection generation, including after
     * terminal completion. Packets and controls share the configured byte and event budgets.
     * On control overflow, queued events drain before
     * [HtspSubscriptionTermination.CONSUMER_OVERFLOW]; the consumer must send unsubscribe.
     * Queue saturation never blocks the reader. Pressure and
     * malformed packets whose subscription id remains trustworthy are reported by an ordered
     * [HtspSubscriptionEvent.Dropped]; an untrustworthy packet envelope closes the incompatible
     * transport. After a drop, discard video until the next keyframe.
     * Subscribe is rejected before its wire write when no active collection has
     * registered the id. Server [HtspSubscriptionEvent.Stopped] events do not complete the flow:
     * the same subscription may restart with replacement stream metadata. A successful unsubscribe
     * acknowledgement drains committed events then completes; transport or local retirement adds
     * [HtspSubscriptionEvent.Terminated] before completion. Collector cancellation propagates.
     */
    public fun subscriptionEvents(subscriptionId: Long): Flow<HtspSubscriptionEvent>

    /**
     * Returns the ordered stream for [subscriptionId] only if [expectedGeneration] is still the
     * live connection when collection registers the id. A stale or nonlive generation emits
     * Terminated(GENERATION_LOST) and completes without consuming the replacement's id.
     */
    public fun subscriptionEvents(
        subscriptionId: Long,
        expectedGeneration: HtspConnectionGeneration,
    ): Flow<HtspSubscriptionEvent>

    /**
     * Executes one typed request. [timeoutMs] covers handshake/write serialization,
     * dispatch, socket write/flush, and the reply wait. Caller cancellation propagates;
     * a stale [expectedGeneration] before dispatch returns [HtspResult.TransportUnavailable].
     * A completed reply is returned unchanged even after generation replacement.
     */
    public suspend fun <R> execute(
        request: HtspRequest<R>,
        timeoutMs: Long = 5_000L,
        expectedGeneration: HtspConnectionGeneration? = null,
    ): HtspResult<R>

    /**
     * Starts or reuses a connection according to [endpoint] identity and [options].
     * Returns its own successful snapshot, or Failed(SUPERSEDED) if superseded before success.
     * Only cancellation of the calling coroutine propagates as cancellation.
     */
    public suspend fun connect(
        endpoint: HtspEndpoint,
        options: HtspConnectOptions = HtspConnectOptions(),
    ): HtspConnectOutcome

    /** Returns whether [generation] is the current live-or-gone generation identity. */
    public fun isCurrent(generation: HtspConnectionGeneration): Boolean

    /**
     * Disconnects the expected current generation, or performs owner-global cleanup when null.
     * Returns true when this call retires an attempt or transport, or clears sticky Error to
     * Disconnected. Returns false for a stale generation, an already disconnected service, or
     * an already closed service, leaving any replacement untouched. A current generation remains
     * eligible after transport loss or disconnect: neither replaces the generation, so [close]
     * can still close it. A later admitted connect attempt invalidates that generation, even if
     * the attempt fails. Reusing a live connection does not replace its generation.
     * Cleanup completes even for a cancelled caller; only caller
     * cancellation may then throw CancellationException.
     */
    public suspend fun disconnect(expectedGeneration: HtspConnectionGeneration? = null): Boolean

    /**
     * Terminally closes the expected current generation, or performs owner-global close when null.
     * Returns true only when this call closes the service, including an already disconnected
     * service. Returns false if already closed or if the expected generation is stale, leaving
     * any replacement untouched. Transport loss and disconnect keep the current generation,
     * so close(expectedGeneration) can still close it. A later admitted connect attempt
     * invalidates that generation, even if the attempt fails.
     * Reusing a live connection does not replace its generation. Owners wanting unconditional
     * terminal shutdown must call close() without an expected generation.
     * Cleanup completes even for a cancelled caller; only caller cancellation may then throw
     * CancellationException.
     */
    public suspend fun close(expectedGeneration: HtspConnectionGeneration? = null): Boolean
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
    ): HtspResult<R> = try {
        callCurrent(request, timeoutMs, expectedGeneration)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        HtspResult.TransportUnavailable
    }

    private suspend fun <R> callCurrent(
        request: HtspRequest<R>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspResult<R> {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        currentCoroutineContext().ensureActive()
        val startedAtNanos = System.nanoTime()
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
            var acquired = false
            try {
                val remainingMs = timeoutMs - (System.nanoTime() - startedAtNanos) / 1_000_000L
                val withinBudget = withTimeoutOrNull(remainingMs) {
                    handshakeMutex.lock()
                    acquired = true
                    true
                } ?: false
                ensureActiveGeneration(generation)
                if (withinBudget) {
                    callCaptured(request, timeoutMs, startedAtNanos, generation, protocolVersion, isHandshake = true)
                } else {
                    HtspResult.Timeout
                }
            } finally {
                if (acquired) handshakeMutex.unlock()
            }
        } else {
            callCaptured(request, timeoutMs, startedAtNanos, generation, protocolVersion, isHandshake = false)
        }
    }

    private suspend fun <R> callCaptured(
        request: HtspRequest<R>,
        timeoutMs: Long,
        startedAtNanos: Long,
        generation: HtspCapturedGeneration,
        protocolVersion: Int?,
        isHandshake: Boolean,
    ): HtspResult<R> {
        var replyReceived = false
        return try {
            val fields = HtspRequestCodecs.encode(request)
            val remainingMs = timeoutMs - (System.nanoTime() - startedAtNanos) / 1_000_000L
            if (remainingMs <= 0L) throw HtspCallTimeoutException()
            val reply = transport.dispatch(
                generation = generation,
                request = request,
                fields = fields,
                timeoutMs = remainingMs,
            )
            replyReceived = true
            currentCoroutineContext().ensureActive()
            classifyHtspReply(reply, request, protocolVersion ?: 0).also { result ->
                transport.recapture(generation, request, result)
                if (request is HelloRequest && result !is HtspResult.Ok) {
                    transport.retire(generation, HtspSubscriptionTermination.LOCAL_RETIREMENT)
                }
            }
        } catch (cancelled: CancellationException) {
            if (isHandshake && replyReceived) {
                transport.retire(generation, HtspSubscriptionTermination.LOCAL_RETIREMENT)
            }
            throw cancelled
        } catch (_: HtspCallTimeoutException) {
            ensureCurrentGeneration(generation)
            currentCoroutineContext().ensureActive()
            HtspResult.Timeout
        } catch (_: HtspProtocolMappingException) {
            ensureActiveGeneration(generation)
            HtspResult.MalformedReply
        } catch (rejected: HtspRequestAdmissionException) {
            ensureActiveGeneration(generation)
            throw rejected
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
        if (noAccess !is Long) return HtspResult.MalformedReply
        when (noAccess) {
            0L -> Unit
            1L -> {
                if (!reply.fields.containsKey("connlimit")) return HtspResult.AccessDenied
                val connectionLimit = reply.fields["connlimit"]
                return if (connectionLimit is Long && connectionLimit == 1L) {
                    HtspResult.ConnectionLimit
                } else {
                    HtspResult.AccessDenied
                }
            }
            else -> return HtspResult.MalformedReply
        }
    }
    if (reply.fields.containsKey("error")) {
        val error = reply.fields["error"] as? String ?: return HtspResult.MalformedReply
        if (error.lowercase().isUnknownMethodError()) return HtspResult.NotSupported
        return HtspResult.ServerError(serverMessage = error)
    }
    return try {
        HtspResult.Ok(HtspRequestCodecs.decode(request, reply.fields, protocolVersion))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HtspServerRejectionException) {
        HtspResult.ServerError()
    } catch (_: HtspProtocolMappingException) {
        HtspResult.MalformedReply
    } catch (_: RuntimeException) {
        HtspResult.MalformedReply
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
        request: HtspRequest<*>,
        fields: LinkedHashMap<String, Any?>,
        timeoutMs: Long,
    ): HtspWireReply

    /** Identity only: losing the transport does not replace its generation. */
    fun isCurrent(generation: HtspCapturedGeneration): Boolean

    /** Makes only the exact captured generation immediately non-admissible. */
    fun retire(
        generation: HtspCapturedGeneration,
        termination: HtspSubscriptionTermination,
    ) = Unit

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

internal class `HtspServerRejectionException-internal` : Exception()

internal typealias HtspServerRejectionException = `HtspServerRejectionException-internal`

internal class `HtspRequestAdmissionException-internal`(message: String) :
    IllegalStateException(message)

internal typealias HtspRequestAdmissionException =
    `HtspRequestAdmissionException-internal`
