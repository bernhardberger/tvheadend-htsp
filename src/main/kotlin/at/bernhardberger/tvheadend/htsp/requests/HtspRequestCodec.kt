package at.bernhardberger.tvheadend.htsp.requests

import at.bernhardberger.tvheadend.htsp.connection.HtspProtocolMappingException
import at.bernhardberger.tvheadend.htsp.jsonapi.*
import at.bernhardberger.tvheadend.htsp.wire.*
import java.util.Collections

@OptIn(HtspJsonApi::class)
internal object `HtspRequestCodecs-internal` {
    @JvmSynthetic
    internal fun encode(request: HtspRequest<*>): LinkedHashMap<String, Any?> = when (request) {
        is ApiRequest -> linkedMapOf<String, Any?>("path" to request.path)
            .putIfNotNull("args", request.args?.toWireValue())

        is HelloRequest -> linkedMapOf(
            "htspversion" to request.htspVersion,
            "clientname" to request.clientName,
        )

        is AuthenticateRequest -> linkedMapOf()

        is GetProfilesRequest -> linkedMapOf()

        is GetDiskSpaceRequest -> linkedMapOf()

        is GetSysTimeRequest -> linkedMapOf()

        is EnableAsyncMetadataRequest -> linkedMapOf<String, Any?>()
            .putIfNotNull("epg", request.epg)
            .putIfNotNull("lastUpdate", request.lastUpdate)
            .putIfNotNull("epgMaxTime", request.epgMaxTime)
            .putIfNotNull("language", request.language)

        is GetChannelRequest -> linkedMapOf("channelId" to request.channelId)

        is GetEventRequest -> linkedMapOf<String, Any?>("eventId" to request.eventId)
            .putIfNotNull("language", request.language)

        is GetEventsRequest -> linkedMapOf<String, Any?>()
            .putIfNotNull("channelId", request.channelId)
            .putIfNotNull("eventId", request.eventId)
            .putIfNotNull("language", request.language)
            .putIfNotNull("numFollowing", request.numFollowing)
            .putIfNotNull("maxTime", request.maxTime)

        is EpgQueryRequest -> linkedMapOf<String, Any?>("query" to request.query)
            .putIfNotNull("channelId", request.channelId)
            .putIfNotNull("tagId", request.tagId)
            .putIfNotNull("contentType", request.contentType)
            .putIfNotNull("language", request.language)
            .putIfNotNull("fulltext", request.fullText)
            .putIfNotNull("mergetext", request.mergeText)
            .putIfNotNull("full", request.full)
            .putIfNotNull("minduration", request.minDurationSeconds)
            .putIfNotNull("maxduration", request.maxDurationSeconds)

        is GetEpgObjectRequest -> linkedMapOf<String, Any?>("id" to request.id)
            .putIfNotNull(
                "type",
                request.objectType?.let { objectType ->
                    when (objectType) {
                        HtspEpgObjectType.BROADCAST -> 1L
                    }
                },
            )

        is GetDvrConfigsRequest -> linkedMapOf()

        is AddDvrEntryRequest -> linkedMapOf<String, Any?>().apply {
            if (request.selector is AddDvrEntrySelector.ExplicitChannelTime) {
                put("channelId", request.selector.channelId)
            }
            if (request.selector is AddDvrEntrySelector.Event) {
                put("eventId", request.selector.eventId)
            }
            putIfNotNull("configName", request.configName)
            putIfNotNull("language", request.language)
            if (request.selector is AddDvrEntrySelector.ExplicitChannelTime) {
                put("start", request.selector.start)
                put("stop", request.selector.stop)
            }
            putIfNotNull("title", request.title)
            putIfNotNull("subtitle", request.subtitle)
            putIfNotNull("summary", request.summary)
            putIfNotNull("description", request.description)
            putIfNotNull("ageRating", request.ageRating)
        }

        is UpdateDvrEntryRequest -> linkedMapOf<String, Any?>()
            .putIfNotNull("channelId", request.channelId)
            .putIfNotNull("configName", request.configName)
            .putIfNotNull("title", request.title)
            .putIfNotNull("subtitle", request.subtitle)
            .putIfNotNull("summary", request.summary)
            .putIfNotNull("description", request.description)
            .putIfNotNull("language", request.language)
            .putIfNotNull("comment", request.comment)
            .putIfNotNull("playcount", request.playCount)
            .putIfNotNull("playposition", request.playPosition)
            .putIfNotNull("enabled", request.enabled)
            .putIfNotNull("start", request.start)
            .putIfNotNull("stop", request.stop)
            .putIfNotNull("startExtra", request.startExtra)
            .putIfNotNull("stopExtra", request.stopExtra)
            .putIfNotNull("retention", request.retention)
            .putIfNotNull("removal", request.removal)
            .putIfNotNull("priority", request.priority)
            .putIfNotNull("ageRating", request.ageRating)
            .apply { put("id", request.entryId) }

        is StopDvrEntryRequest -> linkedMapOf("id" to request.entryId)

        is CancelDvrEntryRequest -> linkedMapOf("id" to request.entryId)

        is DeleteDvrEntryRequest -> linkedMapOf("id" to request.entryId)

        is AddAutorecEntryRequest -> linkedMapOf<String, Any?>("title" to request.title)
            .putRecordingRuleChannel(request.channel)
            .putIfNotNull("minduration", request.minDurationSeconds)
            .putIfNotNull("maxduration", request.maxDurationSeconds)
            .putIfNotNull("fulltext", request.fullText)
            .putIfNotNull("mergetext", request.mergeText)
            .putIfNotNull("dupDetect", request.duplicateDetection)
            .putIfNotNull("maxCount", request.maximumRecordingCount)
            .putIfNotNull("broadcastType", request.broadcastType)
            .putIfNotNull("startExtra", request.startExtraMinutes)
            .putIfNotNull("stopExtra", request.stopExtraMinutes)
            .putIfNotNull("serieslinkUri", request.seriesLinkUri)
            .putIfNotNull("approxTime", request.approximateStartMinutesSinceMidnight)
            .putIfNotNull("start", request.startMinutesSinceMidnight)
            .putIfNotNull("startWindow", request.startWindowEndMinutesSinceMidnight)
            .putIfNotNull("enabled", request.enabled?.toWireFlag())
            .putIfNotNull("retention", request.retentionDays)
            .putIfNotNull("removal", request.removalDays)
            .putIfNotNull("priority", request.priority)
            .putIfNotNull("name", request.name)
            .putIfNotNull("comment", request.comment)
            .putIfNotNull("directory", request.directory)
            .putIfNotNull("configName", request.configName)
            .putIfNotNull("daysOfWeek", request.daysOfWeekMask)

        is UpdateAutorecEntryRequest -> linkedMapOf<String, Any?>("id" to request.id)
            .putRecordingRuleChannel(request.channel)
            .putIfNotNull("minduration", request.minDurationSeconds)
            .putIfNotNull("maxduration", request.maxDurationSeconds)
            .putIfNotNull("fulltext", request.fullText)
            .putIfNotNull("mergetext", request.mergeText)
            .putIfNotNull("dupDetect", request.duplicateDetection)
            .putIfNotNull("maxCount", request.maximumRecordingCount)
            .putIfNotNull("broadcastType", request.broadcastType)
            .putIfNotNull("startExtra", request.startExtraMinutes)
            .putIfNotNull("stopExtra", request.stopExtraMinutes)
            .putIfNotNull("serieslinkUri", request.seriesLinkUri)
            .putIfNotNull("start", request.startMinutesSinceMidnight)
            .putIfNotNull("startWindow", request.startWindowEndMinutesSinceMidnight)
            .putIfNotNull("enabled", request.enabled?.toWireFlag())
            .putIfNotNull("retention", request.retentionDays)
            .putIfNotNull("removal", request.removalDays)
            .putIfNotNull("priority", request.priority)
            .putIfNotNull("name", request.name)
            .putIfNotNull("comment", request.comment)
            .putIfNotNull("directory", request.directory)
            .putIfNotNull("title", request.title)
            .putIfNotNull("configName", request.configName)
            .putIfNotNull("daysOfWeek", request.daysOfWeekMask)

        is DeleteAutorecEntryRequest -> linkedMapOf("id" to request.id)

        is AddTimerecEntryRequest -> linkedMapOf<String, Any?>("title" to request.title)
            .putRecordingRuleChannel(request.channel)
            .putIfNotNull("start", request.startMinutesSinceMidnight)
            .putIfNotNull("stop", request.stopMinutesSinceMidnight)
            .putIfNotNull("enabled", request.enabled?.toWireFlag())
            .putIfNotNull("retention", request.retentionDays)
            .putIfNotNull("removal", request.removalDays)
            .putIfNotNull("priority", request.priority)
            .putIfNotNull("name", request.name)
            .putIfNotNull("comment", request.comment)
            .putIfNotNull("directory", request.directory)
            .putIfNotNull("configName", request.configName)
            .putIfNotNull("daysOfWeek", request.daysOfWeekMask)

        is UpdateTimerecEntryRequest -> linkedMapOf<String, Any?>("id" to request.id)
            .putRecordingRuleChannel(request.channel)
            .putIfNotNull("start", request.startMinutesSinceMidnight)
            .putIfNotNull("stop", request.stopMinutesSinceMidnight)
            .putIfNotNull("enabled", request.enabled?.toWireFlag())
            .putIfNotNull("retention", request.retentionDays)
            .putIfNotNull("removal", request.removalDays)
            .putIfNotNull("priority", request.priority)
            .putIfNotNull("name", request.name)
            .putIfNotNull("comment", request.comment)
            .putIfNotNull("directory", request.directory)
            .putIfNotNull("title", request.title)
            .putIfNotNull("configName", request.configName)
            .putIfNotNull("daysOfWeek", request.daysOfWeekMask)

        is DeleteTimerecEntryRequest -> linkedMapOf("id" to request.id)

        is GetDvrCutpointsRequest -> linkedMapOf("id" to request.entryId)

        is GetTicketRequest -> when (val selector = request.selector) {
            is GetTicketSelector.Channel -> linkedMapOf("channelId" to selector.channelId)
            is GetTicketSelector.Dvr -> linkedMapOf("dvrId" to selector.dvrId)
        }

        is SubscribeRequest -> linkedMapOf<String, Any?>(
            "subscriptionId" to request.subscriptionId,
        ).apply {
            when (val channel = request.channel) {
                is SubscribeChannel.Id -> put("channelId", channel.channelId)
                is SubscribeChannel.Name -> put("channelName", channel.channelName)
            }
            putIfNotNull("profile", request.profile)
            putIfNotNull("weight", request.weight)
            putIfNotNull("90khz", request.ninetyKhz)
            putIfNotNull("timeshiftPeriod", request.timeshiftPeriodSeconds)
            putIfNotNull("queueDepth", request.queueDepth)
        }

        is UnsubscribeRequest -> linkedMapOf("subscriptionId" to request.subscriptionId)

        is SubscriptionChangeWeightRequest -> linkedMapOf<String, Any?>("subscriptionId" to request.subscriptionId)
            .putIfNotNull("weight", request.weight)

        is SubscriptionSeekRequest -> linkedMapOf<String, Any?>(
            "subscriptionId" to request.subscriptionId,
        ).apply {
            when (val position = request.position) {
                is SubscriptionSeekPosition.Time -> put("time", position.time)
                is SubscriptionSeekPosition.Size -> put("size", position.size)
            }
            putIfNotNull("absolute", request.absolute)
        }

        is SubscriptionSkipRequest -> linkedMapOf<String, Any?>(
            "subscriptionId" to request.subscriptionId,
        ).apply {
            when (val position = request.position) {
                is SubscriptionSeekPosition.Time -> put("time", position.time)
                is SubscriptionSeekPosition.Size -> put("size", position.size)
            }
            putIfNotNull("absolute", request.absolute)
        }

        is SubscriptionSpeedRequest -> linkedMapOf(
            "subscriptionId" to request.subscriptionId,
            "speed" to request.speed,
        )

        is SubscriptionLiveRequest -> linkedMapOf("subscriptionId" to request.subscriptionId)

        is SubscriptionFilterStreamRequest -> linkedMapOf<String, Any?>("subscriptionId" to request.subscriptionId)
            .putIfNotNull("enable", request.enable)
            .putIfNotNull("disable", request.disable)

        is FileOpenRequest -> linkedMapOf("file" to request.file)

        is FileReadRequest -> linkedMapOf<String, Any?>(
            "id" to request.id,
            "size" to request.size,
        )
            .putIfNotNull("offset", request.offset)

        is FileCloseRequest -> linkedMapOf<String, Any?>("id" to request.id)
            .putIfNotNull("playposition", request.playPositionSeconds)
            .putIfNotNull("playcount", request.playCount)

        is FileStatRequest -> linkedMapOf("id" to request.id)

        is FileSeekRequest -> linkedMapOf<String, Any?>(
            "id" to request.id,
            "offset" to request.offset,
        ).putIfNotNull(
            "whence",
            request.whence?.let { whence ->
                when (whence) {
                    FileSeekWhence.SET -> "SEEK_SET"
                    FileSeekWhence.CURRENT -> "SEEK_CUR"
                    FileSeekWhence.END -> "SEEK_END"
                }
            },
        )

        else -> malformedReply()
    }

