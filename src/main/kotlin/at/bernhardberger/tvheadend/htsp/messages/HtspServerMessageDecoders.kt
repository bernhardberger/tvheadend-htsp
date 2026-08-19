package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.requests.*
import at.bernhardberger.tvheadend.htsp.wire.*

private val CHANNEL_NUMBER_KEYS = listOf("channelNumber", "number", "lcn", "channelNum", "channelno")
private val CHANNEL_TAG_KEYS = listOf("tagIds", "tags", "channelTags")
private val TAG_ID_KEYS = listOf("tagId", "id")
private val TAG_NAME_KEYS = listOf("tagName", "name")
private val TAG_INDEX_KEYS = listOf("tagIndex", "index")
private val DVR_ID_KEYS = listOf("id", "dvrId")
private val DVR_CHANNEL_KEYS = listOf("channelId", "channel")
private val DVR_ERROR_KEYS = listOf("error", "statusError")
private val DVR_PLAY_POSITION_KEYS = listOf("playposition", "playPosition")
private val DVR_PLAY_COUNT_KEYS = listOf("playcount", "playCount")
private val DVR_FILE_PATH_KEYS = listOf("filename", "path")
private val EVENT_ID_KEYS = listOf("eventId", "id")
private val EVENT_CHANNEL_KEYS = listOf("channelId", "channel")
private val EVENT_START_KEYS = listOf("start", "startTime")
private val EVENT_STOP_KEYS = listOf("stop", "stopTime")
private val EVENT_TITLE_KEYS = listOf("title", "eventTitle", "name")
private val EVENT_CONTENT_KEYS = listOf("contentType", "content")
private val SUBSCRIPTION_ID_KEYS = listOf("subscriptionId", "id")
private val STATUS_KEYS = listOf("state", "status")
private val SUBSCRIPTION_ERROR_KEYS = listOf("subscriptionError", "error")

private class `HtspServerMessageMappingException-internal` : IllegalArgumentException()

private typealias HtspServerMessageMappingException =
    `HtspServerMessageMappingException-internal`

@JvmSynthetic
internal fun decodeChannelAdd(fields: Map<String, Any?>): HtspServerMessage = HtspChannelAddMessage(
    channelId = fields.requiredU32("channelId"),
    channelUuid = fields.optionalString("channelIdStr"),
    channelNumber = fields.optionalServerAliasU32(CHANNEL_NUMBER_KEYS),
    channelNumberMinor = fields.optionalU32("channelNumberMinor"),
    channelName = fields.optionalString("channelName"),
    channelIcon = fields.optionalString("channelIcon"),
    currentEventId = fields.optionalU32("eventId"),
    nextEventId = fields.optionalU32("nextEventId"),
    services = fields.optionalObjectList("services", ::decodeServerChannelService),
    tagIds = fields.optionalServerAliasU32List(CHANNEL_TAG_KEYS),
)

@JvmSynthetic
internal fun decodeChannelUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspChannelUpdateMessage(
    channelId = fields.requiredU32("channelId"),
    channelUuid = fields.optionalString("channelIdStr"),
    channelNumber = fields.optionalServerAliasU32(CHANNEL_NUMBER_KEYS),
    channelNumberMinor = fields.optionalU32("channelNumberMinor"),
    channelName = fields.optionalString("channelName"),
    channelIcon = fields.optionalString("channelIcon"),
    currentEventId = fields.optionalU32("eventId"),
    nextEventId = fields.optionalU32("nextEventId"),
    services = fields.optionalObjectList("services", ::decodeServerChannelService),
    tagIds = fields.optionalServerAliasU32List(CHANNEL_TAG_KEYS),
)

@JvmSynthetic
internal fun decodeChannelDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspChannelDeleteMessage(fields.requiredU32("channelId"))

@JvmSynthetic
internal fun decodeTagAdd(fields: Map<String, Any?>): HtspServerMessage = HtspTagAddMessage(
    tagId = fields.requiredServerAliasU32(TAG_ID_KEYS),
    tagUuid = fields.optionalString("tagIdStr"),
    tagIndex = fields.optionalServerAliasU32(TAG_INDEX_KEYS),
    tagName = fields.optionalServerAliasString(TAG_NAME_KEYS),
    tagIcon = fields.optionalString("tagIcon"),
    tagTitledIcon = fields.optionalU32("tagTitledIcon"),
    channelIds = fields.optionalU32List("members"),
)

