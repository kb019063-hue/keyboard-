package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * "Which build is on the phone?" — owner report, 2026-11-28: *"since the app got made or maybe a
 * little after that, the version has always been the same, whether a new [build] or not."*
 *
 * They were right, and it cost two rounds of "still not fixed" on APKs that were older than the
 * fix: every dev build shipped as `2.0.0` / versionCode 200002, so neither a screenshot nor the
 * phone could say which file had been installed.
 *
 * A build now identifies itself in two places, and this pins both:
 *  - `versionName` carries `-dev.<short sha>` for CI dev builds (Android's App info shows it),
 *  - `version_info.txt` carries `commit=` and `date=` beside `version=`, which the settings
 *    Version Information card and the keyboard timings card print.
 *
 * Two things must stay true for this to be worth anything:
 *  - the stamp reaches the *build* (workflow exports it before `assembleDebug`, and Gradle reads
 *    it at configuration time);
 *  - a release build stays plain `2.0.0`, because the F-Droid recipe and the reproducibility
 *    guard expect exactly that, and because a suffix would make the recipe's version unusable.
 *
 * The values are deterministic for a given tree — commit hash and the commit's own date, never a
 * wall-clock time — so two builds of one commit remain byte-identical.
 */
class BuildStampDriftTest {

    private fun read(path: String): String {
        val file = File(path)
        check(file.isFile) { "$path not found — run with the project root as CWD." }
        return file.readText()
    }

    private val buildGradle by lazy { read("build.gradle") }
    private val workflow by lazy { read(".github/workflows/build-apk.yml") }
    private val settingsCards by lazy {
        read("src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsInfoCards.kt")
    }

    @Test
    fun theWorkflowStampsTheBuildBeforeItCompiles() {
        assertWithMessage("CI must export the commit for Gradle to read")
            .that(workflow).contains("CLEVERKEYS_DEV_BUILD_ID=")

        val stampAt = workflow.indexOf("CLEVERKEYS_DEV_BUILD_ID=")
        val buildAt = workflow.indexOf("./gradlew assembleDebug")
        assertWithMessage("the stamp step must exist").that(stampAt).isAtLeast(0)
        assertWithMessage("./gradlew assembleDebug must still be the gating build step").that(buildAt).isAtLeast(0)
        assertWithMessage("a stamp written after the build would name the NEXT build, not this one")
            .that(stampAt).isLessThan(buildAt)
    }

    @Test
    fun theVersionNameNamesTheBuild() {
        assertWithMessage("Gradle must read the CI stamp")
            .that(buildGradle).contains("CLEVERKEYS_DEV_BUILD_ID")
        assertWithMessage("the dev version name is the plain version plus the commit")
            .that(buildGradle).contains("""ext.versionNameFull = ext.buildStamp ? "${'$'}{ext.versionNameStr}-dev.${'$'}{ext.buildStamp}" : ext.versionNameStr""")
        assertWithMessage("the manifest must carry the stamped name, not the bare version string")
            .that(buildGradle).contains("versionName rootProject.ext.versionNameFull")
    }

    @Test
    fun aReleaseBuildStaysPlainVersion() {
        assertWithMessage("without the CI stamp the suffix must be empty")
            .that(buildGradle).contains("""ext.versionNameFull = ext.buildStamp ?""")
        assertWithMessage("the release version name itself must not be touched")
            .that(buildGradle)
            .contains("ext.versionNameStr = \"\${ext.VERSION_MAJOR}.\${ext.VERSION_MINOR}.\${ext.VERSION_PATCH}\"")
    }

    @Test
    fun versionInfoCarriesTheCommitAndItsDate() {
        val task = buildGradle.substringAfter("tasks.register('generateVersionInfo')")
            .substringBefore("tasks.named(\"preBuild\")")
        assertWithMessage("the app must be able to name its commit")
            .that(task).contains("commit=${'$'}{commit}")
        assertWithMessage("and the date it was committed")
            .that(task).contains("date=${'$'}{commitDate}")
        assertWithMessage("the version itself must stay in the file")
            .that(task).contains("version=${'$'}{project.ext.versionNameStr}")
        assertWithMessage("a build without git must still produce a valid file")
            .that(task).contains("if (commit) lines <<")
        // A wall-clock build time would make two builds of one commit differ. Commit date is
        // fixed per commit; the build time is not, and must not come back.
        assertWithMessage("no wall-clock timestamp in the version file")
            .that(task).doesNotContain("new Date()")
        assertWithMessage("the commit date comes from the commit, not from the clock")
            .that(buildGradle).contains("'--format=%cs'")
    }

    @Test
    fun theSettingsCardShowsWhatTheFileCarries() {
        assertWithMessage("the card must read the commit")
            .that(settingsCards).contains("\"commit\"")
        assertWithMessage("the card must read the date")
            .that(settingsCards).contains("\"date\"")
        assertWithMessage("and put them on the visible line, not only in the copy payload")
            .that(settingsCards).contains("""parts.joinToString(" · ")""")
        assertWithMessage("a build with no git info still shows the localized unknown, not a blank")
            .that(settingsCards).contains("ifEmpty { unknown }")
    }
}
