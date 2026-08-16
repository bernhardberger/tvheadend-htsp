# HTSP for Kotlin/JVM

A Kotlin/JVM client library for the HTSP protocol spoken by
[TVHeadend](https://github.com/tvheadend/tvheadend) servers. It gives you the
protocol as typed Kotlin requests, responses, and server messages instead of
raw method strings and maps, on top of a coroutines-based transport.

The public API lives under `at.bernhardberger.tvheadend.htsp` in five packages:

- `connection`: connect, authenticate, observe server push messages, and manage
  the connection lifecycle.
- `requests`: the typed catalog of all 39 client-to-server HTSP methods, with
  generated convenience functions on the connection.
- `messages`: the 30 typed server-to-client messages, delivered through a
  Kotlin `Flow`.
- `wire`: the binary framing and protocol value types underneath.
- `jsonapi`: an opt-in bridge to TVHeadend's separate HTTP JSON API.

The only runtime dependency is `kotlinx-coroutines-core`. The artifact contains
no Android, Media3, or decoder code.

## Requirements

- A Java 17 or newer runtime. The artifact is compiled for JVM 17.
- A TVHeadend server. The request catalog is derived from TVHeadend's HTSP v44
  sources, and the client negotiates the protocol version with the server
  during the handshake.

## Installation

The Gradle dependency uses the exact immutable release coordinate
`at.bernhardberger.tvheadend:htsp:0.1.0`. Availability from Maven Central or another public
repository must be independently verified; this repository does not claim that
publication has occurred.

The Gradle dependency is:

<!-- dependency-static:htsp -->
```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:htsp:0.1.0")
}
```

The API is provisional during the major-zero line and may change; see
[versioning and compatibility](docs/versioning.md), [release
policy](docs/releasing.md), and the [release change history](CHANGELOG.md).

## Quick start

```kotlin
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.connection.getOrNull
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import kotlinx.coroutines.Dispatchers

suspend fun main() {
    val connection = createHtspConnection(ioDispatcher = Dispatchers.IO)
    try {
        val endpoint = HtspEndpoint("tvh.example.com", 9982, username = "user", password = "secret")
        when (val outcome = connection.connect(endpoint)) {
            is HtspConnectOutcome.Failed ->
                println("Could not connect: ${outcome.failure.kind}")
            is HtspConnectOutcome.Connected -> {
                val serverTime = connection.getSysTime().getOrNull()
                println("Server time: ${serverTime?.unixTimeSeconds}")
            }
        }
    } finally {
        connection.close()
    }
}
```

`connect` performs the handshake and authentication and reports the outcome as
a value. Every request works the same way: the suspending call returns a typed
outcome with explicit success and failure cases, so a "no access" answer or a
timeout is something you handle, not something you catch. Cancelling the
calling coroutine cancels the call. See
[API behavior: outcomes, errors, and cancellation](docs/public-api.md) for the
details.

## A complete example

This example covers server messages, connection generations, request failures,
and cleanup; it is also available as a [standalone consumer
fixture](consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ProtocolQuickStart.kt).

<!-- source-static:htsp -->
```kotlin
package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.connection.execute
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
    onServerMessage: suspend (HtspServerMessage) -> Unit,
): ProtocolQuickStartOutcome = coroutineScope {
    val connection = createHtspConnection(ioDispatcher = ioDispatcher)
    val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
        connection.events
            .filterIsInstance<HtspTransportEvent.ServerMessage>()
            .collect { event -> onServerMessage(event.message) }
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
```

## Documentation

- [API behavior: outcomes, errors, and cancellation](docs/public-api.md)
- [Versioning and compatibility](docs/versioning.md)
- [Release policy](docs/releasing.md)
- [Change history](CHANGELOG.md)
- [HTSP protocol reference](docs/htsp-protocol/README.md)
- [Documentation index](docs/README.md)

## License and attribution

This independently maintained GPLv3 library descends from
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It is not official TVHeadend software and is not affiliated with or endorsed by the
TVHeadend project; the TVHeadend name describes compatibility only. See
[LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and the
[licensing and attribution notes](docs/licensing.md).
