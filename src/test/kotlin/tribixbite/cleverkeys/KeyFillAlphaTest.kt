package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `KeyFillAlpha` — how a key's fill alpha combines the theme colour with the user's opacity
 * setting (bug hunt 2026-10-30).
 *
 * `Paint.setAlpha` REPLACES a colour's alpha channel. The old code therefore assigned the user's
 * `key_opacity` straight onto the paint, which silently flattened any translucent theme colour:
 * a 15 %-white glass key rendered fully opaque, so the Gboard-glass theme could not exist. The
 * composition now multiplies, which is a no-op for every opaque theme.
 */
class KeyFillAlphaTest {

    @Test
    fun opaqueColour_keepsTheUsersPercentage() {
        // 80 % of an opaque fill is 204 — exactly what the old assignment produced.
        assertThat(KeyFillAlpha.of(0xFFFFFFFF.toInt(), 204)).isEqualTo(204)
        // 100 % is untouched.
        assertThat(KeyFillAlpha.of(0xFF3C4043.toInt(), 255)).isEqualTo(255)
    }

    @Test
    fun translucentColour_isScaledNotReplaced() {
        // 0x26 = 15 % white: at full opacity it must stay 15 %, not become 100 %.
        assertThat(KeyFillAlpha.of(0x26FFFFFF, 255)).isEqualTo(0x26)
        // Half the user opacity halves the already-translucent fill.
        assertThat(KeyFillAlpha.of(0x26FFFFFF, 128)).isEqualTo(0x13)
    }

    @Test
    fun fullyTransparent_staysTransparent() {
        assertThat(KeyFillAlpha.of(0x00000000, 255)).isEqualTo(0)
    }

    @Test
    fun opacityOutsideTheByteRange_isClamped() {
        assertThat(KeyFillAlpha.of(0xFF000000.toInt(), 400)).isEqualTo(255)
        assertThat(KeyFillAlpha.of(0xFF000000.toInt(), -5)).isEqualTo(0)
    }
}