    @JvmSynthetic
    @Suppress("UNCHECKED_CAST")
    internal fun <R> decode(
        request: HtspRequest<R>,
        fields: Map<String, Any?>,
        protocolVersion: Int,
    ): R = when (request) {
        is ApiRequest -> decodeApiResponse(fields)

        is HelloRequest -> HelloResponse(
            htspVersion = fields.requiredU32("htspversion"),
            serverName = fields.observedString("servername"),
            serverVersion = fields.observedString("serverversion"),
            challenge = HtspBinary(
                fields.requiredBinary("challenge").also { challenge ->
                    if (challenge.size != 32) malformedReply()
                },
            ),
            webRoot = fields.observedString("webroot"),
            language = fields.observedString("language"),
            serverCapabilities = fields.observedStringList("servercapability"),
            apiVersion = fields.observedU32("api_version"),
        )

        is AuthenticateRequest -> AuthenticateResponse(
            noAccess = fields.observedFlag("noaccess"),
            admin = fields.observedFlag("admin"),
            streaming = fields.observedFlag("streaming"),
            dvr = fields.observedFlag("dvr"),
            failedDvr = fields.observedFlag("faileddvr"),
            anonymous = fields.observedFlag("anonymous"),
            limitAll = fields.observedU32("limitall"),
            limitDvr = fields.observedU32("limitdvr"),
            limitStreaming = fields.observedU32("limitstreaming"),
            uiLevel = fields.observedU32("uilevel"),
            uiLanguage = fields.observedString("uilanguage"),
        )

        is GetProfilesRequest -> GetProfilesResponse(fields.optionalObjectList("profiles", ::profileFromFields))

        is GetDiskSpaceRequest -> GetDiskSpaceResponse(
            freeBytes = fields.requiredS64("freediskspace"),
            usedBytes = fields.optionalS64("useddiskspace"),
            totalBytes = fields.requiredS64("totaldiskspace"),
        )

        is GetSysTimeRequest -> GetSysTimeResponse(
            // Pinned v44 source emits s32 even though the official method page says s64.
            unixTimeSeconds = fields.requiredS32("time").toLong(),
            legacyTimezoneHoursWestOfGmt = fields.requiredS32("timezone"),
            gmtOffsetMinutes = fields.optionalS32("gmtoffset"),
        )

        is EnableAsyncMetadataRequest -> HtspEmptyResponse

        is GetChannelRequest -> decodeChannel(fields, protocolVersion)

        is GetEventRequest -> GetEventResponse(eventFromFields(fields))

        is GetEventsRequest -> GetEventsResponse(fields.requiredObjectList("events", ::eventFromFields))

        is EpgQueryRequest -> decodeEpgQuery(request, fields)

        is GetEpgObjectRequest -> GetEpgObjectResponse(epgBroadcastObjectFromFields(fields))

        is GetDvrConfigsRequest -> GetDvrConfigsResponse(fields.optionalObjectList("dvrconfigs", ::dvrConfigFromFields))

        is AddDvrEntryRequest -> decodeDvrMutation(fields) { success, error ->
            val entryId = if (fields.containsKey("id")) {
                fields.optionalU32("id")
            } else {
                fields.optionalU32("dvrId")
            }
            AddDvrEntryResponse(success, entryId, error)
        }

        is UpdateDvrEntryRequest -> decodeDvrMutation(fields, ::UpdateDvrEntryResponse)

        is StopDvrEntryRequest -> decodeDvrMutation(fields, ::StopDvrEntryResponse)

        is CancelDvrEntryRequest -> decodeDvrMutation(fields, ::CancelDvrEntryResponse)

        is DeleteDvrEntryRequest -> decodeDvrMutation(fields, ::DeleteDvrEntryResponse)

        is AddAutorecEntryRequest -> decodeRecordingRuleAdd(fields, ::AddAutorecEntryResponse)

        is UpdateAutorecEntryRequest -> decodeRecordingRuleAcknowledgement(fields, UpdateAutorecEntryResponse)

        is DeleteAutorecEntryRequest -> decodeRecordingRuleAcknowledgement(fields, DeleteAutorecEntryResponse)

        is AddTimerecEntryRequest -> decodeRecordingRuleAdd(fields, ::AddTimerecEntryResponse)

        is UpdateTimerecEntryRequest -> decodeRecordingRuleAcknowledgement(fields, UpdateTimerecEntryResponse)

        is DeleteTimerecEntryRequest -> decodeRecordingRuleAcknowledgement(fields, DeleteTimerecEntryResponse)

        is GetDvrCutpointsRequest -> GetDvrCutpointsResponse(
            fields.optionalObjectList("cutpoints") { cutpoint ->
                HtspDvrCutpoint(
                    start = cutpoint.requiredU32("start"),
                    end = cutpoint.requiredU32("end"),
                    type = cutpoint.requiredU32("type"),
                )
            },
        )

        is GetTicketRequest -> GetTicketResponse(
            path = fields.requiredString("path"),
            ticket = fields.requiredString("ticket"),
        )

        is SubscribeRequest -> SubscribeResponse(
            ninetyKhz = fields.optionalU32("90khz"),
            normalizedTimestamps = fields.optionalU32("normts"),
            weight = fields.optionalU32("weight"),
            timeshiftPeriodSeconds = fields.optionalU32("timeshiftPeriod"),
        )

        is UnsubscribeRequest -> HtspEmptyResponse

        is SubscriptionChangeWeightRequest -> HtspEmptyResponse

        is SubscriptionSeekRequest -> HtspEmptyResponse

        is SubscriptionSkipRequest -> {
            if (fields.keys.any { it != "seq" }) malformedReply()
            HtspEmptyResponse
        }

        is SubscriptionSpeedRequest -> HtspEmptyResponse

        is SubscriptionLiveRequest -> HtspEmptyResponse

        is SubscriptionFilterStreamRequest -> HtspEmptyResponse

        is FileOpenRequest -> decodeFileOpen(fields)

        is FileReadRequest -> FileReadResponse(HtspBinary(fields.requiredBinary("data")))

        is FileCloseRequest -> decodeFileClose(fields)

        is FileStatRequest -> decodeFileStat(fields)

        is FileSeekRequest -> FileSeekResponse(
            offset = fields.requiredS64("offset").also { offset ->
                if (offset < 0L) malformedReply()
            },
        )

        else -> malformedReply()
    } as R

