package at.bernhardberger.tvheadend.htsp

import java.util.Collections

private const val SERVER_MESSAGE_U32_MAX: Long = 0xffff_ffffL
private val CHANNEL_NUMBER_KEYS = listOf("channelNumber", "number", "lcn", "channelNum", "channelno")
private val CHANNEL_TAG_KEYS = listOf("tagIds", "tags", "channelTags")
private val TAG_ID_KEYS = listOf("tagId", "id")
private val TAG_NAME_KEYS = listOf("tagName", "name")
private val TAG_INDEX_KEYS = listOf("tagIndex", "index")
private val DVR_ID_KEYS = listOf("id", "dvrId")
private val DVR_CHANNEL_KEYS = listOf("channelId", "channel")
private val DVR_ERROR_KEYS = listOf("error", "statusError")
private val DVR_PLAY_POSITION_KEYS = listOf("playposition", "playPosition")
private val DVR_PLAY_COUNT_KEYS = listOf("playcount", "playCount")
private val DVR_FILE_PATH_KEYS = listOf("filename", "path")
private val EVENT_ID_KEYS = listOf("eventId", "id")
private val EVENT_CHANNEL_KEYS = listOf("channelId", "channel")
private val EVENT_START_KEYS = listOf("start", "startTime")
private val EVENT_STOP_KEYS = listOf("stop", "stopTime")
private val EVENT_TITLE_KEYS = listOf("title", "eventTitle", "name")
private val EVENT_CONTENT_KEYS = listOf("contentType", "content")
private val SUBSCRIPTION_ID_KEYS = listOf("subscriptionId", "id")
private val STATUS_KEYS = listOf("state", "status")
private val SUBSCRIPTION_ERROR_KEYS = listOf("subscriptionError", "error")

/** A typed asynchronous HTSP server message. It never represents an RPC reply. */
public sealed interface HtspServerMessage

/** Defensively copied binary protocol data with content value semantics. */
public class HtspBinary(bytes: ByteArray) {
    private val content: ByteArray = bytes.copyOf()

