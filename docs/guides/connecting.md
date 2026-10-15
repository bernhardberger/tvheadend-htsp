# Connecting, requests, and the connection lifecycle

This guide starts where the [README quick start](../../README.md#quick-start)
stops: it subscribes to server messages, routes requests through one connection
generation, maps every failure to a decision, and cleans up even when the
caller is cancelled.

## A complete example

This is the [`ProtocolQuickStart.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ProtocolQuickStart.kt) consumer fixture, which is
compiled against the staged artifact in CI.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ProtocolQuickStart.kt -->
```kotlin
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
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadataAwaitingInitialSync
import at.bernhardberger.tvheadend.htsp.requests.getDiskSpace
import at.bernhardberger.tvheadend.htsp.requests.getProfiles
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProtocolFailurePolicy {
    CHECK_ACCESS,
    RETRY_LATER,
    RECONNECT,
    UNSUPPORTED,
    REJECTED,
    REVIEW_SERVER_STATE,
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
    onTransportNotice: suspend (HtspTransportEvent) -> Unit,
): ProtocolQuickStartOutcome = coroutineScope {
    val connection = createHtspConnection(ioDispatcher = ioDispatcher)
    val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
        connection.events.collect { event ->
            when (event) {
                is HtspTransportEvent.ServerMessage -> onMetadataMessage(event.message)
                else -> onTransportNotice(event)
            }
        }
    }

    try {
        when (val connectOutcome = connection.connect(endpoint, options)) {
            is HtspConnectOutcome.Failed -> ProtocolQuickStartOutcome.ConnectionFailed
            is HtspConnectOutcome.Connected -> {
                val generation = connectOutcome.connection.generation
                val failures = mutableListOf<ProtocolFailurePolicy>()
                connection.enableAsyncMetadataAwaitingInitialSync(
                    epg = true,
                    language = epgLanguage,
                    expectedGeneration = generation,
                ).onFailure { failure -> failures += policyFor(failure) }
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
                        onOk = { response -> response.timeEpochSeconds },
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
    HtspResult.MalformedReply -> ProtocolFailurePolicy.REVIEW_SERVER_STATE // Do not blindly retry.
    is HtspResult.ServerError -> ProtocolFailurePolicy.REJECTED
}
```

## Handling failures

`ServerError.serverMessage` optionally carries untrusted server rejection text
for display, not a stable error code; `toString()` never renders it. Locally
detected failures have no server message. DVR refusals return `ServerError`;
an `Ok` DVR mutation acknowledges the request, not a completed state change.
Autorec and timerec updates always set the channel restriction: pass the
rule's current channel to keep it, or `HtspRecordingRuleChannel.Any` to clear it.

## Connection lifecycle

Requests stale before dispatch return `TransportUnavailable`; completed replies
are returned unchanged even if their generation was replaced in flight.
`disconnect`/`close` return Boolean: true means this call changed state, false
means stale or no-op. Disconnect after close returns false; cleanup still completes
for cancelled callers. Connects superseded by another connect, disconnect, or owner close return
`Failed(SUPERSEDED)`. Only caller cancellation propagates as cancellation.
Both owner-global `close()` and successful `close(currentGeneration)` publish
terminal `Closed`; later connects return `Failed(CLOSED)`. A stale generation
close leaves the replacement untouched. Owner close emits no `ConnectionFailure`.
After failure, lifecycle `Error` remains until connect/disconnect/close, but
StateFlow may conflate transitions. Failed connects return their outcome to the
caller and sticky Error to observers, with no failure event. `ConnectionFailure`
reports only unsolicited failures of live generations and is authoritative.

`connectionState` is a native StateFlow: synchronous `value` reads may briefly
lag internal state, but publication is eventual and older snapshots never replace
newer ones. The library never invokes consumer code under its lifecycle or queue
locks. `Dispatchers.Unconfined` (or another immediate dispatcher) can run consumers
on library threads, including the transport reader: do not block them. A blocking
immediate consumer can delay delivery to others, including another thread publishing
state; blocking on another lifecycle operation from an immediate state consumer can
deadlock. Use a real dispatcher for consumers. `Connected(host, port, connection)`
atomically carries its live connection, including generation and handshake facts.
Route requests through that connection's `generation` fence.

See [API behavior](../public-api.md) for the outcome types and cancellation rules.
