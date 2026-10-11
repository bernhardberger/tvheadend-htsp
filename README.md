# TVHeadend HTSP for Kotlin/JVM

[![CI](https://github.com/bernhardberger/tvheadend-htsp/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/bernhardberger/tvheadend-htsp/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/at.bernhardberger.tvheadend/htsp)](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp)
[![License: GPLv3](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

This Kotlin/JVM client library provides typed requests, responses, and server
messages for the HTSP protocol used by
[TVHeadend](https://github.com/tvheadend/tvheadend) servers. Callers use the
coroutine-based typed API rather than raw method names and maps.

The public API lives under `at.bernhardberger.tvheadend.htsp` in five packages:

- `connection`: connect, authenticate, observe server push messages, and manage
  the connection lifecycle.
- `requests`: the typed catalog of all 39 client-to-server HTSP methods, with
  convenience functions on the connection.
- `messages`: the 30 typed server-to-client messages, split between a global
  metadata flow and ordered per-subscription flows.
- `wire`: the binary framing and protocol value types underneath.
- `jsonapi`: an opt-in bridge to TVHeadend's separate HTTP JSON API.

The artifact uses Kotlin's standard library and `kotlinx-coroutines-core` at
runtime. It contains no Android, Media3, or decoder code.

Programme credits, HbbTV applications, and recording-file stream metadata have
immutable typed representations. Packet payloads support direct copying into a
caller-owned `ByteBuffer` through `HtspBinary.copyInto`, without a temporary array.

## Requirements

- A Java 17 or newer runtime. The artifact is compiled for JVM 17.
- A TVHeadend server reporting HTSP 36 or newer (HTSP 36 first appeared upstream
  in 2021-08). Older servers are rejected before authentication with
  `UNSUPPORTED_SERVER_VERSION`. Requested versions must also be at least 36.
  Servers at v36–v43 omit newer fields, which are nullable in the typed API.
  The typed catalog covers HTSP methods through protocol
  v44; the client requests protocol v44 by default and the server negotiates
  downward during the handshake.

## Installation

The exact immutable release coordinate is
`at.bernhardberger.tvheadend:htsp:1.0.0-beta.1`. It is available from
[Maven Central](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp/1.0.0-beta.1),
with the [repository files](https://repo1.maven.org/maven2/at/bernhardberger/tvheadend/htsp/1.0.0-beta.1/)
available directly.

The Gradle dependency is:

<!-- dependency-static:htsp -->
```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:htsp:1.0.0-beta.1")
}
```

`1.0.0-beta.1` is a pre-release of `1.0.0`; its API may still change before
the final release. See
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
                println("Server time: ${serverTime?.timeEpochSeconds}")
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

This example covers metadata messages, connection generations, request failures,
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

`ServerError.serverMessage` optionally carries untrusted server rejection text
for display, not a stable error code; `toString()` never renders it. Locally
detected failures have no server message. DVR refusals return `ServerError`;
an `Ok` DVR mutation acknowledges the request, not a completed state change.
Autorec/timerec updates that omit `channel` clear the server's channel selection.

Requests stale before dispatch return `TransportUnavailable`; completed replies
are returned unchanged even if their generation was replaced in flight.
`disconnect`/`close` return Boolean: true means this call changed state, false
means stale or no-op. Disconnect after close returns false; cleanup still completes
for cancelled callers. Connects superseded by another connect, disconnect, or owner close return
`Failed(SUPERSEDED)`. Only caller cancellation propagates as cancellation.
After failure, lifecycle `Error` remains until connect/disconnect/close, but
StateFlow may conflate transitions. Failed connects return their outcome to the
caller and sticky Error to observers, with no failure event. `ConnectionFailure`
reports only unsolicited failures of live generations and is authoritative.

The state properties are native StateFlows: synchronous `value` reads may briefly
lag internal state, but publication is eventual and older snapshots never replace
newer ones. The library never invokes consumer code under its lifecycle or queue
locks. `Dispatchers.Unconfined` (or another immediate dispatcher) can run consumers
on library threads, including the transport reader: do not block them. A blocking
immediate consumer can delay delivery to others, including another thread publishing
state; blocking on another lifecycle operation from an immediate state consumer can
deadlock. Use a real dispatcher for consumers. `connectionState` and `liveConnection`
are not an atomic read pair: route requests through `liveConnection.value` and its
`generation`, and never `!!` it because `connectionState` read `Connected`.

The `events` flow has an independent bounded queue per collector. Immutable
decoded messages may be shared; queue saturation never blocks the reader,
RPC replies, or other collectors. `HtspEventBufferOptions` on
`createHtspConnection` defaults to 8 MiB/8192 events per metadata collector and
per subscription; pass explicit budgets if their numbers must stay fixed.
Bytes count retained frame bodies, not exact heap. The queues drop newest
metadata and report a coalesced `MetadataOverflow`; failure and overflow markers
are unbudgeted and never dropped. Counts coalesce only within a generation:
losses after reconnect start a new marker, leaving the old marker in order.
Handle these in `onTransportNotice`, rather
than filtering them away. The library never reconnects or re-requests data.

A full EPG sync (roughly 150 channels × 7–14 days: 26k–84k events, 15–100 MB)
can overflow a slow consumer; XMLTV imports also produce `eventUpdate` bursts.
Consider `epgMaxTimeEpochSeconds`, `epg = false` plus paged `getEvents`, and
consumer-side batching. After overflow, do not wait for `initialSyncCompleted`,
which is the last sync message and may be dropped. A second `enableAsyncMetadata`
only resends EPG. Full recovery is consumer-owned reconnect and full sync with
backoff. The initial-sync helper returns `TransportUnavailable` promptly for its
own generation's overflow: the transport may still be live, but this sync is incomplete.

One TCP stream still imposes head-of-line ordering. Server `queueDepthBytes` is
separate from the client queues. Approximate worst-case memory is the sum of
all budgets plus one maximum frame (up to 32 MiB) per subscription plus the
largest metadata queue. This excludes heap and decoded-object overhead, not an
exact heap cap. There is no connection-wide cap or time ceiling.

For a live subscription, start collecting `connection.subscriptionEvents(id)` before
sending `subscribe` with that id, using `launch(start = CoroutineStart.UNDISPATCHED)`
so registration has run. Each id can be collected once per connection
generation; a nonlive collection instead terminates with `GENERATION_LOST`.
A subscribe without an active collector is rejected before it
reaches the server. The ordered stream reports packet pressure or a rejected
malformed packet with a trustworthy subscription id as `Dropped`; discard video
until the next keyframe after a drop. Packets and controls share the budgets.
An oversized packet is admitted after evicting other packets; oversized metadata
is dropped. Control overflow drains the queue then delivers
`Terminated(CONSUMER_OVERFLOW)`, affecting only that subscription. The consumer
must send `unsubscribe`; the library never does so automatically. An untrustworthy
packet envelope closes the incompatible transport. `Stopped` is an interruption:
keep collecting because the same stream can receive another `Started`. A successful
unsubscribe acknowledgement drains and completes the stream; a server refusal of
`subscribe`, generation loss or transport loss ends it with `Terminated`.

To request a position near the live edge without invoking the exact
`subscriptionLive` wire operation, retain an observed `Timeshift` event from the
same subscription generation and call `subscriptionSkipNearLive` with the
matching `SubscriptionTimestampClock` and an explicit positive margin. The
helper sends one normal absolute skip to `status.end - margin`; it does not
choose a default margin, fall back to `subscriptionLive`, guarantee exact live
mode, or wait for the asynchronous result. Serialize navigation and use the
ordered `Timeshift` and `Skipped` events to establish the outcome.

Mux packet `decodingTimeUs`, `presentationTimeUs`, and `durationUs` values are
always microseconds. The connection applies the native or 90 kHz clock selected
by the matching subscribe request, including packets delivered before its
acknowledgement. `frameType` is ASCII I/P/B, or `-1` when absent or sent as
wire zero or the explicit unknown sentinel.

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
