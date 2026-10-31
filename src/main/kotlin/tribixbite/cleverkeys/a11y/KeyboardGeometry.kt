package tribixbite.cleverkeys.a11y

import tribixbite.cleverkeys.KeyValue
import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.SplitConfig

/**
 * Pure-JVM keyboard hit-test geometry, extracted verbatim from
 * `Keyboard2View.getRowAtPosition` + `getKeyAtPosition` so that touch/swipe
 * hit-testing and the TalkBack virtual-view tree share ONE source of truth.
 *
 * Kept Android-free (no `android.graphics.Rect`) so it can be unit-tested on the
 * JVM: bounds are carried by [KeyBounds] (Float edges), the caller converts to a
 * `Rect` when populating an `AccessibilityNodeInfo`.
 *
 * ## Which geometry?
 * This uses the **hit-test cell geometry** — full `key.width * keyWidth` cells,
 * y measured from [Params.marginTop] (`_config.marginTop`), x from
 * [Params.marginLeft] (`maxOf(config.margin_left, insetsLeft)`). This is the
 * *tappable* area, NOT the visual-inset cells `onDraw` renders (which shrink by
 * `tc.margin_left`/`tc.horizontal_margin`). Accessibility bounds must equal the
 * area a user's tap actually lands on, so we deliberately do NOT reuse the
 * `onDraw` insets here.
 */
object KeyboardGeometry {

    /**
     * Layout parameters that vary per measure pass. Sourced by the view from
     * `_config.marginTop`, `maxOf(config.margin_left, insetsLeft)`, `_keyWidth`,
     * and `_tc.row_height`. Passing them in keeps this object stateless & pure.
     *
     * @param keyWidth width of one relative key-width unit, in px (`_keyWidth`).
     * @param rowHeight height of one relative row-height unit, in px (`tc.row_height`).
     * @param marginTop top offset of the first row, in px (`_config.marginTop`).
     *   NOTE: this is the raw config margin, NOT `tc.margin_top` (which adds
     *   `vertical_margin/2`) — the hit-tester uses the raw value.
     * @param marginLeft left offset of the first key in each row, in px
     *   (`maxOf(config.margin_left, insetsLeft)`). NOT `tc.margin_left`.
     */
    data class Params(
        val keyWidth: Float,
        val rowHeight: Float,
        val marginTop: Float,
        val marginLeft: Float,
        /** Horizontal gap inserted at each row's midpoint in split mode; 0 otherwise. */
        val splitGap: Float = 0f,
        /** Key-width multiplier compensating splitGap so halves plus gap fill the row. */
        val splitScale: Float = 1f,
    )

    /** One key's horizontal span within its row, split gap and scale already applied. */
    data class KeySpan(val key: KeyboardData.Key, val xLeft: Float, val xRight: Float)

    /** Float-edged bounds of one virtual key cell (hit-test geometry). */
    data class KeyBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * One virtual view: its flat row-major id, the underlying layout [Key], and
     * its hit-test [bounds]. [kv] is the key's primary value (`key.keys[0]`),
     * carried for convenience; it may be null for placeholder cells (which
     * [computeKeyRects] excludes, so [kv] is non-null in produced rects).
     */
    data class KeyRect(
        val virtualId: Int,
        val key: KeyboardData.Key,
        val kv: KeyValue,
        val bounds: KeyBounds,
    )

    /**
     * The key at [tx],[ty], or null if outside the keyboard.
     *
     * VERBATIM transplant of `Keyboard2View.getRowAtPosition` +
     * `getKeyAtPosition`, including all three slop rules and the a/l-row special
     * case. Do not "clean up" — the exact behaviour is depended on by every
     * user's tap AND swipe hit-testing (swipe tracing calls this per point).
     *
     *  - left-of-row slop: if `tx < marginLeft` and the row has an 'a' key,
     *    return 'a' (extends 'a''s zone to the left margin).
     *  - gap slop: if `tx < xRight` for a key, return that key (the gap before a
     *    key belongs to it).
     *  - right slop: if past the last key's right edge, return the last key.
     */
    fun keyAt(keyboard: KeyboardData, params: Params, tx: Float, ty: Float): KeyboardData.Key? =
        keyAt(keyboard.rows, params, tx, ty)

