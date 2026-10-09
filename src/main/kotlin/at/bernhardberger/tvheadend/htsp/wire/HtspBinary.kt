package at.bernhardberger.tvheadend.htsp.wire

import java.util.Collections

/** Owned binary protocol data with content value semantics. */
public class HtspBinary private constructor(bytes: ByteArray, copy: Boolean) {
    private val content: ByteArray = if (copy) bytes.copyOf() else bytes

    /** Creates an owned value by defensively copying [bytes]. */
    public constructor(bytes: ByteArray) : this(bytes, copy = true)

    /** Number of bytes in this value. */
    public val size: Int
        get() = content.size

    /**
     * Copies the content prefix that fits at byte index [destinationOffset].
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

    /**
     * Writes all content at [destination]'s position and advances it, without an intermediate array.
     * @return the number of bytes copied
     * @throws java.nio.BufferOverflowException if the remaining destination space is insufficient
     * @throws java.nio.ReadOnlyBufferException if the destination is read-only
     */
    public fun copyInto(destination: java.nio.ByteBuffer): Int {
        destination.put(content)
        return content.size
    }

    /** Returns a new copy on every access. */
    public fun toByteArray(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is HtspBinary && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "HtspBinary(size=${content.size})"

    internal companion object {
        @JvmSynthetic
        internal fun takeOwnership(bytes: ByteArray): HtspBinary = HtspBinary(bytes, copy = false)
    }
}

internal const val HTSP_U32_MAX: Long = 0xffff_ffffL
internal const val MAX_FILE_READ_SIZE_BYTES: Long = 16L * 1024L * 1024L

internal fun requireU32(name: String, value: Long) {
    require(value in 0L..HTSP_U32_MAX) { "$name must be in the HTSP u32 range" }
}

internal fun <T> List<T>.immutableSnapshot(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
