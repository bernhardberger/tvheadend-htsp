# API behavior: outcomes, errors, and cancellation

How the public API reports success and failure, and what you can rely on when
writing against it.

## Naming conventions

Fixed-unit time quantities end in `Ms`, `Us`, `Seconds`, `Minutes`, or `Days`.
Absolute wall-clock values use `EpochSeconds`; times of day use
`MinutesSinceMidnight`. Byte quantities use `Bytes`, except names already
expressing bytes such as `byteCount`. Container sizes and stdlib-mirroring
parameters keep their stdlib names: `HtspBinary.size` and
`copyInto(destination, destinationOffset)`. Sibling types use the same concept name:
`protocolVersion`, `retentionDays`, `removalDays`, `startExtraMinutes`,
`stopExtraMinutes`, and `playPositionSeconds`.

KDoc records differing HTSP field names, units, and sentinel values. Names do
not change integer widths; the protocol field-domain mapping below still applies.
`configName` (name) and `configId` (UUID) are distinct concepts, and
`HtspSubscriptionEvent.Packet.packet` remains unchanged.

Queue `delayUs` and stream `frameDurationUs` use the existing subscription-clock
lookup to normalize to microseconds, like mux packets. Seek/skip `time` and
timeshift bounds retain the negotiated clock, because near-live commands need
the exact original coordinates rather than a lossy conversion round trip.
`ecmTime` has no established unit in the pinned sender and is not guessed.
The [units table](htsp-protocol/README.md#units) records the wire fields and
source lines. `PublicQuantityNamingTest` enforces time-unit suffixes on public
properties and constructor/function parameters; byte suffixes remain a documented
convention. The test uses a small
commented exception list for those coordinates and non-time concepts.

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
  sharpest case because it collides with `kotlinx.coroutines.channels.Channel`, which
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

During connect, a malformed hello or authenticate reply returns
`HtspConnectOutcome.Failed` with `INCOMPATIBLE_SERVER` and retires the attempt's
transport. An explicit authenticate access denial remains `AUTHENTICATION_REJECTED`.

The closed request failure cases are:

- `ServerError`: an explicit server refusal, with optional untrusted server error text.
- `AccessDenied`: the server explicitly denied access.
- `ConnectionLimit`: the server explicitly denied access because of its connection limit.
- `NotSupported`: the negotiated protocol is too old or the server reports an unknown method.
- `Timeout`: the response deadline elapsed; this does not prove the server did nothing.
- `TransportUnavailable`: no usable transport was available.
- `MalformedReply`: a reply arrived but could not be decoded, either because an envelope
  field was malformed or because the typed decoder could not map the reply. This is
  payload-free, with no server text or throwable, and is not a server refusal. The
  server may have performed the request: a subscribe may have created a server-side
  subscription, and its stream stays open (send unsubscribe if it is not wanted).
  Do not blindly retry non-idempotent requests such as `addDvrEntry`.

Subscribe streams terminate with `SUBSCRIBE_REJECTED` for classified server rejections
(`ServerError`, `AccessDenied`, `ConnectionLimit`, `NotSupported`), not for
`MalformedReply`, timeout, or caller cancellation. Late replies after timeout do not
change that stream decision.

DVR mutation refusals (`error` or integer `success: 0`) return
`HtspResult.ServerError`, not an `Ok` response carrying `success`/`error` fields.
Without an explicit error, missing or wrong-typed `success`, integers other than
0 or 1, and malformed success payloads return `MalformedReply`.
`AddDvrEntryResponse.entryId` is required; update, stop, cancel, and delete return
acknowledgement objects. `HtspDvrMutationResponse` is a memberless marker.
`Ok` means the server acknowledged the request, not that the recording state
has already changed; observe DVR metadata updates for that state.

## Cancellation stays cancellation

Cancelling the calling coroutine cancels the in-flight call, which propagates
CancellationException like any other suspending Kotlin code. Cancellation never
shows up disguised as a failure outcome or a transport-failure event. A stale
generation fence before request dispatch instead returns `HtspResult.TransportUnavailable`.
`disconnect` and `close` return Boolean: true means this call changed state, false
means a stale fence or nothing to do. Disconnect retires an attempt/transport or
clears sticky Error to Disconnected; already disconnected or closed returns false.
Close returns true only on the first actual close, including from Disconnected.
Both decide atomically with transport retirement and leave replacements untouched.
Disconnect keeps the current generation, as does plain transport loss, so
`close(generation)` can still close the service after either event. A later admitted
connect attempt invalidates that generation, even if the attempt fails.
A reused live connection does not replace its generation. A stale `close(generation)`
does not terminally close the service; owners wanting unconditional terminal shutdown
must call `close()` without a generation.
Cleanup completes even for an already-cancelled caller, then propagates caller cancellation.
A connect superseded by another connect, disconnect, or owner close
returns `HtspConnectOutcome.Failed` with `SUPERSEDED`, not cancellation.

`execute` uses one timeout budget for generation capture, handshake serialization,
request encoding/admission, write serialization, socket write/flush, and the reply
wait. A timeout or cancellation while queued does not retire the connection.
Once an ordinary frame has been completely written, cancelling its reply wait also leaves the connection
live. Aborting an incomplete write retires the exact socket because its frame may
be partial. A started direct handshake is retired on cancellation or timeout even
after the frame is written, because its server-side state may have changed.
An enclosing coroutine timeout is caller cancellation, not the request's owned
timeout. A completed reply is returned unchanged even if its generation loses the
transport or is replaced while the request is in flight. Fences apply before dispatch,
not after a server-acknowledged mutation.
Use generation identities to reject stale application work in consumer-owned
serialization.
To migrate from removed `commitIfCurrent`/`commitIfLive`, snapshot
`liveConnection.value`, compare its `generation`, and tag application state with
that generation under application-owned serialization.

Socket creation, DNS resolution, connect, and writes run on the supplied I/O
dispatcher; it must accommodate concurrent blocking reader and writer work, such
as `Dispatchers.IO`. Blocking socket operations are caller-owned and cancellation
closes their captured raw socket before waiting for the worker to finish. System
DNS and arbitrary application factories cannot be reliably interrupted; their
workers must return before cancellation completes. `connectTimeoutMs` bounds TCP
connect, not DNS or factory execution. Late factory sockets are closed rather than
installed after cancellation.

The transport's silence watchdog uses twice the configured handshake response
timeout. Before a frame starts, it requires both that much incoming silence and
an outstanding fully written request of that age; previous idle time and local
write/dispatcher queues do not count against the server. A partial frame may recover
from a socket timeout, but a consecutive timeout streak gets only the same
watchdog interval of grace after its first timeout. Successful byte progress resets that grace. Expiration
is checked at socket-read timeout boundaries, so a long socket-read timeout also
delays detection. A connection with neither pending work nor a partial frame may
remain idle indefinitely.

## Metadata and subscription event streams

`HtspConnection.connectionState` exposes the service-owned current lifecycle as
a native hot `StateFlow<HtspConnectionState>`; `liveConnection` is also a native
StateFlow, including `onSubscription` semantics. Synchronous `value` reads may
briefly lag internal truth. Publication is eventual, never backwards: older
snapshots cannot replace newer ones. The two flows are not an atomic read pair;
publication orders them so `Connected` is never exposed with a null live connection
at any single instant, but two separate reads (or `combine` on a multi-threaded
dispatcher) can still observe `Connected` followed by a null live connection. Route
requests through `liveConnection.value` and its `generation`; never `!!` it because
`connectionState` read `Connected`.
StateFlow conflation
applies, so consumers should treat it as current state rather than an audit log
of every short-lived transition. After transport failure, `Error(failure)` stays
until the next `connect`, `disconnect`, or `close`. The unbudgeted
`ConnectionFailure` event reports unsolicited failures only of established live
generations (including direct-hello rejection). Failed connect attempts report only
their `HtspConnectOutcome.Failed` to the caller and sticky Error to state observers,
not a ConnectionFailure event. Live failure events are authoritative, not a count of
observed state transitions. Failure transitions directly to Error without an intermediate
Disconnected. A failed reader exits promptly, including during a
handshake; the connect result captures its own successful live snapshot atomically.

The library never invokes consumer code under its lifecycle or queue locks.
`Dispatchers.Unconfined` (or another immediate dispatcher) can run consumers on
library threads, including the transport reader, and those consumers must not
block. A blocking immediate consumer can delay delivery to others. State
publication is serialized separately, so it can also delay another thread
publishing state, and an immediate state consumer that blocks on another lifecycle
operation (for example `runBlocking { close() }`) can deadlock. Prefer a real
dispatcher for consumer code.

`HtspConnection.events` has replay zero and carries metadata server messages and
connection failures and overflow markers. Every collector has its own bounded
queue; immutable decoded events may be shared between queues. Queue saturation
never blocks the reader. On overflow, newest metadata is dropped only for that
collector. One pending `MetadataOverflow` marker per generation coalesces the count
at the first loss position, after earlier queued events and before later admitted
events. Losses after reconnect start a new marker for the new generation; the old
marker stays in its original position. Delivery then continues. `ConnectionFailure`
and `MetadataOverflow` are outside the budget and are never dropped.

Pass `HtspEventBufferOptions` to `createHtspConnection`, not to `connect`.
Defaults are 8 MiB and 8192 events per metadata collector and per subscription.
Bytes count retained server frame bodies, not exact heap usage. Byte limits are
1 MiB..1 GiB and event limits 256..1,048,576. Both limits apply independently;
construction and `copy` validate them. There is no connection-wide cap or time
ceiling. Defaults may change in minor releases; specify values for fixed budgets.

High-rate subscription traffic is isolated by id through
`HtspConnection.subscriptionEvents`. The returned flow is cold: collection
registers the unsigned-u32 id and must start before the matching `subscribe`
request. An id may be collected once in a connection generation, even after the
flow has completed. A subscribe call without an active registration is rejected
before its wire write.
Collecting when the generation is not live emits `Terminated(GENERATION_LOST)`
and completes normally.

Each stream shares its byte and event budgets between packets and controls
(at least 256 event slots). When it fills, the connection
evicts packets only and inserts an eager `Dropped` marker where each packet was
removed. Adjacent markers may be combined. Drop markers are unbudgeted. If a full
queue has no packet to evict, an incoming packet becomes an ordered `Dropped`
marker, while an incoming control ends only that subscription with
`Terminated(CONSUMER_OVERFLOW)` after its queued events drain. The consumer must
send `unsubscribe`; the library does not send it automatically. An oversized
packet is admitted once all other packets have been evicted, subject to the
event count limit. An oversized metadata event is dropped and counted instead.
After `Dropped`, discard video until the next keyframe. A malformed packet with a trustworthy
subscription id is reported in the same order with `Dropped`. A malformed
subscription control or untrustworthy packet envelope closes the incompatible
transport instead of disappearing.

A single TCP stream still orders all traffic; these queues do not eliminate
server-side or network head-of-line blocking. The server's `queueDepthBytes`
subscription option is separate from client event budgets. Worst-case memory is
approximately the sum of all budgets plus one maximum frame per subscription plus
the largest metadata queue. This excludes heap and decoded-object overhead; it is
not an exact heap guarantee. A legal HTSP frame body is at most 32 MiB.

A full EPG sync can overflow a slow consumer: roughly 150 channels × 7–14 days
can mean 26k–84k events and 15–100 MB on the wire. Use `epgMaxTimeEpochSeconds`,
`epg = false` plus paged `getEvents`, or consumer-side batching. XMLTV imports
also cause `eventUpdate` bursts. After `MetadataOverflow`, do not keep waiting
for `initialSyncCompleted`: it is the last sync message and may have been dropped.
A second `enableAsyncMetadata` on a live connection only resends EPG; full
recovery requires consumer-owned reconnect plus full sync, with backoff. The
library never reconnects or re-requests data automatically. Initial-sync wait
helpers return `TransportUnavailable` promptly when their own generation's queue
overflows: synchronization is incomplete even if the transport remains live.

This includes queue-delay normalization overflow: an s64 `delay` that cannot
fit in microseconds is a malformed subscription control, not a dropped packet.
The connection reports `INCOMPATIBLE_SERVER`, terminates registered subscription
streams with `MALFORMED_MESSAGE`, and makes pending requests transport-unavailable.

`Stopped` is an ordered stream interruption, not subscription retirement. Keep
collecting: the same id may receive another `Started` with replacement stream and
source metadata, then more packets. Do not infer retirement from status text.
A successful unsubscribe acknowledgement drains committed events and completes
the flow. When the subscribe reply is classified as a server refusal
(`ServerError`, `AccessDenied`, `ConnectionLimit`, or `NotSupported`), the flow
delivers events committed before the reply, then ends with
`Terminated(SUBSCRIBE_REJECTED)`; the id remains used for that generation. A
timeout or cancellation alone leaves the flow open, because the server may still
have created the subscription. A refusal after caller cancellation is observed
only until the original request deadline; timeout or generation retirement removes
the late-reply observer. A
`MalformedReply` leaves the flow open; send `unsubscribe` to release it. Malformed
`noaccess` takes precedence even over a string `error`, since the reply cannot be
classified as a refusal. Stream termination depends on the classified result, not
`serverMessage`: `noaccess: 1` with malformed `connlimit` ends it with
`AccessDenied`. Generation, transport or local
retirement ends the flow with a final `Terminated`, even after `Stopped`. Collector cancellation remains
`CancellationException`. Reconfiguration does not reset the subscription's
negotiated timestamp clock or permit a second collection/subscribe for that id.

This follows the pinned upstream's `service_restart_streams` in `src/service.c`
(lines 1050-1073): a composition change emits `SMT_STOP` with
`SM_CODE_SOURCE_RECONFIGURED`, followed by `SMT_START`. In `src/htsp_server.c`,
lines 4656-4663 forward these through the same subscription; lines 4401-4423
serialize the same id without destroying it. Unsubscribe separately acknowledges
then destroys the subscription (lines 2751-2773). See the
[upstream pin](htsp-protocol/upstream.json).

Consumers must handle repeated `Started` and reconfigure their decoding pipeline.

Use `enableAsyncMetadataAwaitingInitialSync` to enable metadata and wait for the
unsequenced `initialSyncCompleted` marker. It installs its generation-scoped
observer before sending the request, so a marker adjacent to or preceding the
acknowledgement is retained. One timeout covers both phases and returns
`HtspResult.Timeout`; caller cancellation remains
cancellation; a stale generation returns `TransportUnavailable`. Because the marker has no request sequence, callers must serialize
this orchestration within a connection generation.

Autorec/timerec updates are not sparse patches for the channel: omitting `channel`
clears it on the server (pinned `src/htsp_server.c`, lines 700–703). Send the channel
when it must remain selected.

Each subscription id may also be sent in only one `subscribe` request per
connection generation; local reuse throws `IllegalStateException` without
retiring the connection. The request's nullable Boolean `ninetyKhz` field selects the
packet clock: absent or false is native microseconds and true is 90
kHz. `HtspMuxPacketMessage` always exposes `decodingTimeUs`,
`presentationTimeUs`, and non-null `durationUs` in microseconds. Missing PTS or
DTS remains `null`; frame type is ASCII I/P/B or the unknown sentinel `-1`.
TVHeadend's wire frame type zero is normalized to the same unknown sentinel.
`SubscribeResponse.ninetyKhz` and `normalizedTimestamps` are strict nullable
boolean observations.

`subscriptionSkipNearLive` derives one ordinary absolute time skip from an
observed `HtspTimeshiftStatusMessage`, the matching
`SubscriptionTimestampClock`, and an explicit positive margin in seconds. Both
status bounds must be present, the target `end - margin` must remain within the
observed buffer, and the target must be safe for TVHeadend's selected-clock
integer conversion. Validation fails before dispatch. The caller owns status
provenance, generation fencing, and per-subscription navigation serialization.
A successful result acknowledges only the synchronous request reply; matching
ordered `Timeshift` and `Skipped` events establish success or rejection and may
precede that reply. Already-live subscriptions, buffers without an indexed
frame, and full buffers can produce server-specific outcomes. The helper never
falls back to `subscriptionLive` and does not promise exact live mode.

## Binary payload access

`HtspBinary` owns its content and retains content equality and redacted
rendering. Public construction and standalone map decoding take a defensive
snapshot; typed mux decoding and private typed file reads transfer codec-owned
payloads internally without another payload-sized array. Use `size` to
allocate the final consumer buffer and `copyInto` to write directly into it.
The array copy returns the number of bytes written and copies only the prefix
that fits after the requested destination offset. `copyInto(ByteBuffer)` instead
writes all content at the current position and advances it, or throws
`BufferOverflowException` without changing the buffer when remaining space is too
small (all-or-nothing). `toByteArray()` remains
available when a standalone defensive copy is more convenient. No borrowed
mutable-array access is exposed.

## Mergeable metadata messages

Channel, tag, event, and DVR-entry add/update messages and
`HtspSubscriptionStartMessage` are data classes. Reducers can merge partial
updates with `copy()` and compare values structurally. Public list inputs are
still copied to immutable snapshots during construction and replacement, and
every copied unsigned field is validated again. Subscription-start binary
metadata participates through `HtspBinary` content equality rather than raw
array identity. Message `toString()` output remains redacted and does not expose
paths, server errors, subscription identifiers, or payload content.

## Evolving data classes

Public models, requests, responses, and messages generally are data classes.
Immutable metadata aggregate wrappers instead provide explicit structural
`equals`/`hashCode`, defensive snapshots, and redacted `toString`. A data
class's constructor and generated `copy` change their JVM signatures when a
property is added, so a compatible minor release adds a property this way:

```kotlin
@ConsistentCopyVisibility
public data class Example private constructor(
    public val id: Long,
    public val added: Long?,
    private val evolution: Unit,
) {
    public constructor(id: Long, added: Long? = null) : this(id, added, Unit)

    @Deprecated("Retained for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor(id: Long) : this(id, null, Unit)

    public fun copy(id: Long = this.id, added: Long? = this.added): Example = Example(id, added)

    @Deprecated("Retained for binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun copy(id: Long = this.id): Example = copy(id, added)
}
```

- Append the new property after the existing public ones, nullable or with a
  default; never reorder or remove properties.
- Make the primary constructor private with `@ConsistentCopyVisibility` (the
  mergeable messages above already do) and expose a public constructor and
  `copy` with the full parameter list.
- Keep each previous public constructor and `copy` as a hidden deprecated
  overload with exactly its previous parameters and default values, so the
  generated default-argument bridges survive. If every previous constructor
  parameter had a default, also keep a hidden no-argument constructor.
- In `api/htsp.api` the old signatures become `synthetic`, so existing
  binaries still link; the diff must contain no removed declarations.

Existing `componentN` functions keep their positions, and equality, hashing and
`copy` include the new property.

## Argument validation and lifecycle calls

### Protocol field-domain mapping

Wire flags use `Boolean` or `Boolean?`; null preserves absence. Integer flags
encode as 0/1. Incoming integer flags decode zero as false and any nonzero
integer as true for message and reply data fields; non-integer types remain malformed.
Reply envelope flags `success`, `noaccess`, and `connlimit` remain strict 0/1.
This is separate from HTSP's native Boolean wire type, which remains in use where
the server reads it (for example, `epgQuery.fullText` and `mergeText`).

Each wire field's documented domain decides its Kotlin type: u32 → `Long`
because 0..4294967295 does not fit `Int`; s32 → `Int`; s64 → `Long`. This rule
also applies to fields with small validated ranges. Library-level configuration
uses `Long` for durations. Socket connect and read timeouts must be in
1..2147483647 milliseconds to fit the JDK's `Int` socket APIs; larger values
are rejected, never truncated.

Passing an invalid argument, such as a non-positive timeout, may throw
`IllegalArgumentException`. Lifecycle calls `disconnect` and `close` return
Boolean: true when this call changes state, false for stale or no-op cleanup.

## Socket injection

`createHtspConnection` accepts an optional `socketFactory`. The default creates
a real JVM socket; an injected factory must return a fresh, initially
unconnected `java.net.Socket` for each connection attempt. This is a trusted raw
transport boundary for deterministic integration and embedding needs: custom
sockets must not block during construction or log, retain, or expose HTSP wire
traffic and credentials.

## What outcome values deliberately omit

Failure values never expose throwables, endpoints, credentials, digest or
challenge bytes, paths, sequence numbers, subscription IDs, or generation
identities. `HtspResult.ServerError.serverMessage` carries the reply's `error`
string whenever the reply had one, and is null otherwise, such as for an
unsuccessful DVR acknowledgement without error text. It does not determine
subscribe-stream termination. The pinned
upstream `htsp_server.c` sends only fixed error literals
translated via `tvh_gettext_lang` into the connection language.

Treat that message as untrusted, user-displayable text, not a stable error code.
`toString()` never renders it. `ServerError` is a data class: match it with
`is HtspResult.ServerError`, and remember that equality includes `serverMessage`.

Requests reach the server only through the finite typed catalog:
`HtspConnection.execute` accepts those request types and nothing else, so there
is no raw "method string plus map" escape hatch and no way to subclass in a
custom request from outside the library. Every convenience request calls this
member, which lets custom `HtspConnection` implementations intercept typed calls
without relying on service internals.
