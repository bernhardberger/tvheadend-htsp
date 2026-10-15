package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionState
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportFailureKind
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.requests.getDiskSpace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// region connect
suspend fun connectOrNull(connection: HtspConnection, endpoint: HtspEndpoint): HtspLiveConnection? =
    when (val outcome = connection.connect(endpoint, HtspConnectOptions(responseTimeoutMs = 10_000L))) {
        is HtspConnectOutcome.Connected -> outcome.connection
        is HtspConnectOutcome.Failed -> {
            println("Could not connect: ${outcome.failure.kind}")
            null
        }
    }
// endregion

// region live-connection
fun describeServer(live: HtspLiveConnection): String {
    val facts = live.serverFacts
    return "${facts.serverName} ${facts.serverVersion}, HTSP ${live.protocolVersion}, " +
        "recording access: ${live.dvrAccess}"
}
// endregion

// region failure-kind
fun adviceFor(kind: HtspTransportFailureKind): String = when (kind) {
    HtspTransportFailureKind.AUTHENTICATION_REJECTED -> "Check the user name and password."
    HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION -> "The server is older than HTSP 36."
    HtspTransportFailureKind.INCOMPATIBLE_SERVER -> "The server does not speak HTSP as expected."
    HtspTransportFailureKind.HOST_NOT_FOUND,
    HtspTransportFailureKind.CONNECTION_REFUSED,
    HtspTransportFailureKind.NETWORK_UNREACHABLE,
    HtspTransportFailureKind.CONNECTION_TIMEOUT,
    -> "Check the host, the port and the network; retry later."
    HtspTransportFailureKind.CLOSED,
    HtspTransportFailureKind.SUPERSEDED,
    -> "This client ended the attempt itself."
    else -> "The connection failed; retry later."
}
// endregion

// region failure-policy
enum class FailureAction { CHECK_ACCESS, RETRY_LATER, RECONNECT, UNSUPPORTED, REJECTED, REVIEW_SERVER_STATE }

fun actionFor(failure: HtspFailure): FailureAction = when (failure) {
    HtspResult.AccessDenied -> FailureAction.CHECK_ACCESS
    HtspResult.ConnectionLimit, HtspResult.Timeout -> FailureAction.RETRY_LATER
    HtspResult.TransportUnavailable -> FailureAction.RECONNECT
    HtspResult.NotSupported -> FailureAction.UNSUPPORTED
    HtspResult.MalformedReply -> FailureAction.REVIEW_SERVER_STATE
    is HtspResult.ServerError -> FailureAction.REJECTED
}
// endregion

// region fenced-request
suspend fun freeBytesOrNull(connection: HtspConnection, live: HtspLiveConnection): Long? =
    when (val result = connection.getDiskSpace(expectedGeneration = live.generation)) {
        is HtspResult.Ok -> result.value.freeBytes
        is HtspFailure -> null
    }
// endregion

// region watch-state
suspend fun awaitGenerationEnd(connection: HtspConnection, generation: HtspConnectionGeneration) {
    connection.connectionState.first { state ->
        (state as? HtspConnectionState.Connected)?.connection?.generation !== generation
    }
}
// endregion

// region reconnect
suspend fun keepConnected(
    connection: HtspConnection,
    endpoint: HtspEndpoint,
    onConnected: suspend (HtspLiveConnection) -> Unit,
) {
    var backoffMs = 1_000L
    while (true) {
        when (val outcome = connection.connect(endpoint)) {
            is HtspConnectOutcome.Connected -> {
                backoffMs = 1_000L
                onConnected(outcome.connection)
                awaitGenerationEnd(connection, outcome.connection.generation)
            }
            is HtspConnectOutcome.Failed -> when (outcome.failure.kind) {
                HtspTransportFailureKind.CLOSED,
                HtspTransportFailureKind.SUPERSEDED,
                HtspTransportFailureKind.AUTHENTICATION_REJECTED,
                HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION,
                HtspTransportFailureKind.INCOMPATIBLE_SERVER,
                -> return
                else -> Unit
            }
        }
        delay(backoffMs)
        backoffMs = minOf(backoffMs * 2, 60_000L)
    }
}
// endregion

// region shutdown
suspend fun runAndClose(ioDispatcher: CoroutineDispatcher, endpoint: HtspEndpoint) {
    val connection = createHtspConnection(ioDispatcher = ioDispatcher)
    try {
        val live = connectOrNull(connection, endpoint) ?: return
        println(describeServer(live))
        println("Free bytes: ${freeBytesOrNull(connection, live)}")
    } finally {
        connection.close()
    }
}
// endregion
