package at.bernhardberger.tvheadend.htsp.jsonapi

import java.util.Collections

/** Closed value vocabulary transported by the provisional JSON API bridge. */
@HtspJsonApi
public sealed interface HtspApiValue

/** A recursively nestable map or list response payload. */
@HtspJsonApi
public sealed interface HtspApiContainer : HtspApiValue

/** Ordered immutable string-keyed object. */
@HtspJsonApi
public class HtspApiObject private constructor(entries: Array<out Pair<String, HtspApiValue>>) : HtspApiContainer {
    private val values: Map<String, HtspApiValue> =
        Collections.unmodifiableMap(linkedMapOf(*entries))
    public val size: Int
        get() = values.size
    public val keys: Set<String>
        get() = values.keys

    public operator fun get(name: String): HtspApiValue? = values[name]

    @JvmSynthetic
    internal fun forEachEntry(action: (String, HtspApiValue) -> Unit) {
        values.forEach(action)
    }

    internal companion object {
        @JvmSynthetic
        internal fun create(entries: Array<out Pair<String, HtspApiValue>>): HtspApiObject = HtspApiObject(entries)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is HtspApiObject && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "HtspApiObject(values=$values)"
}

/** Ordered immutable recursive list. */
@HtspJsonApi
public class HtspApiList private constructor(values: Array<out HtspApiValue>) : HtspApiContainer {
    private val values: Array<out HtspApiValue> = values.copyOf()
    public val size: Int
        get() = values.size

    public operator fun get(index: Int): HtspApiValue = values[index]

    @JvmSynthetic
    internal fun forEachValue(action: (HtspApiValue) -> Unit) {
        values.forEach(action)
    }

    internal companion object {
        @JvmSynthetic
        internal fun create(values: Array<out HtspApiValue>): HtspApiList = HtspApiList(values)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is HtspApiList && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = "HtspApiList(values=${values.contentToString()})"
}

/** Exact UTF-8 string value. */
@HtspJsonApi
@JvmInline
public value class HtspApiString(public val value: String) : HtspApiValue

/** Exact signed 64-bit integer value. */
@HtspJsonApi
@JvmInline
public value class HtspApiLong(public val value: Long) : HtspApiValue

/** Exact HTSP boolean value. */
@HtspJsonApi
@JvmInline
public value class HtspApiBoolean(public val value: Boolean) : HtspApiValue

/** Defensive arbitrary-width binary value. */
@HtspJsonApi
public class HtspApiBinary(bytes: ByteArray) : HtspApiValue {
    private val value = bytes.copyOf()

    public fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is HtspApiBinary && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "HtspApiBinary(size=${value.size})"
}

/** Defensive, distinct fixed-width HTSP UUID value. */
@HtspJsonApi
public class HtspApiUuid(bytes: ByteArray) : HtspApiValue {
    private val value = bytes.copyOf()

    init {
        require(value.size == 16) { "HTSP UUID must contain exactly 16 bytes" }
    }

    public fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is HtspApiUuid && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "HtspApiUuid(<redacted>)"
}

/** Bounded project-owned factory for an ordered API object. */
@HtspJsonApi
public fun htspApiObject(vararg entries: Pair<String, HtspApiValue>): HtspApiObject =
    HtspApiObject.create(entries)

/** Bounded project-owned factory for an ordered API list. */
@HtspJsonApi
public fun htspApiList(vararg values: HtspApiValue): HtspApiList =
    HtspApiList.create(values)
