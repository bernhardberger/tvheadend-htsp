package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** Closed channel selector for automatic and time-based recording rule requests. */
public sealed interface HtspRecordingRuleChannel {
    /**
 * Selects one recording-rule channel by its complete unsigned [channelId].
 *
 * @property channelId channel identifier (`channelId`), 0 to 4294967295
 */
    @JvmInline
    public value class Id(public val channelId: Long) : HtspRecordingRuleChannel {
        init {
            requireU32("channelId", channelId)
        }
    }

    /** Selects any channel: adds encode signed `-1`; updates omit `channelId` to clear the restriction. */
    public data object Any : HtspRecordingRuleChannel
}

/**
 * Carries the server identifier returned by an automatic-recording-rule add reply.
 *
 * @property id UUID of the new rule (`id`)
 */
public data class AddAutorecEntryResponse(public val id: String)

/** Carries the strict success discriminator returned for an automatic-recording-rule update request. */
public data object UpdateAutorecEntryResponse

/** Carries the strict success discriminator returned for an automatic-recording-rule delete request. */
public data object DeleteAutorecEntryResponse

/**
 * Carries the server identifier returned by a time-based recording-rule add reply.
 *
 * @property id UUID of the new rule (`id`)
 */
public data class AddTimerecEntryResponse(public val id: String)

/** Carries the strict success discriminator returned for a time-based recording-rule update request. */
public data object UpdateTimerecEntryResponse

/** Carries the strict success discriminator returned for a time-based recording-rule delete request. */
public data object DeleteTimerecEntryResponse
/**
 * Defines an automatic recording rule with required title and optional channel, text, duration, time-window, count, schedule, retention, ownership, and configuration fields.
 *
 * @property title title the rule matches (`title`); always sent
 * @property channel channel restriction (`channelId`); `null` omits it, so the rule matches any channel; [HtspRecordingRuleChannel.Any] sends `-1`
 * @property minDurationSeconds shortest matching programme in seconds (`minduration`), 0 to 4294967295; `null` omits it and the server uses 0, meaning any
 * @property maxDurationSeconds longest matching programme in seconds (`maxduration`), 0 to 4294967295; `null` omits it and the server uses 0, meaning any
 * @property duplicateDetection duplicate-detection policy code (`dupDetect`), 0 to 4294967295; `null` omits it and the server applies its record-all default
 * @property maximumRecordingCount recording limit (`maxCount`), 0 to 4294967295; 0 means unlimited; `null` omits it and the server uses 0
 * @property broadcastType broadcast policy code (`broadcastType`, v39 or later), 0 to 4294967295; 0 means all; `null` omits it and the server uses 0
 * @property startExtraMinutes padding before the programme start in minutes (`startExtra`); the server treats a negative value as 0, and 0 means the DVR configuration's padding; `null` omits it and the server uses 0
 * @property stopExtraMinutes padding after the programme end in minutes (`stopExtra`); the server treats a negative value as 0, and 0 means the DVR configuration's padding; `null` omits it and the server uses 0
 * @property seriesLinkUri series link the rule follows (`serieslinkUri`); `null` omits it and the server stores an empty link
 * @property approximateStartMinutesSinceMidnight approximate start in minutes after midnight (`approxTime`); the server uses it only when [startMinutesSinceMidnight] and [startWindowEndMinutesSinceMidnight] are not both set, deriving a 30-minute window centred on it; `null` omits it
 * @property startMinutesSinceMidnight start of the allowed start-time window in minutes after midnight (`start`); the server reads a negative value as unset, meaning any time, and ignores it unless [startWindowEndMinutesSinceMidnight] is also set; `null` omits it
 * @property startWindowEndMinutesSinceMidnight end of the allowed start-time window in minutes after midnight (`startWindow`); the window may wrap past midnight; the server reads a negative value as unset and ignores it unless [startMinutesSinceMidnight] is also set; `null` omits it
 * @property enabled whether the rule is active (`enabled`); `null` omits it and the server enables the rule
 * @property retentionDays days to keep recordings, or a server retention-policy sentinel (`retention`), 0 to 4294967295; `null` omits it and the server applies its default policy
 * @property removalDays days until recordings are removed, or a server removal-policy sentinel (`removal`), 0 to 4294967295; `null` omits it and the server applies its default policy
 * @property priority priority code (`priority`), 0 to 4294967295; `null` omits it and the server applies its default priority
 * @property name display name of the rule (`name`); `null` omits it and the server stores an empty name
 * @property comment free-text comment (`comment`, v42 or later); `null` omits it and the server stores an empty comment
 * @property directory recording directory (`directory`); `null` omits it and the server stores an empty directory
 * @property configName name or UUID of the DVR configuration the rule records with (`configName`); `null` omits it and the server chooses the configuration
 * @property daysOfWeekMask weekday bitmask (`daysOfWeek`), 0 to 4294967295; the server reads bit `n` (0 to 6) as weekday `n + 1`; `null` omits it, which means all days
 */
