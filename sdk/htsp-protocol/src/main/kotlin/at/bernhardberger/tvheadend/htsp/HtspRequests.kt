package at.bernhardberger.tvheadend.htsp

import java.util.Collections

private const val U32_MAX: Long = 0xffff_ffffL

/** One stream-profile map returned by `getProfiles`. */
public data class HtspProfile(
    public val profileUuid: String,
    public val name: String,
    public val comment: String,
)

/** One DVR-configuration map returned by `getDvrConfigs`. */
public data class HtspDvrConfig(
    public val dvrConfigUuid: String,
    public val name: String,
    public val comment: String,
)

/** One bounded service map returned inside a channel reply. */
public data class HtspChannelService(
    public val name: String,
    public val type: String,
    public val content: Long,
    public val conditionalAccessId: Long?,
    public val conditionalAccessName: String?,
    public val providerName: String?,
)

/** Complete typed current-source channel reply. */
public data class HtspChannel(
    public val channelId: Long,
    public val channelUuid: String?,
    public val channelNumber: Long,
    public val channelNumberMinor: Long?,
    public val channelName: String,
    public val channelIcon: String?,
    public val currentEventId: Long,
    public val nextEventId: Long,
    public val services: List<HtspChannelService>,
    public val tagIds: List<Long>,
)

/** Complete typed current-source event reply, excluding deliberately opaque credits. */
public data class HtspEvent(
    public val eventId: Long,
    public val channelId: Long?,
    public val start: Long,
    public val stop: Long,
    public val title: String?,
    public val subtitle: String?,
    public val summary: String?,
    public val description: String?,
    public val categories: List<String>?,
    public val keywords: List<String>?,
    public val seriesLinkUri: String?,
    public val episodeUri: String?,
    public val contentType: Long?,
    public val ageRating: Long?,
    public val ratingLabel: String?,
    public val ratingIcon: String?,
    public val ratingAuthority: String?,
    public val ratingCountry: String?,
    public val starRating: Long?,
    public val copyrightYear: Long?,
    public val firstAired: Long?,
    public val isNew: Long?,
    public val seasonNumber: Long?,
    public val seasonCount: Long?,
    public val episodeNumber: Long?,
    public val episodeCount: Long?,
    public val partNumber: Long?,
    public val partCount: Long?,
    public val episodeOnscreen: String?,
    public val image: String?,
    public val dvrId: Long?,
    public val nextEventId: Long?,
)

/** One DVR cutpoint with the exact unsigned wire action code. */
public data class HtspDvrCutpoint(
    public val start: Long,
    public val end: Long,
    public val type: Long,
)

/** Explicit successful empty RPC acknowledgement. */
public data object HtspEmptyResponse

public data class GetProfilesResponse(public val profiles: List<HtspProfile>?)

public data class GetDiskSpaceResponse(
    public val freeBytes: Long,
    public val usedBytes: Long?,
    public val totalBytes: Long,
)

public data class GetSysTimeResponse(
    public val unixTimeSeconds: Long,
    public val legacyTimezoneHoursWestOfGmt: Int,
    public val gmtOffsetMinutes: Int?,
)

public data class GetChannelResponse(public val channel: HtspChannel)

public data class GetEventResponse(public val event: HtspEvent)

public data class GetEventsResponse(public val events: List<HtspEvent>)

public data class GetDvrConfigsResponse(public val configurations: List<HtspDvrConfig>?)

public data class AddDvrEntryResponse(
    public val success: Long,
    public val entryId: Long?,
)

public data class UpdateDvrEntryResponse(public val success: Long)

public data class StopDvrEntryResponse(public val success: Long)

public data class CancelDvrEntryResponse(public val success: Long)

public data class DeleteDvrEntryResponse(public val success: Long)

public data class GetDvrCutpointsResponse(public val cutpoints: List<HtspDvrCutpoint>?)

public data class SubscribeResponse(
    public val ninetyKhz: Long?,
    public val normalizedTimestamps: Long?,
    public val weight: Long?,
    public val timeshiftPeriodSeconds: Long?,
)

public class GetProfilesRequest : HtspRequest<GetProfilesResponse>(
    method = "getProfiles",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 16,
)

public class GetDiskSpaceRequest : HtspRequest<GetDiskSpaceResponse>(
    method = "getDiskSpace",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 3,
)

