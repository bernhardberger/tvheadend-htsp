package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/**
 * One subscription packet with microsecond timing, owned payload bytes, and a
 * typed frame type.
 */
public data class HtspMuxPacketMessage(
    public val subscriptionId: Long,
    public val frameType: HtspFrameType,
    public val streamIndex: Long,
    public val decodingTimeUs: Long?,
    public val presentationTimeUs: Long?,
    public val durationUs: Long,
    public val payload: HtspBinary,
    /**
     * HTSP `com`, decoded to a shared enum constant (src/streaming.h:59–63;
     * src/htsp_server.c:4213–4214).
     */
    public val commercialAdvice: HtspCommercialAdvice? = null,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        requireU32("streamIndex", streamIndex)
        require(durationUs >= 0L) { "durationUs must be non-negative" }
    }

    override fun toString(): String = "HtspMuxPacketMessage(<redacted>)"
}

/**
 * Mux frame classification (pinned src/htsp_server.c:4162–4166, 4209–4210).
 * Minor releases may add entries; keep an `else` branch when matching.
 */
public enum class HtspFrameType {
    /** Intra-coded frame, wire ASCII I (`73`); pinned src/htsp_server.c:4162–4166. */
    I,
    /** Predictive frame, wire ASCII P (`80`); pinned src/htsp_server.c:4162–4166. */
    P,
    /** Bidirectionally predictive frame, wire ASCII B (`66`); pinned src/htsp_server.c:4162–4166. */
    B,
    /** Absent or wire `0` (pinned src/htsp_server.c:4162–4166, 4209–4210), or accepted signed `-1`. */
    UNKNOWN,
}

/** Commercial advice for packet consumers. Future u32 codes map to [UNRECOGNIZED]. */
public enum class HtspCommercialAdvice {
    /** Server has no advice (`0`). */
    UNKNOWN,
    /** Commercial content (`1`). */
    YES,
    /** Non-commercial content (`2`). */
    NO,
    /** A future code, distinct from missing advice and explicit unknown. */
    UNRECOGNIZED,
}

/** Queue counters for one subscription: queued packets and bytes, optional delay and data errors, and dropped B-, P-, and I-frame counts. */
public data class HtspQueueStatusMessage(
    public val subscriptionId: Long,
    public val packetCount: Long,
    public val byteCount: Long,
    /** HTSP `delay`, normalized microseconds; null means absent (htsp_server.c:4258–4279). */
    public val delayUs: Long?,
    public val bFrameDropCount: Long,
    public val pFrameDropCount: Long,
    public val iFrameDropCount: Long,
    /** Cumulative unsigned-u32 data errors (`errors`); null when not reported, distinct from an explicit zero. */
    public val errorCount: Long? = null,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        requireU32("packetCount", packetCount)
        requireU32("byteCount", byteCount)
        requireU32("bFrameDropCount", bFrameDropCount)
        requireU32("pFrameDropCount", pFrameDropCount)
        requireU32("iFrameDropCount", iFrameDropCount)
        errorCount?.let { requireU32("errorCount", it) }
    }

    override fun toString(): String = "HtspQueueStatusMessage(<redacted>)"
}

/** One stream descriptor with index and codec type plus optional language, video, audio, radio-data, and codec metadata fields. */
public data class HtspSubscriptionStream(
    public val streamIndex: Long,
    public val streamType: String,
    public val language: String?,
    public val compositionId: Long?,
    public val ancillaryId: Long?,
    public val width: Long?,
    public val height: Long?,
    /** HTSP `duration`, normalized microseconds; null means absent (htsp_server.c:4350–4351). */
    public val frameDurationUs: Long?,
    public val aspectNumerator: Long?,
    public val aspectDenominator: Long?,
    public val audioType: Long?,
    public val audioVersion: Long?,
    public val channelCount: Long?,
    public val sampleRate: Long?,
    /** HTSP `rds_uecp`: optional 0/1 RDS UECP flag (pinned htsp_server.c:4367). */
    public val rdsUecp: Boolean?,
    public val codecMetadata: HtspBinary? = null,
) {
    init {
        requireU32("streamIndex", streamIndex)
        listOfNotNull(
                    compositionId,
                    ancillaryId,
                    width,
                    height,
                    aspectNumerator,
                    aspectDenominator,
                    audioType,
                    audioVersion,
                    channelCount,
                    sampleRate,
                ).forEach { requireU32("stream field", it) }
        frameDurationUs?.let { require(it >= 0L) { "frameDurationUs must be non-negative" } }
    }

    override fun toString(): String = "HtspSubscriptionStream(<redacted>)"
}

/** Optional tuner source identity and display metadata for a subscription, including adapter, mux, network, provider, service, and satellite position. */
public data class HtspSubscriptionSourceInfo(
    public val adapterUuid: String?,
    public val muxUuid: String?,
    public val networkUuid: String?,
    public val adapter: String?,
    public val mux: String?,
    public val network: String?,
    public val networkType: String?,
    public val provider: String?,
    public val service: String?,
    public val satellitePosition: String?,
) {
    override fun toString(): String = "HtspSubscriptionSourceInfo(<redacted>)"
}

