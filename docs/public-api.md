# API behavior: outcomes, errors, and cancellation

How the public API reports success and failure, and what you can rely on when
writing against it.

## Type naming

Whether a public type carries the `Htsp` prefix is decided by what it is, so you
can predict a name without looking it up. Every one of the 155 public types
follows this.

Bare, because the name is already tied to one wire method:

- One concrete request or response per method: `GetChannelRequest`,
  `HelloResponse`, `FileOpenRequest`, `EmptyResponse`. The suffix already says
  what the type is, so a prefix would only add noise.
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
- Abstractions over a whole request family rather than a single method — the
  `HtspRequest` base class and the `HtspDvrMutationRequest` /
  `HtspDvrMutationResponse` markers. These name a category, not a wire method,
  so they read as domain types.

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
