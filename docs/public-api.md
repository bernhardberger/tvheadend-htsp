# API behavior: outcomes, errors, and cancellation

How the public API reports success and failure, and what you can rely on when
writing against it.

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

## For contributors

The outcome policy above is enforced by `./tools/check-public-api-outcomes`
plus the API dump checks in CI. Run them, and the focused tests, before
changing any public signature.
