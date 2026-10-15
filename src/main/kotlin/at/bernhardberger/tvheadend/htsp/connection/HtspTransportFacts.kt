package at.bernhardberger.tvheadend.htsp.connection

import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot

/**
 * Safe, read-only HTSP server identity and access observations from one successful handshake.
 *
 * Every property is optional: `null` means the wire field was absent or not representable.
 * Empty strings, empty capability lists, zero limits, and explicit `false` access flags are
 * observed values and are distinct from unknown. This type never carries endpoint, credential,
 * digest, challenge, raw message, sequence, subscription, generation, or throwable data.
 *
 * @property serverName server program name from `hello` (`servername`)
 * @property serverVersion server software version string from `hello` (`serverversion`)
 * @property webRoot server web root path from `hello` (`webroot`)
 * @property language server language from `hello` (`language`)
 * @property serverCapabilities immutable capability names from `hello` (`servercapability`)
 * @property apiVersion JSON API version from `hello` (`api_version`); see [at.bernhardberger.tvheadend.htsp.jsonapi.ApiRequest]
 * @property admin whether `authenticate` reported administrator access (`admin`)
 * @property streaming whether `authenticate` reported streaming access (`streaming`)
 * @property dvr whether `authenticate` reported DVR access (`dvr`)
 * @property failedDvr whether `authenticate` reported access to failed recordings (`faileddvr`)
 * @property anonymous whether the server anonymizes this account's HTSP access and omits stream details such as signal strength and input source (`anonymous`)
 * @property limitAll maximum number of connections for this account (`limitall`); zero means no limit
 * @property limitDvr maximum number of recordings for this account (`limitdvr`); zero means no limit
 * @property limitStreaming maximum number of streaming connections for this account (`limitstreaming`); zero means no limit
 * @property uiLevel web interface level for this account (`uilevel`): 0 basic, 1 advanced, 2 expert
 * @property uiLanguage UI language reported by `authenticate` (`uilanguage`)
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

    override fun toString(): String = "HtspServerFacts(<redacted>)"
}
