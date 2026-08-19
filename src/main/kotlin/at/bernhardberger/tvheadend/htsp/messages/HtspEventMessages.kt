package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.requests.HtspEvent
import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Carries the bounded [event] fields reported by an event-add message plus accepted genre, episode, and series-link identifiers. */
@ConsistentCopyVisibility
public data class HtspEventAddMessage private constructor(
    public val event: HtspEvent,
    public val genre: String? = null,
    public val episodeId: Long? = null,
    public val seriesLinkId: Long? = null,
    private val immutableSnapshot: Unit,
) : HtspServerMessage {
    public constructor(
        event: HtspEvent,
        genre: String? = null,
        episodeId: Long? = null,
        seriesLinkId: Long? = null,
    ) : this(
        event = event.copy(
            categories = event.categories?.immutableSnapshot(),
            keywords = event.keywords?.immutableSnapshot(),
        ),
        genre = genre,
        episodeId = episodeId,
        seriesLinkId = seriesLinkId,
        immutableSnapshot = Unit,
    )

    /** Returns a validated copy with immutable snapshots of nested event collections. */
    public fun copy(
        event: HtspEvent = this.event,
        genre: String? = this.genre,
        episodeId: Long? = this.episodeId,
        seriesLinkId: Long? = this.seriesLinkId,
    ): HtspEventAddMessage = HtspEventAddMessage(
        event = event,
        genre = genre,
        episodeId = episodeId,
        seriesLinkId = seriesLinkId,
    )

    override fun toString(): String = "HtspEventAddMessage(<redacted>)"

    init {
        requireU32("eventId", this.event.eventId)
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
                ).forEach { requireU32("event field", it) }
    }
}

