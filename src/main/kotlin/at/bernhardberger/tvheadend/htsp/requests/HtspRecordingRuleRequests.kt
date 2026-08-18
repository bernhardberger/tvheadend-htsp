package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** Closed channel selector for automatic and time-based recording rule requests. */
public sealed interface HtspRecordingRuleChannel {
    /** Selects one recording-rule channel by its complete unsigned [channelId]. */
    @JvmInline
    public value class Id(public val channelId: Long) : HtspRecordingRuleChannel {
        init {
            requireU32("channelId", channelId)
        }
    }

    /** Encodes the signed `-1` any-channel sentinel; update requests may also clear a channel by omitting it. */
    public data object Any : HtspRecordingRuleChannel
}

/** Carries the server identifier returned by an automatic-recording-rule add reply. */
public data class AddAutorecEntryResponse(public val id: String)

/** Carries the strict success discriminator returned for an automatic-recording-rule update request. */
public data object UpdateAutorecEntryResponse

/** Carries the strict success discriminator returned for an automatic-recording-rule delete request. */
public data object DeleteAutorecEntryResponse

/** Carries the server identifier returned by a time-based recording-rule add reply. */
public data class AddTimerecEntryResponse(public val id: String)

/** Carries the strict success discriminator returned for a time-based recording-rule update request. */
public data object UpdateTimerecEntryResponse

/** Carries the strict success discriminator returned for a time-based recording-rule delete request. */
public data object DeleteTimerecEntryResponse
/** Defines an automatic recording rule with required title and optional channel, text, duration, time-window, count, schedule, retention, ownership, and configuration fields. */
public data class AddAutorecEntryRequest(
    public val title: String,
    public val channel: HtspRecordingRuleChannel? = null,
    public val minDurationSeconds: Long? = null,
    public val maxDurationSeconds: Long? = null,
    public val fullText: Long? = null,
    public val mergeText: Long? = null,
    public val duplicateDetection: Long? = null,
    public val maximumRecordingCount: Long? = null,
    public val broadcastType: Long? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val seriesLinkUri: String? = null,
    public val approximateStartMinutesSinceMidnight: Int? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val startWindowEndMinutesSinceMidnight: Int? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val configName: String? = null,
    public val daysOfWeekMask: Long? = null,
) : HtspRequest<AddAutorecEntryResponse>(
    method = "addAutorecEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(
            13,
            25.takeIf { channel is HtspRecordingRuleChannel.Any },
            18.takeIf {
                name != null || startMinutesSinceMidnight != null || startWindowEndMinutesSinceMidnight != null
            },
            19.takeIf { enabled != null || directory != null },
            20.takeIf { fullText != null || duplicateDetection != null },
            39.takeIf { broadcastType != null },
            42.takeIf { comment != null },
        ),
) {
    init {
        validateAutorecU32Fields()
    }
}

/** Identifies an automatic recording rule and carries optional channel, matching, duration, schedule, count, retention, ownership, title, and configuration changes. */
public data class UpdateAutorecEntryRequest(
    public val id: String,
    public val channel: HtspRecordingRuleChannel? = null,
    public val minDurationSeconds: Long? = null,
    public val maxDurationSeconds: Long? = null,
    public val fullText: Long? = null,
    public val mergeText: Long? = null,
    public val duplicateDetection: Long? = null,
    public val maximumRecordingCount: Long? = null,
    public val broadcastType: Long? = null,
    public val startExtraMinutes: Long? = null,
    public val stopExtraMinutes: Long? = null,
    public val seriesLinkUri: String? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val startWindowEndMinutesSinceMidnight: Int? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val title: String? = null,
    public val configName: String? = null,
    public val daysOfWeekMask: Long? = null,
) : HtspRequest<UpdateAutorecEntryResponse>(
    method = "updateAutorecEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(
            25,
            39.takeIf { broadcastType != null },
            42.takeIf { comment != null },
        ),
) {
    init {
        validateAutorecU32Fields()
    }
}

/** Selects one automatic recording rule by string [id] for deletion. */
public data class DeleteAutorecEntryRequest(public val id: String) : HtspRequest<DeleteAutorecEntryResponse>(
    method = "deleteAutorecEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 13,
)

