package tribixbite.cleverkeys.swipe.geometric

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Turn angles must be measured in the PHYSICAL frame, not in raw normalized (u, v).
 *
 * Audited 2026-11-21. [GesturePreprocessor.nonCornerWobbleDeg] and [LayoutGeometry.dKw] have
 * always divided Δv by the layout aspect — physical y is `Δv / aspect` — but
 * [GesturePreprocessor.detectCorners], [GesturePreprocessor.countReversals] and
 * [PathScorer.templateCornerIndices] did not. Normalized u/v is only a physical frame when the key
 * area happens to be square, so the same gesture measured differently on every device.
 *
 * The consequences, at 150° (reversal) and 55° (corner) thresholds:
 *
 *  - a physical 160° hairpin read 148° on a 2.0-aspect key area and did not count as a reversal,
 *    so the confidence temperature kept a value the decode had not earned;
 *  - a physical 60° corner read 50° on a 3.0-aspect key area and was not a corner at all, so the
 *    corner-anchor bonus was never offered.
 *
 * These are the polyline-level rules, so this class needs no language packs and never skips.
 */
class GeoPhysicalFrameTurnTest {

    private val config = GeometricEngineConfig()
    private val preprocessor = GesturePreprocessor(config)

    /** Only `aspect` is read by the detectors under test. */
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
        // Guards the finding itself, not just its absence: on a 2.0-aspect key area the OLD rule
        // (raw u/v, no /aspect) read this 160-degree hairpin as 148 degrees and returned 0.
        val aspect = 2.0f
        val poly = polylineWithPhysicalTurn(160.0, aspect)

        // Reproduce the old rule locally so the claim stays true even if the production code
        // changes again: angle in raw (u,v).
        fun rawTurnDegrees(p: FloatArray, i: Int): Double {
            val ax = (p[2 * i] - p[2 * (i - 1)]).toDouble()
            val ay = (p[2 * i + 1] - p[2 * (i - 1) + 1]).toDouble()
            val bx = (p[2 * (i + 1)] - p[2 * i]).toDouble()
            val by = (p[2 * (i + 1) + 1] - p[2 * i + 1]).toDouble()
            val cos = (ax * bx + ay * by) / (Math.hypot(ax, ay) * Math.hypot(bx, by))
            return Math.toDegrees(Math.acos(cos.coerceIn(-1.0, 1.0)))
        }

        val raw = rawTurnDegrees(poly, 1)
        assertWithMessage("the raw-frame angle is below the 150-degree reversal threshold")
            .that(raw).isLessThan(config.reversalAngleThresholdDeg.toDouble())
        assertWithMessage("...so the old rule counted no reversal here, while the fixed one does")
            .that(preprocessor.countReversals(poly, layoutOf(aspect))).isEqualTo(1)
    }

    @Test
    fun `a physical corner is detected as a corner at every aspect`() {
        // 60 degrees: just over the 55 threshold, and the case the raw frame lost at aspect 3.0.
        for (aspect in listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)) {
            val poly = polylineWithPhysicalTurn(60.0, aspect)
            assertWithMessage("60-degree physical corner must be detected at aspect $aspect")
                .that(preprocessor.detectCorners(poly, layoutOf(aspect)).size).isEqualTo(1)
            // ...and it is NOT a reversal, at any aspect.
            assertWithMessage("60 degrees must never count as a reversal at aspect $aspect")
                .that(preprocessor.countReversals(poly, layoutOf(aspect))).isEqualTo(0)
        }
    }

    @Test
    fun `the template and gesture corner detectors agree in the same frame`() {
        // They are index-compared (±CORNER_MATCH_WINDOW on the same arc-length resampling), so
        // they MUST measure turns identically. They are separate implementations of the same
        // rule, which is exactly how the frame divergence could have been fixed on one side only.
        val scorer = PathScorer(config)
        for (aspect in listOf(1.0f, 2.0f, 2.5f, 3.0f)) {
            val layout = layoutOf(aspect)
            for (deg in listOf(30.0, 60.0, 90.0, 160.0)) {
                val poly = polylineWithPhysicalTurn(deg, aspect)
                assertWithMessage("corner detectors must agree at aspect $aspect, turn $deg")
                    .that(scorer.templateCornerIndices(poly, layout).toList())
                    .isEqualTo(preprocessor.detectCorners(poly, layout).toList())
            }
        }
    }

    @Test
    fun `a square key area is unaffected by the change`() {
        // aspect 1.0 is the only frame in which the old and new rules are the same rule, so the
        // fix must change nothing there. Checking it across a range of turns is a stronger claim
        // than one polyline: at aspect 1.0 the /aspect division is a no-op, so every count must
        // equal the raw-frame count the old code produced.
        //
        // (A 160-degree turn is ALSO a corner by the 55-degree rule - the corner rule is the
        // lower threshold of the same measure - so this compares whole index lists, not just
        // whether something was found.)
        val square = layoutOf(1.0f)
        for (deg in listOf(30.0, 60.0, 90.0, 160.0)) {
            val poly = polylineWithPhysicalTurn(deg, 1.0f)

            fun rawCorners(p: FloatArray): List<Int> {
                val out = mutableListOf<Int>()
                for (i in 1 until p.size / 2 - 1) {
                    val ax = (p[2 * i] - p[2 * (i - 1)]).toDouble()
                    val ay = (p[2 * i + 1] - p[2 * (i - 1) + 1]).toDouble()
                    val bx = (p[2 * (i + 1)] - p[2 * i]).toDouble()
                    val by = (p[2 * (i + 1) + 1] - p[2 * i + 1]).toDouble()
                    val cos = (ax * bx + ay * by) / (Math.hypot(ax, ay) * Math.hypot(bx, by))
                    if (Math.toDegrees(Math.acos(cos.coerceIn(-1.0, 1.0))) >=
                        config.cornerAngleThresholdDeg
                    ) {
                        out.add(i)
                    }
                }
                return out
            }

            assertWithMessage("corner indices must be identical to the raw-frame rule at $deg deg")
                .that(preprocessor.detectCorners(poly, square).toList())
                .isEqualTo(rawCorners(poly))
            // A 160-degree hairpin is a reversal; nothing below 150 is.
            assertWithMessage("reversal count at $deg deg on a square key area")
                .that(preprocessor.countReversals(poly, square))
                .isEqualTo(if (deg >= config.reversalAngleThresholdDeg) 1 else 0)
        }
    }
}