    /** Returns a new copy on every access. */
    public fun toByteArray(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is HtspBinary && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "HtspBinary(size=${content.size})"
}

public class HtspChannelAddMessage(
    public val channelId: Long,
    public val channelName: String? = null,
    public val channelUuid: String? = null,
    public val channelNumber: Long? = null,
    public val channelNumberMinor: Long? = null,
    public val channelIcon: String? = null,
    public val currentEventId: Long? = null,
    public val nextEventId: Long? = null,
    services: List<HtspChannelService>? = null,
    tagIds: List<Long>? = null,
) : HtspServerMessage {
    public val services: List<HtspChannelService>? = services?.immutableServerSnapshot()
    public val tagIds: List<Long>? = tagIds?.immutableServerSnapshot()

    init {
        requireServerU32("channelId", channelId)
        channelNumber?.let { requireServerU32("channelNumber", it) }
        channelNumberMinor?.let { requireServerU32("channelNumberMinor", it) }
        currentEventId?.let { requireServerU32("currentEventId", it) }
        nextEventId?.let { requireServerU32("nextEventId", it) }
        this.tagIds?.forEach { requireServerU32("tagIds", it) }
        this.services?.forEach { service ->
            requireServerU32("service.content", service.content)
            service.conditionalAccessId?.let { requireServerU32("service.conditionalAccessId", it) }
        }
    }
}

public class HtspChannelUpdateMessage(
    public val channelId: Long,
    public val channelUuid: String? = null,
    public val channelNumber: Long? = null,
    public val channelNumberMinor: Long? = null,
    public val channelName: String? = null,
    public val channelIcon: String? = null,
    public val currentEventId: Long? = null,
    public val nextEventId: Long? = null,
    services: List<HtspChannelService>? = null,
    tagIds: List<Long>? = null,
) : HtspServerMessage {
    public val services: List<HtspChannelService>? = services?.immutableServerSnapshot()
    public val tagIds: List<Long>? = tagIds?.immutableServerSnapshot()

    init {
        requireServerU32("channelId", channelId)
        channelNumber?.let { requireServerU32("channelNumber", it) }
        channelNumberMinor?.let { requireServerU32("channelNumberMinor", it) }
        currentEventId?.let { requireServerU32("currentEventId", it) }
        nextEventId?.let { requireServerU32("nextEventId", it) }
        this.tagIds?.forEach { requireServerU32("tagIds", it) }
        this.services?.forEach { service ->
                requireServerU32("service.content", service.content)
                service.conditionalAccessId?.let {
                    requireServerU32("service.conditionalAccessId", it)
                }
        }
    }
}

public data class HtspChannelDeleteMessage(public val channelId: Long) : HtspServerMessage {
    init {
        requireServerU32("channelId", channelId)
    }
}

public class HtspTagAddMessage(
    public val tagId: Long,
    public val tagName: String? = null,
    public val tagUuid: String? = null,
    public val tagIndex: Long? = null,
    public val tagIcon: String? = null,
    public val tagTitledIcon: Long? = null,
    channelIds: List<Long>? = null,
) : HtspServerMessage {
    public val channelIds: List<Long>? = channelIds?.immutableServerSnapshot()

    init {
        requireServerU32("tagId", tagId)
        tagIndex?.let { requireServerU32("tagIndex", it) }
        tagTitledIcon?.let { requireServerU32("tagTitledIcon", it) }
        this.channelIds?.forEach { requireServerU32("channelIds", it) }
    }
}

public class HtspTagUpdateMessage(
    public val tagId: Long,
    public val tagUuid: String? = null,
    public val tagIndex: Long? = null,
    public val tagName: String? = null,
    public val tagIcon: String? = null,
    public val tagTitledIcon: Long? = null,
    channelIds: List<Long>? = null,
) : HtspServerMessage {
    public val channelIds: List<Long>? = channelIds?.immutableServerSnapshot()

    init {
        requireServerU32("tagId", tagId)
        tagIndex?.let { requireServerU32("tagIndex", it) }
        tagTitledIcon?.let { requireServerU32("tagTitledIcon", it) }
        this.channelIds?.forEach { requireServerU32("channelIds", it) }
    }
}

public data class HtspTagDeleteMessage(public val tagId: Long) : HtspServerMessage {
    init {
        requireServerU32("tagId", tagId)
    }
}

/** Known bounded fields from one otherwise upstream-dynamic DVR recording-file map. */
public data class HtspDvrRecordingFile(
    public val fileId: Long?,
    public val path: String?,
    public val start: Long?,
    public val stop: Long?,
    public val sizeBytes: Long?,
) {
    init {
        fileId?.let { requireServerU32("fileId", it) }
    }
}

public class HtspDvrEntryAddMessage(
    public val entryId: Long,
    public val entryUuid: String? = null,
    public val enabled: Long? = null,
    public val channelId: Long? = null,
    public val channelName: String? = null,
    public val eventId: Long? = null,
    public val autorecEntryUuid: String? = null,
    public val timerecEntryUuid: String? = null,
    public val start: Long? = null,
    public val stop: Long? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val contentType: Long? = null,
    public val ageRating: Long? = null,
    public val ratingLabel: String? = null,
    public val ratingIcon: String? = null,
    public val ratingAuthority: String? = null,
    public val ratingCountry: String? = null,
    public val playCount: Long? = null,
    public val playPositionSeconds: Long? = null,
    public val seasonNumber: Long? = null,
    public val episodeNumber: Long? = null,
    public val episodeCount: Long? = null,
    public val partNumber: Long? = null,
    public val partCount: Long? = null,
    public val title: String? = null,
    public val description: String? = null,
    public val summary: String? = null,
    public val subtitle: String? = null,
    public val owner: String? = null,
    public val creator: String? = null,
    public val comment: String? = null,
    public val image: String? = null,
    public val fanartImage: String? = null,
    public val copyrightYear: Long? = null,
    files: List<HtspDvrRecordingFile>? = null,
    public val path: String? = null,
    public val dvrConfigUuid: String? = null,
    public val duplicate: Long? = null,
    public val state: String? = null,
    public val error: String? = null,
    public val subscriptionError: String? = null,
    public val streamErrors: Long? = null,
    public val dataErrors: Long? = null,
    public val dataSizeBytes: Long? = null,
) : HtspServerMessage {
    public val files: List<HtspDvrRecordingFile>? = files?.immutableServerSnapshot()

    init {
        requireServerU32("entryId", entryId)
        validateDvrU32Fields()
    }

    private fun validateDvrU32Fields() {
        listOf(
            "enabled" to enabled,
            "channelId" to channelId,
            "eventId" to eventId,
            "retentionDays" to retentionDays,
            "removalDays" to removalDays,
            "priority" to priority,
            "contentType" to contentType,
            "ageRating" to ageRating,
            "playCount" to playCount,
            "playPositionSeconds" to playPositionSeconds,
            "seasonNumber" to seasonNumber,
            "episodeNumber" to episodeNumber,
            "episodeCount" to episodeCount,
            "partNumber" to partNumber,
            "partCount" to partCount,
            "copyrightYear" to copyrightYear,
            "duplicate" to duplicate,
            "streamErrors" to streamErrors,
            "dataErrors" to dataErrors,
        ).forEach { (name, value) -> value?.let { requireServerU32(name, it) } }
    }
}

public class HtspDvrEntryUpdateMessage(
    public val entryId: Long,
    public val entryUuid: String? = null,
    public val enabled: Long? = null,
    public val channelId: Long? = null,
    public val channelName: String? = null,
    public val eventId: Long? = null,
    public val autorecEntryUuid: String? = null,
    public val timerecEntryUuid: String? = null,
    public val start: Long? = null,
    public val stop: Long? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val contentType: Long? = null,
    public val ageRating: Long? = null,
    public val ratingLabel: String? = null,
    public val ratingIcon: String? = null,
    public val ratingAuthority: String? = null,
    public val ratingCountry: String? = null,
    public val playCount: Long? = null,
    public val playPositionSeconds: Long? = null,
    public val seasonNumber: Long? = null,
    public val episodeNumber: Long? = null,
    public val episodeCount: Long? = null,
    public val partNumber: Long? = null,
    public val partCount: Long? = null,
    public val title: String? = null,
    public val description: String? = null,
    public val summary: String? = null,
    public val subtitle: String? = null,
    public val owner: String? = null,
    public val creator: String? = null,
    public val comment: String? = null,
    public val image: String? = null,
    public val fanartImage: String? = null,
    public val copyrightYear: Long? = null,
    files: List<HtspDvrRecordingFile>? = null,
    public val path: String? = null,
    public val dvrConfigUuid: String? = null,
    public val duplicate: Long? = null,
    public val state: String? = null,
    public val error: String? = null,
    public val subscriptionError: String? = null,
    public val streamErrors: Long? = null,
    public val dataErrors: Long? = null,
    public val dataSizeBytes: Long? = null,
) : HtspServerMessage {
    public val files: List<HtspDvrRecordingFile>? = files?.immutableServerSnapshot()

    init {
        requireServerU32("entryId", entryId)
        listOf(
            "enabled" to enabled, "channelId" to channelId, "eventId" to eventId,
            "retentionDays" to retentionDays, "removalDays" to removalDays,
            "priority" to priority, "contentType" to contentType, "ageRating" to ageRating,
            "playCount" to playCount, "playPositionSeconds" to playPositionSeconds,
            "seasonNumber" to seasonNumber, "episodeNumber" to episodeNumber,
            "episodeCount" to episodeCount, "partNumber" to partNumber, "partCount" to partCount,
            "copyrightYear" to copyrightYear, "duplicate" to duplicate,
            "streamErrors" to streamErrors, "dataErrors" to dataErrors,
        ).forEach { (name, value) -> value?.let { requireServerU32(name, it) } }
    }
}

public data class HtspDvrEntryDeleteMessage(public val entryId: Long) : HtspServerMessage {
    init {
        requireServerU32("entryId", entryId)
    }
}

/** A complete automatic DVR rule announced during asynchronous metadata sync. */
public data class HtspAutorecEntryAddMessage(
    public val id: String,
    public val enabled: Boolean,
    public val maxDurationSeconds: Long,
    public val minDurationSeconds: Long,
    public val retentionDays: Long,
    public val removalDays: Long,
    public val daysOfWeekMask: Long,
    public val approximateStartMinutesSinceMidnight: Int,
    public val startMinutesSinceMidnight: Int,
    public val startWindowEndMinutesSinceMidnight: Int,
    public val priority: Long,
    public val startExtraMinutes: Long,
    public val stopExtraMinutes: Long,
    public val duplicateDetection: Long,
    public val maximumRecordingCount: Long,
    public val broadcastType: Long,
    public val comment: String,
    public val title: String? = null,
    public val fullText: Boolean? = null,
    public val mergeText: Boolean? = null,
    public val name: String,
    public val directory: String? = null,
    public val owner: String,
    public val creator: String,
    public val channelId: Long? = null,
    public val seriesLinkUri: String? = null,
    public val configId: String? = null,
) : HtspServerMessage {
    init {
        listOf(
            "maxDurationSeconds" to maxDurationSeconds,
            "minDurationSeconds" to minDurationSeconds,
            "retentionDays" to retentionDays,
            "removalDays" to removalDays,
            "daysOfWeekMask" to daysOfWeekMask,
            "priority" to priority,
            "duplicateDetection" to duplicateDetection,
            "maximumRecordingCount" to maximumRecordingCount,
            "broadcastType" to broadcastType,
        ).forEach { (field, value) -> requireServerU32(field, value) }
        channelId?.let { requireServerU32("channelId", it) }
    }
}

/** A partial automatic DVR rule update. Null properties were absent on the wire. */
public data class HtspAutorecEntryUpdateMessage(
    public val id: String,
    public val enabled: Boolean? = null,
    public val maxDurationSeconds: Long? = null,
    public val minDurationSeconds: Long? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val daysOfWeekMask: Long? = null,
    public val approximateStartMinutesSinceMidnight: Int? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val startWindowEndMinutesSinceMidnight: Int? = null,
    public val priority: Long? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val duplicateDetection: Long? = null,
    public val maximumRecordingCount: Long? = null,
    public val broadcastType: Long? = null,
    public val comment: String? = null,
    public val title: String? = null,
    public val fullText: Boolean? = null,
    public val mergeText: Boolean? = null,
    public val name: String? = null,
    public val directory: String? = null,
    public val owner: String? = null,
    public val creator: String? = null,
    public val channelId: Long? = null,
    public val seriesLinkUri: String? = null,
    public val configId: String? = null,
) : HtspServerMessage {
    init {
        listOf(
            "maxDurationSeconds" to maxDurationSeconds,
            "minDurationSeconds" to minDurationSeconds,
            "retentionDays" to retentionDays,
            "removalDays" to removalDays,
            "daysOfWeekMask" to daysOfWeekMask,
            "priority" to priority,
            "duplicateDetection" to duplicateDetection,
            "maximumRecordingCount" to maximumRecordingCount,
            "broadcastType" to broadcastType,
            "channelId" to channelId,
        ).forEach { (field, value) -> value?.let { requireServerU32(field, it) } }
    }
}

public data class HtspAutorecEntryDeleteMessage(public val id: String) : HtspServerMessage

/** A complete time-based DVR rule announced during asynchronous metadata sync. */
public data class HtspTimerecEntryAddMessage(
    public val id: String,
    public val enabled: Boolean,
    public val name: String,
    public val title: String,
    public val channelId: Int,
    public val startMinutesSinceMidnight: Int,
    public val stopMinutesSinceMidnight: Int,
    public val daysOfWeekMask: Long? = null,
    public val priority: Long? = null,
    public val retentionDays: Long? = null,
    public val directory: String? = null,
    public val owner: String? = null,
    public val creator: String? = null,
    public val configId: String? = null,
    public val comment: String? = null,
) : HtspServerMessage {
    init {
        require(channelId >= 0) { "channelId must be non-negative" }
        require(startMinutesSinceMidnight in 0..1_440) {
            "startMinutesSinceMidnight must be between 0 and 1440"
        }
        require(stopMinutesSinceMidnight in 0..1_440) {
            "stopMinutesSinceMidnight must be between 0 and 1440"
        }
        daysOfWeekMask?.let { requireServerU32("daysOfWeekMask", it) }
        priority?.let { requireServerU32("priority", it) }
        retentionDays?.let { requireServerU32("retentionDays", it) }
    }
}

/** A partial time-based DVR rule update. Null properties were absent on the wire. */
public data class HtspTimerecEntryUpdateMessage(
    public val id: String,
    public val enabled: Boolean? = null,
    public val name: String? = null,
    public val title: String? = null,
    public val channelId: Int? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val stopMinutesSinceMidnight: Int? = null,
    public val daysOfWeekMask: Long? = null,
    public val priority: Long? = null,
    public val retentionDays: Long? = null,
    public val directory: String? = null,
    public val owner: String? = null,
    public val creator: String? = null,
    public val configId: String? = null,
    public val comment: String? = null,
) : HtspServerMessage {
    init {
        require(channelId == null || channelId >= 0) { "channelId must be non-negative" }
        require(startMinutesSinceMidnight == null || startMinutesSinceMidnight in 0..1_440) {
            "startMinutesSinceMidnight must be between 0 and 1440"
        }
        require(stopMinutesSinceMidnight == null || stopMinutesSinceMidnight in 0..1_440) {
            "stopMinutesSinceMidnight must be between 0 and 1440"
        }
        daysOfWeekMask?.let { requireServerU32("daysOfWeekMask", it) }
        priority?.let { requireServerU32("priority", it) }
        retentionDays?.let { requireServerU32("retentionDays", it) }
    }
}

public data class HtspTimerecEntryDeleteMessage(public val id: String) : HtspServerMessage

public class HtspEventAddMessage(
    event: HtspEvent,
    public val genre: String? = null,
    public val episodeId: Long? = null,
    public val seriesLinkId: Long? = null,
) : HtspServerMessage {
    public val event: HtspEvent = event.copy(
        categories = event.categories?.immutableServerSnapshot(),
        keywords = event.keywords?.immutableServerSnapshot(),
    )

    init {
        requireServerU32("eventId", this.event.eventId)
        listOfNotNull(
            this.event.channelId,
            this.event.contentType,
            this.event.ageRating,
            this.event.starRating,
            this.event.copyrightYear,
            this.event.isNew,
            this.event.seasonNumber,
            this.event.seasonCount,
            this.event.episodeNumber,
            this.event.episodeCount,
            this.event.partNumber,
            this.event.partCount,
            this.event.dvrId,
            this.event.nextEventId,
            episodeId,
            seriesLinkId,
        ).forEach { requireServerU32("event field", it) }
    }
}

public class HtspEventUpdateMessage(
    public val eventId: Long,
    public val channelId: Long? = null,
    public val start: Long? = null,
    public val stop: Long? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val genre: String? = null,
    categories: List<String>? = null,
    keywords: List<String>? = null,
    public val seriesLinkUri: String? = null,
    public val episodeUri: String? = null,
    public val contentType: Long? = null,
    public val ageRating: Long? = null,
    public val ratingLabel: String? = null,
    public val ratingIcon: String? = null,
    public val ratingAuthority: String? = null,
    public val ratingCountry: String? = null,
    public val starRating: Long? = null,
    public val copyrightYear: Long? = null,
    public val firstAired: Long? = null,
    public val isNew: Long? = null,
    public val seasonNumber: Long? = null,
    public val seasonCount: Long? = null,
    public val episodeNumber: Long? = null,
    public val episodeCount: Long? = null,
    public val partNumber: Long? = null,
    public val partCount: Long? = null,
    public val episodeOnscreen: String? = null,
    public val episodeId: Long? = null,
    public val seriesLinkId: Long? = null,
    public val image: String? = null,
    public val dvrId: Long? = null,
    public val nextEventId: Long? = null,
) : HtspServerMessage {
    public val categories: List<String>? = categories?.immutableServerSnapshot()
    public val keywords: List<String>? = keywords?.immutableServerSnapshot()

    init {
        requireServerU32("eventId", eventId)
        listOf(
            "channelId" to channelId, "contentType" to contentType, "ageRating" to ageRating,
            "starRating" to starRating, "copyrightYear" to copyrightYear, "isNew" to isNew,
            "seasonNumber" to seasonNumber, "seasonCount" to seasonCount,
            "episodeNumber" to episodeNumber, "episodeCount" to episodeCount,
            "partNumber" to partNumber, "partCount" to partCount,
            "episodeId" to episodeId, "seriesLinkId" to seriesLinkId,
            "dvrId" to dvrId, "nextEventId" to nextEventId,
        ).forEach { (name, value) -> value?.let { requireServerU32(name, it) } }
    }
}

public data class HtspEventDeleteMessage(public val eventId: Long) : HtspServerMessage {
    init {
        requireServerU32("eventId", eventId)
    }
}

public data object HtspInitialSyncCompletedMessage : HtspServerMessage

public data class HtspMuxPacketMessage(
    public val subscriptionId: Long,
    public val frameType: Long,
    public val streamIndex: Long,
    public val decodingTimestamp: Long?,
    public val presentationTimestamp: Long?,
    public val duration: Long,
    public val payload: HtspBinary,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
        requireServerU32("frameType", frameType)
        requireServerU32("streamIndex", streamIndex)
        requireServerU32("duration", duration)
    }
}

