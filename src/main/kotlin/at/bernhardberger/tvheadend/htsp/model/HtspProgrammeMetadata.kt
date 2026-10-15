package at.bernhardberger.tvheadend.htsp.model

import at.bernhardberger.tvheadend.htsp.wire.immutableSnapshot
import at.bernhardberger.tvheadend.htsp.wire.HtspFieldReader
import at.bernhardberger.tvheadend.htsp.wire.orderedFields
import java.util.Collections

/**
 * Immutable ordered programme credits for event and EPG replies.
 * A person credited in several roles appears once per role, in wire order.
 * Produced by src/epggrab/module/xmltv.c:726–740, sent as `credits` by
 * src/htsp_server.c:1373–1374 and `cred` by src/epg.c:1766–1768.
 */
public class HtspProgrammeCredits(entries: List<HtspProgrammeCredit>) {
    /** Ordered snapshot; repeated names and unknown roles are representable. */
    public val entries: List<HtspProgrammeCredit> = entries.immutableSnapshot()

    override fun equals(other: Any?): Boolean = other is HtspProgrammeCredits && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()
    override fun toString(): String = "HtspProgrammeCredits(<redacted>)"
}

/** One programme credit from pinned htsp_server.c:1373–1374; a person can have several roles. */
public data class HtspProgrammeCredit(
    /** Person name, the key of the wire credits map. */
    public val name: String,
    /** Role label, the value of the wire credits map. */
    public val role: String,
) {
    override fun toString(): String = "HtspProgrammeCredit(<redacted>)"
}

/** Localized HbbTV application title (src/input/mpegts/dvb_psi_hbbtv.c:104–108). */
public data class HtspHbbtvTitle(
    /** Localized application name, wire `name`. */
    public val name: String,
    /** Language when available, wire `lang`. */
    public val language: String? = null,
) {
    override fun toString(): String = "HtspHbbtvTitle(<redacted>)"
}

/**
 * Application metadata published to channel-service consumers (src/input/mpegts/dvb_psi_hbbtv.c:139–150).
 * Sent as service `hbbtv` by pinned htsp_server.c:911–913.
 * Legacy service configuration may omit fields; absent or invalid scalars decode as null
 * and unusable titles are skipped (src/service.c:1788–1794).
 */
public class HtspHbbtvApplication(
    titles: List<HtspHbbtvTitle>,
    /** Application URL when available, wire `url`. */
    public val url: String? = null,
    /** Server visibility string: none, apps, reserved, or all; future strings are retained. */
    public val visibility: String? = null,
) {
    /** Ordered, immutable localized titles. */
    public val titles: List<HtspHbbtvTitle> = titles.immutableSnapshot()
    override fun equals(other: Any?): Boolean = other is HtspHbbtvApplication &&
        titles == other.titles && url == other.url && visibility == other.visibility
    override fun hashCode(): Int = 31 * (31 * titles.hashCode() + (url?.hashCode() ?: 0)) + (visibility?.hashCode() ?: 0)
    override fun toString(): String = "HtspHbbtvApplication(<redacted>)"
}

/** HbbTV applications indexed by section string (src/input/mpegts/dvb_psi_hbbtv.c:178–183). */
public class HtspHbbtvApplications(sections: Map<String, List<HtspHbbtvApplication>>) {
    /** Deep immutable snapshot of section lists. */
    public val sections: Map<String, List<HtspHbbtvApplication>> = Collections.unmodifiableMap(
        sections.mapValues { (_, applications) -> applications.immutableSnapshot() },
    )
    override fun equals(other: Any?): Boolean = other is HtspHbbtvApplications && sections == other.sections
    override fun hashCode(): Int = sections.hashCode()
    override fun toString(): String = "HtspHbbtvApplications(<redacted>)"
}

internal fun decodeProgrammeCredits(
    fields: Map<*, *>,
    name: String,
    fail: () -> Nothing,
): HtspProgrammeCredits? {
    if (!fields.containsKey(name)) return null
    // Not requiredObject: its copy would lose the repeated names the producer appends.
    val source = fields[name] as? Map<*, *> ?: fail()
    val entries = source.orderedFields().map { (key, value) ->
        HtspProgrammeCredit(key as? String ?: fail(), value as? String ?: fail())
    }
    return HtspProgrammeCredits(entries)
}

internal fun decodeHbbtvApplications(fields: Map<*, *>, fail: () -> Nothing): HtspHbbtvApplications? {
    if (!fields.containsKey("hbbtv")) return null
    val sections = HtspFieldReader(fields, fail).requiredObject("hbbtv")
    return HtspHbbtvApplications(sections.keys.associate { key ->
        val section = key as? String ?: fail()
        // Persisted service configuration is copied without schema validation (src/service.c:1788–1794).
        section to (sections[section] as? List<*>).orEmpty().mapNotNull { value ->
            val application = value as? Map<*, *> ?: return@mapNotNull null
            HtspHbbtvApplication(
                titles = (application["title"] as? List<*>).orEmpty().mapNotNull titleEntry@{ value ->
                    val title = value as? Map<*, *> ?: return@titleEntry null
                    val name = title["name"] as? String ?: return@titleEntry null
                    HtspHbbtvTitle(name, title["lang"] as? String)
                },
                url = application["url"] as? String,
                visibility = application["visibility"] as? String,
            )
        }
    })
}
