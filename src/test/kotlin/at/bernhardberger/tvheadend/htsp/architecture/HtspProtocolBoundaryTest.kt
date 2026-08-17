package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class HtspProtocolBoundaryTest {
    // Production code lives only in the five owner packages and depends only on siblings, Kotlin/JDK, and coroutines.
    @Test
    fun `production code stays within the HTSP dependency boundary`() {
        val ownerPrefix = "at.bernhardberger.tvheadend.htsp."
        val owners = setOf("connection", "jsonapi", "messages", "requests", "wire")
        val allowedExternalPrefixes = listOf(
            "java.",
            "javax.",
            "jdk.",
            "kotlin.",
            "kotlinx.coroutines.",
            "org.w3c.dom.",
            "org.xml.sax.",
        )

        Konsist.scopeFromDirectory("src/main/kotlin").files.assertTrue { file ->
            val packageName = file.packagee?.name.orEmpty()
            val packageOwner = packageName.removePrefix(ownerPrefix).substringBefore('.')
            packageName.startsWith(ownerPrefix) &&
                packageOwner in owners &&
                file.imports.all { declaration ->
                    val imported = declaration.name.removeSuffix(".*")
                    if (imported.startsWith(ownerPrefix)) {
                        imported.removePrefix(ownerPrefix).substringBefore('.') in owners
                    } else {
                        allowedExternalPrefixes.any(imported::startsWith)
                    }
                }
        }
    }
}