@JvmSynthetic
internal fun decodeTagUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspTagUpdateMessage(
    tagId = fields.requiredServerAliasU32(TAG_ID_KEYS),
    tagUuid = fields.optionalString("tagIdStr"),
    tagIndex = fields.optionalServerAliasU32(TAG_INDEX_KEYS),
    tagName = fields.optionalServerAliasString(TAG_NAME_KEYS),
    tagIcon = fields.optionalString("tagIcon"),
    tagTitledIcon = fields.optionalU32("tagTitledIcon"),
    channelIds = fields.optionalU32List("members"),
)

@JvmSynthetic
internal fun decodeTagDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspTagDeleteMessage(fields.requiredServerAliasU32(TAG_ID_KEYS))

@JvmSynthetic
internal fun decodeDvrEntryAdd(fields: Map<String, Any?>): HtspServerMessage = HtspDvrEntryAddMessage(
    entryId = fields.requiredServerAliasU32(DVR_ID_KEYS),
    entryUuid = fields.optionalString("idStr"),
    enabled = fields.optionalU32("enabled"),
    channelId = fields.optionalServerAliasU32(DVR_CHANNEL_KEYS),
    channelName = fields.optionalString("channelName"),
    eventId = fields.optionalU32("eventId"),
    autorecEntryUuid = fields.optionalString("autorecId"),
    timerecEntryUuid = fields.optionalString("timerecId"),
    start = fields.optionalS64("start"),
    stop = fields.optionalS64("stop"),
    startExtraMinutes = fields.optionalS64("startExtra"),
    stopExtraMinutes = fields.optionalS64("stopExtra"),
    retentionDays = fields.optionalU32("retention"),
    removalDays = fields.optionalU32("removal"),
    priority = fields.optionalU32("priority"),
    contentType = fields.optionalU32("contentType"),
    ageRating = fields.optionalU32("ageRating"),
    ratingLabel = fields.optionalString("ratingLabel"),
    ratingIcon = fields.optionalString("ratingIcon"),
    ratingAuthority = fields.optionalString("ratingAuthority"),
    ratingCountry = fields.optionalString("ratingCountry"),
    playCount = fields.optionalServerAliasU32(DVR_PLAY_COUNT_KEYS),
    playPositionSeconds = fields.optionalServerAliasU32(DVR_PLAY_POSITION_KEYS),
    seasonNumber = fields.optionalU32("seasonNumber"),
    episodeNumber = fields.optionalU32("episodeNumber"),
    episodeCount = fields.optionalU32("episodeCount"),
    partNumber = fields.optionalU32("partNumber"),
    partCount = fields.optionalU32("partCount"),
    title = fields.optionalString("title"),
    description = fields.optionalString("description"),
    summary = fields.optionalString("summary"),
    subtitle = fields.optionalString("subtitle"),
    owner = fields.optionalString("owner"),
    creator = fields.optionalString("creator"),
    comment = fields.optionalString("comment"),
    image = fields.optionalString("image"),
    fanartImage = fields.optionalString("fanartImage"),
    copyrightYear = fields.optionalU32("copyrightYear"),
    files = fields.optionalObjectList("files", ::decodeDvrRecordingFile),
    path = fields.optionalString("path"),
    dvrConfigUuid = fields.optionalString("configId"),
    duplicate = fields.optionalU32("duplicate"),
    state = fields.optionalServerAliasString(STATUS_KEYS),
    error = fields.optionalServerAliasString(DVR_ERROR_KEYS),
    subscriptionError = fields.optionalString("subscriptionError"),
    streamErrors = fields.optionalU32("streamErrors"),
    dataErrors = fields.optionalU32("dataErrors"),
    dataSizeBytes = fields.optionalS64("dataSize"),
)

