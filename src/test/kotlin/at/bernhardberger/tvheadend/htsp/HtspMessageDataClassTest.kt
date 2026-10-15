package at.bernhardberger.tvheadend.htsp

import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspApiUuid
import at.bernhardberger.tvheadend.htsp.jsonapi.HtspJsonApi
import at.bernhardberger.tvheadend.htsp.messages.*
import at.bernhardberger.tvheadend.htsp.model.*
import at.bernhardberger.tvheadend.htsp.requests.FileOpenRequest
import at.bernhardberger.tvheadend.htsp.requests.GetTicketResponse
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HtspMessageDataClassTest {
    private val decoder = HtspEntityDecoder(44) { error("invalid fixture") }

    @Test
    fun wrappersCarryOneEntityAndFullReplacementClearsAbsentFields() {
        val old = decoder.channel(metadataFixture("channelAdd") + ("channelIcon" to "old.png"))
        val replacement = decoder.channel(metadataFixture("channelUpdate"))
        assertNull(replacement.channelIcon)
        assertNotEquals(old, replacement)
        val messages = listOf(
            HtspChannelAddMessage(old), HtspChannelUpdateMessage(replacement),
            HtspTagAddMessage(decoder.tag(metadataFixture("tagAdd"))),
            HtspTagUpdateMessage(decoder.tag(metadataFixture("tagUpdate"))),
            HtspEventAddMessage(decoder.event(metadataFixture("eventAdd"))),
            HtspEventUpdateMessage(decoder.event(metadataFixture("eventUpdate"))),
            HtspDvrEntryAddMessage(decoder.dvrEntry(metadataFixture("dvrEntryAdd"))),
            HtspDvrEntryUpdateMessage(decoder.dvrEntry(metadataFixture("dvrEntryUpdate"))),
            HtspAutorecEntryAddMessage(decoder.autorecEntry(metadataFixture("autorecEntryAdd"))),
            HtspAutorecEntryUpdateMessage(decoder.autorecEntry(metadataFixture("autorecEntryUpdate"))),
            HtspTimerecEntryAddMessage(decoder.timerecEntry(metadataFixture("timerecEntryAdd"))),
            HtspTimerecEntryUpdateMessage(decoder.timerecEntry(metadataFixture("timerecEntryUpdate"))),
        )
        messages.forEach { message ->
            val components = message.javaClass.declaredMethods.filter { it.name.matches(Regex("component\\d+")) }
            assertEquals(listOf("component1"), components.map { it.name })
            val entity = components.single().invoke(message)
            val copy = message.javaClass.declaredMethods.single { it.name == "copy" }
            assertEquals(message, copy.invoke(message, entity))
        }
    }

    @Test
    fun entityConstructionAndCopySnapshotListsAndValidateUnsignedFields() {
        val tags = mutableListOf(1L, 1L)
        val services = mutableListOf(HtspChannelService("service", "SDTV", 1L, null, null, null))
        val channel = HtspChannel(1L, null, 2L, null, "Channel", null, 0L, 0L, services, tags)
        tags.clear()
        services.clear()
        assertEquals(listOf(1L, 1L), channel.tagIds)
        assertEquals(1, channel.services.size)
        assertUnmodifiable(channel.tagIds)
        assertUnmodifiable(channel.services)
        val copiedTags = mutableListOf(2L)
        val copied = channel.copy(tagIds = copiedTags)
        copiedTags.clear()
        assertEquals(listOf(2L), copied.tagIds)
        assertUnmodifiable(copied.tagIds)

        val members = mutableListOf(3L)
        val tag = decoder.tag(metadataFixture("tagAdd")).copy(channelIds = members)
        members.clear()
        assertEquals(listOf(3L), tag.channelIds)
        assertUnmodifiable(tag.channelIds!!)

        val categories = mutableListOf("Drama")
        val event = decoder.event(metadataFixture("eventAdd")).copy(categories = categories)
        categories.clear()
        assertEquals(listOf("Drama"), event.categories)
        assertUnmodifiable(event.categories!!)
        val keywords = mutableListOf("new")
        val eventCopy = event.copy(keywords = keywords)
        keywords.clear()
        assertEquals(listOf("new"), eventCopy.keywords)
        assertUnmodifiable(eventCopy.keywords!!)

        val files = mutableListOf(HtspDvrRecordingFile("/private.ts", null, null, null))
        val dvr = decoder.dvrEntry(metadataFixture("dvrEntryAdd")).copy(files = files)
        files.clear()
        assertEquals(1, dvr.files!!.size)
        assertUnmodifiable(dvr.files)
        val replacementFiles = dvr.files.toMutableList()
        val dvrCopy = dvr.copy(files = replacementFiles)
        replacementFiles.clear()
        assertEquals(dvr, dvrCopy)
        assertUnmodifiable(dvrCopy.files!!)

        val autorec = decoder.autorecEntry(metadataFixture("autorecEntryAdd"))
        val timerec = decoder.timerecEntry(metadataFixture("timerecEntryAdd"))
        assertEquals(0, autorec.copy(startMinutesSinceMidnight = 0).startMinutesSinceMidnight)
        assertEquals(1439,
            autorec.copy(startWindowEndMinutesSinceMidnight = 1439).startWindowEndMinutesSinceMidnight)
        assertEquals(null, dvr.copy(ageRating = null).ageRating)
        listOf<() -> Unit>(
            { channel.copy(channelId = -1L) }, { channel.copy(channelNumber = -1L) },
            { channel.copy(currentEventId = -1L) }, { channel.copy(tagIds = listOf(-1L)) },
            { tag.copy(tagIndex = -1L) }, { tag.copy(channelIds = listOf(-1L)) },
            { event.copy(eventId = -1L) }, { event.copy(contentType = -1L) },
            { event.copy(episodeNumber = -1L) }, { event.copy(seasonCount = 0x1_0000_0000L) },
            { dvr.copy(entryId = -1L) }, { dvr.copy(channelId = -1L) },
            { autorec.copy(maximumRecordingCount = -1L) }, { autorec.copy(channelId = -1L) },
            { autorec.copy(approximateStartMinutesSinceMidnight = -1) },
            { autorec.copy(approximateStartMinutesSinceMidnight = 1440) },
            { autorec.copy(startMinutesSinceMidnight = -1) },
            { autorec.copy(startMinutesSinceMidnight = 1440) },
            { autorec.copy(startMinutesSinceMidnight = Int.MAX_VALUE) },
            { autorec.copy(startWindowEndMinutesSinceMidnight = -2) },
            { autorec.copy(startWindowEndMinutesSinceMidnight = 1440) },
            { autorec.copy(startWindowEndMinutesSinceMidnight = Int.MAX_VALUE) },
            { timerec.copy(channelId = -1L) }, { timerec.copy(channelId = 0x1_0000_0000L) },
            { timerec.copy(startMinutesSinceMidnight = -1) }, { timerec.copy(stopMinutesSinceMidnight = 1440) },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }

    @Test
    fun entityCopiesRetainAllComponentsAndHideSnapshotMarker() {
        val entities = listOf(
            decoder.channel(metadataFixture("channelAdd")), decoder.tag(metadataFixture("tagAdd")),
            decoder.event(metadataFixture("eventAdd") + mapOf("category" to listOf("Drama"), "episodeNumber" to 42L)),
            decoder.dvrEntry(metadataFixture("dvrEntryAdd") + mapOf("seasonCount" to 4L, "episode" to "S1E2")),
            decoder.autorecEntry(metadataFixture("autorecEntryAdd")), decoder.timerecEntry(metadataFixture("timerecEntryAdd")),
            HtspDvrRecordingFile("recording.ts", 1L, 2L, 3L, HtspDvrRecordingInfo(emptyList())),
        )
        entities.forEach { entity ->
            val components = entity.javaClass.declaredMethods
                .filter { it.name.matches(Regex("component\\d+")) && Modifier.isPublic(it.modifiers) }
                .sortedBy { it.name.removePrefix("component").toInt() }
            val values = components.map { it.invoke(entity) }
            assertFalse(values.contains(Unit))
            val copy = entity.javaClass.declaredMethods.single { it.name == "copy" && Modifier.isPublic(it.modifiers) }
            val copied = copy.invoke(entity, *values.toTypedArray())
            assertEquals(entity, copied)
            assertEquals(entity.hashCode(), copied.hashCode())
        }
    }

    @Test
    fun subscriptionStartUsesBinaryContentSemanticsThroughNestedCopies() {
        fun stream(index: Long) = HtspSubscriptionStream(
            index, "AAC", null, null, null, null, null, null, null, null, null, null, null, null, null,
        )
        val first = HtspSubscriptionStartMessage(1L, streams = listOf(stream(0L)),
            codecMetadata = HtspBinary(byteArrayOf(3, 4)))
        val second = HtspSubscriptionStartMessage(1L, streams = listOf(stream(0L)),
            codecMetadata = HtspBinary(byteArrayOf(3, 4)))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first, first.copy())
        assertUnmodifiable(first.streams!!)
        val streams = mutableListOf(stream(1L))
        val copy = first.copy(streams = streams)
        streams.clear()
        assertEquals(1, copy.streams!!.size)
        assertUnmodifiable(copy.streams)
        assertThrows(IllegalArgumentException::class.java) { first.copy(subscriptionId = -1L) }
    }

    @OptIn(HtspJsonApi::class)
    @Test
    fun entitiesAndSensitiveTypesKeepRedactedRendering() {
        val secret = "private-value"
        val entities = listOf(
            decoder.channel(metadataFixture("channelAdd")).copy(channelName = secret),
            decoder.tag(metadataFixture("tagAdd")).copy(tagName = secret),
            decoder.event(metadataFixture("eventAdd")).copy(title = secret, episodeUri = secret),
            decoder.dvrEntry(metadataFixture("dvrEntryAdd")).copy(path = secret, error = secret),
            decoder.autorecEntry(metadataFixture("autorecEntryAdd")).copy(owner = secret, dvrConfigUuid = secret),
            decoder.timerecEntry(metadataFixture("timerecEntryAdd")).copy(directory = secret, dvrConfigUuid = secret),
            HtspEndpoint("host", 9982, "user", secret), GetTicketResponse(secret, secret), FileOpenRequest(secret),
            HtspApiUuid(ByteArray(16) { it.toByte() }),
        )
        entities.forEach {
            assertFalse(it.toString().contains(secret))
            assertTrue(it.toString().contains("redacted", ignoreCase = true))
        }
        assertEquals("HtspSubscriptionStartMessage(<redacted>)",
            HtspSubscriptionStartMessage(456L).toString())
    }

    private fun <T> assertUnmodifiable(values: List<T>) {
        assertThrows(UnsupportedOperationException::class.java) { (values as MutableList<T>).clear() }
    }
}
