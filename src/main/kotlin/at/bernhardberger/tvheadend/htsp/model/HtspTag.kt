package at.bernhardberger.tvheadend.htsp.model

import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Full tag metadata (pinned htsp_server.c:932–958); membership has separate inclusion semantics. */
@ConsistentCopyVisibility
public data class HtspTag private constructor(
    /** Unsigned identity (:940). */
    public val tagId: Long,
    /** Display name, always emitted (:945). */
    public val tagName: String,
    /** UUID introduced in v41; required on the wire at v41+ (:941). */
    public val tagUuid: String?,
    /** Display order, always emitted (:943). */
    public val tagIndex: Long,
    /** Icon URI when nonempty (:946–948). */
    public val tagIcon: String?,
    /** Unsigned titled-icon flag, always emitted (:949). */
    public val tagTitledIcon: Long,
    /** Wire `members`: null means not included; empty means no members (:938,951–955,1709–1719). */
    public val channelIds: List<Long>?,
    private val immutableSnapshot: Unit,
) {
    /** Constructs tag metadata with an immutable membership snapshot. */
    public constructor(
        tagId: Long,
        tagName: String,
        tagUuid: String?,
        tagIndex: Long,
        tagIcon: String?,
        tagTitledIcon: Long,
        channelIds: List<Long>?,
    ) : this(tagId, tagName, tagUuid, tagIndex, tagIcon, tagTitledIcon, channelIds?.immutableSnapshot(), Unit)

    /** Returns validated tag metadata with an immutable membership snapshot. */
    public fun copy(
        tagId: Long = this.tagId,
        tagName: String = this.tagName,
        tagUuid: String? = this.tagUuid,
        tagIndex: Long = this.tagIndex,
        tagIcon: String? = this.tagIcon,
        tagTitledIcon: Long = this.tagTitledIcon,
        channelIds: List<Long>? = this.channelIds,
    ): HtspTag = HtspTag(tagId, tagName, tagUuid, tagIndex, tagIcon, tagTitledIcon, channelIds)

    init {
        requireU32("tagId", tagId)
        requireU32("tagIndex", tagIndex)
        requireU32("tagTitledIcon", tagTitledIcon)
        channelIds?.forEach { requireU32("channelIds", it) }
    }

    override fun toString(): String = "HtspTag(<redacted>)"
}
