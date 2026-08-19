# Changelog

## [Unreleased]

Link the current coordinate to Maven Central and streamline future GitHub
prereleases to the verified Central ZIP and release manifest. Individual Maven
members remain available from Central instead of being duplicated as GitHub
assets.

**BREAKING (source + binary):** Typed `execute` is now a member of
`HtspConnection` instead of an extension backed by an internal capability.
Custom connection implementations can intercept requests, and convenience
request functions dispatch through that member.

`HtspConnectionGeneration` now has a public constructor so fakes can create
identity-based generation tokens without an internal factory.

**BREAKING (source + binary):** `HtspConnection` now exposes cold, single-use
ordered `subscriptionEvents(id)` flows. Subscription packets and controls no
longer publish through global `events`; that flow is now reserved for metadata
and connection failures with a bounded 1024-event burst budget. Per-subscription
streams retain controls under pressure, report packet eviction with ordered
`Dropped` markers, and drain to explicit stop, unsubscribe, generation-loss, or
transport-loss completion.

## [0.2.0]

Clarify that the client requests HTSP v43 by default while the typed surface
has a v44 coverage ceiling. Remove the protocol evidence and generation tooling
(`derive.py`, `report.py`, `htsp_spec.json`, `HTSP_METHOD_MATRIX.md`,
`htsp_surface.py`, the four typed Kotlin generators, and the generated-source
drift checker). The protocol surface is now maintained by hand. This tooling
removal alone changes no API, ABI, or runtime behavior.

**BREAKING (source + binary):** The hand-maintained protocol surface is now
grouped into domain source files. The former JVM facades
`jsonapi.GeneratedHtspExtensionsKt`,
`messages.GeneratedHtspServerMessageDispatchKt`, and
`requests.GeneratedHtspExtensionsKt` have been replaced by facades derived from
the new filenames. Two `getTicket`, two `subscriptionSeek`, and two
`subscriptionSkip` subtype overloads were removed; pass their base selector
types instead. `fileCloseWithProgress` was merged into `fileClose`, which now
accepts optional `playPositionSeconds` and `playCount` arguments. Existing
positional calls of the form `fileClose(id, timeoutMs)` now bind the second
argument to `playPositionSeconds`; use the named argument `timeoutMs = ...`.

**BREAKING (source + binary):** Public type names now follow one documented rule
(see `docs/public-api.md`): concrete per-method request and response types are
bare, while domain models, connection-lifecycle types, server messages, and
shared protocol abstractions carry the `Htsp` prefix. `ConnectionState` is
renamed to `HtspConnectionState`. The unused `StreamProfile` is removed;
profile responses already use `HtspProfile`, and `StreamProfile` had no
repository references.

## [0.1.1]

This release records the initial provisional baseline under `0.1.1` after the
`v0.1.0` release attempt stopped before publication. It does not promise source,
binary, or behavioral compatibility or support.

The initial provisional baseline contains the standalone Kotlin/JVM HTSP v44
protocol library and its typed outcome API. Publication and availability are
independently verified external state and are not established by this entry.

## [0.1.0]

The signed `v0.1.0` tag did not produce a release. Its workflow stopped before
Central or GitHub publication, and the tag is not reused.
