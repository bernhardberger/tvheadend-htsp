package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.model.HtspEntityDecoder
import at.bernhardberger.tvheadend.htsp.wire.*

private val NOW_NEXT_FIELDS = setOf("method", "channelId", "eventId", "nextEventId")
private val DVR_STATS_FIELDS = setOf(
    "method", "id", "idStr", "state", "duplicate", "error", "subscriptionError", "streamErrors", "dataErrors", "dataSize",
)

private class `HtspServerMessageMappingException-internal` : IllegalArgumentException()
private typealias HtspServerMessageMappingException = `HtspServerMessageMappingException-internal`
private fun malformed(): Nothing = throw HtspServerMessageMappingException()
private fun entities(version: Long): HtspEntityDecoder = HtspEntityDecoder(version, ::malformed)

@JvmSynthetic
internal fun decodeChannelAdd(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspChannelAddMessage(entities(version).channel(fields))

@JvmSynthetic
internal fun decodeChannelUpdate(fields: Map<String, Any?>, version: Long): HtspServerMessage {
    if (fields.containsKey("channelName")) return HtspChannelUpdateMessage(entities(version).channel(fields))
    if (fields.keys.any { it !in NOW_NEXT_FIELDS }) malformed()
    return fields.server().run {
        HtspChannelNowNextMessage(requiredU32("channelId"), requiredU32("eventId"), requiredU32("nextEventId"))
    }
}

@JvmSynthetic
internal fun decodeChannelDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspChannelDeleteMessage(fields.server().requiredU32("channelId"))

@JvmSynthetic
internal fun decodeTagAdd(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspTagAddMessage(entities(version).tag(fields))

@JvmSynthetic
internal fun decodeTagUpdate(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspTagUpdateMessage(entities(version).tag(fields))

@JvmSynthetic
internal fun decodeTagDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspTagDeleteMessage(fields.server().requiredU32("tagId"))

@JvmSynthetic
internal fun decodeDvrEntryAdd(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspDvrEntryAddMessage(entities(version).dvrEntry(fields))

@JvmSynthetic
internal fun decodeDvrEntryUpdate(fields: Map<String, Any?>, version: Long): HtspServerMessage {
    if (fields.containsKey("enabled")) return HtspDvrEntryUpdateMessage(entities(version).dvrEntry(fields))
    if (fields.keys.any { it !in DVR_STATS_FIELDS }) malformed()
    return fields.server().run {
        HtspDvrEntryStatsMessage(
            entryId = requiredU32("id"),
            entryUuid = if (version >= 41) requiredString("idStr") else optionalString("idStr"),
            state = requiredString("state"), duplicate = optionalFlag("duplicate"),
            error = optionalString("error"),
            subscriptionError = optionalString("subscriptionError"), streamErrors = optionalU32("streamErrors"),
            dataErrors = optionalU32("dataErrors"), dataSizeBytes = optionalS64("dataSize"),
        )
    }
}

@JvmSynthetic
internal fun decodeDvrEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspDvrEntryDeleteMessage(fields.server().requiredU32("id"))

@JvmSynthetic
internal fun decodeAutorecEntryAdd(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspAutorecEntryAddMessage(entities(version).autorecEntry(fields))

@JvmSynthetic
internal fun decodeAutorecEntryUpdate(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspAutorecEntryUpdateMessage(entities(version).autorecEntry(fields))

@JvmSynthetic
internal fun decodeAutorecEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspAutorecEntryDeleteMessage(fields.server().requiredString("id"))

@JvmSynthetic
internal fun decodeTimerecEntryAdd(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspTimerecEntryAddMessage(entities(version).timerecEntry(fields))

@JvmSynthetic
internal fun decodeTimerecEntryUpdate(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspTimerecEntryUpdateMessage(entities(version).timerecEntry(fields))

@JvmSynthetic
internal fun decodeTimerecEntryDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspTimerecEntryDeleteMessage(fields.server().requiredString("id"))

@JvmSynthetic
internal fun decodeEventAdd(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspEventAddMessage(entities(version).event(fields))

@JvmSynthetic
internal fun decodeEventUpdate(fields: Map<String, Any?>, version: Long): HtspServerMessage =
    HtspEventUpdateMessage(entities(version).event(fields))

@JvmSynthetic
internal fun decodeEventDelete(fields: Map<String, Any?>): HtspServerMessage =
    HtspEventDeleteMessage(fields.server().requiredU32("eventId"))

@JvmSynthetic
internal fun decodeInitialSyncCompleted(fields: Map<String, Any?>): HtspServerMessage = HtspInitialSyncCompletedMessage

@JvmSynthetic
internal fun decodeMuxPacket(
    fields: Map<String, Any?>,
    timestampClockForSubscription: (Long) -> HtspTimestampClock,
    ownedPayload: ByteArray?,
): HtspServerMessage = fields.server().run {
    val subscriptionId = requiredU32("subscriptionId")
    val clock = timestampClockForSubscription(subscriptionId)
    try {
        val payload = requiredBinary("payload")
        HtspMuxPacketMessage(
            subscriptionId = subscriptionId,
            frameType = when (optionalS64("frametype")) {
                null, -1L, 0L -> HtspFrameType.UNKNOWN
                73L -> HtspFrameType.I
                80L -> HtspFrameType.P
                66L -> HtspFrameType.B
                else -> malformed()
            },
            streamIndex = requiredU32("stream"), decodingTimeUs = optionalS64("dts")?.let(clock::toMicroseconds),
            presentationTimeUs = optionalS64("pts")?.let(clock::toMicroseconds),
            durationUs = clock.toMicroseconds(requiredU32("duration")),
            commercialAdvice = optionalU32("com")?.let {
                when (it) {
                    0L -> HtspCommercialAdvice.UNKNOWN
                    1L -> HtspCommercialAdvice.YES
                    2L -> HtspCommercialAdvice.NO
                    else -> HtspCommercialAdvice.UNRECOGNIZED
                }
            },
            payload = if (payload === ownedPayload) HtspBinary.takeOwnership(payload) else HtspBinary(payload),
        )
    } catch (_: ArithmeticException) {
        malformed()
    }
}

@JvmSynthetic
internal fun decodeQueueStatus(
    fields: Map<String, Any?>,
    timestampClockForSubscription: (Long) -> HtspTimestampClock = { HtspTimestampClock.MICROSECONDS },
): HtspServerMessage = fields.server().run {
    HtspQueueStatusMessage(
        subscriptionId = requiredU32("subscriptionId"), packetCount = requiredU32("packets"),
        byteCount = requiredU32("bytes"), delayUs = optionalS64("delay")?.let {
            timestampClockForSubscription(requiredU32("subscriptionId")).normalizedMicroseconds(it)
        },
        bFrameDropCount = requiredU32("Bdrops"), pFrameDropCount = requiredU32("Pdrops"),
        iFrameDropCount = requiredU32("Idrops"), errorCount = optionalU32("errors"),
    )
}

@JvmSynthetic
internal fun decodeSubscriptionStart(
    fields: Map<String, Any?>,
    timestampClockForSubscription: (Long) -> HtspTimestampClock = { HtspTimestampClock.MICROSECONDS },
): HtspServerMessage = fields.server().run {
    HtspSubscriptionStartMessage(
        subscriptionId = requiredU32("subscriptionId"),
        streams = optionalObjectList("streams") {
            decodeSubscriptionStream(it, timestampClockForSubscription(requiredU32("subscriptionId")))
        },
        sourceInfo = if (contains("sourceinfo")) decodeSubscriptionSourceInfo(requiredObject("sourceinfo")) else null,
        codecMetadata = optionalBinary("meta")?.let(::HtspBinary),
    )
}

@JvmSynthetic
internal fun decodeSubscriptionStop(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspSubscriptionStopMessage(
        subscriptionId = requiredU32("subscriptionId"), status = optionalString("status"),
        subscriptionError = optionalString("subscriptionError"),
    )
}

@JvmSynthetic
internal fun decodeSubscriptionGrace(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspSubscriptionGraceMessage(requiredU32("subscriptionId"), requiredU32("graceTimeout"))
}

@JvmSynthetic
internal fun decodeSubscriptionStatus(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspSubscriptionStatusMessage(
        subscriptionId = requiredU32("subscriptionId"), status = optionalString("status"),
        subscriptionError = optionalString("subscriptionError"),
    )
}

@JvmSynthetic
internal fun decodeSignalStatus(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspSignalStatusMessage(
        subscriptionId = requiredU32("subscriptionId"), frontendStatus = optionalString("feStatus"),
        relativeSnr = optionalU32("feSNR"), absoluteSnr = optionalS64("feAbsoluteSNR"),
        relativeSignal = optionalU32("feSignal"), absoluteSignal = optionalS64("feAbsoluteSignal"),
        bitErrorRate = optionalU32("feBER"), uncorrectedBlockCount = optionalU32("feUNC"),
    )
}

@JvmSynthetic
internal fun decodeDescrambleInfo(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspDescrambleInfoMessage(
        subscriptionId = requiredU32("subscriptionId"), pid = requiredU32("pid"),
        conditionalAccessId = requiredU32("caid"), providerId = requiredU32("provid"),
        ecmTime = requiredU32("ecmtime"), hopCount = requiredU32("hops"), cardSystem = optionalString("cardsystem"),
        reader = optionalString("reader"), source = optionalString("from"), protocol = optionalString("protocol"),
    )
}

@JvmSynthetic
internal fun decodeSubscriptionSpeed(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspSubscriptionSpeedMessage(requiredU32("subscriptionId"), requiredS32("speed"))
}

@JvmSynthetic
internal fun decodeTimeshiftStatus(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspTimeshiftStatusMessage(
        subscriptionId = requiredU32("subscriptionId"), full = requiredFlag("full"), shift = requiredS64("shift"),
        start = optionalS64("start"), end = optionalS64("end"), speed = optionalS32("speed"),
    )
}

@JvmSynthetic
internal fun decodeSubscriptionSkip(fields: Map<String, Any?>): HtspServerMessage = fields.server().run {
    HtspSubscriptionSkipMessage(
        subscriptionId = requiredU32("subscriptionId"), absolute = optionalFlag("absolute"), error = optionalFlag("error"),
        time = optionalS64("time"), sizeBytes = optionalS64("size"),
    )
}

private fun decodeSubscriptionStream(fields: Map<*, *>, clock: HtspTimestampClock): HtspSubscriptionStream =
    fields.server().run {
        HtspSubscriptionStream(
            streamIndex = requiredU32("index"), streamType = requiredString("type"), language = optionalString("language"),
            compositionId = optionalU32("composition_id"), ancillaryId = optionalU32("ancillary_id"),
            width = optionalU32("width"), height = optionalU32("height"),
            frameDurationUs = optionalU32("duration")?.let(clock::normalizedMicroseconds),
            aspectNumerator = optionalU32("aspect_num"), aspectDenominator = optionalU32("aspect_den"),
            audioType = optionalU32("audio_type"), audioVersion = optionalU32("audio_version"),
            channelCount = optionalU32("channels"), sampleRate = optionalU32("rate"), rdsUecp = optionalFlag("rds_uecp"),
        )
    }

private fun HtspTimestampClock.normalizedMicroseconds(value: Long): Long = try {
    toMicroseconds(value)
} catch (_: ArithmeticException) {
    malformed()
}

private fun decodeSubscriptionSourceInfo(fields: Map<*, *>): HtspSubscriptionSourceInfo = fields.server().run {
    HtspSubscriptionSourceInfo(
        adapterUuid = optionalString("adapter_uuid"), muxUuid = optionalString("mux_uuid"),
        networkUuid = optionalString("network_uuid"), adapter = optionalString("adapter"), mux = optionalString("mux"),
        network = optionalString("network"), networkType = optionalString("network_type"), provider = optionalString("provider"),
        service = optionalString("service"), satellitePosition = optionalString("satpos"),
    )
}

private fun Map<*, *>.server(): HtspFieldReader = HtspFieldReader(this, ::malformed)
