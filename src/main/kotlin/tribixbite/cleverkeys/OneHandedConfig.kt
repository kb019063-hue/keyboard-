package tribixbite.cleverkeys

/**
 * One-handed mode: shrink the keyboard and hug it to one edge so a single thumb can reach it.
 *
 * Phase 2 of the HeliBoard gap analysis (`docs/HELIBOARD-GAP-ANALYSIS.md`, design in
 * `docs/PHASE2-ONE-HANDED-DESIGN.md`). HeliBoard implements this in `KeyboardWrapperView` by
 * genuinely re-measuring the keyboard narrower, which is why it needs a custom `onLayout` — and
 * that re-measure lives in `KeyboardBuilder`/`KeyboardSwitcher`, the layout engine. Porting it
 * here would mean editing the code that computes every key rectangle.
 *
 * This instead applies a horizontal scale with the pivot at one edge, so layout bounds are
 * untouched: key geometry, hit-testing, the swipe engine and [KeyboardDimensionsHelper] all keep
 * working on the untransformed layout, and `ViewGroup.dispatchTransformedTouchEvent` applies the
 * inverse matrix before handing an event down, so a tap on a visually-shrunk key arrives as the
 * same local coordinate it would have unscaled. That inverse transform is the one assumption the
 * design rests on.
 *
 * ## Why this file has no Android imports
 *
 * Every value here is arithmetic on plain numbers, and a sign error — left/right swapped, pivot at
 * the wrong edge — is exactly the kind of bug that is invisible until someone types on a phone.
 * Keeping `android.view.Gravity` and friends out means [OneHandedConfigTest] exercises all of it
 * with plain JUnit, no Robolectric and no emulator. The mapping from [Side] to a `Gravity` constant
 * therefore lives in [OneHandedRoot], the only class here that touches the framework.
 */
object OneHandedConfig {

    /** Preference key: whether one-handed mode is active. */
    const val PREF_ENABLED = "one_handed_enabled"

    /** Preference key: which edge the keyboard hugs, [SIDE_LEFT] or [SIDE_RIGHT]. */
    const val PREF_SIDE = "one_handed_side"

    /** Preference key: keyboard width as a percentage of the screen, [MIN_SCALE]..[MAX_SCALE]. */
    const val PREF_SCALE = "one_handed_scale"

    const val SIDE_LEFT = "left"
    const val SIDE_RIGHT = "right"

    /**
     * Off by default. Unlike the phase-1 toolbar, which only exposed commands that already existed,
     * this changes the shape of the keyboard, so it must never surprise an existing install.
     */
    const val DEFAULT_ENABLED = false

    const val DEFAULT_SIDE = SIDE_RIGHT

    const val DEFAULT_SCALE = 85

    /**
     * Lower bound. HeliBoard allows 50%, but it scales the keyboard alone; this scales the toolbar
     * and suggestion strip with it (choice B in the design doc), and suggestion text stops being
     * comfortably readable below about 70%. The range is the reason that trade-off is acceptable.
     */
    const val MIN_SCALE = 70

    const val MAX_SCALE = 100

    /** Which edge the keyboard hugs. The empty strip is on the opposite edge. */
    enum class Side { LEFT, RIGHT }

    /**
     * Parse a persisted side. Anything unrecognised — including a null or blank value, or a
     * spelling a future version stops using — yields [Side.RIGHT], the default. Never throws: a
     * corrupt preference must not be able to stop the keyboard from laying out.
     */
    fun parseSide(raw: String?): Side =
        if (raw?.trim()?.lowercase() == SIDE_LEFT) Side.LEFT else Side.RIGHT

    /** Persisted form of [side]. */
    fun serializeSide(side: Side): String = if (side == Side.LEFT) SIDE_LEFT else SIDE_RIGHT

    /** Bring a percentage into [MIN_SCALE]..[MAX_SCALE]. A stored 0 or 500 cannot break layout. */
    fun clampScale(percent: Int): Int = percent.coerceIn(MIN_SCALE, MAX_SCALE)

    /** The scale as a fraction for `View.scaleX`, 0.70..1.00. */
    fun scaleFraction(percent: Int): Float = clampScale(percent) / 100f

    /**
     * The X pivot that hugs the content to [side].
     *
     * Scaling about x=0 holds the LEFT edge fixed, so the content shrinks toward the left and the
     * gap opens on the right; scaling about x=width holds the RIGHT edge fixed and opens the gap on
     * the left. Getting these the wrong way round is the single most likely bug in this feature —
     * it presents as "the keyboard hugs the opposite side to the one I chose" — so it is pinned by
     * name in [OneHandedConfigTest].
     *
     * @param contentWidth the content's laid-out width in px; 0 before the first layout, which
     *   yields 0f for both sides and is harmless because [OneHandedRoot] re-applies after layout.
     */
    fun pivotXFor(side: Side, contentWidth: Int): Float =
        if (side == Side.LEFT) 0f else contentWidth.toFloat()

    /**
     * Width in px of the empty strip beside the shrunken content — where the stop control sits.
     * Zero at [MAX_SCALE], which is why [OneHandedRoot] hides the control rather than drawing it
     * on top of the keys.
     *
     * Integer arithmetic rather than `contentWidth * (1f - scaleFraction(percent))`: that version
     * returns 161 for 85% of a 1080px keyboard, because `1f - 0.85f` lands just below 0.15 and
     * `toInt()` truncates. One pixel nobody would see, but the exact value is the one a test can
     * state without a tolerance.
     */
    fun gapWidth(percent: Int, contentWidth: Int): Int =
        contentWidth * (100 - clampScale(percent)) / 100

    /** The gap is on the opposite edge from the content. */
    fun gapSide(side: Side): Side = if (side == Side.LEFT) Side.RIGHT else Side.LEFT

    /** Flip to the other edge. */
    fun otherSide(side: Side): Side = gapSide(side)
}