    private fun decodeChannel(fields: Map<String, Any?>, protocolVersion: Int): GetChannelResponse {
        val channelUuid = fields.optionalString("channelIdStr")
        if (protocolVersion >= 41 && channelUuid == null) malformedReply()
        val services = fields.requiredObjectList("services") { service ->
            HtspChannelService(
                name = service.requiredString("name"),
                type = service.requiredString("type"),
                content = service.requiredU32("content"),
                conditionalAccessId = service.optionalU32("caid"),
                conditionalAccessName = service.optionalString("caname"),
                providerName = service.optionalString("providername"),
            )
        }
        return GetChannelResponse(
            HtspChannel(
                channelId = fields.requiredU32("channelId"),
                channelUuid = channelUuid,
                channelNumber = fields.requiredU32("channelNumber"),
                channelNumberMinor = fields.optionalU32("channelNumberMinor"),
                channelName = fields.requiredString("channelName"),
                channelIcon = fields.optionalString("channelIcon"),
                currentEventId = fields.requiredU32("eventId"),
                nextEventId = fields.requiredU32("nextEventId"),
                services = services,
                tagIds = fields.requiredU32List("tags"),
            ),
        )
    }

    private fun decodeApiResponse(fields: Map<String, Any?>): ApiResponse {
        val permitted = setOf("seq", "noaccess", "response")
        if (fields.keys.any { it !in permitted }) malformedReply()
        if (!fields.containsKey("response")) return ApiResponse.NoPayload
        val value = fields["response"].toApiValue()
        return ApiResponse.Payload(value as? HtspApiContainer ?: malformedReply())
    }