public data class HtspQueueStatusMessage(
    public val subscriptionId: Long,
    public val packetCount: Long,
    public val byteCount: Long,
    public val delay: Long?,
    public val bFrameDropCount: Long,
    public val pFrameDropCount: Long,
    public val iFrameDropCount: Long,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
        requireServerU32("packetCount", packetCount)
        requireServerU32("byteCount", byteCount)
        requireServerU32("bFrameDropCount", bFrameDropCount)
        requireServerU32("pFrameDropCount", pFrameDropCount)
        requireServerU32("iFrameDropCount", iFrameDropCount)
    }
}

public data class HtspSubscriptionStream(
    public val streamIndex: Long,
    public val streamType: String,
    public val language: String?,
    public val compositionId: Long?,
    public val ancillaryId: Long?,
    public val width: Long?,
    public val height: Long?,
    public val frameDuration: Long?,
    public val aspectNumerator: Long?,
    public val aspectDenominator: Long?,
    public val audioType: Long?,
    public val audioVersion: Long?,
    public val channelCount: Long?,
    public val sampleRate: Long?,
    public val rdsUecp: Long?,
) {
    init {
        requireServerU32("streamIndex", streamIndex)
        listOfNotNull(
            compositionId,
            ancillaryId,
            width,
            height,
            frameDuration,
            aspectNumerator,
            aspectDenominator,
            audioType,
            audioVersion,
            channelCount,
            sampleRate,
            rdsUecp,
        ).forEach { requireServerU32("stream field", it) }
    }
}

