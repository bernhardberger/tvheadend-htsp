package at.bernhardberger.tvheadend.htsp.model

import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Full time recording rule (pinned htsp_server.c:1275–1311). */
@ConsistentCopyVisibility
public data class HtspTimerecEntry private constructor(
    /** Rule UUID (:1280). */
    public val id: String,
    /** Enabled flag (:1281). */
    public val enabled: Boolean,
    /** Optional name (:1298–1299). */
    public val name: String? = null,
    /** Optional title (:1296–1297). */
    public val title: String? = null,
    /** Optional channel (:1304–1305). */
    public val channelId: Long? = null,
    /** Start time; wire -1 becomes null, otherwise 0..1439 (:1292). */
    public val startMinutesSinceMidnight: Int?,
    /** Stop time; wire -1 becomes null, otherwise 0..1439 (:1293). */
    public val stopMinutesSinceMidnight: Int?,
    /** Always-emitted weekday bitmask (:1282). */
    public val daysOfWeekMask: Long,
    /** Always-emitted priority (:1291). */
    public val priority: Long,
    /** Always-emitted retention days or policy sentinel (:1284–1288). */
    public val retentionDays: Long,
    /** Always-emitted removal days or policy sentinel (:1290). */
    public val removalDays: Long,
    /** Optional directory (:1300–1301). */
    public val directory: String? = null,
    /** Optional owner (:1302). */
    public val owner: String? = null,
    /** Optional creator (:1303). */
    public val creator: String? = null,
    /** Optional configuration UUID, wire `configId`, introduced in v40 (:1306–1307). */
    public val dvrConfigUuid: String? = null,
    /** Optional comment, introduced in v42 (:1294). */
    public val comment: String? = null,
    private val immutableSnapshot: Unit,
) {
    /** Constructs a validated time recording rule, with null for unset times. */
    public constructor(
        id: String,
        enabled: Boolean,
        name: String? = null,
        title: String? = null,
        channelId: Long? = null,
        startMinutesSinceMidnight: Int?,
        stopMinutesSinceMidnight: Int?,
        daysOfWeekMask: Long,
        priority: Long,
        retentionDays: Long,
        removalDays: Long,
        directory: String? = null,
        owner: String? = null,
        creator: String? = null,
        dvrConfigUuid: String? = null,
        comment: String? = null,
    ) : this(
        id, enabled, name, title, channelId, startMinutesSinceMidnight, stopMinutesSinceMidnight,
        daysOfWeekMask, priority, retentionDays, removalDays, directory, owner, creator, dvrConfigUuid, comment, Unit,
    )

    /** Returns a validated copy of this rule. */
    public fun copy(
        id: String = this.id,
        enabled: Boolean = this.enabled,
        name: String? = this.name,
        title: String? = this.title,
        channelId: Long? = this.channelId,
        startMinutesSinceMidnight: Int? = this.startMinutesSinceMidnight,
        stopMinutesSinceMidnight: Int? = this.stopMinutesSinceMidnight,
        daysOfWeekMask: Long = this.daysOfWeekMask,
        priority: Long = this.priority,
        retentionDays: Long = this.retentionDays,
        removalDays: Long = this.removalDays,
        directory: String? = this.directory,
        owner: String? = this.owner,
        creator: String? = this.creator,
        dvrConfigUuid: String? = this.dvrConfigUuid,
        comment: String? = this.comment,
    ): HtspTimerecEntry = HtspTimerecEntry(
        id, enabled, name, title, channelId, startMinutesSinceMidnight, stopMinutesSinceMidnight,
        daysOfWeekMask, priority, retentionDays, removalDays, directory, owner, creator, dvrConfigUuid, comment,
    )

    init {
        listOfNotNull(channelId, daysOfWeekMask, priority, retentionDays, removalDays)
            .forEach { requireU32("time rule field", it) }
        startMinutesSinceMidnight?.let { require(it in 0..1439) }
        stopMinutesSinceMidnight?.let { require(it in 0..1439) }
    }
    override fun toString(): String = "HtspTimerecEntry(<redacted>)"
}