public class GetSysTimeRequest : HtspRequest<GetSysTimeResponse>(
    method = "getSysTime",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 3,
)

public data class GetChannelRequest(public val channelId: Long) : HtspRequest<GetChannelResponse>(
    method = "getChannel",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 14,
) {
    init {
        requireU32("channelId", channelId)
    }

}

public data class GetEventRequest(
    public val eventId: Long,
    public val language: String? = null,
) : HtspRequest<GetEventResponse>(
    method = "getEvent",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 6.takeIf { language != null },
) {
    init {
        requireU32("eventId", eventId)
    }

}

public data class GetEventsRequest(
    public val channelId: Long? = null,
    public val eventId: Long? = null,
    public val language: String? = null,
    public val numFollowing: Long? = null,
    public val maxTime: Long? = null,
) : HtspRequest<GetEventsResponse>(
    method = "getEvents",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = if (
        channelId != null || eventId != null || language != null || numFollowing != null || maxTime != null
    ) 6 else 4,
) {
    init {
        channelId?.let { requireU32("channelId", it) }
        eventId?.let { requireU32("eventId", it) }
        numFollowing?.let { requireU32("numFollowing", it) }
    }

}

public class GetDvrConfigsRequest : HtspRequest<GetDvrConfigsResponse>(
    method = "getDvrConfigs",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 16,
)

/** Valid `addDvrEntry` selector forms from pinned source and accepted event scheduling. */
public sealed interface AddDvrEntrySelector {
    public data class Event(public val eventId: Long) : AddDvrEntrySelector {
        init {
            requireU32("eventId", eventId)
        }
    }

    public data class ExplicitChannelTime(
        public val channelId: Long,
        public val start: Long,
        public val stop: Long,
    ) : AddDvrEntrySelector {
        init {
            requireU32("channelId", channelId)
        }
    }
}

public data class AddDvrEntryRequest(
    public val selector: AddDvrEntrySelector,
    public val configName: String? = null,
    public val language: String? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val ageRating: Long? = null,
) : HtspRequest<AddDvrEntryResponse>(
    method = "addDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(
        4,
        5.takeIf { selector is AddDvrEntrySelector.ExplicitChannelTime },
        6.takeIf { title != null },
        20.takeIf { subtitle != null },
        5.takeIf { description != null },
        36.takeIf { ageRating != null },
    ),
) {
    init {
        ageRating?.let { requireU32("ageRating", it) }
    }

}

public data class UpdateDvrEntryRequest(
    public val entryId: Long,
    public val channelId: Long? = null,
    public val configName: String? = null,
    public val title: String? = null,
    public val subtitle: String? = null,
    public val summary: String? = null,
    public val description: String? = null,
    public val language: String? = null,
    public val comment: String? = null,
    public val playCount: Long? = null,
    public val playPosition: Long? = null,
    public val enabled: Long? = null,
    public val start: Long? = null,
    public val stop: Long? = null,
    public val startExtra: Long? = null,
    public val stopExtra: Long? = null,
    public val retention: Long? = null,
    public val removal: Long? = null,
    public val priority: Long? = null,
    public val ageRating: Long? = null,
) : HtspRequest<UpdateDvrEntryResponse>(
    method = "updateDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = maxVersion(
        5,
        22.takeIf { channelId != null },
        21.takeIf { subtitle != null },
        6.takeIf { description != null },
        42.takeIf { comment != null },
        27.takeIf { playCount != null || playPosition != null },
        23.takeIf { enabled != null },
        6.takeIf { startExtra != null || stopExtra != null },
        13.takeIf { retention != null || priority != null },
        36.takeIf { ageRating != null },
    ),
) {
    init {
        requireU32("id", entryId)
        channelId?.let { requireU32("channelId", it) }
        playCount?.let { requireU32("playCount", it) }
        playPosition?.let { requireU32("playPosition", it) }
        retention?.let { requireU32("retention", it) }
        removal?.let { requireU32("removal", it) }
        priority?.let { requireU32("priority", it) }
        ageRating?.let { requireU32("ageRating", it) }
    }

}

public data class StopDvrEntryRequest(public val entryId: Long) : HtspRequest<StopDvrEntryResponse>(
    method = "stopDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = null,
) {
    init {
        requireU32("id", entryId)
    }
}

