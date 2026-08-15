# HTSP for Kotlin/JVM

## Quick Start

This provisional coordinate is not externally published by this repository.
P3-E1 verifies the dependency and source snippets below against the moved
`consumer-contract` files using static, offline checks. It does **not** resolve
the coordinate or compile an independent consumer; that first isolated compile
and its staging/artifact evidence belong to P3-E2.

<!-- dependency-static:htsp -->
```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:htsp:0.1.0-alpha.1-SNAPSHOT")
}
```

The complete example keeps endpoint and credential values caller-owned, uses
typed round-trip outcomes, preserves cancellation, and closes its connection.
It is byte-identical to
[`consumer-contract/.../ProtocolQuickStart.kt`](consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ProtocolQuickStart.kt).

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

## Public API boundary

The artifact is `at.bernhardberger.tvheadend:htsp` and the root project is
`htsp`. Under `at.bernhardberger.tvheadend.htsp`, production API is confined to
exactly five shallow packages:

- `connection`: transport lifecycle, typed outcomes, and generation fencing;
- `requests`: the finite typed client request catalog and conveniences;
- `messages`: finite typed asynchronous server messages and dispatch;
- `wire`: framing and defensive protocol value support; and
- `jsonapi`: the explicit opt-in bridge to TVHeadend's separate JSON API.

`HtspConnection.execute` accepts an already-constructed request from the finite
catalog. Public suspending server round trips report failure through
`HtspConnectOutcome` or `HtspResult`; cancellation remains cancellation. The
artifact has exactly one declared production dependency,
`kotlinx-coroutines-core`, and has no Android, Media3, native decoder, logging,
network-client, application, or other SDK-module dependency.

The pinned HTSP v44 inventory, generated matrix, reviewed surface catalog, and
generator guidance are [repository engineering evidence](docs/htsp-protocol/README.md),
not a support, completeness, stability, release, or distribution promise.

## License, lineage, and status

This is an independently maintained GPLv3 library that descends from
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It incorporates
predecessor work and is not wholly original. The standalone repository begins
with the HTSP protocol extraction baseline instead of embedding the predecessor
application's unrelated Git history.

It is not official TVHeadend software and is not affiliated with or endorsed by
the TVHeadend project. The TVHeadend name describes compatibility only. There is
no external publication, support, release-readiness, or distribution claim.
See the [licensing and attribution authority](docs/licensing.md), `NOTICE.md`,
and `LICENSE`.
