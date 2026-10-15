@file:JvmName("HtspConnectionFactoryKt")

package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.messages.HtspMetadataMessage
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Network endpoint used by the typed HTSP transport. Endpoint and credentials are never rendered.
 *
 * @property host server host name or address to connect to
 * @property port TCP port of the server's HTSP service
 * @property username account name sent in `authenticate`; credentials are sent only when both username and password are non-blank, otherwise the server applies its anonymous rights
 * @property password account password used to answer the `authenticate` challenge; never sent in clear
 */
public class HtspEndpoint(
    public val host: String,
    public val port: Int,
    public val username: String = "",
    public val password: String = "",
) {
    override fun toString(): String =
        "HtspEndpoint(<redacted>)"
}

/** Minimum supported server protocol version. Raising it requires a major release; lowering it is compatible. */
public const val MINIMUM_HTSP_PROTOCOL_VERSION: Long = 36

/**
 * Connection timeouts and socket bounds, all durations in milliseconds.
 *
 * @property connectTimeoutMs maximum time to wait for the TCP connect; in `1..Int.MAX_VALUE`
 * @property responseTimeoutMs maximum time to wait for each handshake reply; positive
 * @property socketReadTimeoutMs socket read timeout, after which a blocked read fails as a timeout; in `1..Int.MAX_VALUE`
 * @property socketBufferBytes size in bytes of the buffered socket input and output streams; in `1..16777216`
 * @property requestedProtocolVersion HTSP version offered in `hello` (`htspversion`); the connection uses the lower of it and the server's version
 * @property forceReconnect `true` replaces a live connection to the same endpoint instead of reusing it
 */
public data class HtspConnectOptions(
    public val connectTimeoutMs: Long = 10_000L,
    public val responseTimeoutMs: Long = 5_000L,
    public val socketReadTimeoutMs: Long = 60_000L,
    public val socketBufferBytes: Int = 64 * 1_024,
    public val requestedProtocolVersion: Long = 44,
    public val forceReconnect: Boolean = false,
) {
    init {
        require(connectTimeoutMs in 1L..Int.MAX_VALUE.toLong()) {
            "connectTimeoutMs must be in 1..Int.MAX_VALUE for the socket API"
        }
        require(responseTimeoutMs > 0L) { "responseTimeoutMs must be positive" }
        require(socketReadTimeoutMs in 1L..Int.MAX_VALUE.toLong()) {
            "socketReadTimeoutMs must be in 1..Int.MAX_VALUE for the socket API"
        }
        require(socketBufferBytes in 1..16 * 1024 * 1024) { "socketBufferBytes must be in 1..16777216" }
        require(requestedProtocolVersion in MINIMUM_HTSP_PROTOCOL_VERSION..0xffff_ffffL) {
            "requestedProtocolVersion must be in $MINIMUM_HTSP_PROTOCOL_VERSION..4294967295"
        }
    }
}

/**
 * Stable failure categories; implementation exceptions never cross the transport seam.
 *
 * Minor releases may add entries; keep an `else` branch when matching.
 */
public enum class HtspTransportFailureKind {
    /** The server rejected the supplied credentials. */
    AUTHENTICATION_REJECTED,

    /** The host name did not resolve. */
    HOST_NOT_FOUND,

    /** The server refused the TCP connection. */
    CONNECTION_REFUSED,

    /** A connect, read, or request timeout expired. */
    CONNECTION_TIMEOUT,

    /** No network route to the host exists. */
    NETWORK_UNREACHABLE,

    /** The server violated the supported protocol, rather than merely reporting an older version. */
    INCOMPATIBLE_SERVER,

    /** Any other transport failure not covered by a more specific kind. */
    TRANSPORT_UNAVAILABLE,

    /** The owner terminally closed this client before the operation was admitted. */
    CLOSED,
    /** A newer connection attempt, disconnect, or owner close superseded this attempt. */
    SUPERSEDED,
    /** The server reports a protocol version below [MINIMUM_HTSP_PROTOCOL_VERSION]. */
    UNSUPPORTED_SERVER_VERSION,
}

/**
 * Bounded transport-failure classification with no implementation exception payload.
 *
 * @property kind category of the failure
 */
public data class HtspTransportFailure(
    public val kind: HtspTransportFailureKind,
)