    private fun decodeEpgQuery(
        request: EpgQueryRequest,
        fields: Map<String, Any?>,
    ): EpgQueryResponse = if (request.full == null || request.full == 0L) {
        if (fields.containsKey("events")) malformedReply()
        EpgQueryResponse.EventIds(
            if (fields.containsKey("eventIds")) fields.requiredU32List("eventIds") else emptyList(),
        )
    } else {
        if (fields.containsKey("eventIds")) malformedReply()
        EpgQueryResponse.Events(
            if (fields.containsKey("events")) {
                fields.requiredObjectList("events", ::eventFromFields)
            } else {
                emptyList()
            },
        )
    }

    private fun decodeFileStat(fields: Map<String, Any?>): FileStatResponse {
        val hasSize = fields.containsKey("size")
        val hasModifiedAt = fields.containsKey("mtime")
        if (!hasSize && !hasModifiedAt) return FileStatResponse(null, null)
        if (!hasSize || !hasModifiedAt) malformedReply()
        val sizeBytes = fields.requiredS64("size")
        if (sizeBytes < 0L) malformedReply()
        return FileStatResponse(
            sizeBytes = sizeBytes,
            modifiedAtUnixSeconds = fields.requiredS64("mtime"),
        )
    }

    private fun decodeFileOpen(fields: Map<String, Any?>): FileOpenResponse {
        val hasSize = fields.containsKey("size")
        val hasModifiedAt = fields.containsKey("mtime")
        if (hasSize != hasModifiedAt) malformedReply()
        val sizeBytes = if (hasSize) fields.requiredS64("size") else null
        if (sizeBytes != null && sizeBytes < 0L) malformedReply()
        return FileOpenResponse(
            id = fields.requiredU32("id"),
            sizeBytes = sizeBytes,
            modifiedAtUnixSeconds = if (hasModifiedAt) fields.requiredS64("mtime") else null,
        )
    }

