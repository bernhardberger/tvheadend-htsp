@file:JvmName("HtspConnectionFactoryKt")

package at.bernhardberger.tvheadend.htsp

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/** Network endpoint used by the typed HTSP transport. Credentials are never rendered. */
public class HtspEndpoint(
    public val host: String,
    public val port: Int,
    public val username: String = "",
    public val password: String = "",
) {
    override fun toString(): String =
        "HtspEndpoint(host=$host, port=$port, username=$username, password=<redacted>)"
}

/** Connection timeouts and socket bounds, all durations in milliseconds. */
public data class HtspConnectOptions(
    public val connectTimeoutMs: Int = 10_000,
    public val responseTimeoutMs: Long = 5_000L,
    public val socketReadTimeoutMs: Int = 60_000,
    public val socketBufferBytes: Int = 64 * 1_024,
    public val requestedProtocolVersion: Int = 43,
    public val forceReconnect: Boolean = false,
) {
    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(responseTimeoutMs > 0L) { "responseTimeoutMs must be positive" }
        require(socketReadTimeoutMs > 0) { "socketReadTimeoutMs must be positive" }
        require(socketBufferBytes > 0) { "socketBufferBytes must be positive" }
        require(requestedProtocolVersion > 0) { "requestedProtocolVersion must be positive" }
    }
}

/** Stable failure categories; implementation exceptions never cross the transport seam. */
public enum class HtspTransportFailureKind {
    AUTHENTICATION_REJECTED,
    PERMISSION_DENIED,
    HOST_NOT_FOUND,
    CONNECTION_REFUSED,
    CONNECTION_TIMEOUT,
    NETWORK_UNREACHABLE,
    INCOMPATIBLE_SERVER,
    ZERO_CHANNELS,
    TRANSPORT_UNAVAILABLE,
}

public data class HtspTransportFailure(
    public val kind: HtspTransportFailureKind,
)

/** One live, opaque protocol generation and its bounded handshake observations. */
public data class HtspLiveConnection(
    public val generation: HtspConnectionGeneration,
    public val protocolVersion: Int?,
    public val dvrAccess: Boolean?,
    public val serverFacts: HtspServerFacts,
)

public sealed interface HtspConnectOutcome {
    public data class Connected(public val connection: HtspLiveConnection) : HtspConnectOutcome
    public data class Failed(public val failure: HtspTransportFailure) : HtspConnectOutcome
}

/** Ordered typed observations from one admitted transport generation. */
public sealed interface HtspTransportEvent {
    public val generation: HtspConnectionGeneration?

    public data class ServerMessage(
        public val message: HtspServerMessage,
        override val generation: HtspConnectionGeneration,
    ) : HtspTransportEvent

    public data class ConnectionFailure(
        public val failure: HtspTransportFailure,
        override val generation: HtspConnectionGeneration?,
    ) : HtspTransportEvent
}

public fun createHtspConnection(
    ioDispatcher: CoroutineDispatcher,
    clientIdentity: HtspClientIdentity = HtspClientIdentity.Default,
    logger: HtspLogger = HtspLogger.None,
): HtspConnection = HtspService(
    ioDispatcher = ioDispatcher,
    clientIdentity = clientIdentity,
    logger = logger,
)

internal fun typedTransportFailure(error: Throwable): HtspTransportFailure {
    val chain = generateSequence(error as Throwable?) { current -> current.cause }.toList()
    val kind = when {
        chain.any { it is MetadataPermissionDeniedException } ->
            HtspTransportFailureKind.PERMISSION_DENIED
        chain.any { it is UnknownHostException } -> HtspTransportFailureKind.HOST_NOT_FOUND
        chain.any { it is NoRouteToHostException } -> HtspTransportFailureKind.NETWORK_UNREACHABLE
        chain.any { it is SocketTimeoutException || it is HtspRequestTimeoutException } ->
            HtspTransportFailureKind.CONNECTION_TIMEOUT
        chain.any { it is ConnectException } -> HtspTransportFailureKind.CONNECTION_REFUSED
        chain.any { throwable ->
            throwable is IllegalStateException &&
                throwable.message?.contains("auth", ignoreCase = true) == true
        } -> HtspTransportFailureKind.AUTHENTICATION_REJECTED
        else -> HtspTransportFailureKind.TRANSPORT_UNAVAILABLE
    }
    return HtspTransportFailure(kind)
}

internal inline fun <T> transportOutcome(block: () -> T): HtspResult<T> = try {
    HtspResult.Ok(block())
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