public data class AddAutorecEntryRequest(
    public val title: String,
    public val channel: HtspRecordingRuleChannel? = null,
    public val minDurationSeconds: Long? = null,
    public val maxDurationSeconds: Long? = null,
    /** HTSP `fulltext`: 0/1 search flag; null omits it (pinned htsp_server.c:608-609). */
    public val fullText: Boolean? = null,
    /** HTSP `mergetext`: 0/1 merge flag; null omits it (pinned htsp_server.c:610-611). */
    public val mergeText: Boolean? = null,
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

    override fun toString(): String = "AddAutorecEntryRequest(<redacted>)"
}

/**
 * Identifies an automatic recording rule and carries optional matching and policy changes.
 * Updates always set the channel restriction: supply the current [channel] to retain it, or
 * [HtspRecordingRuleChannel.Any] to clear it by omitting wire `channelId` (src/htsp_server.c:700–703).
 * No wire shape keeps the stored channel: despite the "keep old one" comment at
 * src/htsp_server.c:2371, an update without `channelId` clears it. Callers that don't know the
 * current channel cannot update the rule without changing it.
 *
 * @property id UUID of the rule to update (`id`)
 * @property minDurationSeconds shortest matching programme in seconds (`minduration`), 0 to 4294967295; 0 means any; `null` omits it and keeps the stored value
 * @property maxDurationSeconds longest matching programme in seconds (`maxduration`), 0 to 4294967295; 0 means any; `null` omits it and keeps the stored value
 * @property duplicateDetection duplicate-detection policy code (`dupDetect`), 0 to 4294967295; `null` omits it and keeps the stored value
 * @property maximumRecordingCount recording limit (`maxCount`), 0 to 4294967295; 0 means unlimited; `null` omits it and keeps the stored value
 * @property broadcastType broadcast policy code (`broadcastType`, v39 or later), 0 to 4294967295; 0 means all; `null` omits it and keeps the stored value
 * @property startExtraMinutes padding before the programme start in minutes (`startExtra`); the server treats a negative value as 0, and 0 means the DVR configuration's padding; `null` omits it and keeps the stored value
 * @property stopExtraMinutes padding after the programme end in minutes (`stopExtra`); the server treats a negative value as 0, and 0 means the DVR configuration's padding; `null` omits it and keeps the stored value
 * @property seriesLinkUri series link the rule follows (`serieslinkUri`); `null` omits it and keeps the stored value
 * @property startMinutesSinceMidnight start of the allowed start-time window in minutes after midnight (`start`); the server reads a negative value as unset, meaning any time; `null` omits it and keeps the stored value
 * @property startWindowEndMinutesSinceMidnight end of the allowed start-time window in minutes after midnight (`startWindow`); the server reads a negative value as unset; `null` omits it and keeps the stored value
 * @property enabled whether the rule is active (`enabled`); `null` omits it and keeps the stored value
 * @property retentionDays days to keep recordings, or a server retention-policy sentinel (`retention`), 0 to 4294967295; `null` omits it and keeps the stored value
 * @property removalDays days until recordings are removed, or a server removal-policy sentinel (`removal`), 0 to 4294967295; `null` omits it and keeps the stored value
 * @property priority priority code (`priority`), 0 to 4294967295; `null` omits it and keeps the stored value
 * @property name display name of the rule (`name`); `null` omits it and keeps the stored value
 * @property comment free-text comment (`comment`, v42 or later); `null` omits it and keeps the stored value
 * @property directory recording directory (`directory`); `null` omits it and keeps the stored value
 * @property title title the rule matches (`title`); `null` omits it and keeps the stored value
 * @property configName name or UUID of the DVR configuration the rule records with (`configName`); `null` omits it and keeps the stored value
 * @property daysOfWeekMask weekday bitmask (`daysOfWeek`), 0 to 4294967295; the server reads bit `n` (0 to 6) as weekday `n + 1`; `null` omits it and keeps the stored value
 */
public data class UpdateAutorecEntryRequest(
    public val id: String,
    public val channel: HtspRecordingRuleChannel,
    public val minDurationSeconds: Long? = null,
    public val maxDurationSeconds: Long? = null,
    /** HTSP `fulltext`: 0/1 search flag; null omits it (pinned htsp_server.c:608-609). */
    public val fullText: Boolean? = null,
    /** HTSP `mergetext`: 0/1 merge flag; null omits it (pinned htsp_server.c:610-611). */
    public val mergeText: Boolean? = null,
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

    override fun toString(): String = "UpdateAutorecEntryRequest(<redacted>)"
}

/**
 * Selects one automatic recording rule by string [id] for deletion.
 *
 * @property id UUID of the rule to delete (`id`)
 */
public data class DeleteAutorecEntryRequest(public val id: String) : HtspRequest<DeleteAutorecEntryResponse>(
    method = "deleteAutorecEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 13,
)