    /**
     * [keyAt] operating directly on a row list. `internal` so the pure-JVM
     * geometry test can drive it with hand-built [KeyboardData.Row]s without
     * needing an Android XML parser to construct a full [KeyboardData].
     */
    internal fun keyAt(rows: List<KeyboardData.Row>, params: Params, tx: Float, ty: Float): KeyboardData.Key? {
        val row = rowAt(rows, params, ty)
        // Mirrors getKeyAtPosition: uses the passed-in marginLeft (which the view
        // computes dynamically as maxOf(config.margin_left, insetsLeft) to avoid a
        // stale _marginLeft from a delayed onMeasure).
        var x = params.marginLeft

        if (row == null) {
            return null
        }

        // Check if this row contains 'a' and 'l' keys (middle letter row in QWERTY)
        val hasAAndLKeys = rowContainsAAndL(row)
        var aKey: KeyboardData.Key? = null
        var lKey: KeyboardData.Key? = null

        if (hasAAndLKeys) {
            // Find the 'a' and 'l' keys in this row
            for (key in row.keys) {
                if (isCharacterKey(key, 'a')) aKey = key
                if (isCharacterKey(key, 'l')) lKey = key
            }
        }

        // Check if touch is before the first key and we have 'a' key - extend its touch zone
        if (tx < x && aKey != null) {
            return aKey
        }

        if (tx < x) {
            return null
        }

        // Same walk as [computeKeyRects], so a tap lands on the key that is drawn there --
        // including inside the split gap, which falls to the key on its right exactly as the
        // inter-key gaps above fall to the key after them.
        for (span in rowSpans(row, params)) {
            // GAP FIX: If touch is in the gap before this key (xLeft),
            // consider it part of this key for swiping purposes.
            if (tx < span.xRight) {
                return span.key
            }
        }

        // GAP FIX: If we reached here, tx > last key's right edge.
        // Return the last key in the row to handle right-margin slop.
        if (row.keys.isNotEmpty()) {
            return row.keys[row.keys.size - 1]
        }

        return null
    }

    /**
     * Verbatim transplant of `Keyboard2View.getRowAtPosition`. The a/l special
     * case lives in [keyAt]; row selection itself only needs the y walk.
     */
    private fun rowAt(rows: List<KeyboardData.Row>, params: Params, ty: Float): KeyboardData.Row? {
        var y = params.marginTop

        if (ty < y) {
            return null
        }

        for (row in rows) {
            val rowBottom = y + (row.shift + row.height) * params.rowHeight

            if (ty < rowBottom) {
                return row
            }
            y = rowBottom
        }

        return null
    }

    /**
     * Hit-test cell rects for every non-placeholder key, in row-major order.
     * The list index equals each rect's [KeyRect.virtualId], so the a11y helper
     * can assign stable flat ids from a single walk.
     *
     * Geometry matches [keyAt] exactly (same x/y accumulation), so a
     * `keyAt(center)` lookup always resolves back to the rect's key. Placeholder
     * cells (`key.keys[0] == null`) advance the x cursor but produce NO rect and
     * do NOT consume a virtual id.
     */
    fun computeKeyRects(keyboard: KeyboardData, params: Params): List<KeyRect> =
        computeKeyRects(keyboard.rows, params)

    /**
     * [computeKeyRects] operating directly on a row list. `internal` for the
     * same pure-JVM testability reason as [keyAt].
     */
    internal fun computeKeyRects(rows: List<KeyboardData.Row>, params: Params): List<KeyRect> {
        val rects = ArrayList<KeyRect>()
        var y = params.marginTop
        var virtualId = 0

        for (row in rows) {
            val yTop = y + row.shift * params.rowHeight
            val yBottom = y + (row.shift + row.height) * params.rowHeight

            // The ONE horizontal walk: [rowSpans] carries the split gap and its compensating
            // scale, so rects, hit-tests and the drawn keys all agree by construction.
            for (span in rowSpans(row, params)) {
                val kv = span.key.keys[0]
                if (kv != null) {
                    rects.add(
                        KeyRect(
                            virtualId = virtualId++,
                            key = span.key,
                            kv = kv,
                            bounds = KeyBounds(span.xLeft, yTop, span.xRight, yBottom),
                        )
                    )
                }
            }

            y = yBottom
        }

        return rects
    }

