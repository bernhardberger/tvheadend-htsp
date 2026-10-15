package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot

/**
 * Safe, read-only HTSP server identity and access observations from one successful handshake.
 *
 * Every property is optional: `null` means the wire field was absent or not representable.
 * Empty strings, empty capability lists, zero limits, and explicit `false` access flags are
 * observed values and are distinct from unknown. This type never carries endpoint, credential,
 * digest, challenge, raw message, sequence, subscription, generation, or throwable data.
 */
@ConsistentCopyVisibility
public data class HtspServerFacts private constructor(
    val serverName: String?,
    val serverVersion: String?,
    val webRoot: String?,
    val language: String?,
    val serverCapabilities: List<String>?,
    val apiVersion: Long?,
    val admin: Boolean?,
    val streaming: Boolean?,
    val dvr: Boolean?,
    val failedDvr: Boolean?,
    val anonymous: Boolean?,
    val limitAll: Long?,
    val limitDvr: Long?,
    val limitStreaming: Long?,
    val uiLevel: Long?,
    val uiLanguage: String?,
    private val evolution: Unit,
) {
    /** Creates handshake observations with an immutable capability snapshot. */
    public constructor(
        serverName: String? = null,
        serverVersion: String? = null,
        webRoot: String? = null,
        language: String? = null,
        serverCapabilities: List<String>? = null,
        apiVersion: Long? = null,
        admin: Boolean? = null,
        streaming: Boolean? = null,
        dvr: Boolean? = null,
        failedDvr: Boolean? = null,
        anonymous: Boolean? = null,
        limitAll: Long? = null,
        limitDvr: Long? = null,
        limitStreaming: Long? = null,
        uiLevel: Long? = null,
        uiLanguage: String? = null,
    ) : this(
        serverName, serverVersion, webRoot, language, serverCapabilities?.immutableSnapshot(),
        apiVersion, admin, streaming, dvr, failedDvr, anonymous, limitAll, limitDvr,
        limitStreaming, uiLevel, uiLanguage, Unit,
    )

    /** Explicit no-argument bridge retained for future binary-compatible evolution. */
    @Deprecated("Retained for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor() : this(
        null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, Unit,
    )

    /** Replaces observations, taking a fresh immutable capability snapshot. */
    public fun copy(
        serverName: String? = this.serverName,
        serverVersion: String? = this.serverVersion,
        webRoot: String? = this.webRoot,
        language: String? = this.language,
        serverCapabilities: List<String>? = this.serverCapabilities,
        apiVersion: Long? = this.apiVersion,
        admin: Boolean? = this.admin,
        streaming: Boolean? = this.streaming,
        dvr: Boolean? = this.dvr,
        failedDvr: Boolean? = this.failedDvr,
        anonymous: Boolean? = this.anonymous,
        limitAll: Long? = this.limitAll,
        limitDvr: Long? = this.limitDvr,
        limitStreaming: Long? = this.limitStreaming,
        uiLevel: Long? = this.uiLevel,
        uiLanguage: String? = this.uiLanguage,
    ): HtspServerFacts = HtspServerFacts(
        serverName, serverVersion, webRoot, language, serverCapabilities,
        apiVersion, admin, streaming, dvr, failedDvr, anonymous, limitAll, limitDvr,
        limitStreaming, uiLevel, uiLanguage,
    )
}
