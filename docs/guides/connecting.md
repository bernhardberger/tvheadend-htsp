# Connecting, requests, and the connection lifecycle

Everything a TVHeadend client does goes through one connection object, so most
of the work in a first client is around it: opening it, running requests,
noticing when it dies, and closing it. This guide covers those four, starting
where the [README quick start](../../README.md#quick-start) stops, and the part
the library leaves to you, which is reconnecting. Metadata and live streams
build on the same connection and have their own guides:
[metadata sync](metadata.md) and [subscriptions](subscriptions.md).

The snippets below omit imports. The connection types live in
`at.bernhardberger.tvheadend.htsp.connection`, request functions such as
`getDiskSpace` in `at.bernhardberger.tvheadend.htsp.requests`. The
complete files, imports included, are
[`ConnectingGuide.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt)
and
[`ProtocolQuickStart.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ProtocolQuickStart.kt),
a longer worked example that connects, collects events, syncs metadata, runs
several requests and maps every failure to a decision. Both are compiled
against the staged artifact in CI.

## Create a connection

`createHtspConnection(ioDispatcher)` returns an `HtspConnection`. One object
holds at most one live TCP connection at a time and keeps working across
disconnects and reconnects until you close it. It is an interface, so
application code that depends on `HtspConnection` can be tested with a fake.

`ioDispatcher` runs socket creation, DNS resolution, connecting, and the
blocking reads and writes. A reader occupies one of its threads for as long as
the connection is live and writes need another, so give it a dispatcher that can
run several blocking tasks at once, such as `Dispatchers.IO`.

The other parameters are optional:

- `clientIdentity` sets the client name the server sees in `hello`. The default
  is `Kotlin HTSP client`. `clientVersion` is not transmitted.
- `logger` receives the transport's diagnostics; see
  [logging](troubleshooting.md#logging). The default discards them.
- `eventBufferOptions` sizes the event queues; see
  [metadata sync](metadata.md#buffers-and-overflow).
- `socketFactory` supplies the sockets, for tests and embedding; see
  [socket injection](../public-api.md#socket-injection).

## Connect

`connect` opens the socket, negotiates the protocol version, authenticates, and
returns one of two outcomes:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#connect -->
```kotlin
suspend fun connectOrNull(connection: HtspConnection, endpoint: HtspEndpoint): HtspLiveConnection? =
    when (val outcome = connection.connect(endpoint, HtspConnectOptions(responseTimeoutMs = 10_000L))) {
        is HtspConnectOutcome.Connected -> outcome.connection
        is HtspConnectOutcome.Failed -> {
            println("Could not connect: ${outcome.failure.kind}")
            null
        }
    }
```

Both outcomes are values. A refused login, a closed port and a wrong host all
arrive as `Failed`; `connect` throws only when the calling coroutine is
cancelled.

### What the handshake does

1. The client sends `hello` with the version it asks for. The default
   `requestedProtocolVersion` is 44, the newest version the typed API covers.
   The negotiated version is the lower of that and the server's.
2. A server that reports a version below 36 is rejected here, before any
   credentials are sent, with `UNSUPPORTED_SERVER_VERSION`.
3. The client always sends `authenticate`. With a user name and a password it
   answers the server's challenge with a digest, so the password does not cross
   the wire. If either is blank, including a user name without a password, it
   sends `authenticate` without credentials and you connect with whatever the
   server grants anonymous clients. The reply is also the only place HTSP reports
   your access rights.

The `Connected` outcome carries an `HtspLiveConnection`:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#live-connection -->
```kotlin
fun describeServer(live: HtspLiveConnection): String {
    val facts = live.serverFacts
    return "${facts.serverName} ${facts.serverVersion}, HTSP ${live.protocolVersion}, " +
        "recording access: ${live.dvrAccess}"
}
```

- `protocolVersion` is the negotiated version. Requests that need a newer
  version return `NotSupported` without being sent, and fields an older server
  never sends are nullable in the typed replies.
- `dvrAccess` is null when the server did not report it. A false value means
  the account may not use the DVR.
- `serverFacts` holds the rest of what `hello` and `authenticate` reported:
  server name and version, capabilities, access flags and connection limits.
  Every property is nullable, and null means the server did not send it.
- `generation` identifies this connection; see [generations](#generations).

### Connect options

`HtspConnectOptions` is passed to `connect`. Invalid values throw
`IllegalArgumentException` when you construct it.

| Option | Default | Controls |
|---|---|---|
| `connectTimeoutMs` | 10 000 | the TCP connect only, not DNS resolution |
| `responseTimeoutMs` | 5 000 | the `hello` and `authenticate` replies, and the silence check described [below](#what-ends-a-connection) |
| `socketReadTimeoutMs` | 60 000 | the socket read timeout |
| `socketBufferBytes` | 65 536 | the read and write buffer sizes |
| `requestedProtocolVersion` | 44 | the version asked for, from 36 upward |
| `forceReconnect` | false | whether a live connection is replaced; see below |

### Failed connects

`failure.kind` is an `HtspTransportFailureKind`. It is an open enum, so keep an
`else` branch:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#failure-kind -->
```kotlin
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
```

| Kind | Cause |
|---|---|
| `HOST_NOT_FOUND` | DNS resolution failed |
| `CONNECTION_REFUSED` | nothing accepted the connection on that port |
| `NETWORK_UNREACHABLE` | no route to the host |
| `CONNECTION_TIMEOUT` | the TCP connect, a handshake reply or the socket timed out |
| `UNSUPPORTED_SERVER_VERSION` | the server reports HTSP below 36 |
| `INCOMPATIBLE_SERVER` | the `hello` or `authenticate` reply was malformed, usually because the port does not speak HTSP |
| `AUTHENTICATION_REJECTED` | the server refused the `authenticate` request, including for a connection limit |
| `TRANSPORT_UNAVAILABLE` | any other failure while connecting |
| `CLOSED` | the client was already closed |
| `SUPERSEDED` | a later `connect`, `disconnect` or `close` ended this attempt |

Failure values never carry the endpoint, credentials, or the underlying
exception; see
[what outcome values deliberately omit](../public-api.md#what-outcome-values-deliberately-omit).
A failed attempt leaves `connectionState` in `Error` until the next connect,
disconnect or close, and it does not emit a `ConnectionFailure` event.

### Connecting again

Calling `connect` for the same host, port, user name and password while that
connection is live returns the existing `HtspLiveConnection`, with the same
generation, and ignores the options you pass. Set `forceReconnect = true` to
replace it. A connect to a different endpoint always replaces the live
connection, and the old one's subscription streams end.

If another `connect`, a `disconnect` or a `close` overtakes a connect that is
still in progress, that connect returns `Failed(SUPERSEDED)`. Cancelling the
calling coroutine cancels the attempt and propagates as cancellation, not as an
outcome.

## Run requests

Every server round trip is a typed `HtspRequest<R>` run through
`connection.execute(request, timeoutMs, expectedGeneration)`, which returns an
`HtspResult<R>`. Each request also has a convenience function, such as
`getSysTime()`, `getDiskSpace()` or `getEvents(...)`, that builds the request
and calls `execute`. There is no way to send a raw method name and field map.

`HtspResult` is either `Ok(value)` or one of seven failures. Server-side
problems never throw. Match on the cases, or use the helpers `getOrNull`,
`getOrElse`, `map`, `fold`, `onOk` and `onFailure`.

| Failure | Meaning | Usual decision |
|---|---|---|
| `AccessDenied` | the server denied this account the request | check access rights |
| `ConnectionLimit` | access denied because the server's connection limit is reached | retry later |
| `ServerError` | the server refused the request and may say why | show the reason, do not retry blindly |
| `NotSupported` | the negotiated version is below the request's minimum, or the server does not know the method | treat the feature as unavailable |
| `Timeout` | no reply within `timeoutMs`; this does not prove the server did nothing | repeat a request that is safe to repeat; otherwise read the state back first |
| `TransportUnavailable` | no usable connection, or the generation fence was stale | reconnect |
| `MalformedReply` | a reply arrived but could not be decoded | do not retry blindly |

One decision per failure is often all a caller needs:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#failure-policy -->
```kotlin
enum class FailureAction {
    CHECK_ACCESS,
    RETRY_LATER,
    RETRY_IF_REPEATABLE,
    RECONNECT,
    UNSUPPORTED,
    REJECTED,
    REVIEW_SERVER_STATE,
}

fun actionFor(failure: HtspFailure): FailureAction = when (failure) {
    HtspResult.AccessDenied -> FailureAction.CHECK_ACCESS
    HtspResult.ConnectionLimit -> FailureAction.RETRY_LATER
    HtspResult.Timeout -> FailureAction.RETRY_IF_REPEATABLE
    HtspResult.TransportUnavailable -> FailureAction.RECONNECT
    HtspResult.NotSupported -> FailureAction.UNSUPPORTED
    HtspResult.MalformedReply -> FailureAction.REVIEW_SERVER_STATE
    is HtspResult.ServerError -> FailureAction.REJECTED
}
```

`RETRY_IF_REPEATABLE` is the decision for `Timeout`, and the function cannot
finish it, because it does not see the request. Repeat a read such as
`getDiskSpace`. For a request that changes state, treat it like
`REVIEW_SERVER_STATE` and check the state first, because the server may have
acted.

Three of these deserve a closer look.

- `ServerError.serverMessage` is the server's own error text, when the reply had
  one. It is translated into the connection's language, untrusted, and not a
  stable code, so display it but do not parse it. `toString()` never renders it.
  A refusal the library detects locally, such as an unsuccessful DVR
  acknowledgement without error text, has a null message.
- `MalformedReply` is not a refusal. The server may well have performed the
  request, so do not blindly retry a non-idempotent one such as `addDvrEntry`.
- `Timeout` covers the whole call: capturing the connection, writing the
  request, and waiting for the reply. A timeout of an ordinary request leaves the
  connection live, unless it strikes while the request is only partly written;
  then the connection is dropped, as with cancellation below. An enclosing `withTimeout` is different: it cancels the call,
  which propagates as cancellation instead of returning `Timeout`.

DVR requests add one more rule. A refused DVR change comes back as
`ServerError`, and `Ok` means the server acknowledged the request, not that the
recording state has already changed. Watch the DVR metadata updates for that.
[API behavior](../public-api.md#public-call-outcomes) has the exact rules.

### Cancellation and exceptions

Cancelling the calling coroutine cancels the request and throws
`CancellationException` like any suspending call. It never shows up as a
failure value. If the request was only partly written when it was cancelled,
the connection is dropped, because the stream can no longer be trusted. After a
complete write, cancelling the wait for the reply leaves the connection live.
`hello` and `authenticate` sent through `execute` are stricter: once any part
was written, cancelling them drops the connection.

A few things still throw, all for mistakes you can fix in code: a non-positive
`timeoutMs`, a value outside an unsigned 32-bit field such as a channel id, and
the subscription misuse described in the [subscriptions guide](subscriptions.md).

## Generations

Every connection the library establishes gets its own identity, called a
generation. `HtspConnectionGeneration` is opaque: you compare it with `===`, and
`live.generation` is the one for the connection you just opened.

The generation matters when you reconnect. A coroutine that started work for the
old connection may still be running when the new one comes up. Passing
`expectedGeneration` makes the call fail instead of landing on the wrong one:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#fenced-request -->
```kotlin
suspend fun freeBytesOrNull(connection: HtspConnection, live: HtspLiveConnection): Long? =
    when (val result = connection.getDiskSpace(expectedGeneration = live.generation)) {
        is HtspResult.Ok -> result.value.freeBytes
        is HtspFailure -> null
    }
```

- A request whose fence is stale returns `TransportUnavailable` before anything
  is sent.
- The fence applies before dispatch only. A reply that already arrived is
  returned unchanged, even if the connection was replaced meanwhile, so tag the
  state you build from a reply with its generation and reject it under your own
  serialization when the generation is no longer current.
- `connection.isCurrent(generation)` answers whether a generation is the current
  one, live or lost.
- A generation stays current after a transport loss or a `disconnect`. Only the
  next connect attempt, even a failed one, replaces it. A reused live connection
  does not.

## Watch the connection

Three signals describe a connection, and each answers a different question.

| Question | Signal |
|---|---|
| Did this connect attempt work? | the `HtspConnectOutcome` it returned |
| Is the connection usable right now? | `connectionState` |
| Did a live connection just fail? | `HtspTransportEvent.ConnectionFailure` on `events` |

`connectionState` is a `StateFlow<HtspConnectionState>`:

| State | Meaning |
|---|---|
| `Disconnected` | no transport and no attempt in progress |
| `Connecting(host, port)` | an attempt is in progress |
| `Connected(host, port, connection)` | live; `connection` is the `HtspLiveConnection` for this exact snapshot |
| `Error(failure)` | an attempt or a live transport failed |
| `Closed` | closed for good |

`Connected` carries the live connection atomically, so read the generation,
protocol version, `dvrAccess` and `serverFacts` from it. After a re-`hello` or
re-`authenticate` request the state is replaced with the updated facts. `Error`
stays until the next `connect`, `disconnect` or `close`, and a transport failure
goes straight to `Error` without passing through `Disconnected`.

Treat the flow as current state, not as a log. It conflates quick transitions,
a synchronous `value` read may briefly lag, and a newer snapshot is never
replaced by an older one. To wait until a generation is over, wait for any state
that is not `Connected` with that generation:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#watch-state -->
```kotlin
suspend fun awaitGenerationEnd(connection: HtspConnection, generation: HtspConnectionGeneration) {
    connection.connectionState.first { state ->
        (state as? HtspConnectionState.Connected)?.connection?.generation !== generation
    }
}
```

`ConnectionFailure` reports unsolicited failures of established live
connections, each with its generation and a failure kind. It is the
authoritative signal: count failures with it, not with state transitions. It is
not sent for a failed `connect`, which already has its outcome, or for your own
`close`. It travels on the `events` flow, so start collecting before you connect
if you want it; see [metadata sync](metadata.md#the-events-flow).

### What ends a connection

A live connection ends when the peer closes the socket, a read or write fails,
the server violates HTSP framing, or the silence check fires. That check looks at
requests you are waiting on. If the server has said nothing for twice
`responseTimeoutMs` and a request has been outstanding for that long, or a frame
stalls halfway, the connection is dropped with `CONNECTION_TIMEOUT`. A connection
with nothing pending and no partial frame can stay silent indefinitely, so a dead
peer on an idle connection may go unnoticed until your next request.

When a connection ends, its pending requests fail, its subscription flows end
with `Terminated`, and the state becomes `Error`. Later requests return
`TransportUnavailable`.

## Reconnect

The library never reconnects, re-subscribes or re-requests anything. Deciding
when to try again, and what to set up on the new connection, is yours. The
decision has three parts:

1. Reconnect on the failures that can heal, such as timeouts, resets and
   unreachable hosts, with a growing delay.
2. Stop on the ones that will not, such as rejected credentials or an
   unsupported server.
3. Repeat the per-connection setup, that is the metadata sync and any
   subscriptions, on every new generation. State built on the old one is
   gone.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#reconnect -->
```kotlin
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
```

Cancel the coroutine running this loop to stop it, or call `close()`. A plain
`disconnect()` does not stop it, because the loop sees the connection end and
connects again.

## Disconnect and close

`disconnect` and `close` both return a Boolean: true when this call changed
something, false when there was nothing to do or the generation you passed was
stale.

| | `disconnect()` | `close()` |
|---|---|---|
| Ends the live connection | yes | yes |
| State afterwards | `Disconnected` | `Closed` |
| Later `connect` | works | returns `Failed(CLOSED)` |
| Later requests | `TransportUnavailable` | `TransportUnavailable`; check `connectionState` for `Closed` |
| Returns true | when it ends an attempt or a transport, or clears an `Error` | the first time, including from `Disconnected` |

After `close`, `disconnect` and a repeated `close` return false. Both take an
optional generation. With one, they act only if that generation is still
current and otherwise return false, leaving a replacement connection alone.
Without one they act on whatever is current. `close(generation)` closes
the client for good too, and after a transport loss or a `disconnect` the old
generation is still eligible, so it can still close the service. Both calls
end the current connection's subscription streams, and neither emits
`ConnectionFailure`.

Cleanup completes even when the caller is already cancelled, and only then does
the call throw the caller's `CancellationException`. That makes `close()` safe
in a `finally` block:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/ConnectingGuide.kt#shutdown -->
```kotlin
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
```

## Callbacks and threads

The library never calls your code while it holds a lifecycle or queue lock.
Even so, a collector or state observer on an immediate dispatcher such as
`Dispatchers.Unconfined` runs on library threads, including the transport reader:

- Do not block it. A blocking consumer delays delivery to the other collectors
  and can hold up another thread that is publishing state.
- Do not wait for another lifecycle operation from it. Calling
  `runBlocking { close() }` from a state observer can deadlock.

Collect on a real dispatcher instead.

## Where to go next

- [Metadata sync and event streams](metadata.md) for channels, tags, EPG and
  recordings.
- [Live subscriptions, timeshift, and packets](subscriptions.md) for streams.
- [Recordings, recording rules, and recording files](dvr.md) for the DVR.
- [The JSON API bridge](json-api.md) for server features without a typed
  request.
- [Errors and troubleshooting](troubleshooting.md) for what a failure means and
  what to do about it.
- [API behavior](../public-api.md) for the exact rules behind outcomes,
  cancellation and lifecycle calls.
