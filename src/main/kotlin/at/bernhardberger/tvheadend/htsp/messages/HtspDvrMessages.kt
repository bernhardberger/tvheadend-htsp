package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** One bounded recording-file entry with optional file identity, path, time range, and byte size. */
public data class HtspDvrRecordingFile(
    public val fileId: Long?,
    public val path: String?,
    public val start: Long?,
    public val stop: Long?,
    public val sizeBytes: Long?,
) {
    init {
        fileId?.let { requireU32("fileId", it) }
    }
}

/** Carries a DVR-entry add message whose only required identity field is [entryId]; nullable schedule, metadata, state, progress, error, and file fields were absent when null. */
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
    public val files: List<HtspDvrRecordingFile>? = files?.immutableSnapshot()

    init {
        requireU32("entryId", entryId)
        enabled?.let { requireU32("enabled", it) }
        channelId?.let { requireU32("channelId", it) }
        eventId?.let { requireU32("eventId", it) }
        retentionDays?.let { requireU32("retentionDays", it) }
        removalDays?.let { requireU32("removalDays", it) }
        priority?.let { requireU32("priority", it) }
        contentType?.let { requireU32("contentType", it) }
        ageRating?.let { requireU32("ageRating", it) }
        playCount?.let { requireU32("playCount", it) }
        playPositionSeconds?.let { requireU32("playPositionSeconds", it) }
        seasonNumber?.let { requireU32("seasonNumber", it) }
        episodeNumber?.let { requireU32("episodeNumber", it) }
        episodeCount?.let { requireU32("episodeCount", it) }
        partNumber?.let { requireU32("partNumber", it) }
        partCount?.let { requireU32("partCount", it) }
        copyrightYear?.let { requireU32("copyrightYear", it) }
        duplicate?.let { requireU32("duplicate", it) }
        streamErrors?.let { requireU32("streamErrors", it) }
        dataErrors?.let { requireU32("dataErrors", it) }
    }
}

/** Carries one DVR-entry update; nullable schedule, metadata, progress, state, error, and file properties were absent when null. */
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
    public val files: List<HtspDvrRecordingFile>? = files?.immutableSnapshot()

    init {
        requireU32("entryId", entryId)
        enabled?.let { requireU32("enabled", it) }
        channelId?.let { requireU32("channelId", it) }
        eventId?.let { requireU32("eventId", it) }
        retentionDays?.let { requireU32("retentionDays", it) }
        removalDays?.let { requireU32("removalDays", it) }
        priority?.let { requireU32("priority", it) }
        contentType?.let { requireU32("contentType", it) }
        ageRating?.let { requireU32("ageRating", it) }
        playCount?.let { requireU32("playCount", it) }
        playPositionSeconds?.let { requireU32("playPositionSeconds", it) }
        seasonNumber?.let { requireU32("seasonNumber", it) }
        episodeNumber?.let { requireU32("episodeNumber", it) }
        episodeCount?.let { requireU32("episodeCount", it) }
        partNumber?.let { requireU32("partNumber", it) }
        partCount?.let { requireU32("partCount", it) }
        copyrightYear?.let { requireU32("copyrightYear", it) }
        duplicate?.let { requireU32("duplicate", it) }
        streamErrors?.let { requireU32("streamErrors", it) }
        dataErrors?.let { requireU32("dataErrors", it) }
    }
}

/** Carries the complete unsigned [entryId] reported by a DVR-entry delete message. */
public data class HtspDvrEntryDeleteMessage(
    public val entryId: Long,
) : HtspServerMessage {
    init {
        requireU32("entryId", entryId)
    }
}

/** Carries the bounded automatic-recording-rule fields reported by an add message, including match, schedule, ownership, and retention data. */
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
        requireU32("maxDurationSeconds", maxDurationSeconds)
        requireU32("minDurationSeconds", minDurationSeconds)
        requireU32("retentionDays", retentionDays)
        requireU32("removalDays", removalDays)
        requireU32("daysOfWeekMask", daysOfWeekMask)
        requireU32("priority", priority)
        requireU32("duplicateDetection", duplicateDetection)
        requireU32("maximumRecordingCount", maximumRecordingCount)
        requireU32("broadcastType", broadcastType)
        channelId?.let { requireU32("channelId", it) }
    }
}

/** Carries an automatic-recording-rule update; every nullable matching, scheduling, ownership, or retention property was absent when null. */
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
        maxDurationSeconds?.let { requireU32("maxDurationSeconds", it) }
        minDurationSeconds?.let { requireU32("minDurationSeconds", it) }
        retentionDays?.let { requireU32("retentionDays", it) }
        removalDays?.let { requireU32("removalDays", it) }
        daysOfWeekMask?.let { requireU32("daysOfWeekMask", it) }
        priority?.let { requireU32("priority", it) }
        duplicateDetection?.let { requireU32("duplicateDetection", it) }
        maximumRecordingCount?.let { requireU32("maximumRecordingCount", it) }
        broadcastType?.let { requireU32("broadcastType", it) }
        channelId?.let { requireU32("channelId", it) }
    }
}

/** Carries the string [id] reported by an automatic-recording-rule delete message. */
public data class HtspAutorecEntryDeleteMessage(public val id: String) : HtspServerMessage

/** Carries the bounded time-based recording-rule fields reported by an add message, including channel, interval, policy, and ownership data. */
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
        daysOfWeekMask?.let { requireU32("daysOfWeekMask", it) }
        priority?.let { requireU32("priority", it) }
        retentionDays?.let { requireU32("retentionDays", it) }
    }
}

/** Carries a time-based recording-rule update; nullable interval, channel, day, policy, and ownership properties were absent when null. */
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
        daysOfWeekMask?.let { requireU32("daysOfWeekMask", it) }
        priority?.let { requireU32("priority", it) }
        retentionDays?.let { requireU32("retentionDays", it) }
    }
}

/** Carries the string [id] reported by a time-based recording-rule delete message. */
public data class HtspTimerecEntryDeleteMessage(public val id: String) : HtspServerMessage
