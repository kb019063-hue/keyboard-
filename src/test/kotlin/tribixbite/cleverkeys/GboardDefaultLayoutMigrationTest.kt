package tribixbite.cleverkeys

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Test

/**
 * Pins the Gboard-style default that makes the shipped keyboard match the owner's Gboard
 * reference screenshot (2026-10-26): a clean digit row, plain lowercase QWERTY, and a minimal
 * bottom row — never the old dense PC-style layout.
 *
 * Two halves:
 *  - the one-time config migration that re-points an UPGRADED install still pinned to a legacy
 *    dense default (pure decision + SharedPreferences wiring, mockk);
 *  - the static default chain (method.xml English subtypes, the no-subtype fallback, and the
 *    settings previews) so a future edit cannot silently reintroduce the cluttered layout.
 *
 * NOTE: CI's Build APK runs assembleDebug, not test; tools/check_layout.py independently
 * enforces the same static invariants at preBuild. This JVM test documents + double-checks them.
 */
class GboardDefaultLayoutMigrationTest {

    private companion object {
        const val GB = "latn_qwerty_us_gboard_style"
        const val LAYOUTS_KEY = "layouts"
        fun src(path: String) = File(path).readText()
    }

    // ── Pure decision matrix ────────────────────────────────────────────────────

    @Test
    fun legacyDenseDefaultIsRepointed() {
        assertThat(GboardDefaultLayoutMigration.shouldRepoint(false, "[\"latn_qwerty_us\"]")).isTrue()
        assertThat(GboardDefaultLayoutMigration.shouldRepoint(false, "[\"latn_qwerty_gb\"]")).isTrue()
        assertThat(GboardDefaultLayoutMigration.shouldRepoint(false, " [\"latn_qwerty_us\"] ")).isTrue()
    }

    @Test
    fun alreadyMigratedIsLeftAlone() {
        assertThat(GboardDefaultLayoutMigration.shouldRepoint(true, "[\"latn_qwerty_us\"]")).isFalse()
    }

    @Test
    fun userChoicesAreNeverTouched() {
        // No pref, the system entry, a custom layout, multiple layouts, a bound named layout,
        // or any other named layout is a deliberate choice — never rewritten.
        val untouched = listOf(
            null,
            "",
            "[]",
            "[\"system\"]",
            "[{\"kind\":\"system\"}]",
            "[\"latn_colemak\"]",
            "[\"latn_qwerty_us\",\"latn_dvorak\"]",
            "[{\"kind\":\"named\",\"name\":\"latn_qwerty_us\",\"language\":\"en\"}]",
            "[{\"kind\":\"custom\",\"xml\":\"<keyboard/>\"}]",
            "[\"latn_qwerty_us_gboard_style\"]",
        )
        for (raw in untouched) {
            assertThat(GboardDefaultLayoutMigration.shouldRepoint(false, raw)).isFalse()
        }
    }

    // ── Config.migrateGboardDefaultLayout wiring ────────────────────────────────

    private fun prefs(
        markerSet: Boolean,
        layoutsRaw: String?,
    ): Pair<SharedPreferences, SharedPreferences.Editor> {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(GboardDefaultLayoutMigration.MIGRATION_MARKER_KEY, false) } returns markerSet
        every { prefs.getString(LAYOUTS_KEY, null) } returns layoutsRaw
        every { prefs.edit() } returns editor
        return prefs to editor
    }

    @Test
    fun migrationWritesGboardLayoutAndMarkerForLegacyDefault() {
        val (prefs, editor) = prefs(markerSet = false, layoutsRaw = "[\"latn_qwerty_us\"]")
        Config.migrateGboardDefaultLayout(prefs)
        verify { editor.putString(LAYOUTS_KEY, "[\"$GB\"]") }
        verify { editor.putBoolean(GboardDefaultLayoutMigration.MIGRATION_MARKER_KEY, true) }
        verify { editor.apply() }
    }

    @Test
    fun migrationAlwaysMarksEvenWhenNothingChanges() {
        val (prefs, editor) = prefs(markerSet = false, layoutsRaw = "[\"latn_colemak\"]")
        Config.migrateGboardDefaultLayout(prefs)
        verify(exactly = 0) { editor.putString(LAYOUTS_KEY, any()) }
        verify { editor.putBoolean(GboardDefaultLayoutMigration.MIGRATION_MARKER_KEY, true) }
        verify { editor.apply() }
    }

    @Test
    fun migrationIsNoOpWhenMarkerAlreadySet() {
        val (prefs, editor) = prefs(markerSet = true, layoutsRaw = "[\"latn_qwerty_us\"]")
        Config.migrateGboardDefaultLayout(prefs)
        verify(exactly = 0) { editor.putString(LAYOUTS_KEY, any()) }
        verify(exactly = 0) { editor.putBoolean(any(), any()) }
    }

    @Test
    fun initGlobalConfigCallsMigration() {
        val configKt = src("src/main/kotlin/tribixbite/cleverkeys/Config.kt")
        val initBody = configKt
            .substringAfter("fun initGlobalConfig(")
            .substringBefore("val config = Config(")
        assertThat(initBody).contains("migrateGboardDefaultLayout(prefs)")
    }

    // ── Static default chain (matches what check_layout.py enforces at preBuild) ──

    @Test
    fun englishSubtypesAllDefaultToGboardStyle() {
        val method = src("res/xml/method.xml")
        for (tag in listOf("en-US", "en-GB", "en-GH", "en-IN", "en-AU", "en-CA")) {
            val line = method.lineSequence().firstOrNull {
                it.contains("<subtype") && it.contains("android:languageTag=\"$tag\"")
            }
            assertThat(line).isNotNull()
            assertThat(line ?: "").contains("default_layout=$GB")
        }
        assertThat(method).contains("android:imeSubtypeLocale=\"en_GH\"")
        // The generic `en` entry (default for unrecognised locales) too.
        assertThat(method.lineSequence().first {
            it.contains("<subtype") && it.contains("android:languageTag=\"en\"")
                && !it.contains("android:languageTag=\"en-")
        }).contains("default_layout=$GB")
    }

    @Test
    fun noSubtypeFallbackLoadsGboardStyleNotDense() {
        val graph = src("src/main/kotlin/tribixbite/cleverkeys/wiring/KeyboardComponentGraph.kt")
        assertThat(graph).contains("R.raw.$GB")
        assertThat(graph).doesNotContain("R.raw.latn_qwerty_us)")
    }

    @Test
    fun previewsLoadGboardStyleNotDense() {
        val previews = listOf(
            "src/main/kotlin/tribixbite/cleverkeys/customization/KeyboardPreviewHost.kt",
            "src/main/kotlin/tribixbite/cleverkeys/customization/KeyboardPreviewView.kt",
            "src/main/kotlin/tribixbite/cleverkeys/theme/ThemeEditorPreview.kt",
        )
        for (p in previews) {
            assertThat(src(p)).contains("R.raw.$GB")
        }
    }
}
