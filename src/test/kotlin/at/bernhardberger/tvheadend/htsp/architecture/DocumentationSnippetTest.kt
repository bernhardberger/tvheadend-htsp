package at.bernhardberger.tvheadend.htsp.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Keeps documented code identical to the consumer-contract fixtures that compile against the
 * staged artifact. A `<!-- source: path -->` marker before a Kotlin block names the whole fixture;
 * `path#name` names the lines between `// region name` and `// endregion`.
 */
class DocumentationSnippetTest {
    @Test
    fun `marked documentation snippets match their compiled fixtures`() {
        val documents = listOf(Path.of("README.md")) + Files.walk(Path.of("docs")).use { paths ->
            paths.filter { path -> path.isRegularFile() && path.extension == "md" }.sorted().toList()
        }
        val snippets = documents.flatMap(::markedSnippets)
        assertTrue(snippets.isNotEmpty(), "No documentation snippet is bound to a compiled fixture")
        snippets.forEach { snippet ->
            assertEquals(
                fixtureText(snippet.source),
                snippet.code,
                "${snippet.document} snippet differs from ${snippet.source}",
            )
        }
    }

    private data class Snippet(val document: Path, val source: String, val code: String)

    private fun markedSnippets(document: Path): List<Snippet> {
        val text = Files.readString(document)
        val markers = MARKER.findAll(text).count()
        val snippets = SNIPPET.findAll(text).map { match ->
            Snippet(document, match.groupValues[1], match.groupValues[2])
        }.toList()
        assertEquals(markers, snippets.size, "$document has a source marker without a directly following Kotlin block")
        return snippets
    }

    private fun fixtureText(source: String): String {
        val path = source.substringBefore('#')
        assertTrue(path.startsWith("consumer-contract/src/"), "Snippet source must be a consumer-contract fixture: $source")
        val lines = Files.readString(Path.of(path)).trimEnd('\n').lines()
        val region = source.substringAfter('#', missingDelimiterValue = "")
        if (region.isEmpty()) return lines.joinToString("\n")
        val start = lines.indexOf("// region $region")
        assertTrue(start >= 0 && lines.count { it == "// region $region" } == 1, "Missing or repeated region: $source")
        val end = lines.subList(start + 1, lines.size).indexOf("// endregion")
        assertTrue(end >= 0, "Unterminated region: $source")
        return lines.subList(start + 1, start + 1 + end).joinToString("\n")
    }

    private companion object {
        val MARKER = Regex("""^<!-- source: \S+ -->$""", RegexOption.MULTILINE)
        val SNIPPET = Regex(
            """^<!-- source: (\S+) -->\n```kotlin\n(.*?)\n```$""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
