package at.bernhardberger.tvheadend.htsp

import java.util.Collections

/** A typed asynchronous HTSP server message. It never represents an RPC reply. */
public sealed interface HtspServerMessage

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

internal const val SERVER_MESSAGE_U32_MAX: Long = 0xffff_ffffL

internal fun requireServerU32(name: String, value: Long) {
    require(value in 0L..SERVER_MESSAGE_U32_MAX) { "$name must be in the HTSP u32 range" }
}

internal fun <T> List<T>.immutableServerSnapshot(): List<T> =
    Collections.unmodifiableList(ArrayList(this))
