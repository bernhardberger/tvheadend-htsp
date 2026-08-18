package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.requests.HtspEvent
import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Carries the bounded [event] fields reported by an event-add message plus accepted genre, episode, and series-link identifiers. */
public class HtspEventAddMessage(
    event: HtspEvent,
    public val genre: String? = null,
    public val episodeId: Long? = null,
    public val seriesLinkId: Long? = null,
) : HtspServerMessage {
    public val event: HtspEvent = event.copy(
            categories = event.categories?.immutableSnapshot(),
            keywords = event.keywords?.immutableSnapshot(),
        )

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
    public val categories: List<String>? = categories?.immutableSnapshot()
    public val keywords: List<String>? = keywords?.immutableSnapshot()

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
