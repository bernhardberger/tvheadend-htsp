package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.model.*

import at.bernhardberger.tvheadend.htsp.connection.*
import at.bernhardberger.tvheadend.htsp.model.*
import at.bernhardberger.tvheadend.htsp.wire.*

/**
 * Contains the complete EPG event selected by `getEvent`.
 *
 * @property event event for the requested identifier
 */
public data class GetEventResponse(public val event: HtspEvent)

/**
 * Contains the ordered finite event list selected by `getEvents`.
 *
 * @property events events in server order (`events`)
 */
public data class GetEventsResponse(public val events: List<HtspEvent>)

/** Closed `epgQuery` reply family containing either event identifiers or complete event values. */
public sealed interface EpgQueryResponse {
    /**
     * Contains only the event identifiers returned by a summary EPG query.
     *
     * @property eventIds identifiers of the matching events in server order (`eventIds`)
     */
    public data class EventIds(public val eventIds: List<Long>) : EpgQueryResponse
    /**
     * Contains complete typed events returned by a detailed EPG query.
     *
     * @property events matching events in server order (`events`)
     */
    public data class Events(public val events: List<HtspEvent>) : EpgQueryResponse
}

/**
 * Object selector encoded by `getEpgObject`; [BROADCAST] selects a broadcast record.
 *
 * Minor releases may add entries; keep an `else` branch when matching.
 */
public enum class HtspEpgObjectType {
    /** Broadcast record, sent as `type` 1. */
    BROADCAST,
}

/**
 * Optional episode numbering with episode, season, and part values, totals, and display text.
 *
 * Every property is `null` when the server omits it; the server omits zero values, and the decoder rejects a value with no property set.
 *
 * @property episodeNumber episode ordinal (`enum`)
 * @property episodeCount number of episodes (`ecnt`)
 * @property seasonNumber season ordinal (`snum`)
 * @property seasonCount number of seasons (`scnt`)
 * @property partNumber part ordinal (`pnum`)
 * @property partCount number of parts (`pcnt`)
 * @property text display text for the numbering (`text`)
 */
public data class HtspEpgEpisodeNumber(
    public val episodeNumber: Long?,
    public val episodeCount: Long?,
    public val seasonNumber: Long?,
    public val seasonCount: Long?,
    public val partNumber: Long?,
    public val partCount: Long?,
    public val text: String?,
)

/**
 * Bounded detailed broadcast record with timing, identity, flags, ratings, localized text, numbering, genres, links, and programme credits.
 *
 * A `null` property is one the server omitted, which it does for unset and zero values. A `false` flag is one the server omitted.
 *
 * @property id broadcast identifier (`id`), the value [GetEventRequest.eventId] selects
 * @property grabber identifier of the EPG grabber module that supplied the record (`gr`)
 * @property channelUuid UUID of the broadcast's channel (`ch`)
 * @property eventId DVB event identifier (`eid`)
 * @property externalEventId XMLTV event identifier (`xeid`)
 * @property widescreen whether the broadcast is flagged widescreen (`is_wd`)
 * @property highDefinition whether the broadcast is flagged high definition (`is_hd`)
 * @property blackAndWhite whether the broadcast is flagged black and white (`is_bw`)
 * @property deafSigned whether the broadcast is flagged as signed for deaf viewers (`is_de`)
 * @property subtitled whether the broadcast is flagged as subtitled (`is_st`)
 * @property audioDescribed whether the broadcast is flagged as audio described (`is_ad`)
 * @property isNew whether the broadcast is flagged as new (`is_n`)
 * @property isRepeat whether the broadcast is flagged as a repeat (`is_r`)
 * @property lines video resolution in lines (`lines`), such as 576 or 1080
 * @property aspectRatio aspect ratio multiplied by 100 (`aspect`), such as 178 for 16:9
 * @property starRating star rating (`star`)
 * @property ageRating age rating (`age`)
 * @property image image URI (`img`)
 * @property titles titles keyed by language (`tit`)
 * @property subtitles subtitles keyed by language (`sti`)
 * @property summaries summaries keyed by language (`sum`)
 * @property descriptions descriptions keyed by language (`des`)
 * @property episodeNumber episode numbering (`epn`)
 * @property genres genre content codes in server order (`genre`)
 * @property copyrightYear copyright year (`cyear`)
 * @property categories category strings, sorted and unique (`cat`)
 * @property keywords keyword strings, sorted and unique (`key`)
 * @property seriesLinkUri series-link URI (`slink`)
 * @property episodeLinkUri episode-link URI (`elink`)
 */
public data class HtspEpgBroadcastObject(
    public val id: Long,
    /** HTSP `up`, last-update epoch seconds. */
    public val updatedEpochSeconds: Long,
    /** HTSP `start`, broadcast-start epoch seconds. */
    public val startEpochSeconds: Long,
    /** HTSP `stop`, broadcast-stop epoch seconds. */
    public val stopEpochSeconds: Long,
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
    /** Rating-label UUID, HTSP `ratlab` (src/epg.c:1740–1742). */
    public val ratingLabel: String?,
    public val image: String?,
    public val titles: Map<String, String>?,
    public val subtitles: Map<String, String>?,
    public val summaries: Map<String, String>?,
    public val descriptions: Map<String, String>?,
    public val episodeNumber: HtspEpgEpisodeNumber?,
    public val genres: List<Long>?,
    public val copyrightYear: Long?,
    /** HTSP `fair`, first-air epoch seconds; null means absent. */
    public val firstAiredEpochSeconds: Long?,
    public val categories: List<String>?,
    public val keywords: List<String>?,
    public val seriesLinkUri: String?,
    public val episodeLinkUri: String?,
    /** Programme credits, HTSP `cred` (src/epg.c:1766–1768). */
    public val credits: HtspProgrammeCredits? = null,
)

