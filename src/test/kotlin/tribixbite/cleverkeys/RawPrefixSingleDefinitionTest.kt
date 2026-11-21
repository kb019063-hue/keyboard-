package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The `raw:` marker on a decoder output is read or stripped at six call sites across three files,
 * and every one of them used to spell the prefix out inline (as `"raw:"` directly, or as
 * `Regex("^raw:")`).
 *
 * It is the same literal everywhere — `SuggestionHandler.RAW_PREFIX` — so it now lives in one
 * constant. This pins that nothing goes back to a private copy, and that the files that carry it
 * as a *behaviour* (the tap path, the ML recorder, the suggestion span) still derive from it: if
 * the wire format ever changes, it must change once.
 *
 * Source text only, like [VerboseLogRatchetTest]: a pure JVM read, no Android, no runtime. That is
 * also deliberate — reading the constant's *value* through a reference would load
 * `SuggestionHandler` and its Android-typed signatures on a plain JVM, which is the one thing a
 * `runPureTests` class must not do.
 */
class RawPrefixSingleDefinitionTest {

    private val main = java.io.File("src/main/kotlin")

    /** The single declaration, verbatim. Pins both the name and the wire format. */
    private val declaration = "internal const val RAW_PREFIX = \"raw:\""

    private fun source(relative: String): String = java.io.File(main, relative).readText()

    private fun productionFiles() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }

    /**
     * Every `src/main` Kotlin file that mentions the marker, other than the declaration itself.
     * A mention is any occurrence of the quoted literal, so `removePrefix("raw:")`,
     * `startsWith("raw:")`, `Regex("^raw:")` and any new spelling are all caught.
     */
    private fun filesSpellingThePrefixOut(): List<String> = productionFiles()
        .filter { file ->
            file.readLines().any { line ->
                line.trim() != declaration && MARKER in line
            }
        }
        .map { it.relativeTo(main).path }
        .sorted()
        .toList()

    @Test
    fun noProductionFileSpellsThePrefixOutInAFreshLiteral() {
        assertWithMessage("these files spell the `raw:` prefix out instead of using RAW_PREFIX")
            .that(filesSpellingThePrefixOut()).isEmpty()
    }

    @Test
    fun theOnlyLiteralIsTheDeclarationItself() {
        // The check above passes trivially if a file stops mentioning the prefix at all, so pin
        // the declaration: one occurrence in the whole of src/main, and it is this one.
        val occurrences = productionFiles()
            .sumOf { file -> file.readText().split(MARKER).size - 1 }

        assertThat(occurrences).isEqualTo(1)
        assertThat(source("tribixbite/cleverkeys/SuggestionHandler.kt")).contains(declaration)
    }

    @Test
    fun theTapPathAndTheRecorderBothUseTheConstant() {
        val handler = source("tribixbite/cleverkeys/SuggestionHandler.kt")
        // The tap path strips it with removePrefix, not with a Pattern compiled per tap, and the
        // raw-prediction branch tests it with startsWith for the same reason.
        assertThat(handler).contains("processedWord = processedWord.removePrefix(RAW_PREFIX)")
        assertThat(handler).contains("processedWord.startsWith(RAW_PREFIX)")
        assertThat(handler).contains("route.wire.removePrefix(RAW_PREFIX)")

        val recorder = source("tribixbite/cleverkeys/MLDataCollector.kt")
        assertThat(recorder).contains("removePrefix(SuggestionHandler.RAW_PREFIX)")

        val span = source("tribixbite/cleverkeys/SuggestionSpanPolicy.kt")
        assertThat(span).contains("removePrefix(SuggestionHandler.RAW_PREFIX)")
    }

    private companion object {
        /** The quoted literal, spelled once here so the scan cannot drift from the declaration. */
        const val MARKER = "\"raw:\""
    }
}
