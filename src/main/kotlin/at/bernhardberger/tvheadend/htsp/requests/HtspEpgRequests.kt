package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.wire.*

/** A bounded EPG event with channel and time coordinates, localized text, categories, ratings, episode data, imagery, and DVR references. */
public data class HtspEvent(
    public val eventId: Long,
    public val channelId: Long?,
    public val start: Long,
    public val stop: Long,
    public val title: String?,
    public val subtitle: String?,
    public val summary: String?,
    public val description: String?,
    public val categories: List<String>?,
    public val keywords: List<String>?,
    public val seriesLinkUri: String?,
    public val episodeUri: String?,
    public val contentType: Long?,
    public val ageRating: Long?,
    public val ratingLabel: String?,
    public val ratingIcon: String?,
    public val ratingAuthority: String?,
    public val ratingCountry: String?,
    public val starRating: Long?,
    public val copyrightYear: Long?,
    public val firstAired: Long?,
    public val isNew: Long?,
    public val seasonNumber: Long?,
    public val seasonCount: Long?,
    public val episodeNumber: Long?,
    public val episodeCount: Long?,
    public val partNumber: Long?,
    public val partCount: Long?,
    public val episodeOnscreen: String?,
    public val image: String?,
    public val dvrId: Long?,
    public val nextEventId: Long?,
)
/** Contains the complete EPG event selected by `getEvent`. */
public data class GetEventResponse(public val event: HtspEvent)

/** Contains the ordered finite event list selected by `getEvents`. */
public data class GetEventsResponse(public val events: List<HtspEvent>)

/** Closed `epgQuery` reply family containing either event identifiers or complete event values. */
public sealed interface EpgQueryResponse {
    /** Contains only the event identifiers returned by a summary EPG query. */
    public data class EventIds(public val eventIds: List<Long>) : EpgQueryResponse
    /** Contains complete typed events returned by a detailed EPG query. */
    public data class Events(public val events: List<HtspEvent>) : EpgQueryResponse
}

/** Finite object selector encoded by `getEpgObject`; [BROADCAST] selects a broadcast record. */
public enum class HtspEpgObjectType {
    BROADCAST,
}

/** Optional episode numbering with episode, season, and part values, totals, and display text. */
public data class HtspEpgEpisodeNumber(
    public val episodeNumber: Long?,
    public val episodeCount: Long?,
    public val seasonNumber: Long?,
    public val seasonCount: Long?,
    public val partNumber: Long?,
    public val partCount: Long?,
    public val text: String?,
)

/** Bounded detailed broadcast record with timing, channel and event identity, flags, ratings, localized text, numbering, genres, and links; opaque credentials are omitted. */
public data class HtspEpgBroadcastObject(
    public val id: Long,
    public val updatedUnixSeconds: Long,
    public val startUnixSeconds: Long,
    public val stopUnixSeconds: Long,
    public val grabber: String?,
    public val channelUuid: String?,
    public val eventId: Long?,
    public val externalEventId: String?,
    public val widescreen: Boolean,
    public val highDefinition: Boolean,
    public val blackAndWhite: Boolean,
    public val deafSigned: Boolean,
    public val subtitled: Boolean,
    public val audioDescribed: Boolean,
    public val isNew: Boolean,
    public val isRepeat: Boolean,
    public val lines: Long?,
    public val aspectRatio: Long?,
    public val starRating: Long?,
    public val ageRating: Long?,
    public val ratingLabel: String?,
    public val image: String?,
    public val titles: Map<String, String>?,
    public val subtitles: Map<String, String>?,
    public val summaries: Map<String, String>?,
    public val descriptions: Map<String, String>?,
    public val episodeNumber: HtspEpgEpisodeNumber?,
    public val genres: List<Long>?,
    public val copyrightYear: Long?,
    public val firstAiredUnixSeconds: Long?,
    public val categories: List<String>?,
    public val keywords: List<String>?,
    public val seriesLinkUri: String?,
    public val episodeLinkUri: String?,
)

/** Contains the detailed broadcast selected by `getEpgObject`. */
public data class GetEpgObjectResponse(public val broadcast: HtspEpgBroadcastObject)
/** Selects one event by complete unsigned [eventId] and optional response [language]. */
public data class GetEventRequest(
    public val eventId: Long,
    public val language: String? = null,
) : HtspRequest<GetEventResponse>(
    method = "getEvent",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 6.takeIf { language != null },
) {
    init {
        requireU32("eventId", eventId)
    }
}

