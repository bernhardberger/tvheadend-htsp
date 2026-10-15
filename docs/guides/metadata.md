# Metadata sync and event streams

Once you ask for it, TVHeadend sends everything it knows about channels, tags,
EPG events, recordings and recording rules, and then keeps sending changes as
they happen. All of it arrives on one flow, `connection.events`. The library
decodes and queues those messages for you. It keeps no copy of the data, so what
you build from them is yours, and so is the decision of what to do when you fall
behind. This guide covers the flow, the first sync, how to apply updates, and
what overflow and malformed metadata mean for the state you build.

It assumes you have a live connection; see the
[connecting guide](connecting.md). The snippets omit imports: messages live in
`at.bernhardberger.tvheadend.htsp.messages`, entities in
`at.bernhardberger.tvheadend.htsp.model`. The complete file is
[`MetadataGuide.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/MetadataGuide.kt),
compiled against the staged artifact in CI.

## The events flow

`connection.events` is a `Flow<HtspTransportEvent>` with these cases:

| Event | Meaning |
|---|---|
| `ServerMessage` | one decoded metadata message, with its generation and a one-based position in that generation |
| `ConnectionFailure` | a live connection failed; see the [connecting guide](connecting.md#watch-the-connection) |
| `MetadataOverflow` | your collector fell behind and metadata was dropped; see [below](#buffers-and-overflow) |
| `MalformedMetadata` | metadata the library could not decode was skipped; see [below](#malformed-metadata) |

The flow has no replay. It starts empty for each collector, so a collector that
starts late misses what was sent before it. Every collector gets its own queue,
and each event carries the generation it belongs to. Both facts shape the code
below.

`ServerMessage.message` is an `HtspMetadataMessage`, a sealed interface whose
subtypes are the Add, Update and Delete messages of each family, the two sparse
updates described below, and `HtspInitialSyncCompletedMessage`. Subscription
messages never appear here; they have [their own flows](subscriptions.md). Minor
releases may add subtypes, and the same holds for `HtspTransportEvent`, so keep
an `else` branch when you match.

## Run the initial sync

Start the collector first, then enable metadata. Starting it with
`CoroutineStart.UNDISPATCHED` registers it before the request goes out:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/MetadataGuide.kt#collect-then-sync -->
```kotlin
fun CoroutineScope.collectMetadata(connection: HtspConnection, directory: ChannelDirectory): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        connection.events.collect { event -> directory.accept(event) }
    }

suspend fun syncChannels(connection: HtspConnection, live: HtspLiveConnection): HtspResult<Unit> =
    connection.enableAsyncMetadataAwaitingInitialSync(
        epg = false,
        expectedGeneration = live.generation,
    )