public data class HtspSubscriptionSourceInfo(
    public val adapterUuid: String?,
    public val muxUuid: String?,
    public val networkUuid: String?,
    public val adapter: String?,
    public val mux: String?,
    public val network: String?,
    public val networkType: String?,
    public val provider: String?,
    public val service: String?,
    public val satellitePosition: String?,
)

public class HtspSubscriptionStartMessage(
    public val subscriptionId: Long,
    streams: List<HtspSubscriptionStream>? = null,
    public val sourceInfo: HtspSubscriptionSourceInfo? = null,
    public val codecMetadata: HtspBinary? = null,
    public val status: String? = null,
    public val subscriptionError: String? = null,
) : HtspServerMessage {
    public val streams: List<HtspSubscriptionStream>? = streams?.immutableServerSnapshot()

    init {
        requireServerU32("subscriptionId", subscriptionId)
    }
}

public data class HtspSubscriptionStopMessage(
    public val subscriptionId: Long,
    public val status: String?,
    public val subscriptionError: String?,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
    }
}

public data class HtspSubscriptionGraceMessage(
    public val subscriptionId: Long,
    public val graceTimeoutSeconds: Long,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
        requireServerU32("graceTimeoutSeconds", graceTimeoutSeconds)
    }
}

public data class HtspSubscriptionStatusMessage(
    public val subscriptionId: Long,
    public val status: String?,
    public val subscriptionError: String?,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
    }
}

public data class HtspSignalStatusMessage(
    public val subscriptionId: Long,
    public val frontendStatus: String?,
    public val relativeSnr: Long?,
    public val absoluteSnr: Long?,
    public val relativeSignal: Long?,
    public val absoluteSignal: Long?,
    public val bitErrorRate: Long?,
    public val uncorrectedBlockCount: Long?,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
        listOfNotNull(relativeSnr, relativeSignal, bitErrorRate, uncorrectedBlockCount).forEach {
            requireServerU32("signal field", it)
        }
    }
}

public data class HtspSubscriptionSpeedMessage(
    public val subscriptionId: Long,
    public val speed: Int,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
    }
}

public data class HtspTimeshiftStatusMessage(
    public val subscriptionId: Long,
    public val full: Long,
    public val shift: Long,
    public val start: Long?,
    public val end: Long?,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
        requireServerU32("full", full)
    }
}

public data class HtspSubscriptionSkipMessage(
    public val subscriptionId: Long,
    public val absolute: Long?,
    public val error: Long?,
    public val time: Long?,
    public val sizeBytes: Long?,
) : HtspServerMessage {
    init {
        requireServerU32("subscriptionId", subscriptionId)
        absolute?.let { requireServerU32("absolute", it) }
        error?.let { requireServerU32("error", it) }
    }
}

private class `HtspServerMessageMappingException-internal` : IllegalArgumentException()

private typealias HtspServerMessageMappingException =
    `HtspServerMessageMappingException-internal`

@JvmSynthetic
internal fun decodeChannelAdd(fields: Map<String, Any?>): HtspServerMessage = HtspChannelAddMessage(
    channelId = fields.requiredServerU32("channelId"),
    channelUuid = fields.optionalServerString("channelIdStr"),
    channelNumber = fields.optionalServerAliasU32(CHANNEL_NUMBER_KEYS),
    channelNumberMinor = fields.optionalServerU32("channelNumberMinor"),
    channelName = fields.optionalServerString("channelName"),
    channelIcon = fields.optionalServerString("channelIcon"),
    currentEventId = fields.optionalServerU32("eventId"),
    nextEventId = fields.optionalServerU32("nextEventId"),
    services = fields.optionalServerObjectList("services", ::decodeServerChannelService),
    tagIds = fields.optionalServerAliasU32List(CHANNEL_TAG_KEYS),
)

@JvmSynthetic
internal fun decodeChannelUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspChannelUpdateMessage(
    channelId = fields.requiredServerU32("channelId"),
    channelUuid = fields.optionalServerString("channelIdStr"),
    channelNumber = fields.optionalServerAliasU32(CHANNEL_NUMBER_KEYS),
    channelNumberMinor = fields.optionalServerU32("channelNumberMinor"),
    channelName = fields.optionalServerString("channelName"),
    channelIcon = fields.optionalServerString("channelIcon"),
    currentEventId = fields.optionalServerU32("eventId"),
    nextEventId = fields.optionalServerU32("nextEventId"),
    services = fields.optionalServerObjectList("services", ::decodeServerChannelService),
    tagIds = fields.optionalServerAliasU32List(CHANNEL_TAG_KEYS),
)

@JvmSynthetic
internal fun decodeChannelDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspChannelDeleteMessage(fields.requiredServerU32("channelId"))

@JvmSynthetic
internal fun decodeTagAdd(fields: Map<String, Any?>): HtspServerMessage = HtspTagAddMessage(
    tagId = fields.requiredServerAliasU32(TAG_ID_KEYS),
    tagUuid = fields.optionalServerString("tagIdStr"),
    tagIndex = fields.optionalServerAliasU32(TAG_INDEX_KEYS),
    tagName = fields.optionalServerAliasString(TAG_NAME_KEYS),
    tagIcon = fields.optionalServerString("tagIcon"),
    tagTitledIcon = fields.optionalServerU32("tagTitledIcon"),
    channelIds = fields.optionalServerU32List("members"),
)

@JvmSynthetic
internal fun decodeTagUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspTagUpdateMessage(
    tagId = fields.requiredServerAliasU32(TAG_ID_KEYS),
    tagUuid = fields.optionalServerString("tagIdStr"),
    tagIndex = fields.optionalServerAliasU32(TAG_INDEX_KEYS),
    tagName = fields.optionalServerAliasString(TAG_NAME_KEYS),
    tagIcon = fields.optionalServerString("tagIcon"),
    tagTitledIcon = fields.optionalServerU32("tagTitledIcon"),
    channelIds = fields.optionalServerU32List("members"),
)

@JvmSynthetic
internal fun decodeTagDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspTagDeleteMessage(fields.requiredServerAliasU32(TAG_ID_KEYS))

