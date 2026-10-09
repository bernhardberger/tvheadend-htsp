package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class PublicApiOutcomesTest {
    @Test
    fun `decoder outcome hierarchy is internal`() {
        val scope = Konsist.scopeFromDirectory("src/main/kotlin")
        scope.classes().filter { it.name == "HtspServerMessageDecoded" }
            .assertTrue { it.hasInternalModifier }
        scope.interfaces().filter { it.name == "HtspServerMessageDecodeResult" }
            .assertTrue { it.hasInternalModifier }
        scope.objects().filter { it.name in setOf("HtspServerMessageUnknownMethod", "HtspServerMessageMalformedKnownMessage") }
            .assertTrue { it.hasInternalModifier }
    }

    @Test
    fun `raw server message decoder is not public`() {
        Konsist.scopeFromDirectory("src/main/kotlin")
            .functions()
            .filter { it.name == "decodeHtspServerMessage" }
            .assertTrue { it.hasInternalModifier || it.hasPrivateModifier }
    }

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