/**
 * One live, opaque protocol generation and its bounded handshake observations.
 *
 * @property generation identity of this live protocol generation
 * @property protocolVersion negotiated HTSP version, the lower of the requested and server versions
 * @property dvrAccess whether authentication granted `dvr` access; `null` when the reply omitted it or a later `authenticate` sent through [HtspConnection.execute] was denied
 * @property serverFacts server identity and access observations from the handshake
 */
public data class HtspLiveConnection(
    public val generation: HtspConnectionGeneration,
    public val protocolVersion: Long,
    public val dvrAccess: Boolean?,
    public val serverFacts: HtspServerFacts,
)

/** Typed outcome of one connection attempt. */
public sealed interface HtspConnectOutcome {
    /**
     * A connection attempt that established one live opaque generation.
     *
     * @property connection the established live connection
     */
    public data class Connected(public val connection: HtspLiveConnection) : HtspConnectOutcome
    /**
     * A connection attempt reduced to one bounded transport failure.
     *
     * @property failure why the attempt failed
     */
    public data class Failed(public val failure: HtspTransportFailure) : HtspConnectOutcome
}

/**
 * Ordered typed observations from one admitted transport generation.
 * Minor releases may add subtypes; keep an `else` branch.
 */
public sealed interface HtspTransportEvent {
    /** Generation that produced this event. */
    public val generation: HtspConnectionGeneration?

    /**
     * One typed asynchronous server message observed on its source generation.
     *
     * @property message the decoded server message
     */
    public data class ServerMessage(
        public val message: HtspMetadataMessage,
        override val generation: HtspConnectionGeneration,
        /** One-based reader-observed position within [generation]. */
        public val messageSequence: Long,
    ) : HtspTransportEvent {
        init {
            require(messageSequence > 0L) { "messageSequence must be positive" }
        }
    }

    /**
     * An unsolicited failure of an established live generation, never a failed connect attempt.
     *
     * @property failure why the live generation failed
     */
    public data class ConnectionFailure(
        public val failure: HtspTransportFailure,
        override val generation: HtspConnectionGeneration,
    ) : HtspTransportEvent

    /**
     * Metadata discarded for this collector; recover the consumer's state explicitly.
     * [droppedCount] counts losses only for [generation]. Losses after reconnect start
     * a separate marker; each generation has at most one pending marker.
     * A discarded [MalformedMetadata] contributes its skipped-message count.
     */
    public data class MetadataOverflow(
        override val generation: HtspConnectionGeneration,
        public val droppedCount: Long,
    ) : HtspTransportEvent {
        init {
            require(droppedCount > 0L) { "droppedCount must be positive" }
        }
    }

    /**
     * Malformed metadata skipped on [generation], whose consumer state is now incomplete.
     * The connection stays live. Carries only [generation] and the positive skipped-message
     * [count], never method names, fields, identifiers or server text; [toString] redacts generation.
     * Adjacent pending reports for the same generation coalesce without crossing other queued
     * events. Each report occupies one metadata event slot and retains no frame bytes.
     * If it cannot fit, [MetadataOverflow] counts the loss before later admitted messages.
     */
    public data class MalformedMetadata(
        override val generation: HtspConnectionGeneration,
        public val count: Long,
    ) : HtspTransportEvent {
        init {
            require(count > 0L) { "count must be positive" }
        }

        override fun toString(): String = "MalformedMetadata(generation=<redacted>, count=$count)"
    }
}

/**
 * Per-collector metadata and per-subscription queue budgets. Bytes account for retained
 * server frame bodies, not exact heap usage. Subscription packets and controls share both
 * budgets. Malformed-metadata reports consume one metadata event slot and no frame bytes;
 * overflow/drop markers and connection failures are unbudgeted. Defaults may change in minor
 * releases; pass explicit values for fixed limits. There is no connection-wide cap or time ceiling.
 *
 * @property metadataQueueBytes retained server frame bytes allowed per metadata collector; in `1048576..1073741824`
 * @property metadataQueueEvents events allowed per metadata collector; in `256..1048576`
 * @property subscriptionQueueBytes retained server frame bytes allowed per subscription; in `1048576..1073741824`
 * @property subscriptionQueueEvents events allowed per subscription; in `256..1048576`
 */