    private fun decodeFileClose(fields: Map<String, Any?>): FileCloseResponse {
        if (fields.keys.any { it != "seq" }) malformedReply()
        return FileCloseResponse
    }

    private fun <R> decodeDvrMutation(
        fields: Map<String, Any?>,
        response: (Long?, String?) -> R,
    ): R {
        val success = fields.optionalU32("success")
        val error = fields.optionalString("error")
        if (success == null && error == null) malformedReply()
        return response(success, error)
    }

    private fun <R> decodeRecordingRuleAdd(fields: Map<String, Any?>, response: (String) -> R): R {
        fields.requireStrictSuccess()
        return response(fields.requiredString("id"))
    }

    private fun <R> decodeRecordingRuleAcknowledgement(fields: Map<String, Any?>, response: R): R {
        fields.requireStrictSuccess()
        return response
    }
}

internal typealias HtspRequestCodecs = `HtspRequestCodecs-internal`

internal fun maxVersion(base: Int?, vararg selected: Int?): Int? =
    listOfNotNull(base, *selected).maxOrNull()

private fun <V> LinkedHashMap<String, Any?>.putIfNotNull(
    name: String,
    value: V?,
): LinkedHashMap<String, Any?> = apply {
    if (value != null) put(name, value)
}

private fun LinkedHashMap<String, Any?>.putRecordingRuleChannel(
    channel: HtspRecordingRuleChannel?,
): LinkedHashMap<String, Any?> = apply {
    when (channel) {
        null -> Unit
        is HtspRecordingRuleChannel.Id -> put("channelId", channel.channelId)
        HtspRecordingRuleChannel.Any -> put("channelId", -1L)
    }
}

