package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class HtspJsonApiContainmentTest {
    // JSON API declarations and uses stay in jsonapi, except for request codec mapping.
    @Test
    fun `JSON API bridge stays with its owners`() {
        Konsist
            .scopeFromDirectory("src/main/kotlin")
            .files
            .filterNot { file ->
                file.packagee?.name == "at.bernhardberger.tvheadend.htsp.jsonapi" ||
                    file.name == "HtspRequestCodec"
            }
            .assertFalse { file ->
                file.imports.any { declaration ->
                    declaration.name.startsWith("at.bernhardberger.tvheadend.htsp.jsonapi.")
                }
            }
    }
}
