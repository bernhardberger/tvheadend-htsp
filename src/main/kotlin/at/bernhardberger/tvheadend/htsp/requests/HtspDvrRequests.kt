package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** One DVR configuration with its stable UUID, display [name], and server [comment]. */
public data class HtspDvrConfig(
    public val dvrConfigUuid: String,
    public val name: String,
    public val comment: String,
)
/** One DVR cutpoint from [start] through [end] with its unsigned action [type]. */
public data class HtspDvrCutpoint(
    public val start: Long,
    public val end: Long,
    public val type: Long,
)
/** Contains the optional ordered DVR-configuration list visible to the caller. */
public data class GetDvrConfigsResponse(public val configurations: List<HtspDvrConfig>?)

/** Closed request marker implemented by add, update, stop, cancel, and delete DVR entry requests. */
public sealed interface HtspDvrMutationRequest

/** Shared DVR mutation fields: optional success code, untrusted server error text, and an optional entry identifier where applicable. */
public sealed interface HtspDvrMutationResponse {
    public val success: Long?
    public val error: String?
    public val entryId: Long?
        get() = null
}

/** DVR-add reply with optional success code, returned [entryId], and untrusted server [error]. */
public data class AddDvrEntryResponse(
    override public val success: Long?,
    override public val entryId: Long?,
    override public val error: String?,
) : HtspDvrMutationResponse

/** DVR-update result carrying the optional success code and untrusted server error text. */
public data class UpdateDvrEntryResponse(
    override public val success: Long?,
    override public val error: String?,
) : HtspDvrMutationResponse

/** DVR-stop result carrying the optional success code and untrusted server error text. */
public data class StopDvrEntryResponse(
    override public val success: Long?,
    override public val error: String?,
) : HtspDvrMutationResponse

/** DVR-cancel result carrying the optional success code and untrusted server error text. */
public data class CancelDvrEntryResponse(
    override public val success: Long?,
    override public val error: String?,
) : HtspDvrMutationResponse

/** DVR-delete result carrying the optional success code and untrusted server error text. */
public data class DeleteDvrEntryResponse(
    override public val success: Long?,
    override public val error: String?,
) : HtspDvrMutationResponse
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

    /** Selects a complete unsigned [channelId] and signed [start] and [stop] coordinates for DVR scheduling. */
    public data class ExplicitChannelTime(
        public val channelId: Long,
        public val start: Long,
        public val stop: Long,
    ) : AddDvrEntrySelector {
        init {
            requireU32("channelId", channelId)
        }
    }
}

/** Requests DVR scheduling from exactly one [selector] with optional configuration, language, programme text, and age rating. */
public data class AddDvrEntryRequest(
    public val selector: AddDvrEntrySelector,
    public val configName: String? = null,
    public val language: String? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val ageRating: Long? = null,
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
    public val playPosition: Long? = null,
    public val enabled: Long? = null,
    public val start: Long? = null,
    public val stop: Long? = null,
    public val startExtra: Long? = null,
    public val stopExtra: Long? = null,
    public val retention: Long? = null,
    public val removal: Long? = null,
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
            27.takeIf { playCount != null || playPosition != null },
            23.takeIf { enabled != null },
            6.takeIf { startExtra != null || stopExtra != null },
            13.takeIf { retention != null || priority != null },
            36.takeIf { ageRating != null },
        ),
), HtspDvrMutationRequest {
    init {
        requireU32("id", entryId)
        channelId?.let { requireU32("channelId", it) }
        playCount?.let { requireU32("playCount", it) }
        playPosition?.let { requireU32("playPosition", it) }
        retention?.let { requireU32("retention", it) }
        removal?.let { requireU32("removal", it) }
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
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Adapts channel, start, and stop to [AddDvrEntrySelector.ExplicitChannelTime] before sending a typed DVR-add request. */
public suspend fun HtspConnection.addDvrEntry(
    channelId: Long,
    start: Long,
    stop: Long,
    configName: String? = null,
    language: String? = null,
    title: String? = null,
    subtitle: String? = null,
    summary: String? = null,
    description: String? = null,
    ageRating: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<AddDvrEntryResponse> =
    execute(
        request = AddDvrEntryRequest(
            selector = AddDvrEntrySelector.ExplicitChannelTime(channelId, start, stop),
            configName = configName,
            language = language,
            title = title,
            subtitle = subtitle,
            summary = summary,
            description = description,
            ageRating = ageRating,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Requests a DVR-entry change carrying the supplied partial metadata, timing, progress, and policy fields. */
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
    playPosition: Long? = null,
    enabled: Long? = null,
    start: Long? = null,
    stop: Long? = null,
    startExtra: Long? = null,
    stopExtra: Long? = null,
    retention: Long? = null,
    removal: Long? = null,
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
            playPosition = playPosition,
            enabled = enabled,
            start = start,
            stop = stop,
            startExtra = startExtra,
            stopExtra = stopExtra,
            retention = retention,
            removal = removal,
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