/** Defines a time-based recording rule with title and optional channel, daily interval, enablement, days, priority, retention, ownership, and configuration fields. */
public data class AddTimerecEntryRequest(
    public val title: String,
    public val channel: HtspRecordingRuleChannel? = null,
    public val startMinutesSinceMidnight: Long? = null,
    public val stopMinutesSinceMidnight: Long? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val configName: String? = null,
    public val daysOfWeekMask: Long? = null,
) : HtspRequest<AddTimerecEntryResponse>(
    method = "addTimerecEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(
            18,
            25.takeIf { channel is HtspRecordingRuleChannel.Any },
            19.takeIf { enabled != null || directory != null },
            42.takeIf { comment != null },
        ),
) {
    init {
        validateTimerecU32Fields()
    }
}

/** Identifies a time-based recording rule and carries optional channel, daily interval, enablement, days, policy, ownership, title, and configuration changes. */
public data class UpdateTimerecEntryRequest(
    public val id: String,
    public val channel: HtspRecordingRuleChannel? = null,
    public val startMinutesSinceMidnight: Long? = null,
    public val stopMinutesSinceMidnight: Long? = null,
    public val enabled: Boolean? = null,
    public val retentionDays: Long? = null,
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val name: String? = null,
    public val comment: String? = null,
    public val directory: String? = null,
    public val title: String? = null,
    public val configName: String? = null,
    public val daysOfWeekMask: Long? = null,
) : HtspRequest<UpdateTimerecEntryResponse>(
    method = "updateTimerecEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(25, 42.takeIf { comment != null }),
) {
    init {
        validateTimerecU32Fields()
    }
}

/** Selects one time-based recording rule by string [id] for deletion. */
public data class DeleteTimerecEntryRequest(public val id: String) : HtspRequest<DeleteTimerecEntryResponse>(
    method = "deleteTimerecEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 18,
)

