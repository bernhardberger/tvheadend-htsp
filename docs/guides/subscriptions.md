# Live subscriptions, timeshift, and packets

A subscription delivers one channel's stream as ordered events on its own flow,
separate from the metadata `events` flow.

## Subscribing

For a live subscription, start collecting `connection.subscriptionEvents(id)` before
sending `subscribe` with that id, using `launch(start = CoroutineStart.UNDISPATCHED)`
so registration has run. Each id can be collected once per connection
generation; a nonlive collection instead terminates with `GENERATION_LOST`.
A subscribe without an active collector is rejected before it
reaches the server. The ordered stream reports packet pressure or a rejected
malformed packet with a trustworthy subscription id as `Dropped`; discard video
until the next keyframe after a drop. Packets and controls share the budgets.
An oversized packet is admitted after evicting other packets; oversized metadata
is dropped. Control overflow drains the queue then delivers
`Terminated(CONSUMER_OVERFLOW)`, affecting only that subscription. The consumer
must send `unsubscribe`; the library never does so automatically. An untrustworthy
packet envelope closes the incompatible transport. `Stopped` is an interruption:
keep collecting because the same stream can receive another `Started`. A successful
unsubscribe acknowledgement drains and completes the stream; a server refusal of
`subscribe`, generation loss or transport loss ends it with `Terminated`.

## Seeking near the live edge

To request a position near the live edge without invoking the exact
`subscriptionLive` wire operation, retain an observed `Timeshift` event from the
same subscription generation and call `subscriptionSkipNearLive` with the
matching `SubscriptionTimestampClock` and an explicit positive margin. The
helper sends one normal absolute skip to `status.end - margin`; it does not
choose a default margin, fall back to `subscriptionLive`, guarantee exact live
mode, or wait for the asynchronous result. Serialize navigation and use the
ordered `Timeshift` and `Skipped` events to establish the outcome.

## Packet timestamps and frame types

Mux packet `decodingTimeUs`, `presentationTimeUs`, and `durationUs` values are
always microseconds. The connection applies the native or 90 kHz clock selected
by the matching subscribe request, including packets delivered before its
acknowledgement. `frameType` is `HtspFrameType.I`, `P`, or `B`, or `UNKNOWN`
when absent or sent as wire zero or the explicit unknown sentinel `-1`.

See [metadata and subscription event streams](../public-api.md#metadata-and-subscription-event-streams)
for the full contract.
