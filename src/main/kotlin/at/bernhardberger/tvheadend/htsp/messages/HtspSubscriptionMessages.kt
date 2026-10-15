package at.bernhardberger.tvheadend.htsp.messages

import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.requireU32

/**
 * One subscription packet with microsecond timing, owned payload bytes, and a
 * typed frame type.
 *
 * @property subscriptionId identifier of the subscription that carries the packet (`subscriptionId`)
 * @property frameType video frame classification (`frametype`); [HtspFrameType.UNKNOWN] for audio and other non-video packets
 * @property streamIndex index of the elementary stream (`stream`); matches [HtspSubscriptionStream.streamIndex]
 * @property decodingTimeUs decode timestamp in microseconds (`dts`), converted from the subscription's negotiated clock; `null` when the packet has none
 * @property presentationTimeUs presentation timestamp in microseconds (`pts`), converted from the subscription's negotiated clock; `null` when the packet has none
 * @property durationUs packet duration in microseconds (`duration`), converted from the subscription's negotiated clock; never negative
 * @property payload packet data bytes (`payload`)
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

/**
 * Queue counters for one subscription: queued packets and bytes, optional delay and data errors, and dropped B-, P-, and I-frame counts.
 *
 * @property subscriptionId identifier of the subscription the counters belong to (`subscriptionId`)
 * @property packetCount number of packets waiting in the server's send queue (`packets`)
 * @property byteCount payload bytes waiting in the server's send queue (`bytes`)
 * @property bFrameDropCount B-frames the server has dropped for this subscription because its queue was too deep (`Bdrops`)
 * @property pFrameDropCount P-frames the server has dropped for this subscription because its queue was too deep (`Pdrops`)
 * @property iFrameDropCount I-frames the server has dropped for this subscription because its queue was too deep (`Idrops`)
 */
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

/**
 * One stream descriptor with index and codec type plus optional language, video, audio, and radio-data fields.
 *
 * @property streamIndex index of the elementary stream (`index`); matches [HtspMuxPacketMessage.streamIndex]
 * @property streamType codec name sent by the server (`type`)
 * @property language stream language code (`language`); `null` when the server sent none
 * @property compositionId DVB subtitle composition page identifier (`composition_id`); `null` for other stream types
 * @property ancillaryId DVB subtitle ancillary page identifier (`ancillary_id`); `null` for other stream types
 * @property width video width in pixels (`width`); `null` for non-video streams or when the server omitted it
 * @property height video height in pixels (`height`); `null` for non-video streams or when the server omitted it
 * @property aspectNumerator numerator of the video aspect ratio (`aspect_num`); `null` when the server omitted it
 * @property aspectDenominator denominator of the video aspect ratio (`aspect_den`); `null` when the server omitted it
 * @property audioType audio type code (`audio_type`); `null` for non-audio streams
 * @property audioVersion audio version code (`audio_version`); `null` for non-audio streams or when the server omitted it
 * @property channelCount number of audio channels (`channels`); `null` for non-audio streams or when the server omitted it
 * @property sampleRate audio sampling-frequency index (`rate`), the server's MPEG-4 style index rather than hertz; `null` when the server omitted it
 */
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

/**
 * Optional tuner source identity and display metadata for a subscription, including adapter, mux, network, provider, service, and satellite position.
 *
 * Each value is `null` when the server omitted it; the server omits the display strings for anonymous connections.
 *
 * @property adapterUuid UUID of the tuner adapter (`adapter_uuid`)
 * @property muxUuid UUID of the mux (`mux_uuid`)
 * @property networkUuid UUID of the network (`network_uuid`)
 * @property adapter display name of the tuner adapter (`adapter`)
 * @property mux display name of the mux (`mux`)
 * @property network display name of the network (`network`)
 * @property networkType network type name (`network_type`)
 * @property provider service provider name (`provider`)
 * @property service service name (`service`)
 * @property satellitePosition satellite orbital position as text (`satpos`)
 */
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

/**
 * Reports subscription-start metadata: stream list, source information, and codec metadata.
 *
 * @property subscriptionId identifier of the subscription that started (`subscriptionId`)
 * @property streams the enabled streams of the subscription (`streams`); `null` when absent
 * @property sourceInfo tuner source details (`sourceinfo`); `null` when absent
 */
