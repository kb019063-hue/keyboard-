package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The purple default is retired once. Any other stored theme stays. */
class ThemeDefaultMigrationTest {

    @Test
    fun retiredPurpleDefaultIsRepointed() {
        assertThat(ThemeDefaultMigration.shouldRepoint(false, "cleverkeysdark")).isTrue()
    }

    @Test
    fun anExplicitChoiceIsLeftAlone() {
        for (stored in listOf(null, "", "harbor", "cobalt", "ember", "cleverkeyslight")) {
            assertThat(ThemeDefaultMigration.shouldRepoint(false, stored)).isFalse()
        }
    }

    @Test
    fun alreadyMigratedIsLeftAlone() {
        assertThat(ThemeDefaultMigration.shouldRepoint(true, "cleverkeysdark")).isFalse()
    }

    @Test
    fun newDefaultIsTheGboardPairNotPurple() {
        assertThat(Defaults.THEME).isEqualTo("gboard")
        assertThat(Defaults.THEME).isNotEqualTo(ThemeDefaultMigration.RETIRED_DEFAULT)
    }

    // 2026-10-30: the default moved again, to the Gboard-glass pair. Only values that mean
    // "no opinion" move; a deliberate pick (any other built-in, decorative, custom) stays.

    @Test
    fun previousDefaultsAreRepointedToGboard() {
        for (stored in listOf("", "cleverkeysdark", "harbor", "daybreak")) {
            assertThat(GboardThemeMigration.shouldRepoint(false, stored)).isTrue()
        }
    }

    @Test
    fun deliberateChoicesSurviveTheGboardMigration() {
        for (stored in listOf("cobalt", "ember", "monet", "monetlight", "monetdark", "light",
                              "decorative_neon_hot_pink", "custom_1234", "gboard")) {
            assertThat(GboardThemeMigration.shouldRepoint(false, stored)).isFalse()
        }
        assertThat(GboardThemeMigration.shouldRepoint(false, null)).isFalse()
    }

    @Test
    fun gboardMigrationRunsOnce() {
        assertThat(GboardThemeMigration.shouldRepoint(true, "harbor")).isFalse()
    }
}
