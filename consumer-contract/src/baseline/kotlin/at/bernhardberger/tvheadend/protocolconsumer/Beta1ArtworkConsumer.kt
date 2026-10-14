package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventUpdateMessage
import at.bernhardberger.tvheadend.htsp.requests.HtspEpgBroadcastObject
import at.bernhardberger.tvheadend.htsp.requests.HtspEvent

/** Compiled only against beta.1: these call sites must never see the artwork parameters. */
object Beta1ArtworkConsumer {
    fun event(): HtspEvent = HtspEvent(
        eventId = 7,
        channelId = 8,
        startEpochSeconds = 100,
        stopEpochSeconds = 200,
        title = "Original",
        subtitle = null,
        summary = null,
        description = null,
        categories = listOf("Drama"),
        keywords = null,
        seriesLinkUri = null,
        episodeUri = null,
        contentType = null,
        ageRating = null,
        ratingLabel = null,
        ratingIcon = null,
        ratingAuthority = null,
        ratingCountry = null,
        starRating = null,
        copyrightYear = null,
        firstAiredEpochSeconds = null,
        isNew = true,
        seasonNumber = null,
        seasonCount = null,
        episodeNumber = null,
        episodeCount = null,
        partNumber = null,
        partCount = null,
        episodeOnscreen = "S1E2",
        image = "legacy-image",
        dvrId = 9,
        nextEventId = 10,
    )

    fun broadcast(): HtspEpgBroadcastObject = HtspEpgBroadcastObject(
        id = 7,
        updatedEpochSeconds = 90,
        startEpochSeconds = 100,
        stopEpochSeconds = 200,
        grabber = null,
        channelUuid = null,
        eventId = 7,
        externalEventId = null,
        widescreen = true,
        highDefinition = true,
        blackAndWhite = false,
        deafSigned = false,
        subtitled = true,
        audioDescribed = false,
        isNew = true,
        isRepeat = false,
        lines = 1080,
        aspectRatio = null,
        starRating = null,
        ageRating = null,
        ratingLabel = null,
        image = "legacy-image",
        titles = mapOf("eng" to "Original"),
        subtitles = null,
        summaries = null,
        descriptions = null,
        episodeNumber = null,
        genres = listOf(1),
        copyrightYear = null,
        firstAiredEpochSeconds = null,
        categories = listOf("Drama"),
        keywords = null,
        seriesLinkUri = "series-link",
        episodeLinkUri = "episode-link",
    )

    fun eventUpdate(categories: List<String> = listOf("Drama")): HtspEventUpdateMessage =
        HtspEventUpdateMessage(eventId = 7, title = "Original", categories = categories, nextEventId = 10)

    fun dvrAdd(): HtspDvrEntryAddMessage =
        HtspDvrEntryAddMessage(entryId = 7, title = "Original", seasonCount = 2, episodeOnscreen = "S1E2")

    fun dvrUpdate(): HtspDvrEntryUpdateMessage =
        HtspDvrEntryUpdateMessage(entryId = 7, title = "Original", seasonCount = 2, episodeOnscreen = "S1E2")

    // Defaults in both Int masks, plus explicit replacements before and after parameter 32.
    // Each old default bridge also invokes the old full constructor/copy descriptor.
    fun copyEvent(value: HtspEvent): HtspEvent = value.copy(title = "Updated", credits = null)

    fun copyBroadcast(value: HtspEpgBroadcastObject): HtspEpgBroadcastObject =
        value.copy(titles = mapOf("eng" to "Updated"), episodeLinkUri = "updated-link")

    fun copyEventUpdate(value: HtspEventUpdateMessage): HtspEventUpdateMessage =
        value.copy(title = "Updated", nextEventId = 11)

    fun copyDvrAdd(value: HtspDvrEntryAddMessage): HtspDvrEntryAddMessage =
        value.copy(title = "Updated", episodeOnscreen = "S1E3")

    fun copyDvrUpdate(value: HtspDvrEntryUpdateMessage): HtspDvrEntryUpdateMessage =
        value.copy(title = "Updated", episodeOnscreen = "S1E3")

    fun replaceCategories(value: HtspEventUpdateMessage, categories: List<String>): HtspEventUpdateMessage =
        value.copy(categories = categories)

    fun replaceEntryId(value: HtspDvrEntryUpdateMessage, entryId: Long): HtspDvrEntryUpdateMessage =
        value.copy(entryId = entryId)
}