@ConsistentCopyVisibility
public data class HtspSubscriptionStartMessage private constructor(
    public val subscriptionId: Long,
    public val streams: List<HtspSubscriptionStream>? = null,
    public val sourceInfo: HtspSubscriptionSourceInfo? = null,
    /** Codec header bytes of the first stream that has them; there is no stream association on the wire. */
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

/**
 * Reports a stream stop with optional status and error; the same subscription may start again.
 *
 * @property subscriptionId identifier of the subscription that stopped (`subscriptionId`)
 * @property status server status text giving the reason for the stop (`status`); `null` when absent
 * @property subscriptionError server error text for the subscription (`subscriptionError`); `null` when absent
 */
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

/**
 * Reports the grace interval, in seconds, allowed for the identified subscription.
 *
 * @property subscriptionId identifier of the subscription (`subscriptionId`)
 * @property graceTimeoutSeconds grace interval in seconds (`graceTimeout`)
 */
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

/**
 * Reports the current optional status and subscription error for one subscription.
 *
 * @property subscriptionId identifier of the subscription (`subscriptionId`)
 * @property status server status text (`status`); `null` when absent, as when packets are flowing
 * @property subscriptionError server error text for the subscription (`subscriptionError`); `null` when absent
 */
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

/**
 * Signal observations for one subscription: frontend status, relative and absolute SNR and signal, bit errors, and uncorrected blocks.
 *
 * Each optional value is `null` when the server did not report it, including for anonymous connections.
 *
 * @property subscriptionId identifier of the subscription (`subscriptionId`)
 * @property frontendStatus tuner frontend status text (`feStatus`)
 * @property relativeSnr signal-to-noise ratio on a relative scale from 0 to 65535, where 65535 is 100% (`feSNR`)
 * @property absoluteSnr signal-to-noise ratio in thousandths of a decibel (`feAbsoluteSNR`), sent by v44 servers
 * @property relativeSignal signal strength on a relative scale from 0 to 65535, where 65535 is 100% (`feSignal`)
 * @property absoluteSignal signal strength in thousandths of a decibel (`feAbsoluteSignal`), sent by v44 servers
 * @property bitErrorRate bit-error-rate value reported by the frontend (`feBER`)
 * @property uncorrectedBlockCount uncorrected block count reported by the frontend (`feUNC`)
 */
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

/**
 * Descrambling observations for one subscription, including PID, access and provider identifiers, ECM timing, hop count, and optional source labels.
 *
 * @property subscriptionId identifier of the subscription (`subscriptionId`)
 * @property pid transport stream PID being descrambled (`pid`)
 * @property conditionalAccessId conditional access system identifier, CAID (`caid`)
 * @property providerId conditional access provider identifier (`provid`)
 * @property hopCount number of hops to the descrambling source (`hops`)
 * @property cardSystem name of the card system (`cardsystem`); `null` when the server omitted it
 * @property reader name of the card reader (`reader`); `null` when the server omitted it
 * @property source origin of the descrambling answer (`from`); `null` when the server omitted it
 * @property protocol descrambling protocol name (`protocol`); `null` when the server omitted it
 */
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

/**
 * Carries the signed playback [speed] reported by the server for one subscription.
 *
 * @property subscriptionId identifier of the subscription (`subscriptionId`)
 * @property speed signed playback speed in percent of normal speed (`speed`); 100 is normal playback and negative values rewind
 */
public data class HtspSubscriptionSpeedMessage(
    public val subscriptionId: Long,
    public val speed: Int,
) : HtspServerMessage {
    init {
        requireU32("subscriptionId", subscriptionId)
    }

    override fun toString(): String = "HtspSubscriptionSpeedMessage(<redacted>)"
}

/**
 * Timeshift state for one subscription: fullness, current shift, optional start and end bounds, and optional speed.
 *
 * @property subscriptionId identifier of the subscription (`subscriptionId`)
 * @property speed signed playback speed (`speed`); `null` when absent, as the pinned server never sends it here
 */
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

/**
 * Reports the server result of a subscription skip with optional absolute and error flags, time coordinate, and byte coordinate.
 *
 * @property subscriptionId identifier of the subscription (`subscriptionId`)
 * @property sizeBytes byte coordinate of the skip, absolute or relative to the current position (`size`); `null` when absent, as for time skips
 */
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
