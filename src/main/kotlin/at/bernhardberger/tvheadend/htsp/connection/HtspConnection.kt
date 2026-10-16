package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.requests.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal const val MAXIMUM_METADATA_BATCH_SIZE = 4096

/** Opaque identity of one current HTSP transport generation, whether live or gone. */
public class HtspConnectionGeneration {
    @get:JvmSynthetic
    @set:JvmSynthetic
    internal var termination: HtspGenerationTerminationLatch? = null

    override fun toString(): String = "HtspConnectionGeneration"
}

/** The token owns its result; neither the owner key nor the latch retains the service. */
internal class HtspGenerationTerminationLatch(val owner: Any) {
    @Volatile var result: HtspGenerationTermination? = null
    val available = CompletableDeferred<Unit>()

    suspend fun await(): HtspGenerationTermination {
        currentCoroutineContext().ensureActive()
        result?.let { return it }
        available.await()
        return checkNotNull(result)
    }
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
     * for unsolicited failures of live generations. Connected carries its live connection
     * atomically; Closed is terminal and never emits a ConnectionFailure for the owner close.
     */
    public val connectionState: StateFlow<HtspConnectionState>

    /**
     * Awaits the end of [generation]'s live transport.
     *
     * Library-created connections latch exactly one result per generation. Call
     * before or after its end; later calls return the stored result immediately, even after
     * replacement. [HtspGenerationTermination.Failed] carries the same failure as that
     * generation's [HtspTransportEvent.ConnectionFailure]; other teardown returns
     * [HtspGenerationTermination.Disconnected]. Cancelling a caller cancels only its await.
     * A generation that never reached [HtspConnectionState.Connected] ends as
     * [HtspGenerationTermination.Disconnected].
     *
     * This out-of-band signal may resume before a collector drains earlier metadata.
     * [HtspTransportEvent.ConnectionFailure] in [events] and [eventBatches] remains the
     * in-order signal for metadata consumers.
     * [connectionState] is published separately: the result may become visible before that
     * state reflects the end.
     *
     * Library-created connections throw [IllegalArgumentException] before suspending for a
     * token they did not create, including a publicly constructed token. The default for custom
     * connections only waits until [connectionState] is no longer [HtspConnectionState.Connected]
     * with this generation. It maps [HtspConnectionState.Error] to [HtspGenerationTermination.Failed]
     * and everything else to [HtspGenerationTermination.Disconnected]. This best-effort fallback
     * cannot check ownership, latch past results, or attribute an error precisely to a generation.
     */
    public suspend fun awaitTermination(generation: HtspConnectionGeneration): HtspGenerationTermination {
        currentCoroutineContext().ensureActive()
        val state = connectionState.first {
            it !is HtspConnectionState.Connected || it.connection.generation !== generation
        }
        return if (state is HtspConnectionState.Error) HtspGenerationTermination.Failed(state.failure)
        else HtspGenerationTermination.Disconnected
    }

    /**
     * Metadata and connection failures with replay zero and one bounded queue per collector.
     * Queue saturation never blocks the reader; consumers on an immediate dispatcher such as
     * `Dispatchers.Unconfined` may run on library threads and must not block. Immutable
     * decoded events may be shared across queues. A full queue drops newest metadata and reports [HtspTransportEvent.MetadataOverflow]
     * before later admitted events; connection failures and overflow markers are unbudgeted.
     * At most one pending overflow marker per generation coalesces only that generation's losses.
     * [HtspTransportEvent.MalformedMetadata] reports skipped metadata without closing the connection;
     * stateful consumers must treat that generation's state as incomplete. Reports coalesce only
     * with the same generation's report at the queued tail, consuming one event slot and no frame
     * bytes. Once that tail is drained into a collector's chunk, a subsequent report needs its own
     * slot and may overflow while the drained report is still in flight. If full, the queue counts
     * their skipped messages in its overflow marker instead.
     * Budget defaults may change; pass [HtspEventBufferOptions] for fixed limits.
     * Recovery (including reconnect and full sync with backoff) belongs to the consumer.
     */
    public val events: Flow<HtspTransportEvent>

    /**
     * Metadata and connection failures in ordered, non-empty, read-only lists of at most
     * [maxBatchSize] events (default 512). The limit must be in `1..4096`; validation happens when
     * this function is called, before collection. Loosening this range in a later release is
     * compatible. Batches contain what is currently queued, without waiting to fill.
     * Each collection has replay zero and its own queue, with the
     * same capacity, byte budget, overflow and queued-tail malformed coalescing as [events].
     * Collect before enabling metadata. Cancellation propagates and unregisters the collector.
     *
     * A batch never spans a generation; batches split wherever the event generation changes.
     * [HtspTransportEvent.MetadataOverflow] and [HtspTransportEvent.MalformedMetadata] stay in
     * order inside the lists. Overflow counts are snapshotted under the queue lock before
     * emission. The library never mutates an emitted list or retains it in the queue buffer.
     *
     * All entries of a batch leave the count and byte budgets immediately before `emit(batch)`,
     * even if delivery suspends. While the collector processes it, the queue keeps filling and
     * can overflow; losses are reported in order before later admitted events, as with [events].
     * Consumer-held batches are outside those budgets. While processing one batch, allow for
     * the queue's budget plus one consumer-owned batch of at most [maxBatchSize] entries.
     * An empty queue waits on the connection's I/O dispatcher, as [events] does; consumer code
     * runs outside the queue lock.
     *
     * The default implementation for custom connections wraps each [events] emission in a
     * singleton list, inheriting that implementation's queue and lifecycle behavior.
     * An implementation that overrides [events], such as an interceptor delegating with `by`,
     * should override [eventBatches] too; otherwise batch consumers can bypass that override.
     */
    public fun eventBatches(maxBatchSize: Int = 512): Flow<List<HtspTransportEvent>> {
        require(maxBatchSize in 1..MAXIMUM_METADATA_BATCH_SIZE) {
            "maxBatchSize must be in 1..$MAXIMUM_METADATA_BATCH_SIZE"
        }
        return events.map { listOf(it) }
    }

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
     * a started ordinary frame finishes first, leaving the connection live. This takes
     * at most the remaining request budget plus scheduling delay. A write that cannot
     * finish in that budget ends the connection with a timeout failure; the caller still
     * gets cancellation. Cancelling hello/authenticate after any part is written retires
     * the connection immediately.
     * A very large [timeoutMs] can delay cancellation if the peer stops reading.
     * Other requests queue behind that unfinished write.
     * A stale [expectedGeneration] before dispatch returns [HtspResult.TransportUnavailable].
     * A closed client also returns TransportUnavailable; inspect [connectionState] for Closed.
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
     * A call admitted after terminal close returns Failed(CLOSED).
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
     * Both successful variants publish [HtspConnectionState.Closed]; a stale expected generation
     * leaves state untouched. Use [disconnect] for a reconnectable Disconnected state.
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
        protocolVersion: Long?,
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
    protocolVersion: Long,
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

internal data class `HtspCapturedGeneration-internal`(
    val token: HtspConnectionGeneration,
    val protocolVersion: Long?,
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
