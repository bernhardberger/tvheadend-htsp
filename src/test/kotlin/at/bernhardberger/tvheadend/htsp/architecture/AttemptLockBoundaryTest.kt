package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AttemptLockBoundaryTest {
    @Test
    fun `attempt locking cannot bypass deferred effects or shadow synchronized`() {
        val connection = "src/main/kotlin/at/bernhardberger/tvheadend/htsp/connection"
        Konsist.scopeFromDirectory(connection).functions().assertTrue { it.name != "synchronized" }
        val scope = Konsist.scopeFromDirectory(connection)
        val acquisitions = Regex("""\bsynchronized\s*\(([^)]*)\)""")
        val rawAttemptLock = Regex("""\bsynchronized\s*\(\s*(?:this\s*\.\s*)?connectionAttemptLock\b""")
        val aliasImport = Regex("""\bimport\s+kotlin\s*\.\s*synchronized\s+as\b""")
        Files.walk(Path.of(connection)).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { path ->
                val source = withoutComments(path.readText())
                assertFalse(aliasImport.containsMatchIn(source), path.toString())
                // Compiler private visibility is the primary guard outside the owner; this text check is a backstop.
                if (path.fileName.toString() == "HtspAttemptMonitor.kt") {
                    assertEquals(
                        listOf("statePublicationMonitor", "connectionAttemptLock"),
                        acquisitions.findAll(source).map { it.groupValues[1].trim() }.toList(),
                        path.toString(),
                    )
                } else {
                    assertEquals(0, rawAttemptLock.findAll(source).count(), path.toString())
                }
            }
        }
        val owner = scope.files.single { it.name == "HtspAttemptMonitor" }
        owner.functions().forEach { function ->
            val expected = when (function.name) {
                "withAttemptLock" -> listOf("connectionAttemptLock")
                "applyState" -> listOf("statePublicationMonitor")
                else -> emptyList()
            }
            assertEquals(
                expected,
                acquisitions.findAll(withoutComments(function.text)).map { it.groupValues[1].trim() }.toList(),
                function.name,
            )
        }
    }

    private fun withoutComments(source: String): String =
        source.replace(Regex("""/\*[\s\S]*?\*/|//[^\r\n]*"""), " ")
}