/**
 * Contains the detailed broadcast selected by `getEpgObject`.
 *
 * @property broadcast broadcast record for the requested identifier
 */
public data class GetEpgObjectResponse(public val broadcast: HtspEpgBroadcastObject)
/**
 * Selects one event by complete unsigned [eventId] and optional response [language].
 *
 * @property eventId event to fetch (`eventId`), 0 to 4294967295
 * @property language language of the localized text (`language`); `null` omits it and the server uses the connection's language
 */
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

/**
 * Selects an event set by optional channel or event ID, language, following count, and maximum Unix time.
 *
 * With neither [channelId] nor [eventId] the server lists the schedule of every channel the account can access.
 *
 * @property channelId channel to list from, starting at its current event or, without one, its next event (`channelId`), 0 to 4294967295
 * @property eventId event to start the list at (`eventId`), 0 to 4294967295; takes precedence over [channelId] as the starting point
 * @property language language of the localized text (`language`); `null` omits it and the server uses the connection's language
 * @property numFollowing number of events to return per channel, counting the first (`numFollowing`), 0 to 4294967295; `null` or 0 sets no limit
 */
public data class GetEventsRequest(
    public val channelId: Long? = null,
    public val eventId: Long? = null,
    public val language: String? = null,
    public val numFollowing: Long? = null,
    /** HTSP `maxTime`, latest broadcast-start epoch seconds; zero is unlimited, null omits it (htsp_server.c:1813–1828). */
    public val maxTimeEpochSeconds: Long? = null,
) : HtspRequest<GetEventsResponse>(
    method = "getEvents",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = if (
            channelId != null || eventId != null || language != null || numFollowing != null || maxTimeEpochSeconds != null
        ) 6 else 4,
) {
    init {
        channelId?.let { requireU32("channelId", it) }
        eventId?.let { requireU32("eventId", it) }
        numFollowing?.let { requireU32("numFollowing", it) }
    }
}

/**
 * Carries required search [query] plus optional channel, tag, content, language, text-mode, detail, and duration filters.
 *
 * A `null` filter is omitted from the wire and the server then applies no such filter.
 *
 * @property query case-insensitive regular expression to match (`query`); it matches titles unless [fullText] or [mergeText] widens the search
 * @property channelId channel to search (`channelId`), 0 to 4294967295; `null` searches all channels the account can access
 * @property tagId channel tag to search (`tagId`), 0 to 4294967295
 * @property contentType genre content code to match (`contentType`), 0 to 4294967295
 * @property language language of the searched and returned text (`language`); `null` uses the connection's language
 * @property fullText whether [query] also matches subtitle, summary, description, credits and keywords (`fulltext`); `null` is treated as `false`
 * @property mergeText whether [query] matches one string merged from the event's text fields (`mergetext`); takes priority over [fullText], and `null` is treated as `false`
 * @property minDurationSeconds shortest event duration to include, in seconds (`minduration`), 0 to 4294967295
 * @property maxDurationSeconds longest event duration to include, in seconds (`maxduration`), 0 to 4294967295
 */
public data class EpgQueryRequest(
    public val query: String,
    public val channelId: Long? = null,
    public val tagId: Long? = null,
    public val contentType: Long? = null,
    public val language: String? = null,
    public val fullText: Boolean? = null,
    public val mergeText: Boolean? = null,
    /** HTSP `full`: 0/1 full-event response flag; null omits it (pinned htsp_server.c:1915). */
    public val full: Boolean? = null,
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
        minDurationSeconds?.let { requireU32("minduration", it) }
        maxDurationSeconds?.let { requireU32("maxduration", it) }
    }
}

/**
 * Selects a detailed EPG object by unsigned [id] and [objectType].
 *
 * @property id identifier of the object (`id`), 0 to 4294967295
 * @property objectType type of the object (`type`); `null` omits it and the pinned server then answers with an error
 */
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

/**
 * Fetches an event window through typed execution.
 * @param maxTimeEpochSeconds HTSP `maxTime`, epoch seconds; zero is unlimited, null omits it.
 * See [GetEventsRequest] for pinned source evidence.
 */
public suspend fun HtspConnection.getEvents(
    channelId: Long? = null,
    eventId: Long? = null,
    language: String? = null,
    numFollowing: Long? = null,
    maxTimeEpochSeconds: Long? = null,
    timeoutMs: Long = 5_000L,
    expectedGeneration: HtspConnectionGeneration? = null,
): HtspResult<GetEventsResponse> =
    execute(
        request = GetEventsRequest(
            channelId = channelId,
            eventId = eventId,
            language = language,
            numFollowing = numFollowing,
            maxTimeEpochSeconds = maxTimeEpochSeconds,
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
    full: Boolean? = null,
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