@ConsistentCopyVisibility
public data class HtspEventBufferOptions private constructor(
    public val metadataQueueBytes: Long,
    public val metadataQueueEvents: Int,
    public val subscriptionQueueBytes: Long,
    public val subscriptionQueueEvents: Int,
    private val evolution: Unit,
) {
    /** Creates validated queue budgets for a connection's event streams. */
    public constructor(
        metadataQueueBytes: Long = 8L * 1024 * 1024,
        metadataQueueEvents: Int = 8192,
        subscriptionQueueBytes: Long = 8L * 1024 * 1024,
        subscriptionQueueEvents: Int = 8192,
    ) : this(metadataQueueBytes, metadataQueueEvents, subscriptionQueueBytes, subscriptionQueueEvents, Unit)

    /** Explicit no-argument bridge retained for future binary-compatible evolution. */
    @Deprecated("Retained for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor() : this(8L * 1024 * 1024, 8192, 8L * 1024 * 1024, 8192, Unit)

    init {
        require(metadataQueueBytes in 1_048_576L..1_073_741_824L)
        require(subscriptionQueueBytes in 1_048_576L..1_073_741_824L)
        require(metadataQueueEvents in 256..1_048_576)
        require(subscriptionQueueEvents in 256..1_048_576)
    }

    /** Replaces selected budgets, applying the same validation as construction. */
    public fun copy(
        metadataQueueBytes: Long = this.metadataQueueBytes,
        metadataQueueEvents: Int = this.metadataQueueEvents,
        subscriptionQueueBytes: Long = this.subscriptionQueueBytes,
        subscriptionQueueEvents: Int = this.subscriptionQueueEvents,
    ): HtspEventBufferOptions = HtspEventBufferOptions(
        metadataQueueBytes, metadataQueueEvents, subscriptionQueueBytes, subscriptionQueueEvents,
    )
}

/**
 * Creates the factory-owned typed connection using the supplied dispatcher and diagnostics policy.
 * [socketFactory] must return a fresh, initially unconnected socket for each connection attempt.
 */
public fun createHtspConnection(
    ioDispatcher: CoroutineDispatcher,
    clientIdentity: HtspClientIdentity = HtspClientIdentity.Default,
    logger: HtspLogger = HtspLogger.None,
    socketFactory: () -> Socket = ::Socket,
    eventBufferOptions: HtspEventBufferOptions = HtspEventBufferOptions(),
): HtspConnection = HtspService(
    ioDispatcher = ioDispatcher,
    clientIdentity = clientIdentity,
    logger = logger,
    socketFactory = socketFactory,
    eventBufferOptions = eventBufferOptions,
)

internal class HtspIncompatibleServerException : Exception()

internal class HtspUnsupportedServerVersionException : Exception()

internal class HtspAuthenticationRejectedException : Exception()

internal fun typedTransportFailure(error: Throwable): HtspTransportFailure {
    val chain = generateSequence(error as Throwable?) { current -> current.cause }.toList()
    val kind = when {
        chain.any { it is TerminalLifecycleClosedException } -> HtspTransportFailureKind.CLOSED
        chain.any { it is UnknownHostException } -> HtspTransportFailureKind.HOST_NOT_FOUND
        chain.any { it is NoRouteToHostException } -> HtspTransportFailureKind.NETWORK_UNREACHABLE
        chain.any { it is HtspIncompatibleServerException } ->
            HtspTransportFailureKind.INCOMPATIBLE_SERVER
        chain.any { it is HtspUnsupportedServerVersionException } ->
            HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION
        chain.any { it is SocketTimeoutException || it is HtspRequestTimeoutException } ->
            HtspTransportFailureKind.CONNECTION_TIMEOUT
        chain.any { it is ConnectException } -> HtspTransportFailureKind.CONNECTION_REFUSED
        chain.any { it is HtspAuthenticationRejectedException } ->
            HtspTransportFailureKind.AUTHENTICATION_REJECTED
        else -> HtspTransportFailureKind.TRANSPORT_UNAVAILABLE
    }
    return HtspTransportFailure(kind)
}

internal inline fun <T> transportOutcome(block: () -> T): HtspResult<T> = try {
    HtspResult.Ok(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: SocketTimeoutException) {
    HtspResult.Timeout
} catch (_: HtspRequestTimeoutException) {
    HtspResult.Timeout
} catch (_: Exception) {
    HtspResult.TransportUnavailable
}
