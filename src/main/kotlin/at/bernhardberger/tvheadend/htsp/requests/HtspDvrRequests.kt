package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** One DVR configuration with its stable UUID, display [name], and server [comment]. */
public data class HtspDvrConfig(
    public val dvrConfigUuid: String,
    public val name: String,
    public val comment: String,
)
/** One DVR cutpoint from [startMs] through [endMs] with its unsigned action [type]. */
public data class HtspDvrCutpoint(
    /** HTSP `start`, milliseconds (pinned htsp_server.c:2562, `dc_start_ms`). */
    public val startMs: Long,
    /** HTSP `end`, milliseconds (pinned htsp_server.c:2563, `dc_end_ms`). */
    public val endMs: Long,
    public val type: Long,
)
/** Contains the optional ordered DVR-configuration list visible to the caller. */
public data class GetDvrConfigsResponse(public val configurations: List<HtspDvrConfig>?)

/**
 * Request marker implemented by add, update, stop, cancel, and delete DVR entry requests.
 *
 * Minor releases may add subtypes; keep an `else` branch when matching.
 */
public sealed interface HtspDvrMutationRequest

/**
 * Successful DVR mutation acknowledgement. A refused mutation is a
 * [HtspResult.ServerError] instead; an undecodable reply is [HtspResult.MalformedReply].
 * An acknowledgement confirms that the server accepted the
 * request; it does not prove that the recording state has already changed.
 *
 * Minor releases may add subtypes; keep an `else` branch when matching.
 */
public sealed interface HtspDvrMutationResponse

/** DVR-add acknowledgement carrying the created entry's unsigned [entryId]. */
public data class AddDvrEntryResponse(public val entryId: Long) : HtspDvrMutationResponse {
    init {
        requireU32("entryId", entryId)
    }
}

/** Acknowledges a strict `success: 1` DVR-update reply. */
public data object UpdateDvrEntryResponse : HtspDvrMutationResponse

/** Acknowledges a strict `success: 1` DVR-stop reply. */
public data object StopDvrEntryResponse : HtspDvrMutationResponse

/** Acknowledges a strict `success: 1` DVR-cancel reply. */
public data object CancelDvrEntryResponse : HtspDvrMutationResponse

/** Acknowledges a strict `success: 1` DVR-delete reply. */
public data object DeleteDvrEntryResponse : HtspDvrMutationResponse
/** Contains the optional ordered cutpoint list for the selected DVR entry. */
public data class GetDvrCutpointsResponse(public val cutpoints: List<HtspDvrCutpoint>?)

/** Closed exactly-one selector for a channel ticket or a DVR ticket. */
public sealed interface GetTicketSelector {
    /** Selects a ticket source by complete unsigned channel identifier. */
    @JvmInline
    public value class Channel(public val channelId: Long) : GetTicketSelector {
        init {
            requireU32("channelId", channelId)
        }
    }

    /** Selects a ticket source by complete unsigned DVR identifier. */
    @JvmInline
    public value class Dvr(public val dvrId: Long) : GetTicketSelector {
        init {
            requireU32("dvrId", dvrId)
        }
    }
}

/** Credential-bearing ticket reply containing the access [path] and [ticket]; string rendering redacts both. */
public class GetTicketResponse(
    public val path: String,
    public val ticket: String,
) {
    override fun toString(): String =
        "GetTicketResponse(path=<redacted>, ticket=<redacted>)"
}
/** Requests visible DVR configurations and carries no method-specific parameters. */
public class GetDvrConfigsRequest : HtspRequest<GetDvrConfigsResponse>(
    method = "getDvrConfigs",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 16,
)

/** Closed DVR scheduling selector: an existing event or an explicit channel and time range. */
public sealed interface AddDvrEntrySelector {
    /** Selects an existing event by complete unsigned [eventId] for DVR scheduling. */
    public data class Event(public val eventId: Long) : AddDvrEntrySelector {
        init {
            requireU32("eventId", eventId)
        }
    }

    /** Selects a channel and epoch-second boundaries for DVR scheduling. */
    public data class ExplicitChannelTime(
        public val channelId: Long,
        /** HTSP `start`, epoch seconds (pinned htsp_server.c:2085–2095). */
        public val startEpochSeconds: Long,
        /** HTSP `stop`, epoch seconds (pinned htsp_server.c:2085–2095). */
        public val stopEpochSeconds: Long,
    ) : AddDvrEntrySelector {
        init {
            requireU32("channelId", channelId)
        }
    }
}

