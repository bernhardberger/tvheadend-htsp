package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class HtspJsonApiContainmentTest {
    // JSON API bridge declarations and uses stay in jsonapi, except the generated request-model bridge.
    @Test
    fun `JSON API bridge stays with its owners`() {
        Konsist
            .scopeFromDirectory("src/main/kotlin")
            .files
            .filterNot { file ->
                file.packagee?.name == "at.bernhardberger.tvheadend.htsp.jsonapi" ||
                    file.name == "GeneratedHtspRequests"
            }
            .assertFalse { file ->
                file.imports.any { declaration ->
                    declaration.name.startsWith("at.bernhardberger.tvheadend.htsp.jsonapi.")
                }
            }
    }
}
