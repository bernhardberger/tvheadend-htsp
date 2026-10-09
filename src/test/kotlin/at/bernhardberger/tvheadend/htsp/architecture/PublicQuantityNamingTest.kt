package at.bernhardberger.tvheadend.htsp.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoContainingDeclarationProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class PublicQuantityNamingTest {
    @Test
    fun `public properties and constructor and function parameters name time units`() {
        val scope = Konsist.scopeFromDirectory("src/main/kotlin")
        val names = scope.properties().filter { it.hasPublicModifier }.map {
            owner(it.containingDeclaration) to it.name
        } + scope.classes().filter { it.hasPublicModifier }.flatMap { declaration ->
            declaration.constructors.filterNot { it.hasPrivateModifier || it.hasInternalModifier }
                .flatMap { it.parameters }.map { owner(declaration) to it.name }
        } + scope.functions().filter { it.hasPublicModifier }.flatMap { function ->
            function.parameters.map { owner(function) to it.name }
        }
        val violations = names.distinct().filter { (declaration, name) ->
            needsTimeUnit(name) && (declaration to name) !in exceptions
        }
        assertEquals(emptyList<Pair<String, String>>(), violations)
    }

    @Test
    fun `classifier recognizes time words and unit suffixes`() {
        listOf("start", "lastUpdated", "modified", "shift", "period", "age", "interval", "maxTime",
            "frameDuration", "timeout", "firstAired").forEach { assertEquals(true, needsTimeUnit(it), it) }
        listOf("startEpochSeconds", "delayUs", "timeoutMs", "retentionDays", "startMinutesSinceMidnight",
            "size", "destinationOffset", "message", "subscriptionId").forEach {
            assertEquals(false, needsTimeUnit(it), it)
        }
    }

    private fun needsTimeUnit(name: String): Boolean {
        val parts = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(' ')
        return parts.any { it in timeParts } && suffixes.none(name::endsWith)
    }

    private fun owner(declaration: KoBaseDeclaration?): String {
        if (declaration == null || declaration is KoFileDeclaration) return ""
        val parent = (declaration as? KoContainingDeclarationProvider)?.containingDeclaration
        return listOf(owner(parent), (declaration as? KoNameProvider)?.name.orEmpty())
            .filter { it.isNotEmpty() }.joinToString(".")
    }

    private val timeParts = setOf(
        "start", "stop", "end", "time", "delay", "duration", "extra", "retention",
        "removal", "position", "aired", "update", "timeout", "updated", "modified",
        "shift", "period", "age", "interval",
    )
    private val suffixes = setOf("Ms", "Us", "Seconds", "Minutes", "Days", "MinutesSinceMidnight")
    private val exceptions = setOf(
        // Negotiated-clock coordinates must remain exact for outgoing seek/skip commands.
        "HtspTimeshiftStatusMessage" to "start",
        "HtspTimeshiftStatusMessage" to "end",
        "HtspTimeshiftStatusMessage" to "shift",
        "HtspSubscriptionSkipMessage" to "time",
        "SubscriptionSeekPosition.Time" to "time",
        // Discriminated time-or-byte coordinate, not a scalar quantity.
        "SubscriptionSeekRequest" to "position",
        "SubscriptionSkipRequest" to "position",
        "subscriptionSeek" to "position",
        "subscriptionSkip" to "position",
        // Geographic satellite description, not elapsed time.
        "HtspSubscriptionSourceInfo" to "satellitePosition",
        // Pinned sender forwards ecmtime without establishing a unit; do not invent one.
        "HtspDescrambleInfoMessage" to "ecmTime",
        // Content age classifications, not elapsed-time quantities (including custom copy parameters).
        "AddDvrEntryRequest" to "ageRating",
        "UpdateDvrEntryRequest" to "ageRating",
        "HtspEvent" to "ageRating",
        "HtspEpgBroadcastObject" to "ageRating",
        "HtspEventUpdateMessage" to "ageRating",
        "HtspDvrEntryAddMessage" to "ageRating",
        "HtspDvrEntryUpdateMessage" to "ageRating",
        "addDvrEntry" to "ageRating",
        "updateDvrEntry" to "ageRating",
        "HtspEventUpdateMessage.copy" to "ageRating",
        "HtspDvrEntryAddMessage.copy" to "ageRating",
        "HtspDvrEntryUpdateMessage.copy" to "ageRating",
    )
}
