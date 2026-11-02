package tribixbite.cleverkeys

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The space slider's speed estimate, and the vertical selection-delete normalisation.
 *
 * Both are pure functions in [Pointers]' companion precisely so this class can pin the
 * arithmetic that the touch path cannot be tested on without a device.
 *
 * Regression history (bug hunt 2026-10-30):
 *
 *  - `Sliding.onTouchMove` seeded `last_move_ms` and then measured `now - last_move_ms` a few
 *    microseconds later, so the FIRST sample always divided by zero. `Float / 0L` is
 *    +Infinity, `min(slider_speed_max, Infinity)` is the ceiling, and the smoothed estimate
 *    therefore jumped from 1.0 to ~4.0 (max 6.0, smoothing 0.6) on the first movement of
 *    every slide — permanently, since the ceiling is a fixed point of the smoothing. Every
 *    cursor slide ran several times faster than the configured sensitivity, and the
 *    smoothing/max settings could not bring it down.
 *  - `handleSelectionDeleteRepeat` divided by `maxDistance - verticalDeadZone`, a span that
 *    is already negative at the top of the vertical-threshold slider (80% of an approximated
 *    0.7 × diagonal key height = 56% of the diagonal, past the 50% half-diagonal), which
 *    inverted the normalised distance and made a faster vertical drag slow the repeat.
 */
class PointersSliderSpeedTest {

    /** The shipped defaults: Defaults.SLIDER_SPEED_MAX / SLIDER_SPEED_SMOOTHING. */
    private val maxSpeed = Defaults.SLIDER_SPEED_MAX
    private val smoothing = Defaults.SLIDER_SPEED_SMOOTHING

    // ── the speed estimate ────────────────────────────────────────────────────────────────

    @Test
    fun firstSampleInTheSameMillisecond_keepsTheStartingSpeed() {
        assertWithMessage("elapsed 0 must not divide — the slide starts at 1.0x, not at the ceiling")
            .that(Pointers.smoothedSpeed(1f, travelled = 30f, elapsedMs = 0L, maxSpeed = maxSpeed, smoothing = smoothing))
            .isEqualTo(1f)
    }

    @Test
    fun aBackwardsClock_keepsThePreviousEstimate() {
        assertWithMessage("a negative delta is a clock step, not a speed sample")
            .that(Pointers.smoothedSpeed(1.5f, travelled = 40f, elapsedMs = -7L, maxSpeed = maxSpeed, smoothing = smoothing))
            .isEqualTo(1.5f)
    }

    @Test
    fun aSlowMoveRaisesTheEstimateGradually() {
        // 8 px in 8 ms is 1 px/ms, so the instant estimate is 2.0 and the smoothing factor
        // moves 1.0 sixty percent of the way there.
        val next = Pointers.smoothedSpeed(1f, travelled = 8f, elapsedMs = 8L, maxSpeed = maxSpeed, smoothing = 0.6f)
        assertThat(next).isWithin(1e-4f).of(1.6f)
    }

    @Test
    fun theCeilingIsRespected() {
        val next = Pointers.smoothedSpeed(1f, travelled = 10_000f, elapsedMs = 1L, maxSpeed = maxSpeed, smoothing = 1f)
        assertThat(next).isEqualTo(maxSpeed)
    }

    @Test
    fun smoothingAtOneSnapsToTheInstantEstimate() {
        val instant = Pointers.smoothedSpeed(1f, travelled = 8f, elapsedMs = 8L, maxSpeed = maxSpeed, smoothing = 1f)
        assertThat(instant).isWithin(1e-4f).of(2f)
    }

    @Test
    fun repeatedFastSamplesConvergeToTheCeilingAndNeverPassIt() {
        var speed = 1f
        repeat(30) {
            speed = Pointers.smoothedSpeed(speed, travelled = 500f, elapsedMs = 8L, maxSpeed = maxSpeed, smoothing = smoothing)
            assertThat(speed).isAtMost(maxSpeed)
        }
        assertThat(speed).isWithin(0.01f).of(maxSpeed)
    }

    // ── vertical selection-delete normalisation ───────────────────────────────────────────

    /** The shipped geometry: a 200 px diagonal gives a 100 px half-diagonal and a 140 px key. */
    private val diagonal = 200f
    private val maxDistance = diagonal * 0.5f
    private val keyHeight = diagonal * 0.7f

    private fun deadZoneFor(thresholdPercent: Int) = keyHeight * (thresholdPercent / 100f)

    @Test
    fun insideTheDeadZone_normalisesToZero() {
        val deadZone = deadZoneFor(40)
        assertThat(Pointers.selectionDeleteVerticalNormalized(deadZone, deadZone, maxDistance)).isEqualTo(0f)
        assertThat(Pointers.selectionDeleteVerticalNormalized(deadZone - 1f, deadZone, maxDistance)).isEqualTo(0f)
    }

    @Test
    fun leavingTheDeadZone_normalisesToPositiveAndAtMostOne() {
        val deadZone = deadZoneFor(40)
        val justOutside = Pointers.selectionDeleteVerticalNormalized(deadZone + 1f, deadZone, maxDistance)
        assertThat(justOutside).isGreaterThan(0f)
        assertThat(justOutside).isLessThan(1f)
        assertThat(Pointers.selectionDeleteVerticalNormalized(diagonal, deadZone, maxDistance)).isEqualTo(1f)
    }

    /**
     * The bug: at the slider's own maximum the dead zone is already past the half-diagonal, so
     * the old `(absDy - deadZone) / (maxDistance - deadZone)` went NEGATIVE and the caller's
     * delay became longer than the idle delay. Every value must stay in 0..1 and grow with the
     * finger's distance.
     */
    @Test
    fun aDeadZonePastTheHalfDiagonal_stillNormalisesUpward() {
        // The dead zone is 70% of a key height that is 0.7 × the diagonal, i.e. 0.49 × the
        // diagonal against a maxDistance of 0.5 × — so it only overtakes maxDistance from 71.4%
        // up. Starting the sweep at 72 is where the negative span the bug report describes
        // actually begins; 0..71 keep a positive span and are covered by the test above.
        for (threshold in 72..80) {
            val deadZone = deadZoneFor(threshold)
            assertWithMessage("threshold $threshold% puts the dead zone past maxDistance")
                .that(deadZone).isGreaterThan(maxDistance)

            val near = Pointers.selectionDeleteVerticalNormalized(deadZone + 5f, deadZone, maxDistance)
            val far = Pointers.selectionDeleteVerticalNormalized(deadZone + 60f, deadZone, maxDistance)
            assertWithMessage("threshold $threshold%: a longer drag must not normalise below zero")
                .that(near).isAtLeast(0f)
            assertWithMessage("threshold $threshold%: a longer drag must score higher")
                .that(far).isAtLeast(near)
            assertThat(far).isAtMost(1f)
        }
    }

    @Test
    fun aZeroOrUnmeasuredDiagonalNormalisesToZero() {
        assertThat(Pointers.selectionDeleteVerticalNormalized(50f, 0f, 0f)).isEqualTo(0f)
        assertThat(Pointers.selectionDeleteVerticalNormalized(Float.NaN, 10f, 100f)).isEqualTo(0f)
        assertThat(Pointers.selectionDeleteVerticalNormalized(50f, Float.NaN, 100f)).isEqualTo(0f)
    }
}
