package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspApiUuid
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspJsonApi
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrRecordingFile
import at.bernhardberger.tvheadend.htsp.messages.HtspEventAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStartMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStream
import at.bernhardberger.tvheadend.htsp.messages.HtspTagAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTagUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.requests.FileOpenRequest
import at.bernhardberger.tvheadend.htsp.requests.GetTicketResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspChannelService
import at.bernhardberger.tvheadend.htsp.requests.HtspEvent
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtspMessageDataClassTest {
    @Test
    fun mergeableMessagesAreDataClassesAndChannelMetadataMergesWithCopy() {
        val messageTypes = listOf(
            HtspChannelAddMessage::class.java,
            HtspChannelUpdateMessage::class.java,
            HtspTagAddMessage::class.java,
            HtspTagUpdateMessage::class.java,
            HtspEventAddMessage::class.java,
            HtspEventUpdateMessage::class.java,
            HtspDvrEntryAddMessage::class.java,
            HtspDvrEntryUpdateMessage::class.java,
            HtspSubscriptionStartMessage::class.java,
        )
        assertTrue(messageTypes.all { type ->
            type.declaredMethods.any { it.name == "copy" } &&
                type.declaredMethods.any { it.name == "component1" }
        })

        val existing = HtspChannelAddMessage(
            channelId = 7L,
            channelName = "Old",
            channelIcon = "kept.png",
        )
        val update = HtspChannelUpdateMessage(
            channelId = 7L,
            channelName = "New",
        )
        val merged = existing.copy(
            channelName = update.channelName ?: existing.channelName,
            channelIcon = update.channelIcon ?: existing.channelIcon,
        )

        assertEquals("New", merged.channelName)
        assertEquals("kept.png", merged.channelIcon)
        assertEquals(merged, HtspChannelAddMessage(7L, channelName = "New", channelIcon = "kept.png"))
        assertEquals(merged.hashCode(), merged.copy().hashCode())
        assertNotEquals(existing, merged)
        assertEquals(update, update.copy())
        val tagAdd = HtspTagAddMessage(8L, tagName = "tag")
        val tagUpdate = HtspTagUpdateMessage(9L, tagName = "tag")
        assertEquals(tagAdd, tagAdd.copy())
        assertEquals(tagUpdate, tagUpdate.copy())
    }

    @Test
    fun constructionAndCopyRetainImmutableSnapshotsAndUnsignedValidation() {
        val services = mutableListOf(channelService("service"))
        val tagIds = mutableListOf(11L)
        val channel = HtspChannelAddMessage(7L, services = services, tagIds = tagIds)
        services += channelService("later")
        tagIds += 12L
        assertEquals(listOf(channelService("service")), channel.services)
        assertEquals(listOf(11L), channel.tagIds)
        assertUnmodifiable(channel.services!!)
        assertUnmodifiable(channel.tagIds!!)

        val replacementTags = mutableListOf(21L)
        val copiedChannel = channel.copy(tagIds = replacementTags)
        replacementTags += 22L
        assertEquals(listOf(21L), copiedChannel.tagIds)
        assertUnmodifiable(copiedChannel.tagIds!!)
        assertThrows(IllegalArgumentException::class.java) { channel.copy(channelId = -1L) }

        val categories = mutableListOf("Drama")
        val event = HtspEventAddMessage(minimalEvent(categories = categories))
        categories += "Later"
        assertEquals(listOf("Drama"), event.event.categories)
        assertUnmodifiable(event.event.categories!!)
        assertEquals(event, event.copy())

        val replacementKeywords = mutableListOf("new")
        val copiedEvent = event.copy(event = event.event.copy(keywords = replacementKeywords))
        replacementKeywords += "later"
        assertEquals(listOf("new"), copiedEvent.event.keywords)
        assertUnmodifiable(copiedEvent.event.keywords!!)
        assertThrows(IllegalArgumentException::class.java) {
            event.copy(event = event.event.copy(eventId = -1L))
        }

        val files = mutableListOf(HtspDvrRecordingFile(1L, "/recording.ts", null, null, null))
        val dvr = HtspDvrEntryUpdateMessage(3L, files = files)
        files.clear()
        assertEquals(listOf(HtspDvrRecordingFile(1L, "/recording.ts", null, null, null)), dvr.files)
        assertUnmodifiable(dvr.files!!)
        assertThrows(IllegalArgumentException::class.java) { dvr.copy(entryId = -1L) }

        val replacementFiles = mutableListOf(HtspDvrRecordingFile(2L, "/replacement.ts", null, null, null))
        val copiedDvr = dvr.copy(files = replacementFiles)
        replacementFiles.clear()
        assertEquals(1, copiedDvr.files?.size)
        assertUnmodifiable(copiedDvr.files!!)

        val invalidCopies = listOf<() -> Unit>(
            { channel.copy(channelNumber = -1L) },
            { channel.copy(tagIds = listOf(-1L)) },
            { HtspChannelUpdateMessage(1L).copy(currentEventId = -1L) },
            { HtspTagAddMessage(1L).copy(tagIndex = -1L) },
            { HtspTagUpdateMessage(1L).copy(channelIds = listOf(-1L)) },
            { HtspEventUpdateMessage(1L).copy(contentType = -1L) },
            { HtspDvrEntryAddMessage(1L).copy(enabled = -1L) },
        )
        invalidCopies.forEach { invalidCopy ->
            assertThrows(IllegalArgumentException::class.java) { invalidCopy() }
        }
    }

    @Test
    fun largeDataClassComponentsAndCopiesPreserveEveryField() {
        val event = completeEventUpdate()
        assertEquals(event, event.copy())
        assertPublicComponents(event, eventUpdateValues(event))

        val dvrAdd = completeDvrAdd()
        assertEquals(dvrAdd, dvrAdd.copy())
        assertPublicComponents(dvrAdd, dvrAddValues(dvrAdd))

        val dvrUpdate = completeDvrUpdate()
        assertEquals(dvrUpdate, dvrUpdate.copy())
        assertPublicComponents(dvrUpdate, dvrUpdateValues(dvrUpdate))
    }

    @Test
    fun subscriptionStartUsesBinaryContentSemanticsThroughNestedCopies() {
        val first = HtspSubscriptionStartMessage(
            subscriptionId = 1L,
            streams = mutableListOf(subscriptionStream(byteArrayOf(1, 2))),
            codecMetadata = HtspBinary(byteArrayOf(3, 4)),
        )
        val second = HtspSubscriptionStartMessage(
            subscriptionId = 1L,
            streams = listOf(subscriptionStream(byteArrayOf(1, 2))),
            codecMetadata = HtspBinary(byteArrayOf(3, 4)),
        )

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first, first.copy())
        assertUnmodifiable(first.streams!!)

        val replacementStreams = mutableListOf(subscriptionStream(byteArrayOf(5, 6)))
        val copied = first.copy(streams = replacementStreams)
        replacementStreams.clear()
        assertEquals(1, copied.streams?.size)
        assertUnmodifiable(copied.streams!!)
        assertThrows(IllegalArgumentException::class.java) { first.copy(subscriptionId = -1L) }
    }

    @OptIn(HtspJsonApi::class)
    @Test
    fun protectedCredentialAndPathTypesKeepRedactedRendering() {
        val endpoint = HtspEndpoint("host", 9982, "user", "secret-password")
        assertFalse(endpoint.toString().contains("secret-password"))
        assertTrue(endpoint.toString().contains("<redacted>"))

        val ticket = GetTicketResponse("/private/path", "secret-ticket")
        assertFalse(ticket.toString().contains("/private/path"))
        assertFalse(ticket.toString().contains("secret-ticket"))
        assertTrue(ticket.toString().contains("redacted", ignoreCase = true))

        val fileOpen = FileOpenRequest("/private/file")
        assertEquals("FileOpenRequest(file=<redacted>)", fileOpen.toString())

        val uuid = HtspApiUuid(ByteArray(16) { it.toByte() })
        assertEquals("HtspApiUuid(<redacted>)", uuid.toString())

        val dvr = HtspDvrEntryAddMessage(
            entryId = 123L,
            path = "/private/recording.ts",
            error = "raw-server-error",
            subscriptionError = "raw-subscription-error",
        )
        assertEquals("HtspDvrEntryAddMessage(<redacted>)", dvr.toString())
        val autorec = HtspAutorecEntryAddMessage(
            id = "private-rule-id",
            enabled = true,
            maxDurationSeconds = 0L,
            minDurationSeconds = 0L,
            retentionDays = 0L,
            removalDays = 0L,
            daysOfWeekMask = 0L,
            approximateStartMinutesSinceMidnight = -1,
            startMinutesSinceMidnight = -1,
            startWindowEndMinutesSinceMidnight = -1,
            priority = 0L,
            startExtraMinutes = 0L,
            stopExtraMinutes = 0L,
            duplicateDetection = 0L,
            maximumRecordingCount = 0L,
            broadcastType = 0L,
            comment = "private-comment",
            name = "private-name",
            directory = "/private/directory",
            owner = "private-owner",
            creator = "private-creator",
        )
        assertEquals("HtspAutorecEntryAddMessage(<redacted>)", autorec.toString())
        assertEquals(
            "HtspAutorecEntryUpdateMessage(<redacted>)",
            HtspAutorecEntryUpdateMessage(
                id = "private-rule-id",
                directory = "/private/directory",
                owner = "private-owner",
                creator = "private-creator",
            ).toString(),
        )
        assertEquals(
            "HtspTimerecEntryAddMessage(<redacted>)",
            HtspTimerecEntryAddMessage(
                id = "private-rule-id",
                enabled = true,
                name = "private-name",
                title = "private-title",
                channelId = 1,
                startMinutesSinceMidnight = 0,
                stopMinutesSinceMidnight = 1,
                directory = "/private/directory",
                owner = "private-owner",
                creator = "private-creator",
            ).toString(),
        )
        assertEquals(
            "HtspTimerecEntryUpdateMessage(<redacted>)",
            HtspTimerecEntryUpdateMessage(
                id = "private-rule-id",
                directory = "/private/directory",
                owner = "private-owner",
                creator = "private-creator",
            ).toString(),
        )
        val start = HtspSubscriptionStartMessage(
            subscriptionId = 456L,
            status = "raw-status",
            subscriptionError = "raw-subscription-error",
        )
        assertEquals("HtspSubscriptionStartMessage(<redacted>)", start.toString())
    }

    private fun channelService(name: String): HtspChannelService = HtspChannelService(
        name = name,
        type = "SDTV",
        content = 1L,
        conditionalAccessId = null,
        conditionalAccessName = null,
        providerName = null,
    )

    private fun minimalEvent(categories: List<String>? = null): HtspEvent = HtspEvent(
        eventId = 1L,
        channelId = null,
        start = 0L,
        stop = 1L,
        title = null,
        subtitle = null,
        summary = null,
        description = null,
        categories = categories,
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
        firstAired = null,
        isNew = null,
        seasonNumber = null,
        seasonCount = null,
        episodeNumber = null,
        episodeCount = null,
        partNumber = null,
        partCount = null,
        episodeOnscreen = null,
        image = null,
        dvrId = null,
        nextEventId = null,
    )

    private fun subscriptionStream(codecMetadata: ByteArray): HtspSubscriptionStream =
        HtspSubscriptionStream(
            streamIndex = 0L,
            streamType = "AAC",
            language = null,
            compositionId = null,
            ancillaryId = null,
            width = null,
            height = null,
            frameDuration = null,
            aspectNumerator = null,
            aspectDenominator = null,
            audioType = null,
            audioVersion = null,
            channelCount = null,
            sampleRate = null,
            rdsUecp = null,
            codecMetadata = HtspBinary(codecMetadata),
        )

    private fun completeEventUpdate(): HtspEventUpdateMessage = HtspEventUpdateMessage(
        eventId = 1L,
        channelId = 2L,
        start = -3L,
        stop = -4L,
        title = "5",
        subtitle = "6",
        summary = "7",
        description = "8",
        genre = "9",
        categories = listOf("10"),
        keywords = listOf("11"),
        seriesLinkUri = "12",
        episodeUri = "13",
        contentType = 14L,
        ageRating = 15L,
        ratingLabel = "16",
        ratingIcon = "17",
        ratingAuthority = "18",
        ratingCountry = "19",
        starRating = 20L,
        copyrightYear = 21L,
        firstAired = -22L,
        isNew = 23L,
        seasonNumber = 24L,
        seasonCount = 25L,
        episodeNumber = 26L,
        episodeCount = 27L,
        partNumber = 28L,
        partCount = 29L,
        episodeOnscreen = "30",
        episodeId = 31L,
        seriesLinkId = 32L,
        image = "33",
        dvrId = 34L,
        nextEventId = 35L,
    )

    private fun eventUpdateValues(message: HtspEventUpdateMessage): List<Any?> = listOf(
        message.eventId,
        message.channelId,
        message.start,
        message.stop,
        message.title,
        message.subtitle,
        message.summary,
        message.description,
        message.genre,
        message.categories,
        message.keywords,
        message.seriesLinkUri,
        message.episodeUri,
        message.contentType,
        message.ageRating,
        message.ratingLabel,
        message.ratingIcon,
        message.ratingAuthority,
        message.ratingCountry,
        message.starRating,
        message.copyrightYear,
        message.firstAired,
        message.isNew,
        message.seasonNumber,
        message.seasonCount,
        message.episodeNumber,
        message.episodeCount,
        message.partNumber,
        message.partCount,
        message.episodeOnscreen,
        message.episodeId,
        message.seriesLinkId,
        message.image,
        message.dvrId,
        message.nextEventId,
    )

    private fun completeDvrAdd(): HtspDvrEntryAddMessage = HtspDvrEntryAddMessage(
        entryId = 1L,
        entryUuid = "2",
        enabled = 3L,
        channelId = 4L,
        channelName = "5",
        eventId = 6L,
        autorecEntryUuid = "7",
        timerecEntryUuid = "8",
        start = -9L,
        stop = -10L,
        startExtraMinutes = -11L,
        stopExtraMinutes = -12L,
        retentionDays = 13L,
        removalDays = 14L,
        priority = 15L,
        contentType = 16L,
        ageRating = 17L,
        ratingLabel = "18",
        ratingIcon = "19",
        ratingAuthority = "20",
        ratingCountry = "21",
        playCount = 22L,
        playPositionSeconds = 23L,
        seasonNumber = 24L,
        episodeNumber = 25L,
        episodeCount = 26L,
        partNumber = 27L,
        partCount = 28L,
        title = "29",
        description = "30",
        summary = "31",
        subtitle = "32",
        owner = "33",
        creator = "34",
        comment = "35",
        image = "36",
        fanartImage = "37",
        copyrightYear = 38L,
        files = listOf(HtspDvrRecordingFile(39L, "39", -39L, -40L, -41L)),
        path = "40",
        dvrConfigUuid = "41",
        duplicate = 42L,
        state = "43",
        error = "44",
        subscriptionError = "45",
        streamErrors = 46L,
        dataErrors = 47L,
        dataSizeBytes = -48L,
    )

    private fun completeDvrUpdate(): HtspDvrEntryUpdateMessage = HtspDvrEntryUpdateMessage(
        entryId = 101L,
        entryUuid = "102",
        enabled = 103L,
        channelId = 104L,
        channelName = "105",
        eventId = 106L,
        autorecEntryUuid = "107",
        timerecEntryUuid = "108",
        start = -109L,
        stop = -110L,
        startExtraMinutes = -111L,
        stopExtraMinutes = -112L,
        retentionDays = 113L,
        removalDays = 114L,
        priority = 115L,
        contentType = 116L,
        ageRating = 117L,
        ratingLabel = "118",
        ratingIcon = "119",
        ratingAuthority = "120",
        ratingCountry = "121",
        playCount = 122L,
        playPositionSeconds = 123L,
        seasonNumber = 124L,
        episodeNumber = 125L,
        episodeCount = 126L,
        partNumber = 127L,
        partCount = 128L,
        title = "129",
        description = "130",
        summary = "131",
        subtitle = "132",
        owner = "133",
        creator = "134",
        comment = "135",
        image = "136",
        fanartImage = "137",
        copyrightYear = 138L,
        files = listOf(HtspDvrRecordingFile(139L, "139", -139L, -140L, -141L)),
        path = "140",
        dvrConfigUuid = "141",
        duplicate = 142L,
        state = "143",
        error = "144",
        subscriptionError = "145",
        streamErrors = 146L,
        dataErrors = 147L,
        dataSizeBytes = -148L,
    )

    private fun dvrAddValues(message: HtspDvrEntryAddMessage): List<Any?> = listOf(
        message.entryId, message.entryUuid, message.enabled, message.channelId,
        message.channelName, message.eventId, message.autorecEntryUuid, message.timerecEntryUuid,
        message.start, message.stop, message.startExtraMinutes, message.stopExtraMinutes,
        message.retentionDays, message.removalDays, message.priority, message.contentType,
        message.ageRating, message.ratingLabel, message.ratingIcon, message.ratingAuthority,
        message.ratingCountry, message.playCount, message.playPositionSeconds, message.seasonNumber,
        message.episodeNumber, message.episodeCount, message.partNumber, message.partCount,
        message.title, message.description, message.summary, message.subtitle, message.owner,
        message.creator, message.comment, message.image, message.fanartImage, message.copyrightYear,
        message.files, message.path, message.dvrConfigUuid, message.duplicate, message.state,
        message.error, message.subscriptionError, message.streamErrors, message.dataErrors,
        message.dataSizeBytes,
    )

    private fun dvrUpdateValues(message: HtspDvrEntryUpdateMessage): List<Any?> = listOf(
        message.entryId, message.entryUuid, message.enabled, message.channelId,
        message.channelName, message.eventId, message.autorecEntryUuid, message.timerecEntryUuid,
        message.start, message.stop, message.startExtraMinutes, message.stopExtraMinutes,
        message.retentionDays, message.removalDays, message.priority, message.contentType,
        message.ageRating, message.ratingLabel, message.ratingIcon, message.ratingAuthority,
        message.ratingCountry, message.playCount, message.playPositionSeconds, message.seasonNumber,
        message.episodeNumber, message.episodeCount, message.partNumber, message.partCount,
        message.title, message.description, message.summary, message.subtitle, message.owner,
        message.creator, message.comment, message.image, message.fanartImage, message.copyrightYear,
        message.files, message.path, message.dvrConfigUuid, message.duplicate, message.state,
        message.error, message.subscriptionError, message.streamErrors, message.dataErrors,
        message.dataSizeBytes,
    )

    private fun assertPublicComponents(message: Any, expected: List<Any?>) {
        expected.forEachIndexed { index, value ->
            val method = message.javaClass.declaredMethods.single {
                it.name == "component${index + 1}" && Modifier.isPublic(it.modifiers)
            }
            assertEquals(value, method.invoke(message), method.name)
        }
        assertFalse(message.javaClass.declaredMethods.any {
            it.name == "component${expected.size + 1}" && Modifier.isPublic(it.modifiers)
        })
    }

    private fun <T> assertUnmodifiable(values: List<T>) {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (values as MutableList<T>).clear()
        }
    }
}
