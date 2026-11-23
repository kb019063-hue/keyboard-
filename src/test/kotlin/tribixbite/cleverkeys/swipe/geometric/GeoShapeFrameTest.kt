package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Wave 18: `PathScorer.shapeDistance` measures its normalization scale in raw normalized
 * (u, v), where `v` has `aspect` times the physical extent of `u`.
 *
 * Every other channel in the geometric scorer goes through [LayoutGeometry.dKw], which the
 * documented convention makes physical:
 *
 * ```
 * d_kw = sqrt(Δu² + (Δv/aspect)²) / kw
 * ```
 *
 * `shapeDistance` cannot use `d_kw` (it compares shape-normalized coordinates), so it
 * re-derives its own scale from the bbox longest side — but it derives it from the **raw**
 * coordinates, so the vertical extent is counted `aspect` times larger than it physically is.
 *
 * ## What that does
 *
 * The scale is the bbox longest side, so for a path whose long axis is vertical the scale is
 * overstated by `aspect`, which divides both normalized coordinates by more than it should.
 * The metric is symmetric under a horizontal↔vertical swap only when `aspect = 1`; in general
 * it penalizes perpendicular deviation on a **vertical** stroke `aspect²` times less than the
 * identical physical deviation on a **horizontal** one — and because the shape term is
 * `d²/2σ_s²`, the difference in SCORE is `aspect⁴` (1/16 at the default aspect 2.0).
 * `σ_s = 0.30` is calibrated against the horizontal behaviour, so a vertical word is
 * effectively exempt from the shape channel.
 *
 * These tests pin the behaviour **as it is**, not as it should be, because changing it moves
 * the shape channel's absolute numbers — a calibrated score term, and therefore a change that
 * needs a full-grid accuracy A/B (see `docs/BUG-HUNT-2026-11-15-PART-11.md`, G2, for the
 * precedent). They exist so the asymmetry cannot be forgotten or accidentally "fixed" without
 * meeting the number.
 */
class GeoShapeFrameTest {

    private val config = GeometricEngineConfig()

    /** N = `resamplePoints`; the scorer's internal `n` is this. */
    private val n = config.resamplePoints

    /** Resample a 2-point segment into `n` evenly spaced points (what the engine always feeds). */
    private fun straight(ax: Float, ay: Float, bx: Float, by: Float) = FloatArray(2 * n) { i ->
        val f = (i / 2).toFloat() / (n - 1)
        if (i % 2 == 0) ax + (bx - ax) * f else ay + (by - ay) * f
    }

    /** [straight] plus a half-sine perpendicular bulge of amplitude [amp]. */
    private fun bulged(
        ax: Float, ay: Float, bx: Float, by: Float, offX: Float, offY: Float,
    ) = FloatArray(2 * n) { i ->
        val k = i / 2
        val f = k.toFloat() / (n - 1)
        val bulge = kotlin.math.sin(Math.PI.toFloat() * f)
        if (i % 2 == 0) ax + (bx - ax) * f + offX * bulge
        else ay + (by - ay) * f + offY * bulge
    }

    /**
     * The scalar the shape channel actually consumes — `−w·d²/2σ²` at `w = 1`. Included so the
     * tests below can talk in score units, which is what a user feels.
     */
    private fun shapeCost(d: Float): Float =
        -(d * d) / (2f * config.shapeSigma * config.shapeSigma)

