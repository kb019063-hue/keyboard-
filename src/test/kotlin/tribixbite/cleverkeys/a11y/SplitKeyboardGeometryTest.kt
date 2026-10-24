package tribixbite.cleverkeys.a11y

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.SplitConfig

/**
 * Split mode (phase 3 part 4) geometry: the gap lands at the row midpoint, the compensating scale
 * keeps the row's right edge where the unsplit row put it, and -- the invariant the whole feature
 * rests on -- [KeyboardGeometry.keyAt] and [KeyboardGeometry.computeKeyRects] agree about every
 * key, because a split whose drawing and hit-testing disagree is a keyboard that mis-types.
 *
 * Rows are built directly (public API, no Android XML parser) and the `internal` overloads are
 * driven, the same arrangement as [KeyboardGeometryTest].
 */
class SplitKeyboardGeometryTest {

    private fun charKey(c: Char, width: Float = 1f, shift: Float = 0f): KeyboardData.Key =
        KeyboardData.Key.EMPTY
            .withKeyValue(0, KeyValue.makeCharKey(c))
            .let { KeyboardData.Key(it.keys, it.anticircle, 0, width, shift, null) }

    private fun row(vararg keys: KeyboardData.Key) = KeyboardData.Row(keys.toList(), 1f, 0f)

    /** Ten unit keys: a full-width row, midpoint after the fifth. */
    private val tenRow = row(*"qwertyuiop".map { charKey(it) }.toTypedArray())

    private fun params(splitGap: Float = 0f, splitScale: Float = 1f) =
        KeyboardGeometry.Params(
            keyWidth = 100f,
            rowHeight = 160f,
            marginTop = 20f,
            marginLeft = 0f,
            splitGap = splitGap,
            splitScale = splitScale,
        )

    @Test
    fun `split off reproduces the unsplit walk exactly`() {
        val spans = KeyboardGeometry.rowSpans(tenRow, params())
        assertThat(spans.first().xLeft).isEqualTo(0f)
        assertThat(spans.first().xRight).isEqualTo(100f)
        assertThat(spans.last().xRight).isEqualTo(1000f)
    }

    @Test
    fun `gap opens at the row midpoint and shifts only the right half`() {
        val spans = KeyboardGeometry.rowSpans(tenRow, params(splitGap = 100f))
        // Left half untouched: fifth key still ends at 500.
        assertThat(spans[4].xRight).isEqualTo(500f)
        // Sixth key begins after the gap.
        assertThat(spans[5].xLeft).isEqualTo(600f)
        assertThat(spans[5].xRight).isEqualTo(700f)
    }

    @Test
    fun `scale plus gap fills exactly the unsplit width`() {
        // content 1000, gap 100 -> scale 0.9; halves 450 each side of the gap.
        val scale = SplitConfig.keyWidthScale(1000, 100)
        assertThat(scale).isWithin(1e-6f).of(0.9f)
        val spans = KeyboardGeometry.rowSpans(tenRow, params(splitGap = 100f, splitScale = scale))
        assertThat(spans[4].xRight).isWithin(1e-4f).of(450f)
        assertThat(spans[5].xLeft).isWithin(1e-4f).of(550f)
        assertThat(spans.last().xRight).isWithin(1e-3f).of(1000f)
    }

    @Test
    fun `hit-test and rects agree on every key, split on`() {
        val p = params(splitGap = 100f, splitScale = SplitConfig.keyWidthScale(1000, 100))
        val rects = KeyboardGeometry.computeKeyRects(listOf(tenRow), p)
        for (r in rects) {
            val cx = (r.bounds.left + r.bounds.right) / 2f
            val cy = (r.bounds.top + r.bounds.bottom) / 2f
            assertThat(KeyboardGeometry.keyAt(listOf(tenRow), p, cx, cy)).isEqualTo(r.key)
        }
    }

    @Test
    fun `a touch inside the split gap falls to the key on its right`() {
        val p = params(splitGap = 100f, splitScale = SplitConfig.keyWidthScale(1000, 100))
        // The gap spans 450..550; a touch at 500 is in it, and belongs to the sixth key.
        val hit = KeyboardGeometry.keyAt(listOf(tenRow), p, 500f, 100f)
        assertThat(hit).isEqualTo(tenRow.keys[5])
    }

    @Test
    fun `isRightHalf splits on units not pixels`() {
        assertThat(SplitConfig.isRightHalf(10f, 4.9f)).isFalse()
        assertThat(SplitConfig.isRightHalf(10f, 5f)).isTrue()
        assertThat(SplitConfig.isRightHalf(0f, 0f)).isFalse()
    }
}