private fun Boolean.toWireFlag(): Long = if (this) 1L else 0L

@OptIn(HtspJsonApi::class)
private fun HtspApiValue.toWireValue(): Any = when (this) {
    is HtspApiString -> value
    is HtspApiLong -> value
    is HtspApiBoolean -> value
    is HtspApiBinary -> bytes()
    is HtspApiUuid -> HtspWireUuid(bytes())
    is HtspApiObject -> LinkedHashMap<String, Any>(size).also { result ->
        forEachEntry { name, value -> result[name] = value.toWireValue() }
    }
    is HtspApiList -> ArrayList<Any>(size).also { result ->
        forEachValue { value -> result += value.toWireValue() }
    }
}

@OptIn(HtspJsonApi::class)
private fun Any?.toApiValue(): HtspApiValue = when (this) {
    is String -> HtspApiString(this)
    is Long -> HtspApiLong(this)
    is Boolean -> HtspApiBoolean(this)
    is ByteArray -> HtspApiBinary(this)
    is HtspWireUuid -> HtspApiUuid(bytes().also { if (it.size != 16) malformedReply() })
    is Map<*, *> -> {
        val result = ArrayList<Pair<String, HtspApiValue>>(size)
        forEach { (key, value) ->
            result += (key as? String ?: malformedReply()) to value.toApiValue()
        }
        HtspApiObject.create(result.toTypedArray())
    }
    is List<*> -> HtspApiList.create(map { value -> value.toApiValue() }.toTypedArray())
    else -> malformedReply()
}