    @Test
    fun `a perpendicular deviation costs aspect-squared less on a vertical stroke`() {
        val kw = 0.10f
        val aspect = 2.0f
        val len = 4f * kw
        val amp = 0.5f * kw
        val clamp = config.minShapeScaleKw * kw

        // Horizontal word: long axis u, perpendicular deviation in v (raw v = physical * aspect).
        val hStraight = straight(0f, 0f, len, 0f)
        val hBulged = bulged(0f, 0f, len, 0f, 0f, amp * aspect)
        // Vertical word: same physical stroke length and deviation, rotated 90°.
        val vStraight = straight(0f, 0f, 0f, len * aspect)
        val vBulged = bulged(0f, 0f, 0f, len * aspect, amp, 0f)

        // Both are well clear of the minShapeScaleKw clamp, so this is the unclamped regime.
        assertWithMessage("fixture must stay above the shape-scale clamp or the asymmetry is masked")
            .that(len).isGreaterThan(clamp * 2f)

        val scorer = PathScorer(config)
        val dH = scorer.shapeDistance(hBulged, hStraight, layoutFor(aspect))
        val dV = scorer.shapeDistance(vBulged, vStraight, layoutFor(aspect))

        assertWithMessage("identical physical deviation, rotated 90 deg, must cost the same")
            .that(dH / dV).isWithin(0.01f).of(aspect * aspect)

        // ...and the SCORE effect is one power worse, because the term is d²/2σ²: a distance
        // ratio of aspect² becomes a cost ratio of aspect⁴. At the default aspect 2.0 that is
        // 1/16 — the vertical word's shape cost is 6% of the horizontal word's for the same
        // physical deviation, which is the number that matters (σ_s is calibrated to the
        // horizontal behaviour).
        val costH = shapeCost(dH)
        val costV = shapeCost(dV)
        val aspectFourth = (aspect * aspect) * (aspect * aspect)
        assertWithMessage("the vertical word is charged %s of the horizontal word's shape cost"
            .format(costV / costH))
            .that(costV / costH).isWithin(0.01f).of(1f / aspectFourth)
    }

    @Test
    fun `the asymmetry is aspect-squared, so it grows with the device's key aspect`() {
        val kw = 0.10f
        val len = 4f * kw
        val amp = 0.5f * kw
        val ratios = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f).map { aspect ->
            val scorer = PathScorer(config)
            val hS = straight(0f, 0f, len, 0f)
            val hB = bulged(0f, 0f, len, 0f, 0f, amp * aspect)
            val vS = straight(0f, 0f, 0f, len * aspect)
            val vB = bulged(0f, 0f, 0f, len * aspect, amp, 0f)
            aspect to (scorer.shapeDistance(hB, hS, layoutFor(aspect)) /
                scorer.shapeDistance(vB, vS, layoutFor(aspect)))
        }
        for ((aspect, ratio) in ratios) {
            assertWithMessage("aspect=$aspect must give an aspect^2 asymmetry")
                .that(ratio).isWithin(0.01f).of(aspect * aspect)
        }
        assertWithMessage("at a square key area the metric is symmetric — the defect is the aspect")
            .that(ratios.first().second).isWithin(0.001f).of(1f)
    }

    @Test
    fun `the location channel is already symmetric, which is the contrast`() {
        // d_loc goes through LayoutGeometry.dKw (Δv/aspect), so the SAME rotated pair is
        // charged the same. This is what makes shapeDistance the outlier rather than the
        // engine's general convention.
        val aspect = 2.0f
        val kw = 0.10f
        val layout = layoutFor(aspect)
        val len = 4f * kw
        val amp = 0.5f * kw
        val scorer = PathScorer(config)

        val hS = straight(0f, 0f, len, 0f)
        val hB = bulged(0f, 0f, len, 0f, 0f, amp * aspect)
        val vS = straight(0f, 0f, 0f, len * aspect)
        val vB = bulged(0f, 0f, 0f, len * aspect, amp, 0f)

        val locH = scorer.locationDistance(hB, hS, layout)
        val locV = scorer.locationDistance(vB, vS, layout)
        assertWithMessage("d_loc must be rotation-symmetric at any aspect")
            .that(locH / locV).isWithin(0.02f).of(1f)
    }

    /**
     * A [LayoutGeometry] carrying [aspect] and a plausible mean key width. Building it from
     * the shipped QWERTY layout would drag in a fixture the shape channel does not actually
     * read — `shapeDistance` takes `layout` only for `minShapeScaleKw * meanKeyWidth`, and its
     * scale derivation ignores `aspect` entirely, which is the whole finding.
     */
    private fun layoutFor(aspect: Float): LayoutGeometry = LayoutGeometry(
        keys = emptyList(),
        chars = emptyMap(),
        aliases = emptyMap(),
        aspect = aspect,
        meanKeyWidth = 0.10f,
    )
}
