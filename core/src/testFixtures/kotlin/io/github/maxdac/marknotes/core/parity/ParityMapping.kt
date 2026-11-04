package io.github.maxdac.marknotes.core.parity

import kotlinx.serialization.json.JsonElement

/** How a shared spec fixture file is run on Android. */
sealed interface ParityMapping {
    /**
     * Runs each case through the real implementation. [pendingSpecIssues] maps case names to unexpected
     * differences reported to the spec and not yet triaged there (skipped with a TODO).
     */
    class Run(
        val pendingSpecIssues: Map<String, String> = emptyMap(),
        val run: (FixtureCase) -> JsonElement
    ) : ParityMapping

    /** The logic lives outside `:core`; [where] names the test that runs the fixture. */
    class Elsewhere(val where: String) : ParityMapping
}
