# Tvheadend HTSP for Kotlin/JVM

[![CI](https://github.com/bernhardberger/tvheadend-htsp/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/bernhardberger/tvheadend-htsp/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/at.bernhardberger.tvheadend/htsp)](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp)
[![javadoc](https://javadoc.io/badge2/at.bernhardberger.tvheadend/htsp/javadoc.svg)](https://javadoc.io/doc/at.bernhardberger.tvheadend/htsp)
[![License: GPLv3](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

An unofficial Kotlin/JVM client for HTSP, the binary protocol that
[Tvheadend](https://github.com/tvheadend/tvheadend) servers use for channels,
EPG (electronic programme guide) data, DVR (digital video recorder) recordings
and live streams. You work with typed requests, replies and server messages on
coroutines and flows instead of raw method names and field maps.

Every server round trip returns a typed outcome. An access denial, a timeout or
a lost connection is a value you handle, not an exception you catch, and
cancelling the calling coroutine cancels the call.

The public API lives under `at.bernhardberger.tvheadend.htsp` in six packages:

- `connection`: connect, authenticate, observe the connection state and server
  events, disconnect and close.
- `requests`: typed requests for 38 of the 39 client-to-server HTSP methods,
  with suspending convenience functions on the connection. The 39th, `api`, is
  in `jsonapi`.
- `messages`: typed messages for the 30 server-to-client methods. Metadata
  arrives on one shared flow; each live subscription has its own ordered flow.
- `model`: the channel, tag, EPG event, DVR entry and recording-rule entities
  that messages and replies share.
- `wire`: `HtspBinary`, the read-only type for binary payloads. The framing
  itself is internal.
- `jsonapi`: an opt-in bridge that runs Tvheadend JSON API calls over the HTSP
  connection.

At runtime the artifact needs only the Kotlin standard library and
`kotlinx-coroutines-core`. It contains no Android, Media3 or decoder code.

## Requirements

- Java 17 or newer. The artifact is compiled for JVM 17.
- A Tvheadend server that reports HTSP 36 or newer; HTSP 36 first appeared
  upstream in August 2021. The client rejects older servers before
  authentication with `UNSUPPORTED_SERVER_VERSION`.
- The client asks for HTSP 44 by default, the newest version the typed API
  covers, and the server negotiates down. Fields a v36–v43 server doesn't send
  are nullable.

## Installation

The library is on
[Maven Central](https://central.sonatype.com/artifact/at.bernhardberger.tvheadend/htsp/1.0.0-beta.2)
as `at.bernhardberger.tvheadend:htsp:1.0.0-beta.2`:

```kotlin
dependencies {
    implementation("at.bernhardberger.tvheadend:htsp:1.0.0-beta.2")
}
```

`1.0.0-beta.2` is a pre-release of `1.0.0`, and its API may still change before
the final release. See [versioning and compatibility](docs/versioning.md) and
the [change history](CHANGELOG.md).

## Quick start

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/QuickStart.kt#quick-start -->
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

`connect` performs the handshake and authentication and returns its outcome.
`getSysTime` stands in for any request: it returns an `HtspResult`, and
`getOrNull()` turns a failure into `null`. Call `close()` when you are done with
the connection.

## Guides

- [Connecting, requests, and the connection lifecycle](docs/guides/connecting.md):
  authentication, request outcomes and failure handling, generations,
  reconnecting, disconnect and close.
- [Metadata sync and event streams](docs/guides/metadata.md): the initial sync,
  applying add, update and delete messages, overflow and malformed metadata, and
  large EPG syncs.
- [Live subscriptions, timeshift, and packets](docs/guides/subscriptions.md):
  subscribing, event end states, dropped packets, seeking near the live edge,
  and packet clocks.
- [Recordings, recording rules, and recording files](docs/guides/dvr.md):
  listing and scheduling recordings, autorec and timerec rules, acknowledged
  versus completed changes, and reading recording files.
- [The JSON API bridge](docs/guides/json-api.md): calling Tvheadend's JSON API
  over the HTSP connection, its value types, outcomes, and limits.
- [Errors and troubleshooting](docs/guides/troubleshooting.md): what each
  connect failure, request failure, dropped connection, and subscription end
  means, and what to do.

## Reference

- [API reference (KDoc) on javadoc.io](https://javadoc.io/doc/at.bernhardberger.tvheadend/htsp)
- [API behavior: outcomes, errors, and cancellation](docs/public-api.md)
- [Versioning and compatibility](docs/versioning.md)
- [Release policy](docs/releasing.md)
- [Change history](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)
- [Security policy](SECURITY.md)
- [HTSP protocol reference](docs/htsp-protocol/README.md)
- [Documentation index](docs/README.md)

## License and attribution

This independently maintained GPLv3 library descends from
[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream). It is not official Tvheadend software and is not affiliated with or endorsed by the
Tvheadend project; the Tvheadend name describes compatibility only. See
[LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and the
[licensing and attribution notes](docs/licensing.md).
