package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class PublicApiOutcomesTest {
    // Public suspending server round trips return typed outcomes; lifecycle commands may return Unit.
    @Test
    fun `public suspending calls return typed outcomes`() {
        val acceptedReturnTypes = setOf("HtspConnectOutcome", "HtspResult", "Unit")

        Konsist
            .scopeFromDirectory("src/main/kotlin")
            .functions()
            .filter { function ->
                function.hasPublicModifier && function.hasSuspendModifier
            }
            .assertTrue { function ->
                function.returnType?.bareSourceType.orEmpty().ifEmpty { "Unit" } in acceptedReturnTypes
            }
    }
}