@JvmSynthetic
internal fun decodeDvrEntryAdd(fields: Map<String, Any?>): HtspServerMessage = HtspDvrEntryAddMessage(
    entryId = fields.requiredServerAliasU32(DVR_ID_KEYS),
    entryUuid = fields.optionalServerString("idStr"),
    enabled = fields.optionalServerU32("enabled"),
    channelId = fields.optionalServerAliasU32(DVR_CHANNEL_KEYS),
    channelName = fields.optionalServerString("channelName"),
    eventId = fields.optionalServerU32("eventId"),
    autorecEntryUuid = fields.optionalServerString("autorecId"),
    timerecEntryUuid = fields.optionalServerString("timerecId"),
    start = fields.optionalServerS64("start"),
    stop = fields.optionalServerS64("stop"),
    startExtraMinutes = fields.optionalServerS64("startExtra"),
    stopExtraMinutes = fields.optionalServerS64("stopExtra"),
    retentionDays = fields.optionalServerU32("retention"),
    removalDays = fields.optionalServerU32("removal"),
    priority = fields.optionalServerU32("priority"),
    contentType = fields.optionalServerU32("contentType"),
    ageRating = fields.optionalServerU32("ageRating"),
    ratingLabel = fields.optionalServerString("ratingLabel"),
    ratingIcon = fields.optionalServerString("ratingIcon"),
    ratingAuthority = fields.optionalServerString("ratingAuthority"),
    ratingCountry = fields.optionalServerString("ratingCountry"),
    playCount = fields.optionalServerAliasU32(DVR_PLAY_COUNT_KEYS),
    playPositionSeconds = fields.optionalServerAliasU32(DVR_PLAY_POSITION_KEYS),
    seasonNumber = fields.optionalServerU32("seasonNumber"),
    episodeNumber = fields.optionalServerU32("episodeNumber"),
    episodeCount = fields.optionalServerU32("episodeCount"),
    partNumber = fields.optionalServerU32("partNumber"),
    partCount = fields.optionalServerU32("partCount"),
    title = fields.optionalServerString("title"),
    description = fields.optionalServerString("description"),
    summary = fields.optionalServerString("summary"),
    subtitle = fields.optionalServerString("subtitle"),
    owner = fields.optionalServerString("owner"),
    creator = fields.optionalServerString("creator"),
    comment = fields.optionalServerString("comment"),
    image = fields.optionalServerString("image"),
    fanartImage = fields.optionalServerString("fanartImage"),
    copyrightYear = fields.optionalServerU32("copyrightYear"),
    files = fields.optionalServerObjectList("files", ::decodeDvrRecordingFile),
    path = fields.optionalServerString("path"),
    dvrConfigUuid = fields.optionalServerString("configId"),
    duplicate = fields.optionalServerU32("duplicate"),
    state = fields.optionalServerAliasString(STATUS_KEYS),
    error = fields.optionalServerAliasString(DVR_ERROR_KEYS),
    subscriptionError = fields.optionalServerString("subscriptionError"),
    streamErrors = fields.optionalServerU32("streamErrors"),
    dataErrors = fields.optionalServerU32("dataErrors"),
    dataSizeBytes = fields.optionalServerS64("dataSize"),
)

@JvmSynthetic
internal fun decodeDvrEntryUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspDvrEntryUpdateMessage(
    entryId = fields.requiredServerAliasU32(DVR_ID_KEYS),
    entryUuid = fields.optionalServerString("idStr"),
    enabled = fields.optionalServerU32("enabled"),
    channelId = fields.optionalServerAliasU32(DVR_CHANNEL_KEYS),
    channelName = fields.optionalServerString("channelName"),
    eventId = fields.optionalServerU32("eventId"),
    autorecEntryUuid = fields.optionalServerString("autorecId"),
    timerecEntryUuid = fields.optionalServerString("timerecId"),
    start = fields.optionalServerS64("start"),
    stop = fields.optionalServerS64("stop"),
    startExtraMinutes = fields.optionalServerS64("startExtra"),
    stopExtraMinutes = fields.optionalServerS64("stopExtra"),
    retentionDays = fields.optionalServerU32("retention"),
    removalDays = fields.optionalServerU32("removal"),
    priority = fields.optionalServerU32("priority"),
    contentType = fields.optionalServerU32("contentType"),
    ageRating = fields.optionalServerU32("ageRating"),
    ratingLabel = fields.optionalServerString("ratingLabel"),
    ratingIcon = fields.optionalServerString("ratingIcon"),
    ratingAuthority = fields.optionalServerString("ratingAuthority"),
    ratingCountry = fields.optionalServerString("ratingCountry"),
    playCount = fields.optionalServerAliasU32(DVR_PLAY_COUNT_KEYS),
    playPositionSeconds = fields.optionalServerAliasU32(DVR_PLAY_POSITION_KEYS),
    seasonNumber = fields.optionalServerU32("seasonNumber"),
    episodeNumber = fields.optionalServerU32("episodeNumber"),
    episodeCount = fields.optionalServerU32("episodeCount"),
    partNumber = fields.optionalServerU32("partNumber"),
    partCount = fields.optionalServerU32("partCount"),
    title = fields.optionalServerString("title"),
    description = fields.optionalServerString("description"),
    summary = fields.optionalServerString("summary"),
    subtitle = fields.optionalServerString("subtitle"),
    owner = fields.optionalServerString("owner"),
    creator = fields.optionalServerString("creator"),
    comment = fields.optionalServerString("comment"),
    image = fields.optionalServerString("image"),
    fanartImage = fields.optionalServerString("fanartImage"),
    copyrightYear = fields.optionalServerU32("copyrightYear"),
    files = fields.optionalServerObjectList("files", ::decodeDvrRecordingFile),
    path = fields.optionalServerString("path"),
    dvrConfigUuid = fields.optionalServerString("configId"),
    duplicate = fields.optionalServerU32("duplicate"),
    state = fields.optionalServerAliasString(STATUS_KEYS),
    error = fields.optionalServerAliasString(DVR_ERROR_KEYS),
    subscriptionError = fields.optionalServerString("subscriptionError"),
    streamErrors = fields.optionalServerU32("streamErrors"),
    dataErrors = fields.optionalServerU32("dataErrors"),
    dataSizeBytes = fields.optionalServerS64("dataSize"),
)

@JvmSynthetic
internal fun decodeDvrEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspDvrEntryDeleteMessage(fields.requiredServerAliasU32(DVR_ID_KEYS))

@JvmSynthetic
internal fun decodeAutorecEntryAdd(fields: Map<String, Any?>): HtspServerMessage =
    HtspAutorecEntryAddMessage(
        id = fields.requiredServerString("id"),
        enabled = fields.requiredServerFlag("enabled"),
        maxDurationSeconds = fields.requiredServerU32("maxDuration"),
        minDurationSeconds = fields.requiredServerU32("minDuration"),
        retentionDays = fields.requiredServerU32("retention"),
        removalDays = fields.requiredServerU32("removal"),
        daysOfWeekMask = fields.requiredServerU32("daysOfWeek"),
        approximateStartMinutesSinceMidnight = fields.requiredServerS32("approxTime"),
        startMinutesSinceMidnight = fields.requiredServerS32("start"),
        startWindowEndMinutesSinceMidnight = fields.requiredServerS32("startWindow"),
        priority = fields.requiredServerU32("priority"),
        startExtraMinutes = fields.requiredServerS64("startExtra"),
        stopExtraMinutes = fields.requiredServerS64("stopExtra"),
        duplicateDetection = fields.requiredServerU32("dupDetect"),
        maximumRecordingCount = fields.requiredServerU32("maxCount"),
        broadcastType = fields.requiredServerU32("broadcastType"),
        comment = fields.requiredServerString("comment"),
        title = fields.optionalServerString("title"),
        fullText = fields.optionalServerFlag("fulltext"),
        mergeText = fields.optionalServerFlag("mergetext"),
        name = fields.requiredServerString("name"),
        directory = fields.optionalServerString("directory"),
        owner = fields.requiredServerString("owner"),
        creator = fields.requiredServerString("creator"),
        channelId = fields.optionalServerU32("channel"),
        seriesLinkUri = fields.optionalServerString("serieslinkUri"),
        configId = fields.optionalServerString("configId"),
    )

