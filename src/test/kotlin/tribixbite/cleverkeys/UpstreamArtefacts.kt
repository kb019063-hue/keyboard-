package tribixbite.cleverkeys

import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Artefacts the checks in this source set assert on, which this fork deliberately does not
 * vendor (2026-10-31).
 *
 * The upstream project keeps a release book (`docs/RELEASE_RECORD.md`), release notes,
 * `fastlane/` store metadata, a translation glossary, a curated emulator script and the
 * shipped language packs (`scripts/dictionaries/langpack-*.zip`). None of that is in this
 * repository, so the classes that verify it cannot run here — they used to FAIL, which made
 * "run all the tests" permanently red and forced CI to run a hand-picked subset instead.
 *
 * [require] turns that into a JUnit **skip**, via `Assume`. The distinction is the whole point:
 * a skipped test says "not checkable in this checkout", while a green one would claim the
 * invariant holds, which nobody has verified. If the files ever come back, the checks run again
 * with no edit.
 */
object UpstreamArtefacts {

    val RELEASE_RECORD = File("docs/RELEASE_RECORD.md")
    val RELEASE_NOTES = File("RELEASE_NOTES.md")
    val FASTLANE = File("fastlane")
    val EMULATOR_CI = File(".github/scripts/emulator-ci.sh")
    val GLOSSARY = File("docs/i18n/glossary.json")
    val LANGPACK_DIR = File("scripts/dictionaries")
    val CTC_MODELS = File("src/main/assets/models")
    val FDROID_RECIPE = File("metadata/fdroid/tribixbite.cleverkeys.yml")

    /**
     * Skips the calling test (and, from a `@Before`, the whole class) when any of [files] is
     * absent. The message names every missing path, so a skipped run still tells you exactly
     * what this checkout lacks.
     */
    fun require(vararg files: File) {
        val missing = files.filterNot { it.exists() }
        assumeTrue(
            missing.joinToString(", ") { it.path } +
                " — not vendored in this fork, so this check cannot run here",
            missing.isEmpty()
        )
    }

    /** The language pack for [language], e.g. `scripts/dictionaries/langpack-he.zip`. */
    fun langpack(language: String): File = File(LANGPACK_DIR, "langpack-$language.zip")
}
