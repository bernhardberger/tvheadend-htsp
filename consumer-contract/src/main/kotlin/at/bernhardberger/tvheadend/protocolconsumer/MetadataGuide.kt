package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelNowNextMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspMetadataMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTagAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTagDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTagUpdateMessage
import at.bernhardberger.tvheadend.htsp.model.HtspChannel
import at.bernhardberger.tvheadend.htsp.model.HtspTag
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadataAwaitingInitialSync
import at.bernhardberger.tvheadend.htsp.requests.getEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// region directory
class ChannelDirectory(private val generation: HtspConnectionGeneration) {
    private val channels = mutableMapOf<Long, HtspChannel>()
    private val tags = mutableMapOf<Long, HtspTag>()

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
            else -> Unit
        }
    }

    fun channel(channelId: Long): HtspChannel? = channels[channelId]
}
// endregion

// region collect-then-sync
fun CoroutineScope.collectMetadata(connection: HtspConnection, directory: ChannelDirectory): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        connection.events.collect { event -> directory.accept(event) }
    }

suspend fun syncChannels(connection: HtspConnection, live: HtspLiveConnection): HtspResult<Unit> =
    connection.enableAsyncMetadataAwaitingInitialSync(
        epg = false,
        expectedGeneration = live.generation,
    )
// endregion

// region bounded-epg
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
// endregion

// region paged-epg
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
// endregion

// region resync
suspend fun resync(
    scope: CoroutineScope,
    connection: HtspConnection,
    endpoint: HtspEndpoint,
): ChannelDirectory? {
    val outcome = connection.connect(endpoint, HtspConnectOptions(forceReconnect = true))
    val live = (outcome as? HtspConnectOutcome.Connected)?.connection ?: return null
    val directory = ChannelDirectory(live.generation)
    val collector = scope.collectMetadata(connection, directory)
    if (syncChannels(connection, live) !is HtspResult.Ok) {
        collector.cancel()
        return null
    }
    return directory
}
// endregion
