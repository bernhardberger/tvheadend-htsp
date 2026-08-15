package at.bernhardberger.tvheadend.htsp.requests

/** Exact stream-profile identifier and display name returned by protocol metadata. */
public data class StreamProfile(
    val id: String,
    val name: String,
)