/** Carries an update identified by [eventId]; nullable timing, text, rating, episode, image, and DVR fields were absent when null. */
@ConsistentCopyVisibility
public data class HtspEventUpdateMessage private constructor(
    public val eventId: Long,
    public val channelId: Long? = null,
    public val start: Long? = null,
    public val stop: Long? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val genre: String? = null,
    public val categories: List<String>? = null,
    public val keywords: List<String>? = null,
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
    private val immutableSnapshot: Unit,
) : HtspServerMessage {
    public constructor(
        eventId: Long,
        channelId: Long? = null,
        start: Long? = null,
        stop: Long? = null,
        title: String? = null,
        subtitle: String? = null,
        summary: String? = null,
        description: String? = null,
        genre: String? = null,
        categories: List<String>? = null,
        keywords: List<String>? = null,
        seriesLinkUri: String? = null,
        episodeUri: String? = null,
        contentType: Long? = null,
        ageRating: Long? = null,
        ratingLabel: String? = null,
        ratingIcon: String? = null,
        ratingAuthority: String? = null,
        ratingCountry: String? = null,
        starRating: Long? = null,
        copyrightYear: Long? = null,
        firstAired: Long? = null,
        isNew: Long? = null,
        seasonNumber: Long? = null,
        seasonCount: Long? = null,
        episodeNumber: Long? = null,
        episodeCount: Long? = null,
        partNumber: Long? = null,
        partCount: Long? = null,
        episodeOnscreen: String? = null,
        episodeId: Long? = null,
        seriesLinkId: Long? = null,
        image: String? = null,
        dvrId: Long? = null,
        nextEventId: Long? = null,
    ) : this(
        eventId = eventId,
        channelId = channelId,
        start = start,
        stop = stop,
        title = title,
        subtitle = subtitle,
        summary = summary,
        description = description,
        genre = genre,
        categories = categories?.immutableSnapshot(),
        keywords = keywords?.immutableSnapshot(),
        seriesLinkUri = seriesLinkUri,
        episodeUri = episodeUri,
        contentType = contentType,
        ageRating = ageRating,
        ratingLabel = ratingLabel,
        ratingIcon = ratingIcon,
        ratingAuthority = ratingAuthority,
        ratingCountry = ratingCountry,
        starRating = starRating,
        copyrightYear = copyrightYear,
        firstAired = firstAired,
        isNew = isNew,
        seasonNumber = seasonNumber,
        seasonCount = seasonCount,
        episodeNumber = episodeNumber,
        episodeCount = episodeCount,
        partNumber = partNumber,
        partCount = partCount,
        episodeOnscreen = episodeOnscreen,
        episodeId = episodeId,
        seriesLinkId = seriesLinkId,
        image = image,
        dvrId = dvrId,
        nextEventId = nextEventId,
        immutableSnapshot = Unit,
    )

    /** Returns a validated copy with immutable snapshots of replacement collections. */
    public fun copy(
        eventId: Long = this.eventId,
        channelId: Long? = this.channelId,
        start: Long? = this.start,
        stop: Long? = this.stop,
        title: String? = this.title,
        subtitle: String? = this.subtitle,
        summary: String? = this.summary,
        description: String? = this.description,
        genre: String? = this.genre,
        categories: List<String>? = this.categories,
        keywords: List<String>? = this.keywords,
        seriesLinkUri: String? = this.seriesLinkUri,
        episodeUri: String? = this.episodeUri,
        contentType: Long? = this.contentType,
        ageRating: Long? = this.ageRating,
        ratingLabel: String? = this.ratingLabel,
        ratingIcon: String? = this.ratingIcon,
        ratingAuthority: String? = this.ratingAuthority,
        ratingCountry: String? = this.ratingCountry,
        starRating: Long? = this.starRating,
        copyrightYear: Long? = this.copyrightYear,
        firstAired: Long? = this.firstAired,
        isNew: Long? = this.isNew,
        seasonNumber: Long? = this.seasonNumber,
        seasonCount: Long? = this.seasonCount,
        episodeNumber: Long? = this.episodeNumber,
        episodeCount: Long? = this.episodeCount,
        partNumber: Long? = this.partNumber,
        partCount: Long? = this.partCount,
        episodeOnscreen: String? = this.episodeOnscreen,
        episodeId: Long? = this.episodeId,
        seriesLinkId: Long? = this.seriesLinkId,
        image: String? = this.image,
        dvrId: Long? = this.dvrId,
        nextEventId: Long? = this.nextEventId,
    ): HtspEventUpdateMessage = HtspEventUpdateMessage(
        eventId = eventId,
        channelId = channelId,
        start = start,
        stop = stop,
        title = title,
        subtitle = subtitle,
        summary = summary,
        description = description,
        genre = genre,
        categories = categories,
        keywords = keywords,
        seriesLinkUri = seriesLinkUri,
        episodeUri = episodeUri,
        contentType = contentType,
        ageRating = ageRating,
        ratingLabel = ratingLabel,
        ratingIcon = ratingIcon,
        ratingAuthority = ratingAuthority,
        ratingCountry = ratingCountry,
        starRating = starRating,
        copyrightYear = copyrightYear,
        firstAired = firstAired,
        isNew = isNew,
        seasonNumber = seasonNumber,
        seasonCount = seasonCount,
        episodeNumber = episodeNumber,
        episodeCount = episodeCount,
        partNumber = partNumber,
        partCount = partCount,
        episodeOnscreen = episodeOnscreen,
        episodeId = episodeId,
        seriesLinkId = seriesLinkId,
        image = image,
        dvrId = dvrId,
        nextEventId = nextEventId,
    )

    override fun toString(): String = "HtspEventUpdateMessage(<redacted>)"

    init {
        requireU32("eventId", eventId)
        channelId?.let { requireU32("channelId", it) }
        contentType?.let { requireU32("contentType", it) }
        ageRating?.let { requireU32("ageRating", it) }
        starRating?.let { requireU32("starRating", it) }
        copyrightYear?.let { requireU32("copyrightYear", it) }
        isNew?.let { requireU32("isNew", it) }
        seasonNumber?.let { requireU32("seasonNumber", it) }
        seasonCount?.let { requireU32("seasonCount", it) }
        episodeNumber?.let { requireU32("episodeNumber", it) }
        episodeCount?.let { requireU32("episodeCount", it) }
        partNumber?.let { requireU32("partNumber", it) }
        partCount?.let { requireU32("partCount", it) }
        episodeId?.let { requireU32("episodeId", it) }
        seriesLinkId?.let { requireU32("seriesLinkId", it) }
        dvrId?.let { requireU32("dvrId", it) }
        nextEventId?.let { requireU32("nextEventId", it) }
    }
}

/** Carries the complete unsigned [eventId] reported by an EPG-event delete message. */
public data class HtspEventDeleteMessage(
    public val eventId: Long,
) : HtspServerMessage {
    init {
        requireU32("eventId", eventId)
    }
}

/** Fieldless server marker reporting completion of the initial asynchronous metadata snapshot. */
public data object HtspInitialSyncCompletedMessage : HtspServerMessage
