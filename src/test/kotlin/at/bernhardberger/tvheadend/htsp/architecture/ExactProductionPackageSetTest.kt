package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExactProductionPackageSetTest {
    // The production package set is exactly the five shallow HTSP owner packages.
    @Test
    fun `production declares the exact package set`() {
        val expected = setOf(
            "at.bernhardberger.tvheadend.htsp.connection",
            "at.bernhardberger.tvheadend.htsp.jsonapi",
            "at.bernhardberger.tvheadend.htsp.messages",
            "at.bernhardberger.tvheadend.htsp.requests",
            "at.bernhardberger.tvheadend.htsp.wire",
        )

        val actual = Konsist.scopeFromDirectory("src/main/kotlin").packages.map { it.name }.toSet()

        assertEquals(expected, actual)
    }
}
