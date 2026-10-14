package at.bernhardberger.tvheadend.protocolconsumer

import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventUpdateMessage
import at.bernhardberger.tvheadend.htsp.requests.HtspEpgBroadcastObject
import at.bernhardberger.tvheadend.htsp.requests.HtspEvent
import at.bernhardberger.tvheadend.htsp.requests.HtspProgrammeCredit
import at.bernhardberger.tvheadend.htsp.requests.HtspProgrammeCredits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ArtworkBinaryCompatibilityTest {
    @Test
    fun `beta 1 constructors link and default all new artwork to absent`() {
        val event = Beta1ArtworkConsumer.event()
        val broadcast = Beta1ArtworkConsumer.broadcast()
        val update = Beta1ArtworkConsumer.eventUpdate()
        val dvrAdd = Beta1ArtworkConsumer.dvrAdd()
        val dvrUpdate = Beta1ArtworkConsumer.dvrUpdate()
        listOf(
            listOf(event.imagePoster, event.imageBackdrop, event.imageStill),
            listOf(broadcast.imagePoster, broadcast.imageBackdrop, broadcast.imageStill),
            listOf(update.imagePoster, update.imageBackdrop, update.imageStill),
            listOf(dvrAdd.imagePoster, dvrAdd.imageBackdrop, dvrAdd.imageStill),
            listOf(dvrUpdate.imagePoster, dvrUpdate.imageBackdrop, dvrUpdate.imageStill),
        ).flatten().forEach { assertNull(it) }
        // These calls compile against the candidate, independently of the old
        // constructor descriptors used by Beta1ArtworkConsumer.
        assertEquals(
            HtspEvent(
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
                imagePoster = null, imageBackdrop = null, imageStill = null,
            ),
            event,
        )
        assertEquals(
            HtspEpgBroadcastObject(
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
                imagePoster = null, imageBackdrop = null, imageStill = null,
            ),
            broadcast,
        )
        assertEquals(
            HtspEventUpdateMessage(
                eventId = 7, title = "Original", categories = listOf("Drama"), nextEventId = 10,
                imagePoster = null, imageBackdrop = null, imageStill = null,
            ),
            update,
        )
        assertEquals(
            HtspDvrEntryAddMessage(
                entryId = 7, title = "Original", seasonCount = 2, episodeOnscreen = "S1E2",
                imagePoster = null, imageBackdrop = null, imageStill = null,
            ),
            dvrAdd,
        )
        assertEquals(
            HtspDvrEntryUpdateMessage(
                entryId = 7, title = "Original", seasonCount = 2, episodeOnscreen = "S1E2",
                imagePoster = null, imageBackdrop = null, imageStill = null,
            ),
            dvrUpdate,
        )
    }

    @Test
    fun `beta 1 copy bridges retain artwork and replace fields across both default masks`() {
        // Cover references, explicit empty images, and absent images independently.
        val images = listOf(
            Triple("poster", "backdrop", "still"),
            Triple("", "", ""),
            Triple(null, null, null),
            Triple("poster", "", null),
        )
        for ((poster, backdrop, still) in images) {
            val event = Beta1ArtworkConsumer.event().copy(
                credits = HtspProgrammeCredits(listOf(HtspProgrammeCredit("Performer", "actor"))),
                imagePoster = poster, imageBackdrop = backdrop, imageStill = still,
            )
            assertEquals(event.copy(title = "Updated", credits = null), Beta1ArtworkConsumer.copyEvent(event))

            val broadcast = Beta1ArtworkConsumer.broadcast().copy(
                imagePoster = poster, imageBackdrop = backdrop, imageStill = still,
            )
            assertEquals(
                broadcast.copy(titles = mapOf("eng" to "Updated"), episodeLinkUri = "updated-link"),
                Beta1ArtworkConsumer.copyBroadcast(broadcast),
            )

            val update = Beta1ArtworkConsumer.eventUpdate().copy(
                imagePoster = poster, imageBackdrop = backdrop, imageStill = still,
            )
            assertEquals(update.copy(title = "Updated", nextEventId = 11), Beta1ArtworkConsumer.copyEventUpdate(update))

            val dvrAdd = Beta1ArtworkConsumer.dvrAdd().copy(
                imagePoster = poster, imageBackdrop = backdrop, imageStill = still,
            )
            assertEquals(dvrAdd.copy(title = "Updated", episodeOnscreen = "S1E3"), Beta1ArtworkConsumer.copyDvrAdd(dvrAdd))

            val dvrUpdate = Beta1ArtworkConsumer.dvrUpdate().copy(
                imagePoster = poster, imageBackdrop = backdrop, imageStill = still,
            )
            assertEquals(
                dvrUpdate.copy(title = "Updated", episodeOnscreen = "S1E3"),
                Beta1ArtworkConsumer.copyDvrUpdate(dvrUpdate),
            )
        }
    }

    @Test
    fun `beta 1 bridges retain message validation and defensive snapshots`() {
        val originalCategories = mutableListOf("Drama")
        val update = Beta1ArtworkConsumer.eventUpdate(originalCategories)
        originalCategories.clear()
        assertEquals(listOf("Drama"), update.categories)

        val replacementCategories = mutableListOf("Sport")
        val copy = Beta1ArtworkConsumer.replaceCategories(update, replacementCategories)
        replacementCategories.clear()
        assertEquals(listOf("Sport"), copy.categories)
        assertThrows(IllegalArgumentException::class.java) {
            Beta1ArtworkConsumer.replaceEntryId(Beta1ArtworkConsumer.dvrUpdate(), -1)
        }
    }
}
