package at.bernhardberger.tvheadend.htsp.wire

import java.util.Collections

/** Defensively copied binary protocol data with content value semantics. */
public class HtspBinary(bytes: ByteArray) {
    private val content: ByteArray = bytes.copyOf()

    /** Returns a new copy on every access. */
    public fun toByteArray(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is HtspBinary && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "HtspBinary(size=${content.size})"
}

internal const val HTSP_U32_MAX: Long = 0xffff_ffffL
internal const val MAX_FILE_READ_SIZE_BYTES: Long = 16L * 1024L * 1024L

internal fun requireU32(name: String, value: Long) {
    require(value in 0L..HTSP_U32_MAX) { "$name must be in the HTSP u32 range" }
}

internal fun <T> List<T>.immutableSnapshot(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