@JvmSynthetic
internal fun decodeDvrEntryUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspDvrEntryUpdateMessage(
    entryId = fields.requiredServerAliasU32(DVR_ID_KEYS),
    entryUuid = fields.optionalString("idStr"),
    enabled = fields.optionalU32("enabled"),
    channelId = fields.optionalServerAliasU32(DVR_CHANNEL_KEYS),
    channelName = fields.optionalString("channelName"),
    eventId = fields.optionalU32("eventId"),
    autorecEntryUuid = fields.optionalString("autorecId"),
    timerecEntryUuid = fields.optionalString("timerecId"),
    start = fields.optionalS64("start"),
    stop = fields.optionalS64("stop"),
    startExtraMinutes = fields.optionalS64("startExtra"),
    stopExtraMinutes = fields.optionalS64("stopExtra"),
    retentionDays = fields.optionalU32("retention"),
    removalDays = fields.optionalU32("removal"),
    priority = fields.optionalU32("priority"),
    contentType = fields.optionalU32("contentType"),
    ageRating = fields.optionalU32("ageRating"),
    ratingLabel = fields.optionalString("ratingLabel"),
    ratingIcon = fields.optionalString("ratingIcon"),
    ratingAuthority = fields.optionalString("ratingAuthority"),
    ratingCountry = fields.optionalString("ratingCountry"),
    playCount = fields.optionalServerAliasU32(DVR_PLAY_COUNT_KEYS),
    playPositionSeconds = fields.optionalServerAliasU32(DVR_PLAY_POSITION_KEYS),
    seasonNumber = fields.optionalU32("seasonNumber"),
    episodeNumber = fields.optionalU32("episodeNumber"),
    episodeCount = fields.optionalU32("episodeCount"),
    partNumber = fields.optionalU32("partNumber"),
    partCount = fields.optionalU32("partCount"),
    title = fields.optionalString("title"),
    description = fields.optionalString("description"),
    summary = fields.optionalString("summary"),
    subtitle = fields.optionalString("subtitle"),
    owner = fields.optionalString("owner"),
    creator = fields.optionalString("creator"),
    comment = fields.optionalString("comment"),
    image = fields.optionalString("image"),
    fanartImage = fields.optionalString("fanartImage"),
    copyrightYear = fields.optionalU32("copyrightYear"),
    files = fields.optionalObjectList("files", ::decodeDvrRecordingFile),
    path = fields.optionalString("path"),
    dvrConfigUuid = fields.optionalString("configId"),
    duplicate = fields.optionalU32("duplicate"),
    state = fields.optionalServerAliasString(STATUS_KEYS),
    error = fields.optionalServerAliasString(DVR_ERROR_KEYS),
    subscriptionError = fields.optionalString("subscriptionError"),
    streamErrors = fields.optionalU32("streamErrors"),
    dataErrors = fields.optionalU32("dataErrors"),
    dataSizeBytes = fields.optionalS64("dataSize"),
)

@JvmSynthetic
internal fun decodeDvrEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspDvrEntryDeleteMessage(fields.requiredServerAliasU32(DVR_ID_KEYS))

@JvmSynthetic
internal fun decodeAutorecEntryAdd(fields: Map<String, Any?>): HtspServerMessage =
    HtspAutorecEntryAddMessage(
        id = fields.requiredString("id"),
        enabled = fields.requiredFlag("enabled"),
        maxDurationSeconds = fields.requiredU32("maxDuration"),
        minDurationSeconds = fields.requiredU32("minDuration"),
        retentionDays = fields.requiredU32("retention"),
        removalDays = fields.requiredU32("removal"),
        daysOfWeekMask = fields.requiredU32("daysOfWeek"),
        approximateStartMinutesSinceMidnight = fields.requiredS32("approxTime"),
        startMinutesSinceMidnight = fields.requiredS32("start"),
        startWindowEndMinutesSinceMidnight = fields.requiredS32("startWindow"),
        priority = fields.requiredU32("priority"),
        startExtraMinutes = fields.requiredS64("startExtra"),
        stopExtraMinutes = fields.requiredS64("stopExtra"),
        duplicateDetection = fields.requiredU32("dupDetect"),
        maximumRecordingCount = fields.requiredU32("maxCount"),
        broadcastType = fields.requiredU32("broadcastType"),
        comment = fields.requiredString("comment"),
        title = fields.optionalString("title"),
        fullText = fields.optionalFlag("fulltext"),
        mergeText = fields.optionalFlag("mergetext"),
        name = fields.requiredString("name"),
        directory = fields.optionalString("directory"),
        owner = fields.requiredString("owner"),
        creator = fields.requiredString("creator"),
        channelId = fields.optionalU32("channel"),
        seriesLinkUri = fields.optionalString("serieslinkUri"),
        configId = fields.optionalString("configId"),
    )

