package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.model.HtspChannel
import at.bernhardberger.tvheadend.htsp.model.HtspTag
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Adds a complete channel snapshot (pinned htsp_server.c:859–925). */
public data class HtspChannelAddMessage(
    /** Full channel value. */
    public val channel: HtspChannel,
) : HtspMetadataMessage

/** Replaces a channel with the full snapshot (pinned htsp_server.c:859–925). */
public data class HtspChannelUpdateMessage(
    /** Full replacement, including cleared nullable properties. */
    public val channel: HtspChannel,
) : HtspMetadataMessage

/** Updates only the current and next event references (pinned htsp_server.c:3805–3823). */
public data class HtspChannelNowNextMessage(
    /** Unsigned channel identity. */
    public val channelId: Long,
    /** Current event identity, or zero. */
    public val currentEventId: Long,
    /** Next event identity, or zero. */
    public val nextEventId: Long,
) : HtspMetadataMessage {
    init {
        requireU32("channelId", channelId)
        requireU32("currentEventId", currentEventId)
        requireU32("nextEventId", nextEventId)
    }
}

/** Carries the complete unsigned [channelId] reported by a channel-delete message. */
public data class HtspChannelDeleteMessage(public val channelId: Long) : HtspMetadataMessage {
    init { requireU32("channelId", channelId) }
}

/** Adds tag metadata (pinned htsp_server.c:932–958). */
public data class HtspTagAddMessage(
    /** Full metadata; null membership means not included. */
    public val tag: HtspTag,
) : HtspMetadataMessage

/** Replaces tag metadata (pinned htsp_server.c:932–958). */
public data class HtspTagUpdateMessage(
    /** Full metadata; retain previous membership only when membership is null. */
    public val tag: HtspTag,
) : HtspMetadataMessage

/** Carries the complete unsigned [tagId] reported by a channel-tag delete message. */
public data class HtspTagDeleteMessage(public val tagId: Long) : HtspMetadataMessage {
    init { requireU32("tagId", tagId) }
}
