package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [OneHandedConfig] — no Robolectric, no emulator, no Android imports anywhere
 * in the class under test. That is deliberate: the layout maths is the part of one-handed mode
 * where a bug is invisible until somebody types on a phone, and a swapped left/right reads
 * perfectly sensibly in code review.
 *
 * The invariant that matters most is the pairing asserted in
 * [pivot and gap are always on opposite edges]: the pivot sits on the edge the content stays
 * against, and the empty strip opens on the *other* edge. Getting either half right but not both
 * is the classic mistake, and it produces a keyboard that shrinks toward the middle of the screen
 * with the stop control drawn on top of the keys.
 *
 * A pixel value of a shrunken keyboard is not asserted here — see the design doc, which splits
 * verification between these tests and the emulator smoke run.
 */
class OneHandedConfigTest {

    // =========================================================================
    // parseSide() — a corrupt preference must never stop the keyboard laying out
    // =========================================================================

    @Test
    fun `parseSide accepts both persisted spellings`() {
        assertThat(OneHandedConfig.parseSide("left")).isEqualTo(OneHandedConfig.Side.LEFT)
        assertThat(OneHandedConfig.parseSide("right")).isEqualTo(OneHandedConfig.Side.RIGHT)
    }

    @Test
    fun `parseSide falls back to the default rather than throwing`() {
        // null is the realistic case: a preference written by an older version, or by a backup
        // restored from before this feature existed.
        assertThat(OneHandedConfig.parseSide(null)).isEqualTo(OneHandedConfig.Side.RIGHT)
        assertThat(OneHandedConfig.parseSide("")).isEqualTo(OneHandedConfig.Side.RIGHT)
        assertThat(OneHandedConfig.parseSide("   ")).isEqualTo(OneHandedConfig.Side.RIGHT)
        assertThat(OneHandedConfig.parseSide("centre")).isEqualTo(OneHandedConfig.Side.RIGHT)
        assertThat(OneHandedConfig.parseSide("LEFTISH")).isEqualTo(OneHandedConfig.Side.RIGHT)
        // The default is a named constant, so this asserts the two agree rather than hardcoding.
        assertThat(OneHandedConfig.parseSide(null))
            .isEqualTo(OneHandedConfig.parseSide(OneHandedConfig.DEFAULT_SIDE))
    }

    @Test
    fun `parseSide tolerates surrounding whitespace and capitalisation`() {
        assertThat(OneHandedConfig.parseSide("  LEFT  ")).isEqualTo(OneHandedConfig.Side.LEFT)
        assertThat(OneHandedConfig.parseSide("Left")).isEqualTo(OneHandedConfig.Side.LEFT)
        assertThat(OneHandedConfig.parseSide(" RiGhT ")).isEqualTo(OneHandedConfig.Side.RIGHT)
    }

    @Test
    fun `serializeSide round-trips through parseSide`() {
        for (side in OneHandedConfig.Side.entries) {
            assertThat(OneHandedConfig.parseSide(OneHandedConfig.serializeSide(side))).isEqualTo(side)
        }
        // And the persisted form is the documented spelling, not the enum's name().
        assertThat(OneHandedConfig.serializeSide(OneHandedConfig.Side.LEFT))
            .isEqualTo(OneHandedConfig.SIDE_LEFT)
        assertThat(OneHandedConfig.serializeSide(OneHandedConfig.Side.RIGHT))
            .isEqualTo(OneHandedConfig.SIDE_RIGHT)
    }

    // =========================================================================
    // clampScale() — a stored 0 or 500 cannot break layout
    // =========================================================================

    @Test
    fun `clampScale pulls out-of-range values to the bounds`() {
        assertThat(OneHandedConfig.clampScale(0)).isEqualTo(OneHandedConfig.MIN_SCALE)
        assertThat(OneHandedConfig.clampScale(-40)).isEqualTo(OneHandedConfig.MIN_SCALE)
        assertThat(OneHandedConfig.clampScale(69)).isEqualTo(OneHandedConfig.MIN_SCALE)
        assertThat(OneHandedConfig.clampScale(500)).isEqualTo(OneHandedConfig.MAX_SCALE)
        assertThat(OneHandedConfig.clampScale(101)).isEqualTo(OneHandedConfig.MAX_SCALE)
        assertThat(OneHandedConfig.clampScale(Int.MIN_VALUE)).isEqualTo(OneHandedConfig.MIN_SCALE)
        assertThat(OneHandedConfig.clampScale(Int.MAX_VALUE)).isEqualTo(OneHandedConfig.MAX_SCALE)
    }

