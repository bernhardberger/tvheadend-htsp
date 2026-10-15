# Errors and troubleshooting

Almost nothing in this library throws on a server problem. A refused login, a
timeout and a lost connection arrive as values, and each one tells you something
different. This guide is organized by what you see: a connect that fails, a
request that fails, a connection that drops, state that looks incomplete, a
subscription that ends, and what to log when you need to dig deeper. For each it
says what the symptom means and what to do, and it points at the guide that has
the detail.

It assumes you have read the [connecting guide](connecting.md). Snippets omit
imports; the one below is in
[`TroubleshootingGuide.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/TroubleshootingGuide.kt),
compiled against the staged artifact in CI.

## The connection will not open

`connect` returns `HtspConnectOutcome.Failed`, and `failure.kind` says why.
Failure values carry no host, credentials or exception, so the kind is all you
get; the [logger](#logging) and your own log lines supply the rest.

| `failure.kind` | What it means | What to do |
|---|---|---|
| `HOST_NOT_FOUND` | DNS could not resolve the host | check the host name, then the resolver |
| `CONNECTION_REFUSED` | nothing accepted the connection on that port | check the HTSP port in the server's settings and that the server is running |
| `NETWORK_UNREACHABLE` | there is no route to the host | check the network, VPN or firewall |
| `CONNECTION_TIMEOUT` | the TCP connect, a handshake reply or the socket timed out | check that the host answers; raise `connectTimeoutMs` or `responseTimeoutMs` for a slow link |
| `INCOMPATIBLE_SERVER` | the `hello` or `authenticate` reply was malformed | the port probably does not speak HTSP, for example an HTTP port |
| `UNSUPPORTED_SERVER_VERSION` | the server reports HTSP below 36 | upgrade the server; the library does not support older ones |
| `AUTHENTICATION_REJECTED` | the server refused `authenticate`, including for a connection limit | check user name and password, then the account's connection limits |
| `TRANSPORT_UNAVAILABLE` | any other failure while connecting | retry; turn on the logger |
| `CLOSED` | you closed the client | create a new connection object |
| `SUPERSEDED` | a later `connect`, `disconnect` or `close` overtook this attempt | usually expected; check who else drives the connection |

`connectTimeoutMs` bounds only the TCP connect, not DNS. The
[connecting guide](connecting.md#failed-connects) has the options and the full
handshake.

A different symptom is a connect that succeeds and then requests return
`AccessDenied`. One cause is a blank user name or password: the client then
authenticates without credentials, and you connect with whatever the server
grants anonymous clients. Another is an account that lacks the right for that
request. Check `serverFacts` and `dvrAccess` on the live connection to see what
the account got.

A failed attempt leaves `connectionState` in `Error` until the next `connect`,
`disconnect` or `close`, and it does not emit a `ConnectionFailure` event.

## A request fails

Every request returns an `HtspResult`. Match the failure:

| Failure | What it means | What to do |
|---|---|---|
| `AccessDenied` | the server denied this account the request | check the account's rights, for example `dvrAccess` for recordings |
| `ConnectionLimit` | access denied because the connection limit is reached | close another client, or retry later |
| `ServerError` | the server refused the request | show `serverMessage` to a person, and do not parse it |
| `NotSupported` | the negotiated version is below the request's minimum, or the server does not know the method | treat the feature as unavailable on this server |
| `Timeout` | no reply within `timeoutMs` | retry if the request is safe to repeat; raise `timeoutMs` for slow requests |
| `TransportUnavailable` | no usable connection, or a stale generation fence | reconnect, or drop the stale work |
| `MalformedReply` | a reply arrived that could not be decoded | do not retry blindly; the server may have acted |

The [connecting guide](connecting.md#run-requests) explains each failure and how
to turn them into decisions. Four points save time:

- `Timeout` and `MalformedReply` do not prove that the server did nothing. For a
  request that changes state, such as `addDvrEntry`, read the state back before
  you try again.
- `ServerError.serverMessage` is the server's text in the connection's language.
  It can be null, and `toString()` never prints it, so log it deliberately if
  you want it.
- `TransportUnavailable` after a reconnect often means a request was fenced with
  an old generation. Pass the generation of the connection you are working with.
- For a DVR change, `Ok` means the request was acknowledged, not that the
  recording has changed. See the [DVR guide](dvr.md#ok-acknowledges-the-request).

### Exceptions

A few mistakes throw, because they are bugs in the calling code and not server
conditions:

- `IllegalArgumentException` for a non-positive `timeoutMs`, an unsigned value
  outside 32 bits such as a channel id, an invalid option value, and an explicit
  channel and time recording without a title.
- `IllegalStateException` for `subscribe` without a collector registered for its
  id, or an id used twice in one generation. See
  [subscriptions](subscriptions.md#collect-before-you-subscribe).

Cancelling the calling coroutine throws `CancellationException` and is never a
failure value.

## The connection dropped

Signals, in order of reliability:

- `HtspTransportEvent.ConnectionFailure` on `events` reports an unsolicited
  failure of a live connection, with its kind. It is the authoritative count.
- `connectionState` goes to `Error` without passing through `Disconnected`.
- Pending requests fail, and later ones return `TransportUnavailable`.
- Every subscription flow ends with a `Terminated` event.

The [connecting guide](connecting.md#watch-the-connection) shows how to wait for
a generation to end. The library does not reconnect, re-subscribe or re-request
anything, so a lost connection needs your reconnect loop and a fresh metadata
sync and subscriptions on the new generation; see
[reconnecting](connecting.md#reconnect).

### `CONNECTION_TIMEOUT` on a live connection

This kind is also how the silence check ends a connection. It fires when a
request you are waiting on has been outstanding for twice `responseTimeoutMs`
and the server has said nothing for that long, or when a frame stalls halfway.
A connection with nothing pending and no partial frame can stay silent
indefinitely, so a dead peer on an idle connection may go unnoticed until your
next request.

That has two practical consequences:

- If you need to notice a dead server while idle, send a cheap request now and
  then, such as `getSysTime`. A request that gets no answer is what lets the
  check fire.
- Detection happens at socket-read timeout boundaries, so a long
  `socketReadTimeoutMs` delays it. `responseTimeoutMs` sets the interval, and
  both are set in `HtspConnectOptions`.

A single request that times out before the silence check fires is a different
case: it ends in `Timeout` and leaves the connection live. See
[what ends a connection](connecting.md#what-ends-a-connection).

## My state looks incomplete

Metadata arrives on `events`, and the flow does not replay. Three things cause
most surprises:

- **Nothing arrived, or the start is missing.** A collector that starts after
  `enableAsyncMetadata` misses what was sent before it. Start collecting first,
  then run the sync, as in the [metadata guide](metadata.md#run-the-initial-sync).
  Also check that you compare each event's generation with the connection you
  are building state for, because events of an old generation arrive tagged with
  it.
- **`MetadataOverflow`.** Your collector fell behind and the library dropped
  metadata for it. Your state is incomplete; recovery is a new connection and a
  new sync. See [buffers and overflow](metadata.md#buffers-and-overflow) and
  [recovery](metadata.md#recover-from-overflow-or-malformed-metadata).
- **`MalformedMetadata`.** The library skipped metadata it could not decode, and
  the connection stays open. Treat the generation's state as incomplete too. See
  [malformed metadata](metadata.md#malformed-metadata).

If the initial-sync helper returns a failure, the table for it is in
[run the initial sync](metadata.md#run-the-initial-sync).

Some data is missing because the account may not see it. The server sends only
what the account is allowed to see, so recordings and rules the account cannot
access never appear. Check the account's rights before you suspect the library.

## A subscription ended

The flow's last event is `Terminated(reason)`, or the flow completes without it
after a successful `unsubscribe`. The reason is open, so keep an `else` branch.

| Reason | What it means | What to do |
|---|---|---|
| `SUBSCRIBE_REJECTED` | the server refused `subscribe`; the reply was `ServerError`, `AccessDenied`, `ConnectionLimit` or `NotSupported` | read the reply; a connection limit means another stream must end first |
| `CONSUMER_OVERFLOW` | your queue overflowed on a control event | send `unsubscribe`; consume faster or raise the subscription budgets |
| `GENERATION_LOST` | a newer connection replaced this generation, or you collected on a stale one | subscribe again on the current generation |
| `LOCAL_RETIREMENT` | `disconnect` or `close` retired the connection | expected after your own call |
| `REMOTE_EOF` | the server closed the connection | reconnect |
| `IO_FAILURE`, `FRAMING_FAILURE`, `TIMEOUT` | the transport failed or stalled | reconnect; see `ConnectionFailure` |
| `MALFORMED_MESSAGE` | a subscription control message or a packet envelope could not be trusted, so the connection closed | reconnect; if it repeats, report it with the server version |
| `PUBLICATION_FAILURE`, `INTERNAL_FAILURE` | the library's own delivery failed | reconnect, and report it if it repeats |

Other subscription symptoms:

- **`subscribe` throws `IllegalStateException`.** There was no collector for the
  id, or the id was used before in this generation. Collect first and use a new
  id.
- **Gaps or broken pictures.** `Dropped` events say that this client could not
  deliver packets. Discard predicted frames until each stream's next keyframe.
  `Queue` events, by contrast, report the server's own drops. See
  [overflow and dropped packets](subscriptions.md#overflow-and-dropped-packets).
- **`Stopped` followed by `Started`.** This is the server reconfiguring the
  stream, not an end. Keep collecting. See
  [stopped and started](subscriptions.md#stopped-and-started).
- **A flow that stays open after a `Timeout` or `MalformedReply` on
  `subscribe`.** The server may have created the subscription. Send
  `unsubscribe` if you do not want it.

The full list of how a flow ends is in
[how a flow ends](subscriptions.md#how-a-flow-ends).

## Logging

`createHtspConnection` takes a `logger` that receives the transport's
diagnostics. The default discards them:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/TroubleshootingGuide.kt#logger -->
```kotlin
fun connectionWithLogging(ioDispatcher: CoroutineDispatcher): HtspConnection =
    createHtspConnection(
        ioDispatcher = ioDispatcher,
        logger = HtspLogger { level, message, _ -> System.err.println("htsp $level: $message") },
    )
```

The logger gets a level (`WARNING` or `ERROR`), a message and an optional cause.
Today the transport logs one condition: a read timed out in the middle of a
frame, reported as a warning for the first such timeout in a frame and every
fiftieth after that. The transport keeps waiting for the rest of the frame, and
if the stall lasts too long the connection ends with `CONNECTION_TIMEOUT`.
Treat the messages as diagnostics for people, not as an interface to parse; the
set of messages may change between releases.

Most of what you need to debug comes from outcomes and events, so log those at
your call sites:

- the `failure.kind` of a failed connect, and the kind inside every
  `ConnectionFailure`;
- transitions of `connectionState`;
- the failure case of each failed request, with the request you made;
- `MetadataOverflow`, `MalformedMetadata` and the `Terminated` reason.

Failure values carry no endpoint, credential, path or exception by design, and
`toString()` on requests, entities and messages is redacted, so log the facts you
know yourself, such as the host and the request name. Be careful with
`serverMessage` and with fields such as a ticket path that can carry
credentials.

## Where to go next

- [Connecting, requests, and the connection lifecycle](connecting.md) for
  connect options, outcomes and reconnecting.
- [Metadata sync and event streams](metadata.md) for overflow and recovery.
- [Live subscriptions, timeshift, and packets](subscriptions.md) for flow end
  states.
- [Recordings, recording rules, and recording files](dvr.md) for DVR refusals.
- [API behavior](../public-api.md) for the exact rules.
