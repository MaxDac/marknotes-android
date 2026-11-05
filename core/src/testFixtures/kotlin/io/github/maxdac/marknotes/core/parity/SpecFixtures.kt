package io.github.maxdac.marknotes.core.parity

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue

/** One case of a shared spec fixture file (`spec/fixtures/<area>/<function>.json`). */
class FixtureCase(
    /** Fixture id, e.g. "front-matter/read-id". */
    val fixture: String,
    val name: String,
    val input: JsonObject,
    val expected: JsonElement,
    /** Reason from `knownDivergence.android`, or null when Android must match [expected]. */
    val knownDivergence: String?
) {
    override fun toString(): String = "$fixture: $name"

    fun string(key: String): String = input.getValue(key).jsonPrimitive.content

    fun stringOrNull(key: String): String? = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun int(key: String): Int = input.getValue(key).jsonPrimitive.content.toInt()

    /**
     * Compares [actual] with [expected]. With a known divergence (or a [pendingSpecIssue] not yet triaged in
     * shared spec) the output must still differ; the case is then reported as skipped with the reason, so a
     * fix is noticed and the entry removed.
     */
    fun check(actual: JsonElement, pendingSpecIssue: String? = null) {
        val divergence = knownDivergence?.let { "Known divergence: $it" }
            ?: pendingSpecIssue?.let { "TODO(spec): $it" }
        if (divergence == null) {
            assertEquals(toString(), expected, actual)
            return
        }
        assertNotEquals("$this now matches the spec: remove its divergence entry", expected, actual)
        assumeTrue("$divergence (actual: $actual)", false)
    }
}

/**
 * The fixture files under one `fixtures/` folder, as seen by the runner of [edition]: `android` for Libre,
 * `android-pro` for Pro. A file with an `appliesTo` list that lacks [edition] is recognised but not applicable:
 * it is reported as skipped, never run and never counted as unmapped. Files without `appliesTo` apply to all.
 */
class FixtureSet(private val dir: File, val edition: String = SpecFixtures.APP_KEY) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Ids ("area/function") of every fixture file, sorted, applicable or not. */
    fun allIds(): List<String> = dir.walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .map { it.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".json") }
        .sorted()
        .toList()

    /** The runner keys listed by the file's `appliesTo`, or null when it applies to every runner. */
    fun appliesTo(
        id: String
    ): Set<String>? = root(id)["appliesTo"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()

    /** `android` covers Libre and Pro, so Pro (`android-pro`) also runs files that list only `android`. */
    fun isApplicable(id: String): Boolean {
        val keys = appliesTo(id) ?: return true
        return edition in keys || (edition == SpecFixtures.PRO_KEY && SpecFixtures.APP_KEY in keys)
    }

    /** Ids of the files this edition must run (or map as "elsewhere"). */
    fun ids(): List<String> = allIds().filter(::isApplicable)

    /** Ids of the files recognised but excluded for this edition by `appliesTo`. */
    fun skippedIds(): List<String> = allIds().filterNot(::isApplicable)

    /** Cases of fixture [id]; none when the file does not apply to this edition. */
    fun load(id: String): List<FixtureCase> {
        if (!isApplicable(id)) return emptyList()
        return root(id).getValue("cases").jsonArray.map { element ->
            val case = element.jsonObject
            FixtureCase(
                fixture = id,
                name = case.getValue("name").jsonPrimitive.content,
                input = case.getValue("input").jsonObject,
                expected = case.getValue("expected"),
                // Divergences are keyed by app (`android`), not by edition: Pro shares Libre's.
                knownDivergence = (case["knownDivergence"] as? JsonObject)
                    ?.get(SpecFixtures.APP_KEY)?.jsonPrimitive?.content
            )
        }
    }

    private fun root(id: String): JsonObject = json.parseToJsonElement(File(dir, "$id.json").readText()).jsonObject
}

/**
 * Loads the shared golden fixtures from `marknotes.specDir` (Gradle property, default `../spec`). The fixtures
 * are not part of this repository: without them the parity tests skip. Every function takes the runner's
 * [edition] key (default [APP_KEY]); Pro passes [PRO_KEY] to also run the `android-pro` files.
 */
object SpecFixtures {
    /** Runner key of Libre, and of Pro for files that apply to every Android edition. */
    const val APP_KEY = "android"

    /** Runner key of Pro (MarkNotes Plus), selected by `appliesTo: ["android-pro"]`. */
    const val PRO_KEY = "android-pro"

    private val fixturesDir: File?
        get() = System.getProperty("marknotes.specDir")?.let { File(it, "fixtures") }?.takeIf { it.isDirectory }

    private const val MISSING_FIXTURES = "spec/fixtures not found: set -Pmarknotes.specDir to a shared spec checkout"

    /** True when `marknotes.parity.required` is set: missing fixtures are then an error instead of a skip. */
    private val required: Boolean get() = System.getProperty("marknotes.parity.required").toBoolean()

    /** The fixtures of [edition]; skips the calling test when the fixtures aren't there (fails if [required]). */
    fun requireSet(edition: String = APP_KEY): FixtureSet = FixtureSet(requireDir(), edition)

    /** The fixtures folder; skips the calling test when the fixtures aren't there (fails if [required]). */
    fun requireDir(): File {
        val dir = fixturesDir
        check(dir != null || !required) { MISSING_FIXTURES }
        assumeTrue("shared spec fixtures not available (set -Pmarknotes.specDir)", dir != null)
        return dir!!
    }

    /** Ids ("area/function") of the fixture files that apply to [edition], sorted. */
    fun ids(edition: String = APP_KEY): List<String> = requireSet(edition).ids()

    /** Ids of the files [edition] recognises but skips through `appliesTo`. */
    fun skippedIds(edition: String = APP_KEY): List<String> = requireSet(edition).skippedIds()

    /** Cases of fixture [id], or none when the fixtures aren't there (an error when [required]) or the file does
     * not apply to [edition]. */
    fun load(id: String, edition: String = APP_KEY): List<FixtureCase> {
        val dir = fixturesDir
        check(dir != null || !required) { MISSING_FIXTURES }
        if (dir == null) return emptyList()
        return FixtureSet(dir, edition).load(id)
    }
}
