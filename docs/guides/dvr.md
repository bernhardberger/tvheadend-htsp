# Recordings, recording rules, and recording files

TVHeadend keeps three kinds of DVR objects: recordings (scheduled, running or
finished), autorec rules that schedule recordings from matching programmes, and
timerec rules that record a channel at fixed times of day. The server pushes all
three as metadata, and you change them with a handful of requests. This guide
covers listing them, scheduling and changing recordings, managing rules, why
`Ok` is not the end of a change, and reading a recording's file over the same
connection.

It assumes a live connection and a metadata sync; see the
[connecting guide](connecting.md) and the [metadata guide](metadata.md). The
snippets omit imports: requests are in `at.bernhardberger.tvheadend.htsp.requests`,
messages in `at.bernhardberger.tvheadend.htsp.messages`, entities in
`at.bernhardberger.tvheadend.htsp.model`. The complete file is
[`DvrGuide.kt`](../../consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt),
compiled against the staged artifact in CI.

## Check recording access

`HtspLiveConnection.dvrAccess` reports whether the account may use the DVR. It
is null when the server did not say, and false means recorder requests will be
refused. The library does not check it for you: a recorder request on an
account without access is still sent, and the server answers `AccessDenied`.
Read it once after connecting to decide whether to offer recording at all.

## List recordings and rules

HTSP has no request that lists recordings. They arrive as metadata, on the same
`events` flow as channels and tags, as part of the initial sync and then as
changes. Three message families matter here:

| Messages | Entity | Identity |
|---|---|---|
| `HtspDvrEntryAddMessage`, `HtspDvrEntryUpdateMessage`, `HtspDvrEntryStatsMessage`, `HtspDvrEntryDeleteMessage` | `HtspDvrEntry` | `entryId`, a number |
| `HtspAutorecEntryAddMessage`, `...UpdateMessage`, `...DeleteMessage` | `HtspAutorecEntry` | `id`, a UUID string |
| `HtspTimerecEntryAddMessage`, `...UpdateMessage`, `...DeleteMessage` | `HtspTimerecEntry` | `id`, a UUID string |

