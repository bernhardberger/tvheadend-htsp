# Changelog

## [0.3.0]

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

Subscribe is rejected before its wire write unless collection has actively
registered the id. Malformed mux packets whose subscription id remains
trustworthy produce ordered `Dropped` markers. Malformed controls and
untrustworthy packet envelopes fail the transport as incompatible rather than
disappearing silently.

**BREAKING (source + binary):** `HtspMuxPacketMessage` now exposes negotiated
microsecond `decodingTimeUs`, `presentationTimeUs`, and `durationUs` values
instead of raw-clock timestamp fields. Frame type accepts only unknown `-1` or
ASCII I/P/B; TVHeadend's wire value zero is normalized to unknown. `SubscribeResponse.ninetyKhz` and `normalizedTimestamps` are now
strict nullable booleans, while the numeric request still treats any nonzero
`90khz` value as enabled. Subscription IDs cannot be reused in one connection
generation, preventing packets from becoming ambiguous across clock modes.

`HtspBinary` now exposes its `size` and can copy directly into a caller-owned
buffer with bounded `copyInto`, avoiding an intermediate payload array in
playback consumers while retaining defensive public construction and
`toByteArray()`. Typed wire decoding transfers its codec-owned mux payload into
`HtspBinary`, avoiding another payload-sized snapshot before the final consumer
copy.

`enableAsyncMetadataAwaitingInitialSync` now installs generation-scoped metadata
observation before sending `enableAsyncMetadata`, so an adjacent acknowledgement
and `initialSyncCompleted` marker cannot race collector startup. Its typed timeout
covers both phases, while caller and stale-generation cancellation still propagate.

**BREAKING (source + binary):** `HtspConnection` now exposes the service-owned
`connectionState` as a `StateFlow<HtspConnectionState>`. Custom connection
implementations must provide the current lifecycle state instead of requiring
consumers to reconstruct it from events.

**BREAKING (Java source + JVM binary):** `createHtspConnection` now exposes its
existing socket factory as a final Kotlin-optional parameter. Consumers can
inject a fresh unconnected JVM socket for deterministic connect, handshake, and
typed-request sessions without opening a network connection.

**BREAKING (behavior):** Channel, tag, event, and DVR-entry add/update messages
and `HtspSubscriptionStartMessage` now have structural data-class equality,
hashing, components, and snapshot-preserving `copy()` operations for metadata
reducers. Collection inputs remain immutable snapshots, binary metadata keeps
content equality, unsigned validation still applies to copies, and message
rendering remains payload-free and redacted.

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
