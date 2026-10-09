package at.bernhardberger.tvheadend.htsp.connection

/**
 * Safe, read-only HTSP server identity and access observations from one successful handshake.
 *
 * Every property is optional: `null` means the wire field was absent or not representable.
 * Empty strings, empty capability lists, zero limits, and explicit `false` access flags are
 * observed values and are distinct from unknown. This type never carries endpoint, credential,
 * digest, challenge, raw message, sequence, subscription, generation, or throwable data.
 */
public data class HtspServerFacts(
    val serverName: String? = null,
    val serverVersion: String? = null,
    val webRoot: String? = null,
    val language: String? = null,
    val serverCapabilities: List<String>? = null,
    val apiVersion: Long? = null,
    val admin: Boolean? = null,
    val streaming: Boolean? = null,
    val dvr: Boolean? = null,
    val failedDvr: Boolean? = null,
    val anonymous: Boolean? = null,
    val limitAll: Long? = null,
    val limitDvr: Long? = null,
    val limitStreaming: Long? = null,
    val uiLevel: Long? = null,
    val uiLanguage: String? = null,
)