The update rules are the ones from the
[metadata guide](metadata.md#apply-updates): a full update replaces the stored
entity, and `HtspDvrEntryStatsMessage` changes only the recording state, the
error text, the error counts and the data size. Expect those statistics
messages while a recording runs. The server sends only the recordings and rules
the account is allowed to see.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#dvr-book -->
```kotlin
class DvrBook(private val generation: HtspConnectionGeneration) {
    private val recordingsState = MutableStateFlow<Map<Long, HtspDvrEntry>>(emptyMap())
    private val autorecsState = MutableStateFlow<Map<String, HtspAutorecEntry>>(emptyMap())
    private val timerecsState = MutableStateFlow<Map<String, HtspTimerecEntry>>(emptyMap())

    val recordings: StateFlow<Map<Long, HtspDvrEntry>> get() = recordingsState
    val autorecs: StateFlow<Map<String, HtspAutorecEntry>> get() = autorecsState
    val timerecs: StateFlow<Map<String, HtspTimerecEntry>> get() = timerecsState

    fun accept(event: HtspTransportEvent) {
        if (event.generation !== generation || event !is HtspTransportEvent.ServerMessage) return
        apply(event.message)
    }

    private fun apply(message: HtspMetadataMessage) {
        when (message) {
            is HtspDvrEntryAddMessage -> putRecording(message.entry)
            is HtspDvrEntryUpdateMessage -> putRecording(message.entry)
            is HtspDvrEntryStatsMessage -> recordingsState.update { entries ->
                val entry = entries[message.entryId] ?: return@update entries
                val updated = entry.copy(
                    state = message.state,
                    duplicate = message.duplicate,
                    error = message.error,
                    subscriptionError = message.subscriptionError,
                    streamErrors = message.streamErrors,
                    dataErrors = message.dataErrors,
                    dataSizeBytes = message.dataSizeBytes,
                )
                entries + (message.entryId to updated)
            }
            is HtspDvrEntryDeleteMessage -> recordingsState.update { it - message.entryId }
            is HtspAutorecEntryAddMessage -> putAutorec(message.entry)
            is HtspAutorecEntryUpdateMessage -> putAutorec(message.entry)
            is HtspAutorecEntryDeleteMessage -> autorecsState.update { it - message.id }
            is HtspTimerecEntryAddMessage -> putTimerec(message.entry)
            is HtspTimerecEntryUpdateMessage -> putTimerec(message.entry)
            is HtspTimerecEntryDeleteMessage -> timerecsState.update { it - message.id }
            else -> Unit
        }
    }

    private fun putRecording(entry: HtspDvrEntry) = recordingsState.update { it + (entry.entryId to entry) }

    private fun putAutorec(entry: HtspAutorecEntry) = autorecsState.update { it + (entry.id to entry) }

    private fun putTimerec(entry: HtspTimerecEntry) = timerecsState.update { it + (entry.id to entry) }
}
```

Feed it from a collector that is started before the sync, exactly as the
metadata guide does for `ChannelDirectory`. The three `StateFlow`s hold
immutable maps, so a UI can observe them directly and a coroutine can wait on
them, as the next sections do. The class leaves out the flag that
`ChannelDirectory` keeps for overflow and malformed metadata; add it the same
way if you rely on the book being complete.

A few `HtspDvrEntry` properties are worth knowing:

- `state` is a string. The pinned server sends `scheduled`, `recording`,
  `completed`, `missed` and `invalid`. Keep an `else` branch, because the
  library does not turn it into an enum.
- `startEpochSeconds` and `stopEpochSeconds` are the scheduled times, and
  `startExtraMinutes` and `stopExtraMinutes` the padding on either side.
- `error` and `subscriptionError` carry the server's error text, if any. It is
  display text, not a code, with one exception this guide relies on:
  [`File missing`](#deleting-a-recording-whose-file-is-gone). `streamErrors`
  and `dataErrors` are present only when they are nonzero.
- `files` lists the recording files, each an `HtspDvrRecordingFile` with a
  relative `path`, start and stop times, `sizeBytes` and stream details in
  `info`. It is null when the server sent no file list. `dataSizeBytes` is the
  known size of the recording.
- `autorecEntryUuid` and `timerecEntryUuid` point at the rule that scheduled the
  recording, when there is one. They match the `id` of the rule entities.
- `playCount` and `playPositionSeconds` exist only for recordings that are
  running or finished.

## Schedule a recording

`addDvrEntry` has two forms. Pass an `eventId` to record a programme from the
EPG, or a channel with start and stop times in epoch seconds to record a time
range:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#schedule -->
```kotlin
suspend fun recordEvent(connection: HtspConnection, live: HtspLiveConnection, eventId: Long): Long? {
    if (live.dvrAccess == false) return null
    return when (val result = connection.addDvrEntry(eventId = eventId, expectedGeneration = live.generation)) {
        is HtspResult.Ok -> result.value.entryId
        is HtspFailure -> null
    }
}

suspend fun recordTimeRange(
    connection: HtspConnection,
    live: HtspLiveConnection,
    channelId: Long,
    startEpochSeconds: Long,
    stopEpochSeconds: Long,
    title: String,
): HtspResult<AddDvrEntryResponse> =
    connection.addDvrEntry(
        channelId = channelId,
        startEpochSeconds = startEpochSeconds,
        stopEpochSeconds = stopEpochSeconds,
        title = title,
        startExtraMinutes = 2L,
        stopExtraMinutes = 10L,
        expectedGeneration = live.generation,
    )
```

- For an event, the server takes title, subtitle, summary, description and age
  rating from the event and ignores any you pass. For a time range,
  `title` is required and must not be empty; an empty one throws
  `IllegalArgumentException` before anything is sent.
- The padding, `priority`, `retentionDays`, `removalDays`, `enabled`,
  `comment` and `configName` are optional creation settings. `getDvrConfigs()`
  lists the DVR configurations whose names `configName` takes.
- `addDvrEntry` checks none of these settings against the negotiated protocol
  version. A server too old for one ignores it and may still answer `Ok`.
- Unsigned fields such as the event id must fit 32 bits. A value outside that
  range throws `IllegalArgumentException`.

The reply carries `entryId`, the id of the new recording. The entry itself
reaches you as a `HtspDvrEntryAddMessage`, so treat the book, not the reply, as
the record of what exists.

## Change, stop, cancel, or delete

Each of these takes the `entryId` and returns an acknowledgement:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#change -->
```kotlin
suspend fun addStopPadding(
    connection: HtspConnection,
    live: HtspLiveConnection,
    entry: HtspDvrEntry,
    extraMinutes: Long,
): HtspResult<HtspDvrMutationResponse> =
    connection.updateDvrEntry(
        entryId = entry.entryId,
        stopExtraMinutes = entry.stopExtraMinutes + extraMinutes,
        expectedGeneration = live.generation,
    )

suspend fun stopOrCancel(
    connection: HtspConnection,
    live: HtspLiveConnection,
    entry: HtspDvrEntry,
): HtspResult<HtspDvrMutationResponse> =
    if (entry.state == "recording") {
        connection.stopDvrEntry(entry.entryId, expectedGeneration = live.generation)
    } else {
        connection.cancelDvrEntry(entry.entryId, expectedGeneration = live.generation)
    }

suspend fun deleteRecording(
    connection: HtspConnection,
    live: HtspLiveConnection,
    entryId: Long,
): HtspResult<DeleteDvrEntryResponse> =
    connection.deleteDvrEntry(entryId, expectedGeneration = live.generation)
```

`updateDvrEntry` sends only the fields you pass; a null argument is left out,
not cleared. `configName` and `ageRating` are the exceptions. On the pinned
server, an update to an upcoming recording always resets both:

- A missing `configName` selects the default DVR configuration
  (`src/htsp_server.c:2197`, `src/dvr/dvr_db.c:2517-2518`). To keep the
  configuration, pass its name from `getDvrConfigs()`, not the `dvrConfigUuid`
  on the entry: the server looks the value up by name only, and an unknown
  value also selects the default.
- A missing `ageRating` is stored as 0, no rating
  (`src/htsp_server.c:2205`, `src/dvr/dvr_db.c:2565-2568`). Pass the entry's
  `ageRating` to keep it.

This renames an upcoming recording and keeps both:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#rename-keep-config -->
```kotlin
sealed interface RenameOutcome {
    /** The server acknowledged the update; watch the book for the result. */
    data object Acknowledged : RenameOutcome

    /** The entry has no configuration, or `getDvrConfigs` does not list it. Nothing was sent. */
    data object ConfigNotFound : RenameOutcome

    /** Several configurations share the entry's configuration name. Nothing was sent. */
    data object ConfigNameNotUnique : RenameOutcome

    /** `getDvrConfigs` or the update failed. */
    data class Failed(val failure: HtspFailure) : RenameOutcome
}

suspend fun renameKeepingConfig(
    connection: HtspConnection,
    live: HtspLiveConnection,
    entry: HtspDvrEntry,
    title: String,
): RenameOutcome {
    val configUuid = entry.dvrConfigUuid ?: return RenameOutcome.ConfigNotFound
    val configs = when (val result = connection.getDvrConfigs(expectedGeneration = live.generation)) {
        is HtspResult.Ok -> result.value.configurations.orEmpty()
        is HtspFailure -> return RenameOutcome.Failed(result)
    }
    val config = configs.firstOrNull { it.dvrConfigUuid == configUuid } ?: return RenameOutcome.ConfigNotFound
    if (configs.count { it.name == config.name } > 1) return RenameOutcome.ConfigNameNotUnique
    return when (
        val result = connection.updateDvrEntry(
            entryId = entry.entryId,
            configName = config.name,
            title = title,
            ageRating = entry.ageRating,
            expectedGeneration = live.generation,
        )
    ) {
        is HtspResult.Ok -> RenameOutcome.Acknowledged
        is HtspFailure -> RenameOutcome.Failed(result)
    }
}
```

The entry carries only the configuration's UUID, so the function finds the
configuration with that UUID in the `getDvrConfigs()` list and sends its name.
It sends nothing when the entry names no configuration, when the list does not
contain it, or when two configurations share the name, because the name would
then not identify the one to keep. Each of those comes back as a value the
caller can act on, for example by asking the person which configuration to use.
`Acknowledged` means only that the server accepted the request; see
[below](#ok-acknowledges-the-request).

For an account restricted to a list of DVR configurations, the server passes an
identifier where it looks up a name, so the entry ends up on the default
configuration either way. The exception is an account whose list holds no valid
configuration: the server then leaves the stored one alone
(`src/htsp_server.c:576-579`). The library sends the name you give it and the lookup
happens on the server, so it cannot prevent that.

The other three differ in what they do to the recording, and the
server decides by the recording's state. On the pinned server (HTSP 44):

| Call | Upcoming | Running | Finished |
|---|---|---|---|
| `stopDvrEntry` | nothing | ends the recording, which becomes `completed`, or `missed` if no file was written | nothing |
| `cancelDvrEntry` | removes the entry | aborts the recording | nothing |
| `deleteDvrEntry` | removes the entry | stops it and deletes the file | deletes the file |

"Nothing" still returns `Ok`: the server acknowledges the request whether or not
it had an effect. That is the same reason `Ok` can never be your proof of a
state change.

## `Ok` acknowledges the request

An `Ok` from any of these calls, including `addDvrEntry`, means the server
accepted the request. It does not mean the recording has changed state yet, and
you should not assume the metadata update follows the reply. To know what
happened, watch the metadata. `awaitRecordingState` waits on the book
until an entry reaches a state, and gives up after a timeout:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#await-state -->
```kotlin
suspend fun awaitRecordingState(book: DvrBook, entryId: Long, state: String, timeoutMs: Long): HtspDvrEntry? =
    withTimeoutOrNull(timeoutMs) {
        book.recordings.first { entries -> entries[entryId]?.state == state }[entryId]
    }
```

Wait for the state you expect, for example `completed` after stopping a
recording that was writing a file. The timeout matters: a server that accepted
a request and then did nothing leaves the state unchanged, and waiting without
a limit would never end. An enclosing
`withTimeoutOrNull` ends only the wait. The request itself has its own
`timeoutMs`; see [connecting](connecting.md#run-requests).

### Refusals

A refused DVR change comes back as `HtspResult.ServerError`, not as an `Ok`
reply with an error inside. That covers a missing recording, a rule the account
may not touch, and an add the server could not carry out. `serverMessage` holds
the server's text when the reply had one and is null for a refusal without text.
It is translated and untrusted, so show it and do not parse it. A reply that
cannot be decoded is `MalformedReply`, and the server may have acted anyway,
which is why retrying `addDvrEntry` blindly can create a second recording. The
[connecting guide](connecting.md#run-requests) lists the other failures.

### Deleting a recording whose file is gone

On the pinned server, a delete removes the file first and then removes the
entry only if its retention has run out. Otherwise the entry stays in the
list, now without a file, and the server sends a `HtspDvrEntryUpdateMessage`
whose `error` is `File missing` (pinned `src/htsp_server.c:1175`), not a
`HtspDvrEntryDeleteMessage`. The
same text appears on any finished recording whose file disappeared. So an
acknowledged `deleteDvrEntry` can end in either message, and a book that waits
only for the delete message can wait forever.

Some deletes end in neither. A `missed` recording, or a finished one whose file
is already gone, has nothing to remove, so while its retention runs the server
changes nothing and sends no message (`src/dvr/dvr_db.c:5481-5493`,
`:5319-5347`). A running recording that has not written a file yet first
becomes `missed` (`src/dvr/dvr_db.c:3106-3110`), and that update is the last
you see. Bound any wait for the outcome, for example with `awaitRecordingState`
and a timeout:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#file-missing -->
```kotlin
/** True for a finished recording whose file the server no longer has. */
val HtspDvrEntry.fileMissing: Boolean
    get() = state == "completed" && error == "File missing"
```

This is the one place the guide compares `error`, and it works because the
pinned server writes that exact string. It writes it as a plain literal, not
through the translation call it uses for the error text of its replies, so it
does not change with the connection's language. It is still not a documented
code: a later server could reword or translate it, and then `fileMissing`
stops matching. If you show these entries as deleted, treat a vanished file as
the same outcome and let the server's retention remove the entry later.

## Autorec and timerec rules

An autorec rule matches programmes, a timerec rule matches a time of day. Both
are created with `addAutorecEntry` and `addTimerecEntry`, which return the new
rule's UUID in `id`, changed with `updateAutorecEntry` and `updateTimerecEntry`,
and removed with `deleteAutorecEntry` and `deleteTimerecEntry`. Times of day are
minutes since midnight, `0..1439`, as `Int`. The same acknowledgement rule
applies: watch the rule metadata for the result. Fields that need a newer
protocol, such as `broadcastType` (HTSP 39) and `comment` (HTSP 42), return
`NotSupported` without being sent.

### Restrict a rule to a channel, or to any channel

`HtspRecordingRuleChannel` selects the channel. `Id(channelId)` restricts the
rule to one channel, and `Any` means every channel:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#add-rule -->
```kotlin
suspend fun addSeriesRule(
    connection: HtspConnection,
    live: HtspLiveConnection,
    title: String,
    channelId: Long?,
): HtspResult<AddAutorecEntryResponse> =
    connection.addAutorecEntry(
        title = title,
        channel = channelId?.let { HtspRecordingRuleChannel.Id(it) } ?: HtspRecordingRuleChannel.Any,
        startMinutesSinceMidnight = 20 * 60,
        startWindowEndMinutesSinceMidnight = 22 * 60,
        expectedGeneration = live.generation,
    )
```

On an add, `Any` and an omitted channel (null) both create a rule without a
channel restriction. `title` is the rule's title match, and the server defines
its syntax.

An update always sets the channel restriction, so the `channel` parameter is
required. There is no wire shape that keeps the stored channel: an update without
`channelId` clears it, whatever the server's source comments suggest. To leave a
rule's channel as it is, pass the channel it already has. To make it apply to any
channel, pass `Any`, which the library sends as no `channelId`:

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#rule-channel -->
```kotlin
fun HtspAutorecEntry.channelRestriction(): HtspRecordingRuleChannel =
    channelId?.let { HtspRecordingRuleChannel.Id(it) } ?: HtspRecordingRuleChannel.Any

fun HtspTimerecEntry.channelRestriction(): HtspRecordingRuleChannel =
    channelId?.let { HtspRecordingRuleChannel.Id(it) } ?: HtspRecordingRuleChannel.Any

suspend fun disableAutorec(
    connection: HtspConnection,
    live: HtspLiveConnection,
    rule: HtspAutorecEntry,
): HtspResult<UpdateAutorecEntryResponse> =
    connection.updateAutorecEntry(
        id = rule.id,
        channel = rule.channelRestriction(),
        enabled = false,
        expectedGeneration = live.generation,
    )

suspend fun clearTimerecChannel(
    connection: HtspConnection,
    live: HtspLiveConnection,
    rule: HtspTimerecEntry,
): HtspResult<UpdateTimerecEntryResponse> =
    connection.updateTimerecEntry(
        id = rule.id,
        channel = HtspRecordingRuleChannel.Any,
        expectedGeneration = live.generation,
    )
```

This is why a client that edits rules needs the rule metadata first: without the
stored rule you cannot know which channel to pass, and a guess changes the rule.
The stored value you read can also be stale by the time the update arrives, and
the update overwrites whatever is there. Other fields behave like
`updateDvrEntry`: only the ones you pass change.

## Read a recording's file

The file requests read a recording over the HTSP connection, with no separate
HTTP download. They are recorder requests, so they need DVR access, and they
work on a handle that belongs to the connection that opened it.

| Request | Does |
|---|---|
| `fileOpen(file)` | opens a file and returns a handle `id`, with `sizeBytes` and `modifiedAtEpochSeconds` when the server has them |
| `fileRead(id, sizeBytes, offsetBytes)` | reads up to `sizeBytes` bytes, from `offsetBytes` or from the current position when it is null |
| `fileSeek(id, offsetBytes, whence)` | moves the position and returns the absolute offset; `whence` is `SET`, `CURRENT` or `END` |
| `fileStat(id)` | returns the size and modification time, when the server has them |
| `fileClose(id, playPositionSeconds, playCount)` | releases the handle |

The library passes the `file` string through unchanged. The server accepts
`dvr/<entryId>` or `dvrfile/<entryId>` for a recording, with an optional leading
slash. A recording that has no file yet is refused with `ServerError`, and
opening a recording counts toward the account's streaming connection limit, so
it can return `ConnectionLimit`.

<!-- source: consumer-contract/src/main/kotlin/at/bernhardberger/tvheadend/protocolconsumer/DvrGuide.kt#download -->
```kotlin
suspend fun downloadRecording(
    connection: HtspConnection,
    live: HtspLiveConnection,
    entryId: Long,
    sink: OutputStream,
): HtspFailure? {
    val opened = connection.fileOpen("dvr/$entryId", expectedGeneration = live.generation)
    val file = when (opened) {
        is HtspResult.Ok -> opened.value
        is HtspFailure -> return opened
    }
    try {
        while (true) {
            val chunk = connection.fileRead(file.id, sizeBytes = 1L shl 20, expectedGeneration = live.generation)
            when (chunk) {
                is HtspResult.Ok -> {
                    if (chunk.value.data.size == 0) return null
                    sink.write(chunk.value.data.toByteArray())
                }
                is HtspFailure -> return chunk
            }
        }
    } finally {
        withContext(NonCancellable) {
            connection.fileClose(file.id, expectedGeneration = live.generation)
        }
    }
}
```

The loop reads one megabyte at a time and ends on an empty reply. A read may
return fewer bytes than you asked for, and an empty payload is a valid reply, so
do not use a short read as the end test. `sizeBytes` must be between 0 and
16 MiB; a larger value throws `IllegalArgumentException`. A bigger read means
fewer round trips and a larger reply to hold in memory, so choose a size that
suits your link.

Close in a `finally` block, wrapped in `NonCancellable`, so a download cancelled
after the open still releases its handle. Two cases are not covered. If the
caller is cancelled while `fileOpen` waits for its reply, the server may already
have opened a handle whose id you never see. And this close is best effort: its
result is not checked. Either way the handle stays open until the connection
ends, when the server closes all of them (`src/htsp_server.c:3669-3670`). If
that matters, download over a connection of its own and close it when you are
done. Passing the generation to each call stops a request
for an old handle from reaching a new connection, where the same number would
mean nothing. Closing a handle opened for a recording counts as a play on the
server when `playCount` is omitted, so this sample marks the recording as
played once per download, and `playPositionSeconds` stores a resume
position.

Two requests are related but separate. `getDvrCutpoints(entryId)` returns a
recording's cutpoints as millisecond ranges, each with a type code.
`getTicket(GetTicketSelector.Dvr(entryId))` returns a temporary path and ticket
for fetching the recording some other way. The ticket is a
credential: its `toString` is redacted, and you should keep it out of logs.

## Where to go next

- [Metadata sync and event streams](metadata.md) for the sync and update rules
  that `DvrBook` follows.
- [Connecting, requests, and the connection lifecycle](connecting.md) for
  failure handling and the generation fence.
- [Errors and troubleshooting](troubleshooting.md) for what each failure means.
- [API behavior](../public-api.md#public-call-outcomes) for the exact rules
  behind DVR acknowledgements and refusals.
