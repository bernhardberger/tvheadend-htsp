# Live subscriptions, timeshift, and packets

A subscription asks the server to stream one channel to you: stream metadata
first, then a steady flow of audio and video packets alongside status
messages. That traffic is far denser than metadata, so every subscription gets
its own ordered flow, `connection.subscriptionEvents(id)`, separate from
`events`. This guide covers starting a subscription, the events and end states
of its flow, what happens when you fall behind, timeshift, and the clocks on
packets.

It assumes a live connection; see the [connecting guide](connecting.md). The
snippets omit imports: requests are in
`at.bernhardberger.tvheadend.htsp.requests`, messages in
`at.bernhardberger.tvheadend.htsp.messages`, and `HtspSubscriptionEvent` in
`at.bernhardberger.tvheadend.htsp.connection`. The complete file is
[`SubscriptionsGuide.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/SubscriptionsGuide.kt),
compiled against the staged artifact in CI. The library delivers packets; it does
not decode or play them.

## Collect before you subscribe

You choose the subscription id, an unsigned 32-bit number, and it names the flow
and the request. Start collecting `subscriptionEvents(id)` before you send
`subscribe` with that id. Packets can start arriving before the reply does, and
the flow is what registers the id:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/SubscriptionsGuide.kt#subscribe -->
```kotlin
suspend fun CoroutineScope.startStream(
    connection: HtspConnection,
    live: HtspLiveConnection,
    subscriptionId: Long,
    channelId: Long,
    handler: StreamHandler,
): Job? {
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        connection.subscriptionEvents(subscriptionId, live.generation).collect(handler::handle)
        handler.flowCompleted()
    }
    val result = connection.subscribe(
        subscriptionId = subscriptionId,
        channelId = channelId,
        timeshiftPeriodSeconds = 3_600L,
        expectedGeneration = live.generation,
    )
    if (result is HtspResult.Ok) return collector
    if (result is HtspResult.Timeout || result is HtspResult.MalformedReply) {
        connection.unsubscribe(subscriptionId, expectedGeneration = live.generation)
    }
    collector.cancel()
    return null
}
```

`CoroutineStart.UNDISPATCHED` makes the collector run up to its first suspension
before `launch` returns, so registration has happened when `subscribe` is
called. Pass the generation to both calls, as above, so a stale connection cannot
take the id.

The flow is cold and strict:

- A subscribe without an active collector for its id **throws**
  `IllegalStateException` before anything is sent. It is a programming error, not
  an `HtspResult`.
- An id can be collected once per connection generation, even after its flow has
  completed, and can be sent in `subscribe` once. Reusing it throws
  `IllegalStateException` too, without affecting the connection. A new generation
  starts with a clean slate.
- Collecting a flow object a second time throws as well.
- Collecting while the connection is not live, or with a stale generation, emits
  `Terminated(GENERATION_LOST)` and completes. With the generation overload it
  does not use up the replacement's id.
- A subscription id outside 0 to 4 294 967 295 throws `IllegalArgumentException`.

### Subscribe options

`subscribe` takes the channel as `channelId`, `channelName` or a
`SubscribeChannel`, and these options:

| Option | Effect |
|---|---|
| `profile` | the server's stream profile; `getProfiles` lists them |
| `weight` | scheduling weight of the subscription |
| `ninetyKhz` | selects the packet clock; see [clocks](#clocks-and-packets) |
| `timeshiftPeriodSeconds` | asks the server to buffer for [timeshift](#timeshift-and-seeking-near-the-live-edge) |
| `queueDepthBytes` | how many payload bytes the server queues before dropping frames; null uses its default |

The successful `SubscribeResponse` reports what the server settled on:
`ninetyKhz`, `normalizedTimestamps`, `weight` and `timeshiftPeriodSeconds`, each
null when the server did not say.

What the reply means for the stream:

| Reply | The flow |
|---|---|
| `Ok` | keeps delivering |
| `ServerError`, `AccessDenied`, `ConnectionLimit`, `NotSupported` | delivers what was committed before the reply, then ends with `Terminated(SUBSCRIBE_REJECTED)`; the id stays used in this generation |
| `Timeout`, or the caller was cancelled | stays open, because the server may have created the subscription anyway |
| `MalformedReply` | stays open for the same reason |
| `TransportUnavailable` | ends with the `Terminated` of the lost or stale generation |

When the flow is left open and you do not want the subscription, send
`unsubscribe`, as the snippet above does. A refusal that arrives after the
caller was cancelled still ends the stream, but only until the original request
deadline.

## Handle the events

Each `HtspSubscriptionEvent` is one of these, delivered in the order the server
sent them. The type is open, so keep an `else` branch.

| Event | Meaning |
|---|---|
| `Started` | stream list, source information and codec metadata (the codec header bytes of the first stream that has them); may repeat |
| `Packet` | one mux packet, a chunk of one elementary stream such as the video or an audio track, in committed server order |
| `Stopped` | the stream was interrupted |
| `Status` | the subscription's current status and error |
| `Grace` | the subscription's grace interval |
| `Speed` | a change of playback speed |
| `Timeshift` | timeshift buffer state |
| `Skipped` | the result of a skip |
| `Queue` | server-side queue counters and frame drops |
| `Signal` | tuner signal observations |
| `Descramble` | descrambling observations |
| `Dropped` | packets this client could not deliver; see [below](#overflow-and-dropped-packets) |
| `Terminated` | the final event of the flow |

A handler that follows the rules in this guide stays small. It keeps track of
keyframes: an `I` frame can be decoded without other frames, while `P` and `B`
frames depend on them, so a decoder can only start a stream cleanly at a
keyframe.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/SubscriptionsGuide.kt#stream-handler -->
```kotlin
class StreamHandler(
    private val onStarted: (HtspSubscriptionStartMessage) -> Unit,
    private val onPacket: (HtspMuxPacketMessage) -> Unit,
    /** Gets the `Terminated` reason, or null when the flow completed without one. */
    private val onEnded: (HtspSubscriptionTermination?) -> Unit,
) {
    /** The latest timeshift status, which `subscriptionSkipNearLive` needs. */
    var timeshift: HtspTimeshiftStatusMessage? = null
        private set

    /** Streams that have had a keyframe since the last start or gap. */
    private val synced = mutableSetOf<Long>()

    private var termination: HtspSubscriptionTermination? = null

    /** Call once the flow has completed, after the last `handle`. */
    fun flowCompleted() = onEnded(termination)

    fun handle(event: HtspSubscriptionEvent) {
        when (event) {
            is HtspSubscriptionEvent.Started -> {
                synced.clear()
                onStarted(event.message)
            }
            is HtspSubscriptionEvent.Packet -> {
                val packet = event.packet
                if (packet.frameType == HtspFrameType.I) synced += packet.streamIndex
                val predicted = packet.frameType == HtspFrameType.P || packet.frameType == HtspFrameType.B
                if (!predicted || packet.streamIndex in synced) onPacket(packet)
            }
            is HtspSubscriptionEvent.Dropped -> synced.clear()
            is HtspSubscriptionEvent.Timeshift -> timeshift = event.message
            is HtspSubscriptionEvent.Terminated -> termination = event.reason
            else -> Unit
        }
    }
}
```

### Stopped and started

`Stopped` is an interruption, not the end of the subscription. When the server
reconfigures the stream, for example because the channel's service composition
changed, it sends `Stopped` and then a new `Started` with replacement stream and
source metadata, on the same id, followed by more packets. Keep collecting, and
be ready to rebuild your decoding pipeline on every `Started`. Do not infer that
a subscription ended from the status text. Reconfiguration does not reset the
subscription's clock, and it does not allow a second collection or subscribe for
the id.

### How a flow ends

`Terminated(reason)` is the last event, and it follows any events already
queued. A flow also ends without it:

| End | Reason |
|---|---|
| completes without `Terminated` | a successful `unsubscribe`; committed events are delivered first |
| `SUBSCRIBE_REJECTED` | the server refused `subscribe` |
| `CONSUMER_OVERFLOW` | your queue overflowed on a control event |
| `GENERATION_LOST` | a newer connection replaced the generation |
| `LOCAL_RETIREMENT` | the connection was retired locally, for example by `disconnect` or `close` |
| `REMOTE_EOF`, `IO_FAILURE`, `FRAMING_FAILURE`, `TIMEOUT`, `MALFORMED_MESSAGE`, `PUBLICATION_FAILURE`, `INTERNAL_FAILURE` | the transport failed; `connectionState` and `ConnectionFailure` have the details |

A `Terminated` follows even a `Stopped`. The reason enum is open, so keep an
`else` branch.

`StreamHandler` reports every end that happens. `startStream` calls
`flowCompleted()` when `collect` returns, and `onEnded` then receives the
`Terminated` reason, or null when the flow completed without one, as it does
after a successful `unsubscribe`. Cancelling the collector cancels the flow like
any collection and propagates as cancellation, so `onEnded` is not called. It
does not end the subscription on the server either. Send `unsubscribe` for that:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/SubscriptionsGuide.kt#stop -->
```kotlin
suspend fun stopStream(connection: HtspConnection, live: HtspLiveConnection, subscriptionId: Long) {
    connection.unsubscribe(subscriptionId, expectedGeneration = live.generation)
}
```

The library never unsubscribes for you. After a connection loss all of its
subscriptions are gone, and resubscribing on the new generation, with new
collectors, is your job.

## Overflow and dropped packets

Each subscription has its own queue, and packets and control events share its
byte and event budgets. They are the ones set in `HtspEventBufferOptions`; see
[metadata sync](metadata.md#buffers-and-overflow). A subscription queue holds at
least 256 events. A full queue never blocks the reader, the replies, or other
subscriptions, and what it does next depends on what arrives:

- **Packets are sacrificed first.** The oldest queued packets are evicted, and a
  `Dropped(count)` marker takes the place of each one. Neighbouring markers merge.
  Markers do not count against the budget.
- **A single oversized packet is admitted** once all other packets have been
  evicted, as long as an event slot is free.
- **When no packet is left to evict**, an incoming packet becomes a `Dropped`
  marker, and an incoming control event ends the subscription: the queued events
  drain, then `Terminated(CONSUMER_OVERFLOW)` follows. Only that subscription is
  affected. The server still has it, so send `unsubscribe`.
- **A malformed mux packet** whose subscription id can still be trusted is
  reported with the same ordered `Dropped`.

After a `Dropped`, discard predicted frames of each stream until that stream's
next keyframe, because the frames after the gap may depend on frames you never
saw. The handler above does this per `streamIndex`.

Some faults close the whole connection instead of one subscription: a packet
whose envelope cannot be trusted, and a malformed subscription control message,
such as a queue delay that does not fit in microseconds. Every registered stream
then ends with `Terminated(MALFORMED_MESSAGE)` and pending requests return
`TransportUnavailable`.

Server-side drops are a separate mechanism: `queueDepthBytes` limits the server's
queue, and `Queue` events report its counters and the frames it dropped.

## Clocks and packets

`HtspMuxPacketMessage` carries one packet:

- `frameType` is `HtspFrameType.I`, `P`, `B` or `UNKNOWN`. `UNKNOWN` covers an
  absent value, wire zero and the explicit unknown value `-1`. Other codes are
  malformed. The enum is open, so keep an `else`.
- `decodingTimeUs` and `presentationTimeUs` are null when the server did not send
  them. `durationUs` is never null.
- `payload` is an `HtspBinary`; see
  [binary payload access](../public-api.md#binary-payload-access).
- `commercialAdvice` is the server's advice, when present.
- `streamIndex` identifies the stream, as listed in `Started`.

The timing fields are always in microseconds, whichever clock the subscription
uses. The connection applies the native or the 90 kHz clock that the matching
`subscribe` selected, `ninetyKhz = true` for 90 kHz and absent or false for
native microseconds. It does this for every packet, including those delivered
before the subscribe acknowledgement.

The clock still matters for positions. Timeshift and skip coordinates are kept
in the negotiated clock, because a command near the live edge needs the exact
coordinates the server uses, not a rounded conversion.

| Value | Unit |
|---|---|
| packet `decodingTimeUs`, `presentationTimeUs`, `durationUs` | microseconds |
| `Queue.delayUs`, stream `frameDurationUs` | microseconds |
| `Timeshift` `shift`, `start`, `end` | negotiated clock ticks |
| `Skipped.time`, and the `time` of seek and skip requests | negotiated clock ticks |
| `Descramble.ecmTime` | not specified by the server |

## Timeshift and seeking near the live edge

Timeshift needs `timeshiftPeriodSeconds` on `subscribe`. The server then buffers
that period, and `Timeshift` events describe the buffer: `start` and `end` are
the buffered range, `shift` is the current shift, and `full` says that the buffer
is full. Either bound may be null while the server has none.

To move close to the live edge without invoking the exact `subscriptionLive`
operation, keep the latest `Timeshift` event of the same subscription and
generation and call `subscriptionSkipNearLive`:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/SubscriptionsGuide.kt#near-live -->
```kotlin
suspend fun jumpNearLive(
    connection: HtspConnection,
    live: HtspLiveConnection,
    handler: StreamHandler,
    ninetyKhz: Boolean,
): HtspResult<*>? {
    val status = handler.timeshift ?: return null
    if (status.start == null || status.end == null) return null
    val clock = if (ninetyKhz) SubscriptionTimestampClock.NINETY_KHZ else SubscriptionTimestampClock.MICROSECONDS
    return connection.subscriptionSkipNearLive(
        status = status,
        clock = clock,
        marginSeconds = 5L,
        expectedGeneration = live.generation,
    )
}
```

The helper sends one ordinary absolute skip to `end - margin`. The margin
is in seconds and must be positive, and the clock must be the one the
subscription uses, so keep it next to the subscription. Before anything is sent,
the call throws `IllegalArgumentException` if either bound is missing, the target
would fall before `start` or outside what TVHeadend can convert, or the
arithmetic would overflow.

What it does not do:

- It chooses no default margin.
- It does not fall back to `subscriptionLive`, and it does not guarantee exact
  live mode.
- It does not wait for the result.

The `Ok` result only acknowledges the request. The outcome arrives as `Timeshift`
and `Skipped` events, which may even come before the reply. `Skipped.error` is a
flag that the skip failed, not an error code. An already-live subscription, a
buffer without an indexed frame, or a full buffer can give server-specific
results. Run navigation commands for one subscription one at a time, and use the
ordered events to decide what happened. `subscriptionLive` works the same way:
its reply acknowledges the request, and the subscription events report the
resulting position.

## Where to go next

- [Connecting, requests, and the connection lifecycle](connecting.md) for
  reconnecting and the generation fence.
- [Metadata sync and event streams](metadata.md) for the channel list you
  subscribe from.
- [Errors and troubleshooting](troubleshooting.md) for what each way a flow ends
  means and what to do.
- [Metadata and subscription event streams](../public-api.md#metadata-and-subscription-event-streams)
  for the exact rules.
