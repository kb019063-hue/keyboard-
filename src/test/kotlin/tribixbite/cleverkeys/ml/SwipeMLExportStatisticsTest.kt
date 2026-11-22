package tribixbite.cleverkeys.ml

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * The `statistics` block of a swipe-data export, audited 2026-11-21.
 *
 * The three counters in it were lifetime INSERT counters that nothing ever decremented, written
 * under names that read as "how many swipes are in this file". A user whose rows had all been
 * removed by the 90-day retention could export an empty `data` array next to
 * `"total_swipes": 5000`. The fix makes `total_swipes` describe the exported data and relabels the
 * counters as what they are.
 *
 * No database is involved: [SwipeMLDataStore.statisticsJson] is a pure builder, the same seam idea
 * as `relabelRow`'s `newValues` factory. (This environment has no Robolectric runner, and a mocked
 * `SQLiteOpenHelper` cannot be reached through an Objenesis-built instance.)
 */
class SwipeMLExportStatisticsTest {

    /** Wraps the builder's text in a minimal document so it can be read as JSON, as an export is. */
    private fun statisticsObject(
        count: Int,
        lifetimeInserts: Int = 0,
        calibration: Int = 0,
        userSelection: Int = 0,
    ): JSONObject {
        val doc = "{" + SwipeMLDataStore.statisticsJson(
            count, lifetimeInserts, calibration, userSelection
        ) + "}"
        return JSONObject(doc).getJSONObject("statistics")
    }

    @Test
    fun `total_swipes describes the file, not the lifetime insert counter`() {
        // The retention case: 5000 swipes were once stored, every one of them has been deleted, so
        // the export carries no rows. The old body wrote the preference here and said 5000.
        val stats = statisticsObject(
            count = 0, lifetimeInserts = 5000, calibration = 800, userSelection = 400
        )

        assertThat(stats.getInt("total_swipes")).isEqualTo(0)
        // The counters survive, under names that say what they are.
        assertThat(stats.getInt("lifetime_inserts")).isEqualTo(5000)
        assertThat(stats.getInt("lifetime_calibration_inserts")).isEqualTo(800)
        assertThat(stats.getInt("lifetime_user_selection_inserts")).isEqualTo(400)
    }

    @Test
    fun `total_swipes counts the rows actually written`() {
        // Deliberately inconsistent with the row count: only storeSwipeData increments these, so
        // batch inserts and every kind of deletion leave them behind.
        val stats = statisticsObject(
            count = 3, lifetimeInserts = 99, calibration = 7, userSelection = 5
        )

        assertThat(stats.getInt("total_swipes")).isEqualTo(3)
        assertThat(stats.getInt("lifetime_inserts")).isEqualTo(99)
    }

    @Test
    fun `no key reads as a per-source row count but is not one`() {
        // The naming half of the fix: the bare "calibration_swipes" / "user_swipes" pair looked
        // like row counts and were lifetime inserts.
        val stats = statisticsObject(
            count = 1, lifetimeInserts = 1, calibration = 1, userSelection = 1
        )

        assertThat(stats.has("calibration_swipes")).isFalse()
        assertThat(stats.has("user_swipes")).isFalse()
        assertThat(stats.has("lifetime_calibration_inserts")).isTrue()
        assertThat(stats.has("lifetime_user_selection_inserts")).isTrue()
    }

    @Test
    fun `the block is valid JSON and closes the statistics object`() {
        // The export builds this text by hand, so a missing brace or comma is a corrupt file, not
        // an exception in the writer. The parser above would throw on either; this pins the shape.
        val text = SwipeMLDataStore.statisticsJson(4, 4, 0, 4)

        assertThat(text).startsWith("  \"statistics\": {\n")
        assertThat(text).endsWith("  }\n")
        assertThat(text.trimEnd().count { it == '}' }).isEqualTo(1)
        // No trailing comma on the last member, which JSON rejects.
        val lastMember = text.trimEnd().lines().dropLast(1).last().trimEnd()
        assertThat(lastMember.endsWith(",")).isFalse()
    }
}