/**
 * Requests DVR scheduling from exactly one [selector]. Explicit channel/time adds require a
 * non-empty [title] (whitespace is accepted). For event-based adds the server ignores [title], [subtitle], [summary],
 * [description], and [ageRating] (pinned src/htsp_server.c:2082–2111).
 * Creation policy options are copied by src/htsp_server.c:2057–2070.
 * Older servers that predate a creation option silently ignore it and may still answer Ok.
 * These options add no minimumProtocolVersion gate because their introduction versions are unknown.
 */
public data class AddDvrEntryRequest(
    public val selector: AddDvrEntrySelector,
    public val configName: String? = null,
    public val language: String? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val ageRating: Long? = null,
    public val enabled: Boolean? = null,
    /** Padding minutes, HTSP `startExtra`. */
    public val startExtraMinutes: Long? = null,
    /** Padding minutes, HTSP `stopExtra`. */
    public val stopExtraMinutes: Long? = null,
    public val priority: Long? = null,
    /** Days or server DVR retention-policy sentinel. */
    public val retentionDays: Long? = null,
    /** Days or server DVR removal-policy sentinel. */
    public val removalDays: Long? = null,
    public val comment: String? = null,
) : HtspRequest<AddDvrEntryResponse>(
    method = "addDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(
            4,
            5.takeIf { selector is AddDvrEntrySelector.ExplicitChannelTime },
            6.takeIf { title != null },
            20.takeIf { subtitle != null },
            5.takeIf { description != null },
            36.takeIf { ageRating != null },
        ),
), HtspDvrMutationRequest {
    init {
        ageRating?.let { requireU32("ageRating", it) }
        require(selector !is AddDvrEntrySelector.ExplicitChannelTime || !title.isNullOrEmpty()) {
            "Explicit channel/time DVR scheduling requires a non-empty title"
        }
        priority?.let { requireU32("priority", it) }
        retentionDays?.let { requireU32("retentionDays", it) }
        removalDays?.let { requireU32("removalDays", it) }
    }
}

/** Identifies one DVR entry and carries optional channel, configuration, programme text, progress, enablement, timing, retention, priority, and age rating changes. */
public data class UpdateDvrEntryRequest(
    public val entryId: Long,
    public val channelId: Long? = null,
    public val configName: String? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val language: String? = null,
    public val comment: String? = null,
    public val playCount: Long? = null,
    /** HTSP `playposition`, whole playback seconds; null omits it (htsp_server.c:2235). */
    public val playPositionSeconds: Long? = null,
    /** HTSP `enabled`: nullable 0/1 enablement flag (pinned htsp_server.c:2196); null omits it. */
    public val enabled: Boolean? = null,
    /** HTSP `start`, epoch seconds; null omits it (htsp_server.c:2198). */
    public val startEpochSeconds: Long? = null,
    /** HTSP `stop`, epoch seconds; null omits it (htsp_server.c:2199). */
    public val stopEpochSeconds: Long? = null,
    /** HTSP `startExtra`, padding minutes; null omits it (htsp_server.c:998,2200). */
    public val startExtraMinutes: Long? = null,
    /** HTSP `stopExtra`, padding minutes; null omits it (htsp_server.c:999,2201). */
    public val stopExtraMinutes: Long? = null,
    /** HTSP `retention`, days or server DVR retention-policy sentinel; null omits it (htsp_server.c:1002–1005,2202). */
    public val retentionDays: Long? = null,
    /** HTSP `removal`, days or server DVR removal-policy sentinel; null omits it (htsp_server.c:1007,2203). */
    public val removalDays: Long? = null,
    public val priority: Long? = null,
    public val ageRating: Long? = null,
) : HtspRequest<UpdateDvrEntryResponse>(
    method = "updateDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(
            5,
            22.takeIf { channelId != null },
            21.takeIf { subtitle != null },
            6.takeIf { description != null },
            42.takeIf { comment != null },
            27.takeIf { playCount != null || playPositionSeconds != null },
            23.takeIf { enabled != null },
            6.takeIf { startExtraMinutes != null || stopExtraMinutes != null },
            13.takeIf { retentionDays != null || priority != null },
            36.takeIf { ageRating != null },
        ),
), HtspDvrMutationRequest {
    init {
        requireU32("id", entryId)
        channelId?.let { requireU32("channelId", it) }
        playCount?.let { requireU32("playCount", it) }
        playPositionSeconds?.let { requireU32("playPositionSeconds", it) }
        retentionDays?.let { requireU32("retentionDays", it) }
        removalDays?.let { requireU32("removalDays", it) }
        priority?.let { requireU32("priority", it) }
        ageRating?.let { requireU32("ageRating", it) }
    }
}