/** Reports subscription-start metadata: stream list, source information, and codec metadata. */
@ConsistentCopyVisibility
public data class HtspSubscriptionStartMessage private constructor(
    public val subscriptionId: Long,
    public val streams: List<HtspSubscriptionStream>? = null,
    public val sourceInfo: HtspSubscriptionSourceInfo? = null,
    public val codecMetadata: HtspBinary? = null,
    private val immutableSnapshot: Unit,
) : HtspServerMessage {
    public constructor(
        subscriptionId: Long,
        streams: List<HtspSubscriptionStream>? = null,
        sourceInfo: HtspSubscriptionSourceInfo? = null,
        codecMetadata: HtspBinary? = null,
    ) : this(
        subscriptionId = subscriptionId,
        streams = streams?.immutableSnapshot(),
        sourceInfo = sourceInfo,
        codecMetadata = codecMetadata,
        immutableSnapshot = Unit,
    )

    /** Returns a validated copy with an immutable snapshot of replacement streams. */
    public fun copy(
        subscriptionId: Long = this.subscriptionId,
        streams: List<HtspSubscriptionStream>? = this.streams,
        sourceInfo: HtspSubscriptionSourceInfo? = this.sourceInfo,
        codecMetadata: HtspBinary? = this.codecMetadata,
    ): HtspSubscriptionStartMessage = HtspSubscriptionStartMessage(
        subscriptionId = subscriptionId,
        streams = streams,
        sourceInfo = sourceInfo,
        codecMetadata = codecMetadata,
    )

    override fun toString(): String = "HtspSubscriptionStartMessage(<redacted>)"

    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/** Reports a stream stop with optional status and error; the same subscription may start again. */
public data class HtspSubscriptionStopMessage(
    public val subscriptionId: Long,
    public val status: String?,
    public val subscriptionError: String?,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

    override fun toString(): String = "HtspSubscriptionStopMessage(<redacted>)"
}

/** Reports the grace interval, in seconds, allowed for the identified subscription. */
public data class HtspSubscriptionGraceMessage(
    public val subscriptionId: Long,
    public val graceTimeoutSeconds: Long,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        requireU32("graceTimeoutSeconds", graceTimeoutSeconds)
    }

    override fun toString(): String = "HtspSubscriptionGraceMessage(<redacted>)"
}

/** Reports the current optional status and subscription error for one subscription. */
public data class HtspSubscriptionStatusMessage(
    public val subscriptionId: Long,
    public val status: String?,
    public val subscriptionError: String?,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

    override fun toString(): String = "HtspSubscriptionStatusMessage(<redacted>)"
}

/** Signal observations for one subscription: frontend status, relative and absolute SNR and signal, bit errors, and uncorrected blocks. */
public data class HtspSignalStatusMessage(
    public val subscriptionId: Long,
    public val frontendStatus: String?,
    public val relativeSnr: Long?,
    public val absoluteSnr: Long?,
    public val relativeSignal: Long?,
    public val absoluteSignal: Long?,
    public val bitErrorRate: Long?,
    public val uncorrectedBlockCount: Long?,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        listOfNotNull(
                    relativeSnr,
                    relativeSignal,
                    bitErrorRate,
                    uncorrectedBlockCount,
                ).forEach { requireU32("signal field", it) }
    }

    override fun toString(): String = "HtspSignalStatusMessage(<redacted>)"
}

/** Descrambling observations for one subscription, including PID, access and provider identifiers, ECM timing, hop count, and optional source labels. */
public data class HtspDescrambleInfoMessage(
    public val subscriptionId: Long,
    public val pid: Long,
    public val conditionalAccessId: Long,
    public val providerId: Long,
    /** HTSP `ecmtime`, unsigned descrambler timing value; the pinned sender does not specify its unit (htsp_server.c:4559). */
    public val ecmTime: Long,
    public val hopCount: Long,
    public val cardSystem: String? = null,
    public val reader: String? = null,
    public val source: String? = null,
    public val protocol: String? = null,
) : HtspServerMessage {
    init {
        listOfNotNull(
                    subscriptionId,
                    pid,
                    conditionalAccessId,
                    providerId,
                    ecmTime,
                    hopCount,
                ).forEach { requireU32("descramble field", it) }
    }

    override fun toString(): String = "HtspDescrambleInfoMessage(<redacted>)"
}

/** Carries the signed playback [speed] reported by the server for one subscription. */
public data class HtspSubscriptionSpeedMessage(
    public val subscriptionId: Long,
    public val speed: Int,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

    override fun toString(): String = "HtspSubscriptionSpeedMessage(<redacted>)"
}

/** Timeshift state for one subscription: fullness, current shift, optional start and end bounds, and optional speed. */
public data class HtspTimeshiftStatusMessage(
    public val subscriptionId: Long,
    /** HTSP `full`: 0/1 buffer-full flag (pinned htsp_server.c:4596). */
    public val full: Boolean,
    /** HTSP `shift`, signed ticks in this subscription's negotiated 90 kHz or microsecond clock (htsp_server.c:4597). */
    public val shift: Long,
    /** HTSP `start`, ticks in this subscription's negotiated clock; null means unavailable (htsp_server.c:4598–4599). */
    public val start: Long?,
    /** HTSP `end`, ticks in this subscription's negotiated clock; null means unavailable (htsp_server.c:4600–4601). */
    public val end: Long?,
    public val speed: Int? = null,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

    override fun toString(): String = "HtspTimeshiftStatusMessage(<redacted>)"
}

/** Reports the server result of a subscription skip with optional absolute and error flags, time coordinate, and byte coordinate. */
public data class HtspSubscriptionSkipMessage(
    public val subscriptionId: Long,
    /** HTSP `absolute`: optional 0/1 absolute-position flag (pinned htsp_server.c:4625-4626). */
    public val absolute: Boolean?,
    /** HTSP `error`: optional 0/1 skip-failed flag, not an error code (pinned htsp_server.c:4627-4628). */
    public val error: Boolean?,
    /** HTSP `time`, ticks in this subscription's negotiated 90 kHz or microsecond clock; null means absent (htsp_server.c:4630). */
    public val time: Long?,
    public val sizeBytes: Long?,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

    override fun toString(): String = "HtspSubscriptionSkipMessage(<redacted>)"
}