internal fun AddAutorecEntryRequest.validateAutorecU32Fields() {
    validateAutorecU32Fields(
        minDurationSeconds, maxDurationSeconds, fullText, mergeText, duplicateDetection,
        maximumRecordingCount, broadcastType, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

internal fun UpdateAutorecEntryRequest.validateAutorecU32Fields() {
    validateAutorecU32Fields(
        minDurationSeconds, maxDurationSeconds, fullText, mergeText, duplicateDetection,
        maximumRecordingCount, broadcastType, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

private fun validateAutorecU32Fields(
    minDurationSeconds: Long?,
    maxDurationSeconds: Long?,
    fullText: Long?,
    mergeText: Long?,
    duplicateDetection: Long?,
    maximumRecordingCount: Long?,
    broadcastType: Long?,
    retentionDays: Long?,
    removalDays: Long?,
    priority: Long?,
    daysOfWeekMask: Long?,
) {
    minDurationSeconds?.let { requireU32("minduration", it) }
    maxDurationSeconds?.let { requireU32("maxduration", it) }
    fullText?.let { requireU32("fulltext", it) }
    mergeText?.let { requireU32("mergetext", it) }
    duplicateDetection?.let { requireU32("dupDetect", it) }
    maximumRecordingCount?.let { requireU32("maxCount", it) }
    broadcastType?.let { requireU32("broadcastType", it) }
    retentionDays?.let { requireU32("retention", it) }
    removalDays?.let { requireU32("removal", it) }
    priority?.let { requireU32("priority", it) }
    daysOfWeekMask?.let { requireU32("daysOfWeek", it) }
}

internal fun AddTimerecEntryRequest.validateTimerecU32Fields() {
    validateTimerecU32Fields(
        startMinutesSinceMidnight, stopMinutesSinceMidnight, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

internal fun UpdateTimerecEntryRequest.validateTimerecU32Fields() {
    validateTimerecU32Fields(
        startMinutesSinceMidnight, stopMinutesSinceMidnight, retentionDays, removalDays, priority, daysOfWeekMask,
    )
}

private fun validateTimerecU32Fields(
    startMinutesSinceMidnight: Long?,
    stopMinutesSinceMidnight: Long?,
    retentionDays: Long?,
    removalDays: Long?,
    priority: Long?,
    daysOfWeekMask: Long?,
) {
    startMinutesSinceMidnight?.let { requireU32("start", it) }
    stopMinutesSinceMidnight?.let { requireU32("stop", it) }
    retentionDays?.let { requireU32("retention", it) }
    removalDays?.let { requireU32("removal", it) }
    priority?.let { requireU32("priority", it) }
    daysOfWeekMask?.let { requireU32("daysOfWeek", it) }
}

private fun Map<*, *>.requireStrictSuccess() {
    if (requiredU32("success") != 1L) malformedReply()
}

private fun Map<*, *>.request(): HtspFieldReader = HtspFieldReader(this, ::malformedReply)

private fun Map<*, *>.requiredS64(name: String): Long = request().requiredS64(name)

private fun Map<*, *>.optionalS64(name: String): Long? =
    request().optionalS64(name)

private fun Map<*, *>.requiredS32(name: String): Int = request().requiredS32(name)

private fun Map<*, *>.optionalS32(name: String): Int? =
    request().optionalS32(name)

private fun Map<*, *>.requiredU32(name: String): Long = request().requiredU32(name)

private fun Map<*, *>.optionalU32(name: String): Long? =
    request().optionalU32(name)

private fun Map<*, *>.requiredString(name: String): String =
    request().requiredString(name)

private fun Map<*, *>.requiredBinary(name: String): ByteArray =
    request().requiredBinary(name)

private fun Map<*, *>.optionalString(name: String): String? =
    request().optionalString(name)

private fun Map<*, *>.observedString(name: String): String? = request().observedString(name)

private fun Map<*, *>.observedU32(name: String): Long? =
    request().observedU32(name)

private fun Map<*, *>.observedFlag(name: String): Boolean? = request().observedFlag(name)

private fun Map<*, *>.observedStringList(name: String): List<String>? = request().observedStringList(name)

private fun Map<*, *>.requiredU32List(name: String): List<Long> = request().requiredU32List(name)

private fun <R> Map<*, *>.requiredObjectList(
    name: String,
    mapper: (Map<*, *>) -> R,
): List<R> {
    return request().requiredObjectList(name, mapper)
}

private fun <R> Map<*, *>.optionalObjectList(
    name: String,
    mapper: (Map<*, *>) -> R,
): List<R>? = request().optionalObjectList(name, mapper)

private fun profileFromFields(fields: Map<*, *>): HtspProfile = fields.request().run {
    HtspProfile(
        profileUuid = requiredString("uuid"),
        name = requiredString("name"),
        comment = requiredString("comment"),
    )
}

private fun dvrConfigFromFields(fields: Map<*, *>): HtspDvrConfig = fields.request().run {
    HtspDvrConfig(
        dvrConfigUuid = requiredString("uuid"),
        name = requiredString("name"),
        comment = requiredString("comment"),
    )
}

private fun eventFromFields(fields: Map<*, *>): HtspEvent = fields.request().run {
    HtspEvent(
        eventId = requiredU32("eventId"),
        channelId = optionalU32("channelId"),
        start = requiredS64("start"),
        stop = requiredS64("stop"),
        title = optionalString("title"),
        subtitle = optionalString("subtitle"),
        summary = optionalString("summary"),
        description = optionalString("description"),
        categories = optionalStringList("category"),
        keywords = optionalStringList("keyword"),
        seriesLinkUri = optionalString("serieslinkUri"),
        episodeUri = optionalString("episodeUri"),
        contentType = optionalU32("contentType"),
        ageRating = optionalU32("ageRating"),
        ratingLabel = optionalString("ratingLabel"),
        ratingIcon = optionalString("ratingIcon"),
        ratingAuthority = optionalString("ratingAuthority"),
        ratingCountry = optionalString("ratingCountry"),
        starRating = optionalU32("starRating"),
        copyrightYear = optionalU32("copyrightYear"),
        firstAired = optionalS64("firstAired"),
        isNew = optionalU32("isNew"),
        seasonNumber = optionalU32("seasonNumber"),
        seasonCount = optionalU32("seasonCount"),
        episodeNumber = optionalU32("episodeNumber"),
        episodeCount = optionalU32("episodeCount"),
        partNumber = optionalU32("partNumber"),
        partCount = optionalU32("partCount"),
        episodeOnscreen = optionalString("episodeOnscreen"),
        image = optionalString("image"),
        dvrId = optionalU32("dvrId"),
        nextEventId = optionalU32("nextEventId"),
    )
}

private fun epgBroadcastObjectFromFields(fields: Map<*, *>): HtspEpgBroadcastObject {
    if (fields.requiredU32("tp") != 1L) malformedReply()
    return HtspEpgBroadcastObject(
        id = fields.requiredU32("id"),
        updatedUnixSeconds = fields.requiredS64("up"),
        startUnixSeconds = fields.requiredS64("start"),
        stopUnixSeconds = fields.requiredS64("stop"),
        grabber = fields.optionalString("gr"),
        channelUuid = fields.optionalString("ch"),
        eventId = fields.optionalU32("eid"),
        externalEventId = fields.optionalString("xeid"),
        widescreen = fields.optionalTrueFlag("is_wd"),
        highDefinition = fields.optionalTrueFlag("is_hd"),
        blackAndWhite = fields.optionalTrueFlag("is_bw"),
        deafSigned = fields.optionalTrueFlag("is_de"),
        subtitled = fields.optionalTrueFlag("is_st"),
        audioDescribed = fields.optionalTrueFlag("is_ad"),
        isNew = fields.optionalTrueFlag("is_n"),
        isRepeat = fields.optionalTrueFlag("is_r"),
        lines = fields.optionalU32("lines"),
        aspectRatio = fields.optionalU32("aspect"),
        starRating = fields.optionalU32("star"),
        ageRating = fields.optionalU32("age"),
        ratingLabel = fields.optionalString("ratlab"),
        image = fields.optionalString("img"),
        titles = fields.optionalStringMap("tit"),
        subtitles = fields.optionalStringMap("sti"),
        summaries = fields.optionalStringMap("sum"),
        descriptions = fields.optionalStringMap("des"),
        episodeNumber = fields.optionalEpgEpisodeNumber("epn"),
        genres = fields.optionalU32List("genre"),
        copyrightYear = fields.optionalU32("cyear"),
        firstAiredUnixSeconds = fields.optionalS64("fair"),
        categories = fields.optionalSortedUniqueStringList("cat"),
        keywords = fields.optionalSortedUniqueStringList("key"),
        seriesLinkUri = fields.optionalString("slink"),
        episodeLinkUri = fields.optionalString("elink"),
    )
}

private fun Map<*, *>.optionalTrueFlag(name: String): Boolean {
    if (!containsKey(name)) return false
    if (requiredU32(name) != 1L) malformedReply()
    return true
}

private fun Map<*, *>.optionalStringMap(name: String): Map<String, String>? {
    if (!containsKey(name)) return null
    val source = this[name] as? Map<*, *> ?: malformedReply()
    val result = LinkedHashMap<String, String>(source.size)
    source.forEach { (key, value) ->
        result[key as? String ?: malformedReply()] = value as? String ?: malformedReply()
    }
    return Collections.unmodifiableMap(result)
}

private fun Map<*, *>.optionalEpgEpisodeNumber(name: String): HtspEpgEpisodeNumber? {
    if (!containsKey(name)) return null
    val source = this[name] as? Map<*, *> ?: malformedReply()
    val result = HtspEpgEpisodeNumber(
        episodeNumber = source.optionalU32("enum"),
        episodeCount = source.optionalU32("ecnt"),
        seasonNumber = source.optionalU32("snum"),
        seasonCount = source.optionalU32("scnt"),
        partNumber = source.optionalU32("pnum"),
        partCount = source.optionalU32("pcnt"),
        text = source.optionalString("text"),
    )
    if (result == HtspEpgEpisodeNumber(null, null, null, null, null, null, null)) malformedReply()
    return result
}

private fun Map<*, *>.optionalU32List(name: String): List<Long>? =
    request().optionalU32List(name)

private fun Map<*, *>.optionalSortedUniqueStringList(name: String): List<String>? =
    request().optionalSortedUniqueStringList(name)

private fun Map<*, *>.optionalStringList(name: String): List<String>? = request().optionalStringList(name)

private fun malformedReply(): Nothing = throw HtspProtocolMappingException()