/** Selects one DVR entry by complete unsigned [entryId] for stopping. */
public data class StopDvrEntryRequest(public val entryId: Long) : HtspRequest<StopDvrEntryResponse>(
    method = "stopDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = null,
), HtspDvrMutationRequest {
    init {
        requireU32("id", entryId)
    }
}

/** Selects one DVR entry by complete unsigned [entryId] for cancellation. */
public data class CancelDvrEntryRequest(public val entryId: Long) : HtspRequest<CancelDvrEntryResponse>(
    method = "cancelDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 5,
), HtspDvrMutationRequest {
    init {
        requireU32("id", entryId)
    }
}

/** Selects one DVR entry by complete unsigned [entryId] for deletion. */
public data class DeleteDvrEntryRequest(public val entryId: Long) : HtspRequest<DeleteDvrEntryResponse>(
    method = "deleteDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 4,
), HtspDvrMutationRequest {
    init {
        requireU32("id", entryId)
    }
}
/** Selects one DVR entry by complete unsigned [entryId] for cutpoint retrieval. */
public data class GetDvrCutpointsRequest(public val entryId: Long) : HtspRequest<GetDvrCutpointsResponse>(
    method = "getDvrCutpoints",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 12,
) {
    init {
        requireU32("id", entryId)
    }
}

/** Carries exactly one channel-or-DVR [selector] for temporary ticket retrieval. */
public data class GetTicketRequest(
    public val selector: GetTicketSelector,
) : HtspRequest<GetTicketResponse>(
    method = "getTicket",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 5,
)