@JvmSynthetic
internal fun decodeAutorecEntryUpdate(fields: Map<String, Any?>): HtspServerMessage =
    HtspAutorecEntryUpdateMessage(
        id = fields.requiredString("id"),
        enabled = fields.optionalFlag("enabled"),
        maxDurationSeconds = fields.optionalU32("maxDuration"),
        minDurationSeconds = fields.optionalU32("minDuration"),
        retentionDays = fields.optionalU32("retention"),
        removalDays = fields.optionalU32("removal"),
        daysOfWeekMask = fields.optionalU32("daysOfWeek"),
        approximateStartMinutesSinceMidnight = fields.optionalS32("approxTime"),
        startMinutesSinceMidnight = fields.optionalS32("start"),
        startWindowEndMinutesSinceMidnight = fields.optionalS32("startWindow"),
        priority = fields.optionalU32("priority"),
        startExtraMinutes = fields.optionalS64("startExtra"),
        stopExtraMinutes = fields.optionalS64("stopExtra"),
        duplicateDetection = fields.optionalU32("dupDetect"),
        maximumRecordingCount = fields.optionalU32("maxCount"),
        broadcastType = fields.optionalU32("broadcastType"),
        comment = fields.optionalString("comment"),
        title = fields.optionalString("title"),
        fullText = fields.optionalFlag("fulltext"),
        mergeText = fields.optionalFlag("mergetext"),
        name = fields.optionalString("name"),
        directory = fields.optionalString("directory"),
        owner = fields.optionalString("owner"),
        creator = fields.optionalString("creator"),
        channelId = fields.optionalU32("channel"),
        seriesLinkUri = fields.optionalString("serieslinkUri"),
        configId = fields.optionalString("configId"),
    )

@JvmSynthetic
internal fun decodeAutorecEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspAutorecEntryDeleteMessage(fields.requiredString("id"))