    /**
     * Every key's horizontal span in one row, in order, with split mode applied.
     *
     * With [Params.splitGap] zero and [Params.splitScale] one this reproduces the accumulation
     * the three geometry consumers used to perform independently -- `x += shift * keyWidth`, then
     * the key's width -- exactly, in the same float operation order, so splitting off is a
     * no-op rather than a rounding change. Placeholder cells (null primary value) still advance
     * the cursor and still get a span; [computeKeyRects] drops them from rects, and the draw path
     * renders them as empty space, as both always have.
     *
     * [startX] lets the draw path, whose row origin adds the theme's per-row margin on top of the
     * view's, share this walk without adopting [Params.marginLeft].
     */
    internal fun rowSpans(
        row: KeyboardData.Row,
        params: Params,
        startX: Float = params.marginLeft
    ): List<KeySpan> {
        val kw = params.keyWidth * params.splitScale
        var totalUnits = 0f
        for (k in row.keys) totalUnits += k.shift + k.width
        val out = ArrayList<KeySpan>(row.keys.size)
        var x = startX
        var cumUnits = 0f
        for (k in row.keys) {
            val leftUnits = cumUnits + k.shift
            if (params.splitGap > 0f && SplitConfig.isRightHalf(totalUnits, leftUnits)) {
                x += params.splitGap
            }
            val xLeft = x + k.shift * kw
            val xRight = xLeft + k.width * kw
            out.add(KeySpan(k, xLeft, xRight))
            x = xRight
            cumUnits += k.shift + k.width
        }
        return out
    }

    /**
     * Finite TalkBack ownership entries matching [keyAt]'s first-match slop rules.
     * Entries are row-major and may overlap when an a/l row has a real key before `a`: `a` owns
     * the left-edge slop, while the earlier key keeps its physical cell, and the helper resolves
     * the overlap with the same row-major first match as [keyAt].
     * Physical key rectangles remain available through [computeKeyRects] for swipe-model centers.
     *
     * Split mode is part of [keyAt]'s answer, so it is part of this one too: the walk below goes
     * through [rowSpans] and therefore carries the split gap and its compensating scale.
     */
    fun computeAccessibilityKeyRects(
        keyboard: KeyboardData,
        params: Params,
        hostWidth: Float,
    ): List<KeyRect> = computeAccessibilityKeyRects(keyboard.rows, params, hostWidth)

    internal fun computeAccessibilityKeyRects(
        rows: List<KeyboardData.Row>,
        params: Params,
        hostWidth: Float,
    ): List<KeyRect> {
        require(hostWidth >= 0f) { "hostWidth must be non-negative" }
        val rects = ArrayList<KeyRect>()
        var y = params.marginTop
        var virtualId = 0
        for (row in rows) {
            val yBottom = y + (row.shift + row.height) * params.rowHeight
            val extendLeftToEdge = rowContainsAAndL(row)
            // Split mode (phase 3 part 4) moves the right half by `splitGap` and narrows both
            // halves by `splitScale`. This walk used to apply neither -- it advanced by
            // `key.width * keyWidth` only -- so with split on, every rect on the right half sat
            // where the key is NOT drawn and TalkBack's node bounds (touch exploration, focus
            // rectangles) disagreed with [keyAt] by up to the gap. Walking [rowSpans] applies
            // both the gap and the scale, so a node owns exactly the cell the finger would hit;
            // with split off (gap 0, scale 1) this is the same accumulation as before, float
            // operation for float operation.
            val spans = rowSpans(row, params)
            // Where this key's tappable cell starts: the previous cell's right edge (row start
            // for the first key), so the inter-key gap -- and the split gap -- belong to the key
            // on its right, exactly as [keyAt] resolves them.
            var cellLeft = params.marginLeft
            for ((index, span) in spans.withIndex()) {
                val kv = span.key.keys[0]
                if (kv != null) {
                    val left = if (extendLeftToEdge && isCharacterKey(span.key, 'a')) 0f else cellLeft
                    val right = if (index == spans.lastIndex) hostWidth else span.xRight
                    val boundedLeft = left.coerceIn(0f, hostWidth)
                    val boundedRight = right.coerceIn(0f, hostWidth)
                    if (boundedRight > boundedLeft && yBottom > y) {
                        rects.add(
                            KeyRect(
                                virtualId++, span.key, kv,
                                KeyBounds(boundedLeft, y, boundedRight, yBottom),
                            )
                        )
                    }
                }
                cellLeft = span.xRight
            }
            y = yBottom
        }
        return rects
    }

    // ── helpers (verbatim from Keyboard2View) ────────────────────────────────

    /** Check if this row contains both 'a' and 'l' keys (the middle QWERTY row). */
    private fun rowContainsAAndL(row: KeyboardData.Row): Boolean {
        var hasA = false
        var hasL = false
        for (key in row.keys) {
            if (isCharacterKey(key, 'a')) hasA = true
            if (isCharacterKey(key, 'l')) hasL = true
            if (hasA && hasL) return true
        }
        return false
    }

    /** Check if a key represents the specified character. */
    private fun isCharacterKey(key: KeyboardData.Key, character: Char): Boolean {
        val kv = key.keys[0] ?: return false
        return kv.getKind() == KeyValue.Kind.Char && kv.getChar() == character
    }
}
