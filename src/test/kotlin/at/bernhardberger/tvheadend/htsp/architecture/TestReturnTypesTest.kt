package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

internal class TestReturnTypesTest {
    @Test
    fun `every test returns Unit`() {
        Konsist.scopeFromDirectory("src/test").functions()
            .filter { it.hasAnnotationOf(Test::class) }
            .assertTrue { function ->
                val owner = function.containingDeclaration as? KoClassDeclaration ?: return@assertTrue false
                // Source-only inference cannot tell whether runBlocking returns Unit or Boolean.
                Class.forName(jvmName(owner)).declaredMethods
                    .singleOrNull { it.name == function.name && it.isAnnotationPresent(Test::class.java) }
                    ?.returnType == Void.TYPE
            }
    }

    private fun jvmName(declaration: KoClassDeclaration): String {
        val parent = declaration.containingDeclaration as? KoClassDeclaration
        return if (parent == null) checkNotNull(declaration.fullyQualifiedName)
        else "${jvmName(parent)}\$${declaration.name}"
    }
}