```

`enableAsyncMetadataAwaitingInitialSync` sends `enableAsyncMetadata` and returns
once the server has acknowledged it and sent `initialSyncCompleted`, the marker
that follows the last piece of the first sync. It runs its own collector, so
yours and the helper's are independent. Its result is an `HtspResult<Unit>`:

| Result | Meaning |
|---|---|
| `Ok` | acknowledged, and the marker for this generation arrived |
| `Timeout` | no acknowledgement and marker within `timeoutMs`; the default is 30 s, one budget for both |
| `TransportUnavailable` | the connection or its generation ended, a stale `expectedGeneration` was passed, or this generation's queue overflowed |
| other failures | the acknowledgement itself failed, for example `AccessDenied` |

Metadata that the library could not decode does not fail the helper. It still
returns `Ok` when the marker arrives, and the skipped messages are reported as
[`MalformedMetadata`](#malformed-metadata) on `events`. A collector that was
already running sees that report, or an overflow marker that includes it, before
the marker. So `Ok` means the sync finished, not that every row arrived; your
collector decides whether the state is complete. Callers must not run two of
these helpers at once on the same generation, because the marker carries no
request sequence.

The helper returning is not the point at which your own state is ready. Your
collector may still be working through messages ahead of the marker, so publish
"ready" from the collector instead. `ChannelDirectory`, shown below, sets
`ready` when it processes `HtspInitialSyncCompletedMessage` for its generation,
and the UI waits for that flag. It says the marker arrived, not that every row
did; `incomplete` still decides whether to trust the state.

The options select what the server sends:

- `epg` turns EPG events on or off. Left null, it keeps the server's current
  setting.
- `epgMaxTimeEpochSeconds` sets how far ahead the server pushes EPG events. The
  server turns it into a window from the current time, at least 10 minutes
  long, and moves that window forward every 30 seconds, so new events keep
  arriving as time passes (`src/htsp_server.c:1666-1677`, `:4084-4107`). When EPG
  is not on yet, zero or a time that is not in the future means no limit. On a
  connection that already has EPG on, it can only extend an existing limit; it
  cannot add or remove one.
- `lastUpdateEpochSeconds` is the frontier of an earlier sync. Zero means never
  synchronized, so the server sends everything it may send you.
- `language` is sent to the server as the client's language code.

Access rights and the server's EPG settings still apply. A second
`enableAsyncMetadata` on the same live connection only resends EPG events.

## Apply updates

Each family has Add, Update and Delete messages, and two kinds of update carry
less than the full entity:

| Message | Carries | What to do with it |
|---|---|---|
| `...AddMessage`, `...UpdateMessage` | one full entity (`channel`, `tag`, `event` or `entry`) | store it, replacing any previous entity with the same id |
| `HtspChannelNowNextMessage` | `channelId`, `currentEventId`, `nextEventId` | set those two fields on the stored channel; zero means no event |
| `HtspDvrEntryStatsMessage` | `entryId` plus recording state, errors and data size | update only those fields on the stored entry |
| `...DeleteMessage` | the id only | remove the entity |

A full update **replaces** the stored entity, and a full update for an id you
have not seen is an **insert**. Treat Add and Update the same way. A property
that is absent from an update arrives as null, which means the server no longer
sets it. Do not merge the non-null properties of an update into the old entity
with `copy()`: that keeps values the server has cleared.

Two details trip people up:

- The tag membership list, `HtspTag.channelIds`, is the one property where null
  means "not included in this message". An empty list means the tag has no
  channels. During the initial sync the server sends `tagAdd` without members
  and then `tagUpdate` with them, and a tag created later arrives only as
  `tagUpdate`. Keep the previous members when an update's list is null.
- For `HtspDvrEntryStatsMessage`, a null optional statistic replaces the old
  observation, but the message never clears fields that are not statistics, such
  as the title or the files.

A small store that follows these rules, and ignores events from other
generations, looks like this:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/MetadataGuide.kt#directory -->
```kotlin
class ChannelDirectory(private val generation: HtspConnectionGeneration) {
    private val channels = mutableMapOf<Long, HtspChannel>()
    private val tags = mutableMapOf<Long, HtspTag>()
    private val events = mutableMapOf<Long, HtspEvent>()
    private val readyState = MutableStateFlow(false)

    /** True once this store has processed the initial sync marker of its generation. */
    val ready: StateFlow<Boolean> get() = readyState

    /** True once this generation lost or could not decode metadata. */
    var incomplete = false
        private set

    fun accept(event: HtspTransportEvent) {
        if (event.generation !== generation) return
        when (event) {
            is HtspTransportEvent.ServerMessage -> apply(event.message)
            is HtspTransportEvent.MetadataOverflow,
            is HtspTransportEvent.MalformedMetadata,
            -> incomplete = true
            else -> Unit
        }
    }

    private fun apply(message: HtspMetadataMessage) {
        when (message) {
            is HtspChannelAddMessage -> channels[message.channel.channelId] = message.channel
            is HtspChannelUpdateMessage -> channels[message.channel.channelId] = message.channel
            is HtspChannelNowNextMessage -> channels[message.channelId]?.let { channel ->
                channels[message.channelId] = channel.copy(
                    currentEventId = message.currentEventId,
                    nextEventId = message.nextEventId,
                )
            }
            is HtspChannelDeleteMessage -> channels.remove(message.channelId)
            is HtspTagAddMessage -> tags[message.tag.tagId] = message.tag
            is HtspTagUpdateMessage -> {
                val previous = tags[message.tag.tagId]
                tags[message.tag.tagId] =
                    if (message.tag.channelIds == null && previous != null) {
                        message.tag.copy(channelIds = previous.channelIds)
                    } else {
                        message.tag
                    }
            }
            is HtspTagDeleteMessage -> tags.remove(message.tagId)
            is HtspEventAddMessage -> events[message.event.eventId] = message.event
            is HtspEventUpdateMessage -> events[message.event.eventId] = message.event
            is HtspEventDeleteMessage -> events.remove(message.eventId)
            is HtspInitialSyncCompletedMessage -> readyState.value = true
            else -> Unit
        }
    }

    fun channel(channelId: Long): HtspChannel? = channels[channelId]

    fun event(eventId: Long): HtspEvent? = events[eventId]
}
```

Everything is stored by id, so a message that arrives twice leaves the same
state. `incomplete` is the flag you act on; see
[Recover](#recover-from-overflow-or-malformed-metadata). The class is not
thread-safe, so let the collector coroutine own it and hand out immutable
snapshots, not the maps.

The library has no cache, reducer or merge helper on purpose. Entities compare
structurally, copy their lists into immutable snapshots, and keep `toString()`
output redacted, but what to keep, index and persist is application code. The
[decision record](../adr/0002-entity-model-and-update-semantics.md) explains the
update rules and why the store stays out of the library.

## Now and next with programme titles

A channel and its `HtspChannelNowNextMessage` carry event ids, not titles. To
show what is on, keep the EPG events as well. `ChannelDirectory` above stores
them by event id: `HtspEventAddMessage` and
`HtspEventUpdateMessage` each carry the full event and replace the stored one,
and `HtspEventDeleteMessage` removes it. `HtspChannel.currentEventId` and
`nextEventId` then join a channel to its events, and zero means the channel has
no such event:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/MetadataGuide.kt#now-next -->
```kotlin
sealed interface Programme {
    /** The channel has no such event; the server sent id zero. */
    data object None : Programme