    @Test
    fun `clampScale leaves the whole legal range untouched`() {
        for (percent in OneHandedConfig.MIN_SCALE..OneHandedConfig.MAX_SCALE) {
            assertThat(OneHandedConfig.clampScale(percent)).isEqualTo(percent)
        }
    }

    @Test
    fun `the documented bounds are the ones the design settled on`() {
        // Pinned because widening MIN_SCALE below 70 makes suggestion text unreadable, and the
        // range is the trade-off that made scaling the whole container acceptable (choice B).
        assertThat(OneHandedConfig.MIN_SCALE).isEqualTo(70)
        assertThat(OneHandedConfig.MAX_SCALE).isEqualTo(100)
        assertThat(OneHandedConfig.DEFAULT_SCALE).isEqualTo(85)
        assertThat(OneHandedConfig.DEFAULT_ENABLED).isFalse()
        assertThat(OneHandedConfig.DEFAULT_SIDE).isEqualTo(OneHandedConfig.SIDE_RIGHT)
    }

    @Test
    fun `the default scale is inside the legal range`() {
        assertThat(OneHandedConfig.DEFAULT_SCALE)
            .isAtLeast(OneHandedConfig.MIN_SCALE)
        assertThat(OneHandedConfig.DEFAULT_SCALE)
            .isAtMost(OneHandedConfig.MAX_SCALE)
        assertThat(OneHandedConfig.clampScale(OneHandedConfig.DEFAULT_SCALE))
            .isEqualTo(OneHandedConfig.DEFAULT_SCALE)
    }

    // =========================================================================
    // scaleFraction() — what actually reaches View.scaleX
    // =========================================================================

    @Test
    fun `scaleFraction is the percentage as a fraction`() {
        assertThat(OneHandedConfig.scaleFraction(100)).isEqualTo(1.0f)
        assertThat(OneHandedConfig.scaleFraction(85)).isEqualTo(0.85f)
        assertThat(OneHandedConfig.scaleFraction(70)).isEqualTo(0.70f)
    }

    @Test
    fun `scaleFraction clamps, so scaleX can never be zero or above one`() {
        // scaleX of 0f would collapse the keyboard to an invisible sliver and, worse, make the
        // inverse touch matrix singular so taps stop resolving at all.
        assertThat(OneHandedConfig.scaleFraction(0)).isEqualTo(0.70f)
        assertThat(OneHandedConfig.scaleFraction(-100)).isEqualTo(0.70f)
        assertThat(OneHandedConfig.scaleFraction(1000)).isEqualTo(1.0f)
        for (percent in -500..1500 step 7) {
            val f = OneHandedConfig.scaleFraction(percent)
            assertThat(f).isAtLeast(0.70f)
            assertThat(f).isAtMost(1.0f)
        }
    }

    // =========================================================================
    // pivotXFor() / gapSide() — the sign-error guard
    // =========================================================================

