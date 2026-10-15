package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.model.HtspAutorecEntry
import at.bernhardberger.tvheadend.htsp.model.HtspDvrEntry
import at.bernhardberger.tvheadend.htsp.model.HtspTimerecEntry
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Full recording add (pinned htsp_server.c:965–1202). */
public data class HtspDvrEntryAddMessage(
    /** Complete recording snapshot. */
    public val entry: HtspDvrEntry,
) : HtspMetadataMessage

/** Full replacement recording update (pinned htsp_server.c:965–1202). */
public data class HtspDvrEntryUpdateMessage(
    /** Complete recording snapshot; absent optional fields clear previous values. */
    public val entry: HtspDvrEntry,
) : HtspMetadataMessage

/** Statistics-only recording update from htsp_build_dvrentry(statsonly=1), :977–980,1157–1200. */
public data class HtspDvrEntryStatsMessage(
    /** Unsigned recording identity (:977). */
    public val entryId: Long,
    /** Recording UUID, required on the wire at v41+ (:978). */
    public val entryUuid: String?,
    /** Always-emitted recording state (:1190). */
    public val state: String,
    /** Only emitted for upcoming recordings (:1187–1188). */
    public val duplicate: Boolean? = null,
    /** Recording error when present (:1191–1192). */
    public val error: String? = null,
    /** Subscription error when present (:1193–1194). */
    public val subscriptionError: String? = null,
    /** Stream errors, only when nonzero (:1195–1196). */
    public val streamErrors: Long? = null,
    /** Data errors, only when nonzero (:1197–1198). */
    public val dataErrors: Long? = null,
    /** Known file size for recording or completed entries (:1163,1173,1199–1200). */
    public val dataSizeBytes: Long? = null,
) : HtspMetadataMessage {
    init {
        requireU32("entryId", entryId)
        streamErrors?.let { requireU32("streamErrors", it) }
        dataErrors?.let { requireU32("dataErrors", it) }
    }
    override fun toString(): String = "HtspDvrEntryStatsMessage(<redacted>)"
}

/** Identifies a deleted recording. */
public data class HtspDvrEntryDeleteMessage(
    /** Unsigned recording identity. */
    public val entryId: Long,
) : HtspMetadataMessage {
    init { requireU32("entryId", entryId) }
    override fun toString(): String = "HtspDvrEntryDeleteMessage(<redacted>)"
}

/** Full automatic-rule add (pinned htsp_server.c:1209–1268). */
public data class HtspAutorecEntryAddMessage(
    /** Complete automatic-rule snapshot. */
    public val entry: HtspAutorecEntry,
) : HtspMetadataMessage

/** Full replacement automatic-rule update (pinned htsp_server.c:1209–1268). */
public data class HtspAutorecEntryUpdateMessage(
    /** Complete automatic-rule snapshot. */
    public val entry: HtspAutorecEntry,
) : HtspMetadataMessage

/** Identifies a deleted automatic recording rule. */
public data class HtspAutorecEntryDeleteMessage(
    /** Rule UUID. */
    public val id: String,
) : HtspMetadataMessage {
    override fun toString(): String = "HtspAutorecEntryDeleteMessage(<redacted>)"
}

/** Full time-rule add (pinned htsp_server.c:1275–1311). */
public data class HtspTimerecEntryAddMessage(
    /** Complete time-rule snapshot. */
    public val entry: HtspTimerecEntry,
) : HtspMetadataMessage

/** Full replacement time-rule update (pinned htsp_server.c:1275–1311). */
public data class HtspTimerecEntryUpdateMessage(
    /** Complete time-rule snapshot. */
    public val entry: HtspTimerecEntry,
) : HtspMetadataMessage

/** Identifies a deleted time recording rule. */
public data class HtspTimerecEntryDeleteMessage(
    /** Rule UUID. */
    public val id: String,
) : HtspMetadataMessage {
    override fun toString(): String = "HtspTimerecEntryDeleteMessage(<redacted>)"
}
