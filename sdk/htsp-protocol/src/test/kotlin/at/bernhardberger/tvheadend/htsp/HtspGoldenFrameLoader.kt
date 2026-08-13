package at.bernhardberger.tvheadend.htsp

/**
 * Loads one complete HTSP frame from `htsp-wire/<name>`. Whitespace separates
 * two-digit hexadecimal bytes; `#` starts a comment through the end of a line.
 */
internal fun loadHtspGoldenFrame(name: String): ByteArray {
    val resource = requireNotNull(GoldenFrameResourceAnchor::class.java.getResourceAsStream("/htsp-wire/$name")) {
        "Missing HTSP golden frame: $name"
    }
    val tokens = resource.bufferedReader().useLines { lines ->
        lines.flatMap { line -> line.substringBefore('#').trim().split(Regex("\\s+")).asSequence() }
            .filter(String::isNotEmpty)
            .toList()
    }
    return ByteArray(tokens.size) { index ->
        val token = tokens[index]
        require(token.matches(Regex("[0-9a-fA-F]{2}"))) { "Invalid hex byte '$token' in $name" }
        token.toInt(16).toByte()
    }
}

private object GoldenFrameResourceAnchor
