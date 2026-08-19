package at.bernhardberger.tvheadend.htsp.messages

internal enum class HtspTimestampClock {
    MICROSECONDS,
    NINETY_KHZ;

    fun toMicroseconds(value: Long): Long = when (this) {
        MICROSECONDS -> value
        NINETY_KHZ -> {
            val quotient = value / NINETY_KHZ_REDUCTION_DENOMINATOR
            val remainder = value % NINETY_KHZ_REDUCTION_DENOMINATOR
            Math.addExact(
                Math.multiplyExact(quotient, NINETY_KHZ_REDUCTION_NUMERATOR),
                remainder * NINETY_KHZ_REDUCTION_NUMERATOR / NINETY_KHZ_REDUCTION_DENOMINATOR,
            )
        }
    }
}

private const val NINETY_KHZ_REDUCTION_NUMERATOR = 100L
private const val NINETY_KHZ_REDUCTION_DENOMINATOR = 9L
