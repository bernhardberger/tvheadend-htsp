package at.bernhardberger.tvheadend.htsp.model

import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Complete channel snapshot from htsp_build_channel (pinned htsp_server.c:859–925). */
@ConsistentCopyVisibility
public data class HtspChannel private constructor(
    /** Unsigned channel identity (:873). */
    public val channelId: Long,
    /** UUID introduced in v41; required on the wire at v41+ (:874). */
    public val channelUuid: String?,
    /** Major channel number, including zero (:876). */
    public val channelNumber: Long,
    /** Minor number, omitted when zero (:877–878). */
    public val channelNumberMinor: Long?,
    /** Display name, always emitted (:880). */
    public val channelName: String,
    /** Icon URI when set (:881–882). */
    public val channelIcon: String?,
    /** Current event identity, zero when absent (:886). */
    public val currentEventId: Long,
    /** Next event identity, zero when absent (:887). */
    public val nextEventId: Long,
    /** Immutable service snapshot, including an empty list (:895–921). */
    public val services: List<HtspChannelService>,
    /** Immutable tag identities, including an empty list (:889–893,922). */
    public val tagIds: List<Long>,
    private val immutableSnapshot: Unit,
) {
    /** Constructs a validated snapshot of a full channel. */
    public constructor(
        channelId: Long,
        channelUuid: String?,
        channelNumber: Long,
        channelNumberMinor: Long?,
        channelName: String,
        channelIcon: String?,
        currentEventId: Long,
        nextEventId: Long,
        services: List<HtspChannelService>,
        tagIds: List<Long>,
    ) : this(
        channelId, channelUuid, channelNumber, channelNumberMinor, channelName, channelIcon,
        currentEventId, nextEventId, services.immutableSnapshot(), tagIds.immutableSnapshot(), Unit,
    )

    /** Returns a validated copy with immutable collection snapshots. */
    public fun copy(
        channelId: Long = this.channelId,
        channelUuid: String? = this.channelUuid,
        channelNumber: Long = this.channelNumber,
        channelNumberMinor: Long? = this.channelNumberMinor,
        channelName: String = this.channelName,
        channelIcon: String? = this.channelIcon,
        currentEventId: Long = this.currentEventId,
        nextEventId: Long = this.nextEventId,
        services: List<HtspChannelService> = this.services,
        tagIds: List<Long> = this.tagIds,
    ): HtspChannel = HtspChannel(
        channelId, channelUuid, channelNumber, channelNumberMinor, channelName, channelIcon,
        currentEventId, nextEventId, services, tagIds,
    )

    init {
        requireU32("channelId", channelId)
        requireU32("channelNumber", channelNumber)
        channelNumberMinor?.let { requireU32("channelNumberMinor", it) }
        requireU32("currentEventId", currentEventId)
        requireU32("nextEventId", nextEventId)
        tagIds.forEach { requireU32("tagIds", it) }
    }

    override fun toString(): String = "HtspChannel(<redacted>)"
}

/** One channel service (pinned htsp_server.c:895–918). */
public data class HtspChannelService(
    /** Service display name (:898). */
    public val name: String,
    /** Service type label (:901). */
    public val type: String,
    /** Unsigned content code (:904). */
    public val content: Long,
    /** Conditional-access identity when encrypted (:906–908). */
    public val conditionalAccessId: Long?,
    /** Conditional-access label when encrypted (:908). */
    public val conditionalAccessName: String?,
    /** Provider name when set (:916). */
    public val providerName: String?,
    /** Immutable HbbTV applications when set (:911–913). */
    public val hbbtv: HtspHbbtvApplications? = null,
) {
    init {
        requireU32("content", content)
        conditionalAccessId?.let { requireU32("conditionalAccessId", it) }
    }

    override fun toString(): String = "HtspChannelService(<redacted>)"
}
