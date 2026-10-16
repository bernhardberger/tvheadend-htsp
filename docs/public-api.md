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

Timerec request and entity times of day are `Int?` minutes since midnight.
Requests accept `0..1439`; null omits the field. Autorec entity `approxTime`,
`start`, and `startWindow` also use nullable minutes in `0..1439`.
For both rule entities, required wire times of `-1` decode to null (unset);
every other value outside `0..1439` is malformed. The upstream class setters
normalize out-of-range inputs before emission; see the
[setter evidence](htsp-protocol/README.md#shared-entity-and-update-evidence).
All public protocol-version quantities are `Long` (nullable when
unknown), including connect options, live connections, request minimum versions,
and `MINIMUM_HTSP_PROTOCOL_VERSION`, following the u32 mapping without narrowing.

KDoc records differing HTSP field names, units, and sentinel values. Names do
not change integer widths; the protocol field-domain mapping below still applies.
`configName` (name) and wire `configId` (UUID) are distinct concepts; shared
entities expose the latter as `dvrConfigUuid`. Also,
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
can predict a name without looking it up. All top-level public types follow this.

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
has already changed; observe DVR metadata updates for that state. An
acknowledged `deleteDvrEntry` can leave the entry in place; see
[deleting a recording whose file is gone](guides/dvr.md#deleting-a-recording-whose-file-is-gone).

## Cancellation stays cancellation

Cancelling the calling coroutine cancels the in-flight call, which propagates
CancellationException like any other suspending Kotlin code. Cancellation never
shows up disguised as a failure outcome. Cancellation itself does not emit a
transport-failure event; a started write that exceeds its request deadline does.
A stale generation fence before request dispatch instead returns `HtspResult.TransportUnavailable`.
`disconnect` and `close` return Boolean: true means this call changed state, false
means a stale fence or nothing to do. Disconnect retires an attempt/transport or
clears sticky Error to Disconnected; already disconnected or closed returns false.
Close returns true only on the first actual close, including from Disconnected,
and publishes terminal `HtspConnectionState.Closed`.
Both decide atomically with transport retirement and leave replacements untouched.
Disconnect keeps the current generation, as does plain transport loss, so
`close(generation)` can still close the service after either event. A later admitted
connect attempt invalidates that generation, even if the attempt fails.
A reused live connection does not replace its generation. A stale `close(generation)`
does not terminally close the service; owners wanting unconditional terminal shutdown
must call `close()` without a generation.
Both `close()` and a successful `close(currentGeneration)` terminally close the
client. Later `connect` calls return `Failed(CLOSED)` without changing `Closed`;
`disconnect` and repeated `close` calls return false. Request calls still return
the existing `HtspResult.TransportUnavailable` (which has no failure-kind payload);
use `connectionState` to distinguish terminal closure from a reconnectable disconnect.
Owner close does not emit `ConnectionFailure`.
Cleanup completes even for an already-cancelled caller, then propagates caller cancellation.
A connect superseded by another connect, disconnect, or owner close
returns `HtspConnectOutcome.Failed` with `SUPERSEDED`, not cancellation.
Cancelling a fresh connect after it published `Connected` retires that attempt like
a local disconnect: its subscription streams end with `Terminated(LOCAL_RETIREMENT)`,
in-flight requests return `TransportUnavailable`, and `awaitTermination` returns
`Disconnected`. Cancelling a connect that reuses the live connection leaves it intact.

`execute` uses one timeout budget for generation capture, handshake serialization,
request encoding/admission, write serialization, socket write/flush, and the reply
wait. A timeout or cancellation while queued does not retire the connection.
Cancelling an ordinary request leaves the connection live. A started frame finishes
writing before cancellation is rethrown. This wait lasts at most the remaining
request timeout budget, plus scheduling delay. If the write cannot finish by that
deadline, the exact socket retires with `CONNECTION_TIMEOUT`, including a failed
generation termination and a `ConnectionFailure` event. The caller still gets
cancellation. A completed frame's late reply does not enter metadata event flows.
A started direct handshake is retired on cancellation or timeout even
after the frame is written, because its server-side state may have changed.
An enclosing coroutine timeout is caller cancellation, not the request's owned
timeout. A completed reply is returned unchanged even if its generation loses the
transport or is replaced while the request is in flight. Fences apply before dispatch,
not after a server-acknowledged mutation.
Use generation identities to reject stale application work in consumer-owned
serialization.
To migrate from removed `commitIfCurrent`/`commitIfLive`, snapshot
`connectionState.value`, take `connection` when it is `HtspConnectionState.Connected`,
compare its `generation`, and tag application state with that generation under
application-owned serialization.

Socket creation, DNS resolution, connect, and writes run on the supplied I/O
dispatcher; it must accommodate concurrent blocking reader and writer work, such
as `Dispatchers.IO`. Cancelling connection setup closes its captured raw socket
before waiting for the worker to finish. Ordinary request writes instead finish
within their remaining request budget as described above. Owner disconnect,
close, and connection replacement close the socket immediately, including while
a cancelled caller's write is finishing. System
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

## Await a generation's end

`HtspConnection.awaitTermination(generation)` waits for the live transport to end
without collecting metadata. Library-created connections latch exactly one result:
`HtspGenerationTermination.Failed(failure)` carries the same failure as that
generation's `ConnectionFailure`; `Disconnected` covers disconnect, close,
replacement, and other teardown without a failure event. Call before or after the
end, including after replacement. Cancelling an await leaves the result usable.
Foreign or publicly constructed tokens throw `IllegalArgumentException` before
suspending. An ended transport can still have the current generation identity.
A generation that never reached `Connected` ends as `Disconnected`, including a
token received through metadata during a failed handshake.

This is an out-of-band signal: it may resume before a collector drains earlier
metadata. The result may also become visible before `connectionState` reflects
the end; that state is published separately. `ConnectionFailure` in `events` and
`eventBatches` remains the in-order
signal for metadata consumers. The default for custom connections waits until
`connectionState` leaves `Connected` with the given generation, maps `Error` to
`Failed`, and otherwise returns `Disconnected`. It cannot check ownership, latch
past results, or attribute an error precisely to a generation.

## Metadata and subscription event streams

`HtspConnection.connectionState` exposes the service-owned current lifecycle as
a native hot `StateFlow<HtspConnectionState>`, including `onSubscription` semantics.
`Connected(host, port, connection)` carries its non-null `HtspLiveConnection` in
the same atomic snapshot. Read `generation`, `protocolVersion`, `dvrAccess`, and
`serverFacts` through that `connection`; there is no separate `liveConnection` flow.
Re-hello and authenticate updates replace the Connected snapshot atomically with
the updated connection facts. Synchronous `value` reads may briefly lag internal
truth. Publication is eventual, never backwards: older snapshots cannot replace
newer ones. Route requests using the captured connection's `generation` fence.
StateFlow conflation
applies, so consumers should treat it as current state rather than an audit log
of every short-lived transition. After transport failure, `Error(failure)` stays
until the next `connect`, `disconnect`, or `close`. The unbudgeted
`ConnectionFailure` event carries a non-null `generation` and reports unsolicited
failures only of established live generations (including direct-hello rejection).
Failed connect attempts report only
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

`HtspConnection.events` has replay zero and carries metadata server messages,
connection failures, overflow markers and malformed-metadata reports.
`HtspTransportEvent.ServerMessage.message`
is an `HtspMetadataMessage`; subscription stream messages are excluded from this
sealed hierarchy. Minor releases may add metadata subtypes, so keep an `else`
branch when matching. Every collector has its own bounded
queue; immutable decoded events may be shared between queues. Queue saturation
never blocks the reader. On overflow, newest metadata is dropped only for that
collector. One pending `MetadataOverflow` marker per generation coalesces the count
at the first loss position, after earlier queued events and before later admitted
events. Losses after reconnect start a new marker for the new generation; the old
marker stays in its original position. Delivery then continues. `ConnectionFailure`
and `MetadataOverflow` are outside the budget and are never dropped.

Malformed metadata is skipped without closing the connection and reported as
`MalformedMetadata(generation, count)`. It carries only the generation and positive
skipped-message count, with no method name, fields, identifiers or server text;
`toString()` redacts the generation. Stateful consumers (such as caches) must treat
this as **this generation's state is incomplete**. Later valid metadata still arrives.
Envelope faults and malformed subscription controls still close the connection;
malformed mux packets keep their ordered `Dropped` behavior.

Adjacent queued malformed reports for the same generation coalesce into one queue
entry, without crossing other queued events. Once the tail report is drained into
a collector's in-flight chunk, a subsequent report needs its own slot and may
overflow while the drained report is still in flight. Each entry consumes one metadata event
slot and zero frame bytes because the malformed payload is not retained. Coalescing
requires no additional budget, even when the queue is full. A report that cannot fit
is discarded into `MetadataOverflow`, adding its skipped-message count to
`droppedCount` at the existing first-loss position. Thus either the malformed report
or an overflow marker is observed before any later admitted message, including
`initialSyncCompleted`. Different generations never share a malformed report.

`HtspConnection.eventBatches(maxBatchSize = 512)` offers the same event stream in
non-empty, read-only lists of at most `maxBatchSize` entries. The limit must be in
`1..4096`; values outside that range throw `IllegalArgumentException` when the
function is called, before collection. Loosening this range in a later release
is compatible.
Each collection registers its own replay-zero queue and uses the same capacity,
byte budget, overflow placement and queued-tail malformed coalescing as `events`.
Start collection before enabling metadata. Cancellation propagates and removes
that collector in `finally`; an empty queue uses the same I/O-dispatcher wait as
`events`. Neither flow ends merely because the generation changes.

Batches contain whatever is queued at the drain, without waiting to fill. They
preserve event order, including `MetadataOverflow` and `MalformedMetadata` inside
the lists. A batch never spans a generation; batches split wherever the event
generation changes. Overflow counts are snapshotted under the queue lock before
emission. Each batch is a fresh list;
the library never mutates it afterwards or retains it in the buffer.

For `events`, an entry leaves the count and byte budgets immediately before its
`emit`. For `eventBatches`, **all entries of the batch** leave those budgets
immediately before `emit(batch)`, even if delivery suspends. The queue continues
filling while the collector processes a batch and can overflow at its usual limits.
Loss markers still precede later admitted events. Consumer-held batches are outside
the queue budgets. While processing one batch, allow for the queue's budget plus
one consumer-owned batch of at most `maxBatchSize` entries. Different collection
speeds can therefore produce different loss counts. Custom `HtspConnection`
implementations inherit a default that wraps each `events` emission in a singleton
list, preserving their own stream behavior.

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

A full EPG sync can overflow a slow consumer; see
[large EPG syncs](guides/metadata.md#large-epg-syncs) for sizes and mitigations.
After `MetadataOverflow`, do not keep waiting
for `initialSyncCompleted`: it is the last sync message and may have been dropped.
A second `enableAsyncMetadata` on a live connection only resends EPG; full
recovery requires consumer-owned reconnect plus full sync, with backoff. The
library never reconnects or re-requests data automatically. Initial-sync wait
helpers on library-created connections watch for the marker internally without a
second event collector, so they cannot overflow. Custom `HtspConnection` implementations retain an events-collecting
observer that returns `TransportUnavailable` if its generation overflows.

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

Initial-sync waits ignore `MalformedMetadata` reports and return `HtspResult.Ok(Unit)`
once the acknowledgement is Ok and the observed generation's completion marker arrives.
A malformed acknowledgement still returns `MalformedReply`. The caller's own `events`
collector must already be active: it receives malformed reports (or `MetadataOverflow`
if its queue fills) before any later admitted marker. Stateful consumers must observe
these events and treat that generation's state as incomplete, before and after initial
sync. The helper does not wait for the caller's collector to process them. If the
caller's collector overflows, the helper can still return `Ok`. Reconnecting
alone may encounter the same deterministic bad metadata again; recovery policy belongs
to the consumer.

Autorec/timerec updates always set the channel restriction. Both update request
constructors and their `HtspConnection` extensions require a non-null
`HtspRecordingRuleChannel`: pass the current `Id` to retain the restriction, or
explicit `Any` to clear it. `Any` omits wire `channelId`; a concrete `Id` sends it.
The pinned handlers start with a null channel and resolve a supplied non-negative
`channelId` (`src/htsp_server.c:2360–2383,2471–2494`); the shared converter always
writes the resolved channel UUID or an empty restriction on updates
(`src/htsp_server.c:700–703`). There is no wire-level "unchanged" channel option.

Each subscription id may also be sent in only one `subscribe` request per
connection generation; local reuse throws `IllegalStateException` without
retiring the connection. The request's nullable Boolean `ninetyKhz` field selects the
packet clock: absent or false is native microseconds and true is 90
kHz. `HtspMuxPacketMessage` always exposes `decodingTimeUs`,
`presentationTimeUs`, and non-null `durationUs` in microseconds. Missing PTS or
DTS remains `null`; frame type is `HtspFrameType.I`, `P`, or `B`.
Absent frame type, wire zero, and signed `-1` map to `HtspFrameType.UNKNOWN`;
other numeric codes remain malformed. Keep an `else` when matching this open enum.
`SubscribeResponse.ninetyKhz` and `normalizedTimestamps` are strict nullable
boolean observations.

`HtspServerFacts` construction and `copy` take immutable snapshots of capability
lists. Later mutations of caller-owned lists cannot change the observations;
null and an empty capability list remain distinct. Consumers can construct these
facts directly, including for custom connection fakes.

`subscriptionSkipNearLive` derives one ordinary absolute time skip from an
observed `HtspTimeshiftStatusMessage`, the matching
`SubscriptionTimestampClock`, and an explicit positive margin in seconds. Both
status bounds must be present, the target `end - margin` must remain within the
observed buffer, and the target must be safe for Tvheadend's selected-clock
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

## Metadata snapshots and sparse updates

The `model` package owns `HtspChannel`, `HtspTag`, `HtspEvent`, `HtspDvrEntry`,
`HtspAutorecEntry`, and `HtspTimerecEntry`, plus their nested value types.
Add and full Update messages wrap one entity (`channel`, `tag`, `event`, or
`entry`). Channel and event request replies use these same entities and decoders.

A full Update **replaces** the stored entity, or **inserts** it if its identity
has not yet been seen. A nullable property that is absent
on the wire becomes null, clearing any previous value. Do not merge non-null
properties into the old entity with `copy()`: that retains server-side clears.
The sole inclusion exception is `HtspTag.channelIds` (wire `members`): null means
"not included in this message", while an empty list means no members. Initial
sync sends `tagAdd` without members and then `tagUpdate` with members. Tags created
after initial sync arrive only as `tagUpdate`: pinned `src/htsp_server.c:3855–3869`
routes `htsp_tag_add` through `htsp_tag_update_msg` to
`htsp_build_tag(..., "tagUpdate", 1)`. Consumers must accept these as inserts.

Two distinct flat messages carry sparse updates:

- `HtspChannelNowNextMessage` applies only `currentEventId` and `nextEventId` to
  its `channelId`; zero is an explicit empty event identity.
- `HtspDvrEntryStatsMessage` applies only its recording statistics/state fields
  to its `entryId`. Conditional null statistics replace the corresponding old
  observations; they never clear non-statistics fields such as title or files.

Delete messages carry only identity. The library owns no entity cache or reducer.
Consumers should match both full and sparse messages and keep an `else` branch
for future `HtspMetadataMessage` variants. See [ADR 0002](adr/0002-entity-model-and-update-semantics.md).

Entities and `HtspSubscriptionStartMessage` compare structurally. Public list
inputs are copied to immutable snapshots during construction and `copy`, and
copied unsigned fields are validated again. Subscription-start binary metadata
uses `HtspBinary` content equality. Entity and full-message `toString()` output
remains redacted, as does DVR statistics output containing server errors.

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
  entities above already do) and expose a public constructor and
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
