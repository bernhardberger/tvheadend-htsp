package at.bernhardberger.tvheadend.htsp.model

import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/**
 * Stream details in recording-file `info` (src/dvr/dvr_rec.c:1465–1513).
 * DVR logs are reloaded without schema validation (src/dvr/dvr_db.c:1108–1110).
 * Invalid observations decode as null; non-map entries retain their position as empty streams.
 */
public data class HtspDvrRecordingStream(
    /** Stream type label. */
    public val type: String? = null,
    /** Stream language. */
    public val language: String? = null,
    /** Audio type code. */
    public val audioType: Long? = null,
    /** Audio version code. */
    public val audioVersion: Long? = null,
    /** Video width in pixels. */
    public val width: Long? = null,
    /** Video height in pixels. */
    public val height: Long? = null,
    /** Wire `duration` in 90 kHz ticks, normalized to microseconds (src/parsers/parsers.c:1055). */
    public val frameDurationUs: Long? = null,
    /** Display aspect numerator. */
    public val aspectNumerator: Long? = null,
    /** Display aspect denominator. */
    public val aspectDenominator: Long? = null,
    /** Subtitle composition identity. */
    public val compositionId: Long? = null,
    /** Subtitle ancillary identity. */
    public val ancillaryId: Long? = null,
) {
    override fun toString(): String = "HtspDvrRecordingStream(<redacted>)"
}

/** Immutable recording-stream list (pinned htsp_server.c:1133–1135). */
public class HtspDvrRecordingInfo(streams: List<HtspDvrRecordingStream>) {
    /** Ordered snapshot from wire `info`. */
    public val streams: List<HtspDvrRecordingStream> = streams.immutableSnapshot()
    override fun equals(other: Any?): Boolean = other is HtspDvrRecordingInfo && streams == other.streams
    override fun hashCode(): Int = streams.hashCode()
    override fun toString(): String = "HtspDvrRecordingInfo(<redacted>)"
}

/** One recording-file entry copied from persisted logs (pinned htsp_server.c:1124–1147). */
public data class HtspDvrRecordingFile(
    /** Optional persisted file identity. */
    public val fileId: Long?,
    /** Relative filename (:1129–1132). */
    public val path: String?,
    /** Start epoch seconds when available (:1136–1137). */
    public val startEpochSeconds: Long?,
    /** Stop epoch seconds when available (:1138–1139). */
    public val stopEpochSeconds: Long?,
    /** Byte size when available (:1140–1141). */
    public val sizeBytes: Long?,
    /** Stream metadata when available (:1133–1135). */
    public val info: HtspDvrRecordingInfo? = null,
) {
    init { fileId?.let { requireU32("fileId", it) } }

    override fun toString(): String = "HtspDvrRecordingFile(<redacted>)"
}