/** Requests an automatic recording rule with the supplied title, matching, schedule, retention, and ownership fields. */
public suspend fun HtspConnection.addAutorecEntry(
    title: String,
    channel: HtspRecordingRuleChannel? = null,
    minDurationSeconds: Long? = null,
    maxDurationSeconds: Long? = null,
    fullText: Long? = null,
    mergeText: Long? = null,
    duplicateDetection: Long? = null,
    maximumRecordingCount: Long? = null,
    broadcastType: Long? = null,
    startExtraMinutes: Long? = null,
    stopExtraMinutes: Long? = null,
    seriesLinkUri: String? = null,
    approximateStartMinutesSinceMidnight: Int? = null,
    startMinutesSinceMidnight: Int? = null,
    startWindowEndMinutesSinceMidnight: Int? = null,
    enabled: Boolean? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    priority: Long? = null,
    name: String? = null,
    comment: String? = null,
    directory: String? = null,
    configName: String? = null,
    daysOfWeekMask: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<AddAutorecEntryResponse> =
    execute(
        request = AddAutorecEntryRequest(
            title = title,
            channel = channel,
            minDurationSeconds = minDurationSeconds,
            maxDurationSeconds = maxDurationSeconds,
            fullText = fullText,
            mergeText = mergeText,
            duplicateDetection = duplicateDetection,
            maximumRecordingCount = maximumRecordingCount,
            broadcastType = broadcastType,
            startExtraMinutes = startExtraMinutes,
            stopExtraMinutes = stopExtraMinutes,
            seriesLinkUri = seriesLinkUri,
            approximateStartMinutesSinceMidnight = approximateStartMinutesSinceMidnight,
            startMinutesSinceMidnight = startMinutesSinceMidnight,
            startWindowEndMinutesSinceMidnight = startWindowEndMinutesSinceMidnight,
            enabled = enabled,
            retentionDays = retentionDays,
            removalDays = removalDays,
            priority = priority,
            name = name,
            comment = comment,
            directory = directory,
            configName = configName,
            daysOfWeekMask = daysOfWeekMask,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a change to the identified automatic recording rule using only the supplied selector and policy fields. */
public suspend fun HtspConnection.updateAutorecEntry(
    id: String,
    channel: HtspRecordingRuleChannel? = null,
    minDurationSeconds: Long? = null,
    maxDurationSeconds: Long? = null,
    fullText: Long? = null,
    mergeText: Long? = null,
    duplicateDetection: Long? = null,
    maximumRecordingCount: Long? = null,
    broadcastType: Long? = null,
    startExtraMinutes: Long? = null,
    stopExtraMinutes: Long? = null,
    seriesLinkUri: String? = null,
    startMinutesSinceMidnight: Int? = null,
    startWindowEndMinutesSinceMidnight: Int? = null,
    enabled: Boolean? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    priority: Long? = null,
    name: String? = null,
    comment: String? = null,
    directory: String? = null,
    title: String? = null,
    configName: String? = null,
    daysOfWeekMask: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<UpdateAutorecEntryResponse> =
    execute(
        request = UpdateAutorecEntryRequest(
            id = id,
            channel = channel,
            minDurationSeconds = minDurationSeconds,
            maxDurationSeconds = maxDurationSeconds,
            fullText = fullText,
            mergeText = mergeText,
            duplicateDetection = duplicateDetection,
            maximumRecordingCount = maximumRecordingCount,
            broadcastType = broadcastType,
            startExtraMinutes = startExtraMinutes,
            stopExtraMinutes = stopExtraMinutes,
            seriesLinkUri = seriesLinkUri,
            startMinutesSinceMidnight = startMinutesSinceMidnight,
            startWindowEndMinutesSinceMidnight = startWindowEndMinutesSinceMidnight,
            enabled = enabled,
            retentionDays = retentionDays,
            removalDays = removalDays,
            priority = priority,
            name = name,
            comment = comment,
            directory = directory,
            title = title,
            configName = configName,
            daysOfWeekMask = daysOfWeekMask,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests deletion of the automatic recording rule identified by [id] through the typed recorder boundary. */
public suspend fun HtspConnection.deleteAutorecEntry(
    id: String,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<DeleteAutorecEntryResponse> =
    execute(
        request = DeleteAutorecEntryRequest(
            id = id,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a time-based recording rule with the supplied channel, daily interval, day mask, and policy fields. */
public suspend fun HtspConnection.addTimerecEntry(
    title: String,
    channel: HtspRecordingRuleChannel? = null,
    startMinutesSinceMidnight: Long? = null,
    stopMinutesSinceMidnight: Long? = null,
    enabled: Boolean? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    priority: Long? = null,
    name: String? = null,
    comment: String? = null,
    directory: String? = null,
    configName: String? = null,
    daysOfWeekMask: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<AddTimerecEntryResponse> =
    execute(
        request = AddTimerecEntryRequest(
            title = title,
            channel = channel,
            startMinutesSinceMidnight = startMinutesSinceMidnight,
            stopMinutesSinceMidnight = stopMinutesSinceMidnight,
            enabled = enabled,
            retentionDays = retentionDays,
            removalDays = removalDays,
            priority = priority,
            name = name,
            comment = comment,
            directory = directory,
            configName = configName,
            daysOfWeekMask = daysOfWeekMask,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a change to the identified time-based recording rule with the supplied interval and policy fields. */
public suspend fun HtspConnection.updateTimerecEntry(
    id: String,
    channel: HtspRecordingRuleChannel? = null,
    startMinutesSinceMidnight: Long? = null,
    stopMinutesSinceMidnight: Long? = null,
    enabled: Boolean? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    priority: Long? = null,
    name: String? = null,
    comment: String? = null,
    directory: String? = null,
    title: String? = null,
    configName: String? = null,
    daysOfWeekMask: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<UpdateTimerecEntryResponse> =
    execute(
        request = UpdateTimerecEntryRequest(
            id = id,
            channel = channel,
            startMinutesSinceMidnight = startMinutesSinceMidnight,
            stopMinutesSinceMidnight = stopMinutesSinceMidnight,
            enabled = enabled,
            retentionDays = retentionDays,
            removalDays = removalDays,
            priority = priority,
            name = name,
            comment = comment,
            directory = directory,
            title = title,
            configName = configName,
            daysOfWeekMask = daysOfWeekMask,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests deletion of the time-based recording rule identified by [id] through typed connection execution. */
public suspend fun HtspConnection.deleteTimerecEntry(
    id: String,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<DeleteTimerecEntryResponse> =
    execute(
        request = DeleteTimerecEntryRequest(
            id = id,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
