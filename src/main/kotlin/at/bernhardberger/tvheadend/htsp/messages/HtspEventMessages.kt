package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.model.HtspEvent
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Adds a full event snapshot (pinned htsp_server.c:1318–1457). */
public data class HtspEventAddMessage(
    /** Complete event value. */
    public val event: HtspEvent,
) : HtspMetadataMessage

/** Replaces an event with a full snapshot (pinned htsp_server.c:1318–1457). */
public data class HtspEventUpdateMessage(
    /** Complete replacement, including cleared nullable properties. */
    public val event: HtspEvent,
) : HtspMetadataMessage

/** Carries the complete unsigned [eventId] reported by an EPG-event delete message. */
public data class HtspEventDeleteMessage(public val eventId: Long) : HtspMetadataMessage {
    init { requireU32("eventId", eventId) }
    override fun toString(): String = "HtspEventDeleteMessage(<redacted>)"
}

/** Fieldless server marker reporting completion of the initial asynchronous metadata snapshot. */
public data object HtspInitialSyncCompletedMessage : HtspMetadataMessage