@JvmSynthetic
internal fun decodeAutorecEntryUpdate(fields: Map<String, Any?>): HtspServerMessage =
    HtspAutorecEntryUpdateMessage(
        id = fields.requiredServerString("id"),
        enabled = fields.optionalServerFlag("enabled"),
        maxDurationSeconds = fields.optionalServerU32("maxDuration"),
        minDurationSeconds = fields.optionalServerU32("minDuration"),
        retentionDays = fields.optionalServerU32("retention"),
        removalDays = fields.optionalServerU32("removal"),
        daysOfWeekMask = fields.optionalServerU32("daysOfWeek"),
        approximateStartMinutesSinceMidnight = fields.optionalServerS32("approxTime"),
        startMinutesSinceMidnight = fields.optionalServerS32("start"),
        startWindowEndMinutesSinceMidnight = fields.optionalServerS32("startWindow"),
        priority = fields.optionalServerU32("priority"),
        startExtraMinutes = fields.optionalServerS64("startExtra"),
        stopExtraMinutes = fields.optionalServerS64("stopExtra"),
        duplicateDetection = fields.optionalServerU32("dupDetect"),
        maximumRecordingCount = fields.optionalServerU32("maxCount"),
        broadcastType = fields.optionalServerU32("broadcastType"),
        comment = fields.optionalServerString("comment"),
        title = fields.optionalServerString("title"),
        fullText = fields.optionalServerFlag("fulltext"),
        mergeText = fields.optionalServerFlag("mergetext"),
        name = fields.optionalServerString("name"),
        directory = fields.optionalServerString("directory"),
        owner = fields.optionalServerString("owner"),
        creator = fields.optionalServerString("creator"),
        channelId = fields.optionalServerU32("channel"),
        seriesLinkUri = fields.optionalServerString("serieslinkUri"),
        configId = fields.optionalServerString("configId"),
    )

@JvmSynthetic
internal fun decodeAutorecEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspAutorecEntryDeleteMessage(fields.requiredServerString("id"))

