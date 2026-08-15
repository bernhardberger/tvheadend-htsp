# Public API policy

The first public baseline remains provisional. API declarations under
`at.bernhardberger.tvheadend.htsp` are limited to the five package owners named
in `AGENTS.md`; current paths and declarations are not a promise to broaden the
surface.

## Public call outcomes

Every public suspending library call that performs a server round trip returns
failure as a typed outcome value. `HtspConnectOutcome` and `HtspResult` are the
accepted outcome families. Throwing is not a supported server-failure channel.

Caller cancellation and stale-generation cancellation propagate as
`CancellationException`; cancellation is never an outcome case. Invalid caller
arguments may throw `IllegalArgumentException`. Lifecycle commands such as
`disconnect` and `close` may return `Unit`.

Outcome values do not expose throwables, server error text, endpoints,
credentials, digest/challenge bytes, paths, sequence numbers, subscription IDs,
or generation identities. `HtspConnection.execute` accepts only finite catalog
requests; it is not a raw method/map escape hatch.

Run `./tools/check-public-api-outcomes`, the API dump checks in exact-SHA CI,
and focused tests for any explicitly authorized public API change.