@JvmSynthetic
internal fun decodeTimerecEntryAdd(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimerecEntryAddMessage(
        id = fields.requiredString("id"),
        enabled = fields.requiredFlag("enabled"),
        name = fields.requiredString("name"),
        title = fields.requiredString("title"),
        channelId = fields.requiredBoundedInt("channel", 0..Int.MAX_VALUE),
        startMinutesSinceMidnight = fields.requiredBoundedInt("start", 0..1_440),
        stopMinutesSinceMidnight = fields.requiredBoundedInt("stop", 0..1_440),
        daysOfWeekMask = optionalTimerecValue { fields.optionalU32("daysOfWeek") },
        priority = optionalTimerecValue { fields.optionalU32("priority") },
        retentionDays = optionalTimerecValue { fields.optionalU32("retention") },
        directory = optionalTimerecValue { fields.optionalString("directory") },
        owner = optionalTimerecValue { fields.optionalString("owner") },
        creator = optionalTimerecValue { fields.optionalString("creator") },
        configId = optionalTimerecValue { fields.optionalString("configId") },
        comment = optionalTimerecValue { fields.optionalString("comment") },
    )

@JvmSynthetic
internal fun decodeTimerecEntryUpdate(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimerecEntryUpdateMessage(
        id = fields.requiredString("id"),
        enabled = optionalTimerecValue { fields.optionalFlag("enabled") },
        name = optionalTimerecValue { fields.optionalString("name") },
        title = optionalTimerecValue { fields.optionalString("title") },
        channelId = optionalTimerecValue { fields.optionalBoundedInt("channel", 0..Int.MAX_VALUE) },
        startMinutesSinceMidnight = optionalTimerecValue { fields.optionalBoundedInt("start", 0..1_440) },
        stopMinutesSinceMidnight = optionalTimerecValue { fields.optionalBoundedInt("stop", 0..1_440) },
        daysOfWeekMask = optionalTimerecValue { fields.optionalU32("daysOfWeek") },
        priority = optionalTimerecValue { fields.optionalU32("priority") },
        retentionDays = optionalTimerecValue { fields.optionalU32("retention") },
        directory = optionalTimerecValue { fields.optionalString("directory") },
        owner = optionalTimerecValue { fields.optionalString("owner") },
        creator = optionalTimerecValue { fields.optionalString("creator") },
        configId = optionalTimerecValue { fields.optionalString("configId") },
        comment = optionalTimerecValue { fields.optionalString("comment") },
    )

@JvmSynthetic
internal fun decodeTimerecEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimerecEntryDeleteMessage(fields.requiredString("id"))

@JvmSynthetic
internal fun decodeEventAdd(fields: Map<String, Any?>): HtspServerMessage =
    HtspEventAddMessage(
        event = decodeServerEvent(fields),
        genre = fields.optionalServerEventGenre(),
        episodeId = fields.optionalU32("episodeId"),
        seriesLinkId = fields.optionalServerAliasU32(listOf("serieslinkId", "seriesLinkId")),
    )

@JvmSynthetic
internal fun decodeEventUpdate(fields: Map<String, Any?>): HtspServerMessage = HtspEventUpdateMessage(
    eventId = fields.requiredServerAliasU32(EVENT_ID_KEYS),
    channelId = fields.optionalServerAliasU32(EVENT_CHANNEL_KEYS),
    start = fields.optionalServerAliasS64(EVENT_START_KEYS),
    stop = fields.optionalServerAliasS64(EVENT_STOP_KEYS),
    title = fields.optionalServerAliasString(EVENT_TITLE_KEYS),
    subtitle = fields.optionalString("subtitle"),
    summary = fields.optionalString("summary"),
    description = fields.optionalString("description"),
    genre = fields.optionalServerEventGenre(),
    categories = fields.optionalServerEventCategories(),
    keywords = fields.optionalStringList("keyword"),
    seriesLinkUri = fields.optionalString("serieslinkUri"),
    episodeUri = fields.optionalString("episodeUri"),
    contentType = fields.optionalServerAliasU32(EVENT_CONTENT_KEYS),
    ageRating = fields.optionalU32("ageRating"),
    ratingLabel = fields.optionalString("ratingLabel"),
    ratingIcon = fields.optionalString("ratingIcon"),
    ratingAuthority = fields.optionalString("ratingAuthority"),
    ratingCountry = fields.optionalString("ratingCountry"),
    starRating = fields.optionalU32("starRating"),
    copyrightYear = fields.optionalU32("copyrightYear"),
    firstAired = fields.optionalS64("firstAired"),
    isNew = fields.optionalU32("isNew"),
    seasonNumber = fields.optionalServerEventU32(listOf("seasonNumber", "season"), listOf("seasonNumber", "season")),
    seasonCount = fields.optionalServerEventU32(listOf("seasonCount"), listOf("seasonCount", "count")),
    episodeNumber = fields.optionalServerEventU32(listOf("episodeNumber"), listOf("episodeNumber", "number")),
    episodeCount = fields.optionalServerEventU32(listOf("episodeCount"), listOf("episodeCount", "count")),
    partNumber = fields.optionalServerEventU32(listOf("partNumber", "part"), listOf("partNumber", "part")),
    partCount = fields.optionalServerEventU32(listOf("partCount"), listOf("partCount")),
    episodeOnscreen = fields.optionalString("episodeOnscreen"),
    episodeId = fields.optionalU32("episodeId"),
    seriesLinkId = fields.optionalServerAliasU32(listOf("serieslinkId", "seriesLinkId")),
    image = fields.optionalString("image"),
    dvrId = fields.optionalU32("dvrId"),
    nextEventId = fields.optionalU32("nextEventId"),
)

@JvmSynthetic
internal fun decodeEventDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspEventDeleteMessage(fields.requiredServerAliasU32(EVENT_ID_KEYS))

@JvmSynthetic
internal fun decodeInitialSyncCompleted(fields: Map<String, Any?>): HtspServerMessage =
    HtspInitialSyncCompletedMessage

@JvmSynthetic
internal fun decodeMuxPacket(
    fields: Map<String, Any?>,
    timestampClockForSubscription: (Long) -> HtspTimestampClock,
    ownedPayload: ByteArray?,
): HtspServerMessage {
    val subscriptionId = fields.requiredU32("subscriptionId")
    val clock = timestampClockForSubscription(subscriptionId)
    return try {
        val payload = fields.requiredBinary("payload")
        HtspMuxPacketMessage(
            subscriptionId = subscriptionId,
            frameType = when (val frameType = fields.optionalS64("frametype")) {
                null, 0L -> -1L
                else -> frameType
            },
            streamIndex = fields.requiredU32("stream"),
            decodingTimeUs = fields.optionalS64("dts")?.let(clock::toMicroseconds),
            presentationTimeUs = fields.optionalS64("pts")?.let(clock::toMicroseconds),
            durationUs = clock.toMicroseconds(fields.requiredU32("duration")),
            payload = if (payload === ownedPayload) {
                HtspBinary.takeOwnership(payload)
            } else {
                HtspBinary(payload)
            },
        )
    } catch (_: ArithmeticException) {
        throw HtspServerMessageMappingException()
    }
}

@JvmSynthetic
internal fun decodeQueueStatus(fields: Map<String, Any?>): HtspServerMessage = HtspQueueStatusMessage(
    subscriptionId = fields.requiredU32("subscriptionId"),
    packetCount = fields.requiredU32("packets"),
    byteCount = fields.requiredU32("bytes"),
    delay = fields.optionalS64("delay"),
    bFrameDropCount = fields.requiredU32("Bdrops"),
    pFrameDropCount = fields.requiredU32("Pdrops"),
    iFrameDropCount = fields.requiredU32("Idrops"),
)

@JvmSynthetic
internal fun decodeSubscriptionStart(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionStartMessage(
        subscriptionId = fields.requiredServerAliasU32(SUBSCRIPTION_ID_KEYS),
        streams = fields.optionalObjectList("streams", ::decodeSubscriptionStream),
        sourceInfo = if (fields.containsKey("sourceinfo")) {
            decodeSubscriptionSourceInfo(fields.requiredObject("sourceinfo"))
        } else {
            null
        },
        codecMetadata = fields.optionalBinary("meta")?.let(::HtspBinary),
        status = fields.optionalServerAliasString(STATUS_KEYS),
        subscriptionError = fields.optionalServerAliasString(SUBSCRIPTION_ERROR_KEYS),
    )

@JvmSynthetic
internal fun decodeSubscriptionStop(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionStopMessage(
        subscriptionId = fields.requiredServerAliasU32(SUBSCRIPTION_ID_KEYS),
        status = fields.optionalServerAliasString(STATUS_KEYS),
        subscriptionError = fields.optionalServerAliasString(SUBSCRIPTION_ERROR_KEYS),
    )

@JvmSynthetic
internal fun decodeSubscriptionGrace(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionGraceMessage(
        subscriptionId = fields.requiredU32("subscriptionId"),
        graceTimeoutSeconds = fields.requiredU32("graceTimeout"),
    )

@JvmSynthetic
internal fun decodeSubscriptionStatus(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionStatusMessage(
        subscriptionId = fields.requiredServerAliasU32(SUBSCRIPTION_ID_KEYS),
        status = fields.optionalServerAliasString(STATUS_KEYS),
        subscriptionError = fields.optionalServerAliasString(SUBSCRIPTION_ERROR_KEYS),
    )

@JvmSynthetic
internal fun decodeSignalStatus(fields: Map<String, Any?>): HtspServerMessage = HtspSignalStatusMessage(
    subscriptionId = fields.requiredU32("subscriptionId"),
    frontendStatus = fields.optionalString("feStatus"),
    relativeSnr = fields.optionalU32("feSNR"),
    absoluteSnr = fields.optionalS64("feAbsoluteSNR"),
    relativeSignal = fields.optionalU32("feSignal"),
    absoluteSignal = fields.optionalS64("feAbsoluteSignal"),
    bitErrorRate = fields.optionalU32("feBER"),
    uncorrectedBlockCount = fields.optionalU32("feUNC"),
)

@JvmSynthetic
internal fun decodeDescrambleInfo(fields: Map<String, Any?>): HtspServerMessage =
    HtspDescrambleInfoMessage(
        subscriptionId = fields.requiredU32("subscriptionId"),
        pid = fields.requiredU32("pid"),
        conditionalAccessId = fields.requiredU32("caid"),
        providerId = fields.requiredU32("provid"),
        ecmTime = fields.requiredU32("ecmtime"),
        hopCount = fields.requiredU32("hops"),
        cardSystem = fields.optionalString("cardsystem"),
        reader = fields.optionalString("reader"),
        source = fields.optionalString("from"),
        protocol = fields.optionalString("protocol"),
    )

@JvmSynthetic
internal fun decodeSubscriptionSpeed(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionSpeedMessage(
        subscriptionId = fields.requiredU32("subscriptionId"),
        speed = fields.requiredS32("speed"),
    )

@JvmSynthetic
internal fun decodeTimeshiftStatus(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimeshiftStatusMessage(
        subscriptionId = fields.requiredU32("subscriptionId"),
        full = fields.requiredU32("full"),
        shift = fields.requiredS64("shift"),
        start = fields.optionalS64("start"),
        end = fields.optionalS64("end"),
        speed = fields.optionalS32("speed"),
    )

@JvmSynthetic
internal fun decodeSubscriptionSkip(fields: Map<String, Any?>): HtspServerMessage =
    HtspSubscriptionSkipMessage(
        subscriptionId = fields.requiredU32("subscriptionId"),
        absolute = fields.optionalU32("absolute"),
        error = fields.optionalU32("error"),
        time = fields.optionalS64("time"),
        sizeBytes = fields.optionalS64("size"),
    )

private fun decodeServerChannelService(fields: Map<*, *>): HtspChannelService = fields.server().run {
    HtspChannelService(
        name = requiredString("name"),
        type = requiredString("type"),
        content = requiredU32("content"),
        conditionalAccessId = optionalU32("caid"),
        conditionalAccessName = optionalString("caname"),
        providerName = optionalString("providername"),
    )
}

private fun decodeDvrRecordingFile(fields: Map<*, *>): HtspDvrRecordingFile = fields.server().run {
    HtspDvrRecordingFile(
        fileId = optionalU32("id"),
        path = fields.optionalServerAliasString(DVR_FILE_PATH_KEYS),
        start = optionalS64("start"),
        stop = optionalS64("stop"),
        sizeBytes = optionalS64("size"),
    )
}

private fun decodeServerEvent(fields: Map<*, *>): HtspEvent = HtspEvent(
    eventId = fields.requiredServerAliasU32(EVENT_ID_KEYS),
    channelId = fields.optionalServerAliasU32(EVENT_CHANNEL_KEYS),
    start = fields.requiredServerAliasS64(EVENT_START_KEYS),
    stop = fields.requiredServerAliasS64(EVENT_STOP_KEYS),
    title = fields.optionalServerAliasString(EVENT_TITLE_KEYS),
    subtitle = fields.optionalString("subtitle"),
    summary = fields.optionalString("summary"),
    description = fields.optionalString("description"),
    categories = fields.optionalServerEventCategories(),
    keywords = fields.optionalStringList("keyword"),
    seriesLinkUri = fields.optionalString("serieslinkUri"),
    episodeUri = fields.optionalString("episodeUri"),
    contentType = fields.optionalServerAliasU32(EVENT_CONTENT_KEYS),
    ageRating = fields.optionalU32("ageRating"),
    ratingLabel = fields.optionalString("ratingLabel"),
    ratingIcon = fields.optionalString("ratingIcon"),
    ratingAuthority = fields.optionalString("ratingAuthority"),
    ratingCountry = fields.optionalString("ratingCountry"),
    starRating = fields.optionalU32("starRating"),
    copyrightYear = fields.optionalU32("copyrightYear"),
    firstAired = fields.optionalS64("firstAired"),
    isNew = fields.optionalU32("isNew"),
    seasonNumber = fields.optionalServerEventU32(listOf("seasonNumber", "season"), listOf("seasonNumber", "season")),
    seasonCount = fields.optionalServerEventU32(listOf("seasonCount"), listOf("seasonCount", "count")),
    episodeNumber = fields.optionalServerEventU32(listOf("episodeNumber"), listOf("episodeNumber", "number")),
    episodeCount = fields.optionalServerEventU32(listOf("episodeCount"), listOf("episodeCount", "count")),
    partNumber = fields.optionalServerEventU32(listOf("partNumber", "part"), listOf("partNumber", "part")),
    partCount = fields.optionalServerEventU32(listOf("partCount"), listOf("partCount")),
    episodeOnscreen = fields.optionalString("episodeOnscreen"),
    image = fields.optionalString("image"),
    dvrId = fields.optionalU32("dvrId"),
    nextEventId = fields.optionalU32("nextEventId"),
)

private fun decodeSubscriptionStream(fields: Map<*, *>): HtspSubscriptionStream = fields.server().run {
    HtspSubscriptionStream(
        streamIndex = requiredU32("index"),
        streamType = requiredString("type"),
        language = optionalString("language"),
        compositionId = optionalU32("composition_id"),
        ancillaryId = optionalU32("ancillary_id"),
        width = optionalU32("width"),
        height = optionalU32("height"),
        frameDuration = optionalU32("duration"),
        aspectNumerator = optionalU32("aspect_num"),
        aspectDenominator = optionalU32("aspect_den"),
        audioType = optionalU32("audio_type"),
        audioVersion = optionalU32("audio_version"),
        channelCount = optionalU32("channels"),
        sampleRate = optionalU32("rate"),
        rdsUecp = optionalU32("rds_uecp"),
        codecMetadata = optionalBinary("meta")?.let(::HtspBinary),
    )
}

private fun decodeSubscriptionSourceInfo(fields: Map<*, *>): HtspSubscriptionSourceInfo = fields.server().run {
    HtspSubscriptionSourceInfo(
        adapterUuid = optionalString("adapter_uuid"),
        muxUuid = optionalString("mux_uuid"),
        networkUuid = optionalString("network_uuid"),
        adapter = optionalString("adapter"),
        mux = optionalString("mux"),
        network = optionalString("network"),
        networkType = optionalString("network_type"),
        provider = optionalString("provider"),
        service = optionalString("service"),
        satellitePosition = optionalString("satpos"),
    )
}

private inline fun <T> optionalTimerecValue(block: () -> T?): T? = try {
    block()
} catch (_: HtspServerMessageMappingException) {
    null
}

private fun Map<*, *>.server(): HtspFieldReader =
    HtspFieldReader(this) { throw HtspServerMessageMappingException() }

private fun Map<*, *>.firstPresentServerName(names: List<String>): String? =
    names.firstOrNull(::containsKey)

private fun Map<*, *>.requiredServerAliasS64(names: List<String>): Long =
    requiredS64(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasS64(names: List<String>): Long? =
    firstPresentServerName(names)?.let(::requiredS64)

private fun Map<*, *>.requiredServerAliasU32(names: List<String>): Long =
    requiredU32(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasU32(names: List<String>): Long? =
    firstPresentServerName(names)?.let(::requiredU32)

private fun Map<*, *>.requiredServerAliasString(names: List<String>): String =
    requiredString(firstPresentServerName(names) ?: throw HtspServerMessageMappingException())

private fun Map<*, *>.optionalServerAliasString(names: List<String>): String? =
    firstPresentServerName(names)?.let(::requiredString)

private fun Map<*, *>.optionalServerAliasU32List(names: List<String>): List<Long>? =
    firstPresentServerName(names)?.let(::requiredU32List)

private fun Map<*, *>.optionalServerEventGenre(): String? {
    if (containsKey("genre")) return requiredString("genre")
    if (!containsKey("category")) return null
    return when (this["category"]) {
        is String -> requiredString("category")
        is List<*> -> null
        else -> throw HtspServerMessageMappingException()
    }
}

private fun Map<*, *>.optionalServerEventCategories(): List<String>? {
    if (!containsKey("category")) return null
    return when (this["category"]) {
        is String -> null
        is List<*> -> requiredStringList("category")
        else -> throw HtspServerMessageMappingException()
    }
}

private fun Map<*, *>.optionalServerEventU32(
    topLevelNames: List<String>,
    nestedNames: List<String>,
): Long? {
    optionalServerAliasU32(topLevelNames)?.let { return it }
    if (!containsKey("episode")) return null
    return requiredObject("episode").optionalServerAliasU32(nestedNames)
}

private fun Map<*, *>.requiredS64(name: String): Long =
    server().requiredS64(name)

private fun Map<*, *>.optionalS64(name: String): Long? =
    server().optionalS64(name)

private fun Map<*, *>.requiredS32(name: String): Int = server().requiredS32(name)

private fun Map<*, *>.optionalS32(name: String): Int? =
    server().optionalS32(name)

private fun Map<*, *>.requiredBoundedInt(name: String, range: IntRange): Int =
    server().requiredBoundedInt(name, range)

private fun Map<*, *>.optionalBoundedInt(name: String, range: IntRange): Int? =
    server().optionalBoundedInt(name, range)

private fun Map<*, *>.requiredFlag(name: String): Boolean = server().requiredFlag(name)

private fun Map<*, *>.optionalFlag(name: String): Boolean? =
    server().optionalFlag(name)

private fun Map<*, *>.requiredU32(name: String): Long = server().requiredU32(name)

private fun Map<*, *>.optionalU32(name: String): Long? =
    server().optionalU32(name)

private fun Map<*, *>.requiredString(name: String): String =
    server().requiredString(name)

private fun Map<*, *>.optionalString(name: String): String? =
    server().optionalString(name)

private fun Map<*, *>.requiredBinary(name: String): ByteArray =
    server().requiredBinary(name)

private fun Map<*, *>.optionalBinary(name: String): ByteArray? =
    server().optionalBinary(name)

private fun Map<*, *>.requiredObject(name: String): Map<*, *> =
    server().requiredObject(name)

private fun Map<*, *>.requiredU32List(name: String): List<Long> = server().requiredU32List(name)

private fun Map<*, *>.optionalU32List(name: String): List<Long>? =
    server().optionalU32List(name)

private fun Map<*, *>.requiredStringList(name: String): List<String> =
    server().requiredStringList(name)

private fun Map<*, *>.optionalStringList(name: String): List<String>? =
    server().optionalStringList(name)

private fun <T> Map<*, *>.requiredObjectList(
    name: String,
    mapper: (Map<*, *>) -> T,
): List<T> {
    return server().requiredObjectList(name, mapper)
}

private fun <T> Map<*, *>.optionalObjectList(
    name: String,
    mapper: (Map<*, *>) -> T,
): List<T>? = server().optionalObjectList(name, mapper)
