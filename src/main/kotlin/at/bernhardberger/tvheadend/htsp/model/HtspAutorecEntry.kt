package at.bernhardberger.tvheadend.htsp.model

import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** Full automatic recording rule (pinned htsp_server.c:1209–1268). */
@ConsistentCopyVisibility
public data class HtspAutorecEntry private constructor(
    /** Rule UUID (:1215). */
    public val id: String,
    /** Enabled flag (:1216). */
    public val enabled: Boolean,
    /** Maximum duration (:1217). */
    public val maxDurationSeconds: Long,
    /** Minimum duration (:1218). */
    public val minDurationSeconds: Long,
    /** Retention days or policy sentinel (:1220–1224). */
    public val retentionDays: Long,
    /** Removal days or policy sentinel (:1226). */
    public val removalDays: Long,
    /** Weekday bitmask (:1227). */
    public val daysOfWeekMask: Long,
    /** Approximate time in 0..1439; null means wire -1, unset (:1236–1238). */
    public val approximateStartMinutesSinceMidnight: Int?,
    /** Start time in 0..1439; null means wire -1, unset (:1239). */
    public val startMinutesSinceMidnight: Int?,
    /** Window end in 0..1439; null means wire -1, unset (:1240). */
    public val startWindowEndMinutesSinceMidnight: Int?,
    /** Priority code (:1241). */
    public val priority: Long,
    /** Pre-recording padding (:1242). */
    public val startExtraMinutes: Long,
    /** Post-recording padding (:1243). */
    public val stopExtraMinutes: Long,
    /** Duplicate-detection policy (:1244). */
    public val duplicateDetection: Long,
    /** Recording limit (:1245). */
    public val maximumRecordingCount: Long,
    /** Broadcast policy, introduced in v39 (:1246); absent on older servers. */
    public val broadcastType: Long? = null,
    /** Optional comment, introduced in v42 (:1247). */
    public val comment: String? = null,
    /** Optional title expression (:1249–1250). */
    public val title: String? = null,
    /** Search full text, emitted with title (:1251). */
    public val fullText: Boolean? = null,
    /** Merge text, emitted with title (:1252). */
    public val mergeText: Boolean? = null,
    /** Optional name (:1254). */
    public val name: String? = null,
    /** Optional directory (:1255–1256). */
    public val directory: String? = null,
    /** Optional owner (:1257). */
    public val owner: String? = null,
    /** Optional creator (:1258). */
    public val creator: String? = null,
    /** Channel filter; null means no channel restriction (:1259–1260). */
    public val channelId: Long? = null,
    /** Optional series link (:1261–1262). */
    public val seriesLinkUri: String? = null,
    /** Optional configuration UUID, wire `configId`, introduced in v40 (:1263–1264). */
    public val dvrConfigUuid: String? = null,
    private val immutableSnapshot: Unit,
) {
    /** Constructs a validated automatic recording rule. */
    public constructor(
        id: String,
        enabled: Boolean,
        maxDurationSeconds: Long,
        minDurationSeconds: Long,
        retentionDays: Long,
        removalDays: Long,
        daysOfWeekMask: Long,
        approximateStartMinutesSinceMidnight: Int?,
        startMinutesSinceMidnight: Int?,
        startWindowEndMinutesSinceMidnight: Int?,
        priority: Long,
        startExtraMinutes: Long,
        stopExtraMinutes: Long,
        duplicateDetection: Long,
        maximumRecordingCount: Long,
        broadcastType: Long? = null,
        comment: String? = null,
        title: String? = null,
        fullText: Boolean? = null,
        mergeText: Boolean? = null,
        name: String? = null,
        directory: String? = null,
        owner: String? = null,
        creator: String? = null,
        channelId: Long? = null,
        seriesLinkUri: String? = null,
        dvrConfigUuid: String? = null,
    ) : this(
        id, enabled, maxDurationSeconds, minDurationSeconds, retentionDays, removalDays, daysOfWeekMask,
        approximateStartMinutesSinceMidnight, startMinutesSinceMidnight, startWindowEndMinutesSinceMidnight,
        priority, startExtraMinutes, stopExtraMinutes, duplicateDetection, maximumRecordingCount, broadcastType,
        comment, title, fullText, mergeText, name, directory, owner, creator, channelId, seriesLinkUri, dvrConfigUuid, Unit,
    )

    /** Returns a validated copy of this rule. */
    public fun copy(
        id: String = this.id,
        enabled: Boolean = this.enabled,
        maxDurationSeconds: Long = this.maxDurationSeconds,
        minDurationSeconds: Long = this.minDurationSeconds,
        retentionDays: Long = this.retentionDays,
        removalDays: Long = this.removalDays,
        daysOfWeekMask: Long = this.daysOfWeekMask,
        approximateStartMinutesSinceMidnight: Int? = this.approximateStartMinutesSinceMidnight,
        startMinutesSinceMidnight: Int? = this.startMinutesSinceMidnight,
        startWindowEndMinutesSinceMidnight: Int? = this.startWindowEndMinutesSinceMidnight,
        priority: Long = this.priority,
        startExtraMinutes: Long = this.startExtraMinutes,
        stopExtraMinutes: Long = this.stopExtraMinutes,
        duplicateDetection: Long = this.duplicateDetection,
        maximumRecordingCount: Long = this.maximumRecordingCount,
        broadcastType: Long? = this.broadcastType,
        comment: String? = this.comment,
        title: String? = this.title,
        fullText: Boolean? = this.fullText,
        mergeText: Boolean? = this.mergeText,
        name: String? = this.name,
        directory: String? = this.directory,
        owner: String? = this.owner,
        creator: String? = this.creator,
        channelId: Long? = this.channelId,
        seriesLinkUri: String? = this.seriesLinkUri,
        dvrConfigUuid: String? = this.dvrConfigUuid,
    ): HtspAutorecEntry = HtspAutorecEntry(
        id, enabled, maxDurationSeconds, minDurationSeconds, retentionDays, removalDays, daysOfWeekMask,
        approximateStartMinutesSinceMidnight, startMinutesSinceMidnight, startWindowEndMinutesSinceMidnight,
        priority, startExtraMinutes, stopExtraMinutes, duplicateDetection, maximumRecordingCount, broadcastType,
        comment, title, fullText, mergeText, name, directory, owner, creator, channelId, seriesLinkUri, dvrConfigUuid,
    )

    init {
        listOfNotNull(
            approximateStartMinutesSinceMidnight, startMinutesSinceMidnight, startWindowEndMinutesSinceMidnight,
        ).forEach { require(it in 0..1439) { "automatic rule time must be in 0..1439" } }
        listOfNotNull(
            maxDurationSeconds, minDurationSeconds, retentionDays, removalDays, daysOfWeekMask,
            priority, duplicateDetection, maximumRecordingCount, broadcastType, channelId,
        ).forEach { requireU32("automatic rule field", it) }
    }
    override fun toString(): String = "HtspAutorecEntry(<redacted>)"
}
