package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [GesturePreprocessor.countReversals] must measure its turn angle in the PHYSICAL frame.
 *
 * Audited and fixed 2026-11-21. `normalized (u, v)` is only a physical frame when the key area is
 * square; [LayoutGeometry.dKw] and [GesturePreprocessor.nonCornerWobbleDeg] have always divided Δv
 * by the layout aspect for that reason (`physical y = Δv / aspect`), but `countReversals` took an
 * `acos` over raw u/v deltas. So the same physical hairpin measured differently on every device:
 * a physical 160° reversal read 141° at aspect 2.0 and did not count at all, and because the
 * config default is `reversalAngleThresholdDeg = 150`, genuine reversals were silently
 * under-counted on ordinary wide keyboards.
 *
 * **Why this one is safe to change and the corner rule is not.** `reversalCount` reaches ranking
 * only through [CandidateRanker.effectiveTemperature], whose slope
 * (`reversalConfidenceTempSlope`) defaults to **0f** — a documented, bit-identical no-op. Moving
 * it therefore cannot move an accuracy floor. Moving [GesturePreprocessor.detectCorners] was also
 * attempted in the same pass and REVERTED, because that one feeds `PathScorer.cornerBonus` and so
 * live ranking; it measurably shifted TYPICAL top-1 and broke `GeoAblationTest`. See the note on
 * `detectCorners`.
 *
 * These are polyline-level rules, so this class needs no language packs and never skips.
 */
class GeoPhysicalFrameTurnTest {

    private val config = GeometricEngineConfig()
    private val preprocessor = GesturePreprocessor(config)

    /** Only `aspect` is read by the detector under test. */
    private fun layoutOf(aspect: Float): LayoutGeometry = LayoutGeometry(
        // id, label, cx, cy, w, h, row, col, isLetterNode
        keys = listOf(SwipeKey(0, "a", 0.5f, 0.5f, 0.1f, 0.1f, 0, 0, isLetterNode = true)),
        chars = emptyMap(),
        aliases = emptyMap(),
        aspect = aspect,
        meanKeyWidth = 0.1f,
    )

    /**
     * A three-point polyline whose PHYSICAL turn at the middle point is [physicalDeg], expressed
     * in the raw (u, v) coordinates a gesture in that frame would carry.
     *
     * Physical y and raw v differ by the aspect (`physical y = v / aspect`), so a physical
     * direction (dx, dy) is stored as raw (dx, dy * aspect).
     */
    private fun polylineWithPhysicalTurn(physicalDeg: Double, aspect: Float): FloatArray {
        val len = 0.2
        val inDir = doubleArrayOf(1.0, 0.2)
            .let { val m = Math.hypot(it[0], it[1]); doubleArrayOf(it[0] / m, it[1] / m) }
        val th = Math.toRadians(physicalDeg)
        val outDir = doubleArrayOf(
            inDir[0] * Math.cos(th) - inDir[1] * Math.sin(th),
            inDir[0] * Math.sin(th) + inDir[1] * Math.cos(th),
        )
        val bx = inDir[0] * len
        val by = inDir[1] * len
        return floatArrayOf(
            0f, 0f,
            bx.toFloat(), (by * aspect).toFloat(),
            (bx + outDir[0] * len).toFloat(), ((by + outDir[1] * len) * aspect).toFloat(),
        )
    }

    /** The rule as it was before the fix: `acos` over raw (u, v), no aspect division. */
    private fun rawFrameReversals(p: FloatArray): Int {
        var count = 0
        for (i in 1 until p.size / 2 - 1) {
            val ax = (p[2 * i] - p[2 * (i - 1)]).toDouble()
            val ay = (p[2 * i + 1] - p[2 * (i - 1) + 1]).toDouble()
            val bx = (p[2 * (i + 1)] - p[2 * i]).toDouble()
            val by = (p[2 * (i + 1) + 1] - p[2 * i + 1]).toDouble()
            val na = Math.hypot(ax, ay)
            val nb = Math.hypot(bx, by)
            if (na <= 0.0 || nb <= 0.0) continue
            val cos = ((ax * bx + ay * by) / (na * nb)).coerceIn(-1.0, 1.0)
            if (Math.toDegrees(Math.acos(cos)) >= config.reversalAngleThresholdDeg) count++
        }
        return count
    }

    @Test
    fun `a physical hairpin counts as a reversal at every aspect`() {
        // 160 degrees: above the 150 threshold, unambiguously a reversal.
        for (aspect in listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)) {
            val poly = polylineWithPhysicalTurn(160.0, aspect)
            assertWithMessage("160-degree physical hairpin must count at aspect $aspect")
                .that(preprocessor.countReversals(poly, layoutOf(aspect))).isEqualTo(1)
        }
    }

    @Test
    fun `the raw-frame rule this replaced lost that reversal on an ordinary keyboard`() {
        // Pins the FINDING, not just its absence: on a 2.0-aspect key area the old rule counted
        // nothing here, and the old rule is reproduced above so the claim survives later edits.
        val aspect = 2.0f
        val poly = polylineWithPhysicalTurn(160.0, aspect)

        assertWithMessage("the old raw-frame rule must have missed this reversal")
            .that(rawFrameReversals(poly)).isEqualTo(0)
        assertWithMessage("the fixed rule must count it")
            .that(preprocessor.countReversals(poly, layoutOf(aspect))).isEqualTo(1)
    }

    @Test
    fun `a physical turn below the threshold is never a reversal`() {
        // The fix must not turn ordinary corners into confidence signals. 60 degrees is well
        // under 150 at every aspect.
        for (aspect in listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)) {
            val poly = polylineWithPhysicalTurn(60.0, aspect)
            assertWithMessage("60-degree turn must not be a reversal at aspect $aspect")
                .that(preprocessor.countReversals(poly, layoutOf(aspect))).isEqualTo(0)
        }
    }

    @Test
    fun `a square key area is unaffected by the fix`() {
        // aspect 1.0 is the only frame in which old and new coincide, so nothing may change there.
        val square = layoutOf(1.0f)
        for (deg in listOf(30.0, 60.0, 90.0, 140.0, 160.0)) {
            val poly = polylineWithPhysicalTurn(deg, 1.0f)
            val expected = if (deg >= config.reversalAngleThresholdDeg) 1 else 0
            assertWithMessage("reversal count at $deg deg on a square key area")
                .that(preprocessor.countReversals(poly, square)).isEqualTo(expected)
            assertWithMessage("and the old raw rule must agree at $deg deg on a square key area")
                .that(rawFrameReversals(poly)).isEqualTo(expected)
        }
    }

    @Test
    fun `countReversals is wired from the resampled polyline, not the raw one`() {
        // process() must hand the detector the RESAMPLED points and the same layout the rest of
        // the pipeline used — an aspect swapped in from elsewhere would be invisible otherwise.
        // No language pack is needed: any dictionary-free layout works, and the trace is drawn
        // in its own key-area frame.
        val layout = layoutOf(2.0f)
        val trace = listOf(
            TracePoint(100f, 300f, 0), TracePoint(900f, 300f, 1), TracePoint(200f, 340f, 2),
        )
        val g = preprocessor.process(trace, 1000f, 500f, layout)
        assertThat(g.reversalCount).isEqualTo(preprocessor.countReversals(g.points, layout))
    }
}
