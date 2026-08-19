package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.requests.HtspChannelService
import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Carries a channel-add message whose only required identity field is [channelId]; nullable channel metadata was absent when null. */
@ConsistentCopyVisibility
public data class HtspChannelAddMessage private constructor(
    public val channelId: Long,
    public val channelName: String? = null,
    public val channelUuid: String? = null,
    public val channelNumber: Long? = null,
    public val channelNumberMinor: Long? = null,
    public val channelIcon: String? = null,
    public val currentEventId: Long? = null,
    public val nextEventId: Long? = null,
    public val services: List<HtspChannelService>? = null,
    public val tagIds: List<Long>? = null,
    private val immutableSnapshot: Unit,
) : HtspServerMessage {
    public constructor(
        channelId: Long,
        channelName: String? = null,
        channelUuid: String? = null,
        channelNumber: Long? = null,
        channelNumberMinor: Long? = null,
        channelIcon: String? = null,
        currentEventId: Long? = null,
        nextEventId: Long? = null,
        services: List<HtspChannelService>? = null,
        tagIds: List<Long>? = null,
    ) : this(
        channelId = channelId,
        channelName = channelName,
        channelUuid = channelUuid,
        channelNumber = channelNumber,
        channelNumberMinor = channelNumberMinor,
        channelIcon = channelIcon,
        currentEventId = currentEventId,
        nextEventId = nextEventId,
        services = services?.immutableSnapshot(),
        tagIds = tagIds?.immutableSnapshot(),
        immutableSnapshot = Unit,
    )

    /** Returns a validated copy with immutable snapshots of replacement collections. */
    public fun copy(
        channelId: Long = this.channelId,
        channelName: String? = this.channelName,
        channelUuid: String? = this.channelUuid,
        channelNumber: Long? = this.channelNumber,
        channelNumberMinor: Long? = this.channelNumberMinor,
        channelIcon: String? = this.channelIcon,
        currentEventId: Long? = this.currentEventId,
        nextEventId: Long? = this.nextEventId,
        services: List<HtspChannelService>? = this.services,
        tagIds: List<Long>? = this.tagIds,
    ): HtspChannelAddMessage = HtspChannelAddMessage(
        channelId = channelId,
        channelName = channelName,
        channelUuid = channelUuid,
        channelNumber = channelNumber,
        channelNumberMinor = channelNumberMinor,
        channelIcon = channelIcon,
        currentEventId = currentEventId,
        nextEventId = nextEventId,
        services = services,
        tagIds = tagIds,
    )

    override fun toString(): String = "HtspChannelAddMessage(<redacted>)"

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
@ConsistentCopyVisibility
public data class HtspChannelUpdateMessage private constructor(
    public val channelId: Long,
    public val channelUuid: String? = null,
    public val channelNumber: Long? = null,
    public val channelNumberMinor: Long? = null,
    public val channelName: String? = null,
    public val channelIcon: String? = null,
    public val currentEventId: Long? = null,
    public val nextEventId: Long? = null,
    public val services: List<HtspChannelService>? = null,
    public val tagIds: List<Long>? = null,
    private val immutableSnapshot: Unit,
) : HtspServerMessage {
    public constructor(
        channelId: Long,
        channelUuid: String? = null,
        channelNumber: Long? = null,
        channelNumberMinor: Long? = null,
        channelName: String? = null,
        channelIcon: String? = null,
        currentEventId: Long? = null,
        nextEventId: Long? = null,
        services: List<HtspChannelService>? = null,
        tagIds: List<Long>? = null,
    ) : this(
        channelId = channelId,
        channelUuid = channelUuid,
        channelNumber = channelNumber,
        channelNumberMinor = channelNumberMinor,
        channelName = channelName,
        channelIcon = channelIcon,
        currentEventId = currentEventId,
        nextEventId = nextEventId,
        services = services?.immutableSnapshot(),
        tagIds = tagIds?.immutableSnapshot(),
        immutableSnapshot = Unit,
    )

    /** Returns a validated copy with immutable snapshots of replacement collections. */
    public fun copy(
        channelId: Long = this.channelId,
        channelUuid: String? = this.channelUuid,
        channelNumber: Long? = this.channelNumber,
        channelNumberMinor: Long? = this.channelNumberMinor,
        channelName: String? = this.channelName,
        channelIcon: String? = this.channelIcon,
        currentEventId: Long? = this.currentEventId,
        nextEventId: Long? = this.nextEventId,
        services: List<HtspChannelService>? = this.services,
        tagIds: List<Long>? = this.tagIds,
    ): HtspChannelUpdateMessage = HtspChannelUpdateMessage(
        channelId = channelId,
        channelUuid = channelUuid,
        channelNumber = channelNumber,
        channelNumberMinor = channelNumberMinor,
        channelName = channelName,
        channelIcon = channelIcon,
        currentEventId = currentEventId,
        nextEventId = nextEventId,
        services = services,
        tagIds = tagIds,
    )

    override fun toString(): String = "HtspChannelUpdateMessage(<redacted>)"

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
@ConsistentCopyVisibility
public data class HtspTagAddMessage private constructor(
    public val tagId: Long,
    public val tagName: String? = null,
    public val tagUuid: String? = null,
    public val tagIndex: Long? = null,
    public val tagIcon: String? = null,
    public val tagTitledIcon: Long? = null,
    public val channelIds: List<Long>? = null,
    private val immutableSnapshot: Unit,
) : HtspServerMessage {
    public constructor(
        tagId: Long,
        tagName: String? = null,
        tagUuid: String? = null,
        tagIndex: Long? = null,
        tagIcon: String? = null,
        tagTitledIcon: Long? = null,
        channelIds: List<Long>? = null,
    ) : this(
        tagId = tagId,
        tagName = tagName,
        tagUuid = tagUuid,
        tagIndex = tagIndex,
        tagIcon = tagIcon,
        tagTitledIcon = tagTitledIcon,
        channelIds = channelIds?.immutableSnapshot(),
        immutableSnapshot = Unit,
    )

    /** Returns a validated copy with an immutable snapshot of replacement channel membership. */
    public fun copy(
        tagId: Long = this.tagId,
        tagName: String? = this.tagName,
        tagUuid: String? = this.tagUuid,
        tagIndex: Long? = this.tagIndex,
        tagIcon: String? = this.tagIcon,
        tagTitledIcon: Long? = this.tagTitledIcon,
        channelIds: List<Long>? = this.channelIds,
    ): HtspTagAddMessage = HtspTagAddMessage(
        tagId = tagId,
        tagName = tagName,
        tagUuid = tagUuid,
        tagIndex = tagIndex,
        tagIcon = tagIcon,
        tagTitledIcon = tagTitledIcon,
        channelIds = channelIds,
    )

    override fun toString(): String = "HtspTagAddMessage(<redacted>)"

    init {
        requireU32("tagId", tagId)
        tagIndex?.let { requireU32("tagIndex", it) }
        tagTitledIcon?.let { requireU32("tagTitledIcon", it) }
        this.channelIds?.forEach { requireU32("channelIds", it) }
    }
}

/** Carries a tag update identified by [tagId]; null properties and channel membership were absent from the message. */
@ConsistentCopyVisibility
public data class HtspTagUpdateMessage private constructor(
    public val tagId: Long,
    public val tagUuid: String? = null,
    public val tagIndex: Long? = null,
    public val tagName: String? = null,
    public val tagIcon: String? = null,
    public val tagTitledIcon: Long? = null,
    public val channelIds: List<Long>? = null,
    private val immutableSnapshot: Unit,
) : HtspServerMessage {
    public constructor(
        tagId: Long,
        tagUuid: String? = null,
        tagIndex: Long? = null,
        tagName: String? = null,
        tagIcon: String? = null,
        tagTitledIcon: Long? = null,
        channelIds: List<Long>? = null,
    ) : this(
        tagId = tagId,
        tagUuid = tagUuid,
        tagIndex = tagIndex,
        tagName = tagName,
        tagIcon = tagIcon,
        tagTitledIcon = tagTitledIcon,
        channelIds = channelIds?.immutableSnapshot(),
        immutableSnapshot = Unit,
    )

    /** Returns a validated copy with an immutable snapshot of replacement channel membership. */
    public fun copy(
        tagId: Long = this.tagId,
        tagUuid: String? = this.tagUuid,
        tagIndex: Long? = this.tagIndex,
        tagName: String? = this.tagName,
        tagIcon: String? = this.tagIcon,
        tagTitledIcon: Long? = this.tagTitledIcon,
        channelIds: List<Long>? = this.channelIds,
    ): HtspTagUpdateMessage = HtspTagUpdateMessage(
        tagId = tagId,
        tagUuid = tagUuid,
        tagIndex = tagIndex,
        tagName = tagName,
        tagIcon = tagIcon,
        tagTitledIcon = tagTitledIcon,
        channelIds = channelIds,
    )

    override fun toString(): String = "HtspTagUpdateMessage(<redacted>)"

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