    /** The channel names an event that has not arrived (yet). */
    data class Pending(val eventId: Long) : Programme

    data class Known(val event: HtspEvent) : Programme
}

fun ChannelDirectory.programme(eventId: Long): Programme =
    if (eventId == 0L) Programme.None else event(eventId)?.let { Programme.Known(it) } ?: Programme.Pending(eventId)

fun Programme.label(): String = when (this) {
    Programme.None -> "Nothing scheduled"
    is Programme.Pending -> "Loading"
    is Programme.Known -> event.title ?: "Untitled"
}

fun ChannelDirectory.nowNext(channelId: Long): Pair<Programme, Programme>? =
    channel(channelId)?.let { programme(it.currentEventId) to programme(it.nextEventId) }
```

A channel can name an event the store does not hold yet, because the ids and the
events are separate messages and the event may come later in the sync or may
have been dropped by an overflow. `Pending` keeps that apart from `None`, so
the UI can show a placeholder and fill in the title when the event arrives,
instead of reporting an empty slot. `HtspEvent.title` is nullable, so `label`
has a fallback for that too.

This needs EPG events on the connection. The `syncChannels` helper above passes
`epg = false`; on the pinned server the channel messages still carry the event
ids, but the events are only pushed when EPG is on, so every id would stay
`Pending`. Sync with `epg = true`, or leave `epg` null on a server that already
has it on. The index holds every event the server pushes, which is the cost
described in [Large EPG syncs](#large-epg-syncs).

## Buffers and overflow

Every collector of `events` has its own bounded queue. Decoded messages are
immutable, so one message may sit in several queues at once. When a queue is
full, the library drops the newest metadata for that collector only, and reports
the loss with a `MetadataOverflow` marker.

- A full queue never blocks the transport reader, request replies or other
  collectors. A slow collector only hurts itself.
- One marker per generation counts the dropped messages. It sits at the position
  of the first loss, after the events already queued and before any event
  admitted later.
- Losses after a reconnect start a new marker for the new generation and leave
  the old marker in place.
- A single message larger than the byte budget is dropped and counted in the
  marker too.
- `MetadataOverflow` and `ConnectionFailure` do not count against the budget and
  are never dropped.

Queue sizes come from `HtspEventBufferOptions`, passed to `createHtspConnection`,
not to `connect`:

| Option | Default | Allowed range |
|---|---|---|
| `metadataQueueBytes` | 8 MiB | 1 MiB to 1 GiB |
| `metadataQueueEvents` | 8192 | 256 to 1 048 576 |
| `subscriptionQueueBytes` | 8 MiB | 1 MiB to 1 GiB |
| `subscriptionQueueEvents` | 8192 | 256 to 1 048 576 |

The two limits of a queue apply independently, and the options validate on
construction and on `copy`. Bytes count the retained frame bodies, not exact heap
use. The defaults can change in a minor release, so pass explicit values when the
numbers matter. The same budgets apply to each subscription; see
[subscriptions](subscriptions.md).

A `MetadataOverflow` is a statement that your state is incomplete. Handle it, as
`ChannelDirectory` does, rather than filtering it out.

## Malformed metadata

If a metadata message cannot be decoded, the library skips it, keeps the
connection open, and reports `MalformedMetadata(generation, count)`. The event
carries only the generation and the number of skipped messages. It has no method
name, fields, identifiers or server text, and `toString()` hides the generation.
Later valid metadata still arrives.

As with overflow, a stateful consumer must treat it as: this generation's state
is incomplete. Reports that follow each other for the same generation merge
into one entry that uses one queue slot and no frame bytes. If even that does not
fit, the skipped messages are added to the overflow marker at the same position.
So you see either a malformed report or an overflow marker before any later
message, including `initialSyncCompleted`.

Other protocol faults still close the connection: an envelope the library cannot
trust, and a malformed subscription control message. A malformed mux packet
becomes a `Dropped` event on its subscription instead; see
[subscriptions](subscriptions.md#overflow-and-dropped-packets).

## Recover from overflow or malformed metadata

The library never reconnects and never re-requests data. After an overflow or a
malformed report, you have two ways forward:

- Keep the state, flagged incomplete, and show it as stale.
- Drop it and sync again from scratch.

A repeated `enableAsyncMetadata` will not help, because it only resends EPG
events. A full recovery is a new connection and a new sync, with a growing delay
between attempts. Reconnecting alone may hit the same bad metadata again, so
keep watching for malformed reports on the new generation.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/MetadataGuide.kt#resync -->
```kotlin
class MetadataSession(val directory: ChannelDirectory, private val collector: Job) {
    fun stop() = collector.cancel()
}

