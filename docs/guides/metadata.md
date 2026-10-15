# Metadata sync and event streams

After `enableAsyncMetadata`, the server pushes channels, tags, EPG events, DVR
entries and recording rules on the connection's `events` flow. This guide covers how that flow is
buffered, what happens when a consumer falls behind, and how to keep a large EPG
sync manageable.

## Buffering and overflow

The `events` flow has an independent bounded queue per collector. Immutable
decoded messages may be shared; queue saturation never blocks the reader,
RPC replies, or other collectors. `HtspEventBufferOptions` on
`createHtspConnection` defaults to 8 MiB/8192 events per metadata collector and
per subscription; pass explicit budgets if their numbers must stay fixed.
Bytes count retained frame bodies, not exact heap. The queues drop newest
metadata and report a coalesced `MetadataOverflow`; failure and overflow markers
are unbudgeted and never dropped. Counts coalesce only within a generation:
losses after reconnect start a new marker, leaving the old marker in order.
Handle these markers rather than filtering them away. The library never reconnects or re-requests data.

## Large EPG syncs

A full EPG sync (roughly 150 channels × 7–14 days: 26k–84k events, 15–100 MB)
can overflow a slow consumer; XMLTV imports also produce `eventUpdate` bursts.
Consider `epgMaxTimeEpochSeconds`, `epg = false` plus paged `getEvents`, and
consumer-side batching. After overflow, do not wait for `initialSyncCompleted`,
which is the last sync message and may be dropped. A second `enableAsyncMetadata`
only resends EPG. Full recovery is consumer-owned reconnect and full sync with
backoff. The initial-sync helper returns `TransportUnavailable` promptly for its
own generation's overflow: the transport may still be live, but this sync is incomplete.

## Memory limits

One TCP stream still imposes head-of-line ordering. Server `queueDepthBytes` is
separate from the client queues. Approximate worst-case memory is the sum of
all budgets plus one maximum frame (up to 32 MiB) per subscription plus the
largest metadata queue. This excludes heap and decoded-object overhead, not an
exact heap cap. There is no connection-wide cap or time ceiling.

See [metadata and subscription event streams](../public-api.md#metadata-and-subscription-event-streams)
and [metadata snapshots and sparse updates](../public-api.md#metadata-snapshots-and-sparse-updates)
for the full contract.
