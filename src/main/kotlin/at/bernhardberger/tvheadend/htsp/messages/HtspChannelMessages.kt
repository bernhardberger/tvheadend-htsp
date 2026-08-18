package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.requests.HtspChannelService
import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Carries a channel-add message whose only required identity field is [channelId]; nullable channel metadata was absent when null. */
public class HtspChannelAddMessage(
    public val channelId: Long,
    public val channelName: String? = null,
    public val channelUuid: String? = null,
    public val channelNumber: Long? = null,
    public val channelNumberMinor: Long? = null,
    public val channelIcon: String? = null,
    public val currentEventId: Long? = null,
    public val nextEventId: Long? = null,
    services: List<HtspChannelService>? = null,
    tagIds: List<Long>? = null,
) : HtspServerMessage {
    public val services: List<HtspChannelService>? = services?.immutableSnapshot()
    public val tagIds: List<Long>? = tagIds?.immutableSnapshot()

    init {
        requireU32("channelId", channelId)
        channelNumber?.let { requireU32("channelNumber", it) }
        channelNumberMinor?.let { requireU32("channelNumberMinor", it) }
        currentEventId?.let { requireU32("currentEventId", it) }
        nextEventId?.let { requireU32("nextEventId", it) }
        this.tagIds?.forEach { requireU32("tagIds", it) }
        this.services?.forEach { service ->
            requireU32("service.content", service.content)
            service.conditionalAccessId?.let { requireU32("service.conditionalAccessId", it) }
        }
    }
}

/** Carries a channel update identified by [channelId]; nullable metadata, services, tags, and event references were absent when null. */
public class HtspChannelUpdateMessage(
    public val channelId: Long,
    public val channelUuid: String? = null,
    public val channelNumber: Long? = null,
    public val channelNumberMinor: Long? = null,
    public val channelName: String? = null,
    public val channelIcon: String? = null,
    public val currentEventId: Long? = null,
    public val nextEventId: Long? = null,
    services: List<HtspChannelService>? = null,
    tagIds: List<Long>? = null,
) : HtspServerMessage {
    public val services: List<HtspChannelService>? = services?.immutableSnapshot()
    public val tagIds: List<Long>? = tagIds?.immutableSnapshot()

    init {
        requireU32("channelId", channelId)
        channelNumber?.let { requireU32("channelNumber", it) }
        channelNumberMinor?.let { requireU32("channelNumberMinor", it) }
        currentEventId?.let { requireU32("currentEventId", it) }
        nextEventId?.let { requireU32("nextEventId", it) }
        this.tagIds?.forEach { requireU32("tagIds", it) }
        this.services?.forEach { service ->
            requireU32("service.content", service.content)
            service.conditionalAccessId?.let { requireU32("service.conditionalAccessId", it) }
        }
    }
}

/** Carries the complete unsigned [channelId] reported by a channel-delete message. */
public data class HtspChannelDeleteMessage(
    public val channelId: Long,
) : HtspServerMessage {
    init {
        requireU32("channelId", channelId)
    }
}

/** Carries channel-tag add metadata including identity, display order, names, icons, and current channel membership when present. */
public class HtspTagAddMessage(
    public val tagId: Long,
    public val tagName: String? = null,
    public val tagUuid: String? = null,
    public val tagIndex: Long? = null,
    public val tagIcon: String? = null,
    public val tagTitledIcon: Long? = null,
    channelIds: List<Long>? = null,
) : HtspServerMessage {
    public val channelIds: List<Long>? = channelIds?.immutableSnapshot()

    init {
        requireU32("tagId", tagId)
        tagIndex?.let { requireU32("tagIndex", it) }
        tagTitledIcon?.let { requireU32("tagTitledIcon", it) }
        this.channelIds?.forEach { requireU32("channelIds", it) }
    }
}

/** Carries a tag update identified by [tagId]; null properties and channel membership were absent from the message. */
public class HtspTagUpdateMessage(
    public val tagId: Long,
    public val tagUuid: String? = null,
    public val tagIndex: Long? = null,
    public val tagName: String? = null,
    public val tagIcon: String? = null,
    public val tagTitledIcon: Long? = null,
    channelIds: List<Long>? = null,
) : HtspServerMessage {
    public val channelIds: List<Long>? = channelIds?.immutableSnapshot()

    init {
        requireU32("tagId", tagId)
        tagIndex?.let { requireU32("tagIndex", it) }
        tagTitledIcon?.let { requireU32("tagTitledIcon", it) }
        this.channelIds?.forEach { requireU32("channelIds", it) }
    }
}

/** Carries the complete unsigned [tagId] reported by a channel-tag delete message. */
public data class HtspTagDeleteMessage(
    public val tagId: Long,
) : HtspServerMessage {
    init {
        requireU32("tagId", tagId)
    }
}