suspend fun resync(
    scope: CoroutineScope,
    connection: HtspConnection,
    endpoint: HtspEndpoint,
    previous: MetadataSession?,
): MetadataSession? {
    previous?.stop()
    val outcome = connection.connect(endpoint, HtspConnectOptions(forceReconnect = true))
    val live = (outcome as? HtspConnectOutcome.Connected)?.connection ?: return null
    val directory = ChannelDirectory(live.generation)
    val collector = scope.collectMetadata(connection, directory)
    var synced = false
    try {
        synced = syncChannels(connection, live) is HtspResult.Ok
    } finally {
        if (!synced) collector.cancel()
    }
    return if (synced) MetadataSession(directory, collector) else null
}
```

`connection.events` does not end with a generation, so a collector left
running would go on receiving, and ignoring, every later message. `resync`
therefore stops the previous session's collector first, and cancels the new one
if the sync fails or the caller is cancelled. Call `stop()` on the last session
when you are done with the connection.

`resync` returns the session as soon as the helper does, so show its directory
only once `directory.ready` is true. Do not wait for `initialSyncCompleted`
after an overflow, though. It is the last message of the sync, so it may be one of the
dropped ones. The sync helper does not wait: it returns `TransportUnavailable`
as soon as its own generation overflows.

## Large EPG syncs

A full EPG for roughly 150 channels over 7 to 14 days is about 26 000 to 84 000
events, 15 to 100 MB on the wire, and it arrives in one burst. XMLTV imports on
the server also produce bursts of `eventUpdate`. A slow collector can overflow
on that. There are four ways to keep it manageable.

**Ask for less.** `epgMaxTimeEpochSeconds` bounds the window of events the
server pushes. The window is rolling: this sync sends one day of events, and
afterwards the server adds events as they come within a day of now:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/MetadataGuide.kt#bounded-epg -->
```kotlin
suspend fun syncWithOneDayOfEpg(
    connection: HtspConnection,
    live: HtspLiveConnection,
    nowEpochSeconds: Long,
): HtspResult<Unit> =
    connection.enableAsyncMetadataAwaitingInitialSync(
        epg = true,
        epgMaxTimeEpochSeconds = nowEpochSeconds + 24 * 60 * 60,
        expectedGeneration = live.generation,
    )
```

**Fetch EPG on demand.** Skip the push with `epg = false` and request events when
you need them, a channel at a time. `getEvents` takes a channel and a number of
following events, or a maximum start time:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/MetadataGuide.kt#paged-epg -->
```kotlin
suspend fun upcomingEventCount(
    connection: HtspConnection,
    live: HtspLiveConnection,
    channelId: Long,
): Int? =
    connection.getEvents(
        channelId = channelId,
        numFollowing = 20,
        expectedGeneration = live.generation,
    ).let { result -> (result as? HtspResult.Ok)?.value?.events?.size }
```

**Keep the collector fast.** Copy each message into your own bounded queue and
batch the work elsewhere.

**Raise the queue budgets**, within the limits above, if you have the memory.

## Memory limits

One TCP stream still orders everything, so a large frame holds up whatever comes
behind it, however the client queues are sized. The server's own
`queueDepthBytes` subscription option is separate from the client queues.

The worst case for client memory is roughly the sum of all queue budgets, plus
one maximum frame (up to 32 MiB) per subscription, plus the largest metadata
queue. That figure leaves out heap and decoded-object overhead, so it is not a
heap cap. There is no connection-wide limit and no time limit on how long events
may sit in a queue.

## What the library does not do

- It keeps no cache of channels, events or recordings and offers no reducer.
- It never reconnects, and it never re-requests data after a loss.
- It does not recover from overflow or malformed metadata for you.
- It does not cap memory across a whole connection.

These are the decisions in
[ADR 0002](../adr/0002-entity-model-and-update-semantics.md). The exact rules
behind everything above are in
[metadata and subscription event streams](../public-api.md#metadata-and-subscription-event-streams)
and
[metadata snapshots and sparse updates](../public-api.md#metadata-snapshots-and-sparse-updates).

## Where to go next

- [Connecting, requests, and the connection lifecycle](connecting.md) for
  reconnecting after a lost connection.
- [Recordings, recording rules, and recording files](dvr.md) for working with
  the recording and rule messages.
- [Errors and troubleshooting](troubleshooting.md) for what overflow and
  malformed-metadata reports mean in practice.