    @Test
    fun `hugging the left edge pivots at zero for any width`() {
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.LEFT, 1080)).isEqualTo(0f)
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.LEFT, 1440)).isEqualTo(0f)
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.LEFT, 1)).isEqualTo(0f)
        // Before the first layout the width is 0; both sides then agree, and OneHandedRoot
        // re-applies from onLayout, so this is harmless rather than a special case.
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.LEFT, 0)).isEqualTo(0f)
    }

    @Test
    fun `hugging the right edge pivots at the full width`() {
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.RIGHT, 1080)).isEqualTo(1080f)
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.RIGHT, 1440)).isEqualTo(1440f)
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.RIGHT, 1)).isEqualTo(1f)
        assertThat(OneHandedConfig.pivotXFor(OneHandedConfig.Side.RIGHT, 0)).isEqualTo(0f)
    }

    @Test
    fun `pivot and gap are always on opposite edges`() {
        // The real invariant. Content pinned LEFT means the gap opens RIGHT, and vice versa; if
        // these ever agreed, the keyboard would shrink toward the middle of the screen with the
        // stop control drawn over the keys.
        for (side in OneHandedConfig.Side.entries) {
            val width = 1080
            val pivot = OneHandedConfig.pivotXFor(side, width)
            val gap = OneHandedConfig.gapWidth(OneHandedConfig.DEFAULT_SCALE, width)

            assertThat(OneHandedConfig.gapSide(side)).isNotEqualTo(side)

            if (side == OneHandedConfig.Side.LEFT) {
                // Left edge fixed at x=0, so the vacated strip runs from the scaled width to
                // the real width — i.e. the right-hand end of the view.
                assertThat(pivot).isEqualTo(0f)
                assertThat(width - gap).isGreaterThan(0)
            } else {
                // Right edge fixed at x=width, so the vacated strip is at the left-hand end.
                assertThat(pivot).isEqualTo(width.toFloat())
                assertThat(gap).isGreaterThan(0)
            }
        }
    }

    @Test
    fun `otherSide flips and is its own inverse`() {
        assertThat(OneHandedConfig.otherSide(OneHandedConfig.Side.LEFT))
            .isEqualTo(OneHandedConfig.Side.RIGHT)
        assertThat(OneHandedConfig.otherSide(OneHandedConfig.Side.RIGHT))
            .isEqualTo(OneHandedConfig.Side.LEFT)
        for (side in OneHandedConfig.Side.entries) {
            assertThat(OneHandedConfig.otherSide(OneHandedConfig.otherSide(side))).isEqualTo(side)
            // The side command flips to where the gap already is.
            assertThat(OneHandedConfig.otherSide(side)).isEqualTo(OneHandedConfig.gapSide(side))
        }
    }

    // =========================================================================
    // gapWidth() — where the stop control has to fit
    // =========================================================================

    @Test
    fun `gapWidth is exact for the values a real phone produces`() {
        // Integer arithmetic on purpose: the float version returned 161 here, because
        // 1f - 0.85f lands just below 0.15 and toInt() truncates.
        assertThat(OneHandedConfig.gapWidth(85, 1080)).isEqualTo(162)
        assertThat(OneHandedConfig.gapWidth(70, 1080)).isEqualTo(324)
        assertThat(OneHandedConfig.gapWidth(100, 1080)).isEqualTo(0)
        assertThat(OneHandedConfig.gapWidth(85, 1440)).isEqualTo(216)
        assertThat(OneHandedConfig.gapWidth(70, 720)).isEqualTo(216)
    }

    @Test
    fun `gapWidth is zero at full scale, which is why the stop control is hidden there`() {
        assertThat(OneHandedConfig.gapWidth(OneHandedConfig.MAX_SCALE, 1080)).isEqualTo(0)
        // There is no gap to put a control in, so OneHandedRoot must hide it rather than draw it
        // on top of the keys.
        assertThat(OneHandedConfig.gapWidth(OneHandedConfig.MAX_SCALE, 1440)).isEqualTo(0)
    }

    @Test
    fun `gapWidth clamps its percentage like everything else`() {
        val atMin = OneHandedConfig.gapWidth(OneHandedConfig.MIN_SCALE, 1080)
        val atMax = OneHandedConfig.gapWidth(OneHandedConfig.MAX_SCALE, 1080)
        assertThat(OneHandedConfig.gapWidth(0, 1080)).isEqualTo(atMin)
        assertThat(OneHandedConfig.gapWidth(-50, 1080)).isEqualTo(atMin)
        assertThat(OneHandedConfig.gapWidth(500, 1080)).isEqualTo(atMax)
        assertThat(atMin).isGreaterThan(atMax)
    }

    @Test
    fun `gapWidth grows monotonically as the scale drops`() {
        var previous = -1
        for (percent in OneHandedConfig.MAX_SCALE downTo OneHandedConfig.MIN_SCALE) {
            val gap = OneHandedConfig.gapWidth(percent, 1080)
            assertThat(gap).isAtLeast(previous)
            previous = gap
        }
    }

    @Test
    fun `gapWidth never exceeds the width it was given`() {
        for (percent in -100..1000 step 3) {
            for (width in listOf(0, 1, 320, 720, 1080, 1440, 2560)) {
                val gap = OneHandedConfig.gapWidth(percent, width)
                assertThat(gap).isAtLeast(0)
                assertThat(gap).isAtMost(width)
            }
        }
    }

    // =========================================================================
    // preference keys — these are the backup contract
    // =========================================================================

    @Test
    fun `the preference keys are the ones SettingsDefaults and backup rely on`() {
        // Renaming any of these silently orphans a user's setting on upgrade and drops it from
        // full backups, so the literals are pinned here rather than only at the point of use.
        assertThat(OneHandedConfig.PREF_ENABLED).isEqualTo("one_handed_enabled")
        assertThat(OneHandedConfig.PREF_SIDE).isEqualTo("one_handed_side")
        assertThat(OneHandedConfig.PREF_SCALE).isEqualTo("one_handed_scale")
        assertThat(OneHandedConfig.SIDE_LEFT).isEqualTo("left")
        assertThat(OneHandedConfig.SIDE_RIGHT).isEqualTo("right")
    }
}