public data class CancelDvrEntryRequest(public val entryId: Long) : HtspRequest<CancelDvrEntryResponse>(
    method = "cancelDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 5,
) {
    init {
        requireU32("id", entryId)
    }
}

public data class DeleteDvrEntryRequest(public val entryId: Long) : HtspRequest<DeleteDvrEntryResponse>(
    method = "deleteDvrEntry",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 4,
) {
    init {
        requireU32("id", entryId)
    }
}

public data class GetDvrCutpointsRequest(public val entryId: Long) : HtspRequest<GetDvrCutpointsResponse>(
    method = "getDvrCutpoints",
    access = HtspAccess.ACCESS_HTSP_RECORDER,
    minimumProtocolVersion = 12,
) {
    init {
        requireU32("id", entryId)
    }
}

/** Exactly one channel selector accepted by the pinned `subscribe` handler. */
public sealed interface SubscribeChannel {
    public data class Id(public val channelId: Long) : SubscribeChannel {
        init {
            requireU32("channelId", channelId)
        }
    }

    public data class Name(public val channelName: String) : SubscribeChannel
}

public data class SubscribeRequest(
    public val subscriptionId: Long,
    public val channel: SubscribeChannel,
    public val profile: String? = null,
    public val weight: Long? = null,
    public val ninetyKhz: Long? = null,
    public val timeshiftPeriodSeconds: Long? = null,
    public val queueDepth: Long? = null,
) : HtspRequest<SubscribeResponse>(
    method = "subscribe",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = maxVersion(
        null,
        16.takeIf { profile != null },
        7.takeIf { ninetyKhz != null || queueDepth != null },
        9.takeIf { timeshiftPeriodSeconds != null },
    ),
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        weight?.let { requireU32("weight", it) }
        ninetyKhz?.let { requireU32("90khz", it) }
        timeshiftPeriodSeconds?.let { requireU32("timeshiftPeriod", it) }
        queueDepth?.let { requireU32("queueDepth", it) }
    }

}

public data class UnsubscribeRequest(
    public val subscriptionId: Long,
) : HtspRequest<HtspEmptyResponse>(
    method = "unsubscribe",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = null,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

}

public data class SubscriptionChangeWeightRequest(
    public val subscriptionId: Long,
    public val weight: Long? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionChangeWeight",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 5,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        weight?.let { requireU32("weight", it) }
    }

}

/** Exactly one signed seek coordinate accepted by `subscriptionSeek`. */
public sealed interface SubscriptionSeekPosition {
    public data class Time(public val time: Long) : SubscriptionSeekPosition
    public data class Size(public val size: Long) : SubscriptionSeekPosition
}

public data class SubscriptionSeekRequest(
    public val subscriptionId: Long,
    public val position: SubscriptionSeekPosition,
    public val absolute: Long? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionSeek",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
        absolute?.let { requireU32("absolute", it) }
    }

}

public data class SubscriptionSpeedRequest(
    public val subscriptionId: Long,
    public val speed: Int,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionSpeed",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

}

public data class SubscriptionLiveRequest(
    public val subscriptionId: Long,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionLive",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 9,
) {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

}

public class SubscriptionFilterStreamRequest(
    public val subscriptionId: Long,
    enable: List<Long>? = null,
    disable: List<Long>? = null,
) : HtspRequest<HtspEmptyResponse>(
    method = "subscriptionFilterStream",
    access = HtspAccess.ACCESS_HTSP_STREAMING,
    minimumProtocolVersion = 12,
) {
    public val enable: List<Long>? = enable?.immutableSnapshot()
    public val disable: List<Long>? = disable?.immutableSnapshot()

    init {
        requireU32("subscriptionId", subscriptionId)
        this.enable?.forEach { requireU32("enable", it) }
        this.disable?.forEach { requireU32("disable", it) }
    }

}

