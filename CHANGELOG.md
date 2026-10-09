# Changelog

## [Unreleased]

- **BREAKING:** metadata delivery now uses independent bounded collector queues,
  drops newest metadata on overflow and reports `MetadataOverflow`. Queue saturation
  never blocks the reader. `HtspTransportEvent` stays sealed but is open for evolution;
  keep an `else` branch.
  Added `HtspEventBufferOptions` to `createHtspConnection` (changing its ABI).
  Subscription control overflow now drains then ends with `CONSUMER_OVERFLOW`
  instead of blocking the reader; the consumer must unsubscribe.
  Stale fenced calls now return `TransportUnavailable`; `disconnect` and `close`
  return Boolean (changed state versus stale/no-op). Connects superseded by another
  connect, disconnect, or owner close return `Failed(SUPERSEDED)`.
  `ConnectionFailure` is emitted only for live generations, not failed connects.
  A subscribe refusal after the request deadline no longer ends its stream: late
  observers expire at the original deadline.
  Removed `commitIfCurrent` and `commitIfLive`: snapshot `liveConnection.value`,
  compare `generation`, and tag application state with the generation instead.
  Transport `Error` is sticky until
  explicit lifecycle action, and nonlive subscription collection terminates normally.

- **BREAKING:** require HTSP 36 or newer servers and reject requested protocol
  versions below 36 in `HtspConnectOptions` and public `HelloRequest`, including
  their `copy` methods. `HelloRequest` retains the unsigned-u32 upper bound. Added
  `MINIMUM_HTSP_PROTOCOL_VERSION` and `UNSUPPORTED_SERVER_VERSION`; servers below
  the floor are rejected before authentication; a below-floor direct hello
  reply retires the transport without installing its state. `INCOMPATIBLE_SERVER` remains
  the protocol-violation category.
- **BREAKING:** autorec-add `broadcastType` is now nullable (sent by HTSP 39+
  servers), changing its constructor/copy/getter ABI so v36–v38 metadata sync
  can succeed. Absence is not converted to zero.
- **BREAKING:** server facts retain full u32 values as `Long?`; decoder outcomes
  are internal. Explicit channel/time DVR creation requires a non-empty title
  (whitespace-only titles are accepted),
  timerec minutes are bounded to 0..1439, and socket buffers to 1..16 MiB.
- **BREAKING:** added properties change constructor/copy signatures for
  `HtspMuxPacketMessage`, `HtspChannelService`, `HtspDvrRecordingFile`,
  `HtspEvent`, `HtspEpgBroadcastObject`, DVR add/update and event-update messages,
  and `AddDvrEntryRequest`, plus the `addDvrEntry` convenience overloads.
  Timerec add/update messages insert `removalDays` beside `retentionDays`, shifting
  subsequent destructuring components as well as constructor/copy signatures.
- Decode incoming message and reply data integer flags as zero/nonzero, retaining
  strict types; envelope `success`, `noaccess`, and `connlimit` remain strict 0/1.
- Add DVR creation policy options, timerec removal, DVR episode details,
  immutable recording stream/HbbTV/credit metadata, and packet commercial advice.
- Add direct `HtspBinary.copyInto(ByteBuffer)` without an intermediate array.

