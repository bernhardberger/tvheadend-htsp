package at.bernhardberger.tvheadend.htsp.wire

/** Internal wire marker preserving UUID type identity through raw codec decoding. */
internal class `HtspWireUuid-internal`(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun bytes(): ByteArray = value.copyOf()
}

internal typealias HtspWireUuid = `HtspWireUuid-internal`
