package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryStatsMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspMetadataMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.model.HtspAutorecEntry
import at.bernhardberger.tvheadend.htsp.model.HtspDvrEntry
import at.bernhardberger.tvheadend.htsp.model.HtspTimerecEntry
import at.bernhardberger.tvheadend.htsp.requests.AddAutorecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.AddDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.DeleteDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrMutationResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspRecordingRuleChannel
import at.bernhardberger.tvheadend.htsp.requests.UpdateAutorecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.UpdateTimerecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.addAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.addDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.cancelDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.deleteDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.fileClose
import at.bernhardberger.tvheadend.htsp.requests.fileOpen
import at.bernhardberger.tvheadend.htsp.requests.fileRead
import at.bernhardberger.tvheadend.htsp.requests.getDvrConfigs
import at.bernhardberger.tvheadend.htsp.requests.stopDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.updateAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.updateDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.updateTimerecEntry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream

// region dvr-book
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
// endregion

// region schedule
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
// endregion

// region change
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
// endregion

// region rename-keep-config
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
// endregion

// region await-state
suspend fun awaitRecordingState(book: DvrBook, entryId: Long, state: String, timeoutMs: Long): HtspDvrEntry? =
    withTimeoutOrNull(timeoutMs) {
        book.recordings.first { entries -> entries[entryId]?.state == state }[entryId]
    }
// endregion

// region file-missing
/** True for a finished recording whose file the server no longer has. */
val HtspDvrEntry.fileMissing: Boolean
    get() = state == "completed" && error == "File missing"
// endregion

// region add-rule
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
// endregion

// region rule-channel
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
// endregion

// region download
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
// endregion
