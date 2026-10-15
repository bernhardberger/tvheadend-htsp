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
            channelNumber = fields.aliasU32(listOf("channelNumber", "number", "lcn", "channelNum", "channelno"))
                ?: malformed(),
            channelNumberMinor = optionalU32("channelNumberMinor"),
            channelName = requiredString("channelName"),
            channelIcon = optionalString("channelIcon"),
            currentEventId = requiredU32("eventId"),
            nextEventId = requiredU32("nextEventId"),
            services = requiredObjectList("services", ::channelService),
            tagIds = requiredU32List(fields.firstName(listOf("tagIds", "tags", "channelTags")) ?: malformed()),
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
            tagId = fields.aliasU32(listOf("tagId", "id")) ?: malformed(),
            tagUuid = fields.versionedUuid("tagIdStr"),
            tagIndex = fields.aliasU32(listOf("tagIndex", "index")) ?: malformed(),
            tagName = fields.aliasString(listOf("tagName", "name")) ?: malformed(),
            tagIcon = optionalString("tagIcon"), tagTitledIcon = requiredU32("tagTitledIcon"),
            channelIds = optionalU32List("members"),
        )
    }

    fun event(fields: Map<*, *>): HtspEvent = fields.reader().run {
        HtspEvent(
            eventId = fields.aliasU32(listOf("eventId", "id")) ?: malformed(),
            channelId = fields.aliasU32(listOf("channelId", "channel")),
            startEpochSeconds = fields.aliasS64(listOf("start", "startTime")) ?: malformed(),
            stopEpochSeconds = fields.aliasS64(listOf("stop", "stopTime")) ?: malformed(),
            title = fields.aliasString(listOf("title", "eventTitle", "name")),
            subtitle = optionalString("subtitle"), summary = optionalString("summary"),
            description = optionalString("description"),
            categories = when (fields["category"]) {
                is String -> null
                else -> optionalStringList("category")
            },
            genre = if (contains("genre")) requiredString("genre") else fields["category"] as? String,
            keywords = optionalStringList("keyword"), seriesLinkUri = optionalString("serieslinkUri"),
            episodeUri = optionalString("episodeUri"),
            contentType = fields.aliasU32(listOf("contentType", "content")),
            ageRating = optionalU32("ageRating"), ratingLabel = optionalString("ratingLabel"),
            ratingIcon = optionalString("ratingIcon"), ratingAuthority = optionalString("ratingAuthority"),
            ratingCountry = optionalString("ratingCountry"), starRating = optionalU32("starRating"),
            copyrightYear = optionalU32("copyrightYear"), firstAiredEpochSeconds = optionalS64("firstAired"),
            isNew = optionalFlag("isNew"),
            seasonNumber = fields.episodeU32(listOf("seasonNumber", "season"), listOf("seasonNumber", "season")),
            seasonCount = fields.episodeU32(listOf("seasonCount"), listOf("seasonCount", "count")),
            episodeNumber = fields.episodeU32(listOf("episodeNumber"), listOf("episodeNumber", "number")),
            episodeCount = fields.episodeU32(listOf("episodeCount"), listOf("episodeCount", "count")),
            partNumber = fields.episodeU32(listOf("partNumber", "part"), listOf("partNumber", "part")),
            partCount = fields.episodeU32(listOf("partCount"), listOf("partCount")),
            episodeOnscreen = optionalString("episodeOnscreen"),
            episodeId = optionalU32("episodeId"),
            seriesLinkId = fields.aliasU32(listOf("serieslinkId", "seriesLinkId")),
            image = optionalString("image"), dvrId = optionalU32("dvrId"), nextEventId = optionalU32("nextEventId"),
            credits = decodeProgrammeCredits(fields, "credits", malformed),
        )
    }

    fun dvrEntry(fields: Map<*, *>): HtspDvrEntry = fields.reader().run {
        HtspDvrEntry(
            entryId = fields.aliasU32(listOf("id", "dvrId")) ?: malformed(),
            entryUuid = fields.versionedUuid("idStr"), enabled = requiredFlag("enabled"),
            channelId = fields.aliasU32(listOf("channelId", "channel")), channelName = optionalString("channelName"),
            eventId = optionalU32("eventId"), autorecEntryUuid = optionalString("autorecId"),
            timerecEntryUuid = optionalString("timerecId"), startEpochSeconds = requiredS64("start"),
            stopEpochSeconds = requiredS64("stop"), startExtraMinutes = requiredS64("startExtra"),
            stopExtraMinutes = requiredS64("stopExtra"), retentionDays = requiredU32("retention"),
            removalDays = requiredU32("removal"), priority = requiredU32("priority"),
            contentType = requiredU32("contentType"), ageRating = requiredU32("ageRating"),
            ratingLabel = optionalString("ratingLabel"), ratingIcon = optionalString("ratingIcon"),
            ratingAuthority = optionalString("ratingAuthority"), ratingCountry = optionalString("ratingCountry"),
            playCount = fields.aliasU32(listOf("playcount", "playCount")),
            playPositionSeconds = fields.aliasU32(listOf("playposition", "playPosition")),
            seasonNumber = optionalU32("seasonNumber"), seasonCount = optionalU32("seasonCount"),
            episodeNumber = optionalU32("episodeNumber"), episodeCount = optionalU32("episodeCount"),
            partNumber = optionalU32("partNumber"), partCount = optionalU32("partCount"),
            episodeOnscreen = optionalString("episode"), title = optionalString("title"),
            description = optionalString("description"), summary = optionalString("summary"),
            subtitle = optionalString("subtitle"), owner = optionalString("owner"), creator = optionalString("creator"),
            comment = optionalString("comment"), image = optionalString("image"), fanartImage = optionalString("fanartImage"),
            copyrightYear = optionalU32("copyrightYear"), files = optionalObjectList("files", ::recordingFile),
            path = optionalString("path"), dvrConfigUuid = optionalString("configId"), duplicate = optionalFlag("duplicate"),
            state = fields.aliasString(listOf("state", "status")) ?: malformed(),
            error = fields.aliasString(listOf("error", "statusError")), subscriptionError = optionalString("subscriptionError"),
            streamErrors = optionalU32("streamErrors"), dataErrors = optionalU32("dataErrors"),
            dataSizeBytes = optionalS64("dataSize"),
        )
    }

    private fun recordingFile(fields: Map<*, *>): HtspDvrRecordingFile = fields.reader().run {
        HtspDvrRecordingFile(
            fileId = optionalU32("id"), path = fields.aliasString(listOf("filename", "path")),
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
            daysOfWeekMask = requiredU32("daysOfWeek"), approximateStartMinutesSinceMidnight = requiredS32("approxTime"),
            startMinutesSinceMidnight = requiredS32("start"), startWindowEndMinutesSinceMidnight = requiredS32("startWindow"),
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
            startMinutesSinceMidnight = fields.timerecMinutes("start"),
            stopMinutesSinceMidnight = fields.timerecMinutes("stop"), daysOfWeekMask = requiredU32("daysOfWeek"),
            priority = requiredU32("priority"), retentionDays = requiredU32("retention"), removalDays = requiredU32("removal"),
            directory = optionalString("directory"), owner = optionalString("owner"), creator = optionalString("creator"),
            dvrConfigUuid = optionalString("configId"), comment = optionalString("comment"),
        )
    }

    private fun Map<*, *>.reader(): HtspFieldReader = HtspFieldReader(this, malformed)
    private fun Map<*, *>.firstName(names: List<String>): String? = names.firstOrNull(::containsKey)
    private fun Map<*, *>.aliasU32(names: List<String>): Long? = firstName(names)?.let { reader().requiredU32(it) }
    private fun Map<*, *>.aliasS64(names: List<String>): Long? = firstName(names)?.let { reader().requiredS64(it) }
    private fun Map<*, *>.aliasString(names: List<String>): String? = firstName(names)?.let { reader().requiredString(it) }
    private fun Map<*, *>.versionedUuid(name: String): String? =
        if (protocolVersion >= 41) reader().requiredString(name) else reader().optionalString(name)

    private fun Map<*, *>.episodeU32(top: List<String>, nested: List<String>): Long? =
        aliasU32(top) ?: if (containsKey("episode")) reader().requiredObject("episode").aliasU32(nested) else null

    private fun Map<*, *>.timerecMinutes(name: String): Int? = when (val value = reader().requiredS32(name)) {
        -1 -> null
        in 0..1439 -> value
        else -> malformed()
    }
}

internal typealias HtspEntityDecoder = `HtspEntityDecoder-internal`