@JvmSynthetic
internal fun decodeTimerecEntryAdd(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimerecEntryAddMessage(
        id = fields.requiredServerString("id"),
        enabled = fields.requiredServerFlag("enabled"),
        name = fields.requiredServerString("name"),
        title = fields.requiredServerString("title"),
        channelId = fields.requiredServerBoundedInt("channel", 0..Int.MAX_VALUE),
        startMinutesSinceMidnight = fields.requiredServerBoundedInt("start", 0..1_440),
        stopMinutesSinceMidnight = fields.requiredServerBoundedInt("stop", 0..1_440),
        daysOfWeekMask = optionalTimerecValue { fields.optionalServerU32("daysOfWeek") },
        priority = optionalTimerecValue { fields.optionalServerU32("priority") },
        retentionDays = optionalTimerecValue { fields.optionalServerU32("retention") },
        directory = optionalTimerecValue { fields.optionalServerString("directory") },
        owner = optionalTimerecValue { fields.optionalServerString("owner") },
        creator = optionalTimerecValue { fields.optionalServerString("creator") },
        configId = optionalTimerecValue { fields.optionalServerString("configId") },
        comment = optionalTimerecValue { fields.optionalServerString("comment") },
    )

@JvmSynthetic
internal fun decodeTimerecEntryUpdate(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimerecEntryUpdateMessage(
        id = fields.requiredServerString("id"),
        enabled = optionalTimerecValue { fields.optionalServerFlag("enabled") },
        name = optionalTimerecValue { fields.optionalServerString("name") },
        title = optionalTimerecValue { fields.optionalServerString("title") },
        channelId = optionalTimerecValue {
            fields.optionalServerBoundedInt("channel", 0..Int.MAX_VALUE)
        },
        startMinutesSinceMidnight = optionalTimerecValue {
            fields.optionalServerBoundedInt("start", 0..1_440)
        },
        stopMinutesSinceMidnight = optionalTimerecValue {
            fields.optionalServerBoundedInt("stop", 0..1_440)
        },
        daysOfWeekMask = optionalTimerecValue { fields.optionalServerU32("daysOfWeek") },
        priority = optionalTimerecValue { fields.optionalServerU32("priority") },
        retentionDays = optionalTimerecValue { fields.optionalServerU32("retention") },
        directory = optionalTimerecValue { fields.optionalServerString("directory") },
        owner = optionalTimerecValue { fields.optionalServerString("owner") },
        creator = optionalTimerecValue { fields.optionalServerString("creator") },
        configId = optionalTimerecValue { fields.optionalServerString("configId") },
        comment = optionalTimerecValue { fields.optionalServerString("comment") },
    )

@JvmSynthetic
internal fun decodeTimerecEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimerecEntryDeleteMessage(fields.requiredServerString("id"))

@JvmSynthetic
internal fun decodeEventAdd(fields: Map<String, Any?>): HtspServerMessage =
    HtspEventAddMessage(
        event = decodeServerEvent(fields),
        genre = fields.optionalServerEventGenre(),
        episodeId = fields.optionalServerU32("episodeId"),
        seriesLinkId = fields.optionalServerAliasU32(listOf("serieslinkId", "seriesLinkId")),
    )

@JvmSynthetic
internal fun decodeEventUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspEventUpdateMessage(
    eventId = fields.requiredServerAliasU32(EVENT_ID_KEYS),
    channelId = fields.optionalServerAliasU32(EVENT_CHANNEL_KEYS),
    start = fields.optionalServerAliasS64(EVENT_START_KEYS),
    stop = fields.optionalServerAliasS64(EVENT_STOP_KEYS),
    title = fields.optionalServerAliasString(EVENT_TITLE_KEYS),
    subtitle = fields.optionalServerString("subtitle"),
    summary = fields.optionalServerString("summary"),
    description = fields.optionalServerString("description"),
    genre = fields.optionalServerEventGenre(),
    categories = fields.optionalServerEventCategories(),
    keywords = fields.optionalServerStringList("keyword"),
    seriesLinkUri = fields.optionalServerString("serieslinkUri"),
    episodeUri = fields.optionalServerString("episodeUri"),
    contentType = fields.optionalServerAliasU32(EVENT_CONTENT_KEYS),
    ageRating = fields.optionalServerU32("ageRating"),
    ratingLabel = fields.optionalServerString("ratingLabel"),
    ratingIcon = fields.optionalServerString("ratingIcon"),
    ratingAuthority = fields.optionalServerString("ratingAuthority"),
    ratingCountry = fields.optionalServerString("ratingCountry"),
    starRating = fields.optionalServerU32("starRating"),
    copyrightYear = fields.optionalServerU32("copyrightYear"),
    firstAired = fields.optionalServerS64("firstAired"),
    isNew = fields.optionalServerU32("isNew"),
    seasonNumber = fields.optionalServerEventU32(listOf("seasonNumber", "season"), listOf("seasonNumber", "season")),
    seasonCount = fields.optionalServerEventU32(listOf("seasonCount"), listOf("seasonCount", "count")),
    episodeNumber = fields.optionalServerEventU32(listOf("episodeNumber"), listOf("episodeNumber", "number")),
    episodeCount = fields.optionalServerEventU32(listOf("episodeCount"), listOf("episodeCount", "count")),
    partNumber = fields.optionalServerEventU32(listOf("partNumber", "part"), listOf("partNumber", "part")),
    partCount = fields.optionalServerEventU32(listOf("partCount"), listOf("partCount")),
    episodeOnscreen = fields.optionalServerString("episodeOnscreen"),
    episodeId = fields.optionalServerU32("episodeId"),
    seriesLinkId = fields.optionalServerAliasU32(listOf("serieslinkId", "seriesLinkId")),
    image = fields.optionalServerString("image"),
    dvrId = fields.optionalServerU32("dvrId"),
    nextEventId = fields.optionalServerU32("nextEventId"),
)

@JvmSynthetic
internal fun decodeEventDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspEventDeleteMessage(fields.requiredServerAliasU32(EVENT_ID_KEYS))

@JvmSynthetic
internal fun decodeInitialSyncCompleted(fields: Map<String, Any?>): HtspServerMessage =
    HtspInitialSyncCompletedMessage

@JvmSynthetic
internal fun decodeMuxPacket(fields: Map<String, Any?>): HtspServerMessage = HtspMuxPacketMessage(
    subscriptionId = fields.requiredServerU32("subscriptionId"),
    frameType = fields.requiredServerU32("frametype"),
    streamIndex = fields.requiredServerU32("stream"),
    decodingTimestamp = fields.optionalServerS64("dts"),
    presentationTimestamp = fields.optionalServerS64("pts"),
    duration = fields.requiredServerU32("duration"),
    payload = HtspBinary(fields.requiredServerBinary("payload")),
)

@JvmSynthetic
internal fun decodeQueueStatus(fields: Map<String, Any?>): HtspServerMessage = HtspQueueStatusMessage(
    subscriptionId = fields.requiredServerU32("subscriptionId"),
    packetCount = fields.requiredServerU32("packets"),
    byteCount = fields.requiredServerU32("bytes"),
    delay = fields.optionalServerS64("delay"),
    bFrameDropCount = fields.requiredServerU32("Bdrops"),
    pFrameDropCount = fields.requiredServerU32("Pdrops"),
    iFrameDropCount = fields.requiredServerU32("Idrops"),
)

@JvmSynthetic
internal fun decodeSubscriptionStart(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionStartMessage(
        subscriptionId = fields.requiredServerAliasU32(SUBSCRIPTION_ID_KEYS),
        streams = fields.optionalServerObjectList("streams", ::decodeSubscriptionStream),
        sourceInfo = if (fields.containsKey("sourceinfo")) {
            decodeSubscriptionSourceInfo(fields.requiredServerObject("sourceinfo"))
        } else {
            null
        },
        codecMetadata = fields.optionalServerBinary("meta")?.let(::HtspBinary),
        status = fields.optionalServerAliasString(STATUS_KEYS),
        subscriptionError = fields.optionalServerAliasString(SUBSCRIPTION_ERROR_KEYS),
    )

@JvmSynthetic
internal fun decodeSubscriptionStop(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionStopMessage(
        subscriptionId = fields.requiredServerAliasU32(SUBSCRIPTION_ID_KEYS),
        status = fields.optionalServerAliasString(STATUS_KEYS),
        subscriptionError = fields.optionalServerAliasString(SUBSCRIPTION_ERROR_KEYS),
    )

@JvmSynthetic
internal fun decodeSubscriptionGrace(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionGraceMessage(
        subscriptionId = fields.requiredServerU32("subscriptionId"),
        graceTimeoutSeconds = fields.requiredServerU32("graceTimeout"),
    )

@JvmSynthetic
internal fun decodeSubscriptionStatus(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionStatusMessage(
        subscriptionId = fields.requiredServerAliasU32(SUBSCRIPTION_ID_KEYS),
        status = fields.optionalServerAliasString(STATUS_KEYS),
        subscriptionError = fields.optionalServerAliasString(SUBSCRIPTION_ERROR_KEYS),
    )

@JvmSynthetic
internal fun decodeSignalStatus(fields: Map<String, Any?>): HtspServerMessage = HtspSignalStatusMessage(
    subscriptionId = fields.requiredServerU32("subscriptionId"),
    frontendStatus = fields.optionalServerString("feStatus"),
    relativeSnr = fields.optionalServerU32("feSNR"),
    absoluteSnr = fields.optionalServerS64("feAbsoluteSNR"),
    relativeSignal = fields.optionalServerU32("feSignal"),
    absoluteSignal = fields.optionalServerS64("feAbsoluteSignal"),
    bitErrorRate = fields.optionalServerU32("feBER"),
    uncorrectedBlockCount = fields.optionalServerU32("feUNC"),
)

@JvmSynthetic
internal fun decodeSubscriptionSpeed(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionSpeedMessage(
        subscriptionId = fields.requiredServerU32("subscriptionId"),
        speed = fields.requiredServerS32("speed"),
    )

@JvmSynthetic
internal fun decodeTimeshiftStatus(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimeshiftStatusMessage(
        subscriptionId = fields.requiredServerU32("subscriptionId"),
        full = fields.requiredServerU32("full"),
        shift = fields.requiredServerS64("shift"),
        start = fields.optionalServerS64("start"),
        end = fields.optionalServerS64("end"),
    )

@JvmSynthetic
internal fun decodeSubscriptionSkip(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionSkipMessage(
        subscriptionId = fields.requiredServerU32("subscriptionId"),
        absolute = fields.optionalServerU32("absolute"),
        error = fields.optionalServerU32("error"),
        time = fields.optionalServerS64("time"),
        sizeBytes = fields.optionalServerS64("size"),
    )

private fun decodeServerChannelService(fields: Map<*, *>): HtspChannelService = HtspChannelService(
    name = fields.requiredServerString("name"),
    type = fields.requiredServerString("type"),
    content = fields.requiredServerU32("content"),
    conditionalAccessId = fields.optionalServerU32("caid"),
    conditionalAccessName = fields.optionalServerString("caname"),
    providerName = fields.optionalServerString("providername"),
)

private fun decodeDvrRecordingFile(fields: Map<*, *>): HtspDvrRecordingFile = HtspDvrRecordingFile(
    fileId = fields.optionalServerU32("id"),
    path = fields.optionalServerAliasString(DVR_FILE_PATH_KEYS),
    start = fields.optionalServerS64("start"),
    stop = fields.optionalServerS64("stop"),
    sizeBytes = fields.optionalServerS64("size"),
)

private fun decodeServerEvent(fields: Map<*, *>): HtspEvent = HtspEvent(
    eventId = fields.requiredServerAliasU32(EVENT_ID_KEYS),
    channelId = fields.optionalServerAliasU32(EVENT_CHANNEL_KEYS),
    start = fields.requiredServerAliasS64(EVENT_START_KEYS),
    stop = fields.requiredServerAliasS64(EVENT_STOP_KEYS),
    title = fields.optionalServerAliasString(EVENT_TITLE_KEYS),
    subtitle = fields.optionalServerString("subtitle"),
    summary = fields.optionalServerString("summary"),
    description = fields.optionalServerString("description"),
    categories = fields.optionalServerEventCategories(),
    keywords = fields.optionalServerStringList("keyword"),
    seriesLinkUri = fields.optionalServerString("serieslinkUri"),
    episodeUri = fields.optionalServerString("episodeUri"),
    contentType = fields.optionalServerAliasU32(EVENT_CONTENT_KEYS),
    ageRating = fields.optionalServerU32("ageRating"),
    ratingLabel = fields.optionalServerString("ratingLabel"),
    ratingIcon = fields.optionalServerString("ratingIcon"),
    ratingAuthority = fields.optionalServerString("ratingAuthority"),
    ratingCountry = fields.optionalServerString("ratingCountry"),
    starRating = fields.optionalServerU32("starRating"),
    copyrightYear = fields.optionalServerU32("copyrightYear"),
    firstAired = fields.optionalServerS64("firstAired"),
    isNew = fields.optionalServerU32("isNew"),
    seasonNumber = fields.optionalServerEventU32(listOf("seasonNumber", "season"), listOf("seasonNumber", "season")),
    seasonCount = fields.optionalServerEventU32(listOf("seasonCount"), listOf("seasonCount", "count")),
    episodeNumber = fields.optionalServerEventU32(listOf("episodeNumber"), listOf("episodeNumber", "number")),
    episodeCount = fields.optionalServerEventU32(listOf("episodeCount"), listOf("episodeCount", "count")),
    partNumber = fields.optionalServerEventU32(listOf("partNumber", "part"), listOf("partNumber", "part")),
    partCount = fields.optionalServerEventU32(listOf("partCount"), listOf("partCount")),
    episodeOnscreen = fields.optionalServerString("episodeOnscreen"),
    image = fields.optionalServerString("image"),
    dvrId = fields.optionalServerU32("dvrId"),
    nextEventId = fields.optionalServerU32("nextEventId"),
)

private fun decodeSubscriptionStream(fields: Map<*, *>): HtspSubscriptionStream = HtspSubscriptionStream(
    streamIndex = fields.requiredServerU32("index"),
    streamType = fields.requiredServerString("type"),
    language = fields.optionalServerString("language"),
    compositionId = fields.optionalServerU32("composition_id"),
    ancillaryId = fields.optionalServerU32("ancillary_id"),
    width = fields.optionalServerU32("width"),
    height = fields.optionalServerU32("height"),
    frameDuration = fields.optionalServerU32("duration"),
    aspectNumerator = fields.optionalServerU32("aspect_num"),
    aspectDenominator = fields.optionalServerU32("aspect_den"),
    audioType = fields.optionalServerU32("audio_type"),
    audioVersion = fields.optionalServerU32("audio_version"),
    channelCount = fields.optionalServerU32("channels"),
    sampleRate = fields.optionalServerU32("rate"),
    rdsUecp = fields.optionalServerU32("rds_uecp"),
)

private fun decodeSubscriptionSourceInfo(fields: Map<*, *>): HtspSubscriptionSourceInfo =
    HtspSubscriptionSourceInfo(
        adapterUuid = fields.optionalServerString("adapter_uuid"),
        muxUuid = fields.optionalServerString("mux_uuid"),
        networkUuid = fields.optionalServerString("network_uuid"),
        adapter = fields.optionalServerString("adapter"),
        mux = fields.optionalServerString("mux"),
        network = fields.optionalServerString("network"),
        networkType = fields.optionalServerString("network_type"),
        provider = fields.optionalServerString("provider"),
        service = fields.optionalServerString("service"),
        satellitePosition = fields.optionalServerString("satpos"),
    )

private fun requireServerU32(name: String, value: Long) {
    require(value in 0L..SERVER_MESSAGE_U32_MAX) { "$name must be in the HTSP u32 range" }
}

private inline fun <T> optionalTimerecValue(block: () -> T?): T? = try {
    block()
} catch (_: HtspServerMessageMappingException) {
    null
}

private fun <T> List<T>.immutableServerSnapshot(): List<T> =
    Collections.unmodifiableList(ArrayList(this))

private fun Map<*, *>.firstPresentServerName(names: List<String>): String? =
    names.firstOrNull(::containsKey)

private fun Map<*, *>.requiredServerAliasS64(names: List<String>): Long =
    requiredServerS64(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasS64(names: List<String>): Long? =
    firstPresentServerName(names)?.let(::requiredServerS64)

private fun Map<*, *>.requiredServerAliasU32(names: List<String>): Long =
    requiredServerU32(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasU32(names: List<String>): Long? =
    firstPresentServerName(names)?.let(::requiredServerU32)

private fun Map<*, *>.requiredServerAliasString(names: List<String>): String =
    requiredServerString(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasString(names: List<String>): String? =
    firstPresentServerName(names)?.let(::requiredServerString)

private fun Map<*, *>.optionalServerAliasU32List(names: List<String>): List<Long>? =
    firstPresentServerName(names)?.let(::requiredServerU32List)

private fun Map<*, *>.optionalServerEventGenre(): String? {
    if (containsKey("genre")) return requiredServerString("genre")
    if (!containsKey("category")) return null
    return when (this["category"]) {
        is String -> requiredServerString("category")
        is List<*> -> null
        else -> throw HtspServerMessageMappingException()
    }
}

private fun Map<*, *>.optionalServerEventCategories(): List<String>? {
    if (!containsKey("category")) return null
    return when (this["category"]) {
        is String -> null
        is List<*> -> requiredServerStringList("category")
        else -> throw HtspServerMessageMappingException()
    }
}

private fun Map<*, *>.optionalServerEventU32(
    topLevelNames: List<String>,
    nestedNames: List<String>,
): Long? {
    optionalServerAliasU32(topLevelNames)?.let { return it }
    if (!containsKey("episode")) return null
    return requiredServerObject("episode").optionalServerAliasU32(nestedNames)
}

private fun Map<*, *>.requiredServerS64(name: String): Long =
    this[name] as? Long ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.optionalServerS64(name: String): Long? =
    if (containsKey(name)) requiredServerS64(name) else null

private fun Map<*, *>.requiredServerS32(name: String): Int {
    val value = requiredServerS64(name)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw HtspServerMessageMappingException()
    }
    return value.toInt()
}

private fun Map<*, *>.optionalServerS32(name: String): Int? =
    if (containsKey(name)) requiredServerS32(name) else null

private fun Map<*, *>.requiredServerBoundedInt(name: String, range: IntRange): Int {
    val value = requiredServerS64(name)
    if (value !in range.first.toLong()..range.last.toLong()) {
        throw HtspServerMessageMappingException()
    }
    return value.toInt()
}

private fun Map<*, *>.optionalServerBoundedInt(name: String, range: IntRange): Int? =
    if (containsKey(name)) requiredServerBoundedInt(name, range) else null

private fun Map<*, *>.requiredServerFlag(name: String): Boolean = when (requiredServerS64(name)) {
    0L -> false
    1L -> true
    else -> throw HtspServerMessageMappingException()
}

private fun Map<*, *>.optionalServerFlag(name: String): Boolean? =
    if (containsKey(name)) requiredServerFlag(name) else null

private fun Map<*, *>.requiredServerU32(name: String): Long {
    val value = requiredServerS64(name)
    if (value !in 0L..SERVER_MESSAGE_U32_MAX) throw HtspServerMessageMappingException()
    return value
}

private fun Map<*, *>.optionalServerU32(name: String): Long? =
    if (containsKey(name)) requiredServerU32(name) else null

private fun Map<*, *>.requiredServerString(name: String): String =
    this[name] as? String ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.optionalServerString(name: String): String? =
    if (containsKey(name)) requiredServerString(name) else null

private fun Map<*, *>.requiredServerBinary(name: String): ByteArray =
    (this[name] as? ByteArray)?.copyOf() ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.optionalServerBinary(name: String): ByteArray? =
    if (containsKey(name)) requiredServerBinary(name) else null

private fun Map<*, *>.requiredServerObject(name: String): Map<*, *> =
    this[name] as? Map<*, *> ?: throw HtspServerMessageMappingException()

private fun Map<*, *>.requiredServerU32List(name: String): List<Long> {
    val source = this[name] as? List<*> ?: throw HtspServerMessageMappingException()
    return source.map { value ->
        val decoded = value as? Long ?: throw HtspServerMessageMappingException()
        if (decoded !in 0L..SERVER_MESSAGE_U32_MAX) throw HtspServerMessageMappingException()
        decoded
    }.immutableServerSnapshot()
}

private fun Map<*, *>.optionalServerU32List(name: String): List<Long>? =
    if (containsKey(name)) requiredServerU32List(name) else null

private fun Map<*, *>.requiredServerStringList(name: String): List<String> {
    val source = this[name] as? List<*> ?: throw HtspServerMessageMappingException()
    return source.map { it as? String ?: throw HtspServerMessageMappingException() }
        .immutableServerSnapshot()
}

private fun Map<*, *>.optionalServerStringList(name: String): List<String>? =
    if (containsKey(name)) requiredServerStringList(name) else null

private fun <T> Map<*, *>.requiredServerObjectList(
    name: String,
    mapper: (Map<*, *>) -> T,
): List<T> {
    val source = this[name] as? List<*> ?: throw HtspServerMessageMappingException()
    return source.map { value ->
        mapper(value as? Map<*, *> ?: throw HtspServerMessageMappingException())
    }.immutableServerSnapshot()
}

private fun <T> Map<*, *>.optionalServerObjectList(
    name: String,
    mapper: (Map<*, *>) -> T,
): List<T>? = if (containsKey(name)) requiredServerObjectList(name, mapper) else null
