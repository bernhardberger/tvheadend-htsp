package at.bernhardberger.tvheadend.htsp.wire

import java.util.Collections

/** Defensively copied binary protocol data with content value semantics. */
public class HtspBinary(bytes: ByteArray) {
    private val content: ByteArray = bytes.copyOf()

    /** Number of bytes in this value. */
    public val size: Int
        get() = content.size

    /**
     * Copies the content prefix that fits at [destinationOffset].
     *
     * @return the number of bytes copied
     * @throws IndexOutOfBoundsException when [destinationOffset] is outside [destination]
     */
    public fun copyInto(destination: ByteArray, destinationOffset: Int = 0): Int {
        if (destinationOffset !in 0..destination.size) {
            throw IndexOutOfBoundsException("destinationOffset is outside the destination")
        }
        val copied = minOf(content.size, destination.size - destinationOffset)
        content.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            endIndex = copied,
        )
        return copied
    }

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