/**
 * Defines a time-based recording rule with title and optional channel, daily interval, enablement, days, priority, retention, ownership, and configuration fields.
 *
 * @property title title of the rule (`title`); always sent
 * @property channel channel restriction (`channelId`); `null` omits it, so the rule matches any channel; [HtspRecordingRuleChannel.Any] sends `-1`
 * @property startMinutesSinceMidnight start of the daily recording window in minutes after midnight (`start`), 0 to 1439; `null` omits it and the server uses 0
 * @property stopMinutesSinceMidnight end of the daily recording window in minutes after midnight (`stop`), 0 to 1439; `null` omits it and the server uses 0
 * @property enabled whether the rule is active (`enabled`); `null` omits it and the server enables the rule
 * @property retentionDays days to keep recordings, or a server retention-policy sentinel (`retention`), 0 to 4294967295; `null` omits it and the server applies its default policy
 * @property removalDays days until recordings are removed, or a server removal-policy sentinel (`removal`), 0 to 4294967295; `null` omits it and the server applies its default policy
 * @property priority priority code (`priority`), 0 to 4294967295; `null` omits it and the server applies its default priority
 * @property name display name of the rule (`name`); `null` omits it and the server stores an empty name
 * @property comment free-text comment (`comment`, v42 or later); `null` omits it and the server stores an empty comment
 * @property directory recording directory (`directory`); `null` omits it and the server stores an empty directory
 * @property configName name or UUID of the DVR configuration the rule records with (`configName`); `null` omits it and the server chooses the configuration
 * @property daysOfWeekMask weekday bitmask (`daysOfWeek`), 0 to 4294967295; the server reads bit `n` (0 to 6) as weekday `n + 1`; `null` omits it, which means all days
 */
public data class AddTimerecEntryRequest(
    public val title: String,
    public val channel: HtspRecordingRuleChannel? = null,
    public val startMinutesSinceMidnight: Int? = null,
    public val stopMinutesSinceMidnight: Int? = null,
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

    override fun toString(): String = "AddTimerecEntryRequest(<redacted>)"
}

/**
 * Identifies a time-based recording rule and carries optional interval and policy changes.
 * Updates always set the channel restriction: supply the current [channel] to retain it, or
 * [HtspRecordingRuleChannel.Any] to clear it by omitting wire `channelId` (src/htsp_server.c:700–703).
 * No wire shape keeps the stored channel: despite the "keep old one" comment at
 * src/htsp_server.c:2482, an update without `channelId` clears it. Callers that don't know the
 * current channel cannot update the rule without changing it.
 *
 * @property id UUID of the rule to update (`id`)
 * @property startMinutesSinceMidnight start of the daily recording window in minutes after midnight (`start`), 0 to 1439; `null` omits it and keeps the stored value
 * @property stopMinutesSinceMidnight end of the daily recording window in minutes after midnight (`stop`), 0 to 1439; `null` omits it and keeps the stored value
 * @property enabled whether the rule is active (`enabled`); `null` omits it and keeps the stored value
 * @property retentionDays days to keep recordings, or a server retention-policy sentinel (`retention`), 0 to 4294967295; `null` omits it and keeps the stored value
 * @property removalDays days until recordings are removed, or a server removal-policy sentinel (`removal`), 0 to 4294967295; `null` omits it and keeps the stored value
 * @property priority priority code (`priority`), 0 to 4294967295; `null` omits it and keeps the stored value
 * @property name display name of the rule (`name`); `null` omits it and keeps the stored value
 * @property comment free-text comment (`comment`, v42 or later); `null` omits it and keeps the stored value
 * @property directory recording directory (`directory`); `null` omits it and keeps the stored value
 * @property title title of the rule (`title`); `null` omits it and keeps the stored value
 * @property configName name or UUID of the DVR configuration the rule records with (`configName`); `null` omits it and keeps the stored value
 * @property daysOfWeekMask weekday bitmask (`daysOfWeek`), 0 to 4294967295; the server reads bit `n` (0 to 6) as weekday `n + 1`; `null` omits it and keeps the stored value
 */
public data class UpdateTimerecEntryRequest(
    public val id: String,
    public val channel: HtspRecordingRuleChannel,
    public val startMinutesSinceMidnight: Int? = null,
    public val stopMinutesSinceMidnight: Int? = null,
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

    override fun toString(): String = "UpdateTimerecEntryRequest(<redacted>)"
}

/**
 * Selects one time-based recording rule by string [id] for deletion.
 *
 * @property id UUID of the rule to delete (`id`)
 */
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
    fullText: Boolean? = null,
    mergeText: Boolean? = null,
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

/** Updates the automatic recording rule, always setting [channel]; use [HtspRecordingRuleChannel.Any] to clear it. */
public suspend fun HtspConnection.updateAutorecEntry(
    id: String,
    channel: HtspRecordingRuleChannel,
    minDurationSeconds: Long? = null,
    maxDurationSeconds: Long? = null,
    fullText: Boolean? = null,
    mergeText: Boolean? = null,
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
    startMinutesSinceMidnight: Int? = null,
    stopMinutesSinceMidnight: Int? = null,
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

/** Updates the time-based recording rule, always setting [channel]; use [HtspRecordingRuleChannel.Any] to clear it. */
public suspend fun HtspConnection.updateTimerecEntry(
    id: String,
    channel: HtspRecordingRuleChannel,
    startMinutesSinceMidnight: Int? = null,
    stopMinutesSinceMidnight: Int? = null,
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
