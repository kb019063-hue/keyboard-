package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Gboard's highlighted suggestion is a pill filled with the return-key accent and white text.
 * The rules that decide whether that pill is drawable at all live here.
 */
class SuggestionPillPolicyTest {

    private val blue = 0xFF1A73E8.toInt()
    private val darkBar = 0xFF141619.toInt()
    private val lightBar = 0xFFE8EBEF.toInt()

    @Test
    fun blueAccentOnTheGboardBarsDrawsAWhitePill() {
        for (bar in listOf(darkBar, lightBar)) {
            val look = SuggestionPillPolicy.look(blue, bar, fallbackText = 0xFF1F1F1F.toInt(), barText = 0)
            assertWithMessage("bar=$bar").that(look.drawPill).isTrue()
            assertThat(look.fill).isEqualTo(blue)
            assertThat(look.text).isEqualTo(0xFFFFFFFF.toInt())
        }
    }

    @Test
    fun withNoAccentOrNoBackgroundTheOldBoldTextHighlightStays() {
        val fallback = 0xFF00FFFF.toInt()
        assertThat(SuggestionPillPolicy.look(0, darkBar, fallback, 0).drawPill).isFalse()
        assertThat(SuggestionPillPolicy.look(blue, 0, fallback, 0).drawPill).isFalse()
        assertThat(SuggestionPillPolicy.look(0, darkBar, fallback, 0).text).isEqualTo(fallback)
    }

    /** A theme whose accent equals the bar has nothing to draw — a pill there is a smudge. */
    @Test
    fun anAccentThatMatchesTheBarIsNotAPill() {
        val look = SuggestionPillPolicy.look(lightBar, lightBar, fallbackText = 0xFF112233.toInt(), barText = 0)
        assertThat(look.drawPill).isFalse()
        assertThat(look.text).isEqualTo(0xFF112233.toInt())
    }

    /** Pale accents take black ink instead of white, so the word stays readable. */
    @Test
    fun aPaleAccentTakesDarkInk() {
        val pale = 0xFFBBE0FF.toInt()
        val look = SuggestionPillPolicy.look(pale, darkBar, fallbackText = 0, barText = 0)
        assertThat(look.drawPill).isTrue()
        assertThat(look.text).isEqualTo(0xFF000000.toInt())
    }
}
