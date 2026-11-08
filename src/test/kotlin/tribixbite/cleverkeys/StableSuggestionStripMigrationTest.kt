package tribixbite.cleverkeys

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSuggestionStripMigrationTest {
    private val key = "suggestion_strip_always_visible"
    private val marker = "stable_suggestion_strip_v1"

    private fun preferences(migrated: Boolean, stored: Boolean?): Pair<SharedPreferences, SharedPreferences.Editor> {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getBoolean(marker, false) } returns migrated
        every { prefs.contains(key) } returns (stored != null)
        every { prefs.getBoolean(key, false) } returns (stored ?: false)
        every { prefs.edit() } returns editor
        return prefs to editor
    }

    @Test
    fun oldCollapsedSettingIsChangedOnlyOnce() {
        val (prefs, editor) = preferences(migrated = false, stored = false)
        Config.migrateStableSuggestionStrip(prefs)
        verify(exactly = 1) { editor.putBoolean(key, true) }
        verify(exactly = 1) { editor.putBoolean(marker, true) }
        verify(exactly = 1) { editor.apply() }

        val (migratedPrefs, migratedEditor) = preferences(migrated = true, stored = false)
        Config.migrateStableSuggestionStrip(migratedPrefs)
        verify(exactly = 0) { migratedEditor.putBoolean(any(), any()) }
        verify(exactly = 0) { migratedPrefs.edit() }
    }

    @Test
    fun newAndAlreadyStableInstallsKeepTheirPreference() {
        assertTrue(Defaults.SUGGESTION_STRIP_ALWAYS_VISIBLE)
        for (stored in listOf(null, true)) {
            val (prefs, editor) = preferences(migrated = false, stored = stored)
            Config.migrateStableSuggestionStrip(prefs)
            verify(exactly = 0) { editor.putBoolean(key, any()) }
            verify(exactly = 1) { editor.putBoolean(marker, true) }
        }
    }

    @Test
    fun defaultBandDoesNotChangeHeightWhenSuggestionsDisappearAfterSpace() {
        val height = 110
        val setting = Defaults.SUGGESTION_STRIP_ALWAYS_VISIBLE
        val occupied = SuggestionStripPolicy.heightPx(true, setting, height)
        val empty = SuggestionStripPolicy.heightPx(false, setting, height)
        assertEquals(height, occupied)
        assertEquals(occupied, empty)
    }
}
