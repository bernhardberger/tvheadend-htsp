package at.bernhardberger.tvheadend.htsp

/** Minimal full-builder shapes at the pinned v44 server, without optional observations. */
internal fun metadataFixture(method: String): Map<String, Any?> = mapOf("method" to method) + when (method) {
    "channelAdd", "channelUpdate" -> channelFixture(1L)
    "tagAdd", "tagUpdate" -> mapOf(
        "tagId" to 1L, "tagIdStr" to "tag-uuid", "tagIndex" to 0L,
        "tagName" to "Tag", "tagTitledIcon" to 0L,
    )
    "eventAdd", "eventUpdate" -> mapOf("eventId" to 1L, "start" to 2L, "stop" to 3L)
    "dvrEntryAdd", "dvrEntryUpdate" -> mapOf(
        "id" to 1L, "idStr" to "dvr-uuid", "enabled" to 1L, "start" to 2L, "stop" to 3L,
        "startExtra" to 0L, "stopExtra" to 0L, "retention" to 0L, "removal" to 0L,
        "priority" to 0L, "contentType" to 0L, "ageRating" to 0L, "state" to "scheduled",
    )
    "autorecEntryAdd", "autorecEntryUpdate" -> mapOf(
        "id" to "rule", "enabled" to 1L, "maxDuration" to 0L, "minDuration" to 0L,
        "retention" to 0L, "removal" to 0L, "daysOfWeek" to 0L, "approxTime" to -1L,
        "start" to -1L, "startWindow" to -1L, "priority" to 0L, "startExtra" to 0L,
        "stopExtra" to 0L, "dupDetect" to 0L, "maxCount" to 0L, "broadcastType" to 0L,
    )
    "timerecEntryAdd", "timerecEntryUpdate" -> mapOf(
        "id" to "rule", "enabled" to 1L, "start" to -1L, "stop" to -1L,
        "retention" to 0L, "removal" to 0L, "daysOfWeek" to 0L, "priority" to 0L,
    )
    else -> error("Not a full metadata method: $method")
}

internal fun channelFixture(id: Long): Map<String, Any?> = mapOf(
    "channelId" to id, "channelIdStr" to "channel-uuid", "channelName" to "Channel",
    "channelNumber" to 0L, "eventId" to 0L, "nextEventId" to 0L,
    "services" to emptyList<Any>(), "tags" to emptyList<Long>(),
)