/** Fetches visible DVR configurations through the typed recorder request boundary. */
public suspend fun HtspConnection.getDvrConfigs(
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetDvrConfigsResponse> =
    execute(
        request = GetDvrConfigsRequest(),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests DVR scheduling from the explicit selector and optional metadata, then decodes the typed mutation reply. */
public suspend fun HtspConnection.addDvrEntry(
    selector: AddDvrEntrySelector,
    configName: String? = null,
    language: String? = null,
    title: String? = null,
    subtitle: String? = null,
    summary: String? = null,
    description: String? = null,
    ageRating: Long? = null,
    enabled: Boolean? = null,
    startExtraMinutes: Long? = null,
    stopExtraMinutes: Long? = null,
    priority: Long? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    comment: String? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<AddDvrEntryResponse> =
    execute(
        request = AddDvrEntryRequest(
            selector = selector,
            configName = configName,
            language = language,
            title = title,
            subtitle = subtitle,
            summary = summary,
            description = description,
            ageRating = ageRating,
            enabled = enabled,
            startExtraMinutes = startExtraMinutes,
            stopExtraMinutes = stopExtraMinutes,
            priority = priority,
            retentionDays = retentionDays,
            removalDays = removalDays,
            comment = comment,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Adapts [eventId] to [AddDvrEntrySelector.Event] before sending the same typed DVR-add request. */
public suspend fun HtspConnection.addDvrEntry(
    eventId: Long,
    configName: String? = null,
    language: String? = null,
    title: String? = null,
    subtitle: String? = null,
    summary: String? = null,
    description: String? = null,
    ageRating: Long? = null,
    enabled: Boolean? = null,
    startExtraMinutes: Long? = null,
    stopExtraMinutes: Long? = null,
    priority: Long? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    comment: String? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<AddDvrEntryResponse> =
    execute(
        request = AddDvrEntryRequest(
            selector = AddDvrEntrySelector.Event(eventId),
            configName = configName,
            language = language,
            title = title,
            subtitle = subtitle,
            summary = summary,
            description = description,
            ageRating = ageRating,
            enabled = enabled,
            startExtraMinutes = startExtraMinutes,
            stopExtraMinutes = stopExtraMinutes,
            priority = priority,
            retentionDays = retentionDays,
            removalDays = removalDays,
            comment = comment,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Adapts channel and HTSP `start`/`stop` epoch seconds to [AddDvrEntrySelector.ExplicitChannelTime]. */
public suspend fun HtspConnection.addDvrEntry(
    channelId: Long,
    startEpochSeconds: Long,
    stopEpochSeconds: Long,
    configName: String? = null,
    language: String? = null,
    title: String? = null,
    subtitle: String? = null,
    summary: String? = null,
    description: String? = null,
    ageRating: Long? = null,
    enabled: Boolean? = null,
    startExtraMinutes: Long? = null,
    stopExtraMinutes: Long? = null,
    priority: Long? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    comment: String? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<AddDvrEntryResponse> =
    execute(
        request = AddDvrEntryRequest(
            selector = AddDvrEntrySelector.ExplicitChannelTime(channelId, startEpochSeconds, stopEpochSeconds),
            configName = configName,
            language = language,
            title = title,
            subtitle = subtitle,
            summary = summary,
            description = description,
            ageRating = ageRating,
            enabled = enabled,
            startExtraMinutes = startExtraMinutes,
            stopExtraMinutes = stopExtraMinutes,
            priority = priority,
            retentionDays = retentionDays,
            removalDays = removalDays,
            comment = comment,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/**
 * Requests a DVR-entry change carrying partial metadata, timing, progress, and policy fields.
 * @param startEpochSeconds HTSP `start`, epoch seconds; null omits it.
 * @param stopEpochSeconds HTSP `stop`, epoch seconds; null omits it.
 * @param startExtraMinutes HTSP `startExtra`, padding minutes; null omits it.
 * @param stopExtraMinutes HTSP `stopExtra`, padding minutes; null omits it.
 * @param retentionDays HTSP `retention`, days or DVR retention-policy sentinel; null omits it.
 * @param removalDays HTSP `removal`, days or DVR removal-policy sentinel; null omits it.
 * @param playPositionSeconds HTSP `playposition`, whole playback seconds; null omits it.
 * See [UpdateDvrEntryRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.updateDvrEntry(
    entryId: Long,
    channelId: Long? = null,
    configName: String? = null,
    title: String? = null,
    subtitle: String? = null,
    summary: String? = null,
    description: String? = null,
    language: String? = null,
    comment: String? = null,
    playCount: Long? = null,
    playPositionSeconds: Long? = null,
    enabled: Boolean? = null,
    startEpochSeconds: Long? = null,
    stopEpochSeconds: Long? = null,
    startExtraMinutes: Long? = null,
    stopExtraMinutes: Long? = null,
    retentionDays: Long? = null,
    removalDays: Long? = null,
    priority: Long? = null,
    ageRating: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<UpdateDvrEntryResponse> =
    execute(
        request = UpdateDvrEntryRequest(
            entryId = entryId,
            channelId = channelId,
            configName = configName,
            title = title,
            subtitle = subtitle,
            summary = summary,
            description = description,
            language = language,
            comment = comment,
            playCount = playCount,
            playPositionSeconds = playPositionSeconds,
            enabled = enabled,
            startEpochSeconds = startEpochSeconds,
            stopEpochSeconds = stopEpochSeconds,
            startExtraMinutes = startExtraMinutes,
            stopExtraMinutes = stopExtraMinutes,
            retentionDays = retentionDays,
            removalDays = removalDays,
            priority = priority,
            ageRating = ageRating,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests that the selected DVR entry stop and decodes the server's typed mutation reply. */
public suspend fun HtspConnection.stopDvrEntry(
    entryId: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<StopDvrEntryResponse> =
    execute(
        request = StopDvrEntryRequest(
            entryId = entryId,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests cancellation of the selected DVR entry and decodes the server's typed mutation reply. */
public suspend fun HtspConnection.cancelDvrEntry(
    entryId: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<CancelDvrEntryResponse> =
    execute(
        request = CancelDvrEntryRequest(
            entryId = entryId,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests deletion of the selected DVR entry and returns the decoded typed mutation reply. */
public suspend fun HtspConnection.deleteDvrEntry(
    entryId: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<DeleteDvrEntryResponse> =
    execute(
        request = DeleteDvrEntryRequest(
            entryId = entryId,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Fetches the ordered cutpoint coordinates and action codes for one DVR entry through typed execution. */
public suspend fun HtspConnection.getDvrCutpoints(
    entryId: Long,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetDvrCutpointsResponse> =
    execute(
        request = GetDvrCutpointsRequest(
            entryId = entryId,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a temporary access path and ticket for exactly one channel or DVR selector through typed execution. */
public suspend fun HtspConnection.getTicket(
    selector: GetTicketSelector,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetTicketResponse> =
    execute(
        request = GetTicketRequest(
            selector = selector,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