**BREAKING (1.0.0-rc.1 API/ABI):** quantity names now expose their units;
matching convenience parameters and data-class copy parameters follow them.
HTSP field names and integer widths are unchanged. Full wire-field and pinned
source evidence is in [the units table](docs/htsp-protocol/README.md#units).

| Surface | Old → new |
|---|---|
| Event/event-update; DVR add/update, recording file, explicit channel selector, update request | `start`/`stop` → `startEpochSeconds`/`stopEpochSeconds` |
| Event/event-update | `firstAired` → `firstAiredEpochSeconds` |
| DVR update request | `startExtra`/`stopExtra` → `startExtraMinutes`/`stopExtraMinutes`; `retention`/`removal` → `retentionDays`/`removalDays`; `playPosition` → `playPositionSeconds` |
| DVR cutpoint | `start`/`end` → `startMs`/`endMs` |
| Get-events; async metadata | `maxTime` → `maxTimeEpochSeconds`; `lastUpdate`/`epgMaxTime` → `lastUpdateEpochSeconds`/`epgMaxTimeEpochSeconds` |
| Detailed EPG; file open/stat | `…UnixSeconds` → `…EpochSeconds` |
| System-time response | `unixTimeSeconds` → `timeEpochSeconds` |
| File read; file read/seek and seek response | `size` → `sizeBytes`; `offset` → `offsetBytes` |
| Subscription byte seek; subscribe | `size` → `sizeBytes`; `queueDepth` → `queueDepthBytes` |
| Hello request/response; connected state | `htspVersion` → `protocolVersion` |
| Queue status; subscription stream | `delay` → `delayUs`; `frameDuration` → `frameDurationUs` (normalized to microseconds) |

**BREAKING (1.0.0-rc.1 API/ABI):** integer wire flags now use Boolean values,
with null still meaning absent/omitted. Requests encode 0/1; message decoding
accepts zero/nonzero integer flags. Changed properties and matching convenience
parameters (names and HTSP wire field names are unchanged):

| Surface | Property | Old → new |
|---|---|---|
| DVR update request; DVR add/update messages | `enabled` | `Long?` → `Boolean?` |
| DVR add/update messages | `duplicate` | `Long?` → `Boolean?` |
| Autorec add/update requests | `fullText`, `mergeText` | `Long?` → `Boolean?` |
| Event and event-update models | `isNew` | `Long?` → `Boolean?` |
| EPG query request | `full` | `Long?` → `Boolean?` |
| Async metadata request | `epg` | `Long?` → `Boolean?` |
| Subscribe request | `ninetyKhz` | `Long?` → `Boolean?` |
| Subscription seek/skip requests | `absolute` | `Long?` → `Boolean?` |
| Subscription skip message | `absolute`, `error` | `Long?` → `Boolean?` |
| Subscription stream | `rdsUecp` | `Long?` → `Boolean?` |
| Timeshift status message | `full` | `Long` → `Boolean` |

**BREAKING (1.0.0-rc.1 API/ABI):** `HtspResult.ServerError` is now a data class
with optional `serverMessage` carrying the server's own rejection text. `when`
branches must use `is`; equality includes the message. Local failures leave it
null, and `toString()` never renders it.

**BREAKING (1.0.0-rc.1 API/ABI and behavior):** DVR mutation responses no longer
expose `success`/`error`; refusals return `ServerError` instead of `Ok`.
`AddDvrEntryResponse.entryId` is non-null, and update, stop, cancel, and delete
responses are acknowledgement objects. `Ok` acknowledges the request, not an
already-completed recording state change.

**BREAKING (1.0.0-rc.1 API/ABI):** `HtspConnectionState.Error` carries a bounded
`HtspTransportFailure` instead of a `Throwable`. Connection failures no longer
expose exception messages through lifecycle state.

**BREAKING (1.0.0-rc.1 API/ABI):** `decodeHtspServerMessage(Map)` is internal;
consume typed messages through the connection's event streams instead.

**BREAKING (1.0.0-rc.1 API/ABI):** `HtspConnectOptions.connectTimeoutMs` and
`socketReadTimeoutMs` are `Long`. Both require 1..2147483647 milliseconds for
the JDK socket API, including when options are copied.

Endpoint string rendering now redacts usernames as well as passwords.
Automatic/time-based recording-rule add/update requests redact their payloads,
including directories, in string rendering of constructed and copied instances.

**BREAKING (behavior and ABI addition):** when the server refuses `subscribe`
with an error or access denial (`ServerError`, `AccessDenied`,
`ConnectionLimit`, `NotSupported`), the registered `subscriptionEvents(id)` flow
now delivers earlier events and ends with `Terminated` and the new
`HtspSubscriptionTermination.SUBSCRIBE_REJECTED` reason, instead of staying
open until cancellation or generation loss. A timeout or cancellation alone, or
a reply that only fails local decoding, still leaves the flow open.

**BREAKING (behavior):** the client now requests HTSP v44 by default instead of
v43 (`HtspConnectOptions.requestedProtocolVersion`). Servers still negotiate
`MIN(server, requested)`; at the pinned TVHeadend revision nothing depends on
a negotiated 43 versus 44, so `HtspLiveConnection.protocolVersion` reports 44
on v44 servers. Pass `requestedProtocolVersion = 43` to keep the old request.

The `api` dependency `kotlinx-coroutines-core` moves from 1.10.2 to 1.11.0, so
consumers resolve 1.11.0 transitively. The build now uses Gradle 9.8.1.

The release workflow accepts stable `X.Y.Z` versions and `-alpha.N`, `-beta.N`
and `-rc.N` pre-releases. Pre-releases and major-zero releases remain GitHub
prereleases; stable releases from 1.0.0 are full releases marked latest.

## [0.10.0]

**BREAKING (JVM ABI):** `HtspQueueStatusMessage` gains trailing nullable
`errorCount: Long? = null`, decoding the optional unsigned-u32 `queueStatus.errors`
field. Existing Kotlin constructor calls can omit it, but the constructor and
generated `copy` JVM signatures change; recompile consumers. Equality, hashing,
and rendering now include the new counter. No other field types or wire names
change.

Absence remains null, distinct from an explicit zero. Values through
`4294967295` are retained without signed-Int truncation. Malformed present values
produce the existing redacted malformed-message outcome. This is the cumulative
server data-error count, not dropped frames or a per-report delta.

## [0.9.0]

**BREAKING (behavior; Kotlin source and JVM signatures unchanged):** request
timeouts now include local serialization and socket writes. Cancellation and
transport-loss outcomes follow the boundaries described below.

Transport loss during a typed request now returns `TransportUnavailable` for the
current generation instead of spuriously cancelling the caller. Failed or aborted
partial writes retire their exact socket before another request can reuse it.
Stale request fences remain cancellation even after a failed replacement leaves
no transport live. Caller cancellation does not emit a spurious transport failure.
Completed ordinary replies survive same-generation transport loss during result
delivery; generation replacement still rejects stale results.

Socket setup and writes now run on the supplied I/O dispatcher with caller-owned
workers and socket-close cancellation. Request deadlines include handshake/write
serialization and write/flush time, not just the reply wait. Cancellation while
queued or after a complete ordinary frame leaves the shared connection live.
System DNS and application socket factories remain subject to their own blocking
behavior; the TCP connect timeout does not bound those operations.

The silence watchdog uses monotonic time since complete writes and no longer
charges previous idle time or local queues to the server. Partial frames retain
alignment across transient socket timeouts but retire the transport when a
consecutive timeout streak exhausts its grace interval, including when no requests
are pending.

Typed file reads transfer their private decoder-owned payload rather than copy
it again. Public binary construction and standalone decoding still take defensive
snapshots, and byte-array accessors still return copies.

## [0.8.0]

**BREAKING (behavior; Kotlin source and JVM signatures unchanged):**
`HtspSubscriptionEvent.Stopped` no longer completes subscription collection.
TVHeadend can stop and restart streams on the same subscription when source
components change. The flow now preserves the next `Started`, replacement
metadata, and packets in order, without resetting its timestamp clock. Successful
unsubscribe acknowledgement still drains and completes; transport or local
retirement still appends `Terminated`, including after a stop. Packet drop
accounting and collector cleanup remain intact. This repairs HTSP-01 in the
protocol library, not the SDK or application decoder/state-machine handling.

Reply sequences now require an integer in the complete unsigned-u32 wire domain.
Oversized, negative, floating-point, and nonnumeric sequences cannot alias pending
requests or late unsubscribe acknowledgements (HTSP-05). Request rollover retains
the complete unsigned range. General scalar compatibility is unchanged apart
from rejecting oversized root sequence integers.

JSON API diagnostic rendering no longer includes string values, object keys,
nested payload contents, or request paths (HTSP-06). Exact accessors, equality,
and wire payloads are unchanged. This is not a guarantee for explicitly logged
raw accessor values or unrelated message types.

## [0.7.0]

Added an explicit near-live subscription helper that derives one bounded
absolute `subscriptionSkip` from an observed timeshift end, caller-selected
timestamp clock, and caller-selected margin. It validates the observed buffer
span and TVHeadend's multiply-before-divide conversion range before dispatch.

The exact `subscriptionLive` request remains available for protocol fidelity.
The new helper never falls back to it and does not claim exact live mode or
treat the synchronous request acknowledgement as settled positioning; ordered
timeshift and skip events remain authoritative.

## [0.6.0]

Completed or cancelled subscription streams now release their event buffers,
collector-job references, and timestamp clocks after ordered draining finishes.
A lightweight generation-owned ID tombstone still prevents a subscription from
being collected or used again before the connection generation changes.

Anonymous and credentialed authentication denials now both produce
`AUTHENTICATION_REJECTED` through a typed internal cause rather than exception
message matching.

Time-based recording add/update messages now match the pinned TVHeadend wire
shape: channel, name, and title may be absent; channel IDs retain the complete
unsigned-u32 range; and signed `-1` start/stop values become explicit nullable
unset times. Present known fields are decoded strictly, and valid minute values
are `0..1439`.

**BREAKING (Kotlin source + JVM binary):** `HtspTimerecEntryAddMessage` makes
channel, name, title, start, and stop nullable, and both timerec message types
expose channel IDs as `Long?` instead of `Int?`/`Int`. Consumers must handle
all-channel rules and unset intervals explicitly.

Subscription streams now preserve a durable, payload-free terminal reason for
generation replacement, remote EOF, transport I/O, framing, malformed server
messages, timeout teardown, local retirement, publication-boundary failure, and
otherwise unclassified reader failure. Every terminal remains ordered after
already committed subscription events.

**BREAKING (Kotlin source + JVM binary):**
`HtspSubscriptionTermination.TRANSPORT_CLOSED` is replaced by the bounded
attribution cases above. Consumers must handle the expanded enum and map each
reason according to their own recovery policy.

## [0.5.0]

Automatic-recording add metadata now accepts the nullable `comment`, `name`,
`owner`, and `creator` fields emitted by TVHeadend. Their typed properties are
null when absent; present fields with invalid wire types remain incompatible.

**BREAKING (Kotlin source + behavior; JVM linkage unchanged):** those four
properties are now nullable. Source consumers must handle absence, and binaries
compiled against 0.4.0 can observe null from their existing JVM getters.

## [0.4.0]

Malformed recognized global metadata now fails the transport as an incompatible
server instead of disappearing before a later initial-sync marker. Unknown
future asynchronous methods remain ignored for forward compatibility.

Subscription event collection can now be fenced to an expected connection
generation. A stale cold flow fails before it can consume the same numeric id in
a replacement generation. The new overload is abstract so implementations can
provide atomic registration; source implementations of `HtspConnection` must add
it, while existing binaries remain compatible unless they are asked to invoke the
new overload.

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
