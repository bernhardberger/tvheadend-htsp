package at.bernhardberger.tvheadend.htsp.wire

/** One decoded field map, including its envelope fields and any owned binary payload. */
internal data class HtspWireMessage(val fields: Map<String, Any?>) {
    val method: String? = fields["method"] as? String

    val seq: Int?
        get() = (fields["seq"] as? Long)?.takeIf { it in 0L..0xFFFF_FFFFL }?.toInt()

    val rawPayload: ByteArray? = when (method) {
        "muxpkt" -> fields["payload"] as? ByteArray
        else -> null
    }
}

/**
 * Decoded HTSP map whose lookups see the first field for each name, like upstream
 * `htsmsg_field_find` (`src/htsmsg.c:171–182`).
 *
 * Upstream iterates repeated names where they carry meaning, for example programme
 * credits appended per person and role (`src/epggrab/module/xmltv.c:727,740`), so the
 * map also keeps every named field in wire order once a name repeats.
 */
internal class HtspWireMap : LinkedHashMap<String, Any?>() {
    private var repeated: ArrayList<Pair<String, Any?>>? = null

    /** Every named field in wire order, or null when no name repeats. */
    val repeatedFields: List<Pair<String, Any?>>?
        get() = repeated

    fun addField(name: String, value: Any?) {
        val all = repeated
        when {
            all != null -> all += name to value
            containsKey(name) -> repeated = entries.mapTo(ArrayList()) { it.key to it.value }.apply { add(name to value) }
        }
        putIfAbsent(name, value)
    }
}

/** Named fields of a decoded map in wire order, including repeated names. */
internal fun Map<*, *>.orderedFields(): List<Pair<Any?, Any?>> =
    (this as? HtspWireMap)?.repeatedFields ?: entries.map { it.key to it.value }