/** Selects an event set by optional channel or event ID, language, following count, and maximum Unix time. */
public data class GetEventsRequest(
    public val channelId: Long? = null,
    public val eventId: Long? = null,
    public val language: String? = null,
    public val numFollowing: Long? = null,
    public val maxTime: Long? = null,
) : HtspRequest<GetEventsResponse>(
    method = "getEvents",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = if (
            channelId != null || eventId != null || language != null || numFollowing != null || maxTime != null
        ) 6 else 4,
) {
    init {
        channelId?.let { requireU32("channelId", it) }
        eventId?.let { requireU32("eventId", it) }
        numFollowing?.let { requireU32("numFollowing", it) }
    }
}

/** Carries required search [query] plus optional channel, tag, content, language, text-mode, detail, and duration filters. */
public data class EpgQueryRequest(
    public val query: String,
    public val channelId: Long? = null,
    public val tagId: Long? = null,
    public val contentType: Long? = null,
    public val language: String? = null,
    public val fullText: Boolean? = null,
    public val mergeText: Boolean? = null,
    public val full: Long? = null,
    public val minDurationSeconds: Long? = null,
    public val maxDurationSeconds: Long? = null,
) : HtspRequest<EpgQueryResponse>(
    method = "epgQuery",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = maxVersion(
            4,
            6.takeIf { language != null },
            13.takeIf { minDurationSeconds != null || maxDurationSeconds != null },
        ),
) {
    init {
        channelId?.let { requireU32("channelId", it) }
        tagId?.let { requireU32("tagId", it) }
        contentType?.let { requireU32("contentType", it) }
        full?.let { requireU32("full", it) }
        minDurationSeconds?.let { requireU32("minduration", it) }
        maxDurationSeconds?.let { requireU32("maxduration", it) }
    }
}

/** Selects a detailed EPG object by unsigned [id] and optional finite [objectType]. */
public data class GetEpgObjectRequest(
    public val id: Long,
    public val objectType: HtspEpgObjectType? = HtspEpgObjectType.BROADCAST,
) : HtspRequest<GetEpgObjectResponse>(
    method = "getEpgObject",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = null,
) {
    init {
        requireU32("id", id)
    }
}

/** Fetches one EPG event by identifier, optionally localized to [language], through typed execution. */
public suspend fun HtspConnection.getEvent(
    eventId: Long,
    language: String? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetEventResponse> =
    execute(
        request = GetEventRequest(
            eventId = eventId,
            language = language,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Fetches an event window selected by channel, event, language, following count, or maximum time through typed execution. */
public suspend fun HtspConnection.getEvents(
    channelId: Long? = null,
    eventId: Long? = null,
    language: String? = null,
    numFollowing: Long? = null,
    maxTime: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetEventsResponse> =
    execute(
        request = GetEventsRequest(
            channelId = channelId,
            eventId = eventId,
            language = language,
            numFollowing = numFollowing,
            maxTime = maxTime,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Searches EPG text with the supplied channel, tag, content, language, detail, and duration filters through typed execution. */
public suspend fun HtspConnection.epgQuery(
    query: String,
    channelId: Long? = null,
    tagId: Long? = null,
    contentType: Long? = null,
    language: String? = null,
    fullText: Boolean? = null,
    mergeText: Boolean? = null,
    full: Long? = null,
    minDurationSeconds: Long? = null,
    maxDurationSeconds: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<EpgQueryResponse> =
    execute(
        request = EpgQueryRequest(
            query = query,
            channelId = channelId,
            tagId = tagId,
            contentType = contentType,
            language = language,
            fullText = fullText,
            mergeText = mergeText,
            full = full,
            minDurationSeconds = minDurationSeconds,
            maxDurationSeconds = maxDurationSeconds,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )

/** Fetches the selected detailed EPG object and decodes its finite broadcast shape through typed execution. */
public suspend fun HtspConnection.getEpgObject(
    id: Long,
    objectType: HtspEpgObjectType? = HtspEpgObjectType.BROADCAST,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetEpgObjectResponse> =
    execute(
        request = GetEpgObjectRequest(
            id = id,
            objectType = objectType,
        ),
        timeoutMs = timeoutMs,
        expectedGeneration = expectedGeneration,
    )
