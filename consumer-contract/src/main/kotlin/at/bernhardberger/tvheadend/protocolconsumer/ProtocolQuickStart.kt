package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.connection.fold
import at.bernhardberger.tvheadend.htsp.connection.getOrElse
import at.bernhardberger.tvheadend.htsp.connection.getOrNull
import at.bernhardberger.tvheadend.htsp.connection.map
import at.bernhardberger.tvheadend.htsp.connection.onFailure
import at.bernhardberger.tvheadend.htsp.messages.HtspServerMessage
import at.bernhardberger.tvheadend.htsp.requests.GetEventsRequest
import at.bernhardberger.tvheadend.htsp.requests.getDiskSpace
import at.bernhardberger.tvheadend.htsp.requests.getProfiles
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProtocolFailurePolicy {
    CHECK_ACCESS,
    RETRY_LATER,
    RECONNECT,
    UNSUPPORTED,
    REJECTED,
}

data class ProtocolSnapshot(
    val freeBytes: Long?,
    val serverUnixTimeSeconds: Long?,
    val profileCount: Int?,
    val failures: List<ProtocolFailurePolicy>,
)

sealed interface ProtocolQuickStartOutcome {
    data class Connected(val snapshot: ProtocolSnapshot) : ProtocolQuickStartOutcome

    data object ConnectionFailed : ProtocolQuickStartOutcome
}

suspend fun runProtocolQuickStart(
    ioDispatcher: CoroutineDispatcher,
    endpoint: HtspEndpoint,
    epgChannelId: Long,
    epgMaximumEvents: Long,
    epgLanguage: String?,
    options: HtspConnectOptions = HtspConnectOptions(),
    onMetadataMessage: suspend (HtspServerMessage) -> Unit,
): ProtocolQuickStartOutcome = coroutineScope {
    val connection = createHtspConnection(ioDispatcher = ioDispatcher)
    val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
        connection.events
            .filterIsInstance<HtspTransportEvent.ServerMessage>()
            .collect { event -> onMetadataMessage(event.message) }
    }

    try {
        when (val connectOutcome = connection.connect(endpoint, options)) {
            is HtspConnectOutcome.Failed -> ProtocolQuickStartOutcome.ConnectionFailed
            is HtspConnectOutcome.Connected -> {
                val generation = connectOutcome.connection.generation
                val failures = mutableListOf<ProtocolFailurePolicy>()
                val eventsRequest = GetEventsRequest(
                    channelId = epgChannelId,
                    language = epgLanguage,
                    numFollowing = epgMaximumEvents,
                )
                connection.execute(eventsRequest, expectedGeneration = generation)
                    .onFailure { failure -> failures += policyFor(failure) }
                val diskSpace = connection.getDiskSpace(expectedGeneration = generation)
                    .onFailure { failure -> failures += policyFor(failure) }
                    .getOrNull()
                val serverUnixTimeSeconds = connection
                    .getSysTime(expectedGeneration = generation)
                    .fold(
                        onOk = { response -> response.unixTimeSeconds },
                        onFailure = { failure ->
                            failures += policyFor(failure)
                            null
                        },
                    )
                val profileCount = connection
                    .getProfiles(expectedGeneration = generation)
                    .map { response -> response.profiles?.size }
                    .getOrElse { failure ->
                        failures += policyFor(failure)
                        null
                    }
                ProtocolQuickStartOutcome.Connected(
                    ProtocolSnapshot(
                        freeBytes = diskSpace?.freeBytes,
                        serverUnixTimeSeconds = serverUnixTimeSeconds,
                        profileCount = profileCount,
                        failures = failures.toList(),
                    ),
                )
            }
        }
    } finally {
        withContext(NonCancellable) {
            eventCollector.cancelAndJoin()
            try {
                connection.disconnect()
            } finally {
                connection.close()
            }
        }
    }
}

private fun policyFor(failure: HtspFailure): ProtocolFailurePolicy = when (failure) {
    HtspResult.AccessDenied -> ProtocolFailurePolicy.CHECK_ACCESS
    HtspResult.ConnectionLimit -> ProtocolFailurePolicy.RETRY_LATER
    HtspResult.Timeout -> ProtocolFailurePolicy.RETRY_LATER
    HtspResult.TransportUnavailable -> ProtocolFailurePolicy.RECONNECT
    HtspResult.NotSupported -> ProtocolFailurePolicy.UNSUPPORTED
    HtspResult.ServerError -> ProtocolFailurePolicy.REJECTED
}
