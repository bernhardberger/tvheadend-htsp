package at.bernhardberger.tvheadend.htsp.wire

/** One decoded field map, including its envelope fields and any owned binary payload. */
internal data class HtspWireMessage(val fields: Map<String, Any?>) {
    val method: String? = fields["method"] as? String

    val seq: Int?
        get() = (fields["seq"] as? Long)?.takeIf { it in 0L..0xFFFF_FFFFL }?.toInt()

    val rawPayload: ByteArray? = when (method) {
        "muxpkt" -> fields["payload"] as? ByteArray
        else -> null
    }
}
