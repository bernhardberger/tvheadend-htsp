package at.bernhardberger.tvheadend.htsp.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class GovernanceDocumentationTest {
    @Test
    fun `attribution independence and extraction provenance remain intact`() {
        val requiredPhrases = mapOf(
            "README.md" to listOf(
                "This independently maintained GPLv3 library descends from " +
                    "[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream).",
                "It is not official TVHeadend software and is not affiliated with or endorsed by the " +
                    "TVHeadend project; the TVHeadend name describes compatibility only.",
            ),
            "NOTICE.md" to listOf(
                "Copyright (C) 2026 Bernhard Berger",
                "SPDX-License-Identifier: GPL-3.0-only",
                "This HTSP protocol library began as a modified extract of " +
                    "[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream) (GPL-3.0, " +
                    "Copyright (C) 2026 the tvhstream authors).",
                "ideas and code from [TVHClient](https://github.com/rsiebert/TVHClient).",
                "The standalone repository begins with the HTSP protocol extraction baseline instead " +
                    "of embedding the predecessor application's unrelated Git history.",
                "Bernhard Berger has modified and extended the extracted code since August 2026.",
                "library remains a derivative work of tvhstream.",
                "at the release tag `v<version>`.",
                "This library is not affiliated with, endorsed by, or sponsored by the " +
                    "[TVHeadend project](https://github.com/tvheadend/tvheadend).",
            ),
            "docs/licensing.md" to listOf(
                "The HTSP library is licensed under the GNU General Public License v3.0 only " +
                    "(SPDX `GPL-3.0-only`).",
                "This library is an independently maintained descendant of " +
                    "[Preclikos/tvhstream](https://github.com/Preclikos/tvhstream).",
                "It began as a modified extract of predecessor work and is not wholly original.",
                "The library therefore remains a derivative work of tvhstream under GPL-3.0-only.",
                "The standalone repository begins with the HTSP protocol extraction baseline instead " +
                    "of embedding the predecessor application's unrelated Git history.",
                "The library is developed and maintained independently of the TVHeadend project.",
                "It is not affiliated with, endorsed by, or sponsored by the TVHeadend project.",
                "The TVHeadend name describes compatibility only.",
            ),
            "docs/README.md" to listOf(
                "- [`licensing.md`](licensing.md): GPLv3 obligations, attribution, and project lineage.",
            ),
        )
        requiredPhrases.forEach { (relative, phrases) ->
            val document = Files.readString(Path.of(relative)).replace(Regex("\\s+"), " ")
            phrases.forEach { phrase ->
                assertTrue(document.contains(phrase), "$relative is missing required governance phrase: $phrase")
            }
        }

        val provenanceDigests = mapOf(
            "docs/extraction/source-to-filtered.tsv" to
                "c5fad8e6bd00bc6f692405cef79c661325df404c91a0a59ac47c1497f3bb7f27",
            "docs/extraction/manifest.json" to
                "d45c5da0048201eae10a6760d92ae4d6f1abf64ca1bb4e6a60d31dd610244270",
        )
        provenanceDigests.forEach { (relative, expected) ->
            val actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(relative))),
            )
            assertEquals(expected, actual, "$relative extraction provenance digest changed")
        }
    }
}
