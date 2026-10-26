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
    fun newDefaultIsHarborNotPurple() {
        assertThat(Defaults.THEME).isEqualTo("harbor")
        assertThat(Defaults.THEME).isNotEqualTo(ThemeDefaultMigration.RETIRED_DEFAULT)
    }
}
