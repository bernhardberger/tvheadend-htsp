package at.bernhardberger.tvheadend.htsp.connection

internal data class `SubscriptionStatus-internal`(
    val id: Int,
    val state: String? = null,
    val subscriptionError: String? = null,
)

internal typealias SubscriptionStatus = `SubscriptionStatus-internal`
