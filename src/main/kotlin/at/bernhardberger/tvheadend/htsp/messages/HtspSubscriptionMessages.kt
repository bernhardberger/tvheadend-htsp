package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/** One subscription packet with microsecond timing, ASCII I/P/B or unknown `-1` frame type, and copied payload bytes. */
public data class HtspMuxPacketMessage(
    public val subscriptionId: Long,
    public val frameType: Long,
    public val streamIndex: Long,
    public val decodingTimeUs: Long?,
    public val presentationTimeUs: Long?,
    public val durationUs: Long,
    public val payload: HtspBinary,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        require(frameType in HTSP_MUX_FRAME_TYPES) {
            "frameType must be -1 or ASCII I, P, or B"
        }
        requireU32("streamIndex", streamIndex)
        require(durationUs >= 0L) { "durationUs must be non-negative" }
    }
}

private val HTSP_MUX_FRAME_TYPES = setOf(-1L, 66L, 73L, 80L)

/** Queue counters for one subscription: queued packets and bytes, optional delay, and dropped B-, P-, and I-frame counts. */
public data class HtspQueueStatusMessage(
    public val subscriptionId: Long,
    public val packetCount: Long,
    public val byteCount: Long,
    public val delay: Long?,
    public val bFrameDropCount: Long,
    public val pFrameDropCount: Long,
    public val iFrameDropCount: Long,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        requireU32("packetCount", packetCount)
        requireU32("byteCount", byteCount)
        requireU32("bFrameDropCount", bFrameDropCount)
        requireU32("pFrameDropCount", pFrameDropCount)
        requireU32("iFrameDropCount", iFrameDropCount)
    }
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
    public val frameDuration: Long?,
    public val aspectNumerator: Long?,
    public val aspectDenominator: Long?,
    public val audioType: Long?,
    public val audioVersion: Long?,
    public val channelCount: Long?,
    public val sampleRate: Long?,
    public val rdsUecp: Long?,
    public val codecMetadata: HtspBinary? = null,
) {
    init {
        requireU32("streamIndex", streamIndex)
        listOfNotNull(
                    compositionId,
                    ancillaryId,
                    width,
                    height,
                    frameDuration,
                    aspectNumerator,
                    aspectDenominator,
                    audioType,
                    audioVersion,
                    channelCount,
                    sampleRate,
                    rdsUecp,
                ).forEach { requireU32("stream field", it) }
    }
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
)

/** Reports subscription-start metadata: stream list, source information, codec metadata, and optional status or subscription error. */
public class HtspSubscriptionStartMessage(
    public val subscriptionId: Long,
    streams: List<HtspSubscriptionStream>? = null,
    public val sourceInfo: HtspSubscriptionSourceInfo? = null,
    public val codecMetadata: HtspBinary? = null,
    public val status: String? = null,
    public val subscriptionError: String? = null,
) : HtspServerMessage {
    public val streams: List<HtspSubscriptionStream>? = streams?.immutableSnapshot()

    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/** Reports a subscription-stop message with the server's optional terminal status and subscription error. */
public data class HtspSubscriptionStopMessage(
    public val subscriptionId: Long,
    public val status: String?,
    public val subscriptionError: String?,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }
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
}

/** Descrambling observations for one subscription, including PID, access and provider identifiers, ECM timing, hop count, and optional source labels. */
public data class HtspDescrambleInfoMessage(
    public val subscriptionId: Long,
    public val pid: Long,
    public val conditionalAccessId: Long,
    public val providerId: Long,
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
}

/** Carries the signed playback [speed] reported by the server for one subscription. */
public data class HtspSubscriptionSpeedMessage(
    public val subscriptionId: Long,
    public val speed: Int,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }
}

/** Timeshift state for one subscription: fullness, current shift, optional start and end bounds, and optional speed. */
public data class HtspTimeshiftStatusMessage(
    public val subscriptionId: Long,
    public val full: Long,
    public val shift: Long,
    public val start: Long?,
    public val end: Long?,
    public val speed: Int? = null,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        requireU32("full", full)
    }
}

/** Reports the server result of a subscription skip with optional absolute flag, error code, time coordinate, and byte coordinate. */
public data class HtspSubscriptionSkipMessage(
    public val subscriptionId: Long,
    public val absolute: Long?,
    public val error: Long?,
    public val time: Long?,
    public val sizeBytes: Long?,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
        absolute?.let { requireU32("absolute", it) }
        error?.let { requireU32("error", it) }
    }
}
