package at.bernhardberger.tvheadend.htsp.model

import at.bernhardberger.tvheadend.htsp.wire.HtspFieldReader

/** Shared full-builder decoding for pushed metadata and RPC replies. */
internal class `HtspEntityDecoder-internal`(
    private val protocolVersion: Long,
    private val malformed: () -> Nothing,
) {
    fun channel(fields: Map<*, *>): HtspChannel = fields.reader().run {
        HtspChannel(
            channelId = requiredU32("channelId"),
            channelUuid = fields.versionedUuid("channelIdStr"),
            channelNumber = requiredU32("channelNumber"),
            channelNumberMinor = optionalU32("channelNumberMinor"),
            channelName = requiredString("channelName"),
            channelIcon = optionalString("channelIcon"),
            currentEventId = requiredU32("eventId"),
            nextEventId = requiredU32("nextEventId"),
            services = requiredObjectList("services", ::channelService),
            tagIds = requiredU32List("tags"),
        )
    }

    private fun channelService(fields: Map<*, *>): HtspChannelService = fields.reader().run {
        HtspChannelService(
            name = requiredString("name"), type = requiredString("type"), content = requiredU32("content"),
            conditionalAccessId = optionalU32("caid"), conditionalAccessName = optionalString("caname"),
            providerName = optionalString("providername"), hbbtv = decodeHbbtvApplications(fields, malformed),
        )
    }

    fun tag(fields: Map<*, *>): HtspTag = fields.reader().run {
        HtspTag(
            tagId = requiredU32("tagId"),
            tagUuid = fields.versionedUuid("tagIdStr"),
            tagIndex = requiredU32("tagIndex"),
            tagName = requiredString("tagName"),
            tagIcon = optionalString("tagIcon"), tagTitledIcon = requiredU32("tagTitledIcon"),
            channelIds = optionalU32List("members"),
        )
    }

    fun event(fields: Map<*, *>): HtspEvent = fields.reader().run {
        HtspEvent(
            eventId = requiredU32("eventId"),
            channelId = optionalU32("channelId"),
            startEpochSeconds = requiredS64("start"),
            stopEpochSeconds = requiredS64("stop"),
            title = optionalString("title"),
            subtitle = optionalString("subtitle"), summary = optionalString("summary"),
            description = optionalString("description"),
            categories = optionalStringList("category"),
            keywords = optionalStringList("keyword"), seriesLinkUri = optionalString("serieslinkUri"),
            episodeUri = optionalString("episodeUri"),
            contentType = optionalU32("contentType"),
            ageRating = optionalU32("ageRating"), ratingLabel = optionalString("ratingLabel"),
            ratingIcon = optionalString("ratingIcon"), ratingAuthority = optionalString("ratingAuthority"),
            ratingCountry = optionalString("ratingCountry"), starRating = optionalU32("starRating"),
            copyrightYear = optionalU32("copyrightYear"), firstAiredEpochSeconds = optionalS64("firstAired"),
            isNew = optionalFlag("isNew"),
            seasonNumber = optionalU32("seasonNumber"), seasonCount = optionalU32("seasonCount"),
            episodeNumber = optionalU32("episodeNumber"), episodeCount = optionalU32("episodeCount"),
            partNumber = optionalU32("partNumber"), partCount = optionalU32("partCount"),
            episodeOnscreen = optionalString("episodeOnscreen"),
            image = optionalString("image"), dvrId = optionalU32("dvrId"), nextEventId = optionalU32("nextEventId"),
            credits = decodeProgrammeCredits(fields, "credits", malformed),
        )
    }

    fun dvrEntry(fields: Map<*, *>): HtspDvrEntry = fields.reader().run {
        HtspDvrEntry(
            entryId = requiredU32("id"),
            entryUuid = fields.versionedUuid("idStr"), enabled = requiredFlag("enabled"),
            channelId = optionalU32("channel"), channelName = optionalString("channelName"),
            eventId = optionalU32("eventId"), autorecEntryUuid = optionalString("autorecId"),
            timerecEntryUuid = optionalString("timerecId"), startEpochSeconds = requiredS64("start"),
            stopEpochSeconds = requiredS64("stop"), startExtraMinutes = requiredS64("startExtra"),
            stopExtraMinutes = requiredS64("stopExtra"), retentionDays = requiredU32("retention"),
            removalDays = requiredU32("removal"), priority = requiredU32("priority"),
            contentType = requiredU32("contentType"),
            ageRating = if (protocolVersion >= 37) requiredU32("ageRating") else optionalU32("ageRating"),
            ratingLabel = optionalString("ratingLabel"), ratingIcon = optionalString("ratingIcon"),
            ratingAuthority = optionalString("ratingAuthority"), ratingCountry = optionalString("ratingCountry"),
            playCount = optionalU32("playcount"),
            playPositionSeconds = optionalU32("playposition"),
            seasonNumber = optionalU32("seasonNumber"), seasonCount = optionalU32("seasonCount"),
            episodeNumber = optionalU32("episodeNumber"), episodeCount = optionalU32("episodeCount"),
            partNumber = optionalU32("partNumber"), partCount = optionalU32("partCount"),
            episodeOnscreen = optionalString("episode"), title = optionalString("title"),
            description = optionalString("description"), summary = optionalString("summary"),
            subtitle = optionalString("subtitle"), owner = optionalString("owner"), creator = optionalString("creator"),
            comment = optionalString("comment"), image = optionalString("image"), fanartImage = optionalString("fanartImage"),
            copyrightYear = optionalU32("copyrightYear"), files = optionalObjectList("files", ::recordingFile),
            path = optionalString("path"), dvrConfigUuid = optionalString("configId"), duplicate = optionalFlag("duplicate"),
            state = requiredString("state"),
            error = optionalString("error"), subscriptionError = optionalString("subscriptionError"),
            streamErrors = optionalU32("streamErrors"), dataErrors = optionalU32("dataErrors"),
            dataSizeBytes = optionalS64("dataSize"),
        )
    }

    private fun recordingFile(fields: Map<*, *>): HtspDvrRecordingFile = fields.reader().run {
        HtspDvrRecordingFile(
            path = optionalString("filename"),
            startEpochSeconds = optionalS64("start"), stopEpochSeconds = optionalS64("stop"), sizeBytes = optionalS64("size"),
            info = if (contains("info")) {
                val entries = value("info") as? List<*> ?: malformed()
                HtspDvrRecordingInfo(entries.map { recordingStream(it as? Map<*, *> ?: emptyMap<Any?, Any?>()) })
            } else null,
        )
    }

    private fun recordingStream(fields: Map<*, *>): HtspDvrRecordingStream = fields.reader().run {
        HtspDvrRecordingStream(
            type = observedString("type"), language = observedString("language"),
            audioType = observedU32("audio_type"), audioVersion = observedU32("audio_version"),
            width = observedU32("width"), height = observedU32("height"),
            frameDurationUs = observedU32("duration")?.let { it * 1_000_000L / 90_000L },
            aspectNumerator = observedU32("aspect_num"), aspectDenominator = observedU32("aspect_den"),
            compositionId = observedU32("composition_id"), ancillaryId = observedU32("ancillary_id"),
        )
    }

    fun autorecEntry(fields: Map<*, *>): HtspAutorecEntry = fields.reader().run {
        HtspAutorecEntry(
            id = requiredString("id"), enabled = requiredFlag("enabled"),
            maxDurationSeconds = requiredU32("maxDuration"), minDurationSeconds = requiredU32("minDuration"),
            retentionDays = requiredU32("retention"), removalDays = requiredU32("removal"),
            daysOfWeekMask = requiredU32("daysOfWeek"),
            approximateStartMinutesSinceMidnight = fields.ruleMinutes("approxTime"),
            startMinutesSinceMidnight = fields.ruleMinutes("start"),
            startWindowEndMinutesSinceMidnight = fields.ruleMinutes("startWindow"),
            priority = requiredU32("priority"), startExtraMinutes = requiredS64("startExtra"),
            stopExtraMinutes = requiredS64("stopExtra"), duplicateDetection = requiredU32("dupDetect"),
            maximumRecordingCount = requiredU32("maxCount"),
            broadcastType = if (protocolVersion >= 39) requiredU32("broadcastType") else optionalU32("broadcastType"),
            comment = optionalString("comment"), title = optionalString("title"), fullText = optionalFlag("fulltext"),
            mergeText = optionalFlag("mergetext"), name = optionalString("name"), directory = optionalString("directory"),
            owner = optionalString("owner"), creator = optionalString("creator"), channelId = optionalU32("channel"),
            seriesLinkUri = optionalString("serieslinkUri"), dvrConfigUuid = optionalString("configId"),
        )
    }

    fun timerecEntry(fields: Map<*, *>): HtspTimerecEntry = fields.reader().run {
        HtspTimerecEntry(
            id = requiredString("id"), enabled = requiredFlag("enabled"), name = optionalString("name"),
            title = optionalString("title"), channelId = optionalU32("channel"),
            startMinutesSinceMidnight = fields.ruleMinutes("start"),
            stopMinutesSinceMidnight = fields.ruleMinutes("stop"), daysOfWeekMask = requiredU32("daysOfWeek"),
            priority = requiredU32("priority"), retentionDays = requiredU32("retention"), removalDays = requiredU32("removal"),
            directory = optionalString("directory"), owner = optionalString("owner"), creator = optionalString("creator"),
            dvrConfigUuid = optionalString("configId"), comment = optionalString("comment"),
        )
    }

    private fun Map<*, *>.reader(): HtspFieldReader = HtspFieldReader(this, malformed)
    private fun Map<*, *>.versionedUuid(name: String): String? =
        if (protocolVersion >= 41) reader().requiredString(name) else reader().optionalString(name)

    private fun Map<*, *>.ruleMinutes(name: String): Int? = when (val value = reader().requiredS32(name)) {
        -1 -> null
        in 0..1439 -> value
        else -> malformed()
    }
}

internal typealias HtspEntityDecoder = `HtspEntityDecoder-internal`
