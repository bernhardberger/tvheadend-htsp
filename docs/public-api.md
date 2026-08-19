# API behavior: outcomes, errors, and cancellation

How the public API reports success and failure, and what you can rely on when
writing against it.

## Type naming

Whether a public type carries the `Htsp` prefix is decided by what it is, so you
can predict a name without looking it up. Every one of the 157 top-level public
types follows this.

Bare, because the name is already tied to one wire method:

- One concrete request or response per method: `GetChannelRequest`,
  `HelloResponse`, `FileOpenRequest`. The suffix already says what the type is,
  so a prefix would only add noise.
- The argument types those requests take, named after the request that owns
  them: `AddDvrEntrySelector`, `GetTicketSelector`, `SubscribeChannel`,
  `SubscriptionSeekPosition`, `FileSeekWhence`.

Prefixed, because the name stands on its own and would otherwise collide:

- Domain models: `HtspChannel`, `HtspEvent`, `HtspProfile`, `HtspDvrCutpoint`.
  Their bare names are common words consumers already use. `Channel` is the
  sharpest case — it collides with `kotlinx.coroutines.channels.Channel`, which
  this library exposes as an `api` dependency.
- Connection-lifecycle types, for the same reason: `HtspConnection`,
  `HtspEndpoint`, `HtspConnectionState`.
- Server messages, which also take a `Message` suffix:
  `HtspChannelAddMessage`, `HtspSubscriptionStartMessage`.
- Shared protocol abstractions and abstractions over a whole request family:
  `HtspEmptyResponse`, the `HtspRequest` base class, and the
  `HtspDvrMutationRequest` / `HtspDvrMutationResponse` markers. These name a
  category or serve multiple wire methods, so they read as domain types.

## Public call outcomes

Server round trips return values, not exceptions.

Every public suspending call that talks to the server returns its result as a
typed outcome value. Connecting returns HtspConnectOutcome, and each request
call returns HtspResult. Throwing is not a supported server-failure channel: a
refused login, a missing permission, a timeout, or a dead socket arrives as a
failure case you pattern-match on, or unwrap with the `map`, `fold`,
`getOrNull`, `getOrElse`, and `onFailure` helpers.

## Cancellation stays cancellation

Cancelling the calling coroutine cancels the in-flight call, which propagates
CancellationException like any other suspending Kotlin code. Cancellation never
shows up disguised as a failure outcome. The same applies when a call is
abandoned because the connection generation it was fenced to went stale.

## Metadata and subscription event streams

`HtspConnection.events` has replay zero and carries metadata server messages and
connection failures only. It has an exact 1024-event burst budget shared by
independent collectors. An indefinitely stalled collector eventually
backpressures this bounded, never-drop stream.

High-rate subscription traffic is isolated by id through
`HtspConnection.subscriptionEvents`. The returned flow is cold: collection
registers the unsigned-u32 id and must start before the matching `subscribe`
request. An id may be collected once in a connection generation, even after the
flow has completed. The stream preserves committed packet/control order, reports
packet pressure with ordered `Dropped` events, drains on stop or unsubscribe,
and reports generation or transport loss with a final `Terminated` event.
Collector cancellation remains `CancellationException`.

Each subscription id may also be sent in only one `subscribe` request per
connection generation; local reuse throws `IllegalStateException` without
retiring the connection. The request's numeric `ninetyKhz` field selects the
packet clock: absent or zero is native microseconds and any nonzero value is 90
kHz. `HtspMuxPacketMessage` always exposes `decodingTimeUs`,
`presentationTimeUs`, and non-null `durationUs` in microseconds. Missing PTS or
DTS remains `null`; frame type is ASCII I/P/B or the unknown sentinel `-1`.
`SubscribeResponse.ninetyKhz` and `normalizedTimestamps` are strict nullable
boolean observations.

## Binary payload access

`HtspBinary` owns a defensive snapshot and retains content equality and redacted
rendering. Use `size` to allocate the final consumer buffer and `copyInto` to
write directly into it without an intermediate payload array. The bounded copy
returns the number of bytes written and copies only the prefix that fits after
the requested destination offset. `toByteArray()` remains available when a
standalone defensive copy is more convenient. No borrowed mutable-array access
is exposed.

## Argument validation and lifecycle calls

Passing an invalid argument, such as a non-positive timeout, may throw
`IllegalArgumentException`. Lifecycle calls such as `disconnect` and `close`
return `Unit`.

## What outcome values deliberately omit

Failure values are stable categories, not payloads. They never expose
throwables, server error text, endpoints, credentials, digest or challenge
bytes, paths, sequence numbers, subscription IDs, or generation identities, so
logs and crash reports built from them stay free of secrets and wire internals.

Requests reach the server only through the finite typed catalog:
`HtspConnection.execute` accepts those request types and nothing else, so there
is no raw "method string plus map" escape hatch and no way to subclass in a
custom request from outside the library.