internal object `HtspRequestCodecs-internal` {
    @JvmSynthetic
    internal fun encode(request: HtspRequest<*>): LinkedHashMap<String, Any?> = when (request) {
        is GetProfilesRequest,
        is GetDiskSpaceRequest,
        is GetSysTimeRequest,
        is GetDvrConfigsRequest,
        -> linkedMapOf()

        is GetChannelRequest -> linkedMapOf("channelId" to request.channelId)
        is GetEventRequest -> linkedMapOf<String, Any?>(
            "eventId" to request.eventId,
        ).putIfNotNull("language", request.language)

        is GetEventsRequest -> linkedMapOf<String, Any?>()
            .putIfNotNull("channelId", request.channelId)
            .putIfNotNull("eventId", request.eventId)
            .putIfNotNull("language", request.language)
            .putIfNotNull("numFollowing", request.numFollowing)
            .putIfNotNull("maxTime", request.maxTime)

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
        is GetDvrCutpointsRequest -> linkedMapOf("id" to request.entryId)
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
        is SubscriptionChangeWeightRequest -> linkedMapOf<String, Any?>(
            "subscriptionId" to request.subscriptionId,
        ).putIfNotNull("weight", request.weight)

        is SubscriptionSeekRequest -> linkedMapOf<String, Any?>(
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
        is SubscriptionFilterStreamRequest -> linkedMapOf<String, Any?>(
            "subscriptionId" to request.subscriptionId,
        ).putIfNotNull("enable", request.enable)
            .putIfNotNull("disable", request.disable)

        else -> malformedReply()
    }

    @JvmSynthetic
    @Suppress("UNCHECKED_CAST")
    internal fun <R> decode(
        request: HtspRequest<R>,
        fields: Map<String, Any?>,
        protocolVersion: Int,
    ): R = when (request) {
        is GetProfilesRequest ->
            GetProfilesResponse(fields.optionalObjectList("profiles", ::profileFromFields))

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

        is GetChannelRequest -> decodeChannel(fields, protocolVersion)
        is GetEventRequest -> GetEventResponse(eventFromFields(fields))
        is GetEventsRequest ->
            GetEventsResponse(fields.requiredObjectList("events", ::eventFromFields))

        is GetDvrConfigsRequest ->
            GetDvrConfigsResponse(fields.optionalObjectList("dvrconfigs", ::dvrConfigFromFields))

        is AddDvrEntryRequest -> AddDvrEntryResponse(
            success = fields.requiredU32("success"),
            entryId = fields.optionalU32("id"),
        )

        is UpdateDvrEntryRequest -> UpdateDvrEntryResponse(fields.requiredU32("success"))
        is StopDvrEntryRequest -> StopDvrEntryResponse(fields.requiredU32("success"))
        is CancelDvrEntryRequest -> CancelDvrEntryResponse(fields.requiredU32("success"))
        is DeleteDvrEntryRequest -> DeleteDvrEntryResponse(fields.requiredU32("success"))
        is GetDvrCutpointsRequest -> GetDvrCutpointsResponse(
            fields.optionalObjectList("cutpoints") { cutpoint ->
                HtspDvrCutpoint(
                    start = cutpoint.requiredU32("start"),
                    end = cutpoint.requiredU32("end"),
                    type = cutpoint.requiredU32("type"),
                )
            },
        )

        is SubscribeRequest -> SubscribeResponse(
            ninetyKhz = fields.optionalU32("90khz"),
            normalizedTimestamps = fields.optionalU32("normts"),
            weight = fields.optionalU32("weight"),
            timeshiftPeriodSeconds = fields.optionalU32("timeshiftPeriod"),
        )

        is UnsubscribeRequest,
        is SubscriptionChangeWeightRequest,
        is SubscriptionSeekRequest,
        is SubscriptionSpeedRequest,
        is SubscriptionLiveRequest,
        is SubscriptionFilterStreamRequest,
        -> HtspEmptyResponse

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
}

internal typealias HtspRequestCodecs = `HtspRequestCodecs-internal`

private fun requireU32(name: String, value: Long) {
    require(value in 0L..U32_MAX) { "$name must be in the HTSP u32 range" }
}

private fun maxVersion(base: Int?, vararg selected: Int?): Int? =
    listOfNotNull(base, *selected).maxOrNull()

private fun <V> LinkedHashMap<String, Any?>.putIfNotNull(
    name: String,
    value: V?,
): LinkedHashMap<String, Any?> = apply {
    if (value != null) put(name, value)
}

private fun Map<*, *>.requiredS64(name: String): Long =
    this[name] as? Long ?: malformedReply()

private fun Map<*, *>.optionalS64(name: String): Long? =
    if (containsKey(name)) requiredS64(name) else null

private fun Map<*, *>.requiredS32(name: String): Int {
    val value = requiredS64(name)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) malformedReply()
    return value.toInt()
}

private fun Map<*, *>.optionalS32(name: String): Int? =
    if (containsKey(name)) requiredS32(name) else null

private fun Map<*, *>.requiredU32(name: String): Long {
    val value = requiredS64(name)
    if (value !in 0L..U32_MAX) malformedReply()
    return value
}

private fun Map<*, *>.optionalU32(name: String): Long? =
    if (containsKey(name)) requiredU32(name) else null

private fun Map<*, *>.requiredString(name: String): String =
    this[name] as? String ?: malformedReply()

private fun Map<*, *>.optionalString(name: String): String? =
    if (containsKey(name)) requiredString(name) else null

private fun Map<*, *>.requiredU32List(name: String): List<Long> {
    val source = this[name] as? List<*> ?: malformedReply()
    return source.map { value ->
        val decoded = value as? Long ?: malformedReply()
        if (decoded !in 0L..U32_MAX) malformedReply()
        decoded
    }.immutableSnapshot()
}

private fun <R> Map<*, *>.requiredObjectList(
    name: String,
    mapper: (Map<*, *>) -> R,
): List<R> {
    val source = this[name] as? List<*> ?: malformedReply()
    return source.map { value -> mapper(value as? Map<*, *> ?: malformedReply()) }
        .immutableSnapshot()
}

private fun <R> Map<*, *>.optionalObjectList(
    name: String,
    mapper: (Map<*, *>) -> R,
): List<R>? = if (containsKey(name)) requiredObjectList(name, mapper) else null

private fun profileFromFields(fields: Map<*, *>): HtspProfile = HtspProfile(
    profileUuid = fields.requiredString("uuid"),
    name = fields.requiredString("name"),
    comment = fields.requiredString("comment"),
)

private fun dvrConfigFromFields(fields: Map<*, *>): HtspDvrConfig = HtspDvrConfig(
    dvrConfigUuid = fields.requiredString("uuid"),
    name = fields.requiredString("name"),
    comment = fields.requiredString("comment"),
)

private fun eventFromFields(fields: Map<*, *>): HtspEvent = HtspEvent(
    eventId = fields.requiredU32("eventId"),
    channelId = fields.optionalU32("channelId"),
    start = fields.requiredS64("start"),
    stop = fields.requiredS64("stop"),
    title = fields.optionalString("title"),
    subtitle = fields.optionalString("subtitle"),
    summary = fields.optionalString("summary"),
    description = fields.optionalString("description"),
    categories = fields.optionalStringList("category"),
    keywords = fields.optionalStringList("keyword"),
    seriesLinkUri = fields.optionalString("serieslinkUri"),
    episodeUri = fields.optionalString("episodeUri"),
    contentType = fields.optionalU32("contentType"),
    ageRating = fields.optionalU32("ageRating"),
    ratingLabel = fields.optionalString("ratingLabel"),
    ratingIcon = fields.optionalString("ratingIcon"),
    ratingAuthority = fields.optionalString("ratingAuthority"),
    ratingCountry = fields.optionalString("ratingCountry"),
    starRating = fields.optionalU32("starRating"),
    copyrightYear = fields.optionalU32("copyrightYear"),
    firstAired = fields.optionalS64("firstAired"),
    isNew = fields.optionalU32("isNew"),
    seasonNumber = fields.optionalU32("seasonNumber"),
    seasonCount = fields.optionalU32("seasonCount"),
    episodeNumber = fields.optionalU32("episodeNumber"),
    episodeCount = fields.optionalU32("episodeCount"),
    partNumber = fields.optionalU32("partNumber"),
    partCount = fields.optionalU32("partCount"),
    episodeOnscreen = fields.optionalString("episodeOnscreen"),
    image = fields.optionalString("image"),
    dvrId = fields.optionalU32("dvrId"),
    nextEventId = fields.optionalU32("nextEventId"),
)

private fun Map<*, *>.optionalStringList(name: String): List<String>? {
    if (!containsKey(name)) return null
    val source = this[name] as? List<*> ?: malformedReply()
    return source.map { value -> value as? String ?: malformedReply() }.immutableSnapshot()
}

private fun <T> List<T>.immutableSnapshot(): List<T> =
    Collections.unmodifiableList(ArrayList(this))

private fun malformedReply(): Nothing = throw HtspProtocolMappingException()
