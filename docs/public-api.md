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

## Cancellation stays cancellation

Cancelling the calling coroutine cancels the in-flight call, which propagates
CancellationException like any other suspending Kotlin code. Cancellation never
shows up disguised as a failure outcome or a transport-failure event. The same
applies when a call is abandoned because its fenced connection generation went stale.

`execute` uses one timeout budget for generation capture, handshake serialization,
request encoding/admission, write serialization, socket write/flush, and the reply
wait. A timeout or cancellation while queued does not retire the connection.
Once an ordinary frame has been completely written, cancelling its reply wait also leaves the connection
live. Aborting an incomplete write retires the exact socket because its frame may
be partial. A started direct handshake is retired on cancellation or timeout even
after the frame is written, because its server-side state may have changed.
An enclosing coroutine timeout is caller cancellation, not the request's owned
timeout. A completed ordinary reply remains valid if its generation subsequently
loses the transport; replacing the generation still cancels stale work.

`commitIfCurrent` and `commitIfLive` run their blocks under the generation lock.
Keep those blocks short and non-blocking: do not perform I/O or wait for another
HTSP operation inside them, because admission and the reader need the same lock.

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
a hot `StateFlow<HtspConnectionState>`. Its synchronous `value` is always
available, and collectors receive subsequent state changes. StateFlow conflation
applies, so consumers should treat it as current state rather than an audit log
of every short-lived transition.

`HtspConnection.events` has replay zero and carries metadata server messages and
connection failures only. It has an exact 1024-event burst budget shared by
independent collectors. An indefinitely stalled collector eventually
backpressures this bounded, never-drop stream.

High-rate subscription traffic is isolated by id through
`HtspConnection.subscriptionEvents`. The returned flow is cold: collection
registers the unsigned-u32 id and must start before the matching `subscribe`
request. An id may be collected once in a connection generation, even after the
flow has completed. A subscribe call without an active registration is rejected
before its wire write.

Each stream can hold 8192 server-produced events. When it fills, the connection
evicts packets only and inserts an eager `Dropped` marker where each packet was
removed. Adjacent markers may be combined. Controls and drop markers are never
discarded. If a full queue has no packet to evict, an incoming packet becomes an
ordered `Dropped` marker, while an incoming control backpressures the reader
until the collector makes room. A malformed packet with a trustworthy
subscription id is reported in the same order with `Dropped`. A malformed
subscription control or untrustworthy packet envelope closes the incompatible
transport instead of disappearing.

`Stopped` is an ordered stream interruption, not subscription retirement. Keep
collecting: the same id may receive another `Started` with replacement stream and
source metadata, then more packets. Do not infer retirement from status text.
A successful unsubscribe acknowledgement drains committed events and completes
the flow. Generation, transport or local retirement ends it with a final
`Terminated`, even after `Stopped`. Collector cancellation remains
`CancellationException`. Reconfiguration does not reset the subscription's
negotiated timestamp clock or permit a second collection/subscribe for that id.

This follows the pinned upstream's `service_restart_streams` in `src/service.c`
(lines 1050-1073): a composition change emits `SMT_STOP` with
`SM_CODE_SOURCE_RECONFIGURED`, followed by `SMT_START`. In `src/htsp_server.c`,
lines 4632-4639 forward these through the same subscription; lines 4377-4399
serialize the same id without destroying it. Unsubscribe separately acknowledges
then destroys the subscription (lines 2751-2773). See the
[upstream pin](htsp-protocol/upstream.json).

Consumers must handle repeated `Started` and reconfigure their decoding pipeline.
The SDK playback state machine currently treats `Stopped` as terminal and rejects
a second `Started`; upgrading this protocol library alone does not repair SDK or
application playback. SDK integration is tracked separately by P30-S1.

Use `enableAsyncMetadataAwaitingInitialSync` to enable metadata and wait for the
unsequenced `initialSyncCompleted` marker. It installs its generation-scoped
observer before sending the request, so a marker adjacent to or preceding the
acknowledgement is retained. One timeout covers both phases and returns
`HtspResult.Timeout`; caller cancellation and generation replacement remain
cancellation. Because the marker has no request sequence, callers must serialize
this orchestration within a connection generation.

Each subscription id may also be sent in only one `subscribe` request per
connection generation; local reuse throws `IllegalStateException` without
retiring the connection. The request's numeric `ninetyKhz` field selects the
packet clock: absent or zero is native microseconds and any nonzero value is 90
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
The bounded copy returns the number of bytes written and copies only the prefix
that fits after the requested destination offset. `toByteArray()` remains
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

## Argument validation and lifecycle calls

Passing an invalid argument, such as a non-positive timeout, may throw
`IllegalArgumentException`. Lifecycle calls such as `disconnect` and `close`
return `Unit`.

## Socket injection

`createHtspConnection` accepts an optional `socketFactory`. The default creates
a real JVM socket; an injected factory must return a fresh, initially
unconnected `java.net.Socket` for each connection attempt. This is a trusted raw
transport boundary for deterministic integration and embedding needs: custom
sockets must not block during construction or log, retain, or expose HTSP wire
traffic and credentials.

## What outcome values deliberately omit

Failure values are stable categories, not payloads. They never expose
throwables, server error text, endpoints, credentials, digest or challenge
bytes, paths, sequence numbers, subscription IDs, or generation identities, so
logs and crash reports built from them stay free of secrets and wire internals.

Requests reach the server only through the finite typed catalog:
`HtspConnection.execute` accepts those request types and nothing else, so there
is no raw "method string plus map" escape hatch and no way to subclass in a
custom request from outside the library. Every convenience request calls this
member, which lets custom `HtspConnection` implementations intercept typed calls
without relying on service internals.
